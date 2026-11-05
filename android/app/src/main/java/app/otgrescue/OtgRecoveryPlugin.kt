package app.otgrescue

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@CapacitorPlugin(name = "OtgRecovery")
class OtgRecoveryPlugin : Plugin {
    private val executor = Executors.newSingleThreadExecutor()
    private val scanning = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val actionUsbPermission get() = "${context.packageName}.USB_PERMISSION"

    @PluginMethod
    fun listDrives(call: PluginCall) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drives = com.getcapacitor.JSArray()
        manager.deviceList.values.forEach { device ->
            if (isMassStorage(device)) {
                val item = JSObject()
                item.put("deviceId", device.deviceId)
                item.put("name", device.deviceName)
                item.put("manufacturer", if (Build.VERSION.SDK_INT >= 21) device.manufacturerName ?: "" else "")
                item.put("product", if (Build.VERSION.SDK_INT >= 21) device.productName ?: "USB drive" else "USB drive")
                drives.put(item)
            }
        }
        call.resolve(JSObject().put("drives", drives))
    }

    @PluginMethod
    fun connect(call: PluginCall) {
        val device = findDevice(call.getInt("deviceId"))
        if (device == null) {
            call.reject("USB drive is no longer connected")
            return
        }
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) {
            call.resolve(JSObject().put("connected", true))
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != actionUsbPermission) return
                try { context.unregisterReceiver(this) } catch (_: Exception) {}
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) call.resolve(JSObject().put("connected", true))
                else call.reject("USB permission denied")
            }
        }
        val filter = IntentFilter(actionUsbPermission)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val permissionIntent = PendingIntent.getBroadcast(context, device.deviceId, Intent(actionUsbPermission), flags)
        manager.requestPermission(device, permissionIntent)
    }

    @PluginMethod
    fun startScan(call: PluginCall) {
        val device = findDevice(call.getInt("deviceId"))
        if (device == null) {
            call.reject("USB drive is no longer connected")
            return
        }
        if (!scanning.compareAndSet(false, true)) {
            call.reject("A scan is already running")
            return
        }
        stopRequested.set(false)
        val maxBytes = call.getLong("maxBytes") ?: 0L
        call.resolve(JSObject().put("started", true))
        executor.execute { runScan(device, maxBytes) }
    }

    @PluginMethod
    fun stopScan(call: PluginCall) {
        stopRequested.set(true)
        call.resolve(JSObject().put("stopped", true))
    }

    @PluginMethod
    fun listRecoveredFiles(call: PluginCall) {
        val array = com.getcapacitor.JSArray()
        outputRoot().walkTopDown().filter { it.isFile }.forEach { file ->
            array.put(fileToJson(file.name, file.absolutePath, file.length(), file.extension, true))
        }
        call.resolve(JSObject().put("files", array))
    }

    private fun runScan(device: UsbDevice, maxBytes: Long) {
        var found = 0
        var cancelled = false
        var message: String
        var reader: UsbMassStorage? = null
        try {
            reader = UsbMassStorage(context, device)
            val capacity = reader.open()
            val total = if (maxBytes > 0) minOf(maxBytes, capacity.bytes) else capacity.bytes
            if (total <= 0) throw IllegalStateException("USB drive reports zero capacity")
            val directory = File(outputRoot(), "scan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}")
            if (!directory.mkdirs()) throw IllegalStateException("Could not create recovery output folder")
            val carver = FileCarver(directory) { file ->
                found++
                notifyListeners("fileRecovered", fileToJson(file.name, file.path, file.size, file.kind, file.complete))
            }
            var position = 0L
            var lastProgress = 0L
            val chunkSize = 512 * 1024
            while (position < total && !stopRequested.get()) {
                val length = minOf(chunkSize.toLong(), total - position).toInt()
                val bytes = reader.read(position, length, capacity)
                carver.consume(bytes)
                position += bytes.size
                val now = System.currentTimeMillis()
                if (now - lastProgress > 750 || position == total) {
                    notifyProgress(position, total, found)
                    lastProgress = now
                }
            }
            carver.finish()
            cancelled = stopRequested.get()
            message = if (cancelled) "Scan stopped · $found files recovered" else "Scan complete · $found files recovered"
        } catch (error: Exception) {
            message = "Scan failed: ${error.message ?: error.javaClass.simpleName}"
        } finally {
            reader?.close()
            scanning.set(false)
            val result = JSObject()
            result.put("filesFound", found)
            result.put("cancelled", cancelled)
            result.put("message", message)
            notifyListeners("scanComplete", result)
        }
    }

    private fun notifyProgress(bytes: Long, total: Long, found: Int) {
        val progress = JSObject()
        progress.put("bytesScanned", bytes.toDouble())
        progress.put("totalBytes", total.toDouble())
        progress.put("filesFound", found)
        progress.put("percent", if (total > 0) bytes.toDouble() * 100.0 / total.toDouble() else 0.0)
        notifyListeners("scanProgress", progress)
    }

    private fun findDevice(deviceId: Int?): UsbDevice? {
        if (deviceId == null) return null
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values.firstOrNull { it.deviceId == deviceId && isMassStorage(it) }
    }

    private fun isMassStorage(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == 8) return true
        }
        return false
    }

    private fun outputRoot(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "recovered").apply { mkdirs() }

    private fun fileToJson(name: String, path: String, size: Long, kind: String, complete: Boolean): JSObject {
        val result = JSObject()
        result.put("name", name)
        result.put("path", path)
        result.put("size", size.toDouble())
        result.put("kind", kind)
        result.put("complete", complete)
        return result
    }
}
