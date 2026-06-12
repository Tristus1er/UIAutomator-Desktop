package com.salaun.tristan.uiautomator.explorer

import com.salaun.tristan.uiautomator.rules.ElementBehavior
import com.salaun.tristan.uiautomator.rules.ElementRule
import com.salaun.tristan.uiautomator.rules.ElementSelector
import com.salaun.tristan.uiautomator.rules.PackageRuleSet
import com.salaun.tristan.uiautomator.rules.RuleEngine
import com.salaun.tristan.uiautomator.rules.RuleStore
import com.salaun.tristan.uiautomator.rules.SelectorBy
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for per-element rules ([ElementRule]): directives that add or remove
 * ONE work item for a matched element while the rest of the screen keeps its
 * generic exploration — unlike screen rules, which are strict pass-through.
 */
class ElementRuleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = "com.example.app"

    private object SilentListener : Explorer.Listener {
        override fun onLog(msg: String) = Unit
        override fun onProgress(progress: ExplorerProgress) = Unit
        override fun onSessionUpdated(session: ExplorationSession) = Unit
    }

    private fun engineWith(vararg rules: ElementRule): RuleEngine {
        val store = RuleStore(tmp.newFolder())
        store.save(PackageRuleSet(packageName = pkg, elementRules = rules.toMutableList()))
        return RuleEngine(store)
    }

    private fun runExplorer(fake: FakeAdbGateway, engine: RuleEngine): ExplorationSession = runBlocking {
        val store = SessionStore(tmp.newFolder("session"))
        val config = ExplorationConfig(targetPackage = pkg, settleDelayMs = 0)
        Explorer(fake, serial = null, config = config, store = store, ruleEngine = engine).run(SilentListener)
    }

    @Test
    fun `a CLICK element rule forces a tap on a non-clickable image and keeps exploring the rest`() {
        // The STid case: `vcard_background_csn` is an ImageView the app handles
        // taps on, but the accessibility tree says clickable="false" — the
        // generic collector never sees it. The element rule must inject the tap
        // while the ordinary `settings` button is still explored generically.
        val homeXml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" resource-id="$pkg:id/marker_home" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1,1]"/>
    <node class="android.widget.ImageView" resource-id="vcard_background_csn" content-desc="VCard Access" package="$pkg" clickable="false" enabled="true" bounds="[42,300][1038,853]"/>
    <node class="android.widget.Button" resource-id="$pkg:id/settings" package="$pkg" clickable="true" enabled="true" bounds="[40,2090][1040,2110]"/>
  </node>
</hierarchy>"""
        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(homeXml),
                "vcard_detail" to FakeAdbGateway.Screen(vcardDetailXml()),
                "settings_page" to FakeAdbGateway.Screen(leaf("settings_page")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 540, 576) to "vcard_detail", // centre of the image
                FakeAdbGateway.TapKey("home", 540, 2100) to "settings_page",
            ),
            launchTarget = "home",
        )
        val engine = engineWith(
            ElementRule(
                id = "r1", name = "open vcard",
                selector = ElementSelector(SelectorBy.RESOURCE_ID, "vcard_background_csn"),
                behavior = ElementBehavior.CLICK,
            ),
        )

        val session = runExplorer(fake, engine)

        // The non-clickable image was tapped and its destination registered…
        val vcardEdge = session.transitions.single { it.action.resourceId == "vcard_background_csn" }
        assertEquals("S0", vcardEdge.from)
        assertTrue(vcardEdge.to != null, "the forced tap must lead to the vcard detail state")
        // …and the regular button was still explored (no pass-through).
        assertTrue(
            session.transitions.any { it.action.resourceId.endsWith("/settings") && it.to != null },
            "generic exploration of the rest of the screen must continue",
        )
        assertEquals(3, session.states.size, "home + vcard detail + settings page")
    }

    @Test
    fun `an AVOID element rule withdraws a clickable from the frontier as a skipped transition`() {
        val homeXml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" resource-id="$pkg:id/marker_home" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1,1]"/>
    <node class="android.widget.Button" resource-id="$pkg:id/fragile" package="$pkg" clickable="true" enabled="true" bounds="[40,90][1040,110]"/>
    <node class="android.widget.Button" resource-id="$pkg:id/normal" package="$pkg" clickable="true" enabled="true" bounds="[40,190][1040,210]"/>
  </node>
</hierarchy>"""
        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(homeXml),
                "normal_page" to FakeAdbGateway.Screen(leaf("normal_page")),
                // No mapping for `fragile`: tapping it would throw.
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 540, 200) to "normal_page",
            ),
            launchTarget = "home",
        )
        val engine = engineWith(
            ElementRule(
                id = "r1", name = "keep away",
                selector = ElementSelector(SelectorBy.RESOURCE_ID, "fragile"),
                behavior = ElementBehavior.AVOID,
            ),
        )

        val session = runExplorer(fake, engine)

        val skipped = session.transitions.single { it.skipped }
        assertTrue(skipped.action.resourceId.endsWith("/fragile"))
        assertTrue(
            fake.events.filterIsInstance<FakeAdbGateway.Event.Tap>().none { it.y == 100 },
            "the avoided element must never be tapped",
        )
        assertTrue(
            session.transitions.any { it.action.resourceId.endsWith("/normal") && it.to != null },
            "the other elements keep being explored",
        )
    }

    @Test
    fun `a CLICK element rule exempts a destructive-looking element from the SKIP policy`() {
        // "Call support" matches the destructive guard, but the user explicitly
        // wrote a CLICK rule for it — the rule wins over the guard.
        val homeXml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" resource-id="$pkg:id/marker_home" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1,1]"/>
    <node class="android.widget.Button" resource-id="$pkg:id/call_btn" text="Call support" package="$pkg" clickable="true" enabled="true" bounds="[40,90][1040,110]"/>
  </node>
</hierarchy>"""
        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(homeXml),
                "call_page" to FakeAdbGateway.Screen(leaf("call_page")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 540, 100) to "call_page",
            ),
            launchTarget = "home",
        )
        val engine = engineWith(
            ElementRule(
                id = "r1",
                selector = ElementSelector(SelectorBy.RESOURCE_ID, "call_btn"),
                behavior = ElementBehavior.CLICK,
            ),
        )

        val session = runExplorer(fake, engine) // default destructive policy: SKIP

        assertTrue(session.transitions.none { it.skipped }, "the rule must exempt the element from the destructive guard")
        assertTrue(
            session.transitions.any { it.action.resourceId.endsWith("/call_btn") && it.to != null },
            "the explicitly-ruled element must be tapped",
        )
    }

    @Test
    fun `element rules survive a store round-trip alongside screen rules`() {
        val store = RuleStore(tmp.newFolder())
        store.save(
            PackageRuleSet(
                packageName = pkg,
                elementRules = mutableListOf(
                    ElementRule(
                        id = "e1", name = "vcard",
                        selector = ElementSelector(SelectorBy.RESOURCE_ID, "vcard_background_csn"),
                        behavior = ElementBehavior.CLICK,
                    ),
                ),
            ),
        )
        val loaded = store.load(pkg)
        assertEquals(1, loaded.elementRules.size)
        assertEquals(ElementBehavior.CLICK, loaded.elementRules.single().behavior)
        assertEquals("vcard_background_csn", loaded.elementRules.single().selector.value)
        // A set holding only element rules must NOT be wiped from disk.
        assertTrue(store.listPackages().any { it.packageName == pkg && it.elementRuleCount == 1 })
    }

    private fun leaf(marker: String) = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" resource-id="$pkg:id/marker_$marker" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1,1]"/>
  </node>
</hierarchy>"""

    private fun vcardDetailXml() = leaf("vcard_detail")
}
