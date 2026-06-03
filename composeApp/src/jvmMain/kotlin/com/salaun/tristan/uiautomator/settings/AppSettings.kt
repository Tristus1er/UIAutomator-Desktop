package com.salaun.tristan.uiautomator.settings

import java.io.File
import java.util.Properties

class AppSettings internal constructor(private val file: File) {

    private val props = Properties()

    init {
        if (file.isFile) {
            file.inputStream().use { props.load(it) }
        }
    }

    var adbPath: String?
        get() = props.getProperty(KEY_ADB_PATH)?.takeIf { it.isNotBlank() }
        set(value) {
            if (value.isNullOrBlank()) props.remove(KEY_ADB_PATH) else props.setProperty(KEY_ADB_PATH, value)
            save()
        }

    var lastDeviceSerial: String?
        get() = props.getProperty(KEY_LAST_DEVICE)?.takeIf { it.isNotBlank() }
        set(value) {
            if (value.isNullOrBlank()) props.remove(KEY_LAST_DEVICE) else props.setProperty(KEY_LAST_DEVICE, value)
            save()
        }

    /**
     * Preferred UI language as a 2-letter code (e.g. "en", "fr"). `null`
     * means "auto-detect from the operating system on each launch".
     */
    var languageCode: String?
        get() = props.getProperty(KEY_LANGUAGE)?.takeIf { it.isNotBlank() }
        set(value) {
            if (value.isNullOrBlank()) props.remove(KEY_LANGUAGE) else props.setProperty(KEY_LANGUAGE, value)
            save()
        }

    // --- Exploration config persistence -------------------------------------
    //
    // We persist the last-used exploration knobs so a restart keeps the
    // target package, caps and timings the user dialed in. `null` means the
    // setting has never been written — callers use their default.

    var explorationTargetPackage: String?
        get() = props.getProperty(KEY_EXPLORATION_PKG)?.takeIf { it.isNotBlank() }
        set(value) { putOrRemove(KEY_EXPLORATION_PKG, value); save() }

    var explorationMaxStates: Int?
        get() = props.getProperty(KEY_EXPLORATION_MAX_STATES)?.toIntOrNull()
        set(value) { putOrRemove(KEY_EXPLORATION_MAX_STATES, value?.toString()); save() }

    var explorationMaxDepth: Int?
        get() = props.getProperty(KEY_EXPLORATION_MAX_DEPTH)?.toIntOrNull()
        set(value) { putOrRemove(KEY_EXPLORATION_MAX_DEPTH, value?.toString()); save() }

    var explorationMaxClickablesPerState: Int?
        get() = props.getProperty(KEY_EXPLORATION_MAX_CLICKS)?.toIntOrNull()
        set(value) { putOrRemove(KEY_EXPLORATION_MAX_CLICKS, value?.toString()); save() }

    var explorationSettleDelayMs: Long?
        get() = props.getProperty(KEY_EXPLORATION_SETTLE_MS)?.toLongOrNull()
        set(value) { putOrRemove(KEY_EXPLORATION_SETTLE_MS, value?.toString()); save() }

    var explorationIdleMaxWaitMs: Long?
        get() = props.getProperty(KEY_EXPLORATION_IDLE_MS)?.toLongOrNull()
        set(value) { putOrRemove(KEY_EXPLORATION_IDLE_MS, value?.toString()); save() }

    private fun putOrRemove(key: String, value: String?) {
        if (value.isNullOrBlank()) props.remove(key) else props.setProperty(key, value)
    }

    private fun save() {
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "UIAutomator Desktop settings") }
    }

    companion object {
        private const val KEY_ADB_PATH = "adb.path"
        private const val KEY_LAST_DEVICE = "adb.lastDevice"
        private const val KEY_LANGUAGE = "ui.language"
        private const val KEY_EXPLORATION_PKG = "exploration.targetPackage"
        private const val KEY_EXPLORATION_MAX_STATES = "exploration.maxStates"
        private const val KEY_EXPLORATION_MAX_DEPTH = "exploration.maxDepth"
        private const val KEY_EXPLORATION_MAX_CLICKS = "exploration.maxClickablesPerState"
        private const val KEY_EXPLORATION_SETTLE_MS = "exploration.settleDelayMs"
        private const val KEY_EXPLORATION_IDLE_MS = "exploration.idleMaxWaitMs"

        fun load(): AppSettings {
            val home = System.getProperty("user.home") ?: "."
            val dir = File(home, ".uiautomator-desktop")
            return AppSettings(File(dir, "config.properties"))
        }
    }
}
