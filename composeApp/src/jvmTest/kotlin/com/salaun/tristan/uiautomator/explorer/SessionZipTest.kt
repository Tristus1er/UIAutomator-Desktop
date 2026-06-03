package com.salaun.tristan.uiautomator.explorer

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionZipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleSession(): ExplorationSession {
        val action = ClickableRef(
            resourceId = "com.example.app:id/sign_in",
            className = "android.widget.Button",
            text = "Sign In",
            contentDesc = "",
            bounds = SerialBounds(100, 800, 980, 960),
            tapX = 540, tapY = 880,
        )
        val s0 = StateEntry(
            id = "S0",
            fingerprint = "fp-0",
            packageName = "com.example.app",
            depth = 0,
            screenshotPath = "states/S0.png",
            xmlPath = "states/S0.xml",
            clickables = listOf(action),
            pathFromRoot = emptyList(),
        )
        return ExplorationSession(
            id = "zip-test",
            targetPackage = "com.example.app",
            startedAt = 1_700_000_000_000L,
            config = ExplorationConfig(targetPackage = "com.example.app"),
            states = mutableListOf(s0),
            transitions = mutableListOf(),
        )
    }

    @Test
    fun `export then import reproduces the session on disk`() {
        val sourceDir = tmp.newFolder("source")
        val store = SessionStore(sourceDir)
        val session = sampleSession()
        store.save(session)
        store.writeScreenshot("S0", byteArrayOf(1, 2, 3, 4))
        store.writeXml("S0", "<hierarchy/>")

        val zipOut = File(tmp.root, "export.zip")
        SessionZip.exportToZip(sourceDir, zipOut)
        assertTrue(zipOut.length() > 0, "export produced an empty zip")

        val importRoot = tmp.newFolder("imports")
        val imported = SessionZip.importFromZip(zipOut, importRoot)
        assertEquals("export", imported.name)
        assertTrue(File(imported, "session.json").isFile)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), File(imported, "states/S0.png").readBytes())
        assertEquals("<hierarchy/>", File(imported, "states/S0.xml").readText())

        val reloaded = assertNotNull(SessionStore.load(imported))
        assertEquals(session.targetPackage, reloaded.targetPackage)
        assertEquals(session.states.size, reloaded.states.size)
    }

    @Test
    fun `importing the same archive twice creates a second directory with a suffix`() {
        val sourceDir = tmp.newFolder("src")
        SessionStore(sourceDir).save(sampleSession())
        val zipOut = File(tmp.root, "mine.zip")
        SessionZip.exportToZip(sourceDir, zipOut)

        val root = tmp.newFolder("root")
        val a = SessionZip.importFromZip(zipOut, root)
        val b = SessionZip.importFromZip(zipOut, root)
        assertEquals("mine", a.name)
        assertEquals("mine_1", b.name)
    }

    @Test
    fun `zip-slip entries are refused`() {
        // Craft a malicious archive whose entry tries to escape the output directory.
        val bad = File(tmp.root, "evil.zip")
        ZipOutputStream(bad.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("../escape.txt"))
            zip.write("boom".toByteArray())
            zip.closeEntry()
        }
        val root = tmp.newFolder("safe")
        assertFailsWith<IOException> { SessionZip.importFromZip(bad, root) }
    }
}

class SessionStoreListAllTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `listAll returns the newest session first and skips invalid directories`() {
        val root = tmp.newFolder("sessions")

        fun makeSession(name: String, startedAt: Long) {
            val dir = File(root, name).apply { mkdirs() }
            val store = SessionStore(dir)
            val session = ExplorationSession(
                id = name,
                targetPackage = "pkg",
                startedAt = startedAt,
                config = ExplorationConfig(targetPackage = "pkg"),
            )
            store.save(session)
        }

        makeSession("old", 1_000)
        makeSession("new", 5_000)
        // A directory without any session.json should be ignored.
        File(root, "garbage").mkdirs()
        // A stray file at the root should also be ignored.
        File(root, "readme.txt").writeText("hello")

        val summaries = SessionStore.listAll(root)
        assertEquals(listOf("new", "old"), summaries.map { it.dir.name })
    }

    @Test
    fun `listAll returns empty list when the directory does not exist`() {
        val ghost = File(tmp.root, "nowhere")
        assertEquals(emptyList(), SessionStore.listAll(ghost))
    }
}
