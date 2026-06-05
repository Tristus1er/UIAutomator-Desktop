package com.salaun.tristan.uiautomator.explorer

import com.salaun.tristan.uiautomator.rules.ElementSelector
import com.salaun.tristan.uiautomator.rules.MatchMode
import com.salaun.tristan.uiautomator.rules.PackageRuleSet
import com.salaun.tristan.uiautomator.rules.RuleAction
import com.salaun.tristan.uiautomator.rules.RuleEngine
import com.salaun.tristan.uiautomator.rules.RuleStore
import com.salaun.tristan.uiautomator.rules.ScreenRule
import com.salaun.tristan.uiautomator.rules.ScreenSignature
import com.salaun.tristan.uiautomator.rules.SelectorBy
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests for the custom-rule hook wired into [Explorer.frontierExplore]. */
class ExplorerRuleHookTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = "com.example.app"

    private object SilentListener : Explorer.Listener {
        override fun onLog(msg: String) = Unit
        override fun onProgress(progress: ExplorerProgress) = Unit
        override fun onSessionUpdated(session: ExplorationSession) = Unit
    }

    private fun leaf(marker: String) = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" resource-id="$pkg:id/marker_$marker" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1,1]"/>
  </node>
</hierarchy>"""

    private fun engineWith(rules: List<ScreenRule>): RuleEngine {
        val store = RuleStore(tmp.newFolder())
        store.save(PackageRuleSet(packageName = pkg, rules = rules.toMutableList()))
        return RuleEngine(store)
    }

    @Test
    fun `a matching rule runs its routine and the screen is not explored generically (pass-through)`() {
        // onboarding has a scrollable "Accept" button AND a separate generic
        // "other" button. The rule clicks Accept (→ home). Pass-through means
        // the generic "other" button is NEVER tapped.
        val onboarding = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" resource-id="$pkg:id/marker_onb" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1,1]"/>
    <node class="android.widget.ScrollView" package="$pkg" clickable="false" enabled="true" scrollable="true" bounds="[0,200][1080,2000]">
      <node class="android.widget.Button" resource-id="$pkg:id/accept" text="Accept" package="$pkg" clickable="true" enabled="true" bounds="[40,1800][1040,1900]"/>
    </node>
    <node class="android.widget.Button" resource-id="$pkg:id/other" text="Other" package="$pkg" clickable="true" enabled="true" bounds="[40,2100][1040,2200]"/>
  </node>
</hierarchy>"""
        val fake = FakeAdbGateway(
            screens = mapOf(
                "onboarding" to FakeAdbGateway.Screen(onboarding),
                "home" to FakeAdbGateway.Screen(leaf("home")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("onboarding", 540, 1850) to "home", // Accept (rule)
                // "other" (540, 2150) intentionally unmapped: tapping it would
                // throw, proving pass-through never exercises it.
            ),
            launchTarget = "onboarding",
        )
        val rule = ScreenRule(
            id = "r", name = "scroll & accept",
            signature = ScreenSignature(requiredTexts = listOf("Accept")),
            routine = listOf(RuleAction.Click(ElementSelector(SelectorBy.TEXT, "Accept"))),
        )

        val session = runExplorer(fake, engineWith(listOf(rule)))

        assertEquals(2, session.states.size, "onboarding + home")
        val ruleEdge = session.transitions.single { it.action.className == "RULE" }
        assertEquals("S0", ruleEdge.from)
        assertEquals("scroll & accept", ruleEdge.action.text)
        val home = session.states.single { it.id == ruleEdge.to }
        assertEquals(pkg, home.packageName)
        // The Accept tap fired; the generic "other" tap never did.
        val taps = fake.events.filterIsInstance<FakeAdbGateway.Event.Tap>()
        assertTrue(taps.any { it.x == 540 && it.y == 1850 }, "the rule tapped Accept")
        assertTrue(taps.none { it.x == 540 && it.y == 2150 }, "pass-through must not tap the generic button")
        assertTrue(session.transitions.none { it.action.resourceId.endsWith("/other") }, "the generic button must not be exercised")
    }

    @Test
    fun `a rule whose routine leaves the screen unchanged falls through to generic exploration`() {
        // The signature matches via a non-clickable "Accept" caption; the rule's
        // routine is a no-op Wait, so the screen is unchanged. The explorer must
        // record a self-loop and then exercise the generic "go" button normally.
        val home = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" resource-id="$pkg:id/marker_home" text="Accept" package="$pkg" clickable="false" enabled="true" bounds="[40,100][1040,200]"/>
    <node class="android.widget.Button" resource-id="$pkg:id/go" text="Go" package="$pkg" clickable="true" enabled="true" bounds="[40,2100][1040,2200]"/>
  </node>
</hierarchy>"""
        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(home),
                "next" to FakeAdbGateway.Screen(leaf("next")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 540, 2150) to "next", // generic "go"
            ),
            launchTarget = "home",
        )
        val rule = ScreenRule(
            id = "r", name = "noop",
            signature = ScreenSignature(requiredTexts = listOf("Accept"), textMatch = MatchMode.CONTAINS),
            routine = listOf(RuleAction.Wait(0)),
        )

        val session = runExplorer(fake, engineWith(listOf(rule)))

        // A self-loop RULE transition was recorded…
        val loop = session.transitions.firstOrNull { it.action.className == "RULE" && it.loop }
        assertNotNull(loop, "an unchanged routine must record a self-loop")
        assertEquals("S0", loop.from)
        assertEquals("S0", loop.to)
        // …and the generic button was still exercised afterwards.
        assertTrue(
            session.transitions.any { it.action.resourceId.endsWith("/go") && it.to != null },
            "generic exploration must take over when the rule changes nothing",
        )
    }

    @Test
    fun `a rule fires on a physically-distinct screen that deduped to a known state`() {
        // welcome and licence share a full-screen "root_app" container, so the
        // licence screen dedupes into S0 (same root id). The rule is keyed on the
        // licence container id; it must still fire on the licence screen even
        // though the explorer considers it the same state as welcome.
        fun onboardingStep(inner: String, button: String, buttonCy: Int) = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" resource-id="android:id/content" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.view.View" resource-id="root_app" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
      <node class="android.view.View" resource-id="$inner" package="$pkg" clickable="false" enabled="true" bounds="[0,300][1080,2300]">
        <node class="android.widget.Button" resource-id="$button" text="" package="$pkg" clickable="true" enabled="true" bounds="[40,${buttonCy - 50}][1040,${buttonCy + 50}]"/>
      </node>
    </node>
  </node>
</hierarchy>"""
        val welcome = onboardingStep("tag_onboard_welcome_screen", "$pkg:id/welcome_next", 2150)
        val licence = onboardingStep("tag_onboard_licence_screen", "tag_onboard_licence_screen_button_agree", 1850)
        val fake = FakeAdbGateway(
            screens = mapOf(
                "welcome" to FakeAdbGateway.Screen(welcome),
                "licence" to FakeAdbGateway.Screen(licence),
                "home" to FakeAdbGateway.Screen(leaf("home")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("welcome", 540, 2150) to "licence", // walk forward
                FakeAdbGateway.TapKey("licence", 540, 1850) to "home",    // rule's agree click
            ),
            launchTarget = "welcome",
        )
        val rule = ScreenRule(
            id = "r", name = "accept licence",
            signature = ScreenSignature(rootId = "tag_onboard_licence_screen"),
            routine = listOf(RuleAction.Click(ElementSelector(SelectorBy.RESOURCE_ID, "tag_onboard_licence_screen_button_agree"))),
        )

        val session = runExplorer(fake, engineWith(listOf(rule)))

        // welcome + licence collapse to S0; the rule navigates to home (a new state).
        val ruleEdge = session.transitions.firstOrNull { it.action.className == "RULE" && it.to != null }
        assertNotNull(ruleEdge, "the rule must fire on the licence screen despite the dedup")
        assertTrue(
            fake.events.filterIsInstance<FakeAdbGateway.Event.Tap>().any { it.x == 540 && it.y == 1850 },
            "the rule tapped the licence agree button",
        )
    }

    @Test
    fun `a Capture action registers an intermediate dialog as its own step`() {
        // Routine: click Open (→ dialog), Capture (registers the dialog as a
        // step), click OK (→ done). The dialog must appear as a state with edges
        // S0 → dialog → done, instead of a single S0 → done edge.
        fun container(id: String, btnId: String, btnText: String, cy: Int) = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" resource-id="android:id/content" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.view.View" resource-id="$id" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
      <node class="android.widget.Button" resource-id="$btnId" text="$btnText" package="$pkg" clickable="true" enabled="true" bounds="[40,${cy - 50}][1040,${cy + 50}]"/>
    </node>
  </node>
</hierarchy>"""
        val fake = FakeAdbGateway(
            screens = mapOf(
                "screen1" to FakeAdbGateway.Screen(container("screen1", "$pkg:id/open", "Open", 1050)),
                "dialog" to FakeAdbGateway.Screen(container("dialog_view", "$pkg:id/ok", "OK", 1550)),
                "done" to FakeAdbGateway.Screen(leaf("done")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("screen1", 540, 1050) to "dialog",
                FakeAdbGateway.TapKey("dialog", 540, 1550) to "done",
            ),
            launchTarget = "screen1",
        )
        val rule = ScreenRule(
            id = "r", name = "open then confirm",
            signature = ScreenSignature(rootId = "screen1"),
            routine = listOf(
                RuleAction.Click(ElementSelector(SelectorBy.TEXT, "Open")),
                RuleAction.Capture,
                RuleAction.Click(ElementSelector(SelectorBy.TEXT, "OK")),
            ),
        )

        val session = runExplorer(fake, engineWith(listOf(rule)))

        assertEquals(3, session.states.size, "screen1 + dialog (the captured step) + done")
        // The dialog was registered as its own state…
        val dialog = session.states.single { st -> st.clickables.any { it.resourceId.endsWith("/ok") } }
        // …with a chain S0 → dialog → done, both RULE edges.
        assertTrue(session.transitions.any { it.from == "S0" && it.to == dialog.id && it.action.className == "RULE" }, "S0 → dialog step recorded")
        assertTrue(session.transitions.any { it.from == dialog.id && it.action.className == "RULE" && it.to != null }, "dialog → done step recorded")
    }

    @Test
    fun `a Capture step records a system permission dialog from another package`() {
        // The Bluetooth rule pattern: click the permission button (→ a system
        // dialog owned by com.android.permissioncontroller), Capture it as a
        // step, then click Allow. The foreign-package dialog must still be
        // registered as a state — that is the whole point of the Capture step.
        val screen1 = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" resource-id="android:id/content" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.view.View" resource-id="bt_screen" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
      <node class="android.widget.Button" resource-id="$pkg:id/perms" text="Enable" package="$pkg" clickable="true" enabled="true" bounds="[40,1000][1040,1100]"/>
    </node>
  </node>
</hierarchy>"""
        val permDialog = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="com.android.permissioncontroller" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.Button" resource-id="com.android.permissioncontroller:id/permission_allow_button" text="Allow" package="com.android.permissioncontroller" clickable="true" enabled="true" bounds="[40,1500][1040,1600]"/>
  </node>
</hierarchy>"""
        val fake = FakeAdbGateway(
            screens = mapOf(
                "bt" to FakeAdbGateway.Screen(screen1),
                "dialog" to FakeAdbGateway.Screen(permDialog),
                "done" to FakeAdbGateway.Screen(leaf("done")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("bt", 540, 1050) to "dialog",
                FakeAdbGateway.TapKey("dialog", 540, 1550) to "done",
            ),
            launchTarget = "bt",
        )
        val rule = ScreenRule(
            id = "r", name = "Bluetooth",
            signature = ScreenSignature(requiredResourceIds = listOf("bt_screen")),
            routine = listOf(
                RuleAction.Click(ElementSelector(SelectorBy.RESOURCE_ID, "perms")),
                RuleAction.Capture,
                RuleAction.Click(ElementSelector(SelectorBy.RESOURCE_ID, "permission_allow_button")),
            ),
        )

        val session = runExplorer(fake, engineWith(listOf(rule)))

        val dialog = session.states.singleOrNull { it.packageName == "com.android.permissioncontroller" }
        assertNotNull(dialog, "the captured permission dialog must be registered as a state")
        assertTrue(session.transitions.any { it.from == "S0" && it.to == dialog.id && it.action.className == "RULE" }, "bt → dialog step recorded")
        assertTrue(session.transitions.any { it.from == dialog.id && it.action.className == "RULE" && it.to != null }, "dialog → done step recorded")
    }

    @Test
    fun `with no engine the explorer behaves exactly as before`() {
        val fake = FakeAdbGateway(
            screens = mapOf("home" to FakeAdbGateway.Screen(leaf("home"))),
            tapTable = emptyMap(),
            launchTarget = "home",
        )
        val session = runExplorer(fake, ruleEngine = null)
        assertEquals(1, session.states.size)
        assertNull(session.transitions.firstOrNull { it.action.className == "RULE" })
    }

    private fun runExplorer(fake: FakeAdbGateway, ruleEngine: RuleEngine?): ExplorationSession = runBlocking {
        val store = SessionStore(tmp.newFolder("session"))
        val config = ExplorationConfig(targetPackage = pkg, settleDelayMs = 0)
        Explorer(fake, serial = null, config = config, store = store, ruleEngine = ruleEngine).run(SilentListener)
    }
}
