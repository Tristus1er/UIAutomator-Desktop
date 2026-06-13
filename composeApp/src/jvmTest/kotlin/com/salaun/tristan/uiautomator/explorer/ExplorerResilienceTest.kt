package com.salaun.tristan.uiautomator.explorer

import com.salaun.tristan.uiautomator.adb.AdbGateway
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the resilience / autonomy features of [Explorer]: destructive
 * guard-rails, ANR & crash handling, gesture exploration (page-swipes,
 * long-press), normalized-fingerprint dedup, GMS gate dialogs, manifest
 * coverage direct launches and session resume.
 */
class ExplorerResilienceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = "com.example.app"

    private object SilentListener : Explorer.Listener {
        override fun onLog(msg: String) = Unit
        override fun onProgress(progress: ExplorerProgress) = Unit
        override fun onSessionUpdated(session: ExplorationSession) = Unit
    }

    private data class Btn(val id: String, val cx: Int, val cy: Int, val text: String = "")

    private fun screen(marker: String, buttons: List<Btn> = emptyList()): String {
        val children = buildString {
            append(
                """<node index="0" class="android.widget.TextView" resource-id="$pkg:id/marker_$marker" """ +
                    """package="$pkg" clickable="false" enabled="true" bounds="[0,0][1,1]"/>"""
            )
            buttons.forEach { b ->
                append('\n')
                append(
                    """<node index="0" text="${b.text}" resource-id="$pkg:id/${b.id}" """ +
                        """class="android.widget.Button" package="$pkg" content-desc="" clickable="true" """ +
                        """enabled="true" bounds="[${b.cx - 10},${b.cy - 10}][${b.cx + 10},${b.cy + 10}]"/>"""
                )
            }
        }
        return """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    $children
  </node>
</hierarchy>"""
    }

    private fun runExplorer(
        gateway: AdbGateway,
        config: ExplorationConfig = ExplorationConfig(targetPackage = pkg, settleDelayMs = 0),
        store: SessionStore = SessionStore(tmp.newFolder()),
    ): ExplorationSession = runBlocking {
        Explorer(gateway, serial = null, config = config, store = store).run(SilentListener)
    }

    // -- Destructive guard-rails ------------------------------------------------

    @Test
    fun `SKIP policy records destructive elements as skipped and never taps them`() {
        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(
                    screen("home", listOf(Btn("logout", 100, 100, text = "Log out"), Btn("about", 100, 200))),
                ),
                "about_page" to FakeAdbGateway.Screen(screen("about")),
                // No mapping for the logout tap: tapping it would throw.
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 100, 200) to "about_page",
            ),
            launchTarget = "home",
        )

        val session = runExplorer(fake) // default policy: SKIP

        val skipped = session.transitions.single { it.skipped }
        assertTrue(skipped.action.resourceId.endsWith("/logout"))
        assertEquals(null, skipped.to)
        assertTrue(
            fake.events.filterIsInstance<FakeAdbGateway.Event.Tap>().none { it.x == 100 && it.y == 100 },
            "the destructive element must never be physically tapped",
        )
        assertTrue(
            session.transitions.any { it.action.resourceId.endsWith("/about") && it.to != null },
            "the rest of the screen must still be explored",
        )
    }

    @Test
    fun `LAST policy taps the destructive element only after the rest of the screen`() {
        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(
                    screen("home", listOf(Btn("delete", 100, 100, text = "Delete"), Btn("view", 100, 200))),
                ),
                "deleted_page" to FakeAdbGateway.Screen(screen("deleted")),
                "view_page" to FakeAdbGateway.Screen(screen("view")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 100, 100) to "deleted_page",
                FakeAdbGateway.TapKey("home", 100, 200) to "view_page",
            ),
            launchTarget = "home",
        )

        val session = runExplorer(
            fake,
            ExplorationConfig(targetPackage = pkg, settleDelayMs = 0, destructivePolicy = DestructivePolicy.LAST),
        )

        val taps = fake.events.filterIsInstance<FakeAdbGateway.Event.Tap>().filter { it.fromScreen == "home" }
        val viewIdx = taps.indexOfFirst { it.y == 200 }
        val deleteIdx = taps.indexOfFirst { it.y == 100 }
        assertTrue(viewIdx in 0 until deleteIdx, "the ordinary button must be exercised before the destructive one")
        assertTrue(session.transitions.any { it.action.resourceId.endsWith("/delete") && it.to != null })
    }

    @Test
    fun `CONFIRM_ABORT captures the confirmation dialog but never presses its buttons`() {
        val confirmDialog = screen(
            "confirm",
            listOf(Btn("confirm_delete", 300, 300, text = "Delete"), Btn("cancel", 300, 400, text = "Cancel")),
        )
        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(
                    screen("home", listOf(Btn("delete", 100, 100, text = "Delete"), Btn("view", 100, 200))),
                ),
                "confirm" to FakeAdbGateway.Screen(confirmDialog),
                "view_page" to FakeAdbGateway.Screen(screen("view")),
                // The dialog's buttons are unmapped: tapping either would throw.
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 100, 100) to "confirm",
                FakeAdbGateway.TapKey("home", 100, 200) to "view_page",
            ),
            launchTarget = "home",
        )

        val session = runExplorer(
            fake,
            ExplorationConfig(targetPackage = pkg, settleDelayMs = 0, destructivePolicy = DestructivePolicy.CONFIRM_ABORT),
        )

        // The confirmation dialog was captured as a state…
        val dialog = session.states.single { st -> st.clickables.any { it.resourceId.endsWith("/confirm_delete") } }
        assertTrue(
            session.transitions.any { it.from == "S0" && it.to == dialog.id && it.action.resourceId.endsWith("/delete") },
            "the edge home → confirmation dialog must be recorded",
        )
        // …but none of its buttons were ever pressed.
        assertTrue(
            fake.events.filterIsInstance<FakeAdbGateway.Event.Tap>().none { it.fromScreen == "confirm" },
            "no button of the confirmation dialog may be tapped",
        )
    }

    // -- ANR / crash handling -----------------------------------------------------

    @Test
    fun `a crash dialog is dismissed, recorded as a crashed transition, and the crawl continues`() {
        val crashDialogXml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" text="MyApp has stopped" package="android" clickable="false" enabled="true" bounds="[120,1100][960,1180]"/>
    <node class="android.widget.Button" text="Close app" package="android" clickable="true" enabled="true" bounds="[120,1260][960,1340]"/>
  </node>
</hierarchy>"""
        val gw = object : AdbGateway {
            var screen = "home"
            var launches = 0
            override suspend fun launchApp(serial: String?, pkg: String) { screen = "home"; launches++ }
            override suspend fun pressBack(serial: String?) { if (screen != "home") screen = "home" }
            override suspend fun inputTap(serial: String?, x: Int, y: Int) {
                when (screen) {
                    "home" -> screen = if (y < 150) "crash_dialog" else "safe_page"
                    "crash_dialog" -> if (y in 1260..1340) screen = "home" // Close app
                }
            }
            override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
            override suspend fun screenshotPng(serial: String?): ByteArray = byteArrayOf(1)
            override suspend fun dumpUiXml(serial: String?): String = when (screen) {
                "home" -> screen("home", listOf(Btn("boom", 100, 100), Btn("safe", 100, 200)))
                "crash_dialog" -> crashDialogXml
                else -> screen("safe")
            }
        }

        val session = runExplorer(gw)

        val crashed = session.transitions.single { it.crashed }
        assertEquals("S0", crashed.from)
        assertTrue(crashed.action.resourceId.endsWith("/boom"))
        assertTrue(crashed.errorMessage!!.contains("has stopped"))
        assertTrue(
            session.states.none { it.packageName == "android" },
            "the crash dialog must not be registered as a state",
        )
        assertTrue(
            session.transitions.any { it.action.resourceId.endsWith("/safe") && it.to != null },
            "exploration must continue after the crash",
        )
    }

    @Test
    fun `an ANR dialog is waited out and the recovered screen is classified normally`() {
        val anrXml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" text="MyApp isn't responding" package="android" clickable="false" enabled="true" bounds="[120,1100][960,1180]"/>
    <node class="android.widget.Button" text="Close app" package="android" clickable="true" enabled="true" bounds="[120,1260][960,1340]"/>
    <node class="android.widget.Button" text="Wait" package="android" clickable="true" enabled="true" bounds="[120,1400][960,1480]"/>
  </node>
</hierarchy>"""
        val gw = object : AdbGateway {
            var screen = "home"
            var waitPressed = false
            override suspend fun launchApp(serial: String?, pkg: String) { screen = "home" }
            override suspend fun pressBack(serial: String?) { if (screen != "home") screen = "home" }
            override suspend fun inputTap(serial: String?, x: Int, y: Int) {
                when (screen) {
                    "home" -> if (y < 150) screen = "anr"
                    "anr" -> if (y in 1400..1480) { waitPressed = true; screen = "slow_page" } // Wait → app recovers
                }
            }
            override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
            override suspend fun screenshotPng(serial: String?): ByteArray = byteArrayOf(1)
            override suspend fun dumpUiXml(serial: String?): String = when (screen) {
                "home" -> screen("home", listOf(Btn("slow", 100, 100)))
                "anr" -> anrXml
                else -> screen("slow_page")
            }
        }

        val session = runExplorer(gw)

        assertTrue(gw.waitPressed, "the explorer must press Wait on the ANR dialog")
        val edge = session.transitions.single { it.action.resourceId.endsWith("/slow") }
        assertTrue(!edge.crashed, "a recovered ANR is a normal navigation, not a crash")
        assertNotNull(edge.to, "the recovered screen must be classified as the tap's destination")
    }

    @Test
    fun `a silent crash to the launcher is attributed via logcat and the app is relaunched`() {
        val gw = object : AdbGateway {
            var screen = "home"
            var launches = 0
            override suspend fun launchApp(serial: String?, pkg: String) { screen = "home"; launches++ }
            override suspend fun pressBack(serial: String?) {}
            override suspend fun inputTap(serial: String?, x: Int, y: Int) {
                if (screen == "home") screen = if (y < 150) "launcher" else "safe_page"
            }
            override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
            override suspend fun screenshotPng(serial: String?): ByteArray = byteArrayOf(1)
            override suspend fun recentCrashLog(serial: String?, pkg: String): String? =
                if (screen == "launcher") "FATAL EXCEPTION: main\nProcess: $pkg, PID: 1234\njava.lang.NullPointerException" else null
            override suspend fun dumpUiXml(serial: String?): String = when (screen) {
                "home" -> screen("home", listOf(Btn("boom", 100, 100), Btn("safe", 100, 200)))
                "launcher" -> screen("launcher_grid").replace(pkg, "com.google.android.apps.nexuslauncher")
                else -> screen("safe")
            }
        }

        val session = runExplorer(gw)

        val crashed = session.transitions.single { it.crashed }
        assertTrue(crashed.errorMessage!!.contains("FATAL EXCEPTION"))
        assertTrue(gw.launches >= 2, "the app must be relaunched after the crash")
        assertTrue(
            session.states.none { it.packageName.contains("launcher") },
            "the launcher must not be captured as an external state",
        )
        assertTrue(
            session.transitions.any { it.action.resourceId.endsWith("/safe") && it.to != null },
            "exploration must continue after the relaunch",
        )
    }

    // -- Gesture exploration --------------------------------------------------------

    @Test
    fun `a swipe-only pager is advanced with synthetic page-swipes`() {
        fun pagerScreen(marker: String) = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" resource-id="$pkg:id/marker_$marker" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1,1]"/>
    <node class="androidx.viewpager.widget.ViewPager" package="$pkg" clickable="false" enabled="true" scrollable="true" bounds="[0,200][1080,900]"/>
  </node>
</hierarchy>"""
        val gw = object : AdbGateway {
            var page = 1
            override suspend fun launchApp(serial: String?, pkg: String) { page = 1 }
            override suspend fun pressBack(serial: String?) {}
            override suspend fun inputTap(serial: String?, x: Int, y: Int) {}
            override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
                // Horizontal leftward swipe advances the pager; the last page sticks.
                if (y1 == y2 && x1 > x2) page = (page + 1).coerceAtMost(2)
            }
            override suspend fun screenshotPng(serial: String?): ByteArray = byteArrayOf(1)
            override suspend fun dumpUiXml(serial: String?): String = pagerScreen("p$page")
        }

        val session = runExplorer(gw)

        assertEquals(2, session.states.size, "the second pager page must be discovered by swiping")
        val swipeEdge = session.transitions.single { it.to == "S1" && it.from == "S0" }
        assertEquals(ClickableRef.GESTURE_SWIPE_LEFT, swipeEdge.action.gesture)
        assertTrue(
            session.transitions.any { it.from == "S1" && it.loop && it.action.gesture == ClickableRef.GESTURE_SWIPE_LEFT },
            "swiping past the last page must record a self-loop and stop",
        )
    }

    @Test
    fun `long-clickable nodes are long-pressed and their context menu becomes a state`() {
        val homeXml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" resource-id="$pkg:id/marker_home" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1,1]"/>
    <node class="android.widget.TextView" resource-id="$pkg:id/item" text="Row" package="$pkg" clickable="false" long-clickable="true" enabled="true" bounds="[40,100][1040,200]"/>
  </node>
</hierarchy>"""
        val gw = object : AdbGateway {
            var screen = "home"
            override suspend fun launchApp(serial: String?, pkg: String) { screen = "home" }
            override suspend fun pressBack(serial: String?) { screen = "home" }
            override suspend fun inputTap(serial: String?, x: Int, y: Int) {}
            override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
                // A press-and-hold (no movement, long duration) on the row opens its menu.
                if (screen == "home" && x1 == x2 && y1 == y2 && durationMs >= 500 && y1 in 100..200) {
                    screen = "context_menu"
                }
            }
            override suspend fun screenshotPng(serial: String?): ByteArray = byteArrayOf(1)
            override suspend fun dumpUiXml(serial: String?): String =
                if (screen == "home") homeXml else screen("context_menu")
        }

        val session = runExplorer(gw)

        val edge = session.transitions.single { it.action.gesture == ClickableRef.GESTURE_LONG_PRESS && it.to != null }
        assertTrue(edge.action.resourceId.endsWith("/item"))
        assertEquals(2, session.states.size, "the context menu reached by long-press must be a state")
    }

    // -- Normalized fingerprint dedup ------------------------------------------------

    @Test
    fun `the same screen with a different counter value resolves to one state`() {
        fun counter(n: Int) = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" resource-id="$pkg:id/badge" text="$n new messages" package="$pkg" clickable="false" enabled="true" bounds="[40,100][1040,200]"/>
  </node>
</hierarchy>"""
        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(screen("home", listOf(Btn("one", 100, 100), Btn("two", 100, 200)))),
                "counter3" to FakeAdbGateway.Screen(counter(3)),
                "counter4" to FakeAdbGateway.Screen(counter(4)),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 100, 100) to "counter3",
                FakeAdbGateway.TapKey("home", 100, 200) to "counter4",
            ),
            launchTarget = "home",
        )

        val session = runExplorer(fake)

        assertEquals(2, session.states.size, "counter3 and counter4 are the same screen — one state, not two")
        val edges = session.transitions.filter { it.from == "S0" && it.to != null }
        assertEquals(setOf("S1"), edges.mapNotNull { it.to }.toSet(), "both buttons lead to the single counter state")
    }

    // -- System gate dialogs ------------------------------------------------------------

    @Test
    fun `a GMS gate dialog without a literal Allow is auto-accepted through the grant chain`() {
        val gmsDialog = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="com.google.android.gms" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" text="Turn on device location" package="com.google.android.gms" clickable="false" enabled="true" bounds="[120,1000][960,1100]"/>
    <node class="android.widget.Button" text="No thanks" package="com.google.android.gms" clickable="true" enabled="true" bounds="[120,1300][500,1400]"/>
    <node class="android.widget.Button" text="Turn on" package="com.google.android.gms" clickable="true" enabled="true" bounds="[560,1300][960,1400]"/>
  </node>
</hierarchy>"""
        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(screen("home", listOf(Btn("locate", 100, 100)))),
                "gms" to FakeAdbGateway.Screen(gmsDialog),
                "behind" to FakeAdbGateway.Screen(screen("behind")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 100, 100) to "gms",
                FakeAdbGateway.TapKey("gms", 760, 1350) to "behind", // "Turn on" centre
            ),
            launchTarget = "home",
        )

        val session = runExplorer(fake)

        val dialog = session.states.single { it.packageName == "com.google.android.gms" }
        assertTrue(dialog.clickables.isEmpty(), "the gate dialog is terminal, like a permission dialog")
        assertTrue(session.transitions.any { it.from == "S0" && it.to == dialog.id })
        val grant = session.transitions.single { it.from == dialog.id && !it.loop }
        assertEquals("Turn on", grant.action.text)
        assertTrue(session.transitions.none { it.leftApp }, "the gate must be granted, not recorded as leftApp")
        // "No thanks" (centre 310,1350) must never be tapped.
        assertTrue(
            fake.events.filterIsInstance<FakeAdbGateway.Event.Tap>().none { it.fromScreen == "gms" && it.x == 310 },
            "the negative button must never be tapped",
        )
    }

    @Test
    fun `an OEM permission helper outside the standard packages is auto-granted via its activity`() {
        // ColorOS / OPPO routes "allow this app to turn on Bluetooth" through
        // com.oplus.wirelesssettings/…RequestPermissionHelperActivity — a small
        // Allow/Deny dialog that package-only matching treats as a foreign
        // Settings screen, stranding the explorer mid-onboarding. The
        // activity-aware gate detection must recognise and auto-grant it.
        val oplusDialogXml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="com.oplus.wirelesssettings" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" text="Allow STid to turn on Bluetooth?" package="com.oplus.wirelesssettings" clickable="false" enabled="true" bounds="[60,900][1020,1050]"/>
    <node class="android.widget.Button" text="Refuser" resource-id="android:id/button2" package="com.oplus.wirelesssettings" clickable="true" enabled="true" bounds="[120,1300][520,1400]"/>
    <node class="android.widget.Button" text="Autoriser" resource-id="android:id/button3" package="com.oplus.wirelesssettings" clickable="true" enabled="true" bounds="[560,1300][960,1400]"/>
  </node>
</hierarchy>"""
        val gw = object : AdbGateway {
            var screen = "home"
            override suspend fun launchApp(serial: String?, pkg: String) { screen = "home" }
            override suspend fun pressBack(serial: String?) {}
            override suspend fun inputTap(serial: String?, x: Int, y: Int) {
                when (screen) {
                    "home" -> if (y < 150) screen = "oplus_gate"
                    "oplus_gate" -> if (x in 560..960 && y in 1300..1400) screen = "behind" // Autoriser
                }
            }
            override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
            override suspend fun screenshotPng(serial: String?): ByteArray = byteArrayOf(1)
            override suspend fun currentFocusedActivity(serial: String?): String? = when (screen) {
                "oplus_gate" -> "com.oplus.wirelesssettings/com.android.settings.bluetooth.RequestPermissionHelperActivity"
                else -> "com.example.app/com.example.app.MainActivity"
            }
            override suspend fun dumpUiXml(serial: String?): String = when (screen) {
                "home" -> screen("home", listOf(Btn("enable_bt", 100, 100)))
                "oplus_gate" -> oplusDialogXml
                else -> screen("behind")
            }
        }

        val session = runExplorer(gw)

        // The OEM gate is captured as a one-time dialog state…
        val gate = session.states.single { it.packageName == "com.oplus.wirelesssettings" }
        assertTrue(gate.clickables.isEmpty(), "the OEM permission gate is terminal, like any permission dialog")
        // …auto-granted (Autoriser tapped, never Refuser)…
        assertTrue(
            session.transitions.none { it.leftApp },
            "the OEM gate must be granted, not recorded as leftApp",
        )
        val grant = session.transitions.single { it.from == gate.id && !it.loop }
        assertEquals("Autoriser", grant.action.text)
        // …and the screen behind it is reached and explored.
        val behind = session.states.single { it.id == grant.to }
        assertEquals("com.example.app", behind.packageName)
    }

    // -- Manifest coverage / direct launch ------------------------------------------------

    @Test
    fun `unvisited exported activities are launched directly and explored`() {
        val mainComponent = "$pkg/$pkg.MainActivity"
        val hiddenComponent = "$pkg/$pkg.HiddenActivity"
        val gw = object : AdbGateway {
            var screen = "home"
            override suspend fun launchApp(serial: String?, pkg: String) { screen = "home" }
            override suspend fun pressBack(serial: String?) { screen = "home" }
            override suspend fun inputTap(serial: String?, x: Int, y: Int) {
                if (screen == "hidden" && y < 150) screen = "hidden_child"
            }
            override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
            override suspend fun screenshotPng(serial: String?): ByteArray = byteArrayOf(1)
            override suspend fun currentFocusedActivity(serial: String?): String? = when (screen) {
                "home" -> mainComponent
                "hidden", "hidden_child" -> hiddenComponent
                else -> null
            }
            override suspend fun listExportedActivities(serial: String?, pkg: String): List<String> =
                listOf(mainComponent, hiddenComponent)
            override suspend fun startActivity(serial: String?, component: String): Boolean {
                if (component == hiddenComponent) { screen = "hidden"; return true }
                return false
            }
            override suspend fun dumpUiXml(serial: String?): String = when (screen) {
                "home" -> screen("home")
                "hidden" -> screen("hidden", listOf(Btn("inner", 100, 100)))
                else -> screen("hidden_child")
            }
        }

        val session = runExplorer(gw)

        val hidden = session.states.single { it.directLaunchComponent == hiddenComponent }
        assertTrue(hidden.clickables.isNotEmpty(), "the direct-launched screen exposes its own actions")
        assertTrue(
            session.transitions.any { it.from == hidden.id && it.action.resourceId.endsWith("/inner") && it.to != null },
            "the direct-launched activity must be explored like any other screen",
        )
    }

    // -- Session resume ----------------------------------------------------------------------

    @Test
    fun `an interrupted session is resumed and the remaining elements are exercised`() {
        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(screen("home", listOf(Btn("a", 100, 100), Btn("b", 100, 200)))),
                "leaf_a" to FakeAdbGateway.Screen(screen("leaf_a")),
                "leaf_b" to FakeAdbGateway.Screen(screen("leaf_b")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 100, 100) to "leaf_a",
                FakeAdbGateway.TapKey("home", 100, 200) to "leaf_b",
            ),
            launchTarget = "home",
        )
        val storeDir = tmp.newFolder()

        // Phase 1: a state cap interrupts the crawl after home + leaf_a.
        val partial = runExplorer(
            fake,
            ExplorationConfig(targetPackage = pkg, settleDelayMs = 0, maxStates = 2),
            SessionStore(storeDir),
        )
        assertEquals(2, partial.states.size, "phase 1 stops at the cap with work remaining")

        // Phase 2: resume the persisted session with a higher cap.
        val resumed = runBlocking {
            Explorer(
                fake, serial = null,
                config = ExplorationConfig(targetPackage = pkg, settleDelayMs = 0, maxStates = 10),
                store = SessionStore(storeDir),
            ).resume(partial, SilentListener)
        }

        assertEquals(3, resumed.states.size, "the remaining sibling must be discovered after resume")
        assertTrue(resumed.transitions.any { it.action.resourceId.endsWith("/a") })
        assertTrue(
            resumed.transitions.any { it.action.resourceId.endsWith("/b") && it.to != null },
            "the unexercised button must be exercised by the resumed crawl",
        )
        assertNotNull(resumed.endedAt, "the resumed session must be closed properly")
    }
}
