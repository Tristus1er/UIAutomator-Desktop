package com.salaun.tristan.uiautomator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.salaun.tristan.uiautomator.AppState
import com.salaun.tristan.uiautomator.Screen
import com.salaun.tristan.uiautomator.i18n.Language
import com.salaun.tristan.uiautomator.i18n.LocalStrings
import java.awt.FileDialog
import java.awt.Frame

@Composable
fun SettingsScreen(state: AppState) {
    val strings = LocalStrings.current
    var draft by remember(state.adbPath) { mutableStateOf(state.adbPath) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(strings.settingsTitle, style = MaterialTheme.typography.headlineSmall)

        Text(
            strings.settingsAdbPathHint,
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(strings.settingsAdbPathLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { state.applyAdbPath(draft) }) {
                Text(strings.save)
            }
            OutlinedButton(onClick = { state.autoDetectAdb() }) {
                Text(strings.settingsAutoDetect)
            }
            OutlinedButton(onClick = {
                val picked = pickAdbBinary(strings.settingsPickAdbTitle)
                if (picked != null) {
                    draft = picked
                    state.applyAdbPath(picked)
                }
            }) {
                Text(strings.browse)
            }
        }

        if (state.adbPathStatus.isNotBlank()) {
            Text(
                "${strings.settingsStatusPrefix}${state.adbPathStatus}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        LanguagePicker(state)

        Row {
            OutlinedButton(onClick = { state.screen = Screen.Main }) {
                Text(strings.back)
            }
        }
    }
}

@Composable
private fun LanguagePicker(state: AppState) {
    val strings = LocalStrings.current
    val systemLang = remember { Language.fromSystem() }
    var open by remember { mutableStateOf(false) }

    val currentLabel = when (val pref = state.languagePreference) {
        null -> strings.settingsLanguageSystemWith.format(systemLang.displayName)
        else -> pref.displayName
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(strings.settingsLanguage, style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(currentLabel) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(strings.settingsLanguageSystemWith.format(systemLang.displayName)) },
                    onClick = {
                        state.applyLanguagePreference(null)
                        open = false
                    },
                )
                Language.entries.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(lang.displayName) },
                        onClick = {
                            state.applyLanguagePreference(lang)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

private fun pickAdbBinary(title: String): String? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    val windows = System.getProperty("os.name").lowercase().contains("win")
    if (windows) dialog.file = "adb.exe" else dialog.file = "adb"
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return dir.trimEnd('/', '\\') + java.io.File.separator + file
}
