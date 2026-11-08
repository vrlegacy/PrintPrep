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
| Layout | 1, 2, 4, 6, 8, 9, 12, 16, 20, 32 per sheet; passport 35×45 mm and stamp 25×30 mm sheets; auto / portrait / landscape; repeat one photo to fill a sheet; gap; borders; cut lines |
| Fit | Fit to frame / fill frame (crop); auto-rotate to match the frame; scale 25–200 %; margins default / minimum / none / custom |
| Per item | Tap a photo on the sheet (or in the strip): drag to move, pinch to zoom, rotate, flip, reset, copies, move earlier / later, remove |
| Paper & quality | A4 or 4×6; plain / glossy / matte / other photo; Ink saver → Best photo (stored for Phase 2) |
| Colour | Colour, grayscale, black-ink-only, B&W document; brightness and contrast |
| Pages | All / odd / even / custom range (`1-5, 8, 11-13`); copies 1–99; collate; manual two-sided (long / short edge) with the pass-1 / pass-2 order shown |
| Output | **Save as PDF** (Downloads/PrintPrep): photos keep their **original quality**. JPEGs are embedded byte-for-byte; other formats and colour-adjusted photos at full resolution, lossless. Imported PDF pages are rendered at about 400 DPI; **Save as images** (Pictures/PrintPrep, JPEG or PNG, 150 or 300 DPI, one per page); Share / Open the result |

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
- [ ] Original quality: one 12 MP photo, 1 per sheet → Save as PDF → zoom in fully in a PDF viewer: as sharp as the original in Gallery; PDF size about the same as the photo file
- [ ] Page range `2-4, 6`, odd, even, copies 3 with collate on and off: the saved PDF has the right pages in the right order
- [ ] Two-sided with 5 pages and with 6 pages: pass 1 / pass 2 / "keep sheet aside" text is correct
- [ ] Grayscale, black-only and B&W document previews look right, and match the saved file
- [ ] 40 photos at 32 per sheet → Save as PDF: no crash, done in under 20 s
- [ ] Save as images at 150 and 300 DPI, JPEG and PNG: files open in Gallery
- [ ] Rotate the phone, dark mode, back button, and kill-and-reopen the app: nothing breaks
      (the job itself isn't saved between app restarts yet; that comes in Phase 4)

Note anything that fails, with a screenshot, and send it to me.

## Known limits (by design, for later phases)

- No printing yet: the Print button is disabled until Phase 2.
- The job isn't saved when the app is closed (Phase 4).
- Web pages are captured as images, so their text isn't selectable in the PDF.
