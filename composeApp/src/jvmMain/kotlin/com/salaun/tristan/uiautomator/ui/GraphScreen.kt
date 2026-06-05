package com.salaun.tristan.uiautomator.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.salaun.tristan.uiautomator.AppState
import com.salaun.tristan.uiautomator.Screen
import com.salaun.tristan.uiautomator.explorer.ExplorationSession
import com.salaun.tristan.uiautomator.explorer.GraphLayout
import com.salaun.tristan.uiautomator.explorer.SerialPoint
import com.salaun.tristan.uiautomator.explorer.SessionStore
import com.salaun.tristan.uiautomator.explorer.StateEntry
import com.salaun.tristan.uiautomator.explorer.TransitionEntry
import com.salaun.tristan.uiautomator.i18n.LocalStrings
import com.salaun.tristan.uiautomator.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.awt.Cursor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Horizontal double-arrow cursor for the vertical splitter between graph and details. */
private val HorizontalResizeCursor = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

/** Four-way arrow cursor hinting that a graph card can be dragged to reposition. */
private val MoveCursor = PointerIcon(Cursor(Cursor.MOVE_CURSOR))

private const val CARD_W = 220
private const val CARD_H = 170
private const val COL_STEP = 320
private const val ROW_STEP = 230
private const val MARGIN = 40

private const val MIN_SCALE = 0.2f
private const val MAX_SCALE = 3f
private const val DETAILS_MIN_DP = 280f
private const val DETAILS_MAX_DP = 900f

// Compose `Constraints` cannot represent a pixel dimension beyond ~262143.
// With density up to 2x and zoom up to MAX_SCALE, we have ~43k dp of headroom
// on each axis. We cap the unscaled canvas at 30000 dp — well below the limit
// even at the most extreme zoom × density combo — so a long DFS chain
// (~1 card per layer × 320 dp) cannot blow up the renderer.
private const val MAX_CANVAS_DIM_DP = 30_000

/**
 * Length of the arrowhead triangle in pixels. Shared between the curve-
 * shrinking logic in [EdgeCanvas] and the [arrowheadAt] helper so the line
 * always lands exactly at the base of the triangle, no overlap, no gap.
 */
private const val ARROW_LEN = 8f

@Composable
fun GraphScreen(state: AppState) {
    val strings = LocalStrings.current
    val session = state.explorerSession
    val store = state.explorerStore

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenToolbar(
            title = strings.graphTitle,
            middle = {
                session?.let {
                    Text(
                        strings.graphHeaderFmt.format(it.states.size, it.transitions.size),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // HTML export only makes sense once we have a session loaded.
                if (session != null) {
                    OutlinedButton(onClick = {
                        val suggested = session.targetPackage.ifBlank { "graph" }
                        val out = pickHtmlExportFile(strings.graphExportHtmlDialogTitle, suggested)
                        if (out != null) state.exportGraphHtml(out)
                    }) { Text(strings.graphExportHtml) }
                }
            },
            nav = {
                ToolbarNavButton(strings.home, onClick = { state.go(Screen.Main) })
                ToolbarNavButton(strings.toolbarExplorer, onClick = { state.go(Screen.Explorer) })
                ToolbarNavButton(strings.toolbarSessions, onClick = { state.go(Screen.Sessions) })
            },
        )

        if (session == null || store == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(strings.graphNoSession)
            }
        } else {
            var selectedId by remember(session.id) {
                mutableStateOf<String?>(session.states.firstOrNull()?.id)
            }
            var detailsWidth by remember(session.id) { mutableStateOf(420.dp) }
            var largeViewFor by remember(session.id) { mutableStateOf<String?>(null) }
            // The "Outgoing transitions" row the pointer is hovering — drives the
            // arrow highlight on the canvas and the action-bounds overlay on the
            // selected screenshot.
            var hoveredTransition by remember(session.id) { mutableStateOf<TransitionEntry?>(null) }
            // When non-null, the delete-confirmation dialog is shown for the
            // pending set of state ids. We compute the set of transitions
            // about to be removed once at confirm-time so the user knows
            // exactly what they're agreeing to.
            var pendingDelete by remember(session.id) { mutableStateOf<Set<String>?>(null) }

            Column(modifier = Modifier.fillMaxSize()) {
            SessionPathBar(store)
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant)) {
                    GraphCanvas(
                        session = session,
                        store = store,
                        selectedId = selectedId,
                        hoveredTransition = hoveredTransition,
                        onSelect = { selectedId = it },
                        onEnlarge = { largeViewFor = it },
                        onRequestDelete = { ids -> pendingDelete = ids },
                        onRequestMerge = { ids ->
                            // The state's own selection set is updated by the
                            // canvas; we drive the persistent mutation here.
                            state.mergeGraphStates(ids)
                            // After merge only the primary remains. Keep it as
                            // the focused state so the details panel shows the
                            // surviving entry rather than nothing.
                            val primaryId = session.states.firstOrNull { it.id in ids }?.id
                            if (primaryId != null) selectedId = primaryId
                        },
                    )
                }
                VerticalSplitter(
                    onDrag = { deltaDp ->
                        val next = (detailsWidth.value - deltaDp).coerceIn(DETAILS_MIN_DP, DETAILS_MAX_DP)
                        detailsWidth = next.dp
                    },
                )
                DetailsPanel(
                    session = session,
                    store = store,
                    selectedId = selectedId,
                    hoveredTransition = hoveredTransition,
                    onSelectState = { selectedId = it },
                    onEnlarge = { largeViewFor = it },
                    onHoverTransition = { hoveredTransition = it },
                    modifier = Modifier.width(detailsWidth).fillMaxHeight(),
                )
            }
            }

            largeViewFor?.let { stateId ->
                val entry = session.states.firstOrNull { it.id == stateId }
                if (entry != null) {
                    ScreenshotDetailWindow(
                        session = session,
                        store = store,
                        stateEntry = entry,
                        onCloseRequest = { largeViewFor = null },
                        onOpenState = { targetId ->
                            selectedId = targetId
                            largeViewFor = null
                        },
                    )
                }
            }

            pendingDelete?.let { ids ->
                val targetsLabel = ids.sorted().joinToString(", ")
                val affectedTransitions = session.transitions.count { it.from in ids || it.to in ids }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { pendingDelete = null },
                    title = { Text(strings.graphDeleteConfirmTitle) },
                    text = {
                        Text(strings.graphDeleteConfirmBodyFmt.format(targetsLabel, affectedTransitions))
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            state.deleteGraphStates(ids)
                            // If the currently-selected state is being deleted,
                            // move focus to whatever's still around (or null).
                            if (selectedId in ids) {
                                selectedId = session.states.firstOrNull { it.id !in ids }?.id
                            }
                            pendingDelete = null
                        }) {
                            Text(strings.delete, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                            Text(strings.cancel)
                        }
                    },
                )
            }
        }
    }
}

/**
 * Thin bar under the graph toolbar showing the session's on-disk directory.
 * The path is selectable and a Copy button puts it on the clipboard. Shown only
 * for a session that has a directory (i.e. one persisted to / loaded from disk).
 */
@Composable
private fun SessionPathBar(store: SessionStore) {
    val strings = LocalStrings.current
    val path = store.baseDir.absolutePath
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
        OutlinedButton(onClick = { copyTextToClipboard(path) }) { Text(strings.copy) }
    }
    androidx.compose.material3.HorizontalDivider()
}

private data class Layout(
    val positions: Map<String, Offset>,
    val totalW: Int,
    val totalH: Int,
)

/**
 * Merges the auto-computed positions with any manual overrides and recomputes
 * the virtual canvas size so drag-dropped cards don't fall off the scroll bounds.
 */
