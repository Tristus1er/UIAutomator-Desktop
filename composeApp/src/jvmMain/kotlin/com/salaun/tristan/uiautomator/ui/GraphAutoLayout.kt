package com.salaun.tristan.uiautomator.ui

import androidx.compose.ui.geometry.Offset
import com.salaun.tristan.uiautomator.explorer.ExplorationSession
import com.salaun.tristan.uiautomator.explorer.TransitionEntry

/**
 * Layered **tidy-tree** left-to-right auto-layout for an exploration graph.
 *
 * An exploration graph is a *rooted directed* graph: one entry point (S0) and
 * edges flowing outward. We draw it as a hierarchy:
 *
 *  - **Columns = distance from S0.** Layer assignment is a single-source BFS
 *    from S0 (the one and only source), so S0 is the leftmost column and every
 *    tile's column is its real depth from the entry point. Columns are one
 *    [colStep] apart (> card width) ⇒ **no horizontal overlap**.
 *
 *  - **Rows = a Reingold–Tilford tidy tree.** Within the BFS spanning tree,
 *    leaves are laid out on consecutive rows and every parent is centred on its
 *    children. Consequences, *by construction*:
 *      - a child sits directly across from its parent ⇒ **short arrows**, and a
 *        straight chain stays a straight horizontal line (no diagonal drift);
 *      - sibling subtrees occupy disjoint row ranges ⇒ **no overlap** and
 *        **no crossings** between tree edges;
 *      - the drawing is **compact** — its height is just the number of leaves.
 *
 * The handful of non-tree edges (back-edges to an ancestor, cross-edges) are
 * simply drawn as curves on top; they don't disturb the tidy placement.
 *
 * @return a map `stateId → (x, y)` in dp-equivalent float units.
 */
internal fun autoPositions(
    session: ExplorationSession,
    colStep: Int,
    rowStep: Int,
    margin: Int,
    @Suppress("UNUSED_PARAMETER") iterations: Int = 8,
): Map<String, Offset> {
    val ids: List<String> = session.states.map { it.id }
    if (ids.isEmpty()) return emptyMap()
    val m = margin.toFloat()
    if (ids.size == 1) return mapOf(ids[0] to Offset(m, m))

    // Positioning edges: everything that lands on a real tile, INCLUDING leftApp
    // edges to a captured external screen (so that screen sits next to the one
    // that opened it). Order is preserved so the spanning tree and the child
    // order are deterministic and follow exploration order.
    val edges: List<Pair<String, String>> = session.transitions
        .mapNotNull { it.layoutEdge() }
        .filter { it.first != it.second }
        .distinct()

    // --- Layer assignment: single-source BFS from S0. -----------------------
    val layerOf = bfsLayers(ids, edges)

    // --- BFS spanning tree (parent = the layer-1 predecessor that first
    //     reaches a node). Children keep edge (exploration) order. ------------
    val children = LinkedHashMap<String, MutableList<String>>()
    val hasParent = HashSet<String>()
    for ((from, to) in edges) {
        if (to !in hasParent && (layerOf[to] ?: 0) == (layerOf[from] ?: 0) + 1) {
            children.getOrPut(from) { mutableListOf() }.add(to)
            hasParent += to
        }
    }

    // --- Reingold–Tilford row assignment (in-order: leaves sequential,
    //     internal nodes centred over their children). ------------------------
    val rowOf = HashMap<String, Double>()
    var nextLeafRow = 0.0
    fun assignRows(node: String) {
        val kids = children[node].orEmpty()
        if (kids.isEmpty()) {
            rowOf[node] = nextLeafRow
            nextLeafRow += 1.0
        } else {
            for (k in kids) assignRows(k)
            rowOf[node] = (rowOf[kids.first()]!! + rowOf[kids.last()]!!) / 2.0
        }
    }
    fun runReingoldTilford() {
        rowOf.clear()
        nextLeafRow = 0.0
        // The single real root is S0; any node left without a tree parent (only
        // reachable via a back/cross edge) is laid out as its own small root.
        for (id in ids) if (id !in hasParent) assignRows(id)
        for (id in ids) if (id !in rowOf) { rowOf[id] = nextLeafRow; nextLeafRow += 1.0 }
    }
    runReingoldTilford()

    // Pull cross-connected siblings together: reorder each parent's children by
    // the barycenter (mean row) of *all* their neighbours — including back- and
    // cross-edges — then re-run the tidy placement. A few passes converge and
    // noticeably shrink the long back-edges and the residual crossings inside a
    // branchy cluster, without disturbing the no-overlap / tree-crossing-free
    // guarantees (children stay a contiguous block per parent).
    val adj = HashMap<String, MutableList<String>>()
    for ((f, t) in edges) {
        adj.getOrPut(f) { mutableListOf() }.add(t)
        adj.getOrPut(t) { mutableListOf() }.add(f)
    }
    repeat(6) {
        for ((_, kids) in children) {
            if (kids.size > 1) {
                kids.sortBy { kid ->
                    val ns = adj[kid].orEmpty().mapNotNull { rowOf[it] }
                    if (ns.isEmpty()) rowOf[kid] ?: 0.0 else ns.average()
                }
            }
        }
        runReingoldTilford()
    }

    // --- Positioning. -------------------------------------------------------
    val minRow = rowOf.values.minOrNull() ?: 0.0
    return ids.associateWith { id ->
        Offset(
            x = (margin + (layerOf[id] ?: 0) * colStep).toFloat(),
            y = (margin + (rowOf[id]!! - minRow) * rowStep).toFloat(),
        )
    }
}

/**
 * Edges used to position tiles: every transition that lands on a real state,
 * including `leftApp` edges to a captured external screen. Only self-loops and
 * error edges (no destination) are excluded.
 */
private fun TransitionEntry.layoutEdge(): Pair<String, String>? {
    val dest = to ?: return null
    if (loop || errorMessage != null || dest == from) return null
    return from to dest
}

/**
 * Assigns each node a layer equal to its breadth-first distance from **the
 * single source S0** (the first state = the app's entry point). Every screen is
 * reached from S0, so there is exactly one source and each tile's column is its
 * real distance from the entry. Unreachable nodes fall back to layer 0.
 */
private fun bfsLayers(
    nodeIds: List<String>,
    forwardEdges: List<Pair<String, String>>,
): Map<String, Int> {
    val outgoing = HashMap<String, MutableList<String>>()
    for ((from, to) in forwardEdges) outgoing.getOrPut(from) { mutableListOf() }.add(to)

    val layer = HashMap<String, Int>()
    val root = nodeIds.first()
    layer[root] = 0
    val queue: ArrayDeque<String> = ArrayDeque<String>().apply { add(root) }
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
