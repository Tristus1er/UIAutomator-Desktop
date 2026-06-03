package com.salaun.tristan.uiautomator.ui

import androidx.compose.ui.geometry.Offset
import com.salaun.tristan.uiautomator.explorer.ExplorationSession
import com.salaun.tristan.uiautomator.explorer.TransitionEntry
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Compact force-directed auto-layout for an exploration graph.
 *
 * The previous layered (Sugiyama) layout placed one BFS layer per column, so a
 * mostly-linear exploration — the common shape, since apps are walked as a long
 * onboarding/wizard chain with a few branches — stretched into a single very
 * wide row. Deeper crawls only made the strip longer and less readable.
 *
 * This replacement arranges the cards as a spring system (Fruchterman–Reingold):
 *
 *  1. **Deterministic grid seed** — nodes are ordered by BFS distance from the
 *     sources (so chain-neighbours start adjacent) and seeded on a near-square
 *     grid. Starting from a 2-D block (rather than a straight line) lets the
 *     forces fold a long chain into a compact area instead of leaving it
 *     stretched out. No randomness is used anywhere, so the layout is stable
 *     across runs and across the desktop view / HTML export.
 *
 *  2. **Force relaxation** — every pair of nodes repels (keeps the graph from
 *     collapsing); every forward edge attracts (pulls connected screens
 *     together → short links). A cooling schedule caps per-iteration movement
 *     so the system settles instead of oscillating.
 *
 *  3. **Overlap removal** — a few separation passes guarantee no two cards
 *     overlap, treating each as a [colStep]×[rowStep] cell (both larger than a
 *     card, so cards keep a gap).
 *
 *  4. **Normalisation** — positions are shifted so the top-left card sits at
 *     the margin.
 *
 * The result is "grouped, shortest possible links, no overlap".
 *
 * @return a map `stateId → (x, y)` in dp-equivalent float units.
 */