private fun effectiveLayout(
    session: ExplorationSession,
    base: Map<String, Offset>,
    overrides: Map<String, Offset>,
): Layout {
    val merged = session.states.associate { s ->
        s.id to (overrides[s.id] ?: base[s.id] ?: Offset.Zero)
    }
    val maxX = merged.values.maxOfOrNull { it.x } ?: 0f
    val maxY = merged.values.maxOfOrNull { it.y } ?: 0f
    val rawW = (maxX + CARD_W + MARGIN).coerceAtLeast((CARD_W + 2 * MARGIN).toFloat())
    val rawH = (maxY + CARD_H + MARGIN).coerceAtLeast((CARD_H + 2 * MARGIN).toFloat())

    // Long DFS chains can produce a layered auto-layout wider than Compose's
    // Constraints can represent (~262 143 px). When the raw bounds exceed our
    // safe cap, scale all positions proportionally so cards still fit on a
    // bounded canvas. Cards keep their nominal size; only the spacing between
    // them shrinks. Users can zoom in to read individual nodes.
    val largest = max(rawW, rawH)
    val factor = if (largest > MAX_CANVAS_DIM_DP) MAX_CANVAS_DIM_DP / largest else 1f
    val finalPositions = if (factor < 1f)
        merged.mapValues { (_, o) -> Offset(o.x * factor, o.y * factor) }
    else merged
    val w = (rawW * factor).toInt().coerceAtLeast(CARD_W + 2 * MARGIN)
    val h = (rawH * factor).toInt().coerceAtLeast(CARD_H + 2 * MARGIN)
    return Layout(finalPositions, w, h)
}

