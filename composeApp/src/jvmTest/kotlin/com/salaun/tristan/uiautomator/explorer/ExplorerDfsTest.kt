package com.salaun.tristan.uiautomator.explorer

import com.salaun.tristan.uiautomator.adb.AdbError
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the DFS traversal in [Explorer] using a scripted [FakeAdbGateway].
 *
 * Screens in the fake use application names ("home", "detail_*") on purpose,
 * to keep them distinct from the explorer's own state ids ("S0", "S1", ...).
 */
class ExplorerDfsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = "com.example.app"

    private data class Btn(val id: String, val cx: Int, val cy: Int)

    private fun screen(marker: String, buttons: List<Btn> = emptyList()): String {
        val children = buildString {
            // A non-clickable marker view so every screen gets its own fingerprint.
            append(
                """<node index="0" class="android.widget.TextView" resource-id="com.example.app:id/marker_$marker" """ +
                    """package="$pkg" clickable="false" enabled="true" bounds="[0,0][1,1]"/>"""
            )
            buttons.forEach { b ->
                append('\n')
                append(
                    """<node index="0" text="" resource-id="com.example.app:id/${b.id}" """ +
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

    private fun runExplorer(fake: FakeAdbGateway): ExplorationSession = runBlocking {
        val store = SessionStore(tmp.newFolder("session"))
        val config = ExplorationConfig(targetPackage = pkg, settleDelayMs = 0)
        Explorer(fake, serial = null, config = config, store = store).run(SilentListener)
    }

    @Test
    fun `frontier DFS registers each leaf, records one edge per action, and never relaunches`() {
        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(screen("home", listOf(Btn("one", 100, 100), Btn("two", 100, 200)))),
                "detail_a" to FakeAdbGateway.Screen(screen("a")),
                "detail_b" to FakeAdbGateway.Screen(screen("b")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 100, 100) to "detail_a",
                FakeAdbGateway.TapKey("home", 100, 200) to "detail_b",
            ),
            launchTarget = "home",
        )

        val session = runExplorer(fake)

        assertEquals(3, session.states.size, "home plus the two leaf screens")
        assertEquals(2, session.transitions.size)
        assertTrue(session.transitions.all { it.errorMessage == null && !it.leftApp && !it.loop })
        assertEquals(setOf("S1", "S2"), session.transitions.mapNotNull { it.to }.toSet())

        // The crawler dives into detail_a, then climbs back to home with a
        // single BACK to reach the second sibling. After the last sibling it
        // simply stops — no pointless return, no relaunch.
        val backs = fake.events.count { it is FakeAdbGateway.Event.Back }
        assertEquals(1, backs, "one BACK to climb back to home for the second sibling")

        val launches = fake.events.count { it is FakeAdbGateway.Event.Launch }
        assertEquals(1, launches, "context-preserving exploration never relaunches here")
    }

    @Test
    fun `self-loop button is recorded without pressing BACK`() {
        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(screen("home", listOf(Btn("noop", 50, 50)))),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 50, 50) to "home",
            ),
            launchTarget = "home",
        )

        val session = runExplorer(fake)

        assertEquals(1, session.states.size)
        val t = session.transitions.single()
        assertEquals("S0", t.from)
        assertEquals("S0", t.to)
        assertTrue(t.loop)

        assertEquals(0, fake.events.count { it is FakeAdbGateway.Event.Back }, "self-loop must not press BACK")
    }

    @Test
    fun `cross-edge to a known state records a transition but does not re-explore it`() {
        // home has two buttons. Button "one" leads to a NEW screen "detail" (which has a
        // "back" button cycling to home). Button "two" leads to "detail" as well — the
        // second time we reach it, the explorer must record the edge and NOT re-enter it.
        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(screen("home", listOf(Btn("one", 100, 100), Btn("two", 100, 200)))),
                "detail" to FakeAdbGateway.Screen(screen("detail", listOf(Btn("back", 300, 300)))),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 100, 100) to "detail",
                FakeAdbGateway.TapKey("home", 100, 200) to "detail",
                FakeAdbGateway.TapKey("detail", 300, 300) to "home",
            ),
            launchTarget = "home",
        )

        val session = runExplorer(fake)

        assertEquals(2, session.states.size, "home + detail")
        // S0 is home, S1 is detail.
        // Edges: home→detail (via one), detail→home (the "back" button inside detail),
        // home→detail (via two — this is the known-state branch).
        assertEquals(3, session.transitions.size)
        val s0ToS1 = session.transitions.filter { it.from == "S0" && it.to == "S1" }
        assertEquals(2, s0ToS1.size)

        val s1ToS0 = session.transitions.single { it.from == "S1" && it.to == "S0" }
        assertEquals(false, s1ToS0.loop)

        assertTrue(session.transitions.all { it.errorMessage == null && !it.leftApp })
    }

    @Test
    fun `leaving the target package captures the external screen as a terminal state`() {
        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(screen("home", listOf(Btn("web", 100, 100), Btn("inside", 100, 200)))),
                "web_page" to FakeAdbGateway.Screen(
                    screen("web").replace(pkg, "com.android.chrome")
                ),
                "inside_page" to FakeAdbGateway.Screen(screen("inside")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 100, 100) to "web_page",
                FakeAdbGateway.TapKey("home", 100, 200) to "inside_page",
            ),
            launchTarget = "home",
        )

        val session = runExplorer(fake)

        // The left-app edge now points at a captured external state, not a
        // dangling null — so the arrow leads to a real screenshot.
        val leftApp = session.transitions.single { it.leftApp }
        assertEquals("S0", leftApp.from)
        assertNotNull(leftApp.to, "the left-app edge must point at the captured external screen")
        val external = session.states.single { it.id == leftApp.to }
        assertEquals("com.android.chrome", external.packageName)
        assertTrue(external.clickables.isEmpty(), "an external screen is terminal — never explored")
        // The crawler stepped back into the app and still exercised the other button.
        assertNotNull(session.transitions.firstOrNull { !it.leftApp && it.to != null })
    }

    @Test
    fun `capture failure is logged as an error transition and exploration continues`() {
        // The dump throws the first time we land on the "broken" screen. Recovery
        // should relaunch + replay the (empty) path back to home, and then the
        // remaining action ("good") must still be explored.
        var brokenDumps = 0
        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(screen("home", listOf(Btn("bad", 100, 100), Btn("good", 100, 200)))),
                "broken" to FakeAdbGateway.Screen(screen("broken")),
                "good_page" to FakeAdbGateway.Screen(screen("good")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 100, 100) to "broken",
                FakeAdbGateway.TapKey("home", 100, 200) to "good_page",
            ),
            launchTarget = "home",
            onDumpHook = { fakeScreen ->
                if (fakeScreen == "broken") {
                    brokenDumps++
                    if (brokenDumps == 1) throw AdbError("uiautomator dump failed: ERROR could not get idle state.")
                }
            },
        )

        val session = runExplorer(fake)

        // Only home + good_page were registered; broken never made it in because the
        // capture blew up before [registerState].
        assertEquals(2, session.states.size)
        assertEquals(setOf("S0", "S1"), session.states.map { it.id }.toSet())

        val err = session.transitions.single { it.errorMessage != null }
        assertEquals("S0", err.from)
        assertNull(err.to)
        assertTrue(err.errorMessage!!.contains("idle state"))

        // The other action was still exercised.
        val good = session.transitions.single { !it.leftApp && it.errorMessage == null }
        assertEquals("S0", good.from)
        assertEquals("S1", good.to)

        // Recovery required a second launch (initial + relaunch after failure).
        val launches = fake.events.count { it is FakeAdbGateway.Event.Launch }
        assertEquals(2, launches, "initial launch + one recovery launch")
    }

    @Test
    fun `fix-up pass recovers a sibling branch that drift forced DFS to abandon`() {
        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(screen("home", listOf(Btn("first", 100, 100), Btn("second", 100, 200)))),
                "first_page" to FakeAdbGateway.Screen(screen("first")),
                "second_page" to FakeAdbGateway.Screen(screen("second")),
                "drift_page" to FakeAdbGateway.Screen(screen("drift")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 100, 100) to "first_page",
                FakeAdbGateway.TapKey("home", 100, 200) to "second_page",
            ),
            launchTarget = "home",
        )

        // Install the drift trigger on the fake: once we have dumped
        // "first_page" (which happens after tapping the first button), queue
        // two drifted dumps. Those two drifts will be consumed by:
        //   1. `verifyOn(home)` after the BACK from first_page,
        //   2. `verifyOn(home)` inside the recovery attempt's relaunch.
        // Both will see the wrong fingerprint, so dfs() gives up on home's
        // remaining click. The fix-up pass then cleanly replays from root.
        fake.onDumpHook = { screen ->
            if (screen == "first_page" && fake.pendingDrifts.isEmpty()) {
                fake.pendingDrifts.addAll(listOf("drift_page", "drift_page"))
            }
        }

        val session = runExplorer(fake)
        val summary = session.transitions.joinToString(" | ") {
            "${it.from}→${it.to ?: "—"}[${it.action.resourceId}]"
        }

        val transitions = session.transitions
        assertNotNull(
            transitions.firstOrNull { it.from == "S0" && it.action.resourceId.endsWith("/first") },
            "the first click was exercised during phase 1. got: $summary",
        )
        assertNotNull(
            transitions.firstOrNull { it.from == "S0" && it.action.resourceId.endsWith("/second") },
            "fix-up pass should have exercised the second click. got: $summary",
        )
    }

    @Test
    fun `recovery waits for the splash to clear before replaying taps`() {
        // Same layout as the fix-up test. We arm drifts *on every launch* so
        // the fake simulates a splash: after the cold start, the first dump
        // returns the "splash" screen's XML, the next returns the real home.
        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(screen("home", listOf(Btn("first", 100, 100), Btn("second", 100, 200)))),
                "first_page" to FakeAdbGateway.Screen(screen("first")),
                "second_page" to FakeAdbGateway.Screen(screen("second")),
                "drift_page" to FakeAdbGateway.Screen(screen("drift")),
                "splash" to FakeAdbGateway.Screen(screen("splash_screen")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 100, 100) to "first_page",
                FakeAdbGateway.TapKey("home", 100, 200) to "second_page",
            ),
            launchTarget = "home",
        )

        // Step 1 — drive the DFS into a state where recoverTo will be called
        // by the fix-up pass: a real drift on "first_page"'s BACK (2 drift
        // dumps so verify + recovery both see the wrong fp).
        // Step 2 — once we're in recovery, every *launch* queues a single
        // "splash" dump in front of the real home dump. The recover must
        // patiently poll until the splash clears.
        fake.onDumpHook = { screen ->
            if (screen == "first_page" && fake.pendingDrifts.isEmpty()) {
                fake.pendingDrifts.addAll(listOf("drift_page", "drift_page"))
            }
        }
        // Intercept launch to enqueue a one-dump splash before home.
        val launchedFake = SplashLaunchWrapper(fake, splashXmlEntry = "splash")

        val log = mutableListOf<String>()
        val listener = object : Explorer.Listener {
            override fun onLog(msg: String) { log += msg }
            override fun onProgress(progress: ExplorerProgress) = Unit
            override fun onSessionUpdated(session: ExplorationSession) = Unit
        }
        val session = runBlocking {
            val store = SessionStore(tmp.newFolder("session"))
            val config = ExplorationConfig(targetPackage = pkg, settleDelayMs = 0)
            Explorer(launchedFake, serial = null, config = config, store = store).run(listener)
        }

        val summary = session.transitions.joinToString(" | ") {
            "${it.from}→${it.to ?: "—"}[${it.action.resourceId}]"
        }
        val diagnostic = "log:\n${log.joinToString("\n")}\ntransitions: $summary"
        // Both clicks exercised — the fix-up recovery's splash wait gave the
        // home screen time to appear before replay.
        assertNotNull(
            session.transitions.firstOrNull { it.from == "S0" && it.action.resourceId.endsWith("/first") },
            "first click during phase 1.\n$diagnostic",
        )
        assertNotNull(
            session.transitions.firstOrNull { it.from == "S0" && it.action.resourceId.endsWith("/second") },
            "second click must still be exercised.\n$diagnostic",
        )
    }

    @Test
    fun `wheel picker children record one synthetic self-loop each without tapping`() {
        // A `NumberPicker` host wraps three picker cells. Without the wheel-
        // picker short-circuit the explorer would (a) tap each cell, (b) see a
        // post-tap fingerprint that differs from `home`'s (text on the cells
        // changed), and (c) register every rotation as a brand-new state. The
        // fix-up loops we observed on com.laqi's hour picker (24 spurious
        // states) come from exactly that pathology.
        //
        // Below, the picker cells map back to themselves in the fake's tap
        // table — that mapping must NEVER be exercised: the explorer should
        // bypass the tap entirely. The home screen also contains an `outside`
        // button that *does* navigate to `next_page`; it is here to prove
        // the BFS keeps walking after collapsing the picker into self-loops.
        val homeXml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" class="android.widget.FrameLayout" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node index="0" class="android.widget.TextView" resource-id="$pkg:id/marker_home" package="$pkg" clickable="false" enabled="true" bounds="[0,0][1,1]"/>
    <node index="1" class="android.widget.NumberPicker" package="$pkg" clickable="false" enabled="true" scrollable="true" bounds="[100,400][500,1200]">
      <node index="0" text="22" class="android.widget.Button" resource-id="$pkg:id/picker_up" package="$pkg" clickable="true" enabled="true" bounds="[100,400][500,640]"/>
      <node index="1" text="23" class="android.widget.EditText" resource-id="android:id/numberpicker_input" package="$pkg" clickable="true" enabled="true" bounds="[100,640][500,960]"/>
      <node index="2" text="00" class="android.widget.Button" resource-id="$pkg:id/picker_down" package="$pkg" clickable="true" enabled="true" bounds="[100,960][500,1200]"/>
    </node>
    <node index="2" text="Continue" class="android.widget.Button" resource-id="$pkg:id/outside" package="$pkg" clickable="true" enabled="true" bounds="[200,1400][880,1600]"/>
  </node>
</hierarchy>"""

        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(homeXml),
                "next_page" to FakeAdbGateway.Screen(screen("next")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 540, 1500) to "next_page",
                // Map picker cells back to "home" — an actual tap would still
                // be a self-loop in the fake. The whole point of the assertion
                // is that NO tap event ever fires for these coordinates.
                FakeAdbGateway.TapKey("home", 300, 520) to "home",
                FakeAdbGateway.TapKey("home", 300, 800) to "home",
                FakeAdbGateway.TapKey("home", 300, 1080) to "home",
            ),
            launchTarget = "home",
        )

        val session = runExplorer(fake)

        // S0 = home, S1 = next_page after BFS expanded the only outside click.
        assertEquals(2, session.states.size, "wheel picker rotations must NOT mint new states")

        // Every picker child produced exactly one self-loop transition.
        val pickerLoops = session.transitions.filter {
            it.loop && it.from == "S0" && it.action.insideWheelPicker
        }
        assertEquals(3, pickerLoops.size, "one synthetic self-loop per picker cell")

        // The actual tap on the picker coordinates was NOT performed: only
        // the Continue button (540, 1500) should appear in the tap log.
        val pickerCoords = setOf(
            FakeAdbGateway.TapKey("home", 300, 520),
            FakeAdbGateway.TapKey("home", 300, 800),
            FakeAdbGateway.TapKey("home", 300, 1080),
        )
        val pickerTaps = fake.events.filterIsInstance<FakeAdbGateway.Event.Tap>()
            .count { FakeAdbGateway.TapKey(it.fromScreen, it.x, it.y) in pickerCoords }
        assertEquals(0, pickerTaps, "wheel picker children must not be tapped on the device")

        // The non-picker Continue click was still exercised, producing the
        // forward edge to next_page.
        val forward = session.transitions.single {
            !it.loop && it.from == "S0" && it.to == "S1"
        }
        assertEquals("$pkg:id/outside", forward.action.resourceId)
    }

    @Test
    fun `a long-running firmware-update screen is waited out, then the next screen is explored`() {
        // Tapping "start" kicks off a firmware update that shows a progress
        // screen for several polls before the app moves on to "done". The
        // explorer must wait it out and register the screen the app lands on —
        // not the transient progress screen.
        val updatingXml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" class="android.widget.FrameLayout" package="com.example.app" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node index="0" text="Updating firmware… do not turn off" class="android.widget.TextView" resource-id="com.example.app:id/msg" package="com.example.app" clickable="false" enabled="true" bounds="[40,800][1040,1000]"/>
    <node index="1" class="android.widget.ProgressBar" package="com.example.app" clickable="false" enabled="true" bounds="[40,1100][1040,1160]"/>
  </node>
</hierarchy>"""
        val gw = object : com.salaun.tristan.uiautomator.adb.AdbGateway {
            var screen = "home"
            var updatingDumps = 0
            var launches = 0
            override suspend fun launchApp(serial: String?, pkg: String) { screen = "home"; launches++ }
            override suspend fun pressBack(serial: String?) {}
            override suspend fun inputTap(serial: String?, x: Int, y: Int) {
                if (screen == "home" && y < 150) screen = "updating"
            }
            override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
            override suspend fun screenshotPng(serial: String?): ByteArray = byteArrayOf(1)
            override suspend fun dumpUiXml(serial: String?): String = when (screen) {
                "home" -> screen("home", listOf(Btn("start", 100, 100)))
                "updating" -> {
                    updatingDumps++
                    if (updatingDumps >= 3) { screen = "done"; screen("done") } else updatingXml
                }
                else -> screen("done")
            }
        }
        val session = runBlocking {
            val store = SessionStore(tmp.newFolder("session"))
            val config = ExplorationConfig(targetPackage = pkg, settleDelayMs = 0, longOperationMaxWaitMs = 60_000)
            Explorer(gw, serial = null, config = config, store = store).run(SilentListener)
        }

        // home + done; the transient "updating" screen must NOT be registered.
        assertEquals(2, session.states.size, "the progress screen must not become a state")
        assertTrue(
            session.transitions.any { it.from == "S0" && it.to == "S1" && it.action.resourceId.endsWith("/start") },
            "after the update finishes, the resulting screen is explored as a new state",
        )
        assertEquals(1, gw.launches, "waiting out the operation must not relaunch the app")
    }

    @Test
    fun `a deep button behind a now-granted permission gate is still exercised`() {
        // Reproduces the egg-config bug: `deep` is reachable only by tapping
        // through a permission gate, and its first DOM element is a back arrow.
        // The crawler must (a) tap the screen's real button (`validate`) before
        // the back arrow thanks to dismiss-deferral, and (b) re-reach `deep` for
        // its remaining elements via re-planning navigation even though the
        // permission dialog no longer appears once granted (so the recorded
        // home→perm→mid path is stale). Without these fixes "validate" was lost.
        val permXml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" class="android.widget.FrameLayout" package="com.android.permissioncontroller" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node index="0" text="Allow" class="android.widget.Button" resource-id="com.android.permissioncontroller:id/permission_allow_button" package="com.android.permissioncontroller" clickable="true" enabled="true" bounds="[120,1700][960,1800]"/>
    <node index="1" text="Don't allow" class="android.widget.Button" resource-id="com.android.permissioncontroller:id/permission_deny_button" package="com.android.permissioncontroller" clickable="true" enabled="true" bounds="[120,1940][960,2040]"/>
  </node>
</hierarchy>"""
        val deepXml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" class="android.widget.FrameLayout" package="com.example.app" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node index="0" class="android.widget.TextView" resource-id="com.example.app:id/marker_deep" package="com.example.app" clickable="false" enabled="true" bounds="[0,0][1,1]"/>
    <node index="1" class="android.widget.ImageView" resource-id="com.example.app:id/arrow_back" package="com.example.app" clickable="true" enabled="true" bounds="[20,20][80,80]"/>
    <node index="2" text="Validate" class="android.widget.Button" resource-id="com.example.app:id/validate" package="com.example.app" clickable="true" enabled="true" bounds="[40,250][160,350]"/>
  </node>
</hierarchy>"""
        val gw = object : com.salaun.tristan.uiautomator.adb.AdbGateway {
            val xml = mapOf(
                "home" to screen("home", listOf(Btn("enter", 100, 100))),
                "perm" to permXml,
                "mid" to screen("mid", listOf(Btn("dive", 100, 100))),
                "deep" to deepXml,
                "final" to screen("final"),
            )
            var granted = false
            var screen = "home"
            var launches = 0
            override suspend fun launchApp(serial: String?, pkg: String) { screen = "home"; launches++ }
            override suspend fun pressBack(serial: String?) {
                screen = when (screen) {
                    "final" -> "deep"
                    "deep" -> "mid"
                    "mid" -> "home"
                    else -> screen
                }
            }
            override suspend fun inputTap(serial: String?, x: Int, y: Int) {
                when (screen) {
                    "home" -> if (y < 150) screen = if (granted) "mid" else "perm"
                    "perm" -> if (y in 1650..1850) { granted = true; screen = "mid" } // Allow
                    "mid" -> if (y < 150) screen = "deep"
                    "deep" -> screen = if (y < 150) "home" else "final" // arrow_back vs validate
                }
            }
            override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
            override suspend fun screenshotPng(serial: String?): ByteArray = byteArrayOf(1)
            override suspend fun dumpUiXml(serial: String?): String = xml.getValue(screen)
        }
        val session = runBlocking {
            val store = SessionStore(tmp.newFolder("session"))
            val config = ExplorationConfig(targetPackage = pkg, settleDelayMs = 0)
            Explorer(gw, serial = null, config = config, store = store).run(SilentListener)
        }

        val deep = session.states.single { it.packageName == pkg && it.clickables.any { c -> c.resourceId.endsWith("/validate") } }
        assertTrue(
            session.transitions.any { it.from == deep.id && it.action.resourceId.endsWith("/validate") && it.to != null },
            "the deep 'Validate' button behind the permission gate must be exercised",
        )
    }

    @Test
    fun `permission dialog is captured as a state and the grant recorded as a transition`() {
        // The `request_perm` button of `home` mounts a system permission
        // dialog from `com.android.permissioncontroller`. The explorer must
        // (1) capture the dialog as its own state — so the permission is
        // visible in the session — and (2) auto-grant it by tapping the most
        // permissive button, recording source→dialog→behind as two edges
        // rather than a `leftApp` dead-end.
        val homeXml = screen("home", listOf(Btn("request_perm", 100, 100)))
        val grantedXml = screen("granted")
        val permissionDialogXml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" class="android.widget.FrameLayout" package="com.android.permissioncontroller" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node index="0" class="android.widget.TextView" resource-id="com.android.permissioncontroller:id/permission_message" package="com.android.permissioncontroller" clickable="false" enabled="true" bounds="[60,400][1020,600]"/>
    <node index="1" text="Allow" class="android.widget.Button" resource-id="com.android.permissioncontroller:id/permission_allow_button" package="com.android.permissioncontroller" clickable="true" enabled="true" bounds="[120,1700][960,1800]"/>
    <node index="2" text="Don't allow" class="android.widget.Button" resource-id="com.android.permissioncontroller:id/permission_deny_button" package="com.android.permissioncontroller" clickable="true" enabled="true" bounds="[120,1940][960,2040]"/>
  </node>
</hierarchy>"""

        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(homeXml),
                "permission_dialog" to FakeAdbGateway.Screen(permissionDialogXml),
                "granted" to FakeAdbGateway.Screen(grantedXml),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 100, 100) to "permission_dialog",
                // Tapping the "Allow" button (centre of [120,1700][960,1800])
                // dismisses the dialog and lands the device on granted_page.
                FakeAdbGateway.TapKey("permission_dialog", 540, 1750) to "granted",
            ),
            launchTarget = "home",
        )

        val session = runExplorer(fake)

        // home (S0) + permission dialog (S1) + granted (S2).
        assertEquals(3, session.states.size, "the permission dialog must be captured as its own state")
        val dialogState = session.states.single { it.packageName == "com.android.permissioncontroller" }
        assertTrue(dialogState.clickables.isEmpty(), "the captured dialog is terminal — no clickables to BFS")

        // source --request_perm--> dialog, then dialog --Allow--> granted.
        val trigger = session.transitions.single { it.from == "S0" && it.to == dialogState.id }
        assertTrue(trigger.action.resourceId.endsWith("/request_perm"))
        val grant = session.transitions.single { it.from == dialogState.id && !it.loop }
        assertTrue(grant.action.text.equals("Allow", ignoreCase = true), "grant edge is labelled with the Allow button")
        val grantedState = session.states.single { it.id == grant.to }
        assertEquals("com.example.app", grantedState.packageName)

        assertTrue(session.transitions.none { it.leftApp }, "auto-grant must not record a leftApp dead-end")

        // Exactly two taps: the trigger and the Allow button (never the deny).
        val taps = fake.events.filterIsInstance<FakeAdbGateway.Event.Tap>()
        assertEquals(2, taps.size, "auto-grant performs exactly one extra tap beyond the trigger")
        assertTrue(
            taps.none { FakeAdbGateway.TapKey(it.fromScreen, it.x, it.y) == FakeAdbGateway.TapKey("permission_dialog", 540, 1990) },
            "the deny button must never be tapped",
        )
    }

    @Test
    fun `a grant that bounces out of the app captures the external screen and returns, never dead-ends`() {
        // Regression: tapping a button mounted a permission dialog whose grant
        // fired a phone intent — landing the device on the system dialer
        // (com.android.server.telecom), OUTSIDE the app. The old code recorded a
        // dangling `dialog → null` leftApp edge and then returned the *source*
        // while physically stranded on the dialer, so the crawl kept tapping the
        // wrong screen. Now the foreign screen must be captured as a terminal
        // external state (real edge) and the explorer must climb back into the app.
        val homeXml = screen("home", listOf(Btn("request_perm", 100, 100)))
        val telecomXml = screen("dialer").replace(pkg, "com.android.server.telecom")
        val permissionDialogXml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" class="android.widget.FrameLayout" package="com.android.permissioncontroller" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node index="1" text="Allow" class="android.widget.Button" resource-id="com.android.permissioncontroller:id/permission_allow_button" package="com.android.permissioncontroller" clickable="true" enabled="true" bounds="[120,1700][960,1800]"/>
    <node index="2" text="Don't allow" class="android.widget.Button" resource-id="com.android.permissioncontroller:id/permission_deny_button" package="com.android.permissioncontroller" clickable="true" enabled="true" bounds="[120,1940][960,2040]"/>
  </node>
</hierarchy>"""

        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(homeXml),
                "permission_dialog" to FakeAdbGateway.Screen(permissionDialogXml),
                "telecom" to FakeAdbGateway.Screen(telecomXml),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 100, 100) to "permission_dialog",
                FakeAdbGateway.TapKey("permission_dialog", 540, 1750) to "telecom", // Allow → leaves to dialer
            ),
            launchTarget = "home",
        )

        val session = runExplorer(fake)

        val dialog = session.states.single { it.packageName == "com.android.permissioncontroller" }
        assertTrue(dialog.clickables.isEmpty(), "the captured dialog stays terminal")
        // The foreign dialer is captured as a real external state…
        val telecom = session.states.single { it.packageName == "com.android.server.telecom" }
        assertTrue(telecom.clickables.isEmpty(), "an external screen is terminal — never explored")
        // …reached by a real leftApp edge from the dialog, NOT a dangling dead-end.
        val grant = session.transitions.single { it.leftApp }
        assertEquals(dialog.id, grant.from)
        assertEquals(telecom.id, grant.to, "the grant edge must point at the captured external screen")
        assertTrue(
            session.transitions.none { it.leftApp && it.to == null },
            "no dangling leftApp dead-end may remain",
        )
        // The explorer climbed back into the app (it ended on a known in-app screen).
        assertTrue(session.states.first().packageName == pkg)
    }

    @Test
    fun `permission flow bouncing through Settings is backed out to the app`() {
        // Some apps (Laqi does this for location) deep-link the permission
        // request through the system Settings app: the runtime dialog appears
        // over the Settings "App info" page, so once it is granted the device
        // is left in com.android.settings — NOT the target app. The explorer
        // must BACK out of Settings to climb back to the app instead of
        // aborting / recording a leftApp dead-end. The Settings page itself is
        // navigation plumbing and must NOT be registered as an app state.
        //
        // The stack-based FakeAdbGateway can't model a dialog that is *replaced*
        // by Settings (it would re-surface the dialog on BACK), so this test
        // uses a small linear device model:
        //   home --tap perm--> dialog --tap allow--> settings --BACK--> granted
        val gateway = object : com.salaun.tristan.uiautomator.adb.AdbGateway {
            val xml = mapOf(
                "home" to screen("home", listOf(Btn("request_perm", 100, 100))),
                "dialog" to """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" class="android.widget.FrameLayout" package="com.google.android.permissioncontroller" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node index="0" class="android.widget.TextView" resource-id="com.android.permissioncontroller:id/permission_message" package="com.google.android.permissioncontroller" clickable="false" enabled="true" bounds="[60,400][1020,600]"/>
    <node index="1" text="While using the app" class="android.widget.Button" resource-id="com.android.permissioncontroller:id/permission_allow_foreground_only_button" package="com.google.android.permissioncontroller" clickable="true" enabled="true" bounds="[120,1430][960,1556]"/>
    <node index="2" text="Don’t allow" class="android.widget.Button" resource-id="com.android.permissioncontroller:id/permission_deny_and_dont_ask_again_button" package="com.google.android.permissioncontroller" clickable="true" enabled="true" bounds="[120,1682][960,1808]"/>
  </node>
</hierarchy>""",
                "settings" to screen("settings_appinfo").replace(pkg, "com.android.settings"),
                "granted" to screen("granted"),
            )
            var screen = "home"
            val events = mutableListOf<String>()
            override suspend fun launchApp(serial: String?, pkg: String) { screen = "home"; events += "launch" }
            override suspend fun pressBack(serial: String?) {
                events += "back:$screen"
                // Backing out of Settings climbs back into the app (now granted).
                screen = if (screen == "settings") "granted" else screen
            }
            override suspend fun inputTap(serial: String?, x: Int, y: Int) {
                events += "tap:$screen"
                screen = when (screen) {
                    "home" -> "dialog"        // request_perm mounts the dialog
                    "dialog" -> "settings"    // granting reveals the Settings page
                    else -> screen
                }
            }
            override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
            override suspend fun screenshotPng(serial: String?): ByteArray = byteArrayOf(1)
            override suspend fun dumpUiXml(serial: String?): String = xml.getValue(screen)
        }

        val session = runBlocking {
            val store = SessionStore(tmp.newFolder("session"))
            val config = ExplorationConfig(targetPackage = pkg, settleDelayMs = 0)
            Explorer(gateway, serial = null, config = config, store = store).run(SilentListener)
        }

        assertTrue(
            session.states.none { it.packageName == "com.android.settings" },
            "the Settings page is plumbing — it must not be registered as a state",
        )
        val dialogState = session.states.single { it.packageName == "com.google.android.permissioncontroller" }
        assertTrue(session.transitions.any { it.from == "S0" && it.to == dialogState.id }, "source→dialog edge recorded")
        assertTrue(session.transitions.none { it.leftApp }, "must not abort/leftApp on the Settings bounce")
        // The grant edge leaves the dialog and lands back inside the app.
        val grant = session.transitions.single { it.from == dialogState.id && it.to != null && !it.loop }
        val behind = session.states.single { it.id == grant.to }
        assertEquals(pkg, behind.packageName, "after backing out of Settings we must be back in the app")
        // A BACK was issued from the Settings page specifically.
        assertTrue(gateway.events.any { it == "back:settings" }, "explorer must press BACK out of Settings")
    }

    @Test
    fun `BFS expands sibling branches before diving into a deep stack`() {
        // home has two buttons. "shallow" leads to a leaf (no clickables).
        // "deep" leads to a 3-screen wizard. With the previous DFS the
        // explorer would consume the entire wizard before returning to the
        // shallow branch, so under a tight state cap the shallow leaf could
        // be missed. With BFS, the shallow leaf is registered as soon as
        // home's clickables are walked — *before* the wizard is dived into.
        val fake = FakeAdbGateway(
            screens = mapOf(
                "home" to FakeAdbGateway.Screen(
                    screen("home", listOf(Btn("shallow", 100, 100), Btn("deep", 100, 200)))
                ),
                "shallow_leaf" to FakeAdbGateway.Screen(screen("shallow_leaf")),
                "deep_1" to FakeAdbGateway.Screen(screen("deep_1", listOf(Btn("next", 100, 100)))),
                "deep_2" to FakeAdbGateway.Screen(screen("deep_2", listOf(Btn("next", 100, 100)))),
                "deep_3" to FakeAdbGateway.Screen(screen("deep_3")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("home", 100, 100) to "shallow_leaf",
                FakeAdbGateway.TapKey("home", 100, 200) to "deep_1",
                FakeAdbGateway.TapKey("deep_1", 100, 100) to "deep_2",
                FakeAdbGateway.TapKey("deep_2", 100, 100) to "deep_3",
            ),
            launchTarget = "home",
        )

        val session = runExplorer(fake)

        // The order of state registration is BFS: home (S0), shallow_leaf
        // (S1), deep_1 (S2), then deep_2 (S3), deep_3 (S4). The shallow
        // branch's leaf is NOT preceded by deep_1's descendants.
        val ids = session.states.map { it.id }
        assertEquals(listOf("S0", "S1", "S2", "S3", "S4"), ids)
        // S1 is the shallow_leaf (depth 1). S2 is deep_1 (depth 1). S1 must
        // come before any descendant of S2.
        val s1Index = session.states.indexOfFirst { it.id == "S1" }
        val s2Index = session.states.indexOfFirst { it.id == "S2" }
        val s3Index = session.states.indexOfFirst { it.id == "S3" }
        assertTrue(s1Index < s3Index, "shallow leaf must be registered before deep grandchildren")
        assertTrue(s1Index < s2Index || s2Index == s1Index + 1, "BFS keeps siblings adjacent")
    }

    @Test
    fun `recovery attaches to a known state when onboarding disappears on relaunch`() {
        val fake = FakeAdbGateway(
            screens = mapOf(
                "onboarding" to FakeAdbGateway.Screen(screen("onboarding", listOf(Btn("next", 50, 50)))),
                "home" to FakeAdbGateway.Screen(screen("home", listOf(Btn("a", 200, 200), Btn("b", 200, 300)))),
                "page_a" to FakeAdbGateway.Screen(screen("page_a")),
                "page_b" to FakeAdbGateway.Screen(screen("page_b")),
                "drift" to FakeAdbGateway.Screen(screen("drift_screen")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("onboarding", 50, 50) to "home",
                FakeAdbGateway.TapKey("home", 200, 200) to "page_a",
                FakeAdbGateway.TapKey("home", 200, 300) to "page_b",
            ),
            launchTarget = "onboarding",
        )

        // Arm the onboarding simulation: once we've dumped "home" for the
        // *second* time (= after the initial registration) we
        //  - queue two drifts to force the ongoing verify+recover to see an
        //    unexpected fingerprint,
        //  - flip the fake's launchTarget to "home", emulating an app that
        //    remembers onboarding was dismissed and skips it on restart.
        // The recovery must then attach to "home" (S1) instead of looking
        // for the vanished "onboarding" root.
        var homeDumps = 0
        var triggered = false
        fake.onDumpHook = { screen ->
            if (screen == "home") {
                homeDumps++
                if (homeDumps >= 2 && !triggered && fake.pendingDrifts.isEmpty()) {
                    triggered = true
                    fake.pendingDrifts.addAll(listOf("drift", "drift"))
                    fake.launchTarget = "home"
                }
            }
        }

        val session = runExplorer(fake)
        val summary = session.transitions.joinToString(" | ") {
            "${it.from}→${it.to ?: "—"}[${it.action.resourceId}]"
        }

        // Both sibling buttons of the home screen must be exercised; if the
        // recovery had insisted on seeing "onboarding" again it would never
        // have re-attached and the "b" click would be lost.
        assertNotNull(
            session.transitions.firstOrNull { it.action.resourceId.endsWith("/a") },
            "button a exercised. got: $summary",
        )
        assertNotNull(
            session.transitions.firstOrNull { it.action.resourceId.endsWith("/b") },
            "button b exercised. got: $summary",
        )
        // The "next" transition from onboarding remains recorded from phase 1.
        assertNotNull(
            session.transitions.firstOrNull { it.action.resourceId.endsWith("/next") },
            "next transition recorded. got: $summary",
        )
    }

    @Test
    fun `a linear chain deeper than maxDepth is fully explored via branch depth`() {
        // Five single-exit screens in a row. A raw path-length cap of 2 would
        // stop the crawl after S2; branch depth treats a chain with no forks as
        // depth 0, so the whole onboarding-style chain is walked end to end.
        val fake = FakeAdbGateway(
            screens = (0..4).associate { i ->
                "s$i" to FakeAdbGateway.Screen(screen("s$i", listOf(Btn("next", 100, 100))))
            } + ("s5" to FakeAdbGateway.Screen(screen("s5"))),
            tapTable = (0..4).associate { i ->
                FakeAdbGateway.TapKey("s$i", 100, 100) to "s${i + 1}"
            },
            launchTarget = "s0",
        )
        val session = runBlocking {
            val store = SessionStore(tmp.newFolder("session"))
            val config = ExplorationConfig(targetPackage = pkg, maxDepth = 2, settleDelayMs = 0)
            Explorer(fake, serial = null, config = config, store = store).run(SilentListener)
        }
        assertEquals(6, session.states.size, "the whole linear chain must be explored despite maxDepth=2")
    }

    @Test
    fun `recovery climbs back with multiple BACKs instead of relaunching`() {
        // Returning from pageA needs TWO BACKs (an interstitial overlay sits
        // between it and home). The old "single BACK else relaunch" path would
        // restart the app; multi-BACK recovery climbs back without a relaunch.
        val homeXml = screen("home", listOf(Btn("a", 100, 100), Btn("b", 100, 200)))
        val gw = object : com.salaun.tristan.uiautomator.adb.AdbGateway {
            val xml = mapOf(
                "home" to homeXml,
                "overlay" to screen("overlay"),
                "pageA" to screen("pageA"),
                "pageB" to screen("pageB"),
            )
            var screen = "home"
            var launches = 0
            override suspend fun launchApp(serial: String?, pkg: String) { screen = "home"; launches++ }
            override suspend fun pressBack(serial: String?) {
                screen = when (screen) {
                    "pageA" -> "overlay" // first BACK lands on an interstitial
                    "overlay" -> "home"  // second BACK reaches home
                    "pageB" -> "home"
                    else -> screen
                }
            }
            override suspend fun inputTap(serial: String?, x: Int, y: Int) {
                if (screen == "home") screen = if (y < 150) "pageA" else "pageB"
            }
            override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
            override suspend fun screenshotPng(serial: String?): ByteArray = byteArrayOf(1)
            override suspend fun dumpUiXml(serial: String?): String = xml.getValue(screen)
        }
        val session = runBlocking {
            val store = SessionStore(tmp.newFolder("session"))
            val config = ExplorationConfig(targetPackage = pkg, settleDelayMs = 0)
            Explorer(gw, serial = null, config = config, store = store).run(SilentListener)
        }
        assertEquals(3, session.states.size, "home + pageA + pageB")
        assertEquals(1, gw.launches, "multi-BACK recovery must avoid relaunching the app")
    }

    @Test
    fun `leaving the app and being unable to BACK in triggers a kill and relaunch`() {
        // A tap opens a foreign screen that swallows BACK (a browser / external
        // app that ignores it). After a few fruitless BACKs the explorer must
        // force-stop and relaunch the target app, then carry on with the rest.
        val homeXml = screen("home", listOf(Btn("ext", 100, 100), Btn("safe", 100, 200)))
        val extXml = screen("ext").replace(pkg, "com.android.chrome")
        val gw = object : com.salaun.tristan.uiautomator.adb.AdbGateway {
            val xml = mapOf("home" to homeXml, "ext" to extXml, "safe_page" to screen("safe_page"))
            var screen = "home"
            var launches = 0
            override suspend fun launchApp(serial: String?, pkg: String) { screen = "home"; launches++ }
            override suspend fun pressBack(serial: String?) {
                // "ext" is a foreign app that ignores BACK; in-app pages go home.
                screen = if (screen == "safe_page") "home" else screen
            }
            override suspend fun inputTap(serial: String?, x: Int, y: Int) {
                if (screen == "home") screen = if (y < 150) "ext" else "safe_page"
            }
            override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
            override suspend fun screenshotPng(serial: String?): ByteArray = byteArrayOf(1)
            override suspend fun dumpUiXml(serial: String?): String = xml.getValue(screen)
        }
        val session = runBlocking {
            val store = SessionStore(tmp.newFolder("session"))
            val config = ExplorationConfig(targetPackage = pkg, settleDelayMs = 0)
            Explorer(gw, serial = null, config = config, store = store).run(SilentListener)
        }

        // The foreign screen was captured as a terminal external state…
        val leftApp = session.transitions.single { it.leftApp }
        val external = session.states.single { it.id == leftApp.to }
        assertEquals("com.android.chrome", external.packageName)
        // …being trapped outside forced a kill + relaunch (initial + ≥1 relaunch)…
        assertTrue(gw.launches >= 2, "being trapped outside the app must trigger a kill + relaunch, got ${gw.launches}")
        // …and the crawl carried on to the other button afterwards.
        assertTrue(
            session.transitions.any { !it.leftApp && it.action.resourceId.endsWith("/safe") && it.to != null },
            "exploration must continue after the relaunch",
        )
    }

    @Test
    fun `empty text fields are auto-filled before buttons are exercised`() {
        // A login screen with an empty email field gates its "Login" button.
        // The explorer must type a default value before exercising the button.
        val loginXml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" class="android.widget.FrameLayout" package="com.example.app" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node index="0" text="" content-desc="" class="android.widget.EditText" resource-id="com.example.app:id/email" package="com.example.app" clickable="true" enabled="true" bounds="[40,200][1040,320]"/>
    <node index="1" text="Login" class="android.widget.Button" resource-id="com.example.app:id/login" package="com.example.app" clickable="true" enabled="true" bounds="[40,400][1040,520]"/>
  </node>
</hierarchy>"""
        val fake = FakeAdbGateway(
            screens = mapOf(
                "login" to FakeAdbGateway.Screen(loginXml),
                "home2" to FakeAdbGateway.Screen(screen("home2")),
            ),
            tapTable = mapOf(
                FakeAdbGateway.TapKey("login", 540, 260) to "login", // tapping the field stays put
                FakeAdbGateway.TapKey("login", 540, 460) to "home2", // login navigates once filled
            ),
            launchTarget = "login",
        )
        val session = runExplorer(fake)

        val typed = fake.events.filterIsInstance<FakeAdbGateway.Event.InputText>()
        assertTrue(typed.any { it.text == "test@example.com" }, "email field must be auto-filled, got: $typed")
        assertTrue(
            session.transitions.any { it.from == "S0" && it.to == "S1" && it.action.resourceId.endsWith("/login") },
            "the login button must navigate after the field is filled",
        )
    }

    @Test
    fun `off-screen clickables are discovered by scrolling and then exercised`() {
        // home has a scrollable container. A "Top" button is visible at rest; a
        // "Hidden" button only appears after one scroll. The explorer must
        // harvest the hidden button (tagging it scrollToReveal=1) and, when
        // exercising it, scroll first so the tap lands.
        fun scrollHome(button: String, id: String, top: Int, bottom: Int) = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" class="android.widget.FrameLayout" package="com.example.app" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node index="0" class="androidx.recyclerview.widget.RecyclerView" package="com.example.app" clickable="false" enabled="true" scrollable="true" bounds="[0,200][1080,2000]">
      <node index="0" class="android.widget.TextView" resource-id="com.example.app:id/marker_home" package="com.example.app" clickable="false" enabled="true" bounds="[0,0][1,1]"/>
      <node index="1" text="$button" class="android.widget.Button" resource-id="com.example.app:id/$id" package="com.example.app" clickable="true" enabled="true" bounds="[40,$top][1040,$bottom]"/>
    </node>
  </node>
</hierarchy>"""
        val homeTop = scrollHome("Top", "top", 250, 350)        // center 540,300
        val homeScrolled = scrollHome("Hidden", "hidden", 1450, 1550) // center 540,1500
        val gw = object : com.salaun.tristan.uiautomator.adb.AdbGateway {
            var offset = 0
            var screen = "home"
            override suspend fun launchApp(serial: String?, pkg: String) { screen = "home"; offset = 0 }
            override suspend fun pressBack(serial: String?) { if (screen != "home") screen = "home" }
            override suspend fun inputTap(serial: String?, x: Int, y: Int) {
                if (screen == "home") {
                    if (offset == 0 && y in 250..350) screen = "top_page"
                    else if (offset == 1 && y in 1450..1550) screen = "hidden_page"
                }
            }
            override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
                if (screen == "home") offset = if (y1 > y2) (offset + 1).coerceAtMost(1) else (offset - 1).coerceAtLeast(0)
            }
            override suspend fun screenshotPng(serial: String?): ByteArray = byteArrayOf(1)
            override suspend fun dumpUiXml(serial: String?): String = when (screen) {
                "home" -> if (offset == 0) homeTop else homeScrolled
                "top_page" -> screen("top_page")
                else -> screen("hidden_page")
            }
        }
        val session = runBlocking {
            val store = SessionStore(tmp.newFolder("session"))
            val config = ExplorationConfig(targetPackage = pkg, settleDelayMs = 0)
            Explorer(gw, serial = null, config = config, store = store).run(SilentListener)
        }

        assertEquals(3, session.states.size, "home + top_page + hidden_page (the off-screen button was reached)")
        val home = session.states.first()
        val hidden = home.clickables.singleOrNull { it.resourceId.endsWith("/hidden") }
        assertNotNull(hidden, "the hidden button must be harvested into the state's clickables")
        assertEquals(1, hidden.scrollToReveal, "the hidden button must be tagged with the scroll needed to reveal it")
        assertTrue(
            session.transitions.any { it.from == "S0" && it.action.resourceId.endsWith("/hidden") && it.to != null },
            "the off-screen button must be exercised into a new state",
        )
    }

    /**
     * Thin wrapper around [FakeAdbGateway] that simulates a real Android
     * splash screen on every *subsequent* launch (i.e. during recovery
     * relaunches, not the very first launch). The initial launch lets the
     * exploration register the real root state; later launches force one
     * "splash" dump to be served before the real home, exactly like an
     * Android app displaying a splash between the LAUNCHER intent and its
     * real root activity becoming interactive.
     */
    private class SplashLaunchWrapper(
        private val delegate: FakeAdbGateway,
        private val splashXmlEntry: String,
    ) : com.salaun.tristan.uiautomator.adb.AdbGateway {
        private var launchCount = 0
        override suspend fun screenshotPng(serial: String?): ByteArray = delegate.screenshotPng(serial)
        override suspend fun dumpUiXml(serial: String?): String = delegate.dumpUiXml(serial)
        override suspend fun inputTap(serial: String?, x: Int, y: Int) = delegate.inputTap(serial, x, y)
        override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) =
            delegate.inputSwipe(serial, x1, y1, x2, y2, durationMs)
        override suspend fun pressBack(serial: String?) = delegate.pressBack(serial)
        override suspend fun launchApp(serial: String?, pkg: String) {
            delegate.launchApp(serial, pkg)
            launchCount++
            if (launchCount > 1) {
                delegate.pendingDrifts.addFirst(splashXmlEntry)
            }
        }
    }

    private object SilentListener : Explorer.Listener {
        override fun onLog(msg: String) = Unit
        override fun onProgress(progress: ExplorerProgress) = Unit
        override fun onSessionUpdated(session: ExplorationSession) = Unit
    }
}
