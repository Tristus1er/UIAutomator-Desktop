package com.salaun.tristan.uiautomator.ui

import androidx.compose.ui.geometry.Offset
import com.salaun.tristan.uiautomator.explorer.ClickableRef
import com.salaun.tristan.uiautomator.explorer.ExplorationConfig
import com.salaun.tristan.uiautomator.explorer.ExplorationSession
import com.salaun.tristan.uiautomator.explorer.SerialBounds
import com.salaun.tristan.uiautomator.explorer.StateEntry
import com.salaun.tristan.uiautomator.explorer.TransitionEntry
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property-based tests for the force-directed auto-layout. Rather than pin exact
 * grid coordinates (the layout is no longer a rigid grid), these assert the
 * qualities the user asked for: a compact 2-D arrangement (NOT one long row),
 * no overlapping cards, short links, and deterministic output.
 */
class GraphAutoLayoutTest {

    private val margin = 40
    private val colStep = 320
    private val rowStep = 230
    private val cardW = 220f
    private val cardH = 170f

    private fun state(id: String, depth: Int = 0): StateEntry = StateEntry(
        id = id,
        fingerprint = id,
        packageName = "com.example.app",
        depth = depth,
        screenshotPath = "states/$id.png",
        xmlPath = "states/$id.xml",
        clickables = emptyList(),
        pathFromRoot = emptyList(),
    )

    private val dummyAction = ClickableRef(
        resourceId = "id/x", className = "android.widget.Button", text = "", contentDesc = "",
        bounds = SerialBounds(0, 0, 1, 1), tapX = 0, tapY = 0,
    )

    private fun edge(from: String, to: String) = TransitionEntry(from, to, dummyAction)

    private fun session(states: List<StateEntry>, edges: List<TransitionEntry>) = ExplorationSession(
        id = "test",
        targetPackage = "com.example.app",
        startedAt = 0,
        config = ExplorationConfig(targetPackage = "com.example.app"),
        states = states.toMutableList(),
        transitions = edges.toMutableList(),
    )

    private fun layout(states: List<StateEntry>, edges: List<TransitionEntry>) =
        autoPositions(session(states, edges), colStep, rowStep, margin)

    private fun assertNoOverlap(pos: Map<String, Offset>) {
        val e = pos.entries.toList()
        for (i in e.indices) for (j in i + 1 until e.size) {
            val a = e[i].value; val b = e[j].value
            val overlap = abs(a.x - b.x) < cardW && abs(a.y - b.y) < cardH
            assertTrue(!overlap, "cards ${e[i].key}@$a and ${e[j].key}@$b overlap")
        }
    }

