package com.salaun.tristan.uiautomator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.salaun.tristan.uiautomator.AppState
import com.salaun.tristan.uiautomator.Screen
import java.awt.FileDialog
import java.awt.Frame

@Composable
fun SettingsScreen(state: AppState) {
    var draft by remember(state.adbPath) { mutableStateOf(state.adbPath) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Paramètres", style = MaterialTheme.typography.headlineSmall)

        Text(
            "Chemin vers l'exécutable ADB (platform-tools). " +
                "La première ouverture tente une autodétection automatique.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Chemin ADB") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { state.applyAdbPath(draft) }) {
                Text("Enregistrer")
            }
            OutlinedButton(onClick = { state.autoDetectAdb() }) {
                Text("Autodétection")
            }
            OutlinedButton(onClick = {
                val picked = pickAdbBinary()
                if (picked != null) {
                    draft = picked
                    state.applyAdbPath(picked)
                }
            }) {
                Text("Parcourir…")
            }
        }

        if (state.adbPathStatus.isNotBlank()) {
            Text(
                "Statut : ${state.adbPathStatus}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row {
            OutlinedButton(onClick = { state.screen = Screen.Main }) {
                Text("← Retour")
            }
        }
    }
}

private fun pickAdbBinary(): String? {
    val dialog = FileDialog(null as Frame?, "Sélectionner adb", FileDialog.LOAD)
    val windows = System.getProperty("os.name").lowercase().contains("win")
    if (windows) dialog.file = "adb.exe" else dialog.file = "adb"
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return dir.trimEnd('/', '\\') + java.io.File.separator + file
}
