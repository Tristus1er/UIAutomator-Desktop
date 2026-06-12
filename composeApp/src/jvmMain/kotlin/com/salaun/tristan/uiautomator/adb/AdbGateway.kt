package com.salaun.tristan.uiautomator.adb

/**
 * Narrow surface of ADB operations that the explorer depends on.
 *
 * Extracted from [AdbService] so tests can drive the explorer with a
 * scripted fake instead of requiring a real device.
 */
interface AdbGateway {
    suspend fun screenshotPng(serial: String?): ByteArray
    suspend fun dumpUiXml(serial: String?): String
    suspend fun inputTap(serial: String?, x: Int, y: Int)

    /**
     * Types [text] into the currently-focused field (via `adb shell input
     * text`). Used by the explorer to auto-fill forms. Default no-op so test
     * fakes that don't care about text entry need not implement it.
     */
    suspend fun inputText(serial: String?, text: String) {}
    suspend fun inputSwipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int)
    suspend fun pressBack(serial: String?)
    suspend fun launchApp(serial: String?, pkg: String)

    /**
     * Enables Android's SystemUI "demo mode" so the status bar shows fixed
     * values (clock, battery, signal) for the duration of an exploration.
     * Without it, the clock ticking every minute creates a new fingerprint
     * for every screen the explorer captures, inflating the state count
     * with near-duplicates. Returns `true` when the broadcast was accepted
     * by the device, `false` otherwise (e.g. `sysui_demo_allowed` is locked
     * down on a stock retail device).
     */
    suspend fun enterDemoMode(serial: String?): Boolean = false

    /** Mirror of [enterDemoMode]: tells SystemUI to drop the static overrides. */
    suspend fun exitDemoMode(serial: String?) {}

    /**
     * Fully-qualified component (`pkg/cls`) of the foreground Activity, or
     * `null` when it cannot be determined. A strong, nearly-free identity
     * signal for state dedup: two screens hosted by different Activities are
     * never the same state, however similar their trees look. Default `null`
     * keeps test fakes oblivious.
     */
    suspend fun currentFocusedActivity(serial: String?): String? = null

    /**
     * Exported activities declared by [pkg]'s manifest (those reachable with
     * `am start -n`), as `pkg/cls` components. Parsed from the package's
     * Activity Resolver Table, so only intent-filterable activities appear —
     * a lower bound on the app's real screen inventory, used for the
     * end-of-run coverage report and the direct-launch phase. Default empty.
     */
    suspend fun listExportedActivities(serial: String?, pkg: String): List<String> = emptyList()

    /** Starts [component] (`pkg/cls`) with `am start -n`. Returns `false` on failure. */
    suspend fun startActivity(serial: String?, component: String): Boolean = false

    /**
     * Dismisses the soft keyboard if it is showing. After auto-filling text
     * fields the IME can cover half the screen and steal every subsequent tap;
     * the explorer hides it before exercising the screen's clickables.
     */
    suspend fun hideIme(serial: String?) {}

    /**
     * Clears the device's crash logcat buffer. Called once at exploration
     * start so [recentCrashLog] only ever reports crashes caused by this run.
     */
    suspend fun clearCrashLog(serial: String?) {}

    /**
     * First lines of a FATAL EXCEPTION recorded for [pkg] in the crash buffer
     * since [clearCrashLog], or `null`. Lets the explorer attribute a sudden
     * return to the launcher to an app crash — and report the stack header.
     */
    suspend fun recentCrashLog(serial: String?, pkg: String): String? = null
}
