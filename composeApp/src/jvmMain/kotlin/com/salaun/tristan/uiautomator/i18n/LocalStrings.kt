package com.salaun.tristan.uiautomator.i18n

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * CompositionLocal holding the current [Strings] bundle. Provided at the root
 * of the application tree by the [com.salaun.tristan.uiautomator.App]
 * composable and consumed by each screen as `LocalStrings.current`.
 *
 * Changing the language rebinds this provider, which re-runs every consumer —
 * that is how the whole UI retranslates without restarting.
 */
val LocalStrings = staticCompositionLocalOf<Strings> {
    // Sensible default if someone reads the composition local outside of a
    // wrapped tree (e.g. previews), so the app never hard-crashes on a missing
    // provider.
    Translations.EN
}
