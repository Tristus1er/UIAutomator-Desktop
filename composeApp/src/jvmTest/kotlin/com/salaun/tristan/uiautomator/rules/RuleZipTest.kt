package com.salaun.tristan.uiautomator.rules

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuleZipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleSet(pkg: String) = PackageRuleSet(
        packageName = pkg,
        rules = mutableListOf(
            ScreenRule(
                id = "r1",
                name = "Accept onboarding",
                signature = ScreenSignature(requiredTexts = listOf("Accept")),
                routine = listOf(RuleAction.Click(ElementSelector(SelectorBy.TEXT, "Accept"))),
            ),
        ),
    )

    @Test
    fun `export then import reproduces the rule set`() {
        val source = RuleStore(tmp.newFolder("source"))
        val pkg = "com.example.app"
        source.save(sampleSet(pkg))

        val zip = File(tmp.root, "rules.zip")
        RuleZip.exportPackage(source, pkg, zip)
        assertTrue(zip.length() > 0)

        val dest = RuleStore(tmp.newFolder("dest"))
        val importedPkg = RuleZip.importPackage(zip, dest)
        assertEquals(pkg, importedPkg)

        val loaded = dest.load(pkg)
        assertEquals(1, loaded.rules.size)
        assertEquals("Accept onboarding", loaded.rules.single().name)
    }

    @Test
    fun `import resolves the destination from the package name, not the archive name`() {
        val source = RuleStore(tmp.newFolder("source"))
        source.save(sampleSet("com.real.pkg"))
        val zip = File(tmp.root, "totally-different-name.zip")
        RuleZip.exportPackage(source, "com.real.pkg", zip)

        val dest = RuleStore(tmp.newFolder("dest"))
        RuleZip.importPackage(zip, dest)
        assertEquals(listOf("com.real.pkg"), dest.listPackages().map { it.packageName })
    }

    @Test
    fun `zip-slip entries are refused`() {
        val bad = File(tmp.root, "evil.zip")
        ZipOutputStream(bad.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("../escape.json"))
            zip.write("boom".toByteArray())
            zip.closeEntry()
        }
        val dest = RuleStore(tmp.newFolder("safe"))
        assertFailsWith<IOException> { RuleZip.importPackage(bad, dest) }
    }
}
