package com.salaun.tristan.uiautomator.explorer

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Zip-based export/import of exploration sessions.
 *
 * The zip layout mirrors a session directory: `session.json` at the root plus
 * the `states/` folder with `S*.png` and `S*.xml` files. An export is a
 * self-contained archive that can be imported elsewhere and re-opened.
 */
object SessionZip {

    /** Writes every regular file inside [sessionDir] into [outFile] as a zip archive. */
    fun exportToZip(sessionDir: File, outFile: File) {
        require(sessionDir.isDirectory) { "not a session directory: ${sessionDir.absolutePath}" }
        outFile.parentFile?.mkdirs()
        ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
            sessionDir.walkTopDown().forEach { f ->
                if (!f.isFile) return@forEach
                val relative = f.relativeTo(sessionDir).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(relative))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Extracts [zipFile] into a fresh directory under [rootDir]. The directory
     * name is derived from the archive filename; suffixes `_1`, `_2`… are
     * appended to avoid collisions.
     */
    fun importFromZip(zipFile: File, rootDir: File): File {
        require(zipFile.isFile) { "not a file: ${zipFile.absolutePath}" }
        rootDir.mkdirs()
        val base = zipFile.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "imported-session" }
        var outDir = File(rootDir, base)
        var collisionCounter = 1
        while (outDir.exists()) {
            outDir = File(rootDir, "${base}_$collisionCounter")
            collisionCounter++
        }
        outDir.mkdirs()
        val outCanonical = outDir.canonicalFile

        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(outDir, entry.name).canonicalFile
                // Zip-slip guard: make sure no entry escapes the output directory.
                if (!target.path.startsWith(outCanonical.path + File.separator) && target.path != outCanonical.path) {
                    throw IOException("unsafe zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
            }
        }
        return outDir
    }
}
