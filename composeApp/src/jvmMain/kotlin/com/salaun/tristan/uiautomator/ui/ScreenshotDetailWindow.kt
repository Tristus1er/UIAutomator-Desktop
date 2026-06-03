package com.salaun.tristan.uiautomator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.salaun.tristan.uiautomator.explorer.ClickableRef
import com.salaun.tristan.uiautomator.explorer.ExplorationSession
import com.salaun.tristan.uiautomator.explorer.SessionStore
import com.salaun.tristan.uiautomator.explorer.StateEntry
import com.salaun.tristan.uiautomator.explorer.TransitionEntry
import com.salaun.tristan.uiautomator.i18n.LocalStrings
import com.salaun.tristan.uiautomator.model.DumpParser
import com.salaun.tristan.uiautomator.model.UiBounds
import com.salaun.tristan.uiautomator.model.UiNode

/**
 * Separate, resizable window that mirrors the capture screen (screenshot
 * with hover-to-select + full XML tree) and adds:
 *   - a permanent highlight on every clickable element recorded for the state;
 *   - a destination panel that appears when the user *clicks* on an element
 *     in the screenshot or in the tree, describing the transition (if any).
 */
@Composable
fun ScreenshotDetailWindow(
    session: ExplorationSession,
    store: SessionStore,
    stateEntry: StateEntry,
    onCloseRequest: () -> Unit,
    onOpenState: (String) -> Unit,
) {
    val windowState = rememberWindowState(size = DpSize(1400.dp, 900.dp))
    Window(
        onCloseRequest = onCloseRequest,
        state = windowState,
        title = "${stateEntry.id} · ${stateEntry.packageName.ifBlank { "(package inconnu)" }}",
    ) {
        ScreenshotDetailContent(
            session = session,
            store = store,
            stateEntry = stateEntry,
            onOpenState = onOpenState,
        )
    }
}

@Composable
private fun ScreenshotDetailContent(
    session: ExplorationSession,
    store: SessionStore,
    stateEntry: StateEntry,
    onOpenState: (String) -> Unit,
) {
    val pngBytes = remember(stateEntry.id, stateEntry.screenshotPath) {
        store.readScreenshot(stateEntry.screenshotPath)
    }
    val xmlText = remember(stateEntry.id, stateEntry.xmlPath) {
        store.readXml(stateEntry.xmlPath)
    }
    val rootNode: UiNode? = remember(xmlText) { xmlText?.let { DumpParser.parse(it) } }

    // Tree expansion + selection, initialised every time the state changes.
    val expanded = remember(stateEntry.id) { mutableStateSetOf<UiNode>() }
    LaunchedEffect(rootNode) {
        expanded.clear()
        if (rootNode != null) expanded += rootNode
    }
    var selectedNode: UiNode? by remember(stateEntry.id) { mutableStateOf(null) }
    var clickedClickable: ClickableRef? by remember(stateEntry.id) { mutableStateOf(null) }
    var showClickables by remember(stateEntry.id) { mutableStateOf(true) }

    val outgoing: List<TransitionEntry> = remember(session, stateEntry.id) {
        session.transitions.filter { it.from == stateEntry.id }
    }
    // Map an outgoing transition back to one of `stateEntry.clickables` by
    // (resourceId, className, bounds) instead of the full data-class equals.
    // The latter would fail for sessions recorded in MANUAL mode: there,
    // `transition.action.tapX/tapY` are the user's actual click coordinates
    // (e.g. 842, 2158) while `StateEntry.clickables[i].tapX/tapY` are the
    // computed centre of the bounds (e.g. 799, 2152). Same node, two slightly
    // different ClickableRefs → equals returns false → the lookup misses
    // and the detail panel mistakenly shows "action not yet tested".
    val transitionByClickable: Map<ClickableRef, TransitionEntry> =
        remember(outgoing, stateEntry.clickables) {
            val byKey = outgoing.associateBy { transitionKey(it.action) }
            stateEntry.clickables.mapNotNull { c -> byKey[transitionKey(c)]?.let { c to it } }.toMap()
        }

    // Bounds for the permanent overlay on the screenshot.
    val clickableBounds: List<UiBounds> = remember(stateEntry.clickables) {
        stateEntry.clickables.map { c ->
            UiBounds(c.bounds.left, c.bounds.top, c.bounds.right, c.bounds.bottom)
        }
    }

    fun clickableFor(node: UiNode?): ClickableRef? {
        if (node == null) return null
        val b = node.bounds ?: return null
        return stateEntry.clickables.firstOrNull { c ->
            c.resourceId == node.resourceId &&
                c.className == node.className &&
                c.bounds.left == b.left && c.bounds.top == b.top &&
                c.bounds.right == b.right && c.bounds.bottom == b.bottom
        }
    }

    /**
     * Mirrors the behaviour of the main capture screen: selecting a node also
     * unfolds the XML tree along its chain of ancestors so the highlighted row
     * is visible on the right-hand side.
     */
    fun selectAndReveal(node: UiNode?) {
        selectedNode = node
        if (node != null) {
            var p = node.parent
            while (p != null) { expanded += p; p = p.parent }
        }
    }

    val strings = LocalStrings.current
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stateEntry.id, style = MaterialTheme.typography.titleLarge)
            Text(
                stateEntry.packageName.ifBlank { strings.detailPackageUnknown },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                strings.detailClickablesCountFmt.format(stateEntry.clickables.size),
                style = MaterialTheme.typography.bodySmall,
            )
            Box(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showClickables, onCheckedChange = { showClickables = it })
                Text(strings.detailShowClickables, style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()

        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                ScreenshotPanel(
                    pngBytes = pngBytes,
                    rootNode = rootNode,
                    selectedNode = selectedNode,
                    // Hovering reveals the ancestor chain so the XML tree is
                    // expanded and highlighted in sync, exactly like the main
                    // capture screen.
                    onNodeHovered = { selectAndReveal(it) },
                    onNodeClicked = { node ->
                        selectAndReveal(node)
                        clickedClickable = clickableFor(node)
                    },
                    modifier = Modifier.fillMaxSize(),
                    clickableBounds = if (showClickables) clickableBounds else emptyList(),
                )
            }
            VerticalDivider(modifier = Modifier.fillMaxHeight())
            Column(modifier = Modifier.width(440.dp).fillMaxHeight()) {
                XmlTreePanel(
                    root = rootNode,
                    expanded = expanded,
                    selectedNode = selectedNode,
                    onToggle = { if (it in expanded) expanded -= it else expanded += it },
                    // `node` is nullable: a non-null value comes from a hover
                    // (live highlight) or click (locked highlight), and `null`
                    // is sent when the pointer leaves the tree without a
                    // pinned selection — at which point we drop the
                    // screenshot's highlight too.
                    onSelect = { node ->
                        selectAndReveal(node)
                        clickedClickable = node?.let { clickableFor(it) }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                HorizontalDivider()
                DestinationInfo(
                    clickable = clickedClickable,
                    transition = clickedClickable?.let { transitionByClickable[it] },
                    onOpenState = onOpenState,
                    modifier = Modifier.fillMaxWidth().height(190.dp),
                )
            }
        }
    }
}

