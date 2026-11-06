package app.otgrescue

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.result.ActivityResult
import androidx.documentfile.provider.DocumentFile
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@CapacitorPlugin(name = "OtgRecovery")
class OtgRecoveryPlugin : Plugin() {
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
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
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    call.resolve(JSObject().put("connected", true))
                } else call.reject("USB permission denied")
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
    fun createImage(call: PluginCall) {
        if (findDevice(call.getInt("deviceId")) == null) {
            call.reject("USB drive is no longer connected")
            return
        }
        if (!ensureIdle(call)) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "otg_rescue_$stamp.img")
        }
        startActivityForResult(call, intent, "imageDestinationResult")
    }

    @ActivityCallback
    private fun imageDestinationResult(call: PluginCall, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            call.reject("Image creation cancelled")
            return
        }
        persistPermission(uri, true)
        val deviceId = call.getInt("deviceId")!!
        call.resolve(JSObject().put("started", true))
        busy.set(true)
        stopRequested.set(false)
        executor.execute { runImaging(deviceId, uri, false) }
    }

    @PluginMethod
    fun resumeImage(call: PluginCall) {
        if (findDevice(call.getInt("deviceId")) == null) {
            call.reject("Connect the same USB drive before resuming")
            return
        }
        if (!ensureIdle(call)) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
        }
        startActivityForResult(call, intent, "resumeDestinationResult")
    }

    @ActivityCallback
    private fun resumeDestinationResult(call: PluginCall, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            call.reject("Resume cancelled")
            return
        }
        persistPermission(uri, true)
        val deviceId = call.getInt("deviceId")!!
        call.resolve(JSObject().put("started", true))
        busy.set(true)
        stopRequested.set(false)
        executor.execute { runImaging(deviceId, uri, true) }
    }

    @PluginMethod
    fun scanImage(call: PluginCall) {
        if (!ensureIdle(call)) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
        }
        startActivityForResult(call, intent, "imageSourceResult")
    }

    @ActivityCallback
    private fun imageSourceResult(call: PluginCall, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            call.reject("No disk image selected")
            return
        }
        persistPermission(uri, false)
        call.resolve(JSObject().put("started", true))
        busy.set(true)
        stopRequested.set(false)
        executor.execute { runImageScan(uri) }
    }

    @PluginMethod
    fun startScan(call: PluginCall) {
        val device = findDevice(call.getInt("deviceId"))
        if (device == null) {
            call.reject("USB drive is no longer connected")
            return
        }
        if (!ensureIdle(call)) return
        call.resolve(JSObject().put("started", true))
        busy.set(true)
        stopRequested.set(false)
        executor.execute { runDirectScan(device) }
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

    @PluginMethod
    fun exportRecoveredFiles(call: PluginCall) {
        if (!ensureIdle(call)) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(call, intent, "exportDestinationResult")
    }

    @ActivityCallback
    private fun exportDestinationResult(call: PluginCall, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            call.reject("Export cancelled")
            return
        }
        persistPermission(uri, true)
        call.resolve(JSObject().put("started", true))
        busy.set(true)
        stopRequested.set(false)
        executor.execute { runExport(uri) }
    }

    private fun runExport(destination: Uri) {
        var exported = 0
        var skipped = 0
        var message = ""
        try {
            val tree = DocumentFile.fromTreeUri(context, destination)
                ?: throw IOException("Could not open export folder")
            val sources = outputRoot().walkTopDown().filter { it.isFile }.toList()
            if (sources.isEmpty()) throw IOException("No recovered files to export")
            for (source in sources) {
                if (stopRequested.get()) break
                val existing = tree.findFile(source.name)
                if (existing != null && existing.length() == source.length()) {
                    skipped++
                    continue
                }
                existing?.delete()
                val target = tree.createFile(mimeFor(source.extension), source.name)
                    ?: throw IOException("Could not create ${source.name}")
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    source.inputStream().buffered().use { input -> input.copyTo(output) }
                } ?: throw IOException("Could not write ${source.name}")
                if (target.length() != source.length()) throw IOException("Export size verification failed for ${source.name}")
                exported++
            }
            message = "Export complete · $exported copied, $skipped already present"
        } catch (error: Exception) {
            message = "Export failed: ${error.message ?: error.javaClass.simpleName}"
        } finally {
            busy.set(false)
            val event = JSObject()
            event.put("exported", exported)
            event.put("skipped", skipped)
            event.put("message", message)
            notifyListeners("exportComplete", event)
        }
    }

    private fun mimeFor(extension: String): String = when (extension.lowercase()) {
        "jpg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "application/octet-stream"
    }

    private fun runImaging(deviceId: Int, destination: Uri, resume: Boolean) {
        var reader: UsbMassStorage? = null
        var bytesWritten = 0L
        var total = 0L
        var cancelled = false
        var hash = ""
        var complete = false
        var message = ""
        var sourceDevice: UsbDevice? = null
        val preferences = context.getSharedPreferences("acquisitions", Context.MODE_PRIVATE)
        val metadataKey = "image_${destination}"
        try {
            val device = findDevice(deviceId) ?: throw IOException("USB drive disconnected")
            sourceDevice = device
            reader = UsbMassStorage(context, device)
            val capacity = reader.open()
            total = capacity.bytes
            if (resume) {
                val metadata = preferences.getString(metadataKey, null)
                    ?: throw IOException("No verified resume metadata found for this image")
                val state = org.json.JSONObject(metadata)
                if (state.getLong("total") != total || state.getString("device") != deviceFingerprint(device)) {
                    throw IOException("This is not the same source device and capacity used for the partial image")
                }
                bytesWritten = state.getLong("bytesWritten")
                val descriptor = context.contentResolver.openAssetFileDescriptor(destination, "rw")
                val actualLength = descriptor?.length ?: -1L
                descriptor?.close()
                if (actualLength != bytesWritten) throw IOException("Partial image length changed; resume refused")
                if (sha256(destination) != state.getString("partialSha256")) {
                    throw IOException("Partial image checksum mismatch; resume refused")
                }
            } else {
                preferences.edit().remove(metadataKey).apply()
            }
            val acquisitionDigest = MessageDigest.getInstance("SHA-256")
            if (resume && bytesWritten > 0) updateDigestFromUri(acquisitionDigest, destination)
            val descriptor = context.contentResolver.openFileDescriptor(destination, "rw")
                ?: throw IOException("Could not open destination image")
            descriptor.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { fileOutput ->
                    if (resume) fileOutput.channel.position(bytesWritten) else fileOutput.channel.truncate(0)
                    val output = fileOutput.buffered(1024 * 1024)
                    var lastProgress = 0L
                    while (bytesWritten < total && !stopRequested.get()) {
                        val length = minOf(512 * 1024L, total - bytesWritten).toInt()
                        val bytes = reader.read(bytesWritten, length, capacity)
                        output.write(bytes)
                        acquisitionDigest.update(bytes)
                        bytesWritten += bytes.size
                        val now = System.currentTimeMillis()
                        if (now - lastProgress > 750 || bytesWritten == total) {
                            notifyImageProgress(bytesWritten, total)
                            lastProgress = now
                        }
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            cancelled = stopRequested.get() && bytesWritten < total
            complete = !cancelled && bytesWritten == total
            hash = sha256(destination)
            if (complete) {
                val acquisitionHash = acquisitionDigest.digest().joinToString("") { "%02x".format(it) }
                if (hash != acquisitionHash) throw IOException("Image verification mismatch; recovered image does not match acquired data")
                preferences.edit().remove(metadataKey).apply()
                message = "Image complete and SHA-256 verified"
            } else {
                saveResumeState(preferences, metadataKey, destination, device, total, bytesWritten, hash)
                message = "Image incomplete · verified resume is available"
            }
        } catch (error: Exception) {
            complete = false
            message = "Imaging stopped: ${error.message ?: error.javaClass.simpleName}"
            val source = sourceDevice
            if (source != null && total > 0 && bytesWritten > 0 && bytesWritten < total) {
                try {
                    val partialHash = sha256(destination)
                    saveResumeState(preferences, metadataKey, destination, source, total, bytesWritten, partialHash)
                    message += " · Verified resume is available"
                } catch (_: Exception) {}
            }
        } finally {
            reader?.close()
            busy.set(false)
            val event = JSObject()
            event.put("bytesWritten", bytesWritten.toDouble())
            event.put("totalBytes", total.toDouble())
            event.put("complete", complete && hash.isNotEmpty())
            event.put("cancelled", cancelled)
            event.put("sha256", if (complete) hash else "")
            event.put("message", message)
            notifyListeners("imageComplete", event)
        }
    }

    private fun saveResumeState(preferences: android.content.SharedPreferences, key: String, uri: Uri, device: UsbDevice, total: Long, bytes: Long, partialHash: String) {
        val state = org.json.JSONObject()
        state.put("uri", uri.toString())
        state.put("device", deviceFingerprint(device))
        state.put("total", total)
        state.put("bytesWritten", bytes)
        state.put("partialSha256", partialHash)
        preferences.edit().putString(key, state.toString()).apply()
    }

    private fun deviceFingerprint(device: UsbDevice): String =
        "${device.vendorId}:${device.productId}:${if (Build.VERSION.SDK_INT >= 21) device.serialNumber ?: "" else ""}"

    private fun sha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        updateDigestFromUri(digest, uri)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateDigestFromUri(digest: MessageDigest, uri: Uri) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        } ?: throw IOException("Could not verify destination image")
    }

    private fun runDirectScan(device: UsbDevice) {
        var reader: UsbMassStorage? = null
        try {
            reader = UsbMassStorage(context, device)
            val capacity = reader.open()
            performCarving(capacity.bytes) { position, length -> reader.read(position, length, capacity) }
        } catch (error: Exception) {
            completeScan(0, false, "Scan failed: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            reader?.close()
        }
    }

    private fun runImageScan(uri: Uri) {
        try {
            val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
                ?: throw IOException("Could not open selected image")
            descriptor.use { asset ->
                val total = asset.length
                if (total <= 0) throw IOException("Selected file has no readable size")
                val rawInput = FileInputStream(asset.fileDescriptor)
                rawInput.channel.position(asset.startOffset)
                rawInput.buffered(1024 * 1024).use { input ->
                    performCarving(total) { _, length ->
                        val data = ByteArray(length)
                        var offset = 0
                        while (offset < length) {
                            val count = input.read(data, offset, length - offset)
                            if (count < 0) break
                            offset += count
                        }
                        if (offset == 0) ByteArray(0) else data.copyOf(offset)
                    }
                }
            }
        } catch (error: Exception) {
            completeScan(0, false, "Image scan failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun performCarving(total: Long, readBlock: (Long, Int) -> ByteArray) {
        var found = 0
        val directory = File(outputRoot(), "scan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}")
        if (!directory.mkdirs()) throw IOException("Could not create recovery output folder")
        val carver = FileCarver(directory) { file ->
            found++
            notifyListeners("fileRecovered", fileToJson(file.name, file.path, file.size, file.kind, file.complete))
        }
        var position = 0L
        var lastProgress = 0L
        while (position < total && !stopRequested.get()) {
            val length = minOf(512 * 1024L, total - position).toInt()
            val bytes = readBlock(position, length)
            if (bytes.isEmpty()) throw IOException("Unexpected end of image")
            carver.consume(bytes)
            position += bytes.size
            val now = System.currentTimeMillis()
            if (now - lastProgress > 750 || position == total) {
                notifyScanProgress(position, total, found)
                lastProgress = now
            }
        }
        carver.finish()
        val cancelled = stopRequested.get() && position < total
        completeScan(found, cancelled, if (cancelled) "Scan stopped · $found files recovered" else "Scan complete · $found files recovered")
    }

    private fun completeScan(found: Int, cancelled: Boolean, message: String) {
        busy.set(false)
        val result = JSObject()
        result.put("filesFound", found)
        result.put("cancelled", cancelled)
        result.put("message", message)
        notifyListeners("scanComplete", result)
    }

    private fun ensureIdle(call: PluginCall): Boolean {
        if (busy.get()) {
            call.reject("Another acquisition or scan is already running")
            return false
        }
        return true
    }

    private fun notifyImageProgress(bytes: Long, total: Long) {
        val event = JSObject()
        event.put("bytesWritten", bytes.toDouble())
        event.put("totalBytes", total.toDouble())
        event.put("percent", if (total > 0) bytes.toDouble() * 100.0 / total.toDouble() else 0.0)
        notifyListeners("imageProgress", event)
    }

    private fun notifyScanProgress(bytes: Long, total: Long, found: Int) {
        val progress = JSObject()
        progress.put("bytesScanned", bytes.toDouble())
        progress.put("totalBytes", total.toDouble())
        progress.put("filesFound", found)
        progress.put("percent", if (total > 0) bytes.toDouble() * 100.0 / total.toDouble() else 0.0)
        notifyListeners("scanProgress", progress)
    }

    private fun persistPermission(uri: Uri, write: Boolean) {
        val mode = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        try { context.contentResolver.takePersistableUriPermission(uri, mode) } catch (_: Exception) {}
    }

    private fun findDevice(deviceId: Int?): UsbDevice? {
        if (deviceId == null) return null
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values.firstOrNull { it.deviceId == deviceId && isMassStorage(it) }
    }

    private fun isMassStorage(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) if (device.getInterface(i).interfaceClass == 8) return true
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
