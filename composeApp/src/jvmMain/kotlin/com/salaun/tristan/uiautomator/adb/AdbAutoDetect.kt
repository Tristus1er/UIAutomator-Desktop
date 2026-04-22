package com.salaun.tristan.uiautomator.adb

import java.io.File

object AdbAutoDetect {

    fun detect(): String? {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val exe = if (windows) "adb.exe" else "adb"

        val candidates = mutableListOf<File>()

        System.getenv("ANDROID_HOME")?.let { candidates += File(it, "platform-tools/$exe") }
        System.getenv("ANDROID_SDK_ROOT")?.let { candidates += File(it, "platform-tools/$exe") }

        val home = System.getProperty("user.home") ?: ""
        val commonSdkDirs = listOfNotNull(
            if (windows) System.getenv("LOCALAPPDATA")?.let { "$it/Android/Sdk" } else null,
            if (windows) System.getenv("APPDATA")?.let { "$it/Android/Sdk" } else null,
            "$home/AppData/Local/Android/Sdk",
            "$home/Library/Android/sdk",
            "$home/Android/Sdk",
            "/usr/local/share/android-sdk",
            "/opt/android-sdk",
            "C:/Android/Sdk"
        )
        commonSdkDirs.forEach { candidates += File(it, "platform-tools/$exe") }

        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            if (dir.isNotBlank()) candidates += File(dir, exe)
        }

        for (c in candidates) {
            if (c.isFile && c.canExecute() && AdbService.verifyPath(c.absolutePath)) {
                return c.absolutePath
            }
        }
        return null
    }
}
