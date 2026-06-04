package com.salaun.tristan.uiautomator.rules

import com.salaun.tristan.uiautomator.model.UiBounds
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure-geometry tests for [RuleEngine.computeSwipes] — no device involved. */
class ScrollMathTest {

    private val vertical = UiBounds(0, 0, 1000, 1000) // 1000×1000
    private val horizontal = UiBounds(0, 0, 2000, 100) // 2000 wide

    @Test
    fun `percent of container maps to that fraction of travel`() {
        val swipes = RuleEngine.computeSwipes(vertical, ScrollDirection.DOWN, ScrollAmount.Percent(50))
        assertEquals(1, swipes.size)
        assertEquals(500, abs(swipes[0].y1 - swipes[0].y2), "50% of a 1000px container = 500px travel")
        assertTrue(swipes[0].y1 > swipes[0].y2, "DOWN moves the finger upward (yStart > yEnd)")
    }

    @Test
    fun `pixels beyond the max single-swipe split into several swipes`() {
        val swipes = RuleEngine.computeSwipes(vertical, ScrollDirection.DOWN, ScrollAmount.Pixels(2000))
        // maxTravel = 90% of 1000 = 900, so 2000px needs ceil(2000/900) = 3 swipes.
        assertEquals(3, swipes.size)
    }

    @Test
    fun `items use the supplied item height`() {
        val swipes = RuleEngine.computeSwipes(vertical, ScrollDirection.DOWN, ScrollAmount.Items(3), itemHeightPx = 200)
        assertEquals(1, swipes.size)
        assertEquals(600, abs(swipes[0].y1 - swipes[0].y2), "3 items × 200px = 600px")
    }

    @Test
    fun `up reverses the swipe endpoints relative to down`() {
        val down = RuleEngine.computeSwipes(vertical, ScrollDirection.DOWN, ScrollAmount.Percent(40)).single()
        val up = RuleEngine.computeSwipes(vertical, ScrollDirection.UP, ScrollAmount.Percent(40)).single()
        assertTrue(down.y1 > down.y2)
        assertTrue(up.y1 < up.y2)
        assertEquals(abs(down.y1 - down.y2), abs(up.y1 - up.y2), "same travel, opposite direction")
    }

    @Test
    fun `horizontal scroll travels along the width`() {
        val swipes = RuleEngine.computeSwipes(horizontal, ScrollDirection.RIGHT, ScrollAmount.Percent(50))
        assertEquals(1, swipes.size)
        val s = swipes[0]
        assertEquals(1000, abs(s.x1 - s.x2), "50% of 2000px width = 1000px")
        assertEquals(s.y1, s.y2, "a horizontal swipe keeps Y constant")
        assertTrue(s.x1 > s.x2, "RIGHT moves the finger leftward")
    }

    @Test
    fun `to-end yields no precomputed swipes`() {
        assertTrue(RuleEngine.computeSwipes(vertical, ScrollDirection.DOWN, ScrollAmount.ToEnd).isEmpty())
    }
}
