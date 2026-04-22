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

class AdbService(@Volatile var adbPath: String) {

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

    suspend fun screenshotPng(serial: String?): ByteArray = withContext(Dispatchers.IO) {
        val r = run(listOf("exec-out", "screencap", "-p"), timeoutMs = 15_000, serial = serial)
        if (r.exitCode != 0 || r.stdout.isEmpty()) {
            throw AdbError("screencap failed: ${r.stderr.ifBlank { "empty output" }}")
        }
        r.stdout
    }

    suspend fun dumpUiXml(serial: String?): String = withContext(Dispatchers.IO) {
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
        String(cat.stdout, Charsets.UTF_8)
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
