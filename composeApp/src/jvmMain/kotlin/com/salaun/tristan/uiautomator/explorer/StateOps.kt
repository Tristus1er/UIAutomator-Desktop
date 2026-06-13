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
     * Non-permission system packages that mount *gate* dialogs over the app:
     * the Google Play Services "turn on Bluetooth / Location" prompt, the
     * Bluetooth enable confirmation. Like permission dialogs they block the
     * feature behind a single positive button, so the explorer resolves them
     * through the same auto-grant machinery — with the wider
     * positive-affordance patterns of [GATE_POSITIVE_PATTERNS].
     */
    val SYSTEM_GATE_PACKAGES: Set<String> = setOf(
        "com.google.android.gms",
        "com.android.bluetooth",
    )

    /** `true` for any package whose dialogs the auto-grant chain may resolve. */
    fun isPermissionGatePackage(pkg: String): Boolean =
        pkg in PERMISSION_PACKAGES || pkg in SYSTEM_GATE_PACKAGES

    /**
     * Activity class-name fragments that identify an OEM permission / enable
     * confirmation dialog hosted *outside* the standard permission packages.
     * OPPO / OnePlus (ColorOS) routes "allow this app to turn on Bluetooth"
     * through `com.oplus.wirelesssettings/…RequestPermissionHelperActivity`
     * — a permission gate by every other measure (a small Allow/Deny dialog)
     * that package-only matching misses, stranding the explorer on what looks
     * like a foreign Settings screen. Matched case-insensitively on the
     * Activity's simple class name.
     */
    private val PERMISSION_GATE_ACTIVITY_FRAGMENTS = listOf(
        "RequestPermissionHelperActivity",
        "RequestPermissionActivity",
        "GrantPermissionsActivity",
        "ConfirmConnectActivity",
        "BluetoothPermissionActivity",
    )

    /**
     * `true` when the screen is a permission / enable gate — either by package
     * ([isPermissionGatePackage]) or by its foreground Activity being a known
     * OEM permission-helper ([PERMISSION_GATE_ACTIVITY_FRAGMENTS]). [activity]
     * is the `pkg/cls` component from `dumpsys`, or `null` when unavailable
     * (in which case only the package signal is used).
     */
    fun isPermissionGateScreen(pkg: String, activity: String?): Boolean {
        if (isPermissionGatePackage(pkg)) return true
        val cls = activity?.substringAfterLast('/') ?: return false
        return PERMISSION_GATE_ACTIVITY_FRAGMENTS.any { cls.contains(it, ignoreCase = true) }
    }

    /**
     * Home-screen launchers. Landing on one of these after a tap means the
     * app went away under us (a crash, a finish(), an exit menu) — there is
     * nothing to capture or explore there, the only sane move is to relaunch
     * the target app. Matched by exact package or by the "launcher" naming
     * convention every OEM follows.
     */
    private val LAUNCHER_PACKAGES = setOf(
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "net.oneplus.launcher",
        "com.oppo.launcher",
        "com.coloros.launcher",
    )

    fun isLauncherPackage(pkg: String): Boolean =
        pkg in LAUNCHER_PACKAGES || pkg.contains("launcher", ignoreCase = true)

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
        "cancel",
        "annuler",
        "later",
        "plus tard",
    )

    /**
     * Positive affordances on non-permission system gate dialogs (a GMS
     * "turn on Location" prompt, a Bluetooth enable confirmation). Tried only
     * AFTER the permission-specific [PERMISSION_ALLOW_PATTERNS] so a dialog
     * offering a real "Allow" still resolves to it first. Single short words
     * ("ok", "oui") are matched as whole tokens, not substrings — see
     * [findPermissionAllowNode] — so "ok" can't match inside "look".
     */
    private val GATE_POSITIVE_PATTERNS = listOf(
        "turn on",
        "enable",
        "activer",
        "got it",
        "compris",
        "i agree",
        "agree",
        "accept",
        "accepter",
        "continue",
        "continuer",
        "ok",
        "oui",
        "yes",
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
    fun fingerprint(root: UiNode): String = fingerprintInternal(root, maskDigits = false)

    /**
     * Variant of [fingerprint] where every digit run in the labelled text /
     * content-desc is collapsed to `#`. Two captures of the same screen whose
     * only difference is a counter, a timestamp or a percentage ("3
     * notifications" vs "4 notifications", "12:05" vs "12:06") share the same
     * normalized fingerprint. Used as a *secondary* dedup tier — never as the
     * canonical identity — and only honoured when the foreground activity
     * matches, so wizard steps that genuinely differ by a step number don't
     * collapse across activities.
     */
    fun normalizedFingerprint(root: UiNode): String = fingerprintInternal(root, maskDigits = true)

    private val DIGIT_RUN = Regex("\\d+")

    private fun fingerprintInternal(root: UiNode, maskDigits: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-1")
        fun visit(n: UiNode) {
            if (n.packageName in SYSTEM_UI_PACKAGES) return
            var labelledText: String
            var labelledDesc: String
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
            if (maskDigits) {
                labelledText = DIGIT_RUN.replace(labelledText, "#")
                labelledDesc = DIGIT_RUN.replace(labelledDesc, "#")
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
    fun findPermissionAllowNode(root: UiNode, includeGatePositives: Boolean = false): UiNode? {
        val candidates = ArrayList<UiNode>()
        root.walk { n ->
            if (!n.clickable || !n.enabled) return@walk
            val b = n.bounds ?: return@walk
            if (b.width <= 0 || b.height <= 0) return@walk
            val haystack = (n.text + " " + n.contentDesc).lowercase()
            if (PERMISSION_DENY_PATTERNS.any { haystack.contains(it) }) return@walk
            candidates += n
        }
        val patterns = if (includeGatePositives) {
            PERMISSION_ALLOW_PATTERNS + GATE_POSITIVE_PATTERNS
        } else {
            PERMISSION_ALLOW_PATTERNS
        }
        for (pattern in patterns) {
            val match = candidates.firstOrNull { n ->
                val haystack = (n.text + " " + n.contentDesc).lowercase()
                matchesPattern(haystack, pattern)
            }
            if (match != null) return match
        }
        return null
    }

    /**
     * Substring match for multi-word phrases; whole-token match for single
     * short words, so "ok" / "oui" / "yes" can't fire inside an unrelated
     * word ("look", "ouija", "eyes").
     */
    private fun matchesPattern(haystack: String, pattern: String): Boolean =
        if (pattern.length <= 4 && ' ' !in pattern) {
            haystack.split(' ', '\n', '\t', ',', '.', '!', '…').any { it == pattern }
        } else {
            haystack.contains(pattern)
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

    // -- App-stop (ANR / crash) dialogs ---------------------------------------

    /** Captions of the system "Application Not Responding" dialog. */
    private val ANR_PHRASES = listOf(
        "isn't responding",
        "is not responding",
        "not responding",
        "ne répond pas",
        "ne repond pas",
    )

    /** Captions of the system crash dialog. */
    private val CRASH_PHRASES = listOf(
        "has stopped",
        "keeps stopping",
        "s'est arrêté",
        "s'est arrêtée",
        "s'est arrete",
        "continue de s'arrêter",
        "continue de s'arreter",
        "a cessé de fonctionner",
        "a cesse de fonctionner",
    )

    /** "Wait" affordances on the ANR dialog (preferred: gives the app a chance to recover). */
    private val ANR_WAIT_PATTERNS = listOf("wait", "attendre", "patienter")

    /** Dismiss affordances on crash / unrecovered-ANR dialogs. */
    private val STOP_CLOSE_PATTERNS = listOf(
        "close app", "close", "fermer l'application", "fermer", "ok",
    )

    /** Max length of an ANR / crash caption ("MyLongAppName isn't responding"). */
    private const val STOP_CAPTION_MAX_LEN = 80

    /** A detected system ANR / crash dialog and the button that resolves it. */
    data class AppStopDialog(val isAnr: Boolean, val caption: String, val dismissNode: UiNode?)

    /**
     * Detects the system "app isn't responding" / "app has stopped" dialog
     * anywhere in [root]. The dialog is a small window over the app, so the
     * dominant package usually still reads as the app itself — detection must
     * scan captions, not packages. For an ANR the preferred button is "Wait"
     * (the app may still come back); for a crash it is the close affordance.
     */
    fun detectAppStopDialog(root: UiNode): AppStopDialog? {
        var anr = false
        var crash = false
        var caption = ""
        root.walk { n ->
            val raw = n.text.ifBlank { n.contentDesc }.trim()
            if (raw.isEmpty() || raw.length > STOP_CAPTION_MAX_LEN) return@walk
            val low = raw.lowercase()
            if (!anr && ANR_PHRASES.any { low.contains(it) }) { anr = true; caption = raw }
            if (!crash && !anr && CRASH_PHRASES.any { low.contains(it) }) { crash = true; caption = raw }
        }
        if (!anr && !crash) return null
        val buttons = ArrayList<UiNode>()
        root.walk { n ->
            if (!n.clickable || !n.enabled) return@walk
            val b = n.bounds ?: return@walk
            if (b.width <= 0 || b.height <= 0) return@walk
            buttons += n
        }
        fun firstMatching(patterns: List<String>): UiNode? {
            for (p in patterns) {
                val hit = buttons.firstOrNull { matchesPattern((it.text + " " + it.contentDesc).lowercase(), p) }
                if (hit != null) return hit
            }
            return null
        }
        val dismiss = if (anr) {
            firstMatching(ANR_WAIT_PATTERNS) ?: firstMatching(STOP_CLOSE_PATTERNS)
        } else {
            firstMatching(STOP_CLOSE_PATTERNS)
        }
        return AppStopDialog(isAnr = anr, caption = caption, dismissNode = dismiss)
    }

    // -- Destructive / consent heuristics --------------------------------------

    /**
     * Labels that mark an action as *destructive or irreversible*: it destroys
     * the session context (logout), user data (delete / reset), money
     * (buy / subscribe) or fires a real-world side effect (placing a call,
     * sending a message). Matched with word boundaries so "call" can't fire
     * inside "recall" or "delete" inside "undeleted".
     */
    private val DESTRUCTIVE_PATTERNS = listOf(
        // Session killers
        "log out", "logout", "sign out", "déconnexion", "se déconnecter", "deconnexion",
        // Data destruction
        "delete", "supprimer", "remove account", "erase", "effacer",
        "reset", "réinitialiser", "reinitialiser", "clear data", "uninstall", "désinstaller",
        "format",
        // Money
        "buy", "purchase", "acheter", "payer", "pay now", "subscribe", "s'abonner",
        "checkout", "commander",
        // Real-world side effects
        "call", "appeler", "send sms", "envoyer un sms", "emergency", "urgence",
    )

    private fun hasWordBoundaryMatch(haystack: String, patterns: List<String>): Boolean {
        if (haystack.isBlank()) return false
        return patterns.any { p ->
            Regex("(^|[^\\p{L}])${Regex.escape(p)}($|[^\\p{L}])").containsMatchIn(haystack)
        }
    }

    /**
     * Heuristic: tapping this clickable likely destroys something (session,
     * data, money) or triggers a real-world side effect. The explorer applies
     * [com.salaun.tristan.uiautomator.explorer.DestructivePolicy] to these.
     */
    fun isLikelyDestructive(c: ClickableRef): Boolean {
        val idToken = c.resourceId.substringAfterLast('/').replace('_', ' ').lowercase()
        val haystack = (c.text + " " + c.contentDesc).lowercase()
        return hasWordBoundaryMatch(haystack, DESTRUCTIVE_PATTERNS) ||
            hasWordBoundaryMatch(idToken, DESTRUCTIVE_PATTERNS)
    }

    /**
     * Labels of an *unlocking* affordance: a cookie / GDPR consent accept, a
     * "Get started", an "I agree". Exercising these FIRST clears the banner /
     * gate so the rest of the screen (often dimmed or blocked behind the
     * overlay) becomes actionable — the mirror image of dismiss-last.
     */
    private val CONSENT_ACCEPT_PATTERNS = listOf(
        "accept all", "tout accepter", "accept", "accepter", "j'accepte",
        "i agree", "agree", "got it", "compris", "continue", "continuer",
        "get started", "commencer", "start", "démarrer", "demarrer",
    )

    fun isLikelyConsentAccept(c: ClickableRef): Boolean {
        val haystack = (c.text + " " + c.contentDesc).lowercase()
        return hasWordBoundaryMatch(haystack, CONSENT_ACCEPT_PATTERNS)
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
     * most [maxPerSiblingGroup] representatives per group. The default is **1**:
     * a list / grid / picker counts as a *single* candidate to click, because
     * every cell of such a group leads to the same kind of screen — clicking
     * more than one only inflates the crawl with near-duplicate states. The
     * actual number of siblings in the original group is still recorded on the
     * kept [ClickableRef] as `siblingGroupSize`, so the explorer can later tell
     * that a tap came from a "picker-like" group and decide that an
     * apparently-new destination with the same structure is the same screen.
     */
    fun collectClickables(
        root: UiNode,
        pkgFilter: String,
        max: Int,
        maxPerSiblingGroup: Int = 1,
    ): List<ClickableRef> {
        data class Candidate(val node: UiNode, val ref: ClickableRef)
        val seen = HashSet<String>()
        val candidates = ArrayList<Candidate>()
        root.walk { n ->
            if (!n.enabled) return@walk
            if (!n.clickable && !n.longClickable) return@walk
            val b = n.bounds ?: return@walk
            if (b.width <= 0 || b.height <= 0) return@walk
            if (pkgFilter.isNotBlank() && n.packageName.isNotBlank() && n.packageName != pkgFilter) return@walk
            fun ref(gesture: String) = ClickableRef(
                resourceId = n.resourceId,
                className = n.className,
                text = n.text,
                contentDesc = n.contentDesc,
                bounds = SerialBounds.from(b),
                tapX = (b.left + b.right) / 2,
                tapY = (b.top + b.bottom) / 2,
                insideWheelPicker = isInsideWheelPicker(n),
                gesture = gesture,
            )
            // A node may produce up to two candidates: a tap (clickable) and a
            // long-press (long-clickable, which opens context menus a tap never
            // reaches). Each carries its own gesture so they are exercised —
            // and recorded — independently.
            if (n.clickable) {
                val key = "${n.className}|${n.resourceId}|${b.left},${b.top},${b.right},${b.bottom}|tap"
                if (seen.add(key)) candidates += Candidate(n, ref(ClickableRef.GESTURE_TAP))
            }
            if (n.longClickable) {
                val key = "${n.className}|${n.resourceId}|${b.left},${b.top},${b.right},${b.bottom}|long"
                if (seen.add(key)) candidates += Candidate(n, ref(ClickableRef.GESTURE_LONG_PRESS))
            }
        }

        // Group candidates by (parent identity, class, resource-id, gesture).
        // We use an IdentityHashMap-keyed bucket so that two distinct parents
        // that happen to compare structurally equal stay in their own buckets —
        // sibling-ness is a relationship in the actual DOM, not a value
        // equality check.
        val groups = LinkedHashMap<String, MutableList<Candidate>>()
        val parentIds = java.util.IdentityHashMap<UiNode, Int>()
        for (c in candidates) {
            val parentId = c.node.parent?.let { p -> parentIds.getOrPut(p) { parentIds.size } } ?: -1
            val gk = "$parentId|${c.node.className}|${c.node.resourceId}|${c.ref.gesture}"
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
     * Horizontal pagers / carousels on the screen: scrollable containers that
     * are markedly wider than tall (or whose class names them a pager). Their
     * content advances with a horizontal swipe, which is the ONLY way to walk
     * a swipe-only onboarding (no Next button) or to reveal carousel cards.
     * Wheel pickers are excluded — "scrolling" them only rotates labels.
     * Nested pagers are collapsed to the outermost one.
     */
    fun findHorizontalPagers(root: UiNode): List<UiNode> {
        val pagers = ArrayList<UiNode>()
        root.walk { n ->
            if (!n.scrollable || !n.enabled) return@walk
            if (isInsideWheelPicker(n)) return@walk
            val b = n.bounds ?: return@walk
            if (b.width < 300) return@walk
            val classHit = n.className.contains("ViewPager") ||
                n.className.contains("HorizontalScrollView") ||
                n.className.contains("Pager")
            val shapeHit = b.width > b.height && b.height in 1..(b.width)
            if (!classHit && !(shapeHit && b.height < 900)) return@walk
            pagers += n
        }
        // Drop pagers nested inside another collected pager.
        return pagers.filter { p ->
            var anc = p.parent
            while (anc != null) {
                if (anc in pagers) return@filter false
                anc = anc.parent
            }
            true
        }
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
     * The resource-id of the screen's outermost *application* container — e.g. a
     * Compose `settings_screen` testTag that wraps the whole page. Serves as a
     * stable screen identity: the same screen captured with a toggle flipped, a
     * switch checked, or a row scrolled into view keeps the same root id even
     * though its structural fingerprint shifts — so the explorer can recognise
     * it as ONE state instead of minting a near-duplicate for every variation.
     *
     * Selection rule: among the nodes that blanket most of the screen, carry a
     * non-blank, non-framework (`android:` / `com.android.` — e.g.
     * `android:id/content`, the decor content frame shared by every screen)
     * id and belong to the target package, the **deepest** one wins (ties broken
     * by larger area). Returns null when no such container exists (system
     * dialogs, foreign screens), in which case the caller falls back to the
     * structural fingerprint.
     *
     * Why deepest, not largest: single-Activity / Compose apps wrap every screen
     * in one shared full-screen shell (a `root_app`, a nav host) that spans the
     * whole screen on *every* page. Picking that outermost shell would give the
     * same root id to every fragment and collapse the entire app into one state.
     * The real per-screen container sits deeper inside the shell and carries a
     * screen-specific id, so descending to the deepest screen-filling container
     * distinguishes the fragments while still ignoring small inner widgets.
     */
    fun rootScreenId(root: UiNode, pkgFilter: String): String? {
        val screenArea = root.bounds?.area ?: return null
        if (screenArea <= 0L) return null
        var best: String? = null
        var bestDepth = -1
        var bestArea = 0L
        root.walk { n ->
            val rid = n.resourceId
            if (rid.isBlank() || isFrameworkResourceId(rid)) return@walk
            if (pkgFilter.isNotBlank() && n.packageName.isNotBlank() && n.packageName != pkgFilter) return@walk
            val area = n.bounds?.area ?: return@walk
            // Must blanket most of the screen to count as its root container,
            // not merely some large inner panel.
            if (area * 5 < screenArea * 3) return@walk // < 60% of the screen
            if (n.depth > bestDepth || (n.depth == bestDepth && area > bestArea)) {
                bestDepth = n.depth
                bestArea = area
                best = rid
            }
        }
        return best
    }

    /**
     * `android:id/content` (the decor content frame) and other platform ids are
     * shared by every screen, so they can never identify one. App ids — whether
     * a Compose testTag like `settings_screen` or a view id `pkg:id/foo` — are
     * not framework ids.
     */
    private fun isFrameworkResourceId(rid: String): Boolean =
        rid.startsWith("android:") || rid.startsWith("com.android.")

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

    /**
     * The set of app-owned, non-framework resource-ids present on the screen.
     * Used to tell whether two screens that share a root-container id (e.g. a
     * generic app shell like `root_app`) are really the same screen or two
     * unrelated screens that merely live inside the same shell — the former
     * share most of their ids, the latter almost none.
     */
    fun screenResourceIds(root: UiNode, pkgFilter: String): Set<String> {
        val out = HashSet<String>()
        root.walk { n ->
            val rid = n.resourceId
            if (rid.isBlank() || isFrameworkResourceId(rid)) return@walk
            if (pkgFilter.isNotBlank() && n.packageName.isNotBlank() && n.packageName != pkgFilter) return@walk
            out += rid
        }
        return out
    }

    /**
     * `true` when [root] is a *bare media surface*: no readable text, no
     * content-description, and no app-owned id'd widget smaller than the screen
     * (only full-screen containers carry ids). A full-screen video player or a
     * full-bleed image detail looks like this — structurally identical to its
     * siblings, distinguishable only by the pixels in the screenshot. The
     * explorer uses this to decide that several such screens reached from one
     * menu are distinct states (one screenshot each) rather than one merged
     * state. The strict "no small id'd widget" clause keeps ordinary detail
     * screens (which carry labels / buttons / a back affordance with ids) out.
     */
    fun isBareMediaScreen(root: UiNode, pkgFilter: String): Boolean {
        val screenArea = root.bounds?.area ?: return false
        if (screenArea <= 0L) return false
        if (hasMeaningfulText(root)) return false
        var hasSmallIdWidget = false
        root.walk { n ->
            if (hasSmallIdWidget) return@walk
            val rid = n.resourceId
            if (rid.isBlank() || isFrameworkResourceId(rid)) return@walk
            if (pkgFilter.isNotBlank() && n.packageName.isNotBlank() && n.packageName != pkgFilter) return@walk
            val area = n.bounds?.area ?: return@walk
            if (area * 5 < screenArea * 3) hasSmallIdWidget = true // an id'd widget below 60% of the screen
        }
        return !hasSmallIdWidget
    }

    fun dominantPackage(root: UiNode): String {
        val counts = HashMap<String, Int>()
        root.walk { n ->
            if (n.packageName.isNotBlank()) counts.merge(n.packageName, 1, Int::plus)
        }
        return counts.entries.maxByOrNull { it.value }?.key.orEmpty()
    }
}
