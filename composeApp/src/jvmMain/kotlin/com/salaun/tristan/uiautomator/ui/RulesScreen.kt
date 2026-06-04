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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.salaun.tristan.uiautomator.AppState
import com.salaun.tristan.uiautomator.Screen
import com.salaun.tristan.uiautomator.rules.PackageRuleSummary
import com.salaun.tristan.uiautomator.i18n.LocalStrings
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun RulesScreen(state: AppState) {
    val strings = LocalStrings.current
    val packages = remember(state.ruleRevision) { state.listRulePackages() }
    var toDelete by remember { mutableStateOf<PackageRuleSummary?>(null) }
    var showNewPackage by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenToolbar(
            title = strings.rulesTitle,
            middle = {
                OutlinedButton(onClick = { showNewPackage = true }) { Text(strings.rulesNewPackage) }
                OutlinedButton(onClick = {
                    pickRuleZip(strings.rulesImportDialogTitle, FileDialog.LOAD)?.let { state.importRulePackage(it) }
                }) { Text(strings.importLabel) }
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.statusMessage.isNotBlank()) {
                    Text(state.statusMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            nav = { ToolbarNavButton(strings.home, onClick = { state.go(Screen.Main) }) },
        )

        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (packages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(strings.rulesNone) }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    items(packages, key = { it.packageName }) { summary ->
                        PackageRow(
                            summary = summary,
                            statsFmt = strings.rulesPackageStatsFmt,
                            onOpen = { state.openRulePackage(summary.packageName) },
                            onExport = {
                                pickRuleZip(strings.rulesExportDialogTitle, FileDialog.SAVE, "${summary.packageName}.zip")
                                    ?.let { state.exportRulePackage(summary.packageName, it) }
                            },
                            onDelete = { toDelete = summary },
                            exportLabel = strings.exportLabel,
                            openLabel = strings.open,
                            deleteLabel = strings.delete,
                        )
                    }
                }
            }
        }
    }

    if (showNewPackage) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewPackage = false },
            title = { Text(strings.rulesNewPackage) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(strings.rulesNewPackageHint) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val pkg = name.trim()
                        showNewPackage = false
                        state.startNewRule(pkg)
                    },
                ) { Text(strings.save) }
            },
            dismissButton = { TextButton(onClick = { showNewPackage = false }) { Text(strings.cancel) } },
        )
    }

    toDelete?.let { summary ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(strings.rulesDeletePackageConfirmTitle) },
            text = { Text(strings.rulesDeletePackageConfirmBodyFmt.format(summary.packageName)) },
            confirmButton = {
                TextButton(onClick = {
                    state.deleteRulePackage(summary.packageName)
                    toDelete = null
                }) { Text(strings.delete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(strings.cancel) } },
        )
    }
}

@Composable
private fun PackageRow(
    summary: PackageRuleSummary,
    statsFmt: String,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    exportLabel: String,
    openLabel: String,
    deleteLabel: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpen() }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(summary.packageName, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
        Text(
            statsFmt.format(summary.ruleCount, summary.enabledCount, summary.totalActions),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpen) { Text(openLabel) }
            OutlinedButton(onClick = onExport) { Text(exportLabel) }
            OutlinedButton(onClick = onDelete) { Text(deleteLabel, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Shared file picker for rule archives (import = LOAD, export = SAVE). */
internal fun pickRuleZip(title: String, mode: Int, suggestedName: String = "*.zip"): File? {
    val dialog = FileDialog(null as Frame?, title, mode)
    dialog.file = suggestedName
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    val name = if (mode == FileDialog.SAVE && !file.endsWith(".zip", ignoreCase = true)) "$file.zip" else file
    return File(dir, name)
}
