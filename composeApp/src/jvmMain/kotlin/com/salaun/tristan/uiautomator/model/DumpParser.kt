package com.salaun.tristan.uiautomator.model

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

object DumpParser {

    private val boundsPattern = Regex("""\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")

    fun parse(xml: String): UiNode? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
            try { setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) {}
            try { setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Exception) {}
        }
        val builder = factory.newDocumentBuilder()
        val doc = ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)).use { builder.parse(it) }
        val root = doc.documentElement ?: return null

        // UIAutomator dump wraps everything in <hierarchy>; treat its first child as root if it's a node,
        // otherwise wrap the hierarchy itself.
        val nodeChildren = root.childNodes.let { list ->
            (0 until list.length).map { list.item(it) }.filter { it.nodeType == Node.ELEMENT_NODE && (it as Element).tagName == "node" }
        }
        return when (nodeChildren.size) {
            0 -> null
            1 -> convert(nodeChildren.first() as Element, depth = 0)
            else -> {
                // Multiple top-level windows: create a synthetic root.
                val synthetic = UiNode(
                    index = 0,
                    depth = 0,
                    className = "hierarchy",
                    packageName = "",
                    resourceId = "",
                    text = "",
                    contentDesc = "",
                    bounds = unionBounds(nodeChildren.mapNotNull { parseBounds((it as Element).getAttribute("bounds")) }),
                    clickable = false,
                    checkable = false,
                    checked = false,
                    enabled = true,
                    focusable = false,
                    focused = false,
                    scrollable = false,
                    longClickable = false,
                    password = false,
                    selected = false,
                    attributes = emptyMap(),
                )
                nodeChildren.forEachIndexed { i, e ->
                    val child = convert(e as Element, depth = 1, indexOverride = i)
                    child.parent = synthetic
                    synthetic.children += child
                }
                synthetic
            }
        }
    }

    private fun convert(element: Element, depth: Int, indexOverride: Int? = null): UiNode {
        val attrs = mutableMapOf<String, String>()
        val attrNodes = element.attributes
        for (i in 0 until attrNodes.length) {
            val a = attrNodes.item(i)
            attrs[a.nodeName] = a.nodeValue ?: ""
        }
        val node = UiNode(
            index = indexOverride ?: attrs["index"]?.toIntOrNull() ?: 0,
            depth = depth,
            className = attrs["class"].orEmpty(),
            packageName = attrs["package"].orEmpty(),
            resourceId = attrs["resource-id"].orEmpty(),
            text = attrs["text"].orEmpty(),
            contentDesc = attrs["content-desc"].orEmpty(),
            bounds = parseBounds(attrs["bounds"].orEmpty()),
            clickable = attrs["clickable"].toBoolSafe(),
            checkable = attrs["checkable"].toBoolSafe(),
            checked = attrs["checked"].toBoolSafe(),
            enabled = attrs["enabled"]?.toBoolSafe() ?: true,
            focusable = attrs["focusable"].toBoolSafe(),
            focused = attrs["focused"].toBoolSafe(),
            scrollable = attrs["scrollable"].toBoolSafe(),
            longClickable = attrs["long-clickable"].toBoolSafe(),
            password = attrs["password"].toBoolSafe(),
            selected = attrs["selected"].toBoolSafe(),
            attributes = attrs,
        )
        val children = element.childNodes
        for (i in 0 until children.length) {
            val c = children.item(i)
            if (c.nodeType == Node.ELEMENT_NODE && (c as Element).tagName == "node") {
                val child = convert(c, depth + 1)
                child.parent = node
                node.children += child
            }
        }
        return node
    }

    private fun parseBounds(raw: String): UiBounds? {
        val m = boundsPattern.find(raw) ?: return null
        val (l, t, r, b) = m.destructured
        return UiBounds(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }

    private fun unionBounds(list: List<UiBounds>): UiBounds? {
        if (list.isEmpty()) return null
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
        for (x in list) {
            if (x.left < l) l = x.left
            if (x.top < t) t = x.top
            if (x.right > r) r = x.right
            if (x.bottom > b) b = x.bottom
        }
        return UiBounds(l, t, r, b)
    }

    private fun String?.toBoolSafe(): Boolean = this?.equals("true", ignoreCase = true) == true
}
