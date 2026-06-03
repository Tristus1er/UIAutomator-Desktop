package com.salaun.tristan.uiautomator.model

import com.salaun.tristan.uiautomator.testutil.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DumpParserTest {

    @Test
    fun `parses the full tree from a real dump`() {
        val root = DumpParser.parse(Fixtures.dump("sample_dump.xml"))
        assertNotNull(root)
        var count = 0
        root.walk { count++ }
        assertEquals(7, count, "expected FrameLayout + toolbar + title + content + 3 buttons")
        assertEquals("android.widget.FrameLayout", root.className)
        assertEquals("com.example.app", root.packageName)
        assertEquals(2, root.children.size)
    }

    @Test
    fun `attributes round-trip through the tree`() {
        val root = assertNotNull(DumpParser.parse(Fixtures.dump("sample_dump.xml")))
        val signIn = root.findResourceId("com.example.app:id/sign_in")
        assertNotNull(signIn)
        assertEquals("android.widget.Button", signIn.className)
        assertEquals("Sign In", signIn.text)
        assertTrue(signIn.clickable)
        assertTrue(signIn.enabled)
        val b = assertNotNull(signIn.bounds)
        assertEquals(UiBounds(100, 800, 980, 960), b)
    }

    @Test
    fun `findSmallestAt returns the deepest hit`() {
        val root = assertNotNull(DumpParser.parse(Fixtures.dump("sample_dump.xml")))
        val hit = assertNotNull(root.findSmallestAt(500, 880))
        assertEquals("com.example.app:id/sign_in", hit.resourceId)
    }

    @Test
    fun `findSmallestAt returns null outside of any node`() {
        val root = assertNotNull(DumpParser.parse(Fixtures.dump("sample_dump.xml")))
        assertNull(root.findSmallestAt(5_000, 5_000))
    }

    @Test
    fun `parent links point upwards`() {
        val root = assertNotNull(DumpParser.parse(Fixtures.dump("sample_dump.xml")))
        val title = assertNotNull(root.findResourceId("com.example.app:id/title"))
        var ancestors = 0
        var cursor = title.parent
        while (cursor != null) { ancestors++; cursor = cursor.parent }
        assertEquals(2, ancestors, "title -> toolbar -> FrameLayout")
    }

    @Test
    fun `empty bounds attribute becomes null`() {
        val xml = """
            <hierarchy>
              <node index="0" class="X" package="p" bounds="" text="" resource-id="" content-desc=""/>
            </hierarchy>
        """.trimIndent()
        val root = assertNotNull(DumpParser.parse(xml))
        assertNull(root.bounds)
    }
}

private fun UiNode.findResourceId(id: String): UiNode? {
    var found: UiNode? = null
    walk { if (found == null && it.resourceId == id) found = it }
    return found
}
