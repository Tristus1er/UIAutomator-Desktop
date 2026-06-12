package com.salaun.tristan.uiautomator.explorer

import com.salaun.tristan.uiautomator.model.DumpParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests for the exception-case heuristics added to [StateOps]. */
class StateOpsHeuristicsTest {

    private val pkg = "com.example.app"

    private fun click(
        text: String = "",
        desc: String = "",
        resourceId: String = "",
    ) = ClickableRef(
        resourceId = resourceId, className = "android.widget.Button",
        text = text, contentDesc = desc,
        bounds = SerialBounds(0, 0, 10, 10), tapX = 5, tapY = 5,
    )

    // -- Destructive detection -------------------------------------------------

    @Test
    fun `destructive labels are flagged - session, data, money and side effects`() {
        assertTrue(StateOps.isLikelyDestructive(click(text = "Log out")))
        assertTrue(StateOps.isLikelyDestructive(click(text = "Se déconnecter")))
        assertTrue(StateOps.isLikelyDestructive(click(text = "Delete account")))
        assertTrue(StateOps.isLikelyDestructive(click(text = "Supprimer")))
        assertTrue(StateOps.isLikelyDestructive(click(text = "Réinitialiser l'appareil")))
        assertTrue(StateOps.isLikelyDestructive(click(text = "Buy now")))
        assertTrue(StateOps.isLikelyDestructive(click(text = "Call support")))
        assertTrue(StateOps.isLikelyDestructive(click(resourceId = "$pkg:id/btn_logout")))
        assertTrue(StateOps.isLikelyDestructive(click(resourceId = "$pkg:id/delete_item")))
    }

    @Test
    fun `ordinary labels are not flagged destructive - word boundaries hold`() {
        assertTrue(!StateOps.isLikelyDestructive(click(text = "Settings")))
        assertTrue(!StateOps.isLikelyDestructive(click(text = "Recall history")), "'call' inside 'Recall' must not match")
        assertTrue(!StateOps.isLikelyDestructive(click(text = "Undeleted items")), "'delete' inside 'Undeleted' must not match")
        assertTrue(!StateOps.isLikelyDestructive(click(text = "Formatting options")), "'format' inside 'Formatting' must not match")
        assertTrue(!StateOps.isLikelyDestructive(click()))
    }

    // -- Consent / unlocking affordances ---------------------------------------

    @Test
    fun `consent accept affordances are recognised`() {
        assertTrue(StateOps.isLikelyConsentAccept(click(text = "Accept all")))
        assertTrue(StateOps.isLikelyConsentAccept(click(text = "Tout accepter")))
        assertTrue(StateOps.isLikelyConsentAccept(click(text = "J'accepte")))
        assertTrue(StateOps.isLikelyConsentAccept(click(text = "Get started")))
        assertTrue(StateOps.isLikelyConsentAccept(click(text = "Continuer")))
        assertTrue(!StateOps.isLikelyConsentAccept(click(text = "Settings")))
        assertTrue(!StateOps.isLikelyConsentAccept(click()))
    }

    // -- ANR / crash dialogs ----------------------------------------------------

    private fun stopDialog(caption: String, vararg buttons: Pair<String, Int>) = assertNotNull(
        DumpParser.parse(
            """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" text="$caption" package="android" clickable="false" enabled="true" bounds="[120,1000][960,1100]"/>
    ${buttons.joinToString("\n    ") { (label, cy) ->
                """<node class="android.widget.Button" text="$label" package="android" clickable="true" enabled="true" bounds="[120,${cy - 40}][960,${cy + 40}]"/>"""
            }}
  </node>
</hierarchy>""",
        ),
    )

    @Test
    fun `detectAppStopDialog recognises an ANR and prefers the Wait button`() {
        val root = stopDialog("MyApp isn't responding", "Close app" to 1300, "Wait" to 1450)
        val det = assertNotNull(StateOps.detectAppStopDialog(root))
        assertTrue(det.isAnr)
        assertEquals("Wait", det.dismissNode?.text)
    }

    @Test
    fun `detectAppStopDialog recognises a crash and picks the close affordance`() {
        val root = stopDialog("MyApp has stopped", "Close app" to 1300, "App info" to 1450)
        val det = assertNotNull(StateOps.detectAppStopDialog(root))
        assertTrue(!det.isAnr)
        assertEquals("Close app", det.dismissNode?.text)
    }

    @Test
    fun `detectAppStopDialog recognises French captions`() {
        val anr = assertNotNull(StateOps.detectAppStopDialog(stopDialog("MyApp ne répond pas", "Attendre" to 1300)))
        assertTrue(anr.isAnr)
        val crash = assertNotNull(
            StateOps.detectAppStopDialog(stopDialog("MyApp s'est arrêtée", "Fermer l'application" to 1300)),
        )
        assertTrue(!crash.isAnr)
    }

    @Test
    fun `detectAppStopDialog ignores ordinary screens`() {
        val normal = assertNotNull(
            DumpParser.parse(
                """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" text="The server is not responding to ping requests right now, retry later or check your long network configuration documentation" package="$pkg" clickable="false" enabled="true" bounds="[40,200][1040,700]"/>
    <node class="android.widget.Button" text="Retry" package="$pkg" clickable="true" enabled="true" bounds="[40,800][1040,900]"/>
  </node>
</hierarchy>""",
            ),
        )
        assertNull(StateOps.detectAppStopDialog(normal), "a long content paragraph must not be mistaken for an ANR caption")
    }