@Composable
private fun GraphCanvas(
    session: ExplorationSession,
    store: SessionStore,
    selectedId: String?,
    hoveredTransition: TransitionEntry?,
    onSelect: (String) -> Unit,
    onEnlarge: (String) -> Unit,
    onRequestDelete: (Set<String>) -> Unit,
    onRequestMerge: (Set<String>) -> Unit,
) {
    val strings = LocalStrings.current
    val rootId = remember(session) { session.states.firstOrNull()?.id }
    val basePositions = remember(session) {
        autoPositions(session, colStep = COL_STEP, rowStep = ROW_STEP, margin = MARGIN)
    }
    val overrides = remember(session.id) { mutableStateMapOf<String, Offset>() }
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var scale by remember(session.id) { mutableStateOf(1f) }

    // Multi-selection: cards the user has explicitly added to a group via
    // Shift-click (additive) or Ctrl-click (toggle). When the user drags any
    // card belonging to this set, all selected cards translate together. The
    // primary `selectedId` (the value driving the right-hand details panel)
    // tracks the most recent click; visual highlight reflects set membership.
    val multiSelection: SnapshotStateSet<String> = remember(session.id) {
        mutableStateSetOf<String>().apply { selectedId?.let { add(it) } }
    }
    // The most recent selection this canvas itself drove (card click / context
    // menu). We compare against it so that when [selectedId] changes from the
    // OUTSIDE — the details panel's "Open State" button or an outgoing-transition
    // row — we know to reset the visual selection set onto that card. Card clicks
    // route through [selectInternal], which keeps this in step so Shift/Ctrl
    // multi-selection is never clobbered by the sync effect below.
    var lastInternalSelection: String? by remember(session.id) { mutableStateOf(selectedId) }
    val selectInternal: (String) -> Unit = { id ->
        lastInternalSelection = id
        onSelect(id)
    }
    LaunchedEffect(selectedId) {
        if (selectedId != lastInternalSelection) {
            // Selection arrived from outside the canvas → make that card THE
            // selection so its border lights up exactly as a direct click would.
            multiSelection.clear()
            selectedId?.let { multiSelection += it }
            lastInternalSelection = selectedId
        }
    }
    // Marquee rectangle being drawn while the user Shift-drags on the
    // background. Coordinates are in unscaled inner-content dp, so the
    // overlay aligns with the card layout regardless of the current zoom.
    // `null` means no marquee is in progress.
    var marqueeRectDp: Rect? by remember(session.id) { mutableStateOf(null) }
    // Layout coordinates of (a) the scrollable viewport Box that owns the
    // pointer input and (b) the unscaled inner content Box that hosts the
    // cards and the marquee overlay. We capture them via
    // [onGloballyPositioned] and, at marquee time, ask Compose to convert
    // a pointer position from one to the other through
    // [LayoutCoordinates.localPositionOf]. That call accounts for *every*
    // intervening transform — scroll offset, graphicsLayer scale, future
    // reorderings — so the marquee math no longer has to reproduce the
    // viewport→content mapping by hand. The previous hand-rolled formula
    // double-counted the scroll offset on some compositions, dropping the
    // marquee far below the user's pointer at non-trivial zoom levels.
    var pointerHostCoords: LayoutCoordinates? by remember(session.id) { mutableStateOf(null) }
    var contentHostCoords: LayoutCoordinates? by remember(session.id) { mutableStateOf(null) }
    // Live snap guides drawn while a card is being dragged: the dragged
    // card's chosen edge/center has aligned with another card on this axis.
    // `null` means no snap is currently active for that axis.
    var snapGuideXDp: Float? by remember(session.id) { mutableStateOf(null) }
    var snapGuideYDp: Float? by remember(session.id) { mutableStateOf(null) }
    // True ("would-be") position of the dragged primary card before snapping
    // is applied. We accumulate the cursor's deltas onto this value so that
    // when the cursor pulls the card off a magnet, the card catches up to
    // exactly where the cursor is — instead of starting fresh from the
    // snapped position and lagging behind by the captured offset.
    var dragIntendedPrimary: Offset? by remember(session.id) { mutableStateOf(null) }

    // Undo / redo stacks: each entry is a copy of the `overrides` map taken
    // BEFORE a user-initiated layout change, so that undoing restores the
    // state from before the change. We cap at 10 to bound memory and to
    // match the user-visible promise. Both stacks are reset when the session
    // changes — undo across sessions doesn't make sense.
    val undoStack = remember(session.id) { ArrayDeque<Map<String, Offset>>() }
    val redoStack = remember(session.id) { ArrayDeque<Map<String, Offset>>() }
    val undoLimit = 10
    // Marker that flips on the first frame of a drag and clears at drag end,
    // so we capture exactly one snapshot per drag (not one per onDrag call).
    var inDragSession by remember(session.id) { mutableStateOf(false) }
    // Focus requester used to grab keyboard focus on the canvas so Ctrl+Z /
    // Ctrl+Y are routed to the undo/redo handler.
    val focusRequester = remember { FocusRequester() }

    // Load any persisted layout overrides when the session changes.
    var overridesLoaded by remember(session.id) { mutableStateOf(false) }
    LaunchedEffect(session.id, store.baseDir.absolutePath) {
        val loaded = withContext(Dispatchers.IO) { store.loadLayout() }
        overrides.clear()
        loaded?.positions?.forEach { (id, p) -> overrides[id] = Offset(p.x, p.y) }
        overridesLoaded = true
    }

    val layout by remember(session, basePositions, overrides) {
        derivedStateOf { effectiveLayout(session, basePositions, overrides) }
    }

    fun persistLayout() {
        val snapshot = overrides.toMap()
        scope.launch(Dispatchers.IO) {
            store.saveLayout(GraphLayout(snapshot.mapValues { (_, o) -> SerialPoint(o.x, o.y) }))
        }
    }

    // --- Undo / redo helpers.
    // `snapshotForUndo` is called BEFORE any layout-modifying action so the
    // snapshot represents the pre-change state. `undo` and `redo` swap the
    // current state with the top of the matching stack so chained calls
    // walk through history both ways.
    fun snapshotForUndo() {
        undoStack.addLast(overrides.toMap())
        if (undoStack.size > undoLimit) undoStack.removeFirst()
        redoStack.clear()
    }
    fun replaceOverrides(snapshot: Map<String, Offset>) {
        overrides.clear()
        for ((k, v) in snapshot) overrides[k] = v
        persistLayout()
    }
    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(overrides.toMap())
        replaceOverrides(previous)
    }
    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(overrides.toMap())
        replaceOverrides(next)
    }

    fun resetLayout() {
        // Snapshot first so the user can undo a "Reorganise" click.
        snapshotForUndo()
        // Drop manual positions so `effectiveLayout` recomputes from the auto-layout.
        overrides.clear()
        // Also reset zoom + scroll: otherwise the user keeps their old viewport,
        // which may now be anchored off the newly-compact auto-laid graph,
        // giving the impression that the cards "disappeared".
        scale = 1f
        scope.launch {
            hScroll.scrollTo(0)
            vScroll.scrollTo(0)
        }
        scope.launch(Dispatchers.IO) { store.saveLayout(GraphLayout.EMPTY) }
    }

    // --- Helpers for the right-click context menu and the snap-during-drag.
    // Defined here, AFTER `layout` and `persistLayout`, because Kotlin local
    // functions only see local vals declared above them. Earlier we tried
    // putting these next to the state declarations and the compiler couldn't
    // resolve the forward references.
    fun mergedPos(id: String): Offset? = overrides[id] ?: basePositions[id]

    fun applyOverrides(updates: Map<String, Offset>) {
        // Each align/distribute/stack action is one undo step.
        snapshotForUndo()
        for ((id, pos) in updates) {
            overrides[id] = Offset(pos.x.coerceAtLeast(0f), pos.y.coerceAtLeast(0f))
        }
        persistLayout()
    }

    fun selectedPositions(): List<Pair<String, Offset>> =
        multiSelection.mapNotNull { id -> mergedPos(id)?.let { id to it } }

    fun alignSelectedTop() {
        val items = selectedPositions().takeIf { it.size >= 2 } ?: return
        val targetY = items.minOf { it.second.y }
        applyOverrides(items.associate { (id, p) -> id to Offset(p.x, targetY) })
    }
    fun alignSelectedBottom() {
        val items = selectedPositions().takeIf { it.size >= 2 } ?: return
        val targetY = items.maxOf { it.second.y }
        applyOverrides(items.associate { (id, p) -> id to Offset(p.x, targetY) })
    }
    fun alignSelectedHCenter() {
        val items = selectedPositions().takeIf { it.size >= 2 } ?: return
        val midY = (items.minOf { it.second.y } + items.maxOf { it.second.y }) / 2f
        applyOverrides(items.associate { (id, p) -> id to Offset(p.x, midY) })
    }
    fun alignSelectedLeft() {
        val items = selectedPositions().takeIf { it.size >= 2 } ?: return
        val targetX = items.minOf { it.second.x }
        applyOverrides(items.associate { (id, p) -> id to Offset(targetX, p.y) })
    }
    fun alignSelectedRight() {
        val items = selectedPositions().takeIf { it.size >= 2 } ?: return
        val targetX = items.maxOf { it.second.x }
        applyOverrides(items.associate { (id, p) -> id to Offset(targetX, p.y) })
    }
    fun alignSelectedVCenter() {
        val items = selectedPositions().takeIf { it.size >= 2 } ?: return
        val midX = (items.minOf { it.second.x } + items.maxOf { it.second.x }) / 2f
        applyOverrides(items.associate { (id, p) -> id to Offset(midX, p.y) })
    }
    fun distributeSelectedH() {
        val items = selectedPositions().sortedBy { it.second.x }
        if (items.size < 3) return
        val first = items.first().second.x
        val last = items.last().second.x
        val step = (last - first) / (items.size - 1)
        applyOverrides(items.withIndex().associate { (i, pair) ->
            pair.first to Offset(first + step * i, pair.second.y)
        })
    }
    fun distributeSelectedV() {
        val items = selectedPositions().sortedBy { it.second.y }
        if (items.size < 3) return
        val first = items.first().second.y
        val last = items.last().second.y
        val step = (last - first) / (items.size - 1)
        applyOverrides(items.withIndex().associate { (i, pair) ->
            pair.first to Offset(pair.second.x, first + step * i)
        })
    }
    fun resetSelectedOverrides() {
        snapshotForUndo()
        for (id in multiSelection.toList()) overrides.remove(id)
        persistLayout()
    }

    /**
     * Stacks the selected cards in a single column, top-to-bottom, using the
     * auto-layout's [ROW_STEP] as the inter-card spacing. Anchors the column
     * at the topmost card's current position so the user sees a predictable
     * starting point. Useful when several states are scattered and the user
     * wants a clean vertical chain.
     */
    fun stackSelectedVertical() {
        val items = selectedPositions().sortedBy { it.second.y }.takeIf { it.size >= 2 } ?: return
        val baseX = items.first().second.x
        val baseY = items.first().second.y
        val step = ROW_STEP.toFloat()
        applyOverrides(items.withIndex().associate { (i, pair) ->
            pair.first to Offset(baseX, baseY + i * step)
        })
    }

    /** Mirror of [stackSelectedVertical] along the horizontal axis: arranges
     *  the selection left-to-right, anchored at the leftmost card. */
    fun stackSelectedHorizontal() {
        val items = selectedPositions().sortedBy { it.second.x }.takeIf { it.size >= 2 } ?: return
        val baseX = items.first().second.x
        val baseY = items.first().second.y
        val step = COL_STEP.toFloat()
        applyOverrides(items.withIndex().associate { (i, pair) ->
            pair.first to Offset(baseX + i * step, baseY)
        })
    }

    /**
     * Snaps a single card's intended X/Y dp positions to nearby cards' edges,
     * returning the snapped position together with which lines (if any) drove
     * the snap so the caller can render alignment guides. Selection-internal
     * cards are excluded so the group doesn't try to snap to itself.
     *
     * Snap policy is intentionally narrow because a wide one made the cards
     * impossible to nudge:
     *   - Threshold 3 dp (about a pixel at typical density) — anything wider
     *     made cards stick too easily on dense layouts.
     *   - Only "same edge" candidates (left & top alignment with another
     *     card). Side-by-side `±CARD_W` and grid-step `±COL_STEP/±ROW_STEP`
     *     candidates were dropped because every other card contributed many
     *     of them and any drag landed on one accidentally.
     *   - Only consider cards that already share the *other* axis (within
     *     ~2 cards' length) so we don't snap to a card on the far side of
     *     the canvas just because their X coordinates align by chance.
     */
    fun computeSnap(intended: Offset, draggedIds: Set<String>): Triple<Offset, Float?, Float?> {
        val threshold = 3f // dp
        val cardW = CARD_W.toFloat()
        val cardH = CARD_H.toFloat()
        // "Same axis vicinity": only neighbours close enough on the other
        // axis count as snap targets. Two card-lengths is generous enough
        // for visually-relevant alignment but tight enough to keep distant
        // cards from grabbing the cursor.
        val xVicinity = cardH * 2f
        val yVicinity = cardW * 2f
        var snappedX = intended.x
        var snappedY = intended.y
        var bestDX = threshold
        var bestDY = threshold
        var hintX: Float? = null
        var hintY: Float? = null
        for (s in session.states) {
            if (s.id in draggedIds) continue
            val pos = layout.positions[s.id] ?: continue
            // Same-X (left edge alignment): only meaningful when the cards
            // overlap vertically, otherwise the user is unlikely to be aiming
            // for that alignment.
            if (abs(pos.y - intended.y) < xVicinity) {
                val d = abs(intended.x - pos.x)
                if (d < bestDX) {
                    bestDX = d
                    snappedX = pos.x
                    hintX = pos.x
                }
            }
            // Same-Y (top edge alignment): symmetric on the other axis.
            if (abs(pos.x - intended.x) < yVicinity) {
                val d = abs(intended.y - pos.y)
                if (d < bestDY) {
                    bestDY = d
                    snappedY = pos.y
                    hintY = pos.y
                }
            }
        }
        return Triple(Offset(snappedX, snappedY), hintX, hintY)
    }

    // Grab keyboard focus when the graph view first opens so Ctrl+Z / Ctrl+Y
    // are routed to our undo/redo handler. If the user later focuses an
    // input field elsewhere, focus moves there; clicking back on the canvas
    // returns it via the focusable() modifier on the outer Box.
    LaunchedEffect(session.id) {
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val isCtrl = event.isCtrlPressed || event.isMetaPressed
                if (!isCtrl) return@onKeyEvent false
                when (event.key) {
                    Key.Z -> {
                        undo()
                        true
                    }
                    Key.Y -> {
                        redo()
                        true
                    }
                    else -> false
                }
            },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val viewportWpx = with(LocalDensity.current) { maxWidth.toPx() }
            val viewportHpx = with(LocalDensity.current) { maxHeight.toPx() }

            // When the session changes, open on a whole-graph overview: run the
            // same "Fit" zoom automatically and scroll to (0, 0) so the entire
            // layout is visible at once. The user can zoom back to 100% / use
            // the +/- buttons from there.
            var viewSetupDone by remember(session.id) { mutableStateOf(false) }
            LaunchedEffect(session.id, overridesLoaded) {
                if (viewSetupDone || !overridesLoaded) return@LaunchedEffect
                scale = if (layout.totalW > 0 && layout.totalH > 0 && viewportWpx > 0f && viewportHpx > 0f) {
                    min(viewportWpx / layout.totalW, viewportHpx / layout.totalH).coerceIn(MIN_SCALE, MAX_SCALE)
                } else {
                    1f
                }
                hScroll.scrollTo(0)
                vScroll.scrollTo(0)
                viewSetupDone = true
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Capture the viewport Box's window-space coordinates so
                    // the marquee handler can ask Compose itself to convert
                    // pointer positions to inner-content coordinates without
                    // hand-rolling the scroll/zoom transform.
                    .onGloballyPositioned { pointerHostCoords = it }
                    .horizontalScroll(hScroll)
                    .verticalScroll(vScroll)
                    // Ctrl/Cmd + wheel zooms. Plain wheel falls through to the scroll modifiers.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.type == PointerEventType.Scroll) {
                                    val mods = ev.keyboardModifiers
                                    if (mods.isCtrlPressed || mods.isMetaPressed) {
                                        val delta = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                        if (delta != 0f) {
                                            scale = (scale * if (delta < 0f) 1.1f else 1 / 1.1f)
                                                .coerceIn(MIN_SCALE, MAX_SCALE)
                                            ev.changes.forEach { it.consume() }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Drag on an empty area: Shift+drag draws a marquee that
                    // additively selects every card it overlaps; plain drag
                    // pans the viewport. Cards have their own pointer input
                    // upstream and consume their gestures, so this branch
                    // only fires for background drags.
                    .pointerInput(Unit) {
                        val touchSlop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val shiftAtDown = currentEvent.keyboardModifiers.isShiftPressed

                            // Convert a pointer position registered against
                            // the viewport host into unscaled inner-content
                            // dp. We delegate the geometry to Compose's own
                            // layout graph (`localPositionOf`) so every
                            // intervening transform — scroll offset *and*
                            // the graphicsLayer scale — is applied exactly
                            // once, in the same way Compose itself uses to
                            // place the cards. Falling back to the legacy
                            // hand-rolled formula keeps the marquee usable
                            // during the brief window between composition
                            // and the first `onGloballyPositioned` callback.
                            fun toContentDp(px: Offset): Offset {
                                val host = pointerHostCoords
                                val content = contentHostCoords
                                val pxInContent: Offset = if (host != null && content != null && host.isAttached && content.isAttached) {
                                    content.localPositionOf(host, px)
                                } else {
                                    val scrolled = Offset(px.x + hScroll.value, px.y + vScroll.value)
                                    scrolled / scale
                                }
                                return Offset(pxInContent.x / density.density, pxInContent.y / density.density)
                            }

                            if (shiftAtDown) {
                                val downContent = toContentDp(down.position)
                                var endContent = downContent
                                var dragMode = false
                                var totalSlop = Offset.Zero
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: break
                                    if (change.changedToUp()) {
                                        // Apply selection on release: every
                                        // card whose bounds overlap the rect
                                        // is added (additive — never removes).
                                        if (dragMode) {
                                            val rect = makeMarqueeRect(downContent, endContent)
                                            for (s in session.states) {
                                                val pos = layout.positions[s.id] ?: continue
                                                if (rect.overlaps(
                                                        Rect(pos.x, pos.y, pos.x + CARD_W, pos.y + CARD_H)
                                                    )
                                                ) multiSelection += s.id
                                            }
                                        }
                                        marqueeRectDp = null
                                        break
                                    }
                                    val delta = change.positionChange()
                                    if (!dragMode) {
                                        totalSlop += delta
                                        if (totalSlop.getDistance() > touchSlop) dragMode = true
                                    }
                                    if (dragMode) {
                                        change.consume()
                                        endContent = toContentDp(change.position)
                                        marqueeRectDp = makeMarqueeRect(downContent, endContent)
                                    }
                                }
                            } else {
                                // Pan mode: scroll the viewport by the per-frame delta.
                                var dragMode = false
                                var totalSlop = Offset.Zero
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: break
                                    if (change.changedToUp()) break
                                    val delta = change.positionChange()
                                    if (!dragMode) {
                                        totalSlop += delta
                                        if (totalSlop.getDistance() > touchSlop) dragMode = true
                                    }
                                    if (dragMode) {
                                        change.consume()
                                        scope.launch {
                                            hScroll.scrollBy(-delta.x)
                                            vScroll.scrollBy(-delta.y)
                                        }
                                    }
                                }
                            }
                        }
                    },
            ) {
                Box(
                    modifier = Modifier.size(
                        width = (layout.totalW * scale).dp,
                        height = (layout.totalH * scale).dp,
                    ),
                ) {
                    Box(
                        modifier = Modifier
                            .size(layout.totalW.dp, layout.totalH.dp)
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                transformOrigin = TransformOrigin(0f, 0f),
                            )
                            // Capture the unscaled inner-content Box's
                            // window-space coordinates. Used as the target
                            // frame for `localPositionOf` so a pointer
                            // position registered against the viewport Box
                            // is translated through the live scroll AND the
                            // graphicsLayer scale in one call.
                            .onGloballyPositioned { contentHostCoords = it },
                    ) {
                        EdgeCanvas(session, layout, selectedId, hoveredTransition)
                        for (stateEntry in session.states) {
                            val pos = layout.positions[stateEntry.id] ?: continue
                            // Wrap each card in a Box that carries the offset
                            // so `ContextMenuArea`'s right-click detector
                            // shares the visible card's bounds. Without this
                            // outer offsetted Box, the detector stays at
                            // (0, 0) and the right-click hits an empty area.
                            Box(modifier = Modifier.offset(pos.x.dp, pos.y.dp)) {
                                ContextMenuArea(
                                    items = {
                                        if (stateEntry.id !in multiSelection) {
                                            multiSelection.clear()
                                            multiSelection += stateEntry.id
                                            selectInternal(stateEntry.id)
                                        }
                                        val sel = multiSelection.size
                                        buildList {
                                            if (sel >= 2) {
                                                add(ContextMenuItem("⤒  Aligner en haut") { alignSelectedTop() })
                                                add(ContextMenuItem("─  Aligner sur l'axe horizontal") { alignSelectedHCenter() })
                                                add(ContextMenuItem("⤓  Aligner en bas") { alignSelectedBottom() })
                                                add(ContextMenuItem("⇤  Aligner à gauche") { alignSelectedLeft() })
                                                add(ContextMenuItem("│  Aligner sur l'axe vertical") { alignSelectedVCenter() })
                                                add(ContextMenuItem("⇥  Aligner à droite") { alignSelectedRight() })
                                                add(ContextMenuItem("▤  Empiler en colonne") { stackSelectedVertical() })
                                                add(ContextMenuItem("▥  Empiler en ligne") { stackSelectedHorizontal() })
                                            }
                                            if (sel >= 3) {
                                                add(ContextMenuItem("⇔  Distribuer horizontalement") { distributeSelectedH() })
                                                add(ContextMenuItem("⇕  Distribuer verticalement") { distributeSelectedV() })
                                            }
                                            add(ContextMenuItem("⟲  Réinitialiser la position (auto-layout)") { resetSelectedOverrides() })

                                            // Graph-editing items: merge first
                                            // (non-destructive in spirit — the
                                            // primary stays), then delete, both
                                            // separated visually by their order.
                                            if (sel >= 2) {
                                                // Primary = earliest state in
                                                // session order ("le premier
                                                // état"), used both as the kept
                                                // node and as the menu label.
                                                val primaryId = session.states
                                                    .firstOrNull { it.id in multiSelection }?.id
                                                if (primaryId != null) {
                                                    add(ContextMenuItem(
                                                        strings.graphMergeFmt.format(sel, primaryId)
                                                    ) {
                                                        val merged = multiSelection.toSet()
                                                        // Reduce the selection
                                                        // set to just the kept
                                                        // primary so the next
                                                        // right-click on the
                                                        // graph reflects the
                                                        // post-merge reality.
                                                        multiSelection.clear()
                                                        multiSelection += primaryId
                                                        selectInternal(primaryId)
                                                        onRequestMerge(merged)
                                                    })
                                                }
                                            }
                                            if (sel == 1) {
                                                add(ContextMenuItem(
                                                    strings.graphDeleteOneFmt.format(stateEntry.id)
                                                ) {
                                                    onRequestDelete(setOf(stateEntry.id))
                                                    multiSelection -= stateEntry.id
                                                })
                                            } else {
                                                add(ContextMenuItem(
                                                    strings.graphDeleteManyFmt.format(sel)
                                                ) {
                                                    val toDelete = multiSelection.toSet()
                                                    onRequestDelete(toDelete)
                                                    multiSelection.clear()
                                                })
                                            }
                                        }
                                    },
                                ) {
                                    DraggableStateCard(
                                        stateEntry = stateEntry,
                                store = store,
                                isSelected = stateEntry.id in multiSelection,
                                isRoot = stateEntry.id == rootId,
                                // Offset is handled by the wrapping Box above.
                                position = Offset.Zero,
                                onDragStart = {
                                    // Single snapshot at the start of the
                                    // drag (the gesture handler fires this
                                    // exactly once per drag, after the touch
                                    // slop is crossed) so undo restores the
                                    // pre-drag positions of every selected
                                    // card.
                                    snapshotForUndo()
                                    inDragSession = true
                                    // Seed the would-be cursor position to
                                    // the card's current position. From here
                                    // on, every onDrag delta accumulates onto
                                    // this independently of snapping.
                                    dragIntendedPrimary = overrides[stateEntry.id]
                                        ?: basePositions[stateEntry.id]
                                        ?: layout.positions[stateEntry.id]
                                },
                                onClick = { shift, ctrl ->
                                    when {
                                        // Shift-click: additive — keep existing selection
                                        // and add this card. Never deselects, even if the
                                        // card was already in the set.
                                        shift -> multiSelection += stateEntry.id
                                        // Ctrl-click: toggle — add when missing, remove when
                                        // already there. Combined with Shift this is what
                                        // lets the user prune a Shift-selected group.
                                        ctrl -> {
                                            if (stateEntry.id in multiSelection)
                                                multiSelection -= stateEntry.id
                                            else multiSelection += stateEntry.id
                                        }
                                        // Plain click: replace the entire selection with
                                        // just this card.
                                        else -> {
                                            multiSelection.clear()
                                            multiSelection += stateEntry.id
                                        }
                                    }
                                    selectInternal(stateEntry.id)
                                },
                                onDrag = { dragPx ->
                                    // Group-drag: when the dragged card is in the
                                    // multi-selection, translate every selected card by the
                                    // same delta. Dragging an unselected card replaces the
                                    // selection with just that card so the action is
                                    // consistent with a "click + drag" gesture.
                                    if (stateEntry.id !in multiSelection) {
                                        multiSelection.clear()
                                        multiSelection += stateEntry.id
                                    }
                                    val targets = multiSelection.toList()
                                    val dxDp = with(density) { dragPx.x.toDp().value }
                                    val dyDp = with(density) { dragPx.y.toDp().value }
                                    val primaryOld = overrides[stateEntry.id]
                                        ?: basePositions[stateEntry.id]
                                        ?: layout.positions[stateEntry.id]
                                        ?: Offset.Zero
                                    // Track where the cursor would put the
                                    // primary card if no snapping ever
                                    // happened. Once a magnet captures the
                                    // card, `primaryOld` stays glued to the
                                    // guide while this keeps moving with the
                                    // cursor — so when the cursor pulls past
                                    // the snap threshold the card jumps the
                                    // accumulated offset and re-aligns with
                                    // the cursor (bug fix: previously the
                                    // delta was applied to the snapped
                                    // position, leaving the card lagging by
                                    // the capture offset).
                                    val baseIntended = dragIntendedPrimary ?: primaryOld
                                    val intended = Offset(
                                        baseIntended.x + dxDp,
                                        baseIntended.y + dyDp,
                                    )
                                    dragIntendedPrimary = intended
                                    val (snapped, hintX, hintY) =
                                        computeSnap(intended, multiSelection.toSet())
                                    snapGuideXDp = hintX
                                    snapGuideYDp = hintY
                                    val effDx = snapped.x - primaryOld.x
                                    val effDy = snapped.y - primaryOld.y
                                    for (id in targets) {
                                        val old = overrides[id] ?: basePositions[id]
                                            ?: layout.positions[id]
                                            ?: continue
                                        overrides[id] = Offset(
                                            x = (old.x + effDx).coerceAtLeast(0f),
                                            y = (old.y + effDy).coerceAtLeast(0f),
                                        )
                                    }
                                },
                                onDragEnd = {
                                    snapGuideXDp = null
                                    snapGuideYDp = null
                                    inDragSession = false
                                    dragIntendedPrimary = null
                                    persistLayout()
                                },
                                onDoubleClick = { onEnlarge(stateEntry.id) },
                                    )
                                }   // closes the ContextMenuArea content block
                            }       // closes the wrapping offset Box
                        }
                        // Marquee overlay: a translucent rectangle drawn on
                        // top of the cards inside the same `graphicsLayer`,
                        // so it scrolls and zooms together with the layout.
                        marqueeRectDp?.let { rect ->
                            MarqueeOverlay(rect, density.density)
                        }
                        // Snap guides: thin lines that show which alignment
                        // the dragged card has snapped to. They live inside
                        // the scaled `graphicsLayer` so they line up with the
                        // cards regardless of zoom/scroll.
                        if (snapGuideXDp != null || snapGuideYDp != null) {
                            SnapGuidesOverlay(
                                xDp = snapGuideXDp,
                                yDp = snapGuideYDp,
                                density = density.density,
                                contentWdp = layout.totalW.toFloat(),
                                contentHdp = layout.totalH.toFloat(),
                            )
                        }
                    }
                }
            }


            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = {
                    scale = (scale / 1.25f).coerceIn(MIN_SCALE, MAX_SCALE)
                }) { Text("−") }
                Text("${(scale * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    scale = (scale * 1.25f).coerceIn(MIN_SCALE, MAX_SCALE)
                }) { Text("+") }
                OutlinedButton(onClick = {
                    scale = 1f
                    scope.launch { hScroll.scrollTo(0); vScroll.scrollTo(0) }
                }) { Text("100%") }
                OutlinedButton(onClick = {
                    val fit = min(viewportWpx / layout.totalW, viewportHpx / layout.totalH)
                    scale = fit.coerceIn(MIN_SCALE, MAX_SCALE)
                    scope.launch { hScroll.scrollTo(0); vScroll.scrollTo(0) }
                }) { Text(strings.graphFit) }
                OutlinedButton(onClick = { resetLayout() }) { Text(strings.graphReorganize) }
            }
        }
    }
}

