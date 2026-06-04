package com.salaun.tristan.uiautomator.rules

import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Zip-based export/import of a single package's rule set, mirroring
 * `com.salaun.tristan.uiautomator.explorer.SessionZip`. The archive bundles the
 * package's `<pkg>.json` plus its `assets/<pkg>/` reference captures so a rule
 * library can be shared between machines and re-imported intact.
 *
 * The destination on import is derived from the JSON's own `packageName` (not
 * the archive file name), so importing a package that already exists overwrites
 * its rules rather than spawning a `_1` duplicate.
 */
object RuleZip {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Writes the [pkg] rule file and its assets from [store] into [outFile]. */
    fun exportPackage(store: RuleStore, pkg: String, outFile: File) {
        val set = store.load(pkg)
        require(set.rules.isNotEmpty()) { "no rules to export for $pkg" }
        outFile.parentFile?.mkdirs()
        val sanitized = pkg.replace(Regex("[^A-Za-z0-9._-]"), "_")
        ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
            // The rule JSON, at the archive root under its sanitised name.
            zip.putNextEntry(ZipEntry("$sanitized.json"))
            zip.write(json.encodeToString(PackageRuleSet.serializer(), set).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            // Any reference-capture assets the rules point at.
            val assets = File(store.rootDir, "assets/$sanitized")
            if (assets.isDirectory) {
                assets.walkTopDown().forEach { f ->
                    if (!f.isFile) return@forEach
                    val rel = "assets/$sanitized/${f.relativeTo(assets).invariantSeparatorsPath}"
                    zip.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Extracts [zipFile] into [store]'s root, overwriting the package's rule set
     * with the archive's. Returns the imported package name. Guards against
     * zip-slip exactly like `SessionZip.importFromZip`.
     */
    fun importPackage(zipFile: File, store: RuleStore): String {
        require(zipFile.isFile) { "not a file: ${zipFile.absolutePath}" }
        val rootCanonical = store.rootDir.canonicalFile
        var importedPkg: String? = null
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(store.rootDir, entry.name).canonicalFile
                if (!target.path.startsWith(rootCanonical.path + File.separator) && target.path != rootCanonical.path) {
                    throw IOException("unsafe zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    val bytes = zip.readBytes()
                    if (entry.name.endsWith(".json") && !entry.name.contains('/')) {
                        // The rule file: decode it and re-save through the store so
                        // the on-disk name matches the package's own identity.
                        val set = runCatching {
                            json.decodeFromString(PackageRuleSet.serializer(), String(bytes, Charsets.UTF_8))
                        }.getOrNull() ?: throw IOException("invalid rule set in ${entry.name}")
                        store.save(set)
                        importedPkg = set.packageName
                    } else {
                        target.parentFile?.mkdirs()
                        target.writeBytes(bytes)
                    }
                }
                zip.closeEntry()
            }
        }
        return importedPkg ?: throw IOException("archive contained no rule set")
    }
}
