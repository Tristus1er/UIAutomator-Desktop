package com.salaun.tristan.uiautomator.explorer

import com.salaun.tristan.uiautomator.adb.AdbGateway
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The automatic explorer's scroll pass must produce the same stitched
 * full-scroll capture as the manual mode's « Capture scroll » and persist it
 * per state, while leaving the device back at the top of the screen.
 */
class ExplorerScrollStitchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = "com.example.app"

    private object SilentListener : Explorer.Listener {
        override fun onLog(msg: String) = Unit
        override fun onProgress(progress: ExplorerProgress) = Unit
        override fun onSessionUpdated(session: ExplorationSession) = Unit
    }

    /** 200×400 screen over 600 rows of content; `offset` is the scroll position. */
    private class ScrollingScreenGateway : AdbGateway {
        val width = 200
        val height = 400
        val contentRows = 600
        var offset = 0
        var launches = 0

        private val xml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="com.example.app" clickable="false" enabled="true" bounds="[0,0][200,400]">
    <node class="android.widget.TextView" resource-id="com.example.app:id/title" text="Scrolling content list" package="com.example.app" clickable="false" enabled="true" bounds="[0,0][1,1]"/>
    <node class="androidx.recyclerview.widget.RecyclerView" package="com.example.app" clickable="false" enabled="true" scrollable="true" bounds="[0,0][200,400]"/>
  </node>
</hierarchy>"""

        override suspend fun launchApp(serial: String?, pkg: String) { offset = 0; launches++ }
        override suspend fun pressBack(serial: String?) {}
        override suspend fun inputTap(serial: String?, x: Int, y: Int) {}
        override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
            // A vertical swipe scrolls the content by 100 px per gesture; the
            // last page sticks (no more content).
            if (x1 == x2 && y1 != y2) {
                offset = if (y1 > y2) (offset + 100).coerceAtMost(contentRows - height)
                else (offset - 100).coerceAtLeast(0)
            }
        }

        override suspend fun screenshotPng(serial: String?): ByteArray {
            // Every content row gets a unique colour so the stitcher's
            // scroll-delta detection locks onto the exact 100 px shift.
            val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until height) {
                val row = offset + y
                val rgb = (row and 0xFF shl 16) or ((row shr 8) and 0xFF shl 8) or ((row * 7) and 0xFF)
                for (x in 0 until width) img.setRGB(x, y, rgb)
            }
            val out = ByteArrayOutputStream()
            ImageIO.write(img, "png", out)
            return out.toByteArray()
        }

        override suspend fun dumpUiXml(serial: String?): String = xml
    }

    @Test
    fun `a scrolling screen gets a stitched full-scroll capture persisted on its state`() {
        val gw = ScrollingScreenGateway()
        val storeDir = tmp.newFolder("session")
        val log = mutableListOf<String>()
        val listener = object : Explorer.Listener {
            override fun onLog(msg: String) { log += msg }
            override fun onProgress(progress: ExplorerProgress) = Unit
            override fun onSessionUpdated(session: ExplorationSession) = Unit
        }
        val session = runBlocking {
            val config = ExplorationConfig(targetPackage = pkg, settleDelayMs = 0)
            Explorer(gw, serial = null, config = config, store = SessionStore(storeDir)).run(listener)
        }

        val home = session.states.single()
        val scrollPath = assertNotNull(
            home.scrollScreenshotPath,
            "the stitched scroll capture must be recorded on the state.\nlog:\n${log.joinToString("\n")}",
        )
        val file = File(storeDir, scrollPath)
        assertTrue(file.isFile, "the stitched PNG must exist on disk: $file")

        val stitched = assertNotNull(ImageIO.read(file), "the stitched capture must be a decodable PNG")
        assertEquals(gw.width, stitched.width)
        assertEquals(
            gw.contentRows,
            stitched.height,
            "the stitched height must cover the whole 600-row content (400 visible + 2×100 scrolled)",
        )

        // The plain screenshot stays untouched (single-frame, original size).
        val plain = assertNotNull(ImageIO.read(File(storeDir, home.screenshotPath)))
        assertEquals(gw.height, plain.height, "the card screenshot remains the plain single-frame capture")

        // The pass must leave the device back at the top of the screen.
        assertEquals(0, gw.offset, "the scroll pass must restore the screen to its top position")
    }

    @Test
    fun `a non-scrolling screen records no stitched capture`() {
        // Same gateway but with content exactly one screen tall: the first
        // swipe uncovers nothing, the stitcher stops at one frame, and no
        // _scroll.png must be written.
        val gw = ScrollingScreenGateway()
        val fixed = object : AdbGateway by gw {
            override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
                // Content fits the screen: swiping never moves anything.
            }
        }
        val storeDir = tmp.newFolder("session")
        val session = runBlocking {
            val config = ExplorationConfig(targetPackage = pkg, settleDelayMs = 0)
            Explorer(fixed, serial = null, config = config, store = SessionStore(storeDir)).run(SilentListener)
        }
        val home = session.states.single()
        assertEquals(null, home.scrollScreenshotPath, "a static screen must not record a stitched capture")
    }
}
