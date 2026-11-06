package app.otgrescue

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileCarverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun recoversJpgWhenSignatureCrossesReadBoundary() {
        val output = temporaryFolder.newFolder("jpg-output")
        val results = mutableListOf<CarvedFile>()
        val carver = FileCarver(output) { results.add(it) }
        val jpg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()) +
            ByteArray(64) { 7 } + byteArrayOf(0xff.toByte(), 0xd9.toByte())
        carver.consume(jpg.copyOfRange(0, 2))
        carver.consume(jpg.copyOfRange(2, jpg.size))
        carver.finish()
        assertEquals(1, results.size)
        assertEquals(true, results.single().complete)
        assertArrayEquals(jpg, File(results.single().path).readBytes())
    }

    @Test
    fun includesCompleteZipEndRecord() {
        val output = temporaryFolder.newFolder("zip-output")
        val results = mutableListOf<CarvedFile>()
        val carver = FileCarver(output) { results.add(it) }
        val zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(40) { 3 } +
            byteArrayOf(0x50, 0x4b, 0x05, 0x06) + ByteArray(18)
        zip.toList().chunked(7).forEach { carver.consume(it.toByteArray()) }
        carver.finish()
        assertEquals(1, results.size)
        assertEquals(false, results.single().complete)
        assertArrayEquals(zip, File(results.single().path).readBytes())
    }

    @Test
    fun identifiesValidDocxArchive() {
        val output = temporaryFolder.newFolder("docx-output")
        val results = mutableListOf<CarvedFile>()
        val carver = FileCarver(output) { results.add(it) }
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zip.write("<document/>".toByteArray())
            zip.closeEntry()
        }
        val docx = bytes.toByteArray()
        docx.toList().chunked(11).forEach { carver.consume(it.toByteArray()) }
        carver.finish()
        assertEquals(1, results.size)
        assertEquals("docx", results.single().kind)
        assertEquals(true, results.single().complete)
        assertArrayEquals(docx, File(results.single().path).readBytes())
    }

    @Test
    fun recoversPdfAcrossSmallChunks() {
        val output = temporaryFolder.newFolder("pdf-output")
        val results = mutableListOf<CarvedFile>()
        val carver = FileCarver(output) { results.add(it) }
        val pdf = "%PDF-1.7\n".toByteArray() + ByteArray(80) { 32 } + "\n%%EOF".toByteArray()
        pdf.toList().chunked(3).forEach { carver.consume(it.toByteArray()) }
        carver.finish()
        assertEquals(1, results.size)
        assertArrayEquals(pdf, File(results.single().path).readBytes())
    }
}
