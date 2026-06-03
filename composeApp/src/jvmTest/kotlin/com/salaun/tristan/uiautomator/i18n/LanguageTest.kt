package com.salaun.tristan.uiautomator.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class LanguageTest {

    @Test
    fun `fromCode maps known ISO codes to the matching language`() {
        assertSame(Language.English, Language.fromCode("en"))
        assertSame(Language.French, Language.fromCode("fr"))
        assertSame(Language.Spanish, Language.fromCode("es"))
        assertSame(Language.German, Language.fromCode("de"))
    }

    @Test
    fun `fromCode is case-insensitive`() {
        assertSame(Language.French, Language.fromCode("FR"))
        assertSame(Language.German, Language.fromCode("De"))
    }

    @Test
    fun `fromCode falls back to English for unknown or blank input`() {
        assertSame(Language.English, Language.fromCode(null))
        assertSame(Language.English, Language.fromCode(""))
        assertSame(Language.English, Language.fromCode("   "))
        assertSame(Language.English, Language.fromCode("zz"))
        assertSame(Language.English, Language.fromCode("klingon"))
    }

    @Test
    fun `every supported language has a dedicated Strings bundle with the matching code`() {
        for (lang in Language.entries) {
            val bundle = Translations.of(lang)
            assertEquals(lang.code, bundle.code, "language bundle ${lang.name} has wrong code")
        }
    }
}