@Composable
private fun DraggableStateCard(
    stateEntry: StateEntry,
    store: SessionStore,
    isSelected: Boolean,
    position: Offset,
    onClick: (shift: Boolean, ctrl: Boolean) -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDoubleClick: () -> Unit = {},
    isRoot: Boolean = false,
) {
    // Plain (non-state) holder for the last click time so a second click within
    // the double-click window fires `onDoubleClick` instead of a second select.
    val lastClickAt = remember(stateEntry.id) { longArrayOf(0L) }
    // The card's `position` is honoured here only when non-zero (callers may
    // already apply the offset on a wrapping Box — that's currently the case
    // inside the graph view, where the per-card `ContextMenuArea` needs the
    // card to live inside the area's measured bounds for the right-click
    // detector to reach it). Passing `Offset.Zero` makes this a no-op.
    StateCard(
        stateEntry = stateEntry,
        store = store,
        isSelected = isSelected,
        isRoot = isRoot,
        modifier = Modifier
            .offset(position.x.dp, position.y.dp)
            .size(CARD_W.dp, CARD_H.dp)
            // Move cursor hints that the card can be dragged around the graph.
            .pointerHoverIcon(MoveCursor)
            .pointerInput(stateEntry.id) {
                // Combined click + drag gesture. We need both to share the
                // same pointer pipeline because (a) the click branch must
                // capture the keyboard modifiers in effect at press time
                // (Shift / Ctrl drive multi-selection — Compose's `clickable`
                // can't surface them) and (b) we have to discriminate "click"
                // from "drag" via the touch slop ourselves so the drag handler
                // can keep the existing per-frame delta contract.
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Right-click is handled by the outer canvas pointerInput
                    // (it opens the context menu and updates the selection).
                    // We have to ignore secondary presses here so the card
                    // doesn't also fire `onClick` and clobber the selection.
                    if (currentEvent.buttons.isSecondaryPressed) {
                        do {
                            val ev = awaitPointerEvent()
                            if (ev.changes.none { it.pressed }) break
                        } while (true)
                        return@awaitEachGesture
                    }
                    val mods = currentEvent.keyboardModifiers
                    val shift = mods.isShiftPressed
                    val ctrl = mods.isCtrlPressed || mods.isMetaPressed
                    var dragMode = false
                    var totalSlop = Offset.Zero

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUp()) {
                            if (dragMode) {
                                onDragEnd()
                            } else {
                                val now = System.currentTimeMillis()
                                if (now - lastClickAt[0] < 300L) {
                                    onDoubleClick()
                                    lastClickAt[0] = 0L
                                } else {
                                    onClick(shift, ctrl)
                                    lastClickAt[0] = now
                                }
                            }
                            break
                        }
                        // Use `positionChange()` (= position - previousPosition,
                        // both expressed in this frame's modifier coordinate
                        // system) instead of a hand-tracked `lastPos`. When
                        // `onDrag` moves the card via the `Modifier.offset`,
                        // the modifier's frame shifts; a stored `lastPos`
                        // would still reference the previous frame and the
                        // computed delta would oscillate, making the card
                        // visibly vibrate while the user drags it.
                        val delta = change.positionChange()
                        if (!dragMode) {
                            totalSlop += delta
                            if (totalSlop.getDistance() > touchSlop) {
                                dragMode = true
                                // Fired exactly once at the start of the drag
                                // (after the touch slop is crossed) so the
                                // caller can snapshot the pre-drag layout for
                                // undo purposes without recording one entry
                                // per onDrag frame.
                                onDragStart()
                                // Catch up: deliver the accumulated drag so
                                // the card reaches the pointer instead of
                                // trailing `touchSlop` pixels behind for the
                                // rest of the gesture.
                                onDrag(totalSlop)
                                change.consume()
                            }
                        } else {
                            onDrag(delta)
                            change.consume()
                        }
                    }
                }
            },
    )
}

