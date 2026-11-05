package app.otgrescue

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal read-only USB Mass Storage Bulk-Only Transport implementation. */
class UsbMassStorage(context: Context, private val device: UsbDevice) : Closeable {
    data class Capacity(val bytes: Long, val blockSize: Int)

    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var input: UsbEndpoint? = null
    private var output: UsbEndpoint? = null
    private var tag = 1

    fun open(): Capacity {
        if (!manager.hasPermission(device)) throw IOException("USB permission was not granted")
        var selected: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val candidate = device.getInterface(i)
            if (candidate.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                selected = candidate
                break
            }
        }
        val intf = selected ?: throw IOException("No USB mass-storage interface found")
        var endpointIn: UsbEndpoint? = null
        var endpointOut: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val endpoint = intf.getEndpoint(i)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (endpoint.direction == UsbConstants.USB_DIR_IN) endpointIn = endpoint
            if (endpoint.direction == UsbConstants.USB_DIR_OUT) endpointOut = endpoint
        }
        input = endpointIn ?: throw IOException("USB bulk input endpoint missing")
        output = endpointOut ?: throw IOException("USB bulk output endpoint missing")
        val conn = manager.openDevice(device) ?: throw IOException("Could not open USB device")
        connection = conn
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            throw IOException("Could not claim USB interface. Disconnect other USB apps and retry.")
        }
        usbInterface = intf

        var lastError: IOException? = null
        repeat(8) {
            try {
                val data = command(byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), 8)
                if (data.size != 8) throw IOException("Invalid READ CAPACITY response")
                val lastLba = readBigEndianInt(data, 0).toLong() and 0xffffffffL
                val block = readBigEndianInt(data, 4)
                if (block <= 0 || block > 65536) throw IOException("Unsupported USB sector size: $block")
                return Capacity((lastLba + 1) * block, block)
            } catch (error: IOException) {
                lastError = error
                try { command(ByteArray(10), 0) } catch (_: Exception) {}
                Thread.sleep(250)
            }
        }
        throw lastError ?: IOException("Could not read USB capacity")
    }

    fun read(offset: Long, requested: Int, capacity: Capacity): ByteArray {
        require(requested > 0)
        val block = capacity.blockSize
        val firstLba = offset / block
        val withinFirst = (offset % block).toInt()
        val blocks = ((withinFirst + requested + block - 1) / block)
        require(blocks in 1..65535) { "SCSI READ(10) request is too large" }
        val cdb = ByteArray(10)
        cdb[0] = 0x28 // READ(10), deliberately no WRITE commands in this class
        writeBigEndianInt(cdb, 2, firstLba.toInt())
        cdb[7] = ((blocks ushr 8) and 0xff).toByte()
        cdb[8] = (blocks and 0xff).toByte()
        val raw = command(cdb, blocks * block)
        return raw.copyOfRange(withinFirst, withinFirst + requested)
    }

    private fun command(cdb: ByteArray, dataLength: Int): ByteArray {
        val conn = connection ?: throw IOException("USB device is closed")
        val out = output ?: throw IOException("USB output endpoint is closed")
        val input = input ?: throw IOException("USB input endpoint is closed")
        val commandTag = tag++
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(0x43425355)
        cbw.putInt(commandTag)
        cbw.putInt(dataLength)
        cbw.put(if (dataLength > 0) 0x80.toByte() else 0)
        cbw.put(0)
        cbw.put(cdb.size.toByte())
        cbw.put(cdb)
        while (cbw.hasRemaining()) cbw.put(0)
        transferAll(out, cbw.array(), 5000, false)
        val data = if (dataLength > 0) {
            val buffer = ByteArray(dataLength)
            transferAll(input, buffer, 15000, true)
            buffer
        } else ByteArray(0)
        val csw = ByteArray(13)
        transferAll(input, csw, 5000, true)
        val signature = ByteBuffer.wrap(csw, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (signature != 0x53425355) throw IOException("Invalid USB CSW signature")
        if (csw[12].toInt() != 0) throw IOException("SCSI command failed with status ${csw[12]}")
        return data
    }

    private fun transferAll(endpoint: UsbEndpoint, buffer: ByteArray, timeout: Int, receiving: Boolean) {
        val conn = connection ?: throw IOException("USB device is closed")
        var offset = 0
        while (offset < buffer.size) {
            val packet = if (offset == 0) buffer else buffer.copyOfRange(offset, buffer.size)
            val result = conn.bulkTransfer(endpoint, packet, packet.size, timeout)
            if (result <= 0) throw IOException("USB bulk transfer failed (${if (receiving) "read" else "write"})")
            if (offset == 0) {
                offset = result
            } else {
                System.arraycopy(packet, 0, buffer, offset, result)
                offset += result
            }
        }
    }

    override fun close() {
        usbInterface?.let { connection?.releaseInterface(it) }
        connection?.close()
        connection = null
    }

    private fun readBigEndianInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)

    private fun writeBigEndianInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