internal fun autoPositions(
    session: ExplorationSession,
    colStep: Int,
    rowStep: Int,
    margin: Int,
    iterations: Int = 600,
): Map<String, Offset> {
    val ids: List<String> = session.states.map { it.id }
    if (ids.isEmpty()) return emptyMap()
    val m = margin.toFloat()
    if (ids.size == 1) return mapOf(ids[0] to Offset(m, m))

    val indexOfId = ids.withIndex().associate { (i, id) -> id to i }
    val edges: List<Pair<String, String>> = session.transitions
        .mapNotNull { it.forwardEdge() }
        .filter { it.first != it.second }
        .distinct()

    // BFS-distance seed order: chain neighbours land next to each other on the
    // grid, giving the relaxation a head start.
    val layer = bfsLayers(ids, edges)
    val order = ids.sortedWith(compareBy({ layer[it] ?: 0 }, { indexOfId[it] ?: 0 }))

    val n = ids.size
    val k = colStep.toDouble().coerceAtLeast(1.0) // ideal edge length
    val cols = ceil(sqrt(n.toDouble())).toInt().coerceAtLeast(1)
    val rows = ceil(n.toDouble() / cols).toInt().coerceAtLeast(1)

    // Confine the layout to a compact frame just big enough to hold every node
    // as a cell. This is the original Fruchterman–Reingold mechanism: without
    // it, all-pairs repulsion would stretch a linear chain back into a straight
    // line (repulsion is minimised by spreading out). A tight frame forces the
    // chain to fold so the result stays a grouped 2-D block.
    val frameW = (cols * colStep).toDouble()
    val frameH = (rows * rowStep).toDouble()

    // Mutable positions as [x, y] doubles, keyed by id.
    val px = DoubleArray(n)
    val py = DoubleArray(n)
    val pos = HashMap<String, Int>(n) // id -> slot index in px/py
    val slotLayer = IntArray(n)
    order.forEachIndexed { slot, id ->
        pos[id] = slot
        slotLayer[slot] = layer[id] ?: 0
        px[slot] = (slot % cols) * colStep.toDouble()
        py[slot] = (slot / cols) * rowStep.toDouble()
    }

    // Pre-resolve edges to slot indices.
    val edgeSlots = edges.mapNotNull { (u, v) ->
        val a = pos[u] ?: return@mapNotNull null
        val b = pos[v] ?: return@mapNotNull null
        a to b
    }

    val dispX = DoubleArray(n)
    val dispY = DoubleArray(n)
    var temp = k
    val cool = temp / (iterations + 1)

    repeat(iterations) {
        java.util.Arrays.fill(dispX, 0.0)
        java.util.Arrays.fill(dispY, 0.0)

        // Repulsion between every pair (O(n²); n is bounded by maxStates).
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var dx = px[i] - px[j]
                var dy = py[i] - py[j]
                var dist = sqrt(dx * dx + dy * dy)
                if (dist < 1e-4) {
                    // Coincident: nudge apart deterministically (no RNG) so the
                    // layout stays reproducible.
                    dx = ((i - j) % 7 + 1) * 0.01
                    dy = ((i + j) % 5 + 1) * 0.01
                    dist = sqrt(dx * dx + dy * dy)
                }
                val rep = k * k / dist
                val ux = dx / dist
                val uy = dy / dist
                dispX[i] += ux * rep; dispY[i] += uy * rep
                dispX[j] -= ux * rep; dispY[j] -= uy * rep
            }
        }
        // Attraction along edges.
        for ((a, b) in edgeSlots) {
            var dx = px[a] - px[b]
            var dy = py[a] - py[b]
            var dist = sqrt(dx * dx + dy * dy)
            if (dist < 1e-4) dist = 1e-4
            val att = dist * dist / k
            val ux = dx / dist
            val uy = dy / dist
            dispX[a] -= ux * att; dispY[a] -= uy * att
            dispX[b] += ux * att; dispY[b] += uy * att
        }
        // Direction-consistency bias: gently push a forward edge so its
        // deeper-layer end sits lower than the shallower one, giving the whole
        // graph a coherent top→down flow. This stops two connected tiles from
        // ending up reversed (which crosses their arrows). It's weak and the
        // frame still confines the result, so a long chain folds rather than
        // stretching into a single column.
        val flowBias = k * 0.10
        for ((a, b) in edgeSlots) {
            val la = slotLayer[a]
            val lb = slotLayer[b]
            if (lb > la) { dispY[a] -= flowBias; dispY[b] += flowBias }
            else if (la > lb) { dispY[a] += flowBias; dispY[b] -= flowBias }
        }
        // Apply, capped by the cooling temperature, and confine to the frame.
        for (i in 0 until n) {
            val len = sqrt(dispX[i] * dispX[i] + dispY[i] * dispY[i])
            if (len > 1e-9) {
                val cap = min(len, temp)
                px[i] = (px[i] + dispX[i] / len * cap).coerceIn(0.0, frameW)
                py[i] = (py[i] + dispY[i] / len * cap).coerceIn(0.0, frameH)
            }
        }
        temp -= cool
    }

    // Alignment snap: pull near-aligned nodes onto a shared column / row so the
    // result reads as tidy lines instead of a slightly-jittered cloud — the
    // "reward alignment" goal. Gentle and bounded; the overlap pass afterwards
    // separates any cards this brought too close, pushing them apart along the
    // axis that is NOT aligned so the shared column/row is preserved.
    val alignThrX = colStep * 0.35
    val alignThrY = rowStep * 0.35
    repeat(14) {
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (kotlin.math.abs(px[i] - px[j]) < alignThrX) {
                    val mid = (px[i] + px[j]) / 2
                    px[i] += (mid - px[i]) * 0.5
                    px[j] += (mid - px[j]) * 0.5
                }
                if (kotlin.math.abs(py[i] - py[j]) < alignThrY) {
                    val mid = (py[i] + py[j]) / 2
                    py[i] += (mid - py[i]) * 0.5
                    py[j] += (mid - py[j]) * 0.5
                }
            }
        }
    }

    resolveOverlaps(px, py, cellW = colStep.toDouble(), cellH = rowStep.toDouble())

    // Anchor the entry point (S0 = the first state) as the clear leftmost,
    // vertically-centred node, so the reader instantly spots where the graph
    // begins. It gets its own column to the left of everything else (no overlap
    // possible there), centred against the rest of the graph.
    val rootSlot = pos[ids.first()]
    if (rootSlot != null && n > 1) {
        var minOtherX = Double.POSITIVE_INFINITY
        var sumY = 0.0
        for (i in 0 until n) if (i != rootSlot) {
            if (px[i] < minOtherX) minOtherX = px[i]
            sumY += py[i]
        }
        px[rootSlot] = minOtherX - colStep
        py[rootSlot] = sumY / (n - 1)
    }

    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    for (i in 0 until n) {
        if (px[i] < minX) minX = px[i]
        if (py[i] < minY) minY = py[i]
    }
    return ids.associateWith { id ->
        val slot = pos[id] ?: 0
        Offset((px[slot] - minX + margin).toFloat(), (py[slot] - minY + margin).toFloat())
    }
}

