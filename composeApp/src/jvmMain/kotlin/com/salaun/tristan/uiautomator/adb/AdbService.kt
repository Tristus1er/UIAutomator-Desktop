package com.salaun.tristan.uiautomator.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

data class AdbDevice(val serial: String, val description: String) {
    val deviceName: String get() =
        Regex("""\bdevice:(\S+)""").find(description)?.groupValues?.get(1) ?: serial
    val displayName: String get() {
        val dev = Regex("""\bdevice:(\S+)""").find(description)?.groupValues?.get(1)
        return if (dev != null) "$dev ($serial)" else serial
    }
}

class AdbError(message: String) : RuntimeException(message)

class AdbService(@Volatile var adbPath: String) : AdbGateway {

    data class ExecResult(val exitCode: Int, val stdout: ByteArray, val stderr: String) {
        val stdoutText: String get() = String(stdout, Charsets.UTF_8)
    }

    private fun run(
        args: List<String>,
        timeoutMs: Long = 20_000,
        serial: String? = null
    ): ExecResult {
        val cmd = mutableListOf(adbPath)
        if (serial != null) {
            cmd += "-s"; cmd += serial
        }
        cmd += args
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(false)
        val process = pb.start()
        process.outputStream.close()

        val out = ByteArrayOutputStream()
        val err = StringBuilder()

        val outThread = Thread {
            val buf = ByteArray(8192)
            val s = process.inputStream
            while (true) {
                val n = s.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
        val errThread = Thread {
            process.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                r.forEachLine { err.appendLine(it) }
            }
        }
        outThread.start(); errThread.start()

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            outThread.join(500); errThread.join(500)
            throw AdbError("adb ${args.joinToString(" ")} timed out after ${timeoutMs}ms")
        }
        outThread.join(); errThread.join()
        return ExecResult(process.exitValue(), out.toByteArray(), err.toString())
    }

    suspend fun version(): String = withContext(Dispatchers.IO) {
        val r = run(listOf("version"), timeoutMs = 5_000)
        if (r.exitCode != 0) throw AdbError("adb version failed: ${r.stderr}")
        r.stdoutText.lineSequence().firstOrNull().orEmpty().trim()
    }

