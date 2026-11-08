# PrintPrep: Phase 1 (app interface)

The complete app without a printer: share in photos, PDFs and web pages, lay them out, edit them,
choose all print settings, and **save the result as a PDF or as images**. The saved file is
exactly what will print in Phase 2 (same drawing code).

## Open and run (Mac)

1. Unzip, then in Android Studio: *File → Open → PrintPrep*. If it asks about the Gradle wrapper,
   let it use the version in `gradle/wrapper/gradle-wrapper.properties` (or accept the suggested upgrade).
2. Phone: Developer options → **Wireless debugging** → pair it with Android Studio (*Pair Devices Using Wi-Fi*).
3. Press **Run ▶**. "PrintPrep" appears on the phone.

## What's in it

| Area | Features |
|---|---|
| Getting files in | Share from any app (Gallery, WhatsApp, Files, Chrome…): one or many images, PDFs, web links. Also "Open with", and Pick photos / Pick files / Print a web page on Home. New shares are added to the current job. |
| Quick tab (default) | Colour / grayscale, photos per sheet, orientation, fit, margins (default **None**), print quality (follows colour: Standard ↔ Ink saver, changeable), copies, **sides (one side / both sides)**, and **Save as PDF** / **Save as images**. The share menu opens automatically after saving. |
| Layout | 1, 2, 4, 6, 8, 9, 12, 16, 20, 32 per sheet; passport 35×45 mm and stamp 25×30 mm sheets; auto / portrait / landscape; repeat one photo to fill a sheet; gap; borders; cut lines |
| Fit | Fit to frame / fill frame (crop); auto-rotate to match the frame; scale 25–200 %; margins default / minimum / none / custom |
| Per item | Tap a photo on the sheet (or in the strip). **Position** mode: drag to move, pinch to zoom, twist with two fingers to turn a quarter turn, **double-tap to reset** size and position. **Rotate** bar with four positions: 0°, 90°, 180°, 270°. **Crop** mode: drag corners, edges or the whole box. Copies and **Delete**. Bottom buttons: **Cancel** (red, undo everything), **Reset** (blue), **Done** (green) |
| Paper & quality | A4 or 4×6; plain / glossy / matte / other photo; Ink saver → Best photo (stored for Phase 2) |
| Colour | Colour, grayscale, black-ink-only, B&W document; brightness and contrast |
| Pages | All / odd / even / custom range (`1-5, 8, 11-13`); copies 1–99; collate; manual two-sided (long / short edge) with the pass-1 / pass-2 order shown |
| Output | **Save as PDF** (Downloads/PrintPrep): photos keep their **original quality**. JPEGs are embedded byte-for-byte; other formats and colour-adjusted photos at full resolution, lossless. Imported PDF pages are rendered at about 400 DPI; **Save as images** (Pictures/PrintPrep, one per page, 150 or 300 DPI). Format is **Auto** by default, matching what you added (JPEG photos → JPEG, PNG/screenshots/documents → PNG, WebP → WebP), or pick JPEG / PNG / WebP; Share / Open the result |

## Code map

```
model/Model.kt          settings, paper, layouts, items
engine/LayoutEngine.kt  pure maths: cells, orientation, page selection, copies, two-sided order
engine/SheetRenderer.kt draws a sheet on any Canvas (preview, PDF, image, and the printer in Phase 2)
data/ImageStore.kt      copies shared files, decodes images / PDF pages at the right size, cache
data/Exporter.kt        "mock printer": PDF and image export via MediaStore
data/WebPageCapture.kt  turns a loaded web page into page-sized images
JobViewModel.kt         app state and actions
ui/…                    Home, Print (editor), item editor, web page, save sheet
```

## Phase 1 test checklist (all must pass before Phase 2)

Tick each on the vivo T5x:

