package com.salaun.tristan.uiautomator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.salaun.tristan.uiautomator.model.UiNode

data class TreeRow(val node: UiNode, val depth: Int, val expandable: Boolean)

@Composable
fun XmlTreePanel(
    root: UiNode?,
    expanded: Set<UiNode>,
    selectedNode: UiNode?,
    onToggle: (UiNode) -> Unit,
    onSelect: (UiNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows: List<TreeRow> by remember(root) {
        derivedStateOf { buildRows(root, expanded) }
    }
    val selectedIndex =
        if (selectedNode == null) -1 else rows.indexOfFirst { it.node === selectedNode }
    val listState = rememberLazyListState()

    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            val first = listState.firstVisibleItemIndex
            val last = first + listState.layoutInfo.visibleItemsInfo.size
            if (selectedIndex < first || selectedIndex >= last) {
                listState.animateScrollToItem(selectedIndex)
            }
        }
    }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        Box(modifier = Modifier.weight(1f)) {
            if (root == null) {
                Text(
                    "Arbre de l'interface vide.",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(rows, key = { System.identityHashCode(it.node) }) { row ->
                        TreeRowItem(
                            row = row,
                            isExpanded = row.node in expanded,
                            isSelected = row.node === selectedNode,
                            onToggle = { onToggle(row.node) },
                            onSelect = { onSelect(row.node) },
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        DetailsPanel(selectedNode)
    }
}

@Composable
private fun TreeRowItem(
    row: TreeRow,
    isExpanded: Boolean,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable { onSelect() }
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
            Text("Sélectionnez un nœud pour voir ses attributs.", style = MaterialTheme.typography.bodySmall)
        } else {
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
                    Row {
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
