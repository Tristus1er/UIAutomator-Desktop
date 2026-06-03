package com.salaun.tristan.uiautomator.adb

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RetryTest {

    /** Very small backoffs so the test runs in a few milliseconds. */
    private val fastBackoffs = longArrayOf(1L, 1L, 1L)

    @Test
    fun `block is called once when the first attempt succeeds`() = runBlocking {
        var calls = 0
        val out = retryWithBackoff(fastBackoffs) {
            calls++
            "ok"
        }
        assertEquals("ok", out)
        assertEquals(1, calls)
    }

    @Test
    fun `transient failures are retried until one succeeds`() = runBlocking {
        var calls = 0
        val out = retryWithBackoff(fastBackoffs, isTransient = { true }) {
            calls++
            if (calls < 3) error("transient $calls")
            "ok"
        }
        assertEquals("ok", out)
        assertEquals(3, calls)
    }

    @Test
    fun `non-transient failures are rethrown on the first attempt`() {
        var calls = 0
        val thrown = assertFailsWith<IllegalStateException> {
            runBlocking {
                retryWithBackoff(fastBackoffs, isTransient = { false }) {
                    calls++
                    error("fatal")
                }
            }
        }
        assertEquals(1, calls)
        assertEquals("fatal", thrown.message)
    }

    @Test
    fun `every transient retry is attempted and the last error is rethrown`() {
        var calls = 0
        val thrown = assertFailsWith<IllegalStateException> {
            runBlocking {
                retryWithBackoff(fastBackoffs, isTransient = { true }) {
                    calls++
                    error("attempt $calls")
                }
            }
        }
        assertEquals(4, calls, "initial attempt + 3 retries")
        assertEquals("attempt 4", thrown.message)
    }

    @Test
    fun `isTransientDumpError recognises the idle-state message from uiautomator`() {
        assertTrue(isTransientDumpError(RuntimeException("ERROR could not get idle state.")))
        assertTrue(isTransientDumpError(RuntimeException("uiautomator dump failed: ERROR could not get idle state.")))
    }

    @Test
    fun `isTransientDumpError does not match unrelated errors`() {
        assertEquals(false, isTransientDumpError(RuntimeException("device offline")))
        assertEquals(false, isTransientDumpError(RuntimeException("adb: device not found")))
    }
}
