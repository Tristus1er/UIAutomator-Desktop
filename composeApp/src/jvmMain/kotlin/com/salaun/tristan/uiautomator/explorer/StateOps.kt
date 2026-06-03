package com.salaun.tristan.uiautomator.explorer

import com.salaun.tristan.uiautomator.model.UiNode
import java.security.MessageDigest

object StateOps {

    /** Subtrees from these packages are skipped during fingerprinting. */
    private val SYSTEM_UI_PACKAGES = setOf(
        "com.android.systemui",
        "com.android.system", // some OEMs split SysUI like this
    )

    /**
     * Substrings that, when found in a node's class name, mark the node as a
     * wheel-style picker. These widgets cycle their visible labels on every
     * tap without leaving the host screen, so taps on their children should
     * be collapsed into self-loops by the explorer.
     */
    private val WHEEL_PICKER_CLASS_FRAGMENTS = listOf(
        "NumberPicker",
        "DatePicker",
        "TimePicker",
    )

    /**
     * Packages used by the system permission dialog on stock Android and on
     * Google-flavoured devices. When a tap leaves the target app and lands
     * here, the explorer offers a single auto-Allow attempt before recording
     * `leftApp` so it does not stop at the permission gate.
     */
    val PERMISSION_PACKAGES: Set<String> = setOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        // Older Android releases hosted the permission UI inside the package
        // installer; covered here for forward compatibility.
        "com.android.packageinstaller",
    )

    /**
     * System Settings packages a permission flow may bounce through: some apps
     * deep-link the user to "App info" / a special-access page and let the
     * runtime permission dialog pop over it (Laqi does exactly this for
     * location). Once the dialog is granted the device is left inside Settings,
     * not the target app. The explorer BACKs out of these to climb back to the
     * app — it never clicks *into* them, so they are not treated as
     * auto-grantable like [PERMISSION_PACKAGES].
     */
    val PERMISSION_SETTINGS_PACKAGES: Set<String> = setOf(
        "com.android.settings",
        "com.oplus.settings",
        "com.coloros.settings",
    )

    /**
     * Patterns matched (case-insensitively, on text or content-desc) when
     * looking for an "Allow" affordance on the system permission dialog.
     * Order matters: we prefer the most permissive option first because the
     * crawler is exploring, not running a privacy-conscious user flow — the
     * goal is to see what is behind the permission gate. "While using the
     * app" / "Only this time" come next as fallbacks for permissions where
     * the dialog does not offer an unconditional Allow.
     */
    private val PERMISSION_ALLOW_PATTERNS = listOf(
        "allow all the time",
        "allow",
        "while using the app",
        "only this time",
        "autoriser",            // common French translations
        "tout le temps",
        "lorsque l",            // "Lorsque l'app est utilisée"
        "uniquement cette fois",
    )

    /**
     * Negative buttons that must NEVER be tapped while auto-granting. Critical
     * because several allow patterns are *substrings* of a deny label — e.g.
     * "Don't allow" contains "allow", so without this blocklist the crawler
     * would happily tap the refuse button and the permission would be denied
     * (and, with "don't ask again", denied for good). Any candidate whose
     * text / content-desc matches one of these is excluded before the
     * allow-pattern search runs. Matched case-insensitively, substring.
     */
    private val PERMISSION_DENY_PATTERNS = listOf(
        "don't allow",
        "don’t allow",     // curly apostrophe variant used by AOSP
        "dont allow",
        "deny",
        "ne pas autoriser",
        "refuser",
        "no thanks",
        "not now",
        "pas maintenant",
    )

    /**
     * SHA-1 hash describing an app's "state" in a way that is stable across
     * visits but sensitive to meaningful content changes.
     *
     * For every node we hash: class, resource-id, clickable flag and child
     * count — the structural skeleton. On top of that we include the `text`
     * and `content-desc` of nodes that have a non-blank `resource-id`: those
     * are labelled elements the developer deliberately placed (titles,
     * button labels, a11y descriptions) and their copy typically changes
     * between wizard screens that share the same layout, so we want them to
     * count as distinct states. Anonymous nodes (no resource-id) tend to be
     * list-item rows / animations / timestamps whose text is noisy, so we
     * ignore their copy to keep the fingerprint stable.
     *
     * We also drop any subtree whose `package` is the SystemUI overlay (status
     * bar, navigation bar, notification shade): the clock ticks every minute
     * and the battery / signal indicators flip throughout an exploration, all
     * of which would otherwise mint a fresh fingerprint per minute and inflate
     * the discovered-state count with copies of the same app screen. Demo
     * mode (when available) freezes those values; this filter is the
     * defence-in-depth fallback for devices where demo mode is rejected.
     */
    fun fingerprint(root: UiNode): String {
        val digest = MessageDigest.getInstance("SHA-1")
        fun visit(n: UiNode) {
            if (n.packageName in SYSTEM_UI_PACKAGES) return
            val labelledText: String
            val labelledDesc: String
            if (n.resourceId.isNotBlank()) {
                // The *typed content* of an editable field is user data, not
                // structure: the explorer auto-fills empty fields, and a filled
                // form must stay the same state as the empty one (otherwise
                // every keystroke would mint a new screen). Drop `text` for
                // editable fields; keep `content-desc` (usually the stable
                // hint/label).
                labelledText = if (isEditableField(n)) "" else n.text
                labelledDesc = n.contentDesc
            } else {
                labelledText = ""
                labelledDesc = ""
            }
            val line = buildString {
                append(n.className); append('|')
                append(n.resourceId); append('|')
                append(labelledText); append('|')
                append(labelledDesc); append('|')
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

    /**
     * Returns the closest ancestor (or the node itself) whose class name
     * contains one of [WHEEL_PICKER_CLASS_FRAGMENTS], or `null` when the node
     * sits outside of any wheel-style picker. The walk stops at the root.
     */
    fun wheelPickerAncestor(node: UiNode): UiNode? {
        var n: UiNode? = node
        while (n != null) {
            val cls = n.className
            if (WHEEL_PICKER_CLASS_FRAGMENTS.any { cls.contains(it) }) return n
            n = n.parent
        }
        return null
    }

    /**
     * Convenience: `true` when [node] is a wheel-picker child or the picker
     * itself. Used by the explorer to mark clickables that should be
     * exercised at most once and collapsed into a self-loop afterwards.
     */
    fun isInsideWheelPicker(node: UiNode): Boolean = wheelPickerAncestor(node) != null

    /**
     * Walks [root] and returns the first clickable node whose visible text
     * (or content-desc) matches one of the known "Allow" affordances on the
     * Android system permission dialog, or `null` when no such button is
     * visible. Used by the explorer to bypass permission gates instead of
     * reporting the tap as `leftApp` and giving up on whatever lives behind.
     *
     * The match is **whole-token contains** (case-insensitive) and the
     * patterns are tried in priority order — so a dialog with both an
     * "Allow" and a "While using the app" button picks the former first.
     *
     * Deny buttons are excluded up front via [PERMISSION_DENY_PATTERNS]: a
     * dialog that only offers "While using the app" / "Only this time" /
     * "Don't allow" (the standard location prompt) must resolve to "While
     * using the app", never to the deny button just because its label happens
     * to contain the substring "allow".
     */
    fun findPermissionAllowNode(root: UiNode): UiNode? {
        val candidates = ArrayList<UiNode>()
        root.walk { n ->
            if (!n.clickable || !n.enabled) return@walk
            val b = n.bounds ?: return@walk
            if (b.width <= 0 || b.height <= 0) return@walk
            val haystack = (n.text + " " + n.contentDesc).lowercase()
            if (PERMISSION_DENY_PATTERNS.any { haystack.contains(it) }) return@walk
            candidates += n
        }
        for (pattern in PERMISSION_ALLOW_PATTERNS) {
            val match = candidates.firstOrNull { n ->
                val haystack = (n.text + " " + n.contentDesc).lowercase()
                haystack.contains(pattern)
            }
            if (match != null) return match
        }
        return null
    }

    /**
     * `true` when [node] is an editable text field. EditText is the reliable
     * marker across the Android widget zoo; password fields and nodes that
     * explicitly advertise `editable=true` are covered too.
     */
    fun isEditableField(node: UiNode): Boolean =
        node.className.contains("EditText") ||
            node.password ||
            node.attributes["editable"] == "true"

    /**
     * Editable text fields inside the target package, in DOM order. Used by the
     * explorer to auto-fill forms so login / input gates that block on empty
     * fields can be walked through.
     */
    fun collectEditableFields(root: UiNode, pkgFilter: String): List<UiNode> {
        val out = ArrayList<UiNode>()
        root.walk { n ->
            if (!isEditableField(n) || !n.enabled) return@walk
            val b = n.bounds ?: return@walk
            if (b.width <= 0 || b.height <= 0) return@walk
            if (pkgFilter.isNotBlank() && n.packageName.isNotBlank() && n.packageName != pkgFilter) return@walk
            out += n
        }
        return out
    }

    /**
     * A plausible default value to type into [field], inferred from its
     * resource-id / hint / content-desc. Values are kept shell-safe (no spaces
     * or characters `adb shell input text` would choke on) so they can be typed
     * verbatim. The goal is only to get *past* a required field, not to submit
     * meaningful data.
     */
    fun defaultValueFor(field: UiNode): String {
        val hint = (field.resourceId + " " + field.contentDesc + " " + field.text).lowercase()
        return when {
            field.password || "passw" in hint || "mot de passe" in hint -> "Test1234"
            "email" in hint || "e-mail" in hint || "mail" in hint || "courriel" in hint -> "test@example.com"
            "phone" in hint || "mobile" in hint || "numero" in hint || "numéro" in hint || "tel" in hint -> "0612345678"
            "code" in hint || "otp" in hint || "pin" in hint -> "123456"
            "number" in hint || "amount" in hint || "qty" in hint || "quantit" in hint -> "1"
            "search" in hint || "recherch" in hint -> "test"
            else -> "Test"
        }
    }

    /** Words (matched on text / content-desc, lower-case, whole value) that mark a dismiss action. */
    private val DISMISS_WORDS = setOf(
        "back", "navigate up", "up", "close", "cancel", "dismiss",
        "retour", "fermer", "annuler", "précédent", "precedent",
    )

    /**
     * Heuristic: this clickable most likely navigates *away from* / dismisses
     * the current screen — a back arrow, close, cancel or up affordance. The
     * explorer exercises such actions LAST on a screen, so it walks the
     * screen's real content first instead of leaving the moment it taps the
     * top-left back arrow. The egg-config "Start detection" button was lost
     * exactly this way: `arrow_back` sat first in DOM order, so the crawler
     * left before ever reaching the primary button.
     */
    fun isLikelyDismissAction(c: ClickableRef): Boolean {
        val id = c.resourceId.substringAfterLast('/').lowercase()
        val idHit = id == "back" || id == "up" || id == "close" || id == "cancel" ||
            id.contains("arrow_back") || id.contains("back_button") || id.contains("btn_back") ||
            id.contains("_back") || id.contains("navigate_up") ||
            id.contains("close_button") || id.contains("btn_close") || id.contains("toolbar_back")
        val textHit = c.contentDesc.trim().lowercase() in DISMISS_WORDS || c.text.trim().lowercase() in DISMISS_WORDS
        return idHit || textHit
    }

    /**
     * Phrases that name an *active* long-running operation (a firmware flash, a
     * download in progress, an OS-style "do not turn off" warning). They are
     * deliberately specific — "mise à jour en cours" (update **in progress**),
     * not bare "mise à jour" which also appears in "Dernière mise à jour : 8
     * octobre 2024" — and are only honoured on a **short caption**, never
     * inside a content paragraph (a product review mentioning "connexion" must
     * not be mistaken for a connecting screen). Both rules come from real
     * false positives that made the explorer wait 5 minutes on ordinary
     * content screens.
     */
    private val ACTIVE_OP_PHRASES = listOf(
        "updating", "update in progress", "updating firmware", "firmware update",
        "mise à jour en cours", "mise a jour en cours",
        "installing", "installation en cours", "flashing",
        "downloading", "téléchargement en cours", "telechargement en cours",
        "do not turn off", "do not unplug", "do not close the app",
        "n'éteignez pas", "n'eteignez pas", "ne débranchez pas", "ne debranchez pas",
        "please wait", "veuillez patienter",
        "connexion en cours", "pairing", "appairage en cours",
        "syncing", "synchronisation en cours", "preparing", "préparation en cours",
    )

    /** Max length of a text node still considered a status *caption* (vs a paragraph). */
    private const val CAPTION_MAX_LEN = 45

    /**
     * `true` when [root] explicitly names a long-running operation in a short
     * caption, or shows a progress bar on an otherwise actionless screen. This
     * is the *textual* signal; the explorer additionally treats an actionless,
     * text-free, animating screen (a Compose "Connecting" spinner whose caption
     * never reaches the accessibility dump) as a wait screen — see
     * `Explorer.isWaitScreen`.
     */
    fun isLongRunningOperation(root: UiNode): Boolean {
        var hasProgress = false
        var phrase = false
        var clickables = 0
        root.walk { n ->
            if (n.className.contains("ProgressBar")) {
                val b = n.bounds
                if (b != null && b.width > 4 && b.height > 4) hasProgress = true
            }
            if (n.clickable && n.enabled) {
                val b = n.bounds
                if (b != null && b.width > 0 && b.height > 0) clickables++
            }
            val raw = n.text.ifBlank { n.contentDesc }.trim()
            if (raw.length in 1..CAPTION_MAX_LEN) {
                val low = raw.lowercase()
                if (ACTIVE_OP_PHRASES.any { low.contains(it) }) phrase = true
            }
        }
        return phrase || (hasProgress && clickables <= 1)
    }

    /** `true` when any node carries real text (≥ 3 chars) — i.e. the screen has content. */
    fun hasMeaningfulText(root: UiNode): Boolean {
        var found = false
        root.walk { n ->
            if (found) return@walk
            if (n.text.ifBlank { n.contentDesc }.trim().length >= 3) found = true
        }
        return found
    }

    /** A short human label for a long-operation screen (for logging), or "". */
    fun longOperationLabel(root: UiNode): String {
        var label = ""
        root.walk { n ->
            if (label.isNotBlank()) return@walk
            val raw = n.text.ifBlank { n.contentDesc }.trim()
            if (raw.length in 1..CAPTION_MAX_LEN && ACTIVE_OP_PHRASES.any { raw.lowercase().contains(it) }) {
                label = raw.take(40)
            }
        }
        return label
    }

    /**
     * Collects every clickable element on screen, with a per-sibling-group cap.
     *
     * Apps that contain a long list / grid / picker (e.g. an hour selector
     * with 24 cells) would otherwise expose 24 distinct clickables, every one
     * of which the explorer would try in turn — multiplying spurious state
     * registrations because each cell tap shifts the fingerprint a tiny bit.
     *
     * We group clickables by `(parent, className, resourceId)` and keep at
     * most [maxPerSiblingGroup] representatives per group, evenly spread so
     * the first / middle / last cells are still probed (gives signal that any
     * cell of the picker behaves the same way). The actual number of siblings
     * in the original group is recorded on each kept [ClickableRef] as
     * `siblingGroupSize`, so the explorer can later tell that a tap on a cell
     * comes from a "picker-like" group and decide that an apparently-new
     * destination state with the same structure is in fact the same screen.
     */
    fun collectClickables(
        root: UiNode,
        pkgFilter: String,
        max: Int,
        maxPerSiblingGroup: Int = 3,
    ): List<ClickableRef> {
        data class Candidate(val node: UiNode, val ref: ClickableRef)
        val seen = HashSet<String>()
        val candidates = ArrayList<Candidate>()
        root.walk { n ->
            if (!n.clickable || !n.enabled) return@walk
            val b = n.bounds ?: return@walk
            if (b.width <= 0 || b.height <= 0) return@walk
            if (pkgFilter.isNotBlank() && n.packageName.isNotBlank() && n.packageName != pkgFilter) return@walk
            val key = "${n.className}|${n.resourceId}|${b.left},${b.top},${b.right},${b.bottom}"
            if (!seen.add(key)) return@walk
            candidates += Candidate(
                node = n,
                ref = ClickableRef(
                    resourceId = n.resourceId,
                    className = n.className,
                    text = n.text,
                    contentDesc = n.contentDesc,
                    bounds = SerialBounds.from(b),
                    tapX = (b.left + b.right) / 2,
                    tapY = (b.top + b.bottom) / 2,
                    insideWheelPicker = isInsideWheelPicker(n),
                ),
            )
        }

        // Group candidates by (parent identity, class, resource-id). We use
        // an IdentityHashMap-keyed bucket so that two distinct parents that
        // happen to compare structurally equal stay in their own buckets —
        // sibling-ness is a relationship in the actual DOM, not a value
        // equality check.
        val groups = LinkedHashMap<String, MutableList<Candidate>>()
        val parentIds = java.util.IdentityHashMap<UiNode, Int>()
        for (c in candidates) {
            val parentId = c.node.parent?.let { p -> parentIds.getOrPut(p) { parentIds.size } } ?: -1
            val gk = "$parentId|${c.node.className}|${c.node.resourceId}"
            groups.getOrPut(gk) { mutableListOf() } += c
        }

        val out = ArrayList<ClickableRef>()
        for ((_, group) in groups) {
            val total = group.size
            val kept = pickRepresentatives(group, maxPerSiblingGroup)
            for (c in kept) out += c.ref.copy(siblingGroupSize = total)
        }
        return if (out.size > max) out.subList(0, max).toList() else out
    }

    /**
     * Picks up to [max] elements from [group] spread across the list, so the
     * caller observes the first / middle / last members rather than just the
     * first [max]. Stable: when [group.size] <= [max] the list is returned as-is.
     */
    private fun <T> pickRepresentatives(group: List<T>, max: Int): List<T> {
        if (max <= 0) return emptyList()
        if (group.size <= max) return group
        // Evenly-spaced indices: 0, n/k, 2n/k, …, (k-1)·n/k. Always includes 0.
        val step = group.size.toDouble() / max
        val indices = (0 until max).map { (it * step).toInt().coerceAtMost(group.size - 1) }
        return indices.distinct().map { group[it] }
    }

    /**
     * Same-shape variant of [fingerprint] that ignores text and content-desc.
     * Two screens with identical class + resource-id + clickable + child-count
     * trees share the same structure fingerprint even if their copy differs.
     *
     * The explorer uses this as a "is this the same logical screen?" probe
     * after a tap: if the destination's structure matches the source's AND the
     * tapped clickable was part of a large sibling group (a picker / list),
     * the destination is treated as a selection-only variant of the source
     * rather than a brand-new state.
     */
    fun structureFingerprint(root: UiNode): String {
        val digest = MessageDigest.getInstance("SHA-1")
        fun visit(n: UiNode) {
            if (n.packageName in SYSTEM_UI_PACKAGES) return
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

    fun dominantPackage(root: UiNode): String {
        val counts = HashMap<String, Int>()
        root.walk { n ->
            if (n.packageName.isNotBlank()) counts.merge(n.packageName, 1, Int::plus)
        }
        return counts.entries.maxByOrNull { it.value }?.key.orEmpty()
    }
}