    @Test
    fun `empty session yields no positions`() {
        assertTrue(layout(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `single state sits at the margin`() {
        val pos = layout(listOf(state("S0")), emptyList())
        assertEquals(Offset(margin.toFloat(), margin.toFloat()), pos["S0"])
    }

    @Test
    fun `a linear chain reads left-to-right, S0 leftmost, without overlap`() {
        val nodes = (0..15).map { state("N$it") }
        val chain = (0 until 15).map { edge("N$it", "N${it + 1}") }
        val pos = layout(nodes, chain)

        assertNoOverlap(pos)
        // Each step advances exactly one column to the right — a clean flow.
        for (i in 0 until 15) {
            assertTrue(
                pos["N${i + 1}"]!!.x > pos["N$i"]!!.x,
                "N${i + 1} must sit to the right of N$i",
            )
        }
        assertEquals(pos["N0"]!!.x, pos.values.minOf { it.x }, "N0 (entry) is the leftmost tile")
    }

    @Test
    fun `a tree is drawn without any edge crossings`() {
        // A small two-level tree. The barycenter ordering must place it so no
        // two parent→child arrows cross.
        val nodes = (0..6).map { state("S$it") }
        val edges = listOf(
            "S0" to "S1", "S0" to "S2",
            "S1" to "S3", "S1" to "S4",
            "S2" to "S5", "S2" to "S6",
        )
        val pos = layout(nodes, edges.map { edge(it.first, it.second) })
        assertEquals(0, forwardCrossings(pos, edges), "a tree must lay out crossing-free")
    }

    /** Counts crossings between pairs of adjacent-layer edges from their tile positions. */
    private fun forwardCrossings(pos: Map<String, Offset>, edges: List<Pair<String, String>>): Int {
        fun col(id: String) = ((pos[id]!!.x - margin) / colStep).roundToInt()
        fun row(id: String) = (pos[id]!!.y - margin) / rowStep
        var crossings = 0
        for (i in edges.indices) for (j in i + 1 until edges.size) {
            val (u1, v1) = edges[i]
            val (u2, v2) = edges[j]
            if (col(u1) == col(u2) && col(v1) == col(v2) && col(v1) == col(u1) + 1) {
                if ((row(u1) - row(u2)) * (row(v1) - row(v2)) < 0f) crossings++
            }
        }
        return crossings
    }

    @Test
    fun `cards never overlap on a branching graph`() {
        // A small tree with a couple of back-edges.
        val nodes = (0..9).map { state("S$it") }
        val edges = listOf(
            edge("S0", "S1"), edge("S0", "S2"), edge("S1", "S3"), edge("S1", "S4"),
            edge("S2", "S5"), edge("S2", "S6"), edge("S3", "S7"), edge("S5", "S8"),
            edge("S8", "S9"), edge("S9", "S2"), // back-edge
        )
        assertNoOverlap(layout(nodes, edges))
    }

    @Test
    fun `connected states end up close together (short links)`() {
        val nodes = (0..11).map { state("S$it") }
        val edges = listOf(
            edge("S0", "S1"), edge("S1", "S2"), edge("S2", "S3"), edge("S3", "S4"),
            edge("S4", "S5"), edge("S4", "S6"), edge("S6", "S7"), edge("S7", "S8"),
            edge("S8", "S9"), edge("S4", "S10"), edge("S10", "S11"),
        )
        val pos = layout(nodes, edges)
        val lengths = edges.map { (f, t) ->
            val a = pos[f]!!; val b = pos[t]!!; hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
        }
        val avg = lengths.average()
        // Forward edges should stay short — well under the canvas diagonal.
        assertTrue(avg < 2.0 * colStep, "average forward-edge length too long: $avg")
    }

    @Test
    fun `layout is deterministic`() {
        val nodes = (0..12).map { state("S$it") }
        val edges = (0 until 12).map { edge("S$it", "S${it + 1}") } + edge("S12", "S3")
        val a = layout(nodes, edges)
        val b = layout(nodes, edges)
        assertEquals(a.keys, b.keys)
        for (id in a.keys) {
            assertEquals(a[id]!!.x, b[id]!!.x, 0.0001f, "x differs for $id")
            assertEquals(a[id]!!.y, b[id]!!.y, 0.0001f, "y differs for $id")
        }
    }

    @Test
    fun `tiles snap onto shared rows or columns (alignment)`() {
        // A small branching graph. After the alignment pass, nodes should share
        // columns/rows rather than each sitting at its own jittered coordinate:
        // at least one pair must be tightly aligned on x or y.
        val nodes = (0..8).map { state("S$it") }
        val edges = listOf(
            edge("S0", "S1"), edge("S0", "S2"), edge("S1", "S3"), edge("S1", "S4"),
            edge("S2", "S5"), edge("S2", "S6"), edge("S3", "S7"), edge("S5", "S8"),
        )
        val pos = layout(nodes, edges)
        assertNoOverlap(pos)
        val e = pos.values.toList()
        var alignedPairs = 0
        for (i in e.indices) for (j in i + 1 until e.size) {
            if (abs(e[i].x - e[j].x) < 3f || abs(e[i].y - e[j].y) < 3f) alignedPairs++
        }
        assertTrue(alignedPairs > 0, "alignment pass should produce shared rows/columns, got $alignedPairs aligned pairs")
    }

    @Test
    fun `the entry point S0 is anchored as the leftmost node`() {
        val nodes = (0..7).map { state("S$it") }
        val edges = listOf(
            edge("S0", "S1"), edge("S1", "S2"), edge("S2", "S3"),
            edge("S0", "S4"), edge("S4", "S5"), edge("S5", "S6"), edge("S6", "S7"),
        )
        val pos = layout(nodes, edges)
        val s0x = pos["S0"]!!.x
        assertTrue(
            nodes.drop(1).all { pos[it.id]!!.x > s0x },
            "S0 (the app entry point) must sit strictly left of every other tile",
        )
    }

    @Test
    fun `self-loops and left-app transitions are ignored by the layout`() {
        val nodes = listOf(state("R"), state("A"), state("B"))
        val edges = listOf(
            edge("R", "A"), edge("R", "B"),
            TransitionEntry("B", "B", dummyAction, loop = true),
            TransitionEntry("A", null, dummyAction, leftApp = true),
        )
        // Must not throw and must place every node without overlap.
        val pos = layout(nodes, edges)
        assertEquals(3, pos.size)
        assertNoOverlap(pos)
    }
}
