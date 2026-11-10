# Regimen: Form-Factor Observations

Date: 2026-11-09 · Build: `2d089d9` (debug) · Sample data loaded · **Observations only; nothing is implemented yet.**

Reference for milestone M2. The tasks from these notes are tracked in `../BACKLOG.md` (T-011, T-036, T-066–077, T-086);
open questions moved to `../DECISIONS.md`. `AGENTS.md` has the current UI conventions.

## Devices checked

| Device | Window | Size class | Status |
|--------|--------|-----------|--------|
| Pixel 10 Pro (phone, real device) | about 412 × 915 dp | Compact × Medium | The app's design target. Checked in earlier sessions, no layout issues. |
| Pixel Tablet emulator (`Pixel_Tablet_API_35`), landscape | 1280 × 800 dp (2560 × 1600 px, 320 dpi) | Expanded × Medium | **Assessed below** |
| Pixel Tablet emulator, portrait | 800 × 1280 dp | Medium × Expanded | **Assessed below** (Today and Providers only) |
| Pixel 10 Pro Fold emulator (`Pixel_10_Pro_Fold`), folded, portrait | 443 × 970 dp (1080 × 2364 px, 390 dpi) | Compact × Expanded | **Assessed below** |
| Pixel 10 Pro Fold emulator, folded, landscape | 970 × 443 dp | Expanded × Compact | **Assessed below** (Today, Medications, dialogs) |
| Pixel 10 Pro Fold emulator, unfolded (inner screen) | 852 × 883 dp (2076 × 2152 px), near-square | Expanded × Medium | **Assessed below** |
| ASUS Googlebook 14 (real device, Android 37, desktop windowing) | screen 2880 × 1800 px at 260 dpi; freeform window, resized from 444 to 1,723 dp wide | Compact / Medium / Expanded | **Assessed below** (first on the released `0.4.1-alpha1`, then on the current build) |

Theme during the tablet pass was the emulator default (light, dynamic color). Measurements are in dp.

---

## Tablet: what works

- **Navigation adapts on its own.** `NavigationSuiteScaffold` shows a navigation rail in both orientations, and the
  Today tab badge (pending count) shows on the rail. The full-screen editors and Settings hide the rail, as intended.
- **The extra-dose picker sizes to its content** at tablet sizes. (The "Other time" dialog wasn't opened on the tablet;
  on a short window it breaks, see the foldable section.)
- **Portrait is good.** At 800 dp wide the cards fill the window without looking stretched.
- **No crashes** across the screens below; the crash log stayed empty.
- **Text and touch targets are fine.** Nothing is too small. Provider and indication rows wrap cleanly in portrait.

## Tablet: what needs work

### 1. Landscape is one very wide column (the main issue)

Every screen lays out a single column that runs the full window width minus the rail, about 1,150 dp:

| Screen | What it looks like at 1280 dp |
|--------|-------------------------------|
| **Today** | Window cards are about 1,150 dp wide. "Take now" and "Taken on time" are about 540 dp each. Only one or two windows fit on screen, so the day needs scrolling. |
| **Medications, Providers, Indications** | Row text sits at the far left of a 1,150 dp row; the right half or more is empty. Three or four rows fill the screen and the rest is blank. |
| **Medication page** | The hero card is about 1,150 dp wide. "Log Morning dose" is about 1,100 dp wide, with "Other time" and "Extra dose" as small text buttons under it. About 20 label/value rows stack in one column, so the page needs a lot of scrolling while half the width is unused. |
| **Medication editor** | Single-line fields run about 1,250 dp across (Brand, Generic name, NDC, Manufacturer, Prescriber). Strength/Form and Default dose/Unit are already two-up, which reads well. The Pacing and Rx sections are the same width. |
| **Settings** | The 4- and 5-way segmented rows (snooze, repeat) stretch to about 1,200 dp, so each segment is 240-300 dp wide. Switch rows have the label at the far left and the switch at the far right, far apart. |

It is readable and nothing breaks, but it looks like a phone layout stretched, and long lines are hard to scan.

### 2. No list and detail side by side

Tapping a medication, provider or indication replaces the whole screen. On a tablet the list should stay visible on the
left with the detail on the right. Today every item is a push to a new full-screen `Route`.

### 3. Editors hide the rail but don't use the space

Full-screen editors (`Route.isFullScreenTask`) hide the rail, so they have the whole 1280 dp, and then use one column.
Two columns, or a centered max-width form, would suit them better.

### 4. Floating button overlaps content at rest (small)

The extended "Log extra dose" button sits over the last as-needed card's buttons until you scroll (list bottom padding
is 96 dp, so it does clear when scrolled). In portrait it covers the new "Other time" button. This also happens on
phones; it's more noticeable on the tablet because the cards are so wide.

### 5. Minor / cosmetic

