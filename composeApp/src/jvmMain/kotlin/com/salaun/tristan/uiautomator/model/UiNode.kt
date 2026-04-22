package com.salaun.tristan.uiautomator.model

data class UiBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong().coerceAtLeast(0) * height.toLong().coerceAtLeast(0)
    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
}

class UiNode(
    val index: Int,
    val depth: Int,
    val className: String,
    val packageName: String,
    val resourceId: String,
    val text: String,
    val contentDesc: String,
    val bounds: UiBounds?,
    val clickable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val scrollable: Boolean,
    val longClickable: Boolean,
    val password: Boolean,
    val selected: Boolean,
    val attributes: Map<String, String>,
    val children: MutableList<UiNode> = mutableListOf(),
) {
    var parent: UiNode? = null
        internal set

    val label: String by lazy {
        val cls = className.substringAfterLast('.').ifBlank { className }
        buildString {
            append(cls.ifBlank { "node" })
            if (resourceId.isNotBlank()) {
                append(" ")
                append(resourceId.substringAfterLast('/'))
            }
            if (text.isNotBlank()) {
                append(" \"")
                append(text.take(40))
                if (text.length > 40) append('…')
                append('"')
            } else if (contentDesc.isNotBlank()) {
                append(" [")
                append(contentDesc.take(40))
                if (contentDesc.length > 40) append('…')
                append(']')
            }
        }
    }

    fun walk(action: (UiNode) -> Unit) {
        action(this)
        for (c in children) c.walk(action)
    }

    fun findSmallestAt(x: Int, y: Int): UiNode? {
        var best: UiNode? = null
        var bestArea = Long.MAX_VALUE
        walk { node ->
            val b = node.bounds ?: return@walk
            if (b.contains(x, y) && b.area in 1 until bestArea) {
                best = node
                bestArea = b.area
            }
        }
        return best
    }
}
