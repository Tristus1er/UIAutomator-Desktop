package com.salaun.tristan.uiautomator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared top navigation bar used by every top-level screen so the app reads as
 * one coherent whole: the screen [title] on the left, screen-specific actions
 * in the flexible [middle] slot, and a navigation cluster ([nav]) pinned to the
 * right — every navigation affordance rendered as the same [ToolbarNavButton]
 * (an OutlinedButton). Replaces the per-screen, inconsistent headers (some used
 * flat text buttons, some put "back" bottom-left, some had no back at all).
 */
@Composable
fun ScreenToolbar(
    title: String,
    modifier: Modifier = Modifier,
    middle: @Composable RowScope.() -> Unit = {},
    nav: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        // Screen-specific actions / status fill the middle and push the
        // navigation cluster to the far right.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = middle,
        )
        nav()
    }
    HorizontalDivider()
}

/** The single, consistent navigation-button style used across every screen. */
@Composable
fun ToolbarNavButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) }
}
