package com.salaun.tristan.uiautomator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.salaun.tristan.uiautomator.i18n.LocalStrings
import com.salaun.tristan.uiautomator.i18n.Translations
import com.salaun.tristan.uiautomator.settings.AppSettings
import com.salaun.tristan.uiautomator.ui.ExplorerScreen
import com.salaun.tristan.uiautomator.ui.GraphScreen
import com.salaun.tristan.uiautomator.ui.MainScreen
import com.salaun.tristan.uiautomator.ui.ManualExplorerScreen
import com.salaun.tristan.uiautomator.ui.RuleEditScreen
import com.salaun.tristan.uiautomator.ui.RulePackageScreen
import com.salaun.tristan.uiautomator.ui.RulesScreen
import com.salaun.tristan.uiautomator.ui.SessionsScreen
import com.salaun.tristan.uiautomator.ui.SettingsScreen

@Composable
@Preview
fun App() {
    MaterialTheme {
        val scope = rememberCoroutineScope()
        val settings = remember { AppSettings.load() }
        val state = remember { AppState(settings, scope) }

        LaunchedEffect(Unit) { state.initialize() }

        CompositionLocalProvider(LocalStrings provides Translations.of(state.currentLanguage)) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (state.screen) {
                        Screen.Main -> MainScreen(state)
                        Screen.Settings -> SettingsScreen(state)
                        Screen.Explorer -> ExplorerScreen(state)
                        Screen.ManualExplorer -> ManualExplorerScreen(state)
                        Screen.Graph -> GraphScreen(state)
                        Screen.Sessions -> SessionsScreen(state)
                        Screen.Rules -> RulesScreen(state)
                        Screen.RulePackage -> RulePackageScreen(state)
                        Screen.RuleEdit -> RuleEditScreen(state)
                    }
                }
            }
        }
    }
}