/**
 * Separates overlapping cards. Each node occupies a [cellW]×[cellH] rectangle
 * centred on its position; when two cells overlap we push the pair apart along
 * the axis of least penetration. A bounded number of passes converges quickly
 * because the force stage already spreads nodes ~one cell apart; the passes
 * only clean up the residual collisions.
 */
private fun resolveOverlaps(px: DoubleArray, py: DoubleArray, cellW: Double, cellH: Double, passes: Int = 60) {
    val n = px.size
    repeat(passes) {
        var moved = false
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val dx = px[i] - px[j]
                val dy = py[i] - py[j]
                val overlapX = cellW - kotlin.math.abs(dx)
                val overlapY = cellH - kotlin.math.abs(dy)
                if (overlapX <= 0 || overlapY <= 0) continue // not overlapping
                moved = true
                if (overlapX < overlapY) {
                    val push = overlapX / 2 + 0.5
                    val dir = if (dx >= 0) 1.0 else -1.0
                    px[i] += dir * push; px[j] -= dir * push
                } else {
                    val push = overlapY / 2 + 0.5
                    val dir = if (dy >= 0) 1.0 else -1.0
                    py[i] += dir * push; py[j] -= dir * push
                }
            }
        }
        if (!moved) return
    }
}

/** Only transitions that describe a productive (non-loop, non-error) edge. */
private fun TransitionEntry.forwardEdge(): Pair<String, String>? {
    val dest = to ?: return null
    if (loop || leftApp || errorMessage != null || dest == from) return null
    return from to dest
}

/**
 * Assigns each node a layer equal to its breadth-first distance from any
 * source (= a state with no incoming forward edge). Used only to derive a
 * good seed order for the force layout: nodes close in the graph start close
 * on the grid. Disconnected components and back-edge-only orphans fall to 0.
 */
private fun bfsLayers(
    nodeIds: List<String>,
    forwardEdges: List<Pair<String, String>>,
): Map<String, Int> {
    val outgoing = HashMap<String, MutableList<String>>()
    val hasIncoming = HashSet<String>()
    for ((from, to) in forwardEdges) {
        outgoing.getOrPut(from) { mutableListOf() } += to
        hasIncoming += to
    }
    val sources = nodeIds.filter { it !in hasIncoming }.ifEmpty { nodeIds.take(1) }
    val layer = HashMap<String, Int>()
    val queue: ArrayDeque<String> = ArrayDeque()
    for (s in sources) {
        layer[s] = 0
        queue += s
    }
    while (queue.isNotEmpty()) {
        val u = queue.removeFirst()
        val du = layer[u] ?: 0
        for (v in outgoing[u].orEmpty()) {
            val current = layer[v]
            if (current == null || current > du + 1) {
                layer[v] = du + 1
                queue += v
            }
        }
    }
    for (id in nodeIds) layer.getOrPut(id) { 0 }
    return layer
}
