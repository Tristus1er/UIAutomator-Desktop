package com.salaun.tristan.uiautomator.rules

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleSet(pkg: String) = PackageRuleSet(
        packageName = pkg,
        rules = mutableListOf(
            ScreenRule(
                id = "r1",
                name = "Onboarding accept",
                enabled = true,
                signature = ScreenSignature(requiredTexts = listOf("Accept")),
                routine = listOf(
                    RuleAction.Scroll(direction = ScrollDirection.DOWN, amount = ScrollAmount.ToEnd),
                    RuleAction.Click(ElementSelector(SelectorBy.TEXT, "Accept", MatchMode.CONTAINS)),
                ),
            ),
            ScreenRule(
                id = "r2",
                name = "Disabled one",
                enabled = false,
                signature = ScreenSignature(rootId = "home_screen"),
                routine = listOf(RuleAction.Back),
            ),
        ),
    )

    @Test
    fun `save then load round-trips the rule set`() {
        val store = RuleStore(tmp.newFolder())
        val pkg = "com.example.app"
        store.save(sampleSet(pkg))

        val loaded = store.load(pkg)
        assertEquals(pkg, loaded.packageName)
        assertEquals(2, loaded.rules.size)
        val r1 = loaded.rules.first { it.id == "r1" }
        assertEquals(2, r1.routine.size)
        assertTrue(r1.routine[0] is RuleAction.Scroll)
        val click = r1.routine[1] as RuleAction.Click
        assertEquals("Accept", click.selector.value)
        assertEquals(MatchMode.CONTAINS, click.selector.match)
    }

    @Test
    fun `listPackages reports per-package stats`() {
        val store = RuleStore(tmp.newFolder())
        store.save(sampleSet("com.a"))
        store.save(sampleSet("com.b"))

        val summaries = store.listPackages()
        assertEquals(listOf("com.a", "com.b"), summaries.map { it.packageName })
        val a = summaries.first { it.packageName == "com.a" }
        assertEquals(2, a.ruleCount)
        assertEquals(1, a.enabledCount)
        assertEquals(3, a.totalActions, "2 actions in r1 + 1 in r2")
    }

    @Test
    fun `delete removes the package file`() {
        val store = RuleStore(tmp.newFolder())
        store.save(sampleSet("com.example.app"))
        store.delete("com.example.app")
        assertTrue(store.load("com.example.app").rules.isEmpty())
        assertTrue(store.listPackages().isEmpty())
    }

    @Test
    fun `saving an empty rule set removes the file`() {
        val store = RuleStore(tmp.newFolder())
        store.save(sampleSet("com.example.app"))
        store.save(PackageRuleSet(packageName = "com.example.app"))
        assertFalse(store.listPackages().any { it.packageName == "com.example.app" })
    }

    @Test
    fun `loading an unknown package yields an empty set`() {
        val store = RuleStore(tmp.newFolder())
        val set = store.load("never.seen")
        assertEquals("never.seen", set.packageName)
        assertTrue(set.rules.isEmpty())
    }
}
