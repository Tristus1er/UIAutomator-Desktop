package com.salaun.tristan.uiautomator.explorer

import com.salaun.tristan.uiautomator.model.DumpParser
import com.salaun.tristan.uiautomator.testutil.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StateOpsTest {

    private fun parse(name: String) = assertNotNull(DumpParser.parse(Fixtures.dump(name)))

    @Test
    fun `fingerprint is deterministic for identical input`() {
        val a = StateOps.fingerprint(parse("sample_dump.xml"))
        val b = StateOps.fingerprint(parse("sample_dump.xml"))
        assertEquals(a, b)
    }

    @Test
    fun `fingerprint distinguishes states whose labelled text differs`() {
        // Wizard-style screens share the same structure but have different
        // copy on their resource-id'd labels / buttons. These *must* count as
        // distinct states so the explorer keeps going past the first screen.
        val a = StateOps.fingerprint(parse("sample_dump.xml"))
        val b = StateOps.fingerprint(parse("sample_dump_text_changed.xml"))
        assertNotEquals(a, b, "labelled text changes must shift the fingerprint")
    }

    @Test
    fun `fingerprint ignores text changes on anonymous nodes`() {
        // Anonymous nodes (no resource-id) are treated as noise — clocks, list
        // rows, animations… their text changes must not register as a new state.
        val a = StateOps.fingerprint(parse("anon_base.xml"))
        val b = StateOps.fingerprint(parse("anon_base_text_changed.xml"))
        assertEquals(a, b, "text on resource-id-less nodes must not shift the fingerprint")
    }

    @Test
    fun `fingerprint ignores SystemUI status bar so the clock ticking does not split states`() {
        // Two captures of the very same app screen, taken one minute apart.
        // The status bar clock has changed (resource-id'd, so the old logic
        // would have shifted the fingerprint). The fix drops the SystemUI
        // overlay subtree from fingerprinting, so the two captures must
        // collapse to the same state.
        val a = StateOps.fingerprint(parse("systemui_clock_11_00.xml"))
        val b = StateOps.fingerprint(parse("systemui_clock_11_01.xml"))
        assertEquals(a, b, "SystemUI changes must not split the same app screen into multiple states")
    }

    @Test
    fun `fingerprint changes when a new clickable node is added`() {
        val a = StateOps.fingerprint(parse("sample_dump.xml"))
        val b = StateOps.fingerprint(parse("sample_dump_extra_button.xml"))
        assertNotEquals(a, b)
    }

    @Test
    fun `collectClickables keeps only enabled clickable nodes inside the target package`() {
        val root = parse("sample_dump.xml")
        val actions = StateOps.collectClickables(root, pkgFilter = "com.example.app", max = 100)
        assertEquals(2, actions.size)
        assertTrue(actions.any { it.resourceId.endsWith("/sign_in") })
        assertTrue(actions.any { it.resourceId.endsWith("/register") })
        assertTrue(actions.none { it.resourceId.endsWith("/disabled") })
    }

    @Test
    fun `collectClickables computes tap center from bounds`() {
        val root = parse("sample_dump.xml")
        val signIn = StateOps.collectClickables(root, "com.example.app", 100)
            .single { it.resourceId.endsWith("/sign_in") }
        assertEquals(540, signIn.tapX) // (100 + 980) / 2
        assertEquals(880, signIn.tapY) // (800 + 960) / 2
    }

    @Test
    fun `collectClickables caps a long sibling group and records the original size`() {
        // 24 hour cells share parent + class + resource-id; only their text
        // and bounds differ. Without the cap the explorer would queue 24
        // tap actions for what is the same picker → spurious states.
        val root = parse("hour_picker.xml")
        val actions = StateOps.collectClickables(
            root,
            pkgFilter = "com.example.app",
            max = 100,
            maxPerSiblingGroup = 3,
        )
        val cells = actions.filter { it.resourceId.endsWith("/hour_cell") }
        assertEquals(3, cells.size, "the 24-cell picker must be capped to 3 representatives")
        assertTrue(cells.all { it.siblingGroupSize == 24 }, "each kept rep must remember the original group size")
        // The Done button is not part of any large group → it must come through untouched.
        assertTrue(actions.any { it.resourceId.endsWith("/done") })
    }

    @Test
    fun `structureFingerprint is invariant when only labelled text changes`() {
        // The two hour-picker dumps have identical DOM but different title
        // text. The strict fingerprint shifts (text on resource-id'd nodes
        // counts), but the structure fingerprint must collapse them.
        val before = StateOps.structureFingerprint(parse("hour_picker.xml"))
        val after = StateOps.structureFingerprint(parse("hour_picker_after_tap.xml"))
        assertEquals(before, after, "title text alone must not shift the structure fingerprint")
        // Sanity: the strict fingerprint *does* differ.
        val strictBefore = StateOps.fingerprint(parse("hour_picker.xml"))
        val strictAfter = StateOps.fingerprint(parse("hour_picker_after_tap.xml"))
        assertNotEquals(strictBefore, strictAfter)
    }

    @Test
    fun `structureFingerprint shifts when DOM gains a node`() {
        // A new clickable on screen is a real navigation event and must
        // register, so the structure fingerprint has to differ.
        val a = StateOps.structureFingerprint(parse("sample_dump.xml"))
        val b = StateOps.structureFingerprint(parse("sample_dump_extra_button.xml"))
        assertNotEquals(a, b)
    }

    @Test
    fun `collectClickables truncates to the requested maximum`() {
        val root = parse("sample_dump_extra_button.xml")
        val actions = StateOps.collectClickables(root, "com.example.app", max = 2)
        assertEquals(2, actions.size)
    }

    @Test
    fun `dominantPackage returns the most common package attribute`() {
        val root = parse("sample_dump.xml")
        assertEquals("com.example.app", StateOps.dominantPackage(root))
    }

    @Test
    fun `wheelPickerAncestor walks up to a NumberPicker container`() {
        val root = parse("number_picker.xml")
        val cells = StateOps.collectClickables(root, pkgFilter = "com.example.app", max = 100)
        // Six picker cells (3 hour + 3 minute) plus the Validate button.
        val pickerCells = cells.filter { it.insideWheelPicker }
        val nonPickerCells = cells.filterNot { it.insideWheelPicker }
        assertEquals(6, pickerCells.size, "all visible NumberPicker children must be flagged")
        assertTrue(
            nonPickerCells.any { it.resourceId.endsWith("/validate_timer_button") },
            "the Validate button sits outside any NumberPicker and must NOT be flagged",
        )
    }

    @Test
    fun `findPermissionAllowNode prefers the most permissive Allow button`() {
        val root = parse("permission_dialog.xml")
        val allow = StateOps.findPermissionAllowNode(root)
        assertNotNull(allow, "the matcher must surface an Allow button on a stock permission dialog")
        // The fixture exposes "Allow only while using the app" first, "Only
        // this time" next, "Don't allow" last. With the explorer's priority
        // order ("allow" outranks "only this time", which outranks "deny"),
        // we must NOT pick the deny button — we just need *any* of the
        // allow-style buttons.
        val text = allow.text.lowercase() + " " + allow.contentDesc.lowercase()
        assertTrue(
            text.contains("allow") || text.contains("only this time"),
            "matcher must pick an Allow-style button, got: ${allow.text}",
        )
        assertTrue(
            !text.contains("don't") && !text.contains("don’t"),
            "matcher must not pick the Deny button, got: ${allow.text}",
        )
    }

    @Test
    fun `fingerprint ignores the typed content of editable fields`() {
        // The explorer auto-fills empty fields; an empty form and the same form
        // after typing must stay the SAME state, or every keystroke would mint
        // a new screen and the crawl would never converge.
        fun form(value: String) = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" class="android.widget.FrameLayout" package="com.example.app" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node index="0" text="$value" class="android.widget.EditText" resource-id="com.example.app:id/email" package="com.example.app" clickable="true" enabled="true" bounds="[40,200][1040,320]"/>
    <node index="1" text="Submit" class="android.widget.Button" resource-id="com.example.app:id/submit" package="com.example.app" clickable="true" enabled="true" bounds="[40,400][1040,520]"/>
  </node>
</hierarchy>"""
        val empty = assertNotNull(DumpParser.parse(form("")))
        val filled = assertNotNull(DumpParser.parse(form("test@example.com")))
        assertEquals(
            StateOps.fingerprint(empty),
            StateOps.fingerprint(filled),
            "typing into an EditText must not change the screen's fingerprint",
        )
    }

    @Test
    fun `collectEditableFields finds EditText fields and defaults are inferred from hints`() {
        val xml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" class="android.widget.FrameLayout" package="com.example.app" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node index="0" text="" content-desc="Email address" class="android.widget.EditText" resource-id="com.example.app:id/email" package="com.example.app" clickable="true" enabled="true" bounds="[40,200][1040,320]"/>
    <node index="1" text="" class="android.widget.EditText" resource-id="com.example.app:id/password" package="com.example.app" password="true" clickable="true" enabled="true" bounds="[40,360][1040,480]"/>
    <node index="2" text="Go" class="android.widget.Button" resource-id="com.example.app:id/go" package="com.example.app" clickable="true" enabled="true" bounds="[40,560][1040,680]"/>
  </node>
</hierarchy>"""
        val root = assertNotNull(DumpParser.parse(xml))
        val fields = StateOps.collectEditableFields(root, "com.example.app")
        assertEquals(2, fields.size, "both EditTexts are collected, the Button is not")
        val email = fields.single { it.resourceId.endsWith("/email") }
        val password = fields.single { it.resourceId.endsWith("/password") }
        assertEquals("test@example.com", StateOps.defaultValueFor(email))
        assertEquals("Test1234", StateOps.defaultValueFor(password))
    }

    @Test
    fun `findPermissionAllowNode picks 'While using the app' and never the 'Don't allow' button`() {
        // Regression for the location prompt: it has NO plain "Allow" button,
        // only "While using the app" / "Only this time" / "Don't allow". The
        // deny label contains the substring "allow", so a naive contains()
        // match would tap it and DENY the permission. The deny-blocklist must
        // exclude it and the matcher must land on "While using the app".
        // (Uses the curly apostrophe AOSP actually renders in "Don't allow".)
        val xml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" class="android.widget.FrameLayout" package="com.google.android.permissioncontroller" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node index="0" text="Allow Laqi to access this device’s location?" class="android.widget.TextView" resource-id="com.android.permissioncontroller:id/permission_message" package="com.google.android.permissioncontroller" clickable="false" enabled="true" bounds="[199,740][881,846]"/>
    <node index="1" text="While using the app" class="android.widget.Button" resource-id="com.android.permissioncontroller:id/permission_allow_foreground_only_button" package="com.google.android.permissioncontroller" clickable="true" enabled="true" bounds="[120,1430][960,1556]"/>
    <node index="2" text="Only this time" class="android.widget.Button" resource-id="com.android.permissioncontroller:id/permission_allow_one_time_button" package="com.google.android.permissioncontroller" clickable="true" enabled="true" bounds="[120,1556][960,1682]"/>
    <node index="3" text="Don’t allow" class="android.widget.Button" resource-id="com.android.permissioncontroller:id/permission_deny_and_dont_ask_again_button" package="com.google.android.permissioncontroller" clickable="true" enabled="true" bounds="[120,1682][960,1808]"/>
  </node>
</hierarchy>"""
        val root = assertNotNull(DumpParser.parse(xml))
        val allow = assertNotNull(StateOps.findPermissionAllowNode(root), "must find an allow-style button")
        assertEquals(
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            allow.resourceId,
            "must pick 'While using the app', not the deny button (got: ${allow.text})",
        )
    }

    @Test
    fun `findPermissionAllowNode returns null when no allow button is on screen`() {
        val root = parse("sample_dump.xml")
        val allow = StateOps.findPermissionAllowNode(root)
        // sample_dump has Sign in / Register / Disabled — no permission gate.
        val text = allow?.text?.lowercase().orEmpty()
        assertTrue(
            allow == null || (!text.contains("allow") && !text.contains("only this time")),
            "no Allow button on the sample dump (got: ${allow?.text})",
        )
    }
}
