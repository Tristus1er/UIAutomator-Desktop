package com.salaun.tristan.uiautomator.explorer

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphLayoutPersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `missing file returns null`() {
        val store = SessionStore(tmp.newFolder("session"))
        assertNull(store.loadLayout())
    }

    @Test
    fun `save then load round-trips positions`() {
        val store = SessionStore(tmp.newFolder("session"))
        val layout = GraphLayout(
            positions = mapOf(
                "S0" to SerialPoint(32f, 64f),
                "S1" to SerialPoint(512f, 128.5f),
            ),
        )
        store.saveLayout(layout)

        val reloaded = assertNotNull(store.loadLayout())
        assertEquals(layout.positions, reloaded.positions)
    }

    @Test
    fun `saving an empty layout deletes the file so the auto layout takes over`() {
        val store = SessionStore(tmp.newFolder("session"))
        store.saveLayout(GraphLayout(mapOf("S0" to SerialPoint(0f, 0f))))
        val layoutFile = File(store.baseDir, "layout.json")
        assertTrue(layoutFile.isFile, "layout file should exist after a non-empty save")

        store.saveLayout(GraphLayout.EMPTY)
        assertFalse(layoutFile.isFile, "empty layout must remove the persisted file")
        assertNull(store.loadLayout())
    }
}
