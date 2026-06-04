package com.salaun.tristan.uiautomator.rules

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-disk persistence of the custom-rule library, mirroring
 * `com.salaun.tristan.uiautomator.explorer.SessionStore`. One JSON file per
 * package lives under [rootDir] (default `~/.uiautomator-desktop/rules/`); the
 * file name is the sanitised package name. Optional reference captures taken
 * while editing a rule are stored under `assets/<pkg>/<ruleId>.png|.xml`.
 *
 * The library is global — shared across exploration sessions — because a rule
 * encodes app behaviour, not the result of one crawl.
 */
class RuleStore(val rootDir: File = defaultRoot()) {

    init {
        rootDir.mkdirs()
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun sanitize(pkg: String): String =
        pkg.ifBlank { "unknown" }.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun fileFor(pkg: String): File = File(rootDir, "${sanitize(pkg)}.json")

    private fun assetsDir(pkg: String): File =
        File(rootDir, "assets/${sanitize(pkg)}").apply { mkdirs() }

    /** Loads the rule set for [pkg], or a fresh empty one when none exists yet. */
    fun load(pkg: String): PackageRuleSet {
        val f = fileFor(pkg)
        if (!f.isFile) return PackageRuleSet(packageName = pkg)
        return runCatching { json.decodeFromString<PackageRuleSet>(f.readText(Charsets.UTF_8)) }
            .getOrElse { PackageRuleSet(packageName = pkg) }
    }

    /** Writes [set]. Deletes the file (and assets) entirely when it has no rules. */
    fun save(set: PackageRuleSet) {
        val f = fileFor(set.packageName)
        if (set.rules.isEmpty()) {
            if (f.isFile) f.delete()
            return
        }
        f.writeText(json.encodeToString(set), Charsets.UTF_8)
    }

    fun delete(pkg: String) {
        fileFor(pkg).takeIf { it.isFile }?.delete()
        assetsDir(pkg).takeIf { it.isDirectory }?.deleteRecursively()
    }

    fun writeRuleScreenshot(pkg: String, ruleId: String, bytes: ByteArray): String {
        val f = File(assetsDir(pkg), "$ruleId.png")
        f.writeBytes(bytes)
        return "assets/${sanitize(pkg)}/${f.name}"
    }

    fun writeRuleXml(pkg: String, ruleId: String, xml: String): String {
        val f = File(assetsDir(pkg), "$ruleId.xml")
        f.writeText(xml, Charsets.UTF_8)
        return "assets/${sanitize(pkg)}/${f.name}"
    }

    fun readRuleScreenshot(relPath: String): ByteArray? {
        val f = File(rootDir, relPath)
        return if (f.isFile) f.readBytes() else null
    }

    /** Every package that has a rule file, summarised for the list screen. */
    fun listPackages(): List<PackageRuleSummary> {
        if (!rootDir.isDirectory) return emptyList()
        return rootDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == "json" }
            ?.mapNotNull { f ->
                val set = runCatching { json.decodeFromString<PackageRuleSet>(f.readText(Charsets.UTF_8)) }.getOrNull()
                    ?: return@mapNotNull null
                PackageRuleSummary(
                    packageName = set.packageName,
                    ruleCount = set.rules.size,
                    enabledCount = set.rules.count { it.enabled },
                    totalActions = set.rules.sumOf { it.routine.size },
                )
            }
            ?.sortedBy { it.packageName }
            ?.toList()
            .orEmpty()
    }

    fun loadAll(): List<PackageRuleSet> = listPackages().map { load(it.packageName) }

    companion object {
        fun defaultRoot(): File {
            val home = System.getProperty("user.home") ?: "."
            return File(home, ".uiautomator-desktop/rules").apply { mkdirs() }
        }
    }
}

/** Lightweight stats for one package, shown on the rules list screen. */
data class PackageRuleSummary(
    val packageName: String,
    val ruleCount: Int,
    val enabledCount: Int,
    val totalActions: Int,
)
