package com.salaun.tristan.uiautomator.explorer

import com.salaun.tristan.uiautomator.model.UiBounds
import com.salaun.tristan.uiautomator.model.UiNode
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

/**
 * Stitches several scrolled screenshots of the same view into one tall image,
 * detecting and preserving "fixed bands" (sticky headers / footers / FABs that
 * stay in place when the inner content scrolls).
 *
 * The strategy is intentionally pixel-based: we ask UIAutomator where the
 * scrollable container lives, then for every pair of consecutive frames we
 *  1. measure the vertical shift `dy` of the scrolling content, and
 *  2. detect rows inside the container that did NOT move — those are sticky.
 *
 * Fixed bands are anchored from the first frame; only the inner "live" zone is
 * stitched (one frame's worth of live zone, plus all the new content uncovered
 * by each subsequent scroll).
 */
object ScrollCapture {

    /** A single screenshot taken during a scroll-capture pass. */
    data class Frame(
        val png: ByteArray,
        val xml: String,
        val root: UiNode,
        /** Vertical pixels the scrolling content moved up since the previous frame (>= 0). 0 for the first frame. */
        val scrollDelta: Int,
    )

    /**
     * Result of a stitched capture. All Y coordinates are *device pixels* —
     * either real (within a single frame) or virtual (within the tall stitched
     * image). The mapping between the two is recovered with [virtualToReal].
     */
    data class Stitched(
        val pngBytes: ByteArray,
        val width: Int,
        val virtualHeight: Int,
        val realHeight: Int,
        val frames: List<Frame>,
        /** Bounds of the scrollable container, in real device pixels (frame 0). */
        val scrollableBounds: UiBounds,
        /**
         * Effective top of the live (moving) zone in real device pixels,
         * i.e. `scrollableBounds.top + stickyHeaderHeightInsideScrollable`.
         * Anything above this stays put across frames.
         */
        val liveTop: Int,
        /**
         * Effective bottom of the live zone in real device pixels (exclusive),
         * i.e. `scrollableBounds.bottom - stickyFooterHeightInsideScrollable`.
         */
        val liveBottom: Int,
        /**
         * For each frame index, the virtual Y at which that frame's live-zone
         * top lands in the stitched image. `frameVirtualLiveTops[0]` always
         * equals `liveTop` (frame 0 is anchored with its full live zone).
         */
        val frameVirtualLiveTops: List<Int>,
        /** Swipe used to advance from one frame to the next, recorded so we can replay/reverse it for tap routing. */
        val swipe: Swipe,
    ) {
        val liveZoneHeight: Int get() = liveBottom - liveTop
        val fixedBottomBandHeight: Int get() = realHeight - liveBottom

        /**
         * Resolves a virtual click at [vx], [vy] into "which frame to scroll
         * to, and where in that frame the real tap should land".
         */
        fun virtualToReal(vx: Int, vy: Int): TapTarget {
            // Fixed top: zone above liveTop in frame 0 — same location across all frames.
            if (vy < liveTop) return TapTarget(frameIndex = 0, realX = vx, realY = vy, kind = TapKind.FixedTop)
            // Fixed bottom: zone below the last live-zone row in the stitched image.
            val virtualBottomBandStart = virtualHeight - fixedBottomBandHeight
            if (vy >= virtualBottomBandStart) {
                val realY = liveBottom + (vy - virtualBottomBandStart)
                return TapTarget(frameIndex = frames.size - 1, realX = vx, realY = realY, kind = TapKind.FixedBottom)
            }
            // Live zone: locate the frame whose live window covers `vy`.
            // frameVirtualLiveTops[i] = virtual Y of frame i's live-zone top.
            // Frame i covers virtual rows [frameVirtualLiveTops[i], frameVirtualLiveTops[i] + liveZoneHeight).
            var bestIdx = 0
            for (i in frames.indices) {
                val top = frameVirtualLiveTops[i]
                if (vy >= top) bestIdx = i else break
            }
            val realY = liveTop + (vy - frameVirtualLiveTops[bestIdx])
            return TapTarget(frameIndex = bestIdx, realX = vx, realY = realY.coerceIn(liveTop, liveBottom - 1), kind = TapKind.Live)
        }
    }

    enum class TapKind { FixedTop, FixedBottom, Live }
    data class TapTarget(val frameIndex: Int, val realX: Int, val realY: Int, val kind: TapKind)

