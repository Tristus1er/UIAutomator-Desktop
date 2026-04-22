package com.salaun.tristan.uiautomator.settings

import java.io.File
import java.util.Properties

class AppSettings private constructor(private val file: File) {

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

    private fun save() {
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "UIAutomator Desktop settings") }
    }

    companion object {
        private const val KEY_ADB_PATH = "adb.path"
        private const val KEY_LAST_DEVICE = "adb.lastDevice"

        fun load(): AppSettings {
            val home = System.getProperty("user.home") ?: "."
            val dir = File(home, ".uiautomator-desktop")
            return AppSettings(File(dir, "config.properties"))
        }
    }
}
