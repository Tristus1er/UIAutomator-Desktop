package com.salaun.tristan.uiautomator.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiBoundsTest {

    @Test
    fun `contains is inclusive on top-left and exclusive on bottom-right`() {
        val b = UiBounds(10, 20, 30, 40)
        assertTrue(b.contains(10, 20))
        assertTrue(b.contains(29, 39))
        assertFalse(b.contains(30, 39))
        assertFalse(b.contains(29, 40))
        assertFalse(b.contains(9, 20))
    }

    @Test
    fun `width and height are zero-safe`() {
        val empty = UiBounds(10, 10, 10, 10)
        assertEquals(0, empty.width)
        assertEquals(0, empty.height)
        assertEquals(0L, empty.area)
    }

    @Test
    fun `area uses long to avoid overflow on large screens`() {
        val b = UiBounds(0, 0, 10_000, 10_000)
        assertEquals(100_000_000L, b.area)
    }
}
