package com.salaun.tristan.uiautomator.explorer

import com.salaun.tristan.uiautomator.adb.AdbGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Demo mode (the frozen status bar) must always be turned back off when the
 * exploration ends — including when the user presses Stop, which cancels the
 * coroutine. A plain `suspend` ADB call in a cancelled scope throws on entry,
 * so the cleanup has to run under NonCancellable; this test guards that.
 */
class ExplorerDemoModeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = "com.example.app"

    private object SilentListener : Explorer.Listener {
        override fun onLog(msg: String) = Unit
        override fun onProgress(progress: ExplorerProgress) = Unit
        override fun onSessionUpdated(session: ExplorationSession) = Unit
    }

    private fun screen(marker: String) = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" resource-id="$pkg:id/marker_$marker" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1,1]"/>
  </node>
</hierarchy>"""

    /** Records demo-mode lifecycle and lets the test cancel mid-crawl. */
    private inner class DemoGateway : AdbGateway {
        val demoEntered = AtomicBoolean(false)
        val exitCalls = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()

        override suspend fun enterDemoMode(serial: String?): Boolean {
            demoEntered.set(true)
            return true
        }
        override suspend fun exitDemoMode(serial: String?) {
            // Mirror the real AdbService: a suspend body. Under NonCancellable
            // this still runs; without it, the surrounding cancelled scope
            // would make this throw before incrementing.
            delay(1)
            exitCalls.incrementAndGet()
        }
        override suspend fun launchApp(serial: String?, pkg: String) {}
        override suspend fun pressBack(serial: String?) {}
        override suspend fun inputTap(serial: String?, x: Int, y: Int) {}
        override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
        override suspend fun screenshotPng(serial: String?): ByteArray = byteArrayOf(1)
        override suspend fun dumpUiXml(serial: String?): String {
            if (!started.isCompleted) started.complete(Unit)
            // Keep the crawl busy so the test can cancel it mid-run.
            delay(50)
            return screen("home")
        }
    }

    @Test
    fun `demo mode is disabled at the natural end of exploration`() {
        val gw = DemoGateway()
        runBlocking {
            val config = ExplorationConfig(targetPackage = pkg, settleDelayMs = 0)
            Explorer(gw, serial = null, config = config, store = SessionStore(tmp.newFolder())).run(SilentListener)
        }
        assertTrue(gw.demoEntered.get(), "demo mode must have been enabled")
        assertEquals(1, gw.exitCalls.get(), "demo mode must be disabled exactly once at the end")
    }

    @Test
    fun `demo mode is disabled even when the exploration is cancelled (Stop)`() = runBlocking {
        val gw = DemoGateway()
        val config = ExplorationConfig(targetPackage = pkg, settleDelayMs = 0)
        val explorer = Explorer(gw, serial = null, config = config, store = SessionStore(tmp.newFolder()))

        val job = launch(Dispatchers.Default) { explorer.run(SilentListener) }
        // Wait until the crawl has really started (demo mode on, first dump
        // issued), then cancel as the Stop button does.
        withTimeout(5_000) { gw.started.await() }
        assertTrue(gw.demoEntered.get())
        job.cancelAndJoin()

        assertEquals(
            1, gw.exitCalls.get(),
            "demo mode must be turned back off even when the run is cancelled",
        )
    }
}
