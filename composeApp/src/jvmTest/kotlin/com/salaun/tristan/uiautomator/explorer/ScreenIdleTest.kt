package com.salaun.tristan.uiautomator.explorer

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenIdleTest {

    @Test
    fun `returns stable once two consecutive screenshots are byte-identical`() = runBlocking {
        val frames = listOf(
            byteArrayOf(1, 2, 3),
            byteArrayOf(3, 4, 5),
            byteArrayOf(3, 4, 5),
        )
        val iter = frames.iterator()
        val result = waitForScreenIdle(
            takeScreenshot = { iter.next() },
            pollMs = 1L,
            maxMs = 2_000L,
        )
        assertTrue(result.stable)
        assertEquals(3, result.screenshotsTaken)
        assertContentEquals(frames.last(), result.png)
    }

    @Test
    fun `returns stable immediately when the screen was never animating`() = runBlocking {
        val still = byteArrayOf(7, 7, 7, 7)
        val result = waitForScreenIdle(
            takeScreenshot = { still },
            pollMs = 1L,
            maxMs = 2_000L,
        )
        assertTrue(result.stable)
        assertEquals(2, result.screenshotsTaken, "one baseline + one matching screenshot")
    }

    @Test
    fun `gives up after the timeout when the screen keeps changing`() = runBlocking {
        var counter = 0
        val result = waitForScreenIdle(
            takeScreenshot = {
                counter++
                byteArrayOf(counter.toByte(), (counter shr 8).toByte())
            },
            pollMs = 1L,
            maxMs = 30L,
        )
        assertFalse(result.stable)
        assertTrue(result.screenshotsTaken >= 2)
    }
}