@Composable
private fun VerticalSplitter(onDrag: (deltaDp: Float) -> Unit) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(6.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
            // Make the affordance discoverable: when the pointer enters the
            // splitter, switch to a horizontal-resize arrow.
            .pointerHoverIcon(HorizontalResizeCursor)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val dp = with(density) { drag.x.toDp().value }
                    onDrag(dp)
                }
            },
    )
}

@Composable
private fun EdgeCanvas(
    session: ExplorationSession,
    layout: Layout,
    selectedId: String?,
    hoveredTransition: TransitionEntry?,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor = MaterialTheme.colorScheme.error
    val bidirectionalColor = MaterialTheme.colorScheme.tertiary
    // Vivid, theme-independent colour for the hovered transition so it is
    // unmistakable against any edge colour.
    val hoverColor = Color(0xFFFF6D00)

    // Set of "productive" forward edges (excluding self-loops, leftApp, errors).
    // Used to detect bidirectional pairs: when both A→B and B→A exist, we
    // collapse them into a single arrow with arrowheads on both ends, drawn
    // in the tertiary colour so the pair stands out from regular one-way
    // transitions and the graph stays readable.
    val productiveEdges = remember(session) {
        session.transitions.asSequence()
            .filter { it.to != null && !it.loop && !it.leftApp && it.errorMessage == null }
            .map { it.from to it.to!! }
            .toSet()
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Tracks which bidirectional pairs we've already rendered (keyed in
        // a canonical from<to order) so the second transition of the pair
        // is skipped rather than drawn on top of the first.
        val drawnBidirectional = HashSet<Pair<String, String>>()

        // Card positions and CARD_W/CARD_H are stored as dp-equivalent floats:
        // the cards themselves are placed via `Modifier.offset(x.dp, y.dp)`,
        // which Compose multiplies by density to land in pixels. The Canvas
        // drawScope, however, works in raw pixels — so we must convert each
        // coordinate through `density` to keep arrows attached to the cards.
        val cardW = CARD_W * density
        val cardH = CARD_H * density
        for (t in session.transitions) {
            val from = layout.positions[t.from] ?: continue
            val toPos = t.to?.let { layout.positions[it] }
            val targetId = t.to

            val isProductive = targetId != null && !t.loop && !t.leftApp && t.errorMessage == null
            val isBidirectional = isProductive && (targetId to t.from) in productiveEdges
            if (isBidirectional) {
                // Kotlin smart-cast: `isProductive` started with `targetId != null`,
                // so on this branch `targetId` is non-null and we can compare strings.
                val key = if (t.from < targetId) t.from to targetId else targetId to t.from
                if (!drawnBidirectional.add(key)) continue
            }

            val fromXPx = from.x * density
            val fromYPx = from.y * density

            // Choose attachment edges based on the relative position of the
            // two cards: when the destination is mostly to the right or left
            // of the source we attach to the left/right edges (existing
            // behaviour); when it is mostly above or below we attach to the
            // top/bottom edges. The dominant axis (|dx| vs |dy| between
            // centers) decides. This makes vertically-stacked diagrams read
            // naturally instead of weaving arrows through other cards.
            val (start, end, orientation) = if (toPos != null) {
                computeAttachments(
                    srcX = fromXPx, srcY = fromYPx,
                    tgtX = toPos.x * density, tgtY = toPos.y * density,
                    cardW = cardW, cardH = cardH,
                )
            } else {
                // Dangling transition (target unknown / leftApp / error).
                val s = Offset(fromXPx + cardW, fromYPx + cardH / 2f)
                Triple(s, Offset(s.x + 40f, s.y + 40f), EdgeOrientation.HORIZONTAL)
            }

            val highlight = selectedId != null && (t.from == selectedId || t.to == selectedId)
            // The transition the pointer is hovering in the details list wins the
            // strongest highlight so the user can pinpoint exactly which arrow it
            // refers to.
            val hovered = hoveredTransition != null && t === hoveredTransition
            val color = when {
                hovered -> hoverColor
                t.errorMessage != null -> errorColor
                t.leftApp -> errorColor
                t.loop -> onSurface
                isBidirectional -> bidirectionalColor
                highlight -> primary
                else -> onSurface.copy(alpha = 0.6f)
            }
            val strokeWidth = if (hovered) 6f else if (highlight || isBidirectional) 2.5f else 1.5f
            // The line should meet the BASE of each arrowhead, never its
            // tip — otherwise the stroke runs all the way into the triangle
            // and the line visibly pokes out the front of the arrow. We
            // shrink the curve endpoints by `ARROW_LEN` along the curve's
            // direction so the cubic stops where the triangle's flat edge
            // begins. The tip of the triangle stays anchored to the card,
            // giving a clean "line → triangle → card" silhouette.
            val arrowLen = ARROW_LEN
            // Where the curve meets a card its tangent is axis-aligned — the
            // control points below keep it horizontal for a HORIZONTAL edge and
            // vertical for a VERTICAL one. The arrowhead must follow THAT tangent,
            // not the straight start→end chord: on a Bézier curve the chord runs
            // diagonally while the line actually arrives square to the border, so
            // a chord-oriented triangle looks visibly skewed. These approach
            // points encode the tangent direction at each endpoint and drive both
            // the curve-shrink and the arrowhead orientation.
            val endApproach = when (orientation) {
                EdgeOrientation.HORIZONTAL -> Offset(start.x, end.y)
                EdgeOrientation.VERTICAL -> Offset(end.x, start.y)
            }
            val startApproach = when (orientation) {
                EdgeOrientation.HORIZONTAL -> Offset(end.x, start.y)
                EdgeOrientation.VERTICAL -> Offset(start.x, end.y)
            }
            val curveEnd = if (t.to != null) {
                shrinkToward(end, endApproach, arrowLen)
            } else end
            val curveStart = if (isBidirectional) {
                shrinkToward(start, startApproach, arrowLen)
            } else start
            val path = Path().apply {
                moveTo(curveStart.x, curveStart.y)
                // Curve control points biased along the chosen axis so the
                // tangent at each endpoint is perpendicular to the edge of
                // the card it touches — this makes arrowheads sit flush
                // against the card border on every orientation.
                when (orientation) {
                    EdgeOrientation.HORIZONTAL -> {
                        val dx = (curveEnd.x - curveStart.x) / 2f
                        cubicTo(
                            curveStart.x + dx, curveStart.y,
                            curveEnd.x - dx, curveEnd.y,
                            curveEnd.x, curveEnd.y,
                        )
                    }
                    EdgeOrientation.VERTICAL -> {
                        val dy = (curveEnd.y - curveStart.y) / 2f
                        cubicTo(
                            curveStart.x, curveStart.y + dy,
                            curveEnd.x, curveEnd.y - dy,
                            curveEnd.x, curveEnd.y,
                        )
                    }
                }
            }
            if (hovered) {
                // Translucent glow underlay so the hovered edge really stands out.
                drawPath(path, color = hoverColor.copy(alpha = 0.30f), style = Stroke(width = strokeWidth + 12f))
            }
            drawPath(path, color = color, style = Stroke(width = strokeWidth))
            if (t.to != null) {
                // Arrowhead at the destination, following the curve's tangent
                // (axis-aligned) where it meets the card — see endApproach above.
                drawPath(arrowheadAt(tip = end, awayFrom = endApproach, length = arrowLen), color = color)
                if (isBidirectional) {
                    // Mirror arrowhead at the source so the pair reads as
                    // "↔" — the user instantly recognises a round-trip
                    // without following each direction separately.
                    drawPath(arrowheadAt(tip = start, awayFrom = startApproach, length = arrowLen), color = color)
                }
            }
        }
    }
}

