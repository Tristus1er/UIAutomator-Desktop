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
}