- [ ] Share **1**, **10** and **40** photos from Gallery → PrintPrep opens with all of them
- [ ] Share photos from **WhatsApp** → they open
- [ ] Share a **multi-page PDF** from Files → every page appears
- [ ] Share a **link from Chrome** → the page loads → *Add pages* → pages appear
- [ ] Every layout (1–32, passport, stamp) shows the right grid; in the saved PDF the page is **A4** and photos aren't stretched
- [ ] Fit vs fill and a manual crop look the **same** in the preview and the saved PDF
- [ ] Rotate bar: 0° / 90° / 180° / 270° each show in the preview, saved PDF and print; a two-finger twist turns a quarter turn
- [ ] Crop a photo, rotate it, then crop again: the crop stays on the same part of the photo; Cancel brings back the photo as it was before opening the editor
- [ ] Quick tab: switch to Grayscale → quality becomes Ink saver; back to Colour → Standard; saving opens the share menu
- [ ] Original quality: one 12 MP photo, 1 per sheet → Save as PDF → zoom in fully in a PDF viewer: as sharp as the original in Gallery; PDF size about the same as the photo file
- [ ] Page range `2-4, 6`, odd, even, copies 3 with collate on and off: the saved PDF has the right pages in the right order
- [ ] Two-sided with 5 pages and with 6 pages: pass 1 / pass 2 / "keep sheet aside" text is correct
- [ ] Grayscale, black-only and B&W document previews look right, and match the saved file
- [ ] 40 photos at 32 per sheet → Save as PDF: no crash, done in under 20 s
- [ ] Share WebP images (e.g. WhatsApp stickers) → they open, print and save; transparent areas come out white
- [ ] Save as images: Auto picks JPEG for camera photos, PNG for screenshots/PDF pages, WebP for WebP; 150 and 300 DPI files open in Gallery
- [ ] Rotate the phone, dark mode, back button, and kill-and-reopen the app: nothing breaks
      (the job itself isn't saved between app restarts yet; that comes in Phase 4)

Note anything that fails, with a screenshot, and send it to me.

## Known limits (by design, for later phases)

- No printing yet: the Print button is disabled until Phase 2.
- The job isn't saved when the app is closed (Phase 4).
- Web pages are captured as images, so their text isn't selectable in the PDF.


---

# Phase 2: printing to the Canon G2010 over USB

**How it works:** pages are drawn by the same code as the preview and the PDF, turned into Canon printer data by
**Gutenprint 5.3.5** (open source, GPL; driver `bjc-G2000-series`, the closest model to the G2010, which is known to work with it),
and sent over the OTG cable, one printer job per page. Gutenprint is cross-compiled for Android automatically in the GitHub build
(`native/`), so the APK from GitHub includes it. CI also runs all 82 setting combinations the app can produce through the driver.

| Setting | Driver option |
|---|---|
| Ink saver / Standard / High / Photo / Best photo | `Resolution` 300dpi / 600dpi / 600dpi_high / 600dpi_photo / 600dpi_photohigh(2) |
| Plain / Glossy / Matte / Other photo | `MediaType` Plain / PhotoPlusGloss2 / PhotopaperMatte / PhotopaperOther |
| Black ink only, B&W document | `PrintingMode=BW`, `InkType=Gray` (plain paper) |
| Margins None on photo paper | `FullBleed` (borderless) |

On plain paper the printer can't print to the very edge, so a sheet with no margins is shrunk slightly (about 3 %) into the printable area instead of being cut off.

## Phase 2 test checklist (needs the OTG cable)

- [ ] Plug in the printer → Android offers to open PrintPrep → tick **Always** → the Home card turns green ("Connected over USB · ready")
- [ ] Home → **Printer diagnostics** → USB details and Device ID show the G2010; **Print test page** prints colour bars, grey steps and text
- [ ] (Optional) Diagnostics → **Send a raw .prn file** with Canon's own Windows "print to file" output
- [ ] Print 1 photo, 1 per sheet, plain paper, Standard: matches the PDF/preview
- [ ] 12 photos, 4 per sheet on glossy A4, Photo quality: matches the saved PDF
- [ ] Ink saver + Grayscale on plain paper: visibly lighter, black ink only
- [ ] A 5-page PDF with "Print last page first" on: page 1 ends on top of the stack
- [ ] Two-sided: run the **setup test** once (2 sheets), answer the two questions; then a 6-page and a 5-page PDF come out in the right order and orientation
- [ ] Unplug the cable mid-job: a clear message, no crash; replug and print again works
- [ ] Out of paper: a clear "Out of paper" message
- [ ] 20 jobs in a row without a failure


## In-app browser (web pages and Karnataka land records)

Home → **Karnataka land records (RTC)** opens https://landrecords.karnataka.gov.in/Service2/ inside the app
(**Print a web page** opens any other address). In the browser:

- **Capture screen**: what's on screen now, as one image; **Full page**: the whole page split into printable pages
- **Long-press an image** → *Add to print* or *Save to gallery* (downloaded with the page's own session, so it works after filling the form)
- Files the site downloads (PDF or image) are added to the print job automatically
- Compact **navigation bar at the bottom** (above the print bar): back, forward, **home** (land records), a small address/search field
  (type an address, or words to search the web), refresh and **desktop site**; a thin loading line shows while pages or downloads load
- The phone's Back key goes back inside the site; ✕ at the top closes the browser
- **Print (n)** jumps to the print screen with everything you've collected

- [ ] Land records: fill the form, view the RTC, Capture screen → it appears in the print job and prints/saves correctly
- [ ] Long-press an image on the site → Add to print / Save to gallery both work


## App updates

Each GitHub build is published on Releases. The app checks
`github.com/vrlegacy/PrintPrep/releases/latest` (the repository must be **public**) and, when a newer build exists:

- shows an **Update available** banner on Home: **Update** opens the new APK in the browser, which downloads it; tap the finished
  download to install. **Not now** hides the banner until a newer build appears
- **Settings** (gear on Home): *Check for updates when the app opens* (on by default) and **Check now**
- The app deliberately does **not** download and install APKs itself: that needs Android's "install other apps" permission, which
  made Google Play Protect flag build 22 as potentially harmful
- updates only install over the existing app when every build is signed with the same key, so the `SIGNING_KEYSTORE_BASE64` /
  `SIGNING_PASSWORD` secrets must hold a real key and its password (the build checks this and falls back to a temporary key otherwise)

- [ ] With a newer release published: the Home banner appears; Update opens the download; installing it updates the app
- [ ] Not now hides the banner until a newer build; turning off "Check for updates" stops the banner
- [ ] Installing the APK shows no Play Protect "harmful" warning