- The collapsing top app bar on the detail pages leaves the rail-width strip on the left unfilled when collapsed.
- On Today the date arrows and Settings gear are far from the title in landscape (about 1,200 dp apart).

## Tablet: recommendations (in order of payoff)

**Bug to fix first** (found on the foldable cover in landscape, affects any short window): the "Other time" dialog
clips its fields because its content doesn't scroll. See *Foldable: what needs work* #1.

1. **Cap content width, centered**, about 600-840 dp, on lists, the medication page, editors and Settings. Do it in the
   shared scaffolding (`ListContentPadding` and `EditorScaffold` in `ui/components/Components.kt`, plus each screen's
   `LazyColumn`). Smallest change; fixes most of the stretch.
2. **List and detail panes** for Medications, Providers and Indications using Navigation 3 scenes
   (`ListDetailSceneStrategy`). Routes and the back stack stay; the scene shows two entries side by side when the window
   is wide and collapses to one when it's narrow.
3. **Two-column Today** in landscape: a grid of dose windows, so a whole day shows without scrolling.
4. **Two-column editors and medication page** using the Compose Grid or FlexBox layouts.
5. **Foldable and resize checks** (below) before finishing the list/detail work, since the panes must collapse correctly.

Keep: phone layout unchanged (one column below roughly 600 dp), all existing routes, Snackbar + Undo behavior, and
`formatNum` / `UiFormat` for display.

---

## Foldable (Pixel 10 Pro Fold emulator)

**How it was tested:** fold state set with `adb shell cmd device_state state 0|1|2` (CLOSED, HALF_OPENED, OPENED;
`state reset` undoes it). There are two physical displays, so screenshots need `screencap -d <display id>`: cover
`4619827551948147201`, inner `4619827259835644672`. Debug build `2d089d9`.

### What works

- **Folded, portrait:** the phone layout with the bottom navigation bar. Nothing clipped on Today, Medications or the
  medication page. 443 dp is a little wider than a typical phone, so cards look comfortable.
- **Fold and unfold keep your place.** The process stayed alive (same pid) and the app kept the same screen, scroll
  position (medication page) and unsaved editor text (typed into Add medication, folded, unfolded: text still there).
  Navigation switched between the bottom bar and the rail on its own.
- **Inner screen (852 × 883 dp) is the best large-screen layout of the three.** The rail shows, Today cards are about
  730 dp wide so "Take now" / "Taken on time" are a sensible size, the medication page's schedule row fits on one line,
  and the editor's fields are about 800 dp wide. The near-square window avoids the long-line problem that the wide
  tablet landscape has.
- **No app crashes.** The crash buffer on this emulator holds only a system entry from Nov 5.

### What needs work

