package com.pendrivemanager.usb.driver

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * USB Mass Storage "Bulk-Only Transport" (BOT) protocol implement karta hai:
 * har command ek "Command Block Wrapper" (CBW) ke through bhejte hain, phir data
 * transfer hota hai, phir device ek "Command Status Wrapper" (CSW) bhejta hai
 * confirm karne ke liye ki command successful thi ya nahi.
 *
 * Yeh exactly wahi standard hai jo Windows/Linux/Paragon jaisi tools bhi internally
 * use karti hain pendrive se baat karne ke liye.
 */
class ScsiBlockDevice(private val transport: UsbBulkTransport) {

    companion object {
        // Ek USB command mein zyada se zyada itne sectors — bade transfers ko isi
        // hisaab se tukdo mein todte hain, taaki har tarah ki pendrive handle ho jaye.
        private const val MAX_SECTORS_PER_TRANSFER = 128
    }

    var blockSize: Int = 512
        private set
    var totalBlocks: Long = 0
        private set

    private var tagCounter = 1

    fun init() {
        // Kai drives pehli command par "not ready" bolti hain, isliye thoda retry karte hain.
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                testUnitReady()
                readCapacity()
                return
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(150)
            }
        }
        throw IOException("Drive se connect nahi ho paya: ${lastError?.message}")
    }

    private fun nextTag(): Int {
        tagCounter++
        return tagCounter
    }

    /** CBW bhejta hai. direction: true = data hume aayega (IN), false = hum bhejenge (OUT) */
    private fun sendCommandBlock(cdb: ByteArray, dataLength: Int, deviceToHost: Boolean) {
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(0x43425355) // "USBC" signature
        cbw.putInt(nextTag())
        cbw.putInt(dataLength)
        cbw.put(if (deviceToHost) 0x80.toByte() else 0x00)
        cbw.put(0) // LUN = 0
        cbw.put(cdb.size.toByte())
        cbw.put(cdb)
        cbw.put(ByteArray(16 - cdb.size)) // CBWCB hamesha 16 bytes tak pad hota hai
        val sent = transport.bulkOut(cbw.array())
        if (sent < 0) throw IOException("Command bhejne mein fail")
    }

    private fun readStatus() {
        val buf = ByteArray(13)
        val read = transport.bulkIn(buf, 0, 13)
        if (read < 13) throw IOException("Status (CSW) read nahi hua")
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val signature = bb.int
        bb.int // tag - hum ignore kar rahe hain
        bb.int // residue
        val status = bb.get().toInt()
        if (signature != 0x53425355) throw IOException("Galat CSW signature")
        if (status != 0) throw IOException("Drive ne command reject ki (status=$status)")
    }

    fun testUnitReady() {
        sendCommandBlock(byteArrayOf(0x00, 0, 0, 0, 0, 0), 0, deviceToHost = true)
        readStatus()
    }

    fun readCapacity() {
        sendCommandBlock(byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), 8, deviceToHost = true)
        val buf = ByteArray(8)
        transport.bulkIn(buf, 0, 8)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
        val lastLba = bb.int.toLong() and 0xFFFFFFFFL
        blockSize = bb.int
        totalBlocks = lastLba + 1
        readStatus()
    }

    /** [count] sectors [startLba] se padhta hai aur raw bytes return karta hai. Bade requests khud-b-khud chhote, safe pieces mein todi jaati hain. */
    fun readSectors(startLba: Long, count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)
        if (count <= MAX_SECTORS_PER_TRANSFER) return readSectorsOnce(startLba, count)

        val result = ByteArray(count * blockSize)
        var done = 0
        while (done < count) {
            val chunk = minOf(MAX_SECTORS_PER_TRANSFER, count - done)
            val data = readSectorsOnce(startLba + done, chunk)
            System.arraycopy(data, 0, result, done * blockSize, data.size)
            done += chunk
        }
        return result
    }

    private fun readSectorsOnce(startLba: Long, count: Int): ByteArray {
        val length = count * blockSize
        val cdb = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        cdb.put(0x28.toByte()) // READ (10)
        cdb.put(0)
        cdb.putInt(startLba.toInt())
        cdb.put(0)
        cdb.putShort(count.toShort())
        cdb.put(0)
        sendCommandBlock(cdb.array(), length, deviceToHost = true)
        val data = ByteArray(length)
        var readSoFar = 0
        while (readSoFar < length) {
            val n = transport.bulkIn(data, readSoFar, length - readSoFar)
            if (n <= 0) throw IOException("Sector read fail hua (LBA=$startLba)")
            readSoFar += n
        }
        readStatus()
        return data
    }

    /** [data] ko [startLba] se likhta hai. data.size block size ka exact multiple hona chahiye. */
    fun writeSectors(startLba: Long, data: ByteArray) {
        val count = data.size / blockSize
        val cdb = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        cdb.put(0x2A.toByte()) // WRITE (10)
        cdb.put(0)
        cdb.putInt(startLba.toInt())
        cdb.put(0)
        cdb.putShort(count.toShort())
        cdb.put(0)
        sendCommandBlock(cdb.array(), data.size, deviceToHost = false)
        val sent = transport.bulkOut(data)
        if (sent < data.size) throw IOException("Sector write fail hua (LBA=$startLba)")
        readStatus()
    }
}
