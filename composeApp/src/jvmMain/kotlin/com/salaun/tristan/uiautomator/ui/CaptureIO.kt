package com.salaun.tristan.uiautomator.ui

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * Helpers for the capture-side actions available on the main screen:
 * exporting / importing the current capture to disk, copying the screenshot
 * or the XML dump to the system clipboard, and saving them individually.
 *
 * The zip layout is intentionally dead-simple: `screenshot.png` and `dump.xml`
 * at the archive root. That way any external tool can unzip and use the
 * pieces directly.
 */
object CaptureIO {

    const val ENTRY_PNG = "screenshot.png"
    const val ENTRY_XML = "dump.xml"

    data class CaptureBundle(val png: ByteArray, val xml: String)

    fun exportToZip(png: ByteArray, xml: String, out: File) {
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_PNG))
            zip.write(png)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(ENTRY_XML))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    fun importFromZip(zipFile: File): CaptureBundle {
        var png: ByteArray? = null
        var xml: String? = null
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    ENTRY_PNG -> png = zip.readBytes()
                    ENTRY_XML -> xml = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
        val actualPng = png ?: error("archive is missing $ENTRY_PNG")
        val actualXml = xml ?: error("archive is missing $ENTRY_XML")
        return CaptureBundle(actualPng, actualXml)
    }
}

/** Puts arbitrary text on the system clipboard. Shared across screens. */
fun copyTextToClipboard(text: String) {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    clipboard.setContents(StringSelection(text), null)
}

/**
 * Decodes [pngBytes] with [ImageIO] and hands the resulting image to the
 * system clipboard via the standard AWT `Transferable`/`imageFlavor`. The
 * image can then be pasted in Paint, Word, web forms, etc.
 *
 * @return `true` if the bytes decoded into a valid image and were placed on
 *         the clipboard, `false` otherwise.
 */
fun copyPngToClipboard(pngBytes: ByteArray): Boolean {
    val image = ImageIO.read(ByteArrayInputStream(pngBytes)) ?: return false
    val transferable = object : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
            return image
        }
    }
    Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
    return true
}
