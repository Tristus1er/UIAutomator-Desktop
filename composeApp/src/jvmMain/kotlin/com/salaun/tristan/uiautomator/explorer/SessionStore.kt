package com.salaun.tristan.uiautomator.explorer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persisted manual override of a state's graph position, in dp units. */
@Serializable
data class SerialPoint(val x: Float, val y: Float)

/** Per-session manual layout tweaks. Absent state ids fall back to the auto layout. */
@Serializable
data class GraphLayout(
    val positions: Map<String, SerialPoint> = emptyMap(),
) {
    companion object {
        val EMPTY = GraphLayout(emptyMap())
    }
}

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

    /** Writes a state's stitched full-scroll capture next to its plain screenshot. */
    fun writeScrollScreenshot(stateId: String, bytes: ByteArray): String {
        val f = File(statesDir, "${stateId}_scroll.png")
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

    private val layoutFile: File get() = File(baseDir, "layout.json")

    /** Writes the per-session manual layout. Deletes the file if the layout is empty. */
    fun saveLayout(layout: GraphLayout) {
        if (layout.positions.isEmpty()) {
            if (layoutFile.isFile) layoutFile.delete()
        } else {
            layoutFile.writeText(json.encodeToString(layout), Charsets.UTF_8)
        }
    }

    /** Returns the persisted manual layout, or `null` if none was ever saved. */
    fun loadLayout(): GraphLayout? {
        if (!layoutFile.isFile) return null
        return runCatching { json.decodeFromString<GraphLayout>(layoutFile.readText(Charsets.UTF_8)) }.getOrNull()
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

        /** Scans `rootDir` for session folders and returns them sorted by most recent first. */
        fun listAll(rootDir: File): List<SessionSummary> {
            if (!rootDir.isDirectory) return emptyList()
            return rootDir.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory }
                ?.mapNotNull { dir ->
                    val session = load(dir) ?: return@mapNotNull null
                    SessionSummary(dir = dir, session = session)
                }
                ?.sortedByDescending { it.session.startedAt }
                ?.toList()
                .orEmpty()
        }
    }
}

/** Convenience pairing of an on-disk session directory with its decoded metadata. */
data class SessionSummary(val dir: File, val session: ExplorationSession)
