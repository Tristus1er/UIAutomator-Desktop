package com.salaun.tristan.uiautomator.explorer

import com.salaun.tristan.uiautomator.adb.AdbGateway

/**
 * Scripted fake implementation of [AdbGateway] used by the explorer tests.
 *
 * The fake keeps a handful of virtual "screens" keyed by name. Each screen
 * bundles an XML dump and a tiny PNG-like byte array (the content does not
 * matter since the explorer never decodes it — only hashes its structure).
 *
 * A tiny interpreter drives the explorer through a set of rules:
 *   - `launchApp` returns to the screen identified by `launchTarget`.
 *   - `pressBack` pops the top of an internal navigation stack.
 *   - `inputTap(x, y)` consults [tapTable], a map from (screen, x, y) to a
 *     target screen, which is pushed onto the stack.
 *
 * The fake also records every call so tests can assert on counts and order.
 */
class FakeAdbGateway(
    private val screens: Map<String, Screen>,
    private val tapTable: Map<TapKey, String>,
    /**
     * The screen that `launchApp()` places the fake on. Exposed as a mutable
     * property so tests can simulate, for example, an onboarding flow that
     * only appears on the very first launch: flip [launchTarget] to the
     * post-onboarding screen after the first launch has completed.
     */
    var launchTarget: String,
    /**
     * Callback invoked just before every `dumpUiXml` returns. Tests can use
     * it to throw (simulating a broken dump) or to mutate [pendingDrifts]
     * / other state at a precise moment in the exploration.
     */
    var onDumpHook: ((screenName: String) -> Unit)? = null,
) : AdbGateway {

    data class Screen(val xml: String, val png: ByteArray = byteArrayOf(0x89.toByte(), 'P'.code.toByte()))
    data class TapKey(val screen: String, val x: Int, val y: Int)

    sealed class Event {
        data class Launch(val pkg: String) : Event()
        object Back : Event()
        data class Tap(val x: Int, val y: Int, val fromScreen: String, val toScreen: String) : Event()
        data class Dump(val screen: String) : Event()
        data class Screenshot(val screen: String) : Event()
        data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Int) : Event()
        data class InputText(val text: String, val screen: String) : Event()
    }

    private val stack: ArrayDeque<String> = ArrayDeque()
    val events: MutableList<Event> = mutableListOf()

    /**
     * Queue of screen names used to simulate drift. When non-empty, the next
     * `dumpUiXml` calls return the XML of the queued screen instead of the
     * actual current screen. One queued value is consumed per dump call; the
     * queue is drained in FIFO order.
     */
    val pendingDrifts: ArrayDeque<String> = ArrayDeque()

    val currentScreen: String
        get() = stack.lastOrNull() ?: error("fake has no current screen; did you forget launchApp?")

    override suspend fun launchApp(serial: String?, pkg: String) {
        events += Event.Launch(pkg)
        stack.clear()
        stack.addLast(launchTarget)
    }

    override suspend fun pressBack(serial: String?) {
        events += Event.Back
        if (stack.size > 1) stack.removeLast()
        // Bottom of stack stays: pressing BACK at root is a no-op for the fake.
    }

    override suspend fun inputTap(serial: String?, x: Int, y: Int) {
        val from = currentScreen
        val next = tapTable[TapKey(from, x, y)]
            ?: error("no tap mapping from $from at ($x, $y)")
        events += Event.Tap(x, y, from, next)
        // A tap that "stays" on the same screen is a self-loop: do not grow the stack.
        if (next != from) stack.addLast(next)
    }

    override suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        // The fake doesn't model scroll content — swipes are recorded for
        // assertion purposes but otherwise no-op. Tests exercising scroll
        // capture mock the gateway directly instead.
        events += Event.Swipe(x1, y1, x2, y2, durationMs)
    }

    override suspend fun inputText(serial: String?, text: String) {
        events += Event.InputText(text, currentScreen)
    }

    override suspend fun screenshotPng(serial: String?): ByteArray {
        val screen = currentScreen
        events += Event.Screenshot(screen)
        return screens[screen]?.png ?: error("no screen data for $screen")
    }

    override suspend fun dumpUiXml(serial: String?): String {
        val screen = currentScreen
        onDumpHook?.invoke(screen)
        events += Event.Dump(screen)
        val drift = pendingDrifts.removeFirstOrNull()
        if (drift != null) {
            return screens[drift]?.xml ?: error("no screen data for $drift")
        }
        return screens[screen]?.xml ?: error("no screen data for $screen")
    }
}
