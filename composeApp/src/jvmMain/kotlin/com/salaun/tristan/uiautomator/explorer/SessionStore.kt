package com.salaun.tristan.uiautomator.explorer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionStore(val baseDir: File) {

    val statesDir = File(baseDir, "states").apply { mkdirs() }
    private val jsonFile = File(baseDir, "session.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        baseDir.mkdirs()
    }

    fun save(session: ExplorationSession) {
        jsonFile.writeText(json.encodeToString(session), Charsets.UTF_8)
    }

    fun writeScreenshot(stateId: String, bytes: ByteArray): String {
        val f = File(statesDir, "$stateId.png")
        f.writeBytes(bytes)
        return "states/${f.name}"
    }

    fun writeXml(stateId: String, xml: String): String {
        val f = File(statesDir, "$stateId.xml")
        f.writeText(xml, Charsets.UTF_8)
        return "states/${f.name}"
    }

    fun readScreenshot(relPath: String): ByteArray? {
        val f = File(baseDir, relPath)
        return if (f.isFile) f.readBytes() else null
    }

    fun readXml(relPath: String): String? {
        val f = File(baseDir, relPath)
        return if (f.isFile) f.readText(Charsets.UTF_8) else null
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun create(rootDir: File, targetPackage: String): SessionStore {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val safe = targetPackage.ifBlank { "unknown" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dir = File(rootDir, "$stamp-$safe")
            dir.mkdirs()
            return SessionStore(dir)
        }

        fun load(dir: File): ExplorationSession? {
            val f = File(dir, "session.json")
            if (!f.isFile) return null
            return runCatching { json.decodeFromString<ExplorationSession>(f.readText(Charsets.UTF_8)) }.getOrNull()
        }

        fun defaultRoot(): File {
            val home = System.getProperty("user.home") ?: "."
            return File(home, ".uiautomator-desktop/sessions").apply { mkdirs() }
        }
    }
}
