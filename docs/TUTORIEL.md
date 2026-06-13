# UIAutomator Desktop — Complete Tutorial

> Desktop application (Compose Multiplatform / JVM) for inspecting and
> exploring Android applications over ADB: screenshot + UI tree capture,
> guided manual exploration, automatic exploration with custom rules,
> graph visualisation, session management.

---

## Table of contents

1. [First launch and setup](#1-first-launch-and-setup)
2. [Simple capture (main screen)](#2-simple-capture-main-screen)
3. [Manual exploration mode](#3-manual-exploration-mode)
4. [Automatic exploration mode](#4-automatic-exploration-mode)
5. [Custom rules](#5-custom-rules)
6. [The exploration graph](#6-the-exploration-graph)
7. [Session management](#7-session-management)
8. [Settings](#8-settings)
9. [Cross-cutting tips and shortcuts](#9-cross-cutting-tips-and-shortcuts)

---

## 1. First launch and setup

Start the application:

```shell
# Windows
.\gradlew.bat :composeApp:run
# macOS / Linux
./gradlew :composeApp:run
```

On first launch the application attempts an **ADB auto-detection**: it looks
for the executable in `ANDROID_HOME` / `ANDROID_SDK_ROOT`, in the standard SDK
locations (`%LOCALAPPDATA%\Android\Sdk` on Windows, `~/Library/Android/sdk` on
macOS, `~/Android/Sdk` on Linux…), then on the `PATH`. If it fails, go to
**Settings** and fill in the path manually (see [§8](#8-settings)).

Then plug in an Android device (or start an emulator) with **USB debugging
enabled**, click **Refresh devices** in the toolbar and pick your device in
the dropdown list. The chosen device is remembered across launches.

![First launch — ADB auto-detection and device selection](images/premier-lancement.png)

---

## 2. Simple capture (main screen)

This is the equivalent of the stock `uiautomatorviewer`: a photo of the
device screen and the matching accessibility tree, side by side and kept in
sync.

![Main screen — capture + XML tree](images/main-capture.png)


### 2.1 The toolbar

| Element | Role |
|---|---|
| **Capture** | Takes a PNG screenshot **and** an XML dump of the device's current screen. Disabled until ADB is configured and a device is selected. |
| **Refresh devices** | Re-scans the connected devices (`adb devices`). |
| **Device dropdown** | Selects the target device; remembered in the preferences. |
| **Capture actions ▾** | Menu: Export…, Import…, Copy image, Copy XML, Save image…, Save XML… |
| **Explorer / Manual / Sessions / Rules / Graph / Settings** | Navigation to the other screens. *Graph* only appears once a session is loaded. |

A *spinner* shows up next to the buttons during any ADB operation, and the
right-hand area displays the latest status message (capture size…) or error
(in red).

### 2.2 Importing / exporting a capture

- **Export…** creates a `capture.zip` archive containing exactly
  `screenshot.png` + `dump.xml`. This is the exchange format: you can send it
  to a colleague who doesn't have the device.
- **Import…** loads such an archive back — the screen then behaves exactly as
  after a live capture (navigable tree, selection…).
- **Copy image / Copy XML** put the capture on the clipboard (AWT image /
  plain text).
- **Save image… / Save XML…** write the raw files wherever you want.

### 2.3 The screenshot panel (left)

- **Hover**: the smallest UI element under the cursor is highlighted in
  **red** in real time, and the matching row of the XML tree is emphasised
  (its ancestors are unfolded automatically, with auto-scroll).
- **Click**: **locks the selection** ("pinning"). Hovering no longer changes
  the selection: you can move the mouse over to the tree and examine the
  element without losing it. Leaving the panel releases the lock.

### 2.4 The XML tree panel (right)

- **Expand all / Collapse all**: global tree expansion.
- **Chevron ▸ / ▾**: unfolds / folds a node.
- **Hovering a row**: highlights the element on the screenshot (red).
- **Clicking a row**: selects **and pins** the node (hovering no longer
  changes the selection), and gives keyboard focus to the tree.
- **Double-clicking a row**: **deep** expand / collapse — the whole subtree
  at once.
- **Keyboard navigation** (after a click): `↑` / `↓` walk the visible rows,
  `→` expands the current node, `←` collapses it. The selection always stays
  visible (animated auto-scroll).

### 2.5 The details panel (bottom right)

All the attributes of the selected node: class, `resource-id`, `text`,
`content-desc`, `package`, `bounds`, and the flags (`clickable`, `enabled`,
`focusable`, `scrollable`, `checkable`, `checked`, `selected`, `password`).

Each row carries a **⧉** icon: clicking it copies the value to the clipboard
— handy for grabbing a `resource-id` to paste into a rule or a test script.

![Main screen — XML tree + details](images/main-details.png)

---

## 3. Manual exploration mode

Reached via **Manual** in the toolbar. You drive the device **from the
application**, and every screen you traverse is recorded as a state of a
session (with screenshot, dump and transitions) — just like automatic
exploration, except you are the one driving.

![Manual mode](images/manual-explorer.png)

### Walkthrough

1. Fill in the target package and click **Start**: the device's current
   screen is captured and becomes the first state.
2. **Click directly on the capture**: the click is converted into device
   coordinates and a real *tap* is sent. The new screen is captured, recorded
   (or recognised if already known) and the transition traced.
3. Control buttons:
   - **◀ Back** — sends BACK to the device;
   - **⌂ Home** — relaunches the application;
   - **⟳ Refresh** — recaptures the screen without interacting;
   - **↕ Capture scroll** — see below;
   - **Finish** — closes the manual session (it then shows up in *Sessions*
     and in the *Graph*).

### The scrolled capture (stitching)

**↕ Capture scroll** scrolls the device screen and **stitches the frames into
a single tall image** (fixed bands — app bar, FAB, sticky footer — are
detected and kept in place). The information bar shows the number of stitched
frames and the virtual height.

You can **click anywhere in the stitched image**, including on an element
that was not visible on screen: the application re-scrolls the device to the
right position and taps the right spot.

### The side panel

- The list of **recorded states** (`S0`, `S1`, …) with their fingerprint and
  their number of clickable elements; the current state is marked **▶**.
- The session **logs** (selectable / copyable).

---

## 4. Automatic exploration mode

Reached via **Explorer**. The application launches the target app and walks
it on its own, "like a human": it taps every element, follows wherever it
leads, goes back, and builds the complete map of screens (states) and
transitions.

![Automatic exploration](images/auto-explorer.png)


### 4.1 Configuration

| Field | Role |
|---|---|
| **Target package** | The application to explore. The **From capture** button pre-fills it with the package of the latest capture. |
| **Max states** | Safety ceiling on the number of distinct screens (deduplication naturally stops the exploration well before that in most apps). |
| **Max depth** | Maximum *branching* depth. Linear chains (onboarding, wizards) don't consume it — only screens that offer several paths count. |
| **Actions per state** | Maximum number of elements exercised per screen. |
| **Settle delay (ms)** | Stabilisation time after each gesture, on top of the screen-idle detection. |

The **Devices** section lists the connected phones, each with a checkbox:
check **several to run the exploration on all of them in parallel**. Each
device gets its own session (the folder is suffixed with the serial number) —
ideal for comparing the same app's behaviour across phones, side by side,
through the HTML exports or the *Sessions* screen. With nothing checked, the
exploration runs on the toolbar's selected device, as before.

**Start** launches the exploration; **Stop** cleanly interrupts every running
execution (partial sessions are saved to disk and remain usable).

During the exploration, **one monitoring panel per device** is shown side by
side: device name, progress bar (`Discovered: X | Processed: Y/Z`, current
state, current action), state/transition counters, session folder, and
detailed logs with **Auto-scroll** and **Copy logs**.

### 4.2 What the explorer handles on its own

The algorithm ships with many heuristics so it stays autonomous whatever the
application:

- **System permission dialogs**: captured as states then **auto-granted**
  (the most permissive button is chosen, never "Deny"), including chains of
  several permissions and detours through the system Settings. Google Play
  Services' Bluetooth/Location enable dialogs are handled the same way.
- **Crash and ANR**: an "app has stopped" dialog is dismissed and recorded as
  a *crashed* transition (annotated with the `FATAL EXCEPTION` excerpt from
  logcat); an "isn't responding" dialog first gets "Wait" (apps often
  recover). A silent return to the launcher is attributed the same way.
- **Destructive elements** (logout, delete, purchase, call…): by default they
  are **spotted but never tapped** (a `skipped` transition visible in the
  graph), so a "Sign out" can't doom the rest of the exploration.
  Configurable policy: skip / exercise last / capture the confirmation dialog
  without confirming / tap everything.
- **Wheel pickers** (time, date): every cell collapses into a single loop
  instead of minting 24 bogus states.
- **Lists / grids**: only one representative per group of identical elements
  is exercised; a selection that stays on the same screen is recognised as
  such.
- **Input fields**: filled automatically with plausible values (email, phone,
  password, code…), then the keyboard is closed so it doesn't steal the
  following taps.
- **Off-screen content**: every screen is scrolled to harvest the elements
  invisible at rest; they are scrolled back into view before being tapped.
  Along the way the frames are **stitched into a full scrolled capture** (the
  same stitching as the manual mode's "↕ Capture scroll"), saved with the
  state and viewable in the graph's detail window.
- **Horizontal pagers / swipe-only onboardings**: a synthetic swipe advances
  page by page even without a *Next* button.
- **Long presses**: `long-clickable` elements receive a long press (context
  menus invisible to taps).
- **Waiting screens** (firmware update, download, "connecting…"): patiently
  waited out until the app moves on, within a maximum budget, and never twice
  on the same stuck screen.
- **Leaving the application** (web page, dialer, share sheet…): the external
  screen is captured as a terminal state, then the explorer climbs back into
  the app.
- **Smart deduplication**: frozen status bar (SystemUI demo mode), identity
  by root container, by foreground Activity, and by "masked digits"
  fingerprint (a changing counter or clock doesn't create a duplicate).
- **Manifest coverage**: at the end of the crawl the application compares the
  declared (exported) activities to those actually visited, shows a
  **coverage rate**, and directly launches (`am start`) the ones the UI never
  reached so they get explored too.
- **Recovery**: if the explorer gets lost (drift, unknown screen), it
  re-anchors, replays the original path, or relaunches the app; recorded
  paths that stop reproducing are pruned.

You don't have to do anything to benefit from all of this — it's the default
behaviour. When an app resists (a screen with an imposed sequence, an element
invisible in the accessibility tree), turn to the **rules** below.

---

## 5. Custom rules

Reached via **Rules**. Rules are organised **per package**, stored globally
(`~/.uiautomator-desktop/rules/`), and can be exported / imported as `.zip`
to be shared.

There are **two complementary kinds of rules**:

### 5.1 Screen rules (routines)

> "When you recognise THIS screen, run THIS sequence, and do nothing else on
> it."

Ideal for screens with an imposed sequence: a licence to accept after a
mandatory scroll, a step-by-step onboarding, Bluetooth activation…

![Rule editor](images/rule-edit.png)

**Creation** (the **Add screen rule** button on the package):

1. **Capture**: takes a reference capture of the screen in question
   (screenshot + tree), displayed in the first two columns.
2. **Define the signature** — what identifies the screen:
   - click an element on the capture **or double-click** it in the tree to
     "select" it, then use **Add resourceId / text / contentDesc** to turn it
     into a criterion;
   - **Use root id** fills the *root id* field with the detected root
     container;
   - the criteria appear as *chips* (clicking a chip removes it);
   - every criterion you set must be satisfied (logical AND).
3. **Compose the routine** — the sequence of actions: **Click**, **Type
   text**, **Scroll** (direction + amount: N items / % / pixels / to the
   end), **Wait**, **Back**, **Capture** (records the intermediate screen — a
   transient dialog, a permission — as a fully-fledged step in the graph).
   Actions can be reordered (↑ / ↓) and removed.
   *Tip: when the focus is in a selector field (marked •), clicking an
   element on the capture fills the field automatically.*
4. **Save** (at least one signature criterion + a name required).

During automatic exploration, as soon as a freshly-reached screen matches the
signature, the routine runs **instead of** the generic exploration of that
screen (strict pass-through), and every captured step appears as a `RULE`
state in the graph. A package's rules are evaluated in list order
(reorderable ▲ / ▼, individually enabled / disabled).

### 5.2 Element rules

> "If you see THIS element — anywhere — click it / avoid it / swipe it… and
> keep exploring everything else normally."

Unlike screen rules, they **do not replace** the exploration: they add (or
remove) a single targeted action.

![Element rule editor](images/rule-element.png)

On a package's screen, **Element rules** section → **Add element rule**:

- **Selector**: by `resource-id`, `text` or `content-desc` (exact or
  *contains*);
- **Behavior**:
  - **Click it** — forces a tap, *even when the element is marked
    `clickable="false"`* in the accessibility tree (a frequent case with
    Compose surfaces: the image is interactive but the flag isn't exposed);
  - **Long-press it**;
  - **Swipe on it** (horizontal swipe);
  - **Avoid it** — never touch it (a `skipped` transition in the graph).

A Click / Long-press / Swipe behavior takes priority over every heuristic
(including the destructive guard-rail: the rule expresses your explicit
choice). "Avoid" wins over everything.

---

## 6. The exploration graph

Reached via **Graph** as soon as a session is loaded (after an exploration,
or by opening a session from *Sessions*). Every discovered screen is a
**card** (screenshot thumbnail, `S0`/`S1`/… identifier, depth, action count,
package); the **arrows** are the transitions.

![Exploration graph](images/graph.png)

### 6.1 Navigating the canvas

| Gesture | Effect |
|---|---|
| Mouse wheel | Vertical pan |
| **Ctrl + wheel** | Zoom (0.2× to 3×), centred on the cursor |
| Drag on the background | Free pan |
| **− / + / 100% / Fit** buttons (bottom-right corner) | Zoom out / in, back to 100 %, fit the whole graph in the window |
| **Rearrange** | Recomputes the automatic layout (tidy left-to-right tree, minimised crossings) and forgets manual positions |

### 6.2 Selecting and moving cards

| Gesture | Effect |
|---|---|
| Click on a card | Single selection |
| **Shift + click** | Adds to the selection |
| **Ctrl + click** | Toggles (adds / removes) |
| **Shift + drag on the background** | Selection rectangle (dashed) — every touched card is added |
| Dragging a selected card | Moves **the whole group** |
| **Double-click on a card** | Opens the capture's **detail window** (see 6.5) |
| **Ctrl+Z / Ctrl+Y** | Undo / redo layout manipulations (10 levels) |

While dragging, **magnetic alignment guides** appear: the card snaps to the
edges of neighbouring cards.

Manual positions are **persisted with the session**: you get your layout back
when reopening the session.

### 6.3 The context menu (right-click)

With an active selection:

- **Alignments** (≥ 2 cards): top / horizontal axis / bottom, left / vertical
  axis / right; **Stack in a column / in a row**.
- **Distribution** (≥ 3 cards): equal horizontal / vertical spacing.
- **Reset position (auto-layout)**: the selection goes back to its computed
  place.
- **Merge N cards** (≥ 2): merges states that actually represent the same
  screen — transitions are re-routed to the kept card.
- **Delete** the card(s) (with a confirmation dialog listing the affected
  transitions).

### 6.4 Reading the arrows

| Appearance | Meaning |
|---|---|
| Grey | Normal transition (tap → another screen) |
| Loop ↺ | The action stays on the same screen (toggle, list selection, picker) |
| Double-headed ↔ (accent colour) | Round trip A↔B |
| **Red** | The action left the application (`leftApp`) or failed / crashed the app |
| Thick blue | Touches a selected card |
| **Thick orange + halo** | Transition hovered in the details panel |

### 6.5 The details panel and the detail window

Select a card: the right-hand panel shows its metadata (package, fingerprint,
depth), its capture, and the list of its **outgoing transitions** (`→ S12`,
`↺ S3`, errors in red…).

- **Hovering a transition** in the list highlights the arrow in orange on the
  canvas **and** outlines the exact area that was tapped on the capture.
- **Enlarge the capture** (or double-click the card) opens the **detail
  window**: a large capture + the state's full XML tree, with the **Show
  clickables** checkbox that highlights in blue every recorded interactive
  element. Clicking a clickable element shows its destination (`→ S7`, loop,
  error, app exit, or "not yet tested") with an **Open state** button to jump
  to the target state.
- For a scrolling screen the window opens on the **Full scrolled view**: the
  stitched image of the entire content, vertically scrollable — the graph
  card keeps the plain capture. Uncheck the box to return to the interactive
  view (hover, clickables, destinations).

### 6.6 HTML export

The **Export to HTML** button produces a **self-contained web page**
(screenshots inlined as base64) reproducing the graph with zoom, pan,
selection and details panel — viewable by anyone in a browser, without
installing the application. Ideal for sharing an app cartography.

![Exploration graph exported to HTML](images/graph-export.png)

---

## 7. Session management

Reached via **Sessions**. Every exploration (automatic or manual) creates a
dated session folder under `~/.uiautomator-desktop/sessions/`
(`session.json` + a `states/` folder with each state's PNG and XML).

![Session list](images/sessions.png)


- Each row shows the **explored package**, the **folder name**, the date, the
  state / transition counts, and a **preview of the first three screens**
  (full-height thumbnails, lazily loaded to stay light on memory).
- **Open** loads the session (the *Graph* button then appears in the
  toolbar).
- **Export** creates a portable `.zip` of the whole session; **Import** (at
  the top) loads such a zip back — and opens the session directly.
- **Delete** removes the folder (confirmation requested).
- The **sessions folder path** is shown at the top: a **right-click** on it
  copies it to the clipboard (confirmation toast), and the text is also
  selectable.
- **Refresh** re-scans the folder.

---

## 8. Settings

Reached via **Settings**.

![Settings](images/settings.png)

### ADB path

- **ADB path** field + **Save**: path to the executable
  (`…\platform-tools\adb.exe`).
- **Auto-detect**: re-runs the automatic search (environment variables,
  standard SDK locations, `PATH`).
- **Browse…**: native file picker.
- The status line confirms validity (`OK — …` / `Failed: …`).

### Language

Dropdown list: **system language** (detected automatically) or an explicit
choice among **English, Français, Español, Deutsch**. The change is immediate
and remembered.

All preferences (ADB path, last device, language, exploration configuration)
live in `~/.uiautomator-desktop/config.properties`.

---

## 9. Cross-cutting tips and shortcuts

| Where | Gesture | Effect |
|---|---|---|
| Capture / detail window | Hover | Highlights the element under the cursor (red) on both sides |
| Capture / tree | Click | **Pins** the selection (hovering no longer changes it) |
| XML tree | Double-click | Deep expand / collapse of the subtree |
| XML tree | `↑ ↓ → ←` | Keyboard navigation and expansion |
| Node details panel | Click on ⧉ | Copies the attribute (resource-id, bounds…) |
| Graph | Double-click a card | State detail window |
| Graph | Shift+drag / Shift+click / Ctrl+click | Rectangle / additive / toggle selection |
| Graph | Ctrl+wheel · wheel · background drag | Zoom · vertical pan · free pan |
| Graph | Right-click | Align / distribute / merge / delete menu |
| Graph | Ctrl+Z / Ctrl+Y | Undo / redo the layout |
| Sessions | Right-click on the path | Copies the folder path |
| Rule editor | Focus a • field then click the capture | Fills the field with the clicked element |
| Everywhere | Monospace texts (paths, fingerprints, logs) | Mouse-selectable for copying |

---

*Document written for the current version of the application; the exact
labels depend on the language chosen in Settings.*
