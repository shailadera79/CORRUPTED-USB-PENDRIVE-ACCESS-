package com.pendrivemanager.usb.fs

/**
 * Ek file ya folder, exFAT directory se parse kiya hua.
 *
 * [entryLocations] un jagahon ki list hai (disk sector + us sector ke andar byte offset)
 * jahan iski directory-entries (primary + secondary sab) padi hain — delete karte waqt
 * hume in exact jagahon par jaake "in use" bit clear karna padta hai.
 */
data class ExFatEntry(
    val name: String,
    val isDirectory: Boolean,
    val dataLength: Long,
    val firstCluster: Int,
    val noFatChain: Boolean,
    val entryLocations: List<EntryLocation>
)

data class EntryLocation(val sectorLba: Long, val offsetInSector: Int)