    // -- Normalized fingerprint -------------------------------------------------

    private fun counterScreen(count: Int) = assertNotNull(
        DumpParser.parse(
            """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" resource-id="$pkg:id/badge" text="$count notifications" package="$pkg" clickable="false" enabled="true" bounds="[40,100][1040,200]"/>
  </node>
</hierarchy>""",
        ),
    )

    @Test
    fun `normalizedFingerprint collapses digit-only differences that the strict fingerprint splits`() {
        val three = counterScreen(3)
        val four = counterScreen(4)
        assertNotEquals(StateOps.fingerprint(three), StateOps.fingerprint(four), "strict fp must differ")
        assertEquals(
            StateOps.normalizedFingerprint(three),
            StateOps.normalizedFingerprint(four),
            "masking digit runs must collapse counter variants of the same screen",
        )
    }

    @Test
    fun `normalizedFingerprint still splits screens whose words differ`() {
        val a = counterScreen(3)
        val b = assertNotNull(
            DumpParser.parse(
                """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" resource-id="$pkg:id/badge" text="3 messages" package="$pkg" clickable="false" enabled="true" bounds="[40,100][1040,200]"/>
  </node>
</hierarchy>""",
            ),
        )
        assertNotEquals(StateOps.normalizedFingerprint(a), StateOps.normalizedFingerprint(b))
    }

    // -- Horizontal pagers --------------------------------------------------------

    @Test
    fun `findHorizontalPagers spots a ViewPager and skips vertical lists and wheel pickers`() {
        val root = assertNotNull(
            DumpParser.parse(
                """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="androidx.viewpager.widget.ViewPager" package="$pkg" clickable="false" enabled="true" scrollable="true" bounds="[0,200][1080,900]"/>
    <node class="androidx.recyclerview.widget.RecyclerView" package="$pkg" clickable="false" enabled="true" scrollable="true" bounds="[0,1000][1080,2300]"/>
    <node class="android.widget.NumberPicker" package="$pkg" clickable="false" enabled="true" scrollable="true" bounds="[100,910][500,990]"/>
  </node>
</hierarchy>""",
            ),
        )
        val pagers = StateOps.findHorizontalPagers(root)
        assertEquals(1, pagers.size, "only the ViewPager qualifies")
        assertTrue(pagers.single().className.contains("ViewPager"))
    }

    // -- Long-press collection ------------------------------------------------------

    @Test
    fun `collectClickables emits a long-press candidate for long-clickable nodes`() {
        val root = assertNotNull(
            DumpParser.parse(
                """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" resource-id="$pkg:id/item" text="Row" package="$pkg" clickable="true" long-clickable="true" enabled="true" bounds="[40,100][1040,200]"/>
    <node class="android.widget.Button" resource-id="$pkg:id/plain" text="Plain" package="$pkg" clickable="true" enabled="true" bounds="[40,300][1040,400]"/>
  </node>
</hierarchy>""",
            ),
        )
        val actions = StateOps.collectClickables(root, pkg, max = 100)
        val item = actions.filter { it.resourceId.endsWith("/item") }
        assertEquals(2, item.size, "a clickable + long-clickable node yields one tap and one long-press action")
        assertEquals(
            setOf(ClickableRef.GESTURE_TAP, ClickableRef.GESTURE_LONG_PRESS),
            item.map { it.gesture }.toSet(),
        )
        val plain = actions.filter { it.resourceId.endsWith("/plain") }
        assertEquals(listOf(ClickableRef.GESTURE_TAP), plain.map { it.gesture })
    }

    // -- Launcher & gate packages ------------------------------------------------

    @Test
    fun `launcher packages are recognised by name or convention`() {
        assertTrue(StateOps.isLauncherPackage("com.google.android.apps.nexuslauncher"))
        assertTrue(StateOps.isLauncherPackage("com.miui.home"))
        assertTrue(StateOps.isLauncherPackage("com.weird.oem.launcher2"))
        assertTrue(!StateOps.isLauncherPackage("com.example.app"))
        assertTrue(!StateOps.isLauncherPackage("com.android.chrome"))
    }

    @Test
    fun `gate positive button is found on a GMS dialog without a literal Allow`() {
        val root = assertNotNull(
            DumpParser.parse(
                """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="com.google.android.gms" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" text="For a better experience, turn on device location" package="com.google.android.gms" clickable="false" enabled="true" bounds="[120,1000][960,1150]"/>
    <node class="android.widget.Button" text="No thanks" package="com.google.android.gms" clickable="true" enabled="true" bounds="[120,1300][500,1400]"/>
    <node class="android.widget.Button" text="Turn on" package="com.google.android.gms" clickable="true" enabled="true" bounds="[560,1300][960,1400]"/>
  </node>
</hierarchy>""",
            ),
        )
        // Without gate positives the dialog has no Allow-style button…
        assertNull(StateOps.findPermissionAllowNode(root))
        // …with them, "Turn on" is picked and "No thanks" never is.
        val node = assertNotNull(StateOps.findPermissionAllowNode(root, includeGatePositives = true))
        assertEquals("Turn on", node.text)
    }
}