/**
 * Builds an axis-aligned rectangle covering the area swept between two corner
 * points (the marquee anchor and the current pointer position). Order of the
 * arguments doesn't matter — the rect always has positive width/height.
 */
private fun makeMarqueeRect(a: Offset, b: Offset): Rect = Rect(
    left = min(a.x, b.x),
    top = min(a.y, b.y),
    right = max(a.x, b.x),
    bottom = max(a.y, b.y),
)

/**
 * Thin orange lines spanning the full canvas width/height to show the
 * alignment that a snap has just locked onto. Drawn inside the scaled inner
 * `graphicsLayer` so the guides follow the cards exactly when the user
 * pans/zooms during the drag.
 */
@Composable
private fun SnapGuidesOverlay(
    xDp: Float?,
    yDp: Float?,
    density: Float,
    contentWdp: Float,
    contentHdp: Float,
) {
    val color = MaterialTheme.colorScheme.tertiary
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (xDp != null) {
            val xPx = xDp * density
            drawLine(
                color = color,
                start = Offset(xPx, 0f),
                end = Offset(xPx, contentHdp * density),
                strokeWidth = 1.5f,
            )
        }
        if (yDp != null) {
            val yPx = yDp * density
            drawLine(
                color = color,
                start = Offset(0f, yPx),
                end = Offset(contentWdp * density, yPx),
                strokeWidth = 1.5f,
            )
        }
    }
}

