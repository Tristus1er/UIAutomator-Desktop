package com.salaun.tristan.uiautomator.explorer

import com.salaun.tristan.uiautomator.model.UiNode
import java.security.MessageDigest

object StateOps {

    fun fingerprint(root: UiNode): String {
        val digest = MessageDigest.getInstance("SHA-1")
        fun visit(n: UiNode) {
            val line = buildString {
                append(n.className); append('|')
                append(n.resourceId); append('|')
                append(if (n.clickable) '1' else '0'); append('|')
                append(n.children.size)
                append('\n')
            }
            digest.update(line.toByteArray(Charsets.UTF_8))
            for (c in n.children) visit(c)
        }
        visit(root)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun collectClickables(root: UiNode, pkgFilter: String, max: Int): List<ClickableRef> {
        val seen = HashSet<String>()
        val out = ArrayList<ClickableRef>()
        root.walk { n ->
            if (!n.clickable || !n.enabled) return@walk
            val b = n.bounds ?: return@walk
            if (b.width <= 0 || b.height <= 0) return@walk
            if (pkgFilter.isNotBlank() && n.packageName.isNotBlank() && n.packageName != pkgFilter) return@walk
            val key = "${n.className}|${n.resourceId}|${b.left},${b.top},${b.right},${b.bottom}"
            if (!seen.add(key)) return@walk
            out += ClickableRef(
                resourceId = n.resourceId,
                className = n.className,
                text = n.text,
                contentDesc = n.contentDesc,
                bounds = SerialBounds.from(b),
                tapX = (b.left + b.right) / 2,
                tapY = (b.top + b.bottom) / 2,
            )
        }
        return if (out.size > max) out.subList(0, max).toList() else out
    }

    fun dominantPackage(root: UiNode): String {
        val counts = HashMap<String, Int>()
        root.walk { n ->
            if (n.packageName.isNotBlank()) counts.merge(n.packageName, 1, Int::plus)
        }
        return counts.entries.maxByOrNull { it.value }?.key.orEmpty()
    }
}