@Composable
private fun DestinationInfo(
    clickable: ClickableRef?,
    transition: TransitionEntry?,
    onOpenState: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val errorColor = MaterialTheme.colorScheme.error
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(strings.detailDestination, style = MaterialTheme.typography.titleSmall)
        // SelectionContainer makes the displayed identifiers, bounds and
        // destinations selectable and copyable (Ctrl+C).
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    clickable == null -> Text(
                        strings.detailDestinationHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        Text(clickable.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        if (clickable.resourceId.isNotBlank()) {
                            Text(
                                clickable.resourceId,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            strings.detailBoundsFmt.format(
                                clickable.bounds.left,
                                clickable.bounds.top,
                                clickable.bounds.right,
                                clickable.bounds.bottom,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        when {
                            transition == null -> Text(
                                strings.detailDestNotTested,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            transition.errorMessage != null -> Text(
                                strings.detailDestErrorFmt.format(transition.errorMessage),
                                style = MaterialTheme.typography.bodySmall,
                                color = errorColor,
                            )
                            transition.leftApp -> Text(
                                strings.detailDestLeftApp,
                                style = MaterialTheme.typography.bodySmall,
                                color = errorColor,
                            )
                            transition.loop -> Text(
                                strings.detailDestLoopFmt.format(transition.to ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            transition.to != null -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    strings.detailDestGoFmt.format(transition.to),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Button(onClick = { onOpenState(transition.to) }) {
                                    Text(strings.detailOpenState)
                                }
                            }
                            else -> Text("?", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Stable identity key for a [ClickableRef] that ignores `tapX`/`tapY` (which
 * may legitimately differ between a state's clickable list and a transition's
 * recorded action — see the manual-mode discrepancy noted at the call site).
 */
private fun transitionKey(c: ClickableRef): String =
    "${c.resourceId}|${c.className}|${c.bounds.left},${c.bounds.top},${c.bounds.right},${c.bounds.bottom}"
