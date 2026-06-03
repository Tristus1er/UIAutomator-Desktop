package com.salaun.tristan.uiautomator.i18n

import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards that every translation bundle has a non-blank value for every field.
 * If someone adds a new field to [Strings] but forgets to translate it in one
 * of the locales, this test will fail and point at the culprit.
 */
class TranslationCompletenessTest {

    @Test
    fun `every string of every translation is non-blank`() {
        val stringProps: Collection<KProperty1<Strings, *>> =
            Strings::class.memberProperties.filter { it.returnType.classifier == String::class }

        for (lang in Language.entries) {
            val bundle = Translations.of(lang)
            for (prop in stringProps) {
                val value = prop.get(bundle) as String
                assertTrue(
                    value.isNotBlank(),
                    "blank string in ${lang.name} bundle: ${prop.name}",
                )
            }
        }
    }

    @Test
    fun `format placeholders survive across translations`() {
        // A small sanity net: placeholder strings are expected to contain the
        // matching %s / %d markers in every language.
        data class Check(val pick: (Strings) -> String, val regex: Regex)

        val expectations = listOf(
            Check({ s: Strings -> s.captureStatusOkFmt }, Regex("%1\\\$d.*%2\\\$d")),
            Check({ s: Strings -> s.explorerProgressFmt }, Regex("%1\\\$d.*%2\\\$d.*%3\\\$d")),
            Check({ s: Strings -> s.sessionsDeleteConfirmBodyFmt }, Regex("%s")),
            Check({ s: Strings -> s.sessionsStatesTransitionsFmt }, Regex("%1\\\$d.*%2\\\$d")),
            Check({ s: Strings -> s.detailBoundsFmt }, Regex("%1\\\$d.*%2\\\$d.*%3\\\$d.*%4\\\$d")),
        )

        for (lang in Language.entries) {
            val bundle = Translations.of(lang)
            for (check in expectations) {
                val value = check.pick(bundle)
                assertTrue(
                    check.regex.containsMatchIn(value),
                    "placeholder mismatch in ${lang.name}: \"$value\" does not match ${check.regex}",
                )
            }
        }
    }
}
