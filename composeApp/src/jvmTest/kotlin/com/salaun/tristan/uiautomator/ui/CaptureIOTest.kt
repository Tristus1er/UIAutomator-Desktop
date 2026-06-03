package com.salaun.tristan.uiautomator.ui

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CaptureIOTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `export then import reproduces the screenshot bytes and the xml text`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3, 4)
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><hierarchy>test</hierarchy>"

        val out = File(tmp.root, "capture.zip")
        CaptureIO.exportToZip(png, xml, out)
        assertTrue(out.length() > 0)

        val bundle = CaptureIO.importFromZip(out)
        assertContentEquals(png, bundle.png)
        assertEquals(xml, bundle.xml)
    }

    @Test
    fun `importing a zip without the expected entries raises an error`() {
        val empty = File(tmp.root, "empty.zip")
        java.util.zip.ZipOutputStream(empty.outputStream()).use {
            it.putNextEntry(java.util.zip.ZipEntry("README.txt"))
            it.write("not a capture".toByteArray())
            it.closeEntry()
        }
        assertFailsWith<IllegalStateException> { CaptureIO.importFromZip(empty) }
    }
}