    /** Parameters of the inter-frame scroll gesture, in real device pixels. */
    data class Swipe(val x: Int, val yStart: Int, val yEnd: Int, val durationMs: Int)

    // -- Scrollable detection -------------------------------------------------

    /**
     * Picks the most promising scrollable container in [root]. We prefer the
     * one with the largest area: a typical app screen has at most one big
     * scroll view (RecyclerView / NestedScrollView / ScrollView); secondary
     * scrollables are usually small horizontal carousels we don't want to
     * touch.
     */
    fun findScrollable(root: UiNode): UiNode? {
        var best: UiNode? = null
        var bestArea = 0L
        root.walk { n ->
            if (!n.scrollable) return@walk
            val b = n.bounds ?: return@walk
            // Need a meaningful vertical extent: a swipe of < 100 px is too
            // small to expect uiautomator-scrollable content underneath.
            if (b.height < 200) return@walk
            if (b.area > bestArea) { best = n; bestArea = b.area }
        }
        return best
    }

    // -- Image helpers --------------------------------------------------------

    fun decodePng(bytes: ByteArray): BufferedImage =
        ByteArrayInputStream(bytes).use { ImageIO.read(it) }
            ?: error("Cannot decode screenshot PNG (${bytes.size} bytes)")

    fun encodePng(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /**
     * Extracts a compact per-row signature: 16 evenly-spaced ARGB samples per
     * row, packed as 64 bytes. Used to compare row similarity quickly when
     * matching scroll offsets and detecting fixed bands.
     */
    private fun rowSignatures(image: BufferedImage, top: Int, bottom: Int): Array<IntArray> {
        require(top in 0..image.height && bottom in top..image.height) { "rows out of range" }
        val rows = bottom - top
        val w = image.width
        val cols = 16
        val out = Array(rows) { IntArray(cols) }
        val xs = IntArray(cols) { (it * (w - 1)) / (cols - 1).coerceAtLeast(1) }
        val rowBuf = IntArray(w)
        for (i in 0 until rows) {
            image.getRGB(0, top + i, w, 1, rowBuf, 0, w)
            val target = out[i]
            for (k in 0 until cols) target[k] = rowBuf[xs[k]]
        }
        return out
    }

    private fun rgbDistance(a: Int, b: Int): Int {
        val ar = (a ushr 16) and 0xFF; val br = (b ushr 16) and 0xFF
        val ag = (a ushr 8) and 0xFF;  val bg = (b ushr 8) and 0xFF
        val ab = a and 0xFF;            val bb2 = b and 0xFF
        // L1 distance in RGB — fast, anti-aliasing-tolerant enough.
        var d = ar - br; if (d < 0) d = -d
        var dg = ag - bg; if (dg < 0) dg = -dg
        var db = ab - bb2; if (db < 0) db = -db
        return d + dg + db
    }

    private fun rowDiff(a: IntArray, b: IntArray): Int {
        var sum = 0
        for (i in a.indices) sum += rgbDistance(a[i], b[i])
        return sum
    }

    /**
     * Per-row "is this nearly the same row" predicate. Threshold is a sum of
     * absolute differences across 16 RGB samples, so a value of 16 * 16 = 256
     * tolerates ~16 levels of drift per channel per sample (anti-aliasing,
     * compression noise).
     */
    private const val SAME_ROW_THRESHOLD = 16 * 16

    // -- Scroll detection -----------------------------------------------------

    /**
     * Finds the integer dy >= 0 such that `curr[y] ≈ prev[y + dy]` for most y
     * within the live-zone candidates. Returns 0 when no consistent shift
     * stands out (typically: end of list reached). [maxScanRange] caps the
     * search to avoid spending O(H²) when the pages are tall.
     */
    private fun detectScrollDelta(
        prev: BufferedImage,
        curr: BufferedImage,
        liveTop: Int,
        liveBottom: Int,
    ): Int {
        val sigPrev = rowSignatures(prev, liveTop, liveBottom)
        val sigCurr = rowSignatures(curr, liveTop, liveBottom)
        val h = sigPrev.size
        if (h < 4) return 0

        // Test dy = 0 (no scroll) as a baseline. A near-zero best cost means
        // the screen barely changed → end of scrollable reached.
        val maxDy = h - 4
        var bestDy = 0
        var bestCost = Int.MAX_VALUE
        for (dy in 0..maxDy) {
            var sum = 0L
            val rows = h - dy
            // Sample every 2 rows to halve the cost; signatures already sample
            // every Nth column, so the overall complexity is acceptable for
            // typical 1080x2400 screens.
            var counted = 0
            var y = 0
            while (y < rows) {
                sum += rowDiff(sigCurr[y], sigPrev[y + dy])
                counted++
                y += 2
            }
            if (counted == 0) continue
            val cost = (sum / counted).toInt()
            if (cost < bestCost) { bestCost = cost; bestDy = dy }
        }
        // If the best match is dy=0 OR the cost is comparable, consider the
        // scroll exhausted: nothing meaningful changed between the two frames.
        if (bestDy == 0) return 0
        // Reject suspicious "tiny scroll" matches: a real scroll moves at
        // least a handful of pixels, otherwise it's probably a stale frame.
        if (bestDy < 4) return 0
        return bestDy
    }

    // -- Fixed-band detection -------------------------------------------------

    /**
     * Returns the height of the sticky top band INSIDE the scrollable
     * container: the contiguous run of rows starting at [scrollableTop] that
     * stayed identical between [prev] and [curr]. Stops at the first row that
     * changed.
     */
    private fun detectStickyTopHeight(
        prev: BufferedImage,
        curr: BufferedImage,
        scrollableTop: Int,
        scrollableBottom: Int,
    ): Int {
        val sigPrev = rowSignatures(prev, scrollableTop, scrollableBottom)
        val sigCurr = rowSignatures(curr, scrollableTop, scrollableBottom)
        var stuck = 0
        while (stuck < sigPrev.size && rowDiff(sigPrev[stuck], sigCurr[stuck]) < SAME_ROW_THRESHOLD) stuck++
        return stuck
    }

    /** Mirror of [detectStickyTopHeight] from the bottom. */
    private fun detectStickyBottomHeight(
        prev: BufferedImage,
        curr: BufferedImage,
        scrollableTop: Int,
        scrollableBottom: Int,
    ): Int {
        val sigPrev = rowSignatures(prev, scrollableTop, scrollableBottom)
        val sigCurr = rowSignatures(curr, scrollableTop, scrollableBottom)
        var stuck = 0
        var i = sigPrev.size - 1
        while (i >= 0 && rowDiff(sigPrev[i], sigCurr[i]) < SAME_ROW_THRESHOLD) { stuck++; i-- }
        return stuck
    }

    // -- Capture orchestration ------------------------------------------------

    /**
     * Performs the scroll-and-stitch pass.
     *
     * @param initial          first frame already captured (before any scroll).
     * @param scrollableNode   the chosen scrollable container in [initial].
     * @param doSwipe          performs an inter-frame scroll on the device.
     * @param captureFrame     captures the current device state (PNG + XML + tree).
     * @param maxFrames        upper bound on the scroll dance, to keep things finite.
     */
    suspend fun captureAndStitch(
        initial: Frame,
        scrollableNode: UiNode,
        doSwipe: suspend (Swipe) -> Unit,
        captureFrame: suspend () -> Pair<ByteArray, Pair<String, UiNode>>,
        maxFrames: Int = 12,
    ): Stitched {
        val rawBounds = scrollableNode.bounds ?: error("Scrollable container has no bounds")
        val initImg = decodePng(initial.png)
        val realW = initImg.width
        val realH = initImg.height
        // UIAutomator usually reports on-screen bounds, but a few OEM
        // implementations return raw view dimensions that overflow the
        // screen; clamp so all subsequent pixel slicing stays in range.
        val scrollable = UiBounds(
            left = rawBounds.left.coerceIn(0, realW),
            top = rawBounds.top.coerceIn(0, realH),
            right = rawBounds.right.coerceIn(0, realW),
            bottom = rawBounds.bottom.coerceIn(0, realH),
        )

        // Choose a swipe that exercises ~70% of the scrollable height — large
        // enough to expose meaningful new content per step, small enough to
        // overlap the previous frame so the matcher has signal to anchor on.
        val midX = (scrollable.left + scrollable.right) / 2
        val travel = (scrollable.height * 70) / 100
        val centerY = (scrollable.top + scrollable.bottom) / 2
        val swipe = Swipe(
            x = midX,
            yStart = centerY + travel / 2,
            yEnd = centerY - travel / 2,
            durationMs = 350,
        )

        val frames = mutableListOf(initial)
        // Will be progressively widened (max over observed bands) as new
        // frames come in. Initialised to "no sticky bands inside the
        // container" — the very first frame can't tell us anything yet.
        var stickyTopH = 0
        var stickyBottomH = 0
        var prevImage = initImg

        while (frames.size < maxFrames) {
            doSwipe(swipe)
            val (nextPng, xmlAndTree) = captureFrame()
            val (nextXml, nextRoot) = xmlAndTree
            val nextImg = decodePng(nextPng)

            // Sticky bands: take the max we've ever observed. A first scroll
            // may briefly leave the FAB or app bar in place — we want the
            // largest stable sticky region across the whole sequence.
            stickyTopH = max(
                stickyTopH,
                detectStickyTopHeight(prevImage, nextImg, scrollable.top, scrollable.bottom),
            )
            stickyBottomH = max(
                stickyBottomH,
                detectStickyBottomHeight(prevImage, nextImg, scrollable.top, scrollable.bottom),
            )

            val liveTopNow = scrollable.top + stickyTopH
            val liveBottomNow = scrollable.bottom - stickyBottomH
            // Defensive: if sticky bands eat the whole container, bail.
            if (liveBottomNow - liveTopNow < 8) break

            val dy = detectScrollDelta(prevImage, nextImg, liveTopNow, liveBottomNow)
            if (dy == 0) {
                // End of list — discard the frame we just captured (it carries
                // no new pixels) and stop the loop.
                break
            }
            frames += Frame(png = nextPng, xml = nextXml, root = nextRoot, scrollDelta = dy)
            prevImage = nextImg
        }

        // Build the stitched image.
        val liveTop = scrollable.top + stickyTopH
        val liveBottom = scrollable.bottom - stickyBottomH
        val liveH = liveBottom - liveTop
        val totalNew = frames.drop(1).sumOf { it.scrollDelta }
        val virtualHeight = realH + totalNew

        val stitched = BufferedImage(realW, virtualHeight, BufferedImage.TYPE_INT_ARGB)
        val g = stitched.createGraphics()
        try {
            // 1. Paint frame 0 in full at the top of the virtual image. This
            //    naturally seats the fixed top band, the first live-zone slice
            //    and the fixed bottom band at their virtual positions.
            g.drawImage(initImg, 0, 0, null)

            // 2. For each subsequent frame, the bottom `dy` rows of its live
            //    zone are the freshly-revealed content. We paste them right
            //    below the previous live-zone end, then push the bottom fixed
            //    band further down accordingly.
            var virtualLiveBottom = liveBottom // virtual Y where stitched live content ends so far
            for (i in 1 until frames.size) {
                val img = if (i == 1) decodePng(frames[1].png) else decodePng(frames[i].png)
                val dy = frames[i].scrollDelta
                // New content = liveBottom - dy .. liveBottom (in real frame pixels).
                val newBlock = img.getSubimage(0, liveBottom - dy, realW, dy)
                g.drawImage(newBlock, 0, virtualLiveBottom, null)
                virtualLiveBottom += dy
            }

            // 3. Repaint the fixed bottom band of frame 0 immediately below
            //    the now-extended live region. (frame 0's drawImage above
            //    already painted it once, but its position is wrong when
            //    totalNew > 0 — overwrite it at the correct virtual Y.)
            if (totalNew > 0) {
                val bottomBandH = realH - liveBottom
                if (bottomBandH > 0) {
                    val band = initImg.getSubimage(0, liveBottom, realW, bottomBandH)
                    g.drawImage(band, 0, virtualLiveBottom, null)
                }
            }
        } finally {
            g.dispose()
        }

        // Per-frame virtual-live-tops: frame 0 lives at `liveTop`, then each
        // subsequent frame's live top sits `dy` lower than the previous.
        val virtualTops = IntArray(frames.size)
        virtualTops[0] = liveTop
        for (i in 1 until frames.size) {
            virtualTops[i] = virtualTops[i - 1] + frames[i].scrollDelta
        }

        return Stitched(
            pngBytes = encodePng(stitched),
            width = realW,
            virtualHeight = virtualHeight,
            realHeight = realH,
            frames = frames.toList(),
            scrollableBounds = scrollable,
            liveTop = liveTop,
            liveBottom = liveBottom,
            frameVirtualLiveTops = virtualTops.toList(),
            swipe = swipe,
        )
    }

    // -- Merged XML / clickables ---------------------------------------------

    /**
     * Builds a list of [ClickableRef]s for the stitched view, by walking each
     * frame's XML and re-offsetting the bounds of nodes inside the live zone
     * to virtual coordinates. Clickables of the fixed bands (and of frame 0's
     * live zone) are kept once; clickables of subsequent frames are kept only
     * if their *real* bounds fall in the bottom `dy` strip — otherwise they
     * duplicate something already harvested from an earlier frame.
     */
    fun mergedClickables(stitched: Stitched, pkgFilter: String): List<ClickableRef> {
        val out = ArrayList<ClickableRef>()
        val seen = HashSet<String>()

        fun addClick(b: UiBounds, virtualTop: Int, virtualBottom: Int, ref: ClickableRef) {
            // Re-offset the bounds: a real (top, bottom) pair becomes the
            // (virtualTop, virtualBottom) pair in the stitched coord space.
            val virtualBounds = SerialBounds(
                left = b.left,
                top = virtualTop,
                right = b.right,
                bottom = virtualBottom,
            )
            val key = "${ref.className}|${ref.resourceId}|${virtualBounds.left},${virtualBounds.top},${virtualBounds.right},${virtualBounds.bottom}"
            if (!seen.add(key)) return
            out += ref.copy(
                bounds = virtualBounds,
                tapX = (virtualBounds.left + virtualBounds.right) / 2,
                tapY = (virtualBounds.top + virtualBounds.bottom) / 2,
            )
        }

        // Frame 0: take everything (fixed top, full live zone, fixed bottom).
        // Note frame 0's fixed-bottom band lives at the *bottom* of the
        // stitched image — re-offset accordingly.
        val frame0Refs = StateOps.collectClickables(
            root = stitched.frames[0].root,
            pkgFilter = pkgFilter,
            max = Int.MAX_VALUE,
        )
        val virtualBottomBandStart = stitched.virtualHeight - stitched.fixedBottomBandHeight
        for (ref in frame0Refs) {
            val b = ref.bounds
            val realTop = b.top
            val realBottom = b.bottom
            val virtualTop: Int
            val virtualBottom: Int
            when {
                realBottom <= stitched.liveTop -> {
                    // Fixed top band — same coords.
                    virtualTop = realTop; virtualBottom = realBottom
                }
                realTop >= stitched.liveBottom -> {
                    // Fixed bottom band — push down by stitched-vs-real height delta.
                    val shift = stitched.virtualHeight - stitched.realHeight
                    virtualTop = realTop + shift
                    virtualBottom = realBottom + shift
                }
                else -> {
                    // Live zone of frame 0 — coordinates already match the stitched image.
                    virtualTop = realTop; virtualBottom = realBottom
                }
            }
            // Skip clickables crossing the band boundary in weird ways.
            val toUiBounds = UiBounds(b.left, b.top, b.right, b.bottom)
            addClick(toUiBounds, virtualTop, virtualBottom, ref)
        }

        // Subsequent frames: only keep clickables whose center falls in the
        // newly-uncovered band, i.e. real Y in [liveBottom - dy, liveBottom).
        // For those, real Y = liveBottom - k → virtual Y = (frame i's live-top
        // virtual offset) + (liveBottom - dy) row count.
        for (i in 1 until stitched.frames.size) {
            val frame = stitched.frames[i]
            val dy = frame.scrollDelta
            val newRealTop = stitched.liveBottom - dy
            val newRealBottom = stitched.liveBottom
            // Virtual band where this frame's NEW content sits in the stitched image.
            val virtualBandTop = stitched.frameVirtualLiveTops[i] + (stitched.liveZoneHeight - dy)
            val refs = StateOps.collectClickables(
                root = frame.root,
                pkgFilter = pkgFilter,
                max = Int.MAX_VALUE,
            )
            for (ref in refs) {
                val b = ref.bounds
                val centerY = (b.top + b.bottom) / 2
                if (centerY !in newRealTop until newRealBottom) continue
                val virtualTop = virtualBandTop + (b.top - newRealTop).coerceAtLeast(0)
                val virtualBottom = min(
                    virtualBandTop + dy,
                    virtualBandTop + (b.bottom - newRealTop),
                )
                val toUiBounds = UiBounds(b.left, b.top, b.right, b.bottom)
                addClick(toUiBounds, virtualTop, virtualBottom, ref)
            }
        }

        return out
    }
}
