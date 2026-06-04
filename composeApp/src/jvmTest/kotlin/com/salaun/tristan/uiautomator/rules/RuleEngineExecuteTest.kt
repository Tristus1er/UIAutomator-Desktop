package com.salaun.tristan.uiautomator.rules

import com.salaun.tristan.uiautomator.adb.AdbGateway
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleEngineExecuteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = "com.example.app"

    private sealed class Ev {
        data class Tap(val x: Int, val y: Int) : Ev()
        data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : Ev()
        data class Text(val text: String) : Ev()
        object Back : Ev()
    }

    /** A static onboarding screen: a scrollable container + an "Accept" button. */
    private val formXml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" resource-id="$pkg:id/root" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.ScrollView" package="$pkg" clickable="false" enabled="true" scrollable="true" bounds="[0,200][1080,2000]">
      <node class="android.widget.EditText" resource-id="$pkg:id/field" text="" package="$pkg" clickable="true" enabled="true" bounds="[40,400][1040,520]"/>
      <node class="android.widget.Button" resource-id="$pkg:id/accept" text="Accept" package="$pkg" clickable="true" enabled="true" bounds="[40,1800][1040,1900]"/>
    </node>
  </node>
</hierarchy>"""

    private inner class Fake : AdbGateway {
        val events = mutableListOf<Ev>()
        override suspend fun screenshotPng(serial: String?): ByteArray = byteArrayOf(1)
        override suspend fun dumpUiXml(serial: String?): String = formXml
        override suspend fun inputTap(serial: String?, x: Int, y: Int) { events += Ev.Tap(x, y) }
        override suspend fun inputText(serial: String?, text: String) { events += Ev.Text(text) }
        override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
            events += Ev.Swipe(x1, y1, x2, y2)
        }
        override suspend fun pressBack(serial: String?) { events += Ev.Back }
        override suspend fun launchApp(serial: String?, pkg: String) {}
    }

    private fun engine() = RuleEngine(RuleStore(tmp.newFolder()))

    @Test
    fun `scroll then click runs the gestures in order`() = runBlocking {
        val fake = Fake()
        val rule = ScreenRule(
            id = "r", name = "accept",
            signature = ScreenSignature(requiredTexts = listOf("Accept")),
            routine = listOf(
                RuleAction.Scroll(direction = ScrollDirection.DOWN, amount = ScrollAmount.Percent(50)),
                RuleAction.Click(ElementSelector(SelectorBy.TEXT, "Accept")),
            ),
        )

        val outcome = engine().execute(rule, fake, serial = null, settleDelayMs = 0)

        assertEquals(RuleOutcome.Completed(2), outcome)
        val swipeIdx = fake.events.indexOfFirst { it is Ev.Swipe }
        val tap = fake.events.filterIsInstance<Ev.Tap>().single()
        val tapIdx = fake.events.indexOf(tap)
        assertTrue(swipeIdx in 0 until tapIdx, "the scroll must happen before the tap")
        assertEquals(540, tap.x, "tap centres on the Accept button")
        assertEquals(1850, tap.y)
    }

    @Test
    fun `type text taps the field then sends the text`() = runBlocking {
        val fake = Fake()
        val rule = ScreenRule(
            id = "r", name = "type",
            signature = ScreenSignature(requiredResourceIds = listOf("field")),
            routine = listOf(RuleAction.TypeText(ElementSelector(SelectorBy.RESOURCE_ID, "field"), "hello")),
        )

        val outcome = engine().execute(rule, fake, serial = null, settleDelayMs = 0)

        assertEquals(RuleOutcome.Completed(1), outcome)
        assertEquals(Ev.Tap(540, 460), fake.events.filterIsInstance<Ev.Tap>().single())
        assertEquals(Ev.Text("hello"), fake.events.filterIsInstance<Ev.Text>().single())
    }

    @Test
    fun `a missing selector aborts mid-routine, keeping earlier actions`() = runBlocking {
        val fake = Fake()
        val rule = ScreenRule(
            id = "r", name = "partial",
            signature = ScreenSignature(requiredTexts = listOf("Accept")),
            routine = listOf(
                RuleAction.Click(ElementSelector(SelectorBy.TEXT, "Accept")),
                RuleAction.Click(ElementSelector(SelectorBy.TEXT, "DoesNotExist", MatchMode.EXACT)),
            ),
        )

        val outcome = engine().execute(rule, fake, serial = null, settleDelayMs = 0)

        assertTrue(outcome is RuleOutcome.Aborted)
        assertEquals(1, (outcome as RuleOutcome.Aborted).atAction)
        // The first click still fired.
        assertEquals(1, fake.events.filterIsInstance<Ev.Tap>().size)
    }

    @Test
    fun `capture action invokes the capture-step callback`() = runBlocking {
        val fake = Fake()
        var steps = 0
        val rule = ScreenRule(
            id = "r", name = "cap",
            signature = ScreenSignature(requiredTexts = listOf("Accept")),
            routine = listOf(
                RuleAction.Click(ElementSelector(SelectorBy.TEXT, "Accept")),
                RuleAction.Capture,
                RuleAction.Back,
            ),
        )
        val outcome = engine().execute(rule, fake, serial = null, settleDelayMs = 0, onCaptureStep = { steps++ })
        assertEquals(RuleOutcome.Completed(3), outcome)
        assertEquals(1, steps, "the single Capture action triggers exactly one step callback")
        assertEquals(1, fake.events.count { it is Ev.Back })
    }

    @Test
    fun `back action presses BACK`() = runBlocking {
        val fake = Fake()
        val rule = ScreenRule(
            id = "r", name = "back",
            signature = ScreenSignature(requiredTexts = listOf("Accept")),
            routine = listOf(RuleAction.Back),
        )
        engine().execute(rule, fake, serial = null, settleDelayMs = 0)
        assertEquals(1, fake.events.count { it is Ev.Back })
    }
}