1. **Bug: the "Other time" dialog clips on a short window.** In cover landscape (970 × 443 dp), choosing "Other time"
   shows the Date field, then the Time field squashed to a sliver (label and value invisible), and the Note field is
   missing entirely. "Log dose" is still visible, so the dialog looks broken rather than failing. The cause is that
   the dialog's content column doesn't scroll (the Material 3 `AlertDialog` text slot doesn't scroll on its own).
   Anything that makes the window short triggers it: landscape phones, split-screen, a large font size, or the
   keyboard opening when the Note field is focused on a small phone. Fix: make the `LogTimeDialog` content scroll.
   Check the other multi-row dialogs the same way (`SiteSelectionDialog`, the window-delete dialog, the pacing
   explanations). The extra-dose picker is fine because it's a `LazyColumn`.
2. **Cover landscape is cramped.** Today's large collapsing title bar and the notice card use about 40% of the height;
   the first dose card shows as a sliver until you scroll, and the extended "Log extra dose" button sits over the right
   edge of the card and its alarm icon. Medications shows one row. It scrolls and the title collapses, so it's
   usable, but a compact top bar at compact heights would help. The same applies to any landscape phone.
3. **No hinge or posture awareness.** `HALF_OPENED` renders exactly like fully open. The app never reads the folding
   feature, so on a real device in tabletop or book posture the hinge could sit under list rows, buttons or text. The
   emulator doesn't draw the hinge, so I couldn't judge the overlap. Low priority, but worth handling once the
   list/detail panes exist (the adaptive scaffold exposes the posture).
4. **A two-pane layout on the inner screen is marginal.** At 852 dp wide a list/detail split would give a list of
   about 300 dp and a detail pane of about 450 dp. That works but is tight, and the window is near-square. The single
   column already looks good here, so the two-pane threshold should probably be wider than 840 dp, or the list pane
   should stay narrow. (See open questions.)

### Data note (not a layout issue)

This emulator already had the app installed, so its sample data predates indication kinds. The Add medication editor
lists "Acute Bacterial Sinusitis" under a **Chronic** heading: rows from before kinds existed migrate to `CHRONIC`.
The tablet had fresh data and showed it under Acute. It's the migration default working as designed, and there are no
users yet, but remember it when comparing screenshots between devices.

### Not tested

Split-screen or free-form resizing, tabletop/book posture on a device with a drawn hinge, rear display mode, dark
theme, large font sizes, and rotation of the inner screen (near-square, so little would change).

---

## Desktop (ASUS Googlebook 14)

**Builds tested.** The laptop first had the released `0.4.1-alpha1` (version code 31, release-signed, installed through
Obtainium). A debug build couldn't replace it (lower version code, different signature), so the first pass was on that
build: it still said "Conditions" and lacked the grouping chips and extra-dose button. After you uninstalled it, the
current debug build (`2d089d9`) was installed with demo data and the findings were re-checked. Where both were seen, the
result was the same.

**How it was tested:** real device over wireless adb. The app runs in a freeform window with a title bar (minimize,
maximize, close) and a taskbar. The window was resized with `adb shell am task resize <taskId> l t r b`. Dark theme.

### What works

- **Freeform windows resize live and the layout follows the width, without a restart:** 444 dp wide shows the phone
  layout with the bottom bar, 874 dp shows the rail, and 1,723 dp keeps the rail. The default 1,276 × 798 dp window
  looked good.
- **Keyboard focus is visible.** Pressing Tab moves focus and draws a ring (seen on the previous-day arrow).
- **Pointer clicks work**, the Undo snackbar and kebab menus open where expected, and every screen has an in-app back
  arrow or close button, so a laptop with no hardware back button isn't a problem.
- **Nothing crashed**, and the app kept its state while the window was resized.
- **Esc closes dialogs** (checked on the "Other time" dialog in the current build). The dialog is a sensible
  width (about 560 dp) even in a 1,700 dp window.
- **The day rolled over at midnight while the app was open** and the medication page switched to the new day by itself
  ("Log Morning dose" became "Log Morning dose early"), with no restart.

### What needs work

1. **The same very wide single column, at its worst.** Maximized (1,723 dp), the content is about 1,600 dp wide.
   "Take now" / "Taken on time" are about 760 dp each, "Log Morning dose" on the medication page is about 1,550 dp, list
   rows run the full width, and the editor's text fields are about 1,690 dp across. All confirmed on the current build. Desktop windows are routinely this wide, so the width cap, list/detail panes
   and Today grid from the tablet section matter most here.
2. **No desktop input support.** The code has no keyboard shortcuts, hover tooltips, pointer icons or right-click
   menus (searched for key handlers, tooltips, hover and context-click; no matches). On a laptop people expect: Ctrl+N
   to add, arrow keys or `[` / `]` to change day, Esc to close editors and dialogs, tooltips on the icon-only buttons
   (previous/next day, settings, edit, the kebab menus), and right-click on a dose row for the same menu as the kebab.
3. **Dark-theme contrast: the "Review" button on the notice card is nearly invisible.** The card is light lavender in the
   dark UI on this device, and its text button uses the theme's primary color (also light), so it's light on light.
   Fix: take the button's color from the card's on-container color. Other text buttons on tinted containers should be
   checked the same way.
4. **Esc does nothing on a full-screen page.** In the medication editor, Esc left the screen unchanged. On a laptop it
   would be natural for Esc to close an editor (asking first if there are unsaved changes) or go back from a detail page.
5. **Label scan buttons show on desktop.** "Scan Label" and "Label Photo" appear in the editor on the laptop. They
   weren't tried. Worth checking whether the camera and AICore paths make sense there; a file picker for a label photo
   would be the natural desktop route.
6. **Menu alignment (all form factors):** in the dose row menu only "Mark as skipped" has a leading icon, so the three
   labels don't line up.

### Test caution

Pointer input over adb is ambiguous: a tiny `input mouse swipe` meant as a hover registered as a click and logged a
dose on the laptop. It was reversed with "Mark as not taken" (the Undo snackbar had already timed out), leaving one
deleted log entry behind. Hover can't be driven safely this way, so it wasn't judged.

### Not tested

Enter in dialogs, hover states, right-click, dragging the window edges with a mouse, window snapping, multiple
windows, an external display, light theme, the label scan buttons, and the Settings page at desktop width.

---

## Open questions

- **How wide should content get, and a grid on Today:** see D-8.
- **Where list/detail starts, and for which tabs:** see D-7.
- **Rail vs bottom bar:** the rail on the inner screen (852 dp) looks right, and the bottom bar on the cover landscape
  (443 dp tall) is right too, so the current automatic switch is fine. Revisit only if a two-pane layout is added.
- **Compact top bar at compact heights:** T-072.
- **Dark theme:** the tablet and foldable passes were light only; the desktop pass was dark and found T-068. A dark pass on
  the other two is in the backlog's Device checks.
