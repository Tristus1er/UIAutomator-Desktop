package com.salaun.tristan.uiautomator.adb

import kotlinx.coroutines.delay

/**
 * Runs [block] once, and if it fails with a transient error, retries up to
 * `backoffsMs.size` times, waiting `backoffsMs[i]` milliseconds between
 * attempts `i` and `i+1`. A throwable is considered transient when
 * [isTransient] returns `true`; any non-transient failure is rethrown
 * immediately.
 *
 * The total number of attempts is therefore `backoffsMs.size + 1`. When every
 * attempt fails, the last observed throwable is rethrown.
 */
suspend fun <T> retryWithBackoff(
    backoffsMs: LongArray,
    isTransient: (Throwable) -> Boolean = { true },
    block: suspend () -> T,
): T {
    var last: Throwable? = null
    for (attempt in 0..backoffsMs.size) {
        try {
            return block()
        } catch (e: Throwable) {
            last = e
            if (!isTransient(e) || attempt == backoffsMs.size) throw e
            delay(backoffsMs[attempt])
        }
    }
    // Unreachable: either we returned or threw from the loop.
    throw last ?: IllegalStateException("retryWithBackoff exited without any attempt")
}

/** Default backoff for a UIAutomator dump that fails because the UI is still animating. */
internal val UI_DUMP_BACKOFFS_MS = longArrayOf(1_000L, 3_000L, 10_000L)

/** Recognises transient errors coming out of `adb shell uiautomator dump`. */
internal fun isTransientDumpError(t: Throwable): Boolean {
    val msg = t.message?.lowercase() ?: return false
    return "idle state" in msg || "could not get idle" in msg
}
