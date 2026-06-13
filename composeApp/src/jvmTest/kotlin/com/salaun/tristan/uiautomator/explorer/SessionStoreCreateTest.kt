package com.salaun.tristan.uiautomator.explorer

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Folder naming of [SessionStore.create]: parallel multi-device explorations
 * start within the same second, so the session directories must never
 * collide — the device serial is appended and an existing folder is always
 * de-duplicated.
 */
class SessionStoreCreateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `two stores created in the same second get distinct directories`() {
        val root = tmp.newFolder()
        val a = SessionStore.create(root, "com.example.app")
        val b = SessionStore.create(root, "com.example.app")
        assertNotEquals(a.baseDir.absolutePath, b.baseDir.absolutePath)
        assertTrue(a.baseDir.isDirectory)
        assertTrue(b.baseDir.isDirectory)
    }

    @Test
    fun `the device serial suffix is sanitised and appended to the folder name`() {
        val root = tmp.newFolder()
        val store = SessionStore.create(root, "com.example.app", suffix = "RF8M:12/ab")
        assertTrue(
            store.baseDir.name.endsWith("-com.example.app-RF8M_12_ab"),
            "unexpected folder name: ${store.baseDir.name}",
        )
    }

    @Test
    fun `same suffix in the same second still gets distinct directories`() {
        val root = tmp.newFolder()
        val a = SessionStore.create(root, "com.example.app", suffix = "serial1")
        val b = SessionStore.create(root, "com.example.app", suffix = "serial1")
        assertNotEquals(a.baseDir.absolutePath, b.baseDir.absolutePath)
    }

    @Test
    fun `a blank suffix leaves the historical naming untouched`() {
        val root = tmp.newFolder()
        val store = SessionStore.create(root, "com.example.app", suffix = "  ")
        assertTrue(store.baseDir.name.endsWith("-com.example.app"), "unexpected: ${store.baseDir.name}")
        assertEquals(root, store.baseDir.parentFile)
    }
}
