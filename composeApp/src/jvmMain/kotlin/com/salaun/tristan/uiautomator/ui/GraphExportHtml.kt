package com.salaun.tristan.uiautomator.ui

import androidx.compose.ui.geometry.Offset
import com.salaun.tristan.uiautomator.explorer.ExplorationSession
import com.salaun.tristan.uiautomator.explorer.SessionStore
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * Exports an exploration session as a single-file HTML viewer.
 *
 * The output is fully self-contained: the screenshots are inlined as base64
 * data-URIs and all CSS/JS is embedded directly in the page, so the file can
 * be opened from anywhere (USB stick, e-mail attachment, no network) without
 * pulling external dependencies.
 *
 * Capabilities of the produced page:
 *   - View-only — the graph cannot be mutated.
 *   - Zoom (mouse wheel + toolbar buttons) and pan (drag empty area).
 *   - Click a card to select it; the side panel shows the full screenshot,
 *     fingerprint, package, depth and outgoing transitions.
 *   - Click empty space to deselect.
 *
 * Layout is computed Kotlin-side using the same algorithm as the desktop
 * GraphCanvas — including the user's manual position overrides loaded from
 * `layout.json` — so the exported page matches what the user just saw.
 */
object GraphExportHtml {

    // Same constants as GraphScreen so the exported page renders cards at the
    // exact size and spacing the user is used to.
    private const val CARD_W = 220
    private const val CARD_H = 170
    private const val COL_STEP = 320
    private const val ROW_STEP = 230
    private const val MARGIN = 40

    /** Writes the HTML viewer of [session] to [outFile]. */
    fun exportTo(session: ExplorationSession, store: SessionStore, outFile: File) {
        val baseAuto = autoPositions(session, COL_STEP, ROW_STEP, MARGIN)
        val overrides = store.loadLayout()?.positions.orEmpty()
        val positions: Map<String, Offset> = session.states.associate { s ->
            val o = overrides[s.id]
            val pos = if (o != null) Offset(o.x, o.y) else (baseAuto[s.id] ?: Offset.Zero)
            s.id to pos
        }
        val html = buildHtml(session, positions, store)
        outFile.writeText(html, Charsets.UTF_8)
    }

    private fun buildHtml(
        session: ExplorationSession,
        positions: Map<String, Offset>,
        store: SessionStore,
    ): String {
        val data = buildJsonObject {
            put("targetPackage", session.targetPackage)
            put("startedAt", session.startedAt)
            put("cardW", CARD_W)
            put("cardH", CARD_H)
            putJsonArray("states") {
                for (s in session.states) {
                    addJsonObject {
                        put("id", s.id)
                        put("packageName", s.packageName)
                        put("fingerprint", s.fingerprint)
                        put("depth", s.depth)
                        // Inline the PNG as base64 so the page works without
                        // external assets. Decoding happens at render time in
                        // the browser via <img src="data:image/png;base64,…">.
                        put("image", encodeImage(store.readScreenshot(s.screenshotPath)))
                        val pos = positions[s.id] ?: Offset.Zero
                        put("x", pos.x)
                        put("y", pos.y)
                        putJsonArray("clickables") {
                            for (c in s.clickables) {
                                addJsonObject {
                                    put("label", c.label)
                                    put("resourceId", c.resourceId)
                                    put("className", c.className)
                                    put("text", c.text)
                                    put("contentDesc", c.contentDesc)
                                    put("tapX", c.tapX)
                                    put("tapY", c.tapY)
                                }
                            }
                        }
                    }
                }
            }
            putJsonArray("transitions") {
                for (t in session.transitions) {
                    addJsonObject {
                        put("from", t.from)
                        if (t.to != null) put("to", t.to) else put("to", JsonNull)
                        put("loop", t.loop)
                        put("leftApp", t.leftApp)
                        if (t.errorMessage != null) put("errorMessage", t.errorMessage)
                        else put("errorMessage", JsonNull)
                        putJsonObject("action") {
                            put("label", t.action.label)
                            put("resourceId", t.action.resourceId)
                            put("className", t.action.className)
                            put("text", t.action.text)
                            put("contentDesc", t.action.contentDesc)
                        }
                    }
                }
            }
        }
        val dataJson = data.toString()

        // The raw JSON contains base64 image strings which include `</script>`
        // sequences only by extreme coincidence — but we still escape `</` to
        // `<\/` defensively so a hostile/unexpected substring can't close the
        // script tag and execute arbitrary HTML in the page.
        val safeJson = dataJson.replace("</", "<\\/")

        val title = "Graph · ${session.targetPackage.ifBlank { "?" }}"
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(session.startedAt))

