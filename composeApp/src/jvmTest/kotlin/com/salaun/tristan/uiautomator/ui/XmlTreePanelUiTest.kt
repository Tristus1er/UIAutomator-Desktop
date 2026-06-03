package com.salaun.tristan.uiautomator.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.salaun.tristan.uiautomator.model.UiNode
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Compose UI tests that exercise [XmlTreePanel] in isolation.
 *
 * These guard two behaviours the manual panel went through during development:
 *  - expanding a node must actually rebuild the visible row list (regression
 *    guard for the `remember(root, expanded)` bug, where only the first tree
 *    level was ever shown because the `SnapshotStateSet` identity never
 *    changed across recompositions);
 *  - clicking a row must report the selected node to the caller.
 */
@OptIn(ExperimentalTestApi::class)
class XmlTreePanelUiTest {

    private fun node(
        className: String,
        resourceId: String = "",
        text: String = "",
        children: List<UiNode> = emptyList(),
    ): UiNode {
        val n = UiNode(
            index = 0, depth = 0,
            className = "android.widget.$className",
            packageName = "com.example.app",
            resourceId = resourceId,
            text = text, contentDesc = "",
            bounds = null,
            clickable = false, checkable = false, checked = false,
            enabled = true, focusable = false, focused = false,
            scrollable = false, longClickable = false,
            password = false, selected = false,
            attributes = emptyMap(),
        )
        for (c in children) {
            n.children += c
            c.parent = n
        }
        return n
    }

    @Test
    fun `expanding a node reveals its children and collapsing hides them again`() = runComposeUiTest {
        val grandchildA = node("TextView", resourceId = "id/c1a", text = "GrandA")
        val grandchildB = node("TextView", resourceId = "id/c1b", text = "GrandB")
        val child1 = node("LinearLayout", resourceId = "id/c1", children = listOf(grandchildA, grandchildB))
        val child2 = node("LinearLayout", resourceId = "id/c2")
        val root = node("FrameLayout", resourceId = "id/root", children = listOf(child1, child2))

        val expanded = mutableStateSetOf<UiNode>().apply { add(root) }

        setContent {
            MaterialTheme {
                XmlTreePanel(
                    root = root,
                    expanded = expanded,
                    selectedNode = null,
                    onToggle = { if (it in expanded) expanded -= it else expanded += it },
                    onSelect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Only the root and its immediate children are composed initially.
        onNodeWithTag("tree-row-c1").assertIsDisplayed()
        onNodeWithTag("tree-row-c2").assertIsDisplayed()
        onNodeWithTag("tree-row-c1a").assertDoesNotExist()
        onNodeWithTag("tree-row-c1b").assertDoesNotExist()

        // Expand child1 by clicking its toggle arrow.
        onNodeWithTag("tree-toggle-c1").performClick()
        waitForIdle()

        // The grandchildren must now appear (regression guard for the stale-rows bug).
        onNodeWithTag("tree-row-c1a").assertIsDisplayed()
        onNodeWithTag("tree-row-c1b").assertIsDisplayed()

        // Collapsing removes them again.
        onNodeWithTag("tree-toggle-c1").performClick()
        waitForIdle()
        onNodeWithTag("tree-row-c1a").assertDoesNotExist()
        onNodeWithTag("tree-row-c1b").assertDoesNotExist()
    }

    @Test
    fun `clicking a row invokes onSelect with that very node`() = runComposeUiTest {
        val child = node("LinearLayout", resourceId = "id/c1", text = "Child")
        val root = node("FrameLayout", resourceId = "id/root", children = listOf(child))

        val expanded = mutableStateSetOf<UiNode>().apply { add(root) }
        var selected by mutableStateOf<UiNode?>(null)

        setContent {
            MaterialTheme {
                XmlTreePanel(
                    root = root,
                    expanded = expanded,
                    selectedNode = selected,
                    onToggle = {},
                    onSelect = { selected = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        assertNull(selected)
        onNodeWithTag("tree-row-c1").performClick()
        waitForIdle()
        assertSame(child, selected)
    }
}
