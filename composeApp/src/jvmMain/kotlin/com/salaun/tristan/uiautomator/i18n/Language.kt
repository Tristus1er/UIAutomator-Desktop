package com.salaun.tristan.uiautomator.i18n

import java.util.Locale

/** Supported UI languages. [code] matches [Locale.getLanguage] (ISO-639-1). */
enum class Language(val code: String, val displayName: String) {
    English("en", "English"),
    French("fr", "Français"),
    Spanish("es", "Español"),
    German("de", "Deutsch");

    companion object {
        val DEFAULT = English

        /** Matches the given 2-letter language code to a supported [Language], or falls back to English. */
        fun fromCode(code: String?): Language {
            if (code.isNullOrBlank()) return DEFAULT
            val normalized = code.lowercase()
            return entries.firstOrNull { it.code == normalized } ?: DEFAULT
        }

        /** Returns the OS default language mapped onto a supported [Language]. */
        fun fromSystem(): Language = fromCode(Locale.getDefault().language)
    }
}