/**
 * Translucent dashed rectangle drawn over the cards while a Shift-drag
 * marquee is in progress. Lives inside the scaled inner content so the
 * overlay stays glued to the layout when the user pans/zooms mid-drag.
 *
 * @param rectDp marquee bounds in unscaled inner-content dp.
 * @param density dp→px multiplier picked up from `LocalDensity` by the caller.
 */
@Composable
private fun MarqueeOverlay(rectDp: Rect, density: Float) {
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val border = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxSize()) {
        val topLeft = Offset(rectDp.left * density, rectDp.top * density)
        val size = Size(rectDp.width * density, rectDp.height * density)
        drawRect(color = fill, topLeft = topLeft, size = size)
        drawRect(
            color = border,
            topLeft = topLeft,
            size = size,
            style = Stroke(
                width = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
            ),
        )
    }
}

/** Side a cubic-Bezier edge attaches to on a card. */
private enum class EdgeOrientation { HORIZONTAL, VERTICAL }

/**
 * Picks attachment points on the source and target cards based on their
 * relative positions. Returns the start/end pixel coordinates along with the
 * dominant axis (used by the caller to bias the cubic-Bezier control points).
 *
 * The rule: compare horizontal distance between centres (`|dx|`) to vertical
 * distance (`|dy|`). The bigger one wins:
 * - `|dx| >= |dy|`: side-to-side connection (right-of-source ↔ left-of-target,
 *   or left ↔ right when the target sits to the left).
 * - `|dx| <  |dy|`: top-to-bottom connection (bottom-of-source ↔ top-of-target,
 *   or top ↔ bottom when the target sits above).
 */
private fun computeAttachments(
    srcX: Float, srcY: Float,
    tgtX: Float, tgtY: Float,
    cardW: Float, cardH: Float,
): Triple<Offset, Offset, EdgeOrientation> {
    val srcCx = srcX + cardW / 2f
    val srcCy = srcY + cardH / 2f
    val tgtCx = tgtX + cardW / 2f
    val tgtCy = tgtY + cardH / 2f
    val dx = tgtCx - srcCx
    val dy = tgtCy - srcCy
    return if (abs(dx) >= abs(dy)) {
        val (start, end) = if (dx >= 0f) {
            Offset(srcX + cardW, srcCy) to Offset(tgtX, tgtCy)
        } else {
            Offset(srcX, srcCy) to Offset(tgtX + cardW, tgtCy)
        }
        Triple(start, end, EdgeOrientation.HORIZONTAL)
    } else {
        val (start, end) = if (dy >= 0f) {
            Offset(srcCx, srcY + cardH) to Offset(tgtCx, tgtY)
        } else {
            Offset(srcCx, srcY) to Offset(tgtCx, tgtY + cardH)
        }
        Triple(start, end, EdgeOrientation.VERTICAL)
    }
}