    suspend fun listDevices(): List<AdbDevice> = withContext(Dispatchers.IO) {
        val r = run(listOf("devices", "-l"), timeoutMs = 5_000)
        if (r.exitCode != 0) throw AdbError("adb devices failed: ${r.stderr}")
        r.stdoutText
            .lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val parts = trimmed.split(Regex("\\s+"), limit = 2)
                val serial = parts.getOrNull(0) ?: return@mapNotNull null
                val rest = parts.getOrNull(1).orEmpty()
                if (!rest.startsWith("device")) return@mapNotNull null
                val desc = rest.substringAfter("device").trim()
                AdbDevice(serial, desc)
            }
            .toList()
    }

    override suspend fun screenshotPng(serial: String?): ByteArray = withContext(Dispatchers.IO) {
        val r = run(listOf("exec-out", "screencap", "-p"), timeoutMs = 15_000, serial = serial)
        if (r.exitCode != 0 || r.stdout.isEmpty()) {
            throw AdbError("screencap failed: ${r.stderr.ifBlank { "empty output" }}")
        }
        r.stdout
    }

    override suspend fun inputTap(serial: String?, x: Int, y: Int): Unit = withContext(Dispatchers.IO) {
        val r = run(listOf("shell", "input", "tap", x.toString(), y.toString()), timeoutMs = 5_000, serial = serial)
        if (r.exitCode != 0) throw AdbError("input tap failed: ${r.stderr.ifBlank { "exit=${r.exitCode}" }}")
    }

    override suspend fun inputText(serial: String?, text: String): Unit = withContext(Dispatchers.IO) {
        // `adb shell input text` uses %s for spaces and treats a bare argument
        // literally otherwise. We keep the explorer's default values shell-safe
        // (see StateOps.defaultValueFor) so no further escaping is required.
        val arg = text.replace(" ", "%s")
        val r = run(listOf("shell", "input", "text", arg), timeoutMs = 5_000, serial = serial)
        if (r.exitCode != 0) throw AdbError("input text failed: ${r.stderr.ifBlank { "exit=${r.exitCode}" }}")
    }

    override suspend fun inputSwipe(
        serial: String?,
        x1: Int, y1: Int, x2: Int, y2: Int,
        durationMs: Int,
    ): Unit = withContext(Dispatchers.IO) {
        val r = run(
            args = listOf(
                "shell", "input", "swipe",
                x1.toString(), y1.toString(),
                x2.toString(), y2.toString(),
                durationMs.toString(),
            ),
            // The blocking call takes at least durationMs on the device, plus
            // the round-trip overhead, so we leave a generous buffer.
            timeoutMs = (durationMs + 5_000L).coerceAtLeast(5_000L),
            serial = serial,
        )
        if (r.exitCode != 0) throw AdbError("input swipe failed: ${r.stderr.ifBlank { "exit=${r.exitCode}" }}")
    }

    override suspend fun pressBack(serial: String?): Unit = withContext(Dispatchers.IO) {
        val r = run(listOf("shell", "input", "keyevent", "KEYCODE_BACK"), timeoutMs = 5_000, serial = serial)
        if (r.exitCode != 0) throw AdbError("keyevent BACK failed: ${r.stderr.ifBlank { "exit=${r.exitCode}" }}")
    }

    suspend fun pressHome(serial: String?) = withContext(Dispatchers.IO) {
        run(listOf("shell", "input", "keyevent", "KEYCODE_HOME"), timeoutMs = 5_000, serial = serial)
    }

    override suspend fun launchApp(serial: String?, pkg: String): Unit = withContext(Dispatchers.IO) {
        // Force-stop first so the app comes up on its root activity rather
        // than resuming wherever the user (or a previous exploration pass)
        // left it. Without this, `monkey` only brings the existing task to
        // the foreground, which means "relaunch" silently fails to reset
        // navigation state between exploration runs. `am force-stop` is a
        // silent no-op when the package isn't currently running, so we do
        // not need to check its exit code.
        run(
            args = listOf("shell", "am", "force-stop", pkg),
            timeoutMs = 5_000,
            serial = serial,
        )

        val r = run(
            args = listOf("shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"),
            timeoutMs = 10_000,
            serial = serial,
        )
        val text = r.stdoutText + "\n" + r.stderr
        if (r.exitCode != 0 || text.contains("monkey aborted") || text.contains("No activities found")) {
            throw AdbError("launch $pkg failed: ${text.trim().take(200)}")
        }
    }

    /**
     * Toggles SystemUI demo mode on. Two stages:
     *
     *  1. Flip the `sysui_demo_allowed` global setting — without this the
     *     SystemUI broadcast receiver ignores demo commands. On stock retail
     *     devices this setting may be read-only without root, in which case
     *     the function returns `false` and demo mode is unavailable.
     *  2. Send the `enter` broadcast and then the per-element overrides
     *     (clock, battery, network, notifications) so the status bar shows
     *     a fixed snapshot for the duration of the exploration.
     */
    override suspend fun enterDemoMode(serial: String?): Boolean = withContext(Dispatchers.IO) {
        val allow = run(
            args = listOf("shell", "settings", "put", "global", "sysui_demo_allowed", "1"),
            timeoutMs = 5_000,
            serial = serial,
        )
        if (allow.exitCode != 0) return@withContext false

        // The "enter" broadcast must come first so SystemUI starts honouring
        // subsequent overrides. After that, push fixed values for each tile.
        // We don't fail on a per-tile error: if e.g. the network override is
        // refused on a particular OEM ROM, the clock/battery overrides may
        // still take effect, which is already a big win over the default.
        if (broadcastDemo(serial, "enter").exitCode != 0) return@withContext false
        broadcastDemo(serial, "clock", "hhmm" to "1200")
        broadcastDemo(serial, "battery", "level" to "100", "plugged" to "false")
        broadcastDemo(serial, "network", "wifi" to "show", "level" to "4", "fully" to "true")
        broadcastDemo(serial, "network", "mobile" to "show", "datatype" to "lte", "level" to "4")
        broadcastDemo(serial, "notifications", "visible" to "false")
        true
    }

    override suspend fun exitDemoMode(serial: String?) {
        withContext(Dispatchers.IO) {
            // Best-effort: even if the device never accepted demo mode, the
            // exit broadcast is harmless (the receiver just ignores it).
            runCatching { broadcastDemo(serial, "exit") }
        }
    }

    private fun broadcastDemo(serial: String?, command: String, vararg extras: Pair<String, String>): ExecResult {
        val args = mutableListOf("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", command)
        for ((k, v) in extras) {
            args += "-e"; args += k; args += v
        }
        return run(args, timeoutMs = 5_000, serial = serial)
    }

    suspend fun topPackage(serial: String?): String? = withContext(Dispatchers.IO) {
        val r = run(listOf("shell", "dumpsys", "activity", "activities"), timeoutMs = 5_000, serial = serial)
        if (r.exitCode != 0) return@withContext null
        Regex("""mResumedActivity.*?\{[^}]*?([\w.]+)/[\w.\$]+""")
            .find(r.stdoutText)?.groupValues?.getOrNull(1)
            ?: Regex("""topResumedActivity.*?\{[^}]*?([\w.]+)/[\w.\$]+""")
                .find(r.stdoutText)?.groupValues?.getOrNull(1)
    }

    override suspend fun dumpUiXml(serial: String?): String = withContext(Dispatchers.IO) {
        retryWithBackoff(
            backoffsMs = UI_DUMP_BACKOFFS_MS,
            isTransient = ::isTransientDumpError,
        ) { dumpOnce(serial) }
    }

    private fun dumpOnce(serial: String?): String {
        val remote = "/sdcard/window_dump.xml"
        val dump = run(listOf("shell", "uiautomator", "dump", remote), timeoutMs = 20_000, serial = serial)
        val dumpOutput = dump.stdoutText + "\n" + dump.stderr
        if (dump.exitCode != 0 || !dumpOutput.contains("dumped")) {
            throw AdbError("uiautomator dump failed: ${dumpOutput.trim()}")
        }
        val cat = run(listOf("exec-out", "cat", remote), timeoutMs = 15_000, serial = serial)
        if (cat.exitCode != 0 || cat.stdout.isEmpty()) {
            throw AdbError("cat $remote failed: ${cat.stderr.ifBlank { "empty output" }}")
        }
        return String(cat.stdout, Charsets.UTF_8)
    }

    companion object {
        fun verifyPath(path: String): Boolean {
            val f = File(path)
            if (!f.isFile || !f.canExecute()) return false
            return try {
                val p = ProcessBuilder(path, "version")
                    .redirectErrorStream(true)
                    .start()
                val ok = p.waitFor(5, TimeUnit.SECONDS)
                if (!ok) { p.destroyForcibly(); false }
                else p.exitValue() == 0
            } catch (_: Exception) {
                false
            }
        }
    }
}
