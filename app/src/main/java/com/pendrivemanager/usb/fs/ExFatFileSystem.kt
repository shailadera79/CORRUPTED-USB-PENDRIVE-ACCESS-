package com.pendrivemanager.usb.fs

import com.pendrivemanager.usb.driver.ScsiBlockDevice
import java.io.IOException
import java.io.OutputStream

private fun readU16LE(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

private fun readU32LE(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)

private fun readU64LE(b: ByteArray, off: Int): Long {
    var v = 0L
    for (k in 0 until 8) {
        v = v or ((b[off + k].toLong() and 0xFFL) shl (8 * k))
    }
    return v
}

/**
 * exFAT filesystem reader/writer (read + soft-delete), Microsoft ke publicly
 * documented exFAT specification ke hisaab se likha gaya hai, seedha raw sectors
 * (ScsiBlockDevice) par kaam karta hai.
 *
 * Scope (jaan-boojh kar limited rakha hai, taaki galti se drive corrupt na ho):
 *  - Directories browse karna         -> supported
 *  - Files read karna (copy off)      -> supported
 *  - Files delete karna                -> supported ("soft delete": entry hide
 *                                          hoti hai, clusters turant free nahi hote)
 *  - Naye files banana / bade write   -> NAHI support kiya (allocation bitmap
 *                                          manage karna risky hai bina real device
 *                                          par test kiye)
 */
class ExFatFileSystem private constructor(
    private val scsi: ScsiBlockDevice,
    private val partitionStartLba: Long,
    private val bytesPerSector: Int,
    private val sectorsPerCluster: Int,
    private val fatOffsetSectors: Long,
    private val clusterHeapOffsetSectors: Long,
    val rootDirCluster: Int
) {

    companion object {
        fun mount(scsi: ScsiBlockDevice): ExFatFileSystem {
            scsi.init()

            // Case 1: "superfloppy" - poori drive hi ek filesystem hai, koi partition table nahi.
            val sector0 = scsi.readSectors(0, 1)
            if (isExFatBootSector(sector0)) {
                return fromBootSector(scsi, 0L, sector0)
            }

            // Case 2: MBR partition table check karo.
            val hasMbrSignature =
                (sector0[510].toInt() and 0xFF) == 0x55 && (sector0[511].toInt() and 0xFF) == 0xAA
            if (hasMbrSignature) {
                for (i in 0 until 4) {
                    val entryOff = 446 + i * 16
                    val type = sector0[entryOff + 4].toInt() and 0xFF
                    // 0x07 = "IFS" partition type, NTFS/exFAT/HPFS sab isko use karte hain,
                    // isliye boot sector padh kar hi confirm karte hain ki asal mein exFAT hai.
                    if (type == 0x07) {
                        val startLba = readU32LE(sector0, entryOff + 8).toLong() and 0xFFFFFFFFL
                        if (startLba > 0) {
                            val bootSector = scsi.readSectors(startLba, 1)
                            if (isExFatBootSector(bootSector)) {
                                return fromBootSector(scsi, startLba, bootSector)
                            }
                        }
                    }
                }
            }

            throw IOException(
                "exFAT filesystem nahi mila. Ho sakta hai drive exFAT na ho, ya GPT-partitioned ho (abhi sirf MBR partitions support hain)."
            )
        }

        private fun isExFatBootSector(sector: ByteArray): Boolean {
            if (sector.size < 512) return false
            val signature = String(sector, 3, 8, Charsets.US_ASCII)
            return signature == "EXFAT   "
        }

        private fun fromBootSector(scsi: ScsiBlockDevice, partitionStartLba: Long, boot: ByteArray): ExFatFileSystem {
            val fatOffset = readU32LE(boot, 80).toLong() and 0xFFFFFFFFL
            val clusterHeapOffset = readU32LE(boot, 88).toLong() and 0xFFFFFFFFL
            val rootCluster = readU32LE(boot, 96)
            val bytesPerSectorShift = boot[108].toInt() and 0xFF
            val sectorsPerClusterShift = boot[109].toInt() and 0xFF
            val bytesPerSector = 1 shl bytesPerSectorShift
            val sectorsPerCluster = 1 shl sectorsPerClusterShift

            return ExFatFileSystem(
                scsi, partitionStartLba, bytesPerSector, sectorsPerCluster,
                fatOffset, clusterHeapOffset, rootCluster
            )
        }
    }

    private fun clusterToLba(cluster: Int): Long =
        partitionStartLba + clusterHeapOffsetSectors + (cluster - 2).toLong() * sectorsPerCluster

    private fun nextClusterRaw(cluster: Int): Int {
        val fatByteOffset = cluster.toLong() * 4
        val sectorIndex = fatByteOffset / bytesPerSector
        val offsetInSector = (fatByteOffset % bytesPerSector).toInt()
        val lba = partitionStartLba + fatOffsetSectors + sectorIndex
        val sector = scsi.readSectors(lba, 1)
        return readU32LE(sector, offsetInSector)
    }

    private fun isEndOfChain(value: Int): Boolean {
        val unsigned = value.toLong() and 0xFFFFFFFFL
        return unsigned >= 0xFFFFFFF8L || unsigned == 0L
    }

    private fun clusterChain(startCluster: Int): List<Int> {
        val result = mutableListOf<Int>()
        val visited = HashSet<Int>()
        var current = startCluster
        while (current >= 2 && visited.add(current)) {
            result.add(current)
            val next = nextClusterRaw(current)
            if (isEndOfChain(next)) break
            current = next
        }
        return result
    }

    /** Ek directory ke saare 32-byte records + unki disk-par exact location padhta hai. */
    private fun readDirectoryData(startCluster: Int): Pair<ByteArray, List<EntryLocation>> {
        val clusters = clusterChain(startCluster)
        val clusterSizeBytes = sectorsPerCluster * bytesPerSector
        val allBytes = ByteArray(clusters.size * clusterSizeBytes)
        val locations = ArrayList<EntryLocation>(allBytes.size / 32)

        var pos = 0
        for (cluster in clusters) {
            val lbaBase = clusterToLba(cluster)
            val data = scsi.readSectors(lbaBase, sectorsPerCluster)
            System.arraycopy(data, 0, allBytes, pos, data.size)

            var offsetInCluster = 0
            while (offsetInCluster < clusterSizeBytes) {
                val sectorIndex = offsetInCluster / bytesPerSector
                val offsetInSector = offsetInCluster % bytesPerSector
                locations.add(EntryLocation(lbaBase + sectorIndex, offsetInSector))
                offsetInCluster += 32
            }
            pos += data.size
        }
        return Pair(allBytes, locations)
    }

    private fun parseDirectoryEntries(bytes: ByteArray, locations: List<EntryLocation>): List<ExFatEntry> {
        val entries = mutableListOf<ExFatEntry>()
        val numRecords = bytes.size / 32
        var i = 0

        while (i < numRecords) {
            val base = i * 32
            val entryType = bytes[base].toInt() and 0xFF

            if (entryType == 0x00) break // directory ka end

            if (entryType == 0x85) { // File Directory Entry (primary)
                val secondaryCount = bytes[base + 1].toInt() and 0xFF
                val fileAttributes = readU16LE(bytes, base + 4)
                val isDirectory = (fileAttributes and 0x10) != 0

                if (i + 1 >= numRecords || (bytes[(i + 1) * 32].toInt() and 0xFF) != 0xC0) {
                    i += 1
                    continue
                }
                val streamBase = (i + 1) * 32
                val generalFlags = bytes[streamBase + 1].toInt() and 0xFF
                val noFatChain = (generalFlags and 0x02) != 0
                val nameLength = bytes[streamBase + 3].toInt() and 0xFF
                val firstCluster = readU32LE(bytes, streamBase + 20)
                val dataLength = readU64LE(bytes, streamBase + 24)

                val usedRecordIndices = mutableListOf(i, i + 1)
                val nameBuilder = StringBuilder()
                var remainingChars = nameLength
                var idx = i + 2
                while (remainingChars > 0 && idx < numRecords) {
                    val nb = idx * 32
                    if ((bytes[nb].toInt() and 0xFF) != 0xC1) break
                    val charsInThis = minOf(15, remainingChars)
                    for (c in 0 until charsInThis) {
                        val code = readU16LE(bytes, nb + 2 + c * 2)
                        nameBuilder.append(code.toChar())
                    }
                    remainingChars -= charsInThis
                    usedRecordIndices.add(idx)
                    idx++
                }

                val locs = usedRecordIndices.map { locations[it] }
                entries.add(
                    ExFatEntry(
                        name = nameBuilder.toString(),
                        isDirectory = isDirectory,
                        dataLength = dataLength,
                        firstCluster = firstCluster,
                        noFatChain = noFatChain,
                        entryLocations = locs
                    )
                )
                i += 1 + secondaryCount
            } else {
                // Bitmap (0x81), Upcase table (0x82), Volume label (0x83), ya
                // deleted entries (high bit clear) — inhe list mein nahi dikhate.
                i += 1
            }
        }
        return entries
    }

    fun listDirectory(startCluster: Int): List<ExFatEntry> {
        val (bytes, locations) = readDirectoryData(startCluster)
        return parseDirectoryEntries(bytes, locations)
    }

    /** File ka data [out] mein likhta hai (copy-to-phone jaisे kaam ke liye). */
    fun readFile(entry: ExFatEntry, out: OutputStream) {
        val clusterSizeBytes = sectorsPerCluster * bytesPerSector
        var remaining = entry.dataLength

        if (entry.noFatChain) {
            // Contiguous file - ek saath bahut se clusters ek hi baar mein padh sakte hain,
            // isse bade files (GB size) bhi jaldi aur reliably copy hoti hain.
            var cluster = entry.firstCluster
            while (remaining > 0) {
                val clustersNeeded = ((remaining + clusterSizeBytes - 1) / clusterSizeBytes)
                val batchClusters = minOf(clustersNeeded, 512L).toInt()
                val data = scsi.readSectors(clusterToLba(cluster), batchClusters * sectorsPerCluster)
                val take = minOf(remaining, data.size.toLong()).toInt()
                out.write(data, 0, take)
                remaining -= take
                cluster += batchClusters
            }
        } else {
            for (cluster in clusterChain(entry.firstCluster)) {
                if (remaining <= 0) break
                val data = scsi.readSectors(clusterToLba(cluster), sectorsPerCluster)
                val take = minOf(remaining, data.size.toLong()).toInt()
                out.write(data, 0, take)
                remaining -= take
            }
        }
    }

    /**
     * "Soft delete": sirf directory entry ka InUse bit clear karta hai, isliye file
     * list se gayab ho jaati hai. Cluster/FAT/bitmap jaan-boojh kar touch nahi karte,
     * taaki kisi bug ki wajah se baaki drive corrupt hone ka risk na ho.
     */
    fun deleteEntry(entry: ExFatEntry) {
        for (loc in entry.entryLocations) {
            val sector = scsi.readSectors(loc.sectorLba, 1)
            sector[loc.offsetInSector] = (sector[loc.offsetInSector].toInt() and 0x7F).toByte()
            scsi.writeSectors(loc.sectorLba, sector)
        }
    }
}
