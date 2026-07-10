package com.pendrivemanager.usb.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * Sabse niche ki layer: USB pendrive ke saath raw bulk data bhejna/receive karna.
 *
 * Pendrives "Mass Storage class" (class=8), "SCSI transparent command set" (subclass=6),
 * "Bulk-Only Transport" (protocol=80) use karti hain — yeh ek bahut purana aur stable
 * USB standard hai (2000 se same hai), isliye yeh part sabse zyada reliable hai.
 */
class UsbBulkTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint
) {
    companion object {
        private const val TIMEOUT_MS = 8000

        fun open(usbManager: UsbManager, device: UsbDevice): UsbBulkTransport? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    intf.interfaceSubclass == 6 &&
                    intf.interfaceProtocol == 80
                ) {
                    var inEp: UsbEndpoint? = null
                    var outEp: UsbEndpoint? = null
                    for (e in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(e)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                        }
                    }
                    if (inEp != null && outEp != null) {
                        val conn = usbManager.openDevice(device) ?: return null
                        if (!conn.claimInterface(intf, true)) return null
                        return UsbBulkTransport(conn, intf, inEp, outEp)
                    }
                }
            }
            return null
        }
    }

    fun bulkOut(data: ByteArray): Int =
        connection.bulkTransfer(outEndpoint, data, data.size, TIMEOUT_MS)

    /**
     * [buffer] mein [offset] se shuru karke data bharta hai.
     * Yeh offset zaroori hai kyunki bade transfers (jaise ek poora bada cluster)
     * kabhi-kabhi ek hi USB call mein poore nahi aate — dobara call karni padti hai,
     * aur usme pichhla data overwrite nahi hona chahiye.
     */
    fun bulkIn(buffer: ByteArray, offset: Int, length: Int): Int =
        connection.bulkTransfer(inEndpoint, buffer, offset, length, TIMEOUT_MS)

    fun close() {
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Exception) {
        }
        connection.close()
    }
}
