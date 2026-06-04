package com.salaun.tristan.uiautomator.rules

import com.salaun.tristan.uiautomator.model.DumpParser
import com.salaun.tristan.uiautomator.model.UiNode
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleEngineMatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = "com.example.app"

    private fun engine() = RuleEngine(RuleStore(tmp.newFolder()))

    /** An onboarding-style screen: a root container + an "Accept" button at the bottom. */
    private fun onboarding(): UiNode {
        val xml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" resource-id="android:id/content" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.view.View" resource-id="onboarding_screen" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
      <node class="android.widget.Button" resource-id="$pkg:id/accept_btn" text="Accept" content-desc="Accept terms" package="$pkg" clickable="true" enabled="true" bounds="[40,2200][1040,2300]"/>
    </node>
  </node>
</hierarchy>"""
        return DumpParser.parse(xml) ?: error("parse failed")
    }

    @Test
    fun `rootId matches the screen container`() {
        val e = engine()
        val root = onboarding()
        assertTrue(e.matches(ScreenSignature(rootId = "onboarding_screen"), root, pkg))
        assertFalse(e.matches(ScreenSignature(rootId = "other_screen"), root, pkg))
    }

    @Test
    fun `resource-id matches in both full and short form`() {
        val e = engine()
        val root = onboarding()
        assertTrue(e.matches(ScreenSignature(requiredResourceIds = listOf("accept_btn")), root, pkg))
        assertTrue(e.matches(ScreenSignature(requiredResourceIds = listOf("$pkg:id/accept_btn")), root, pkg))
        assertFalse(e.matches(ScreenSignature(requiredResourceIds = listOf("missing_id")), root, pkg))
    }

    @Test
    fun `text matching honours exact vs contains`() {
        val e = engine()
        val root = onboarding()
        assertTrue(e.matches(ScreenSignature(requiredTexts = listOf("Accept"), textMatch = MatchMode.EXACT), root, pkg))
        assertTrue(e.matches(ScreenSignature(requiredTexts = listOf("Acc"), textMatch = MatchMode.CONTAINS), root, pkg))
        assertFalse(e.matches(ScreenSignature(requiredTexts = listOf("Acc"), textMatch = MatchMode.EXACT), root, pkg))
    }

    @Test
    fun `content-desc is matched independently`() {
        val e = engine()
        val root = onboarding()
        assertTrue(e.matches(ScreenSignature(requiredContentDescs = listOf("terms"), textMatch = MatchMode.CONTAINS), root, pkg))
        assertFalse(e.matches(ScreenSignature(requiredContentDescs = listOf("nope")), root, pkg))
    }

    @Test
    fun `all criteria must hold (AND semantics)`() {
        val e = engine()
        val root = onboarding()
        // rootId is right but the required id is absent → no match.
        assertFalse(
            e.matches(
                ScreenSignature(rootId = "onboarding_screen", requiredResourceIds = listOf("missing")),
                root, pkg,
            )
        )
        assertTrue(
            e.matches(
                ScreenSignature(rootId = "onboarding_screen", requiredTexts = listOf("Accept")),
                root, pkg,
            )
        )
    }

    @Test
    fun `rootId matches both the detected fragment container and any id merely present`() {
        // A shared full-screen "root_app" wraps the per-screen fragment container.
        // rootScreenId now resolves to the DEEPEST screen-filling container (the
        // fragment), so fragments no longer all collapse to "root_app".
        val xml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" resource-id="android:id/content" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.view.View" resource-id="root_app" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
      <node class="android.view.View" resource-id="tag_onboard_licence_screen" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
        <node class="android.view.View" resource-id="tag_onboard_licence_screen_button_agree" text="Agree" package="$pkg" clickable="true" enabled="true" bounds="[40,2100][1040,2200]"/>
      </node>
    </node>
  </node>
</hierarchy>"""
        val root = DumpParser.parse(xml) ?: error("parse failed")
        val e = engine()
        // The deepest screen-filling container — the fragment — is the detected root.
        assertEquals("tag_onboard_licence_screen", com.salaun.tristan.uiautomator.explorer.StateOps.rootScreenId(root, pkg))
        // A rule keyed on the fragment matches (it IS the detected root)…
        assertTrue(e.matches(ScreenSignature(rootId = "tag_onboard_licence_screen"), root, pkg))
        // …and a rule keyed on the outer shell still matches by mere presence…
        assertTrue(e.matches(ScreenSignature(rootId = "root_app"), root, pkg))
        // …while an absent id does not.
        assertFalse(e.matches(ScreenSignature(rootId = "tag_onboard_welcome_screen"), root, pkg))
    }

    @Test
    fun `whitespace in a required value is tolerated`() {
        val e = engine()
        val root = onboarding()
        // A pasted value with a stray leading newline must still match.
        assertTrue(e.matches(ScreenSignature(requiredResourceIds = listOf("\naccept_btn")), root, pkg))
    }

    @Test
    fun `an empty signature never matches`() {
        val e = engine()
        assertFalse(e.matches(ScreenSignature(), onboarding(), pkg))
    }

    @Test
    fun `matchRule returns the first matching enabled rule`() {
        val e = engine()
        val root = onboarding()
        val r1 = ScreenRule(id = "1", name = "first", signature = ScreenSignature(requiredTexts = listOf("Accept")))
        val r2 = ScreenRule(id = "2", name = "second", signature = ScreenSignature(rootId = "onboarding_screen"))
        assertEquals("1", e.matchRule(root, pkg, listOf(r1, r2))?.id)
        val miss = ScreenRule(id = "3", name = "miss", signature = ScreenSignature(rootId = "nope"))
        assertNull(e.matchRule(root, pkg, listOf(miss)))
    }
}