        return PAGE_TEMPLATE
            .replace("@@TITLE@@", htmlEscape(title))
            .replace("@@TIMESTAMP@@", htmlEscape(timestamp))
            .replace("@@DATA@@", safeJson)
    }

    private fun encodeImage(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return ""
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    private fun htmlEscape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    // The page is a string template rather than a String.format call because
    // the body contains lots of `%` characters (CSS) and `$` would clash with
    // Kotlin string interpolation. Keeping it as raw text with @@MARKERS@@
    // sidesteps both classes of headache and stays grep-friendly.
    private val PAGE_TEMPLATE: String = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>@@TITLE@@</title>
<style>
:root {
  --bg: #18181c;
  --surface: #25252a;
  --card-bg: #2b2b30;
  --card-border: #424248;
  --card-selected: #5eb4ff;
  --text: #e1e1e1;
  --muted: #8e8e95;
  --edge: #6e6e75;
  --edge-error: #e35150;
  --edge-bidir: #c084fc;
  --edge-selected: #5eb4ff;
}
* { box-sizing: border-box; margin: 0; padding: 0; }
html, body { height: 100%; }
body { font-family: system-ui, -apple-system, "Segoe UI", sans-serif; color: var(--text); background: var(--bg); display: flex; flex-direction: column; overflow: hidden; }
#toolbar { display: flex; align-items: center; gap: 12px; padding: 8px 14px; background: var(--surface); border-bottom: 1px solid var(--card-border); font-size: 13px; flex-wrap: wrap; flex-shrink: 0; }
#toolbar button { background: transparent; border: 1px solid var(--card-border); color: var(--text); padding: 4px 10px; border-radius: 4px; cursor: pointer; font-size: 12px; font-family: inherit; }
#toolbar button:hover { background: rgba(255,255,255,0.06); }
#toolbar .meta { color: var(--muted); font-size: 12px; }
#toolbar #zoom-label { font-variant-numeric: tabular-nums; min-width: 48px; text-align: center; color: var(--muted); }
#main { flex: 1; display: flex; min-height: 0; }
#viewport { flex: 1; overflow: hidden; position: relative; cursor: grab; background: var(--bg); user-select: none; }
#viewport.panning { cursor: grabbing; }
#world { position: absolute; transform-origin: 0 0; left: 0; top: 0; }
.card { position: absolute; width: 220px; height: 170px; background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 8px; padding: 6px; cursor: pointer; display: flex; flex-direction: column; gap: 4px; }
.card.selected { border: 2px solid var(--card-selected); padding: 5px; }
.card-header { display: flex; align-items: center; gap: 6px; }
.card-header .id { font-weight: 600; font-size: 12px; }
.card-header .meta { font-size: 11px; color: var(--muted); }
.card-thumb { flex: 1; background: var(--bg); border-radius: 4px; overflow: hidden; display: flex; align-items: center; justify-content: center; }
.card-thumb img { max-width: 100%; max-height: 100%; object-fit: contain; pointer-events: none; }
.card-pkg { font-size: 11px; color: var(--muted); font-family: ui-monospace, "Cascadia Code", Menlo, Consolas, monospace; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
#edges { position: absolute; left: 0; top: 0; pointer-events: none; }
#edges path.line { fill: none; stroke-width: 1.5; }
#edges path.line.bidir { stroke: var(--edge-bidir); stroke-width: 2.5; }
#edges path.line.error { stroke: var(--edge-error); }
#edges path.line.loop { stroke: var(--muted); }
#edges path.line.def { stroke: var(--edge); opacity: 0.7; }
#edges path.line.selected { stroke: var(--edge-selected); stroke-width: 2.5; opacity: 1; }
#edges path.arrow { stroke: none; }
#details { width: 360px; background: var(--surface); border-left: 1px solid var(--card-border); padding: 16px; overflow-y: auto; flex-shrink: 0; }
#details h2 { font-size: 14px; margin-bottom: 6px; }
#details .row { font-size: 12px; color: var(--muted); margin-top: 6px; word-break: break-all; }
#details .row code { color: var(--text); font-family: ui-monospace, monospace; font-size: 11px; }
#details .full-img { margin: 12px 0; max-width: 100%; max-height: 320px; object-fit: contain; background: var(--bg); border-radius: 4px; display: block; cursor: zoom-in; }
#details .full-img.expanded { max-height: none; cursor: zoom-out; }
#details .section { margin-top: 18px; font-size: 13px; font-weight: 600; }
#details .clickable, #details .transition { font-size: 11px; padding: 5px 0; border-bottom: 1px solid rgba(255,255,255,0.04); font-family: ui-monospace, monospace; line-height: 1.4; }
#details .transition .target { color: var(--muted); display: inline-block; min-width: 90px; }
#details .transition .target.error, #details .transition .target.leftapp { color: var(--edge-error); }
#details .empty { text-align: center; color: var(--muted); margin-top: 32px; font-size: 13px; }
.scrollbar-hint { font-size: 10px; color: var(--muted); }
</style>
</head>
<body>
<div id="toolbar">
  <strong id="title"></strong>
  <span class="meta" id="counts"></span>
  <span class="meta">@@TIMESTAMP@@</span>
  <span style="flex: 1"></span>
  <span class="meta">Ctrl+wheel zoom · drag pan</span>
  <button onclick="zoomBy(0.8)" title="Zoom out">−</button>
  <span id="zoom-label">100%</span>
  <button onclick="zoomBy(1.25)" title="Zoom in">+</button>
  <button onclick="setZoom(1)" title="Reset zoom">100%</button>
  <button onclick="fit()" title="Fit graph in viewport">Fit</button>
</div>
<div id="main">
  <div id="viewport">
    <div id="world">
      <svg id="edges" xmlns="http://www.w3.org/2000/svg"></svg>
      <div id="cards"></div>
    </div>
  </div>
  <div id="details"><div class="empty">Click a state to see its details.</div></div>
</div>
<script>
const DATA = @@DATA@@;
const CARD_W = DATA.cardW;
const CARD_H = DATA.cardH;
const ARROW_LEN = 8;
const ARROW_HALF = 5;
const MIN_SCALE = 0.1;
const MAX_SCALE = 5;

let scale = 1, panX = 0, panY = 0;
let selectedId = null;

const ${'$'} = id => document.getElementById(id);

function init() {
  ${'$'}('title').textContent = 'Package: ' + (DATA.targetPackage || '?');
  ${'$'}('counts').textContent = DATA.states.length + ' states · ' + DATA.transitions.length + ' transitions';

  buildCards();
  buildEdges();
  applyTransform();
  setupInteractions();
  // Centre the viewport on the graph at first paint so users land on the
  // root cards instead of an empty corner if the layout starts at large
  // coordinates (shouldn't happen with the auto-layout, but is harmless).
  if (DATA.states.length > 0) fit();
}

function buildCards() {
  const cards = ${'$'}('cards');
  for (const s of DATA.states) {
    const c = document.createElement('div');
    c.className = 'card';
    c.dataset.id = s.id;
    c.style.left = s.x + 'px';
    c.style.top = s.y + 'px';
    const imgPart = s.image ? '<img src="' + s.image + '" alt="">' : '<span style="color:var(--muted)">—</span>';
    c.innerHTML =
      '<div class="card-header">' +
      '<span class="id">' + esc(s.id) + '</span>' +
      '<span class="meta">d=' + s.depth + '</span>' +
      '<span class="meta">· ' + s.clickables.length + ' actions</span>' +
      '</div>' +
      '<div class="card-thumb">' + imgPart + '</div>' +
      '<div class="card-pkg">' + esc(s.packageName || '?') + '</div>';
    c.addEventListener('click', e => { e.stopPropagation(); selectState(s.id); });
    cards.appendChild(c);
  }
}

function buildEdges() {
  const svg = ${'$'}('edges');
  // Compute world bounds so the SVG is large enough to host every edge.
  let maxX = 0, maxY = 0;
  for (const s of DATA.states) {
    if (s.x + CARD_W > maxX) maxX = s.x + CARD_W;
    if (s.y + CARD_H > maxY) maxY = s.y + CARD_H;
  }
  maxX += 100; maxY += 100;
  svg.setAttribute('width', maxX);
  svg.setAttribute('height', maxY);

  const posById = {};
  for (const s of DATA.states) posById[s.id] = { x: s.x, y: s.y };

  // Productive forward edges (excludes loops, leftApp, errors). Used to find
  // bidirectional pairs that should render as a single ↔ arrow.
  const productive = new Set();
  for (const t of DATA.transitions) {
    if (t.to && !t.loop && !t.leftApp && !t.errorMessage) {
      productive.add(t.from + ' ' + t.to);
    }
  }

  const drawnBidir = new Set();
  let html = '';
  for (const t of DATA.transitions) {
    const from = posById[t.from];
    if (!from) continue;
    const to = t.to ? posById[t.to] : null;

    const isProductive = to && !t.loop && !t.leftApp && !t.errorMessage;
    const isBidir = isProductive && productive.has(t.to + ' ' + t.from);
    if (isBidir) {
      const k = t.from < t.to ? t.from + ' ' + t.to : t.to + ' ' + t.from;
      if (drawnBidir.has(k)) continue;
      drawnBidir.add(k);
    }

    let startX, startY, endX, endY, orient;
    if (to) {
      const r = computeAttachments(from.x, from.y, to.x, to.y);
      startX = r.startX; startY = r.startY; endX = r.endX; endY = r.endY; orient = r.orient;
    } else {
      // Dangling transition (target unknown / left app / errored).
      startX = from.x + CARD_W; startY = from.y + CARD_H / 2;
      endX = startX + 40; endY = startY + 40; orient = 'h';
    }

    let cls = 'def';
    if (t.errorMessage || t.leftApp) cls = 'error';
    else if (t.loop) cls = 'loop';
    else if (isBidir) cls = 'bidir';

    const curveEnd = to ? shrinkToward(endX, endY, startX, startY, ARROW_LEN) : { x: endX, y: endY };
    const curveStart = isBidir ? shrinkToward(startX, startY, endX, endY, ARROW_LEN) : { x: startX, y: startY };

    let d;
    if (orient === 'h') {
      const dx = (curveEnd.x - curveStart.x) / 2;
      d = 'M' + curveStart.x + ',' + curveStart.y +
          ' C' + (curveStart.x + dx) + ',' + curveStart.y +
          ' ' + (curveEnd.x - dx) + ',' + curveEnd.y +
          ' ' + curveEnd.x + ',' + curveEnd.y;
    } else {
      const dy = (curveEnd.y - curveStart.y) / 2;
      d = 'M' + curveStart.x + ',' + curveStart.y +
          ' C' + curveStart.x + ',' + (curveStart.y + dy) +
          ' ' + curveEnd.x + ',' + (curveEnd.y - dy) +
          ' ' + curveEnd.x + ',' + curveEnd.y;
    }

    const fromAttr = ' data-from="' + esc(t.from) + '"';
    const toAttr = t.to ? ' data-to="' + esc(t.to) + '"' : '';
    html += '<path class="line ' + cls + '"' + fromAttr + toAttr + ' d="' + d + '"/>';
    if (to) {
      html += arrowheadSvg(endX, endY, startX, startY, cls);
      if (isBidir) html += arrowheadSvg(startX, startY, endX, endY, cls);
    }
  }
  svg.innerHTML = html;
}

function computeAttachments(srcX, srcY, tgtX, tgtY) {
  const srcCx = srcX + CARD_W / 2, srcCy = srcY + CARD_H / 2;
  const tgtCx = tgtX + CARD_W / 2, tgtCy = tgtY + CARD_H / 2;
  const dx = tgtCx - srcCx, dy = tgtCy - srcCy;
  if (Math.abs(dx) >= Math.abs(dy)) {
    return dx >= 0
      ? { startX: srcX + CARD_W, startY: srcCy, endX: tgtX,         endY: tgtCy, orient: 'h' }
      : { startX: srcX,          startY: srcCy, endX: tgtX + CARD_W, endY: tgtCy, orient: 'h' };
  } else {
    return dy >= 0
      ? { startX: srcCx, startY: srcY + CARD_H, endX: tgtCx, endY: tgtY,         orient: 'v' }
      : { startX: srcCx, startY: srcY,          endX: tgtCx, endY: tgtY + CARD_H, orient: 'v' };
  }
}

function shrinkToward(fromX, fromY, originX, originY, dist) {
  const dx = originX - fromX, dy = originY - fromY;
  const len = Math.sqrt(dx * dx + dy * dy);
  if (len <= dist) return { x: fromX, y: fromY };
  return { x: fromX + dist * dx / len, y: fromY + dist * dy / len };
}

function arrowheadSvg(tipX, tipY, awayX, awayY, cls) {
  const dx = awayX - tipX, dy = awayY - tipY;
  const len = Math.max(1e-6, Math.sqrt(dx * dx + dy * dy));
  const ux = dx / len, uy = dy / len;
  const baseX = tipX + ARROW_LEN * ux, baseY = tipY + ARROW_LEN * uy;
  const perpX = -uy * ARROW_HALF, perpY = ux * ARROW_HALF;
  const fill = colorFor(cls);
  return '<path class="arrow" fill="' + fill + '" d="M' + tipX + ',' + tipY +
    ' L' + (baseX + perpX) + ',' + (baseY + perpY) +
    ' L' + (baseX - perpX) + ',' + (baseY - perpY) + ' Z"/>';
}

function colorFor(cls) {
  // Inline colour values so the arrow triangles match the line classes.
  // Mirrors the CSS variables; kept in JS so SVG fills resolve correctly
  // (CSS vars on SVG fill work in modern browsers but vary on older ones).
  if (cls === 'error') return '#e35150';
  if (cls === 'bidir') return '#c084fc';
  if (cls === 'loop') return '#8e8e95';
  return '#6e6e75';
}

function setupInteractions() {
  const viewport = ${'$'}('viewport');

  // Pan: any mousedown that does NOT originate on a card starts a pan
  // gesture. While dragging, every mousemove translates the world, and on
  // mouseup we deselect if the pointer didn't actually move (= a click on
  // empty space).
  let dragging = false, pannedDuringDrag = false, lastX = 0, lastY = 0;
  viewport.addEventListener('mousedown', e => {
    if (e.button !== 0) return;
    if (e.target.closest && e.target.closest('.card')) return;
    dragging = true; pannedDuringDrag = false;
    lastX = e.clientX; lastY = e.clientY;
    viewport.classList.add('panning');
    e.preventDefault();
  });
  document.addEventListener('mousemove', e => {
    if (!dragging) return;
    const dx = e.clientX - lastX, dy = e.clientY - lastY;
    if (Math.abs(dx) > 2 || Math.abs(dy) > 2) pannedDuringDrag = true;
    panX += dx; panY += dy;
    lastX = e.clientX; lastY = e.clientY;
    applyTransform();
  });
  document.addEventListener('mouseup', () => {
    if (dragging && !pannedDuringDrag) selectState(null);
    dragging = false;
    viewport.classList.remove('panning');
  });

  // Zoom on Ctrl/Cmd + wheel — matches the desktop behaviour. Plain wheel
  // pans vertically (we synthesise the pan since the viewport doesn't have
  // a native scroll bar in this layout).
  viewport.addEventListener('wheel', e => {
    if (e.ctrlKey || e.metaKey) {
      e.preventDefault();
      const factor = e.deltaY < 0 ? 1.1 : 1 / 1.1;
      const rect = viewport.getBoundingClientRect();
      const mx = e.clientX - rect.left;
      const my = e.clientY - rect.top;
      const oldScale = scale;
      scale = clamp(scale * factor, MIN_SCALE, MAX_SCALE);
      // Zoom centred on the cursor: keep the world point under the cursor
      // pinned by reverse-engineering the new pan from the new scale.
      panX = mx - (mx - panX) * (scale / oldScale);
      panY = my - (my - panY) * (scale / oldScale);
      applyTransform();
    } else {
      e.preventDefault();
      panY -= e.deltaY;
      panX -= e.deltaX;
      applyTransform();
    }
  }, { passive: false });

  // Keyboard: arrows pan, +/- zoom, 0 reset, F fit.
  document.addEventListener('keydown', e => {
    const tag = (e.target && e.target.tagName) || '';
    if (tag === 'INPUT' || tag === 'TEXTAREA') return;
    const STEP = 60;
    switch (e.key) {
      case 'ArrowUp': panY += STEP; applyTransform(); break;
      case 'ArrowDown': panY -= STEP; applyTransform(); break;
      case 'ArrowLeft': panX += STEP; applyTransform(); break;
      case 'ArrowRight': panX -= STEP; applyTransform(); break;
      case '+': case '=': zoomBy(1.25); break;
      case '-': zoomBy(0.8); break;
      case '0': setZoom(1); break;
      case 'f': case 'F': fit(); break;
      case 'Escape': selectState(null); break;
    }
  });
}

function applyTransform() {
  ${'$'}('world').style.transform = 'translate(' + panX + 'px, ' + panY + 'px) scale(' + scale + ')';
  ${'$'}('zoom-label').textContent = Math.round(scale * 100) + '%';
}

function zoomBy(factor) {
  const viewport = ${'$'}('viewport');
  const rect = viewport.getBoundingClientRect();
  const cx = rect.width / 2, cy = rect.height / 2;
  const oldScale = scale;
  scale = clamp(scale * factor, MIN_SCALE, MAX_SCALE);
  panX = cx - (cx - panX) * (scale / oldScale);
  panY = cy - (cy - panY) * (scale / oldScale);
  applyTransform();
}
function setZoom(s) {
  const viewport = ${'$'}('viewport');
  const rect = viewport.getBoundingClientRect();
  const cx = rect.width / 2, cy = rect.height / 2;
  const oldScale = scale;
  scale = clamp(s, MIN_SCALE, MAX_SCALE);
  panX = cx - (cx - panX) * (scale / oldScale);
  panY = cy - (cy - panY) * (scale / oldScale);
  applyTransform();
}
function fit() {
  if (DATA.states.length === 0) return;
  const viewport = ${'$'}('viewport');
  const rect = viewport.getBoundingClientRect();
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const s of DATA.states) {
    if (s.x < minX) minX = s.x;
    if (s.y < minY) minY = s.y;
    if (s.x + CARD_W > maxX) maxX = s.x + CARD_W;
    if (s.y + CARD_H > maxY) maxY = s.y + CARD_H;
  }
  const w = maxX - minX, h = maxY - minY;
  const margin = 40;
  const sx = (rect.width - 2 * margin) / w;
  const sy = (rect.height - 2 * margin) / h;
  scale = clamp(Math.min(sx, sy), MIN_SCALE, MAX_SCALE);
  panX = (rect.width - w * scale) / 2 - minX * scale;
  panY = (rect.height - h * scale) / 2 - minY * scale;
  applyTransform();
}

function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

function selectState(id) {
  selectedId = id;
  for (const c of document.querySelectorAll('.card')) {
    if (id && c.dataset.id === id) c.classList.add('selected');
    else c.classList.remove('selected');
  }
  for (const p of ${'$'}('edges').querySelectorAll('path.line')) {
    if (id && (p.dataset.from === id || p.dataset.to === id)) p.classList.add('selected');
    else p.classList.remove('selected');
  }
  renderDetails(id);
}

function renderDetails(id) {
  const div = ${'$'}('details');
  if (!id) { div.innerHTML = '<div class="empty">Click a state to see its details.</div>'; return; }
  const s = DATA.states.find(x => x.id === id);
  if (!s) { div.innerHTML = '<div class="empty">State not found.</div>'; return; }

  let html = '<h2>' + esc(s.id) + '</h2>';
  html += '<div class="row">Package: <code>' + esc(s.packageName || '?') + '</code></div>';
  html += '<div class="row">Fingerprint: <code>' + esc(s.fingerprint) + '</code></div>';
  html += '<div class="row">Depth: <code>' + s.depth + '</code></div>';
  html += '<div class="row">' + s.clickables.length + ' clickable element(s)</div>';
  if (s.image) html += '<img class="full-img" src="' + s.image + '" onclick="this.classList.toggle(\'expanded\')">';

  const outgoing = DATA.transitions.filter(t => t.from === id);
  html += '<div class="section">Outgoing transitions (' + outgoing.length + ')</div>';
  if (outgoing.length === 0) html += '<div class="row">—</div>';
  else for (const t of outgoing) {
    let target, cls = '';
    if (t.errorMessage) { target = '(error)'; cls = 'error'; }
    else if (t.leftApp) { target = '(out of app)'; cls = 'leftapp'; }
    else if (t.loop) target = '↺ ' + t.to;
    else if (t.to) target = '→ ' + t.to;
    else target = '?';
    html += '<div class="transition" ' + (t.to ? 'onclick="selectState(\'' + esc(t.to).replace(/'/g, "\\'") + '\')" style="cursor:pointer"' : '') + '>' +
            '<span class="target ' + cls + '">' + esc(target) + '</span>' + esc(t.action.label) +
            (t.errorMessage ? '<div style="color:var(--edge-error);padding-left:90px">' + esc(t.errorMessage) + '</div>' : '') +
            '</div>';
  }

  html += '<div class="section">Clickables (' + s.clickables.length + ')</div>';
  if (s.clickables.length === 0) html += '<div class="row">—</div>';
  else for (const c of s.clickables) {
    html += '<div class="clickable">' + esc(c.label) + '</div>';
  }

  div.innerHTML = html;
  div.scrollTop = 0;
}

function esc(s) {
  return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c]);
}

document.addEventListener('DOMContentLoaded', init);
</script>
</body>
</html>
"""
}
