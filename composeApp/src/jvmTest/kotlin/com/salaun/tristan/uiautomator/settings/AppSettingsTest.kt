package com.salaun.tristan.uiautomator.settings

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cfg(): File = File(tmp.root, "config.properties")

    @Test
    fun `missing file yields empty settings`() {
        val settings = AppSettings(cfg())
        assertNull(settings.adbPath)
        assertNull(settings.lastDeviceSerial)
    }

    @Test
    fun `writing values creates the parent directory and persists across instances`() {
        val nested = File(tmp.root, "deeper/config.properties")
        val a = AppSettings(nested)
        a.adbPath = "/opt/adb"
        a.lastDeviceSerial = "ABC123"
        assertTrue(nested.isFile, "settings file should be created")

        val b = AppSettings(nested)
        assertEquals("/opt/adb", b.adbPath)
        assertEquals("ABC123", b.lastDeviceSerial)
    }

    @Test
    fun `blank and null values clear the entry`() {
        val settings = AppSettings(cfg())
        settings.adbPath = "/opt/adb"
        settings.adbPath = null
        settings.lastDeviceSerial = "ABC"
        settings.lastDeviceSerial = "   "

        val reloaded = AppSettings(cfg())
        assertNull(reloaded.adbPath)
        assertNull(reloaded.lastDeviceSerial)
    }

    @Test
    fun `languageCode round-trips and defaults to null on a fresh file`() {
        val settings = AppSettings(cfg())
        assertNull(settings.languageCode, "fresh settings imply auto-detection")

        settings.languageCode = "fr"
        val reloaded = AppSettings(cfg())
        assertEquals("fr", reloaded.languageCode)

        reloaded.languageCode = null
        assertNull(AppSettings(cfg()).languageCode)
    }

    @Test
    fun `exploration config fields round-trip and default to null on a fresh file`() {
        val fresh = AppSettings(cfg())
        assertNull(fresh.explorationTargetPackage)
        assertNull(fresh.explorationMaxStates)
        assertNull(fresh.explorationMaxDepth)
        assertNull(fresh.explorationMaxClickablesPerState)
        assertNull(fresh.explorationSettleDelayMs)
        assertNull(fresh.explorationIdleMaxWaitMs)

        fresh.explorationTargetPackage = "com.acme.app"
        fresh.explorationMaxStates = 25
        fresh.explorationMaxDepth = 7
        fresh.explorationMaxClickablesPerState = 10
        fresh.explorationSettleDelayMs = 650L
        fresh.explorationIdleMaxWaitMs = 12_000L

        val reloaded = AppSettings(cfg())
        assertEquals("com.acme.app", reloaded.explorationTargetPackage)
        assertEquals(25, reloaded.explorationMaxStates)
        assertEquals(7, reloaded.explorationMaxDepth)
        assertEquals(10, reloaded.explorationMaxClickablesPerState)
        assertEquals(650L, reloaded.explorationSettleDelayMs)
        assertEquals(12_000L, reloaded.explorationIdleMaxWaitMs)
    }
}
