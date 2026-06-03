package com.salaun.tristan.uiautomator.explorer

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

class SessionStoreTest {

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
            fingerprint = "abc123",
            packageName = "com.example.app",
            depth = 0,
            screenshotPath = "states/S0.png",
            xmlPath = "states/S0.xml",
            clickables = listOf(action),
            pathFromRoot = emptyList(),
        )
        val s1 = s0.copy(id = "S1", fingerprint = "def456", depth = 1, pathFromRoot = listOf(action))
        return ExplorationSession(
            id = "session-1",
            targetPackage = "com.example.app",
            startedAt = 1_700_000_000_000,
            config = ExplorationConfig(targetPackage = "com.example.app"),
            states = mutableListOf(s0, s1),
            transitions = mutableListOf(TransitionEntry(from = "S0", to = "S1", action = action)),
        )
    }

    @Test
    fun `save then load preserves the graph`() {
        val dir = tmp.newFolder("session")
        val store = SessionStore(dir)
        val session = sampleSession()
        store.save(session)

        val loaded = assertNotNull(SessionStore.load(dir))
        assertEquals(session.id, loaded.id)
        assertEquals(session.targetPackage, loaded.targetPackage)
        assertEquals(session.states.size, loaded.states.size)
        assertEquals(session.transitions.size, loaded.transitions.size)
        assertEquals("Sign In", loaded.transitions.single().action.text)
        assertEquals(540, loaded.transitions.single().action.tapX)
    }

    @Test
    fun `create builds a timestamped sub-directory with a sanitized package name`() {
        val root = tmp.newFolder("sessions")
        val store = SessionStore.create(root, "com/evil name?")
        assertTrue(store.baseDir.isDirectory)
        val name = store.baseDir.name
        assertTrue(name.contains("com_evil_name_"), "expected sanitized package name, got '$name'")
    }

    @Test
    fun `writeScreenshot and readScreenshot round-trip bytes`() {
        val dir = tmp.newFolder("session")
        val store = SessionStore(dir)
        val payload = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val rel = store.writeScreenshot("S0", payload)
        assertEquals("states/S0.png", rel)
        assertContentEquals(payload, assertNotNull(store.readScreenshot(rel)))
    }

    @Test
    fun `writeXml and readXml round-trip utf-8 content`() {
        val dir = tmp.newFolder("session")
        val store = SessionStore(dir)
        val xml = "<hierarchy rotation=\"0\">éèà</hierarchy>"
        val rel = store.writeXml("S0", xml)
        assertEquals("states/S0.xml", rel)
        assertEquals(xml, store.readXml(rel))
    }
}
