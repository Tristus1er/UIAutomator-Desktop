package com.salaun.tristan.uiautomator.explorer

import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Outcome of [waitForScreenIdle]. */
data class ScreenIdleResult(
    /** The last screenshot captured (PNG bytes). */
    val png: ByteArray,
    /** `true` if two consecutive screenshots were "the same screen"; `false` if we gave up after the timeout. */
    val stable: Boolean,
    /** Total number of screenshots taken during the wait. */
    val screenshotsTaken: Int,
)

/**
 * Polls screenshots until two consecutive ones show the same settled screen,
 * meaning the screen has stopped *transitioning*. Returns the last screenshot
 * together with whether the wait actually converged (or timed out).
 *
 * Crucially, "the same" is a **tolerant** comparison, not byte-equality: a
 * permanently-animating decoration (a bobbing mascot, a blinking caret, a
 * looping spinner) typically covers only a small fraction of the screen, yet a
 * strict byte comparison would judge every pair of frames different and burn
 * the entire [maxMs] budget on *every* capture. Apps like this turned an
 * 8-second idle ceiling into 8 seconds spent per screen. We instead sample a
 * grid of pixels and treat the screen as settled once the fraction that changed
 * falls below [stabilityTolerance] — a real screen transition (a slide / fade
 * covering much of the viewport) still keeps us waiting, while a tiny looping
 * animation no longer does.
 *
 * We only use screenshots, not `uiautomator dump`, because the dump can raise
 * "could not get idle state" while animations are still running — exactly the
 * condition we are trying to escape. The expensive XML dump is deferred to the
 * caller which fires it once the screen is settled.
 *
 * @param takeScreenshot coroutine that returns a fresh PNG encoded screenshot.
 * @param pollMs interval between screenshot comparisons.
 * @param maxMs upper bound on the total wait time before we give up.
 * @param stabilityTolerance max fraction of sampled pixels allowed to differ
 *   between two consecutive frames for the screen to count as settled.
 */
suspend fun waitForScreenIdle(
    takeScreenshot: suspend () -> ByteArray,
    pollMs: Long = 350L,
    maxMs: Long,
    stabilityTolerance: Double = 0.03,
): ScreenIdleResult {
    require(maxMs > 0) { "maxMs must be positive" }
    val start = System.currentTimeMillis()
    var lastPng = takeScreenshot()
    var lastHash = lastPng.contentHashCode()
    var lastImg = decodeGrid(lastPng)
    var screenshots = 1

    while (System.currentTimeMillis() - start < maxMs) {
        delay(pollMs)
        val png = takeScreenshot()
        screenshots++
        val hash = png.contentHashCode()
        // Cheap fast-path: byte-identical frames are trivially settled.
        if (hash == lastHash) {
            return ScreenIdleResult(png = png, stable = true, screenshotsTaken = screenshots)
        }
        val img = decodeGrid(png)
        // Tolerant path: if both frames decoded, accept "settled" when only a
        // small, animation-sized fraction of the sampled pixels moved.
        if (img != null && lastImg != null && gridsSettled(lastImg, img, stabilityTolerance)) {
            return ScreenIdleResult(png = png, stable = true, screenshotsTaken = screenshots)
        }
        lastPng = png
        lastHash = hash
        lastImg = img
    }
    return ScreenIdleResult(png = lastPng, stable = false, screenshotsTaken = screenshots)
}

/**
 * `true` when two screenshots differ by more than [tolerance] of their sampled
 * pixels — i.e. the screen is actively animating between [a] and [b]. Used to
 * recognise a Compose "wait" screen (a Connecting / loading / updating screen
 * whose caption and spinner are drawn but absent from the accessibility tree)
 * by its motion rather than its (missing) text. Falls back to byte comparison
 * when either frame can't be decoded.
 */
fun screenshotsDiffer(a: ByteArray, b: ByteArray, tolerance: Double = 0.005): Boolean {
    val ga = decodeGrid(a) ?: return !a.contentEquals(b)
    val gb = decodeGrid(b) ?: return !a.contentEquals(b)
    return !gridsSettled(ga, gb, tolerance)
}

/** Number of sample points per axis used by the tolerant frame comparison. */
private const val GRID = 48

/**
 * Decodes [png] and returns a [GRID]×[GRID] grid of packed-RGB samples, or
 * `null` if the bytes can't be decoded (in which case the caller falls back to
 * byte-hash comparison). Sampling to a fixed small grid makes the per-poll
 * comparison O(GRID²) regardless of the real screenshot resolution.
 */
private fun decodeGrid(png: ByteArray): IntArray? {
    val img = runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull() ?: return null
    val w = img.width
    val h = img.height
    if (w <= 0 || h <= 0) return null
    val out = IntArray(GRID * GRID)
    for (gy in 0 until GRID) {
        val y = (gy * (h - 1)) / (GRID - 1)
        for (gx in 0 until GRID) {
            val x = (gx * (w - 1)) / (GRID - 1)
            out[gy * GRID + gx] = img.getRGB(x, y)
        }
    }
    return out
}

/**
 * `true` when fewer than [tolerance] of the grid samples changed by more than a
 * small per-channel amount between [a] and [b] — i.e. the screen has settled
 * apart from a tiny animated region.
 */
private fun gridsSettled(a: IntArray, b: IntArray, tolerance: Double): Boolean {
    if (a.size != b.size) return false
    val perPixelThreshold = 48 // sum of |dR|+|dG|+|dB|
    var changed = 0
    for (i in a.indices) {
        val pa = a[i]; val pb = b[i]
        val dr = ((pa ushr 16) and 0xFF) - ((pb ushr 16) and 0xFF)
        val dg = ((pa ushr 8) and 0xFF) - ((pb ushr 8) and 0xFF)
        val db = (pa and 0xFF) - (pb and 0xFF)
        val dist = (if (dr < 0) -dr else dr) + (if (dg < 0) -dg else dg) + (if (db < 0) -db else db)
        if (dist > perPixelThreshold) changed++
    }
    return changed.toDouble() / a.size < tolerance
}
