package com.salaun.tristan.uiautomator.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.salaun.tristan.uiautomator.i18n.LocalStrings
import com.salaun.tristan.uiautomator.model.UiNode

data class TreeRow(val node: UiNode, val depth: Int, val expandable: Boolean)

@Composable
fun XmlTreePanel(
    root: UiNode?,
    expanded: Set<UiNode>,
    selectedNode: UiNode?,
    onToggle: (UiNode) -> Unit,
    onSelect: (UiNode?) -> Unit,
    modifier: Modifier = Modifier,
    /** Replaces the whole expanded set — used by expand-all / collapse-all and deep double-click toggle. */
    onExpandedChange: (Set<UiNode>) -> Unit = {},
    /**
     * Fired only on a deliberate *click* of a row (not on hover or keyboard
     * navigation). The rule editor uses it to fill the active field/selector
     * from the picked node without reacting to every hover. No-op by default.
     */
    onNodeClicked: (UiNode) -> Unit = {},
) {
    val rows: List<TreeRow> by remember(root) {
        derivedStateOf { buildRows(root, expanded) }
    }
    val selectedIndex =
        if (selectedNode == null) -1 else rows.indexOfFirst { it.node === selectedNode }
    val listState = rememberLazyListState()
    // FocusRequester: clicking on a row asks the LazyColumn for focus so
    // subsequent Up/Down arrow key events arrive at the panel-level
    // `onKeyEvent` handler. Without explicitly grabbing focus, key events
    // would not bubble here and the arrows would be ignored.
    val focusRequester = remember { FocusRequester() }

    // Hover-to-select behaviour, mirroring the screenshot panel: pointer
    // entering a row highlights its node (which the caller typically reflects
    // back as a coloured overlay on the screenshot). Clicking a row pins the
    // selection until the pointer leaves the panel — at which point we
    // either release the pin (preserving the clicked node) or drop the
    // hover-driven selection altogether.
    var pinned by remember(root) { mutableStateOf(false) }
    var lastHoveredNode: UiNode? by remember(root) { mutableStateOf(null) }

    fun handleRowHover(node: UiNode) {
        if (lastHoveredNode === node) return
        lastHoveredNode = node
        if (!pinned) onSelect(node)
    }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            val first = listState.firstVisibleItemIndex
            val last = first + listState.layoutInfo.visibleItemsInfo.size
            if (selectedIndex < first || selectedIndex >= last) {
                listState.animateScrollToItem(selectedIndex)
            }
        }
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            // Panel-level Exit detection: when the pointer leaves the entire
            // tree, we either release the pin (when a row had been clicked)
            // or clear the hover-driven selection so the screenshot doesn't
            // keep highlighting a node the user is no longer looking at. In
            // both cases we also reset `lastHoveredNode` so that re-entering
            // the panel — even straight back onto the same row — restarts a
            // fresh hover and re-fires `onSelect`. Without this clear, the
            // hover throttle would see the same node and skip the re-entry.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type == PointerEventType.Exit) {
                            val wasPinned = pinned
                            pinned = false
                            lastHoveredNode = null
                            if (!wasPinned) {
                                onSelect(null)
                            }
                        }
                    }
                }
            },
    ) {
        if (root != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { onExpandedChange(expandableSubtree(root)) }) {
                    Text(LocalStrings.current.treeExpandAll, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { onExpandedChange(emptySet()) }) {
                    Text(LocalStrings.current.treeCollapseAll, style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (root == null) {
                Text(
                    LocalStrings.current.treeEmpty,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            val flatRows = rows
                            if (flatRows.isEmpty()) return@onKeyEvent false
                            val currentIdx =
                                if (selectedNode == null) -1
                                else flatRows.indexOfFirst { it.node === selectedNode }
                            when (event.key) {
                                Key.DirectionDown -> {
                                    val next = (if (currentIdx < 0) 0 else currentIdx + 1)
                                        .coerceAtMost(flatRows.lastIndex)
                                    if (next != currentIdx) onSelect(flatRows[next].node)
                                    true
                                }
                                Key.DirectionUp -> {
                                    val prev = (if (currentIdx < 0) flatRows.lastIndex else currentIdx - 1)
                                        .coerceAtLeast(0)
                                    if (prev != currentIdx) onSelect(flatRows[prev].node)
                                    true
                                }
                                Key.DirectionRight -> {
                                    // Expand the selected subtree if it has children and is
                                    // not already unfolded. No-op on leaves or already-open
                                    // nodes — keep the contract narrow as requested.
                                    val current = selectedNode
                                    if (current != null && current.children.isNotEmpty() &&
                                        current !in expanded
                                    ) {
                                        onToggle(current)
                                    }
                                    true
                                }
                                Key.DirectionLeft -> {
                                    // Collapse the selected subtree when it is currently
                                    // expanded; otherwise do nothing.
                                    val current = selectedNode
                                    if (current != null && current in expanded) {
                                        onToggle(current)
                                    }
                                    true
                                }
                                else -> false
                            }
                        },
                ) {
                    items(rows, key = { System.identityHashCode(it.node) }) { row ->
                        TreeRowItem(
                            row = row,
                            isExpanded = row.node in expanded,
                            isSelected = row.node === selectedNode,
                            onToggle = { onToggle(row.node) },
                            // Double-click deep-toggles the whole subtree: open
                            // it all if currently collapsed, fold it all if open.
                            onToggleDeep = {
                                val sub = expandableSubtree(row.node)
                                onExpandedChange(if (row.node in expanded) expanded - sub else expanded + sub)
                            },
                            onHover = { handleRowHover(row.node) },
                            onSelect = {
                                onSelect(row.node)
                                onNodeClicked(row.node)
                                pinned = true
                                lastHoveredNode = row.node
                                // Take focus on click so Up/Down arrows are
                                // routed to the LazyColumn from then on.
                                runCatching { focusRequester.requestFocus() }
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        DetailsPanel(selectedNode)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRowItem(
    row: TreeRow,
    isExpanded: Boolean,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onToggleDeep: () -> Unit = {},
    onHover: () -> Unit = {},
    onSelect: () -> Unit,
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val nodeTag = row.node.resourceId.substringAfterLast('/').ifBlank { "node" }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .testTag("tree-row-$nodeTag")
            // Hover detection: report Enter and Move so the panel can drive
            // the screenshot's highlight in real time. Throttling (skipping
            // when the hovered node hasn't changed) is done by the caller.
            .pointerInput(row.node) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type == PointerEventType.Enter ||
                            ev.type == PointerEventType.Move
                        ) {
                            onHover()
                        }
                    }
                }
            }
            .combinedClickable(onClick = { onSelect() }, onDoubleClick = { onToggleDeep() })
            .padding(vertical = 2.dp, horizontal = 4.dp),
    ) {
        Box(modifier = Modifier.width((row.depth * 12).dp))
        if (row.expandable) {
            Text(
                text = if (isExpanded) "▾" else "▸",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .size(18.dp)
                    .testTag("tree-toggle-$nodeTag")
                    .clickable { onToggle() }
                    .padding(horizontal = 2.dp),
            )
        } else {
            Box(modifier = Modifier.size(18.dp))
        }
        Text(
            text = row.node.label,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

@Composable
private fun DetailsPanel(node: UiNode?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
    ) {
        if (node == null) {
            Text(LocalStrings.current.treeSelectNodeHint, style = MaterialTheme.typography.bodySmall)
        } else {
            // Wrapping in a SelectionContainer lets the user drag-select and
            // Ctrl+C any attribute value (resource-id, text, bounds…).
            SelectionContainer {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text(node.className, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    val shown = listOf(
                        "resource-id" to node.resourceId,
                        "text" to node.text,
                        "content-desc" to node.contentDesc,
                        "package" to node.packageName,
                        "bounds" to (node.bounds?.let { "[${it.left},${it.top}][${it.right},${it.bottom}]" } ?: ""),
                        "clickable" to node.clickable.toString(),
                        "enabled" to node.enabled.toString(),
                        "focusable" to node.focusable.toString(),
                        "scrollable" to node.scrollable.toString(),
                        "checkable" to node.checkable.toString(),
                        "checked" to node.checked.toString(),
                        "selected" to node.selected.toString(),
                        "password" to node.password.toString(),
                    )
                    for ((k, v) in shown) {
                        if (v.isBlank()) continue
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Left-click copies the value to the clipboard. This is
                            // a reliable alternative to the SelectionContainer's
                            // right-click menu, which on Compose Desktop can drop
                            // the (hover-driven) selection before the copy lands.
                            Text(
                                "⧉",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .testTag("copy-$k")
                                    .clickable { copyTextToClipboard(v) }
                                    .padding(end = 4.dp),
                            )
                            Text(
                                "$k: ",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                v,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Every node in [node]'s subtree (including [node]) that has children, i.e. can be expanded. */
private fun expandableSubtree(node: UiNode): Set<UiNode> {
    val out = HashSet<UiNode>()
    fun visit(n: UiNode) {
        if (n.children.isNotEmpty()) out += n
        for (c in n.children) visit(c)
    }
    visit(node)
    return out
}

private fun buildRows(root: UiNode?, expanded: Set<UiNode>): List<TreeRow> {
    if (root == null) return emptyList()
    val out = ArrayList<TreeRow>()
    fun recurse(node: UiNode, depth: Int) {
        out += TreeRow(node, depth, node.children.isNotEmpty())
        if (node in expanded) {
            for (c in node.children) recurse(c, depth + 1)
        }
    }
    recurse(root, 0)
    return out
}
