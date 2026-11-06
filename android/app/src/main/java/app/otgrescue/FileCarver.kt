package app.otgrescue

import java.io.File
import java.io.FileOutputStream

data class CarvedFile(
    val name: String,
    val path: String,
    val size: Long,
    val kind: String,
    val complete: Boolean
)

/** Conservative contiguous signature carver. It only writes to the output directory. */
class FileCarver(private val outputDirectory: File, private val onFile: (CarvedFile) -> Unit) {
    private data class Signature(
        val kind: String,
        val extension: String,
        val header: ByteArray,
        val footer: ByteArray?,
        val maxBytes: Long,
        val afterFooter: Int = 0
    )
    private class Active(
        val signature: Signature,
        val file: File,
        val stream: FileOutputStream,
        var size: Long = 0,
        var tail: ByteArray = ByteArray(0)
    )

    private val signatures = listOf(
        Signature("jpg", "jpg", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), byteArrayOf(0xff.toByte(), 0xd9.toByte()), 64L * 1024 * 1024),
        Signature("png", "png", byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), byteArrayOf(0x49, 0x45, 0x4e, 0x44, 0xae.toByte(), 0x42, 0x60, 0x82.toByte()), 64L * 1024 * 1024),
        Signature("gif", "gif", "GIF8".toByteArray(), byteArrayOf(0x00, 0x3b), 32L * 1024 * 1024),
        Signature("pdf", "pdf", "%PDF-".toByteArray(), "%%EOF".toByteArray(), 128L * 1024 * 1024),
        Signature("zip", "zip", byteArrayOf(0x50, 0x4b, 0x03, 0x04), byteArrayOf(0x50, 0x4b, 0x05, 0x06), 256L * 1024 * 1024, 18)
    )
    private var active: Active? = null
    private var carry = ByteArray(0)
    private var sequence = 0
    var filesFound = 0
        private set

    fun consume(data: ByteArray) {
        var position = 0
        while (position < data.size) {
            val current = active
            if (current != null) {
                val footer = current.signature.footer!!
                val remaining = data.copyOfRange(position, data.size)
                val search = current.tail + remaining
                val match = indexOf(search, footer, 0)
                if (match >= 0 && match + footer.size + current.signature.afterFooter <= search.size) {
                    val fromNewData = match + footer.size + current.signature.afterFooter - current.tail.size
                    if (fromNewData > 0) write(current, remaining, 0, minOf(fromNewData, remaining.size))
                    close(true)
                    position += maxOf(0, fromNewData)
                    carry = ByteArray(0)
                    continue
                }
                write(current, remaining, 0, remaining.size)
                current.tail = search.takeLast(maxOf(footer.size + current.signature.afterFooter, 1)).toByteArray()
                position = data.size
                if (current.size >= current.signature.maxBytes) close(false)
            } else {
                val remaining = data.copyOfRange(position, data.size)
                val search = carry + remaining
                var bestIndex = -1
                var bestSignature: Signature? = null
                for (signature in signatures) {
                    val found = indexOf(search, signature.header, 0)
                    if (found >= 0 && (bestIndex < 0 || found < bestIndex)) {
                        bestIndex = found
                        bestSignature = signature
                    }
                }
                if (bestIndex < 0 || bestSignature == null) {
                    carry = search.takeLast(15).toByteArray()
                    break
                }
                val inRemaining = bestIndex - carry.size
                val file = File(outputDirectory, "recovered_${String.format("%04d", ++sequence)}.${bestSignature.extension}")
                val stream = FileOutputStream(file)
                val newActive = Active(bestSignature, file, stream)
                active = newActive
                if (inRemaining < 0) {
                    val prefix = carry.copyOfRange(bestIndex, carry.size)
                    write(newActive, prefix, 0, prefix.size)
                    newActive.tail = prefix
                } else {
                    position += inRemaining
                }
                carry = ByteArray(0)
            }
        }
    }

    fun finish() {
        if (active != null) close(false)
    }

    private fun write(active: Active, data: ByteArray, offset: Int, length: Int) {
        val permitted = minOf(length.toLong(), active.signature.maxBytes - active.size).toInt()
        if (permitted <= 0) return
        active.stream.write(data, offset, permitted)
        active.size += permitted
    }

    private fun close(complete: Boolean) {
        val current = active ?: return
        current.stream.flush()
        current.stream.close()
        active = null
        if (current.size < 32) {
            current.file.delete()
            return
        }
        var outputFile = current.file
        var outputKind = current.signature.kind
        var isComplete = complete
        if (outputKind == "zip") {
            try {
                java.util.zip.ZipFile(outputFile).use { archive ->
                    val names = archive.entries().toList().map { it.name }
                    val extension = when {
                        "word/document.xml" in names -> "docx"
                        "xl/workbook.xml" in names -> "xlsx"
                        "ppt/presentation.xml" in names -> "pptx"
                        else -> "zip"
                    }
                    if (extension != "zip") {
                        val renamed = File(outputFile.parentFile, outputFile.nameWithoutExtension + "." + extension)
                        if (outputFile.renameTo(renamed)) outputFile = renamed
                        outputKind = extension
                    }
                }
            } catch (_: Exception) {
                isComplete = false // A ZIP footer alone is not proof of a valid archive.
            }
        }
        filesFound++
        onFile(CarvedFile(outputFile.name, outputFile.absolutePath, outputFile.length(), outputKind, isComplete))
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, start: Int): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in start..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