/**
 * Builds an arrowhead triangle whose tip lands at [tip] and whose base sits
 * 8px away in the direction of [awayFrom]. The triangle is 10px wide overall.
 * Geometry-correct for any direction (horizontal, vertical, or diagonal).
 */
private fun arrowheadAt(tip: Offset, awayFrom: Offset, length: Float = ARROW_LEN, halfWidth: Float = 5f): Path {
    val dx = awayFrom.x - tip.x
    val dy = awayFrom.y - tip.y
    val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
    val ux = dx / len
    val uy = dy / len
    val baseX = tip.x + length * ux
    val baseY = tip.y + length * uy
    val perpX = -uy * halfWidth
    val perpY = ux * halfWidth
    return Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(baseX + perpX, baseY + perpY)
        lineTo(baseX - perpX, baseY - perpY)
        close()
    }
}

/**
 * Returns a point [distance] pixels closer to [origin] from [from], measured
 * along the straight line between them. Used to bring a curve's endpoint
 * back from a card's edge to the base of an arrowhead, so the line meets
 * the triangle's flat side rather than overlapping its tip.
 */
private fun shrinkToward(from: Offset, origin: Offset, distance: Float): Offset {
    val dx = origin.x - from.x
    val dy = origin.y - from.y
    val len = sqrt(dx * dx + dy * dy)
    if (len <= distance) return from
    val ux = dx / len
    val uy = dy / len
    return Offset(from.x + distance * ux, from.y + distance * uy)
}

@Composable
private fun StateCard(
    stateEntry: StateEntry,
    store: SessionStore,
    isSelected: Boolean,
    modifier: Modifier,
    isRoot: Boolean = false,
) {
    val bitmap = remember(stateEntry.id, stateEntry.screenshotPath) {
        loadThumbnail(store, stateEntry.screenshotPath)
    }
    val rootColor = Color(0xFF2E7D32)
    val borderColor = when {
        isRoot -> rootColor
        isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    // No `Modifier.clickable` here on purpose: the wrapping
    // `DraggableStateCard` runs a custom pointer gesture that needs to read
    // the keyboard modifiers at press time (Shift/Ctrl drive multi-selection).
    // Letting `clickable` race the event would either swallow the press or
    // double-fire selection. The gesture handler in `DraggableStateCard` is
    // the single source of truth for both clicks and drags on a card.
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(if (isRoot || isSelected) 3.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isRoot) {
                // The entry point of the app — flagged so it is unmistakable.
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(rootColor)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text("▶ START", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(stateEntry.id, style = MaterialTheme.typography.titleSmall)
            Text("d=${stateEntry.depth}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("· ${stateEntry.clickables.size} actions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(110.dp).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Text("—", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            stateEntry.packageName.ifBlank { "?" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

@Composable
private fun DetailsPanel(
    session: ExplorationSession,
    store: SessionStore,
    selectedId: String?,
    hoveredTransition: TransitionEntry?,
    onSelectState: (String) -> Unit,
    onEnlarge: (String) -> Unit,
    onHoverTransition: (TransitionEntry?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val selected = session.states.firstOrNull { it.id == selectedId }
    Column(modifier = modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selected == null) {
            Text(strings.graphSelectState)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selected.id, style = MaterialTheme.typography.titleMedium)
                Box(Modifier.weight(1f))
                Button(onClick = { onEnlarge(selected.id) }) { Text(strings.graphEnlargeCapture) }
            }
            // Make the headline metadata selectable so users can copy the
            // package name or the fingerprint for logs/bug reports.
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${strings.explorerPackagePrefix}${selected.packageName}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${strings.graphFingerprintPrefix}${selected.fingerprint}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "${strings.graphDepthPrefix}${selected.depth}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            val bmp = remember(selected.id) { loadFullImage(store, selected.screenshotPath) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxSize())
                    // When a transition row is hovered, outline where its action
                    // is located on this state's screenshot (the tap target).
                    val hb = hoveredTransition?.takeIf { it.from == selected.id }?.action?.bounds
                    if (hb != null) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val imgW = bmp.width.toFloat()
                            val imgH = bmp.height.toFloat()
                            if (imgW <= 0f || imgH <= 0f) return@Canvas
                            // Image is shown with ContentScale.Fit (centered), so
                            // map device-pixel bounds through the same fit scale.
                            val scale = min(size.width / imgW, size.height / imgH)
                            val offX = (size.width - imgW * scale) / 2f
                            val offY = (size.height - imgH * scale) / 2f
                            val hoverColor = Color(0xFFFF6D00)
                            val tl = Offset(offX + hb.left * scale, offY + hb.top * scale)
                            val sz = Size((hb.right - hb.left) * scale, (hb.bottom - hb.top) * scale)
                            // Translucent fill + bold border so the tapped area
                            // on the screenshot is impossible to miss.
                            drawRect(color = hoverColor.copy(alpha = 0.28f), topLeft = tl, size = sz)
                            drawRect(color = hoverColor, topLeft = tl, size = sz, style = Stroke(width = 5f))
                        }
                    }
                } else {
                    Text(strings.graphCaptureUnavailable)
                }
            }

            Text(strings.graphOutgoingTransitions, style = MaterialTheme.typography.titleSmall)
            val outgoing = session.transitions.filter { it.from == selected.id }
            if (outgoing.isEmpty()) {
                Text(strings.graphBulletDash, style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                    items(outgoing) { t ->
                        TransitionRow(
                            t = t,
                            strings = strings,
                            onHover = { entered -> onHoverTransition(if (entered) t else null) },
                            onSelectTarget = { if (it != null) onSelectState(it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransitionRow(
    t: TransitionEntry,
    strings: Strings,
    onSelectTarget: (String?) -> Unit,
    onHover: (Boolean) -> Unit = {},
) {
    val errorColor = MaterialTheme.colorScheme.error
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectTarget(t.to) }
            // Report hover so the canvas can highlight the matching arrow and the
            // screenshot can outline the action's bounds.
            .pointerInput(t) {
                awaitPointerEventScope {
                    while (true) {
                        when (awaitPointerEvent().type) {
                            PointerEventType.Enter -> onHover(true)
                            PointerEventType.Exit -> onHover(false)
                            else -> {}
                        }
                    }
                }
            }
            .padding(vertical = 2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val target = when {
                t.errorMessage != null -> strings.graphTargetError
                t.leftApp -> strings.graphTargetLeftApp
                t.loop -> "↺ ${t.to}"
                t.to != null -> "→ ${t.to}"
                else -> "?"
            }
            Text(
                target,
                style = MaterialTheme.typography.bodySmall,
                color = if (t.errorMessage != null) errorColor else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(80.dp),
            )
            Text(
                t.action.label,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
        }
        t.errorMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = errorColor,
                modifier = Modifier.padding(start = 86.dp),
            )
        }
    }
}

internal fun loadThumbnail(store: SessionStore, path: String): ImageBitmap? {
    return runCatching {
        val bytes = store.readScreenshot(path) ?: return null
        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

internal fun loadFullImage(store: SessionStore, path: String): ImageBitmap? = loadThumbnail(store, path)

/**
 * Native AWT save dialog for the HTML export. The .html extension is enforced
 * here (not in the export logic) so the user always ends up with a file the OS
 * recognises as a web page, regardless of what they typed in the dialog.
 */
private fun pickHtmlExportFile(title: String, suggestedName: String): java.io.File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.SAVE)
    dialog.file = "$suggestedName.html"
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    val finalName = if (name.endsWith(".html", ignoreCase = true) ||
                       name.endsWith(".htm", ignoreCase = true)) name else "$name.html"
    return java.io.File(dir, finalName)
}
