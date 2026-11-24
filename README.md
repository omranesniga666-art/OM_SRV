# OM_SRV — Android Survey Data Manager

**Package:** `com.omsrv.app`  
**Version:** 0.1.0 prototype  
**Languages:** Arabic/English-ready UI foundation

## Current prototype
- Android Studio project using Kotlin + Jetpack Compose.
- Import delimited text files (CSV/TXT; tab/semicolon/comma separated).
- Coordinate table: Point, Northing, Easting, Elevation, Code.
- Switch table display order between N-E-Z and E-N-Z.
- Export CSV, TXT, and DXF (DXF POINT entities; X=Easting, Y=Northing, Z=Elevation).
- Improved CSV/TXT parsing: delimiter detection, quoted fields, and header mapping.
- Merge demonstration with point-ID based replacement.
- Uses Android Storage Access Framework; no broad storage permission required.
- Sample points are shown on first launch.

## Build
1. Install Android Studio (Ladybug or newer) with Android SDK Platform 35.
2. Open this folder in Android Studio.
3. Allow Gradle sync and install missing SDK components if prompted.
4. Select the `app` run configuration and run on an Android device (Android 8.0+).
5. To create an APK: **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
   Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Important format status
This is the first runnable foundation, not yet the complete requested feature set.
- CSV/TXT: initial parser/exporter included.
- DXF: basic 2D/3D POINT entity export included.
- Drawing input supports raster images, first-page PDF rendering, and a limited ASCII DXF raster preview (LINE/CIRCLE/ARC/LWPOLYLINE/POINT). DWG is not directly supported because it is a proprietary binary format; convert DWG to DXF or PDF in a CAD application first.
- SDR/GSI/PNT: vendor/model-specific adapters are not implemented yet. Do not treat these formats as supported until validated against real sample exports from each instrument family.
- Excel XLS/XLSX, PDF, robust multi-file merge/deduplication, editable rows, project database, and full bilingual localization remain planned.
- Coordinate order currently changes table display only; export-order selection will be added in the next milestone.

## Recommended next milestone
Implement robust SDR/GSI/PNT readers and writers from anonymized sample files, then XLSX/PDF, merge rules, editing, and project persistence.

## Multi-device compatibility
The next adapter milestone targets Sokkia/Topcon SDR variants, Leica GSI variants, and vendor-specific point text exports. File extensions alone are not sufficient to infer record layouts. Supply anonymized real sample exports from each device family so the readers and writers can be implemented and tested safely.

## Drawing plotter prototype (v0.3.0)
1. Import survey coordinates into the point table.
2. Load a JPG or PNG plan using **Load**.
3. Open **Plot** and tap the drawing locations for the first three points in the table, in the same order.
4. The app solves an affine transformation and previews all points that fall inside the drawing.
5. Reset calibration to pick the three controls again.

This is a visual placement prototype only. It does not yet save a calibrated drawing, export a drawing with point labels, read multi-page PDFs, fully interpret DXF (blocks/text/layers/dimensions/styles), or calculate residual/error statistics. Validate with independent check points and do not use unverified placements for construction setting-out.

## Drawing format limitations
- PDF: renders only page 1 at a preview resolution.
- DXF: ASCII DXF preview supports common basic 2D entities only. Complex blocks, text, dimensions, hatches, layers and binary DXF are not fully interpreted.
- DWG: not read natively. Convert to DXF or PDF using AutoCAD/another CAD tool before import.
- Projected markers are visual symbols with point IDs. Check the calibration with independent known points; three-point affine placement is not a substitute for a full georeferencing QA workflow.

## Branding and developer contact (v0.5.0)
- Added supplied surveying artwork as the in-app branded header image.
- Added a launcher icon derived from the supplied artwork.
- Developer: Engineer / Omran Sayed.
- Title: Surveying and Geographic Information Systems (GIS) Consultant.
- WhatsApp-only contact: 01004156997 (tap contact line to open WhatsApp).


## Build an installable APK (v0.5.0)
This environment does not include Android SDK, Gradle, or adb, so an APK cannot be compiled or device-tested here.
A GitHub Actions workflow is included at `.github/workflows/android-apk.yml`:
1. Upload this project folder to a GitHub repository.
2. Open the repository's **Actions** tab.
3. Run **Build OM_SRV APK** (or push to `main`).
4. Download the `OM_SRV-debug-apk` artifact from the completed workflow run.
5. Transfer `app-debug.apk` to Android and install it. Android may ask permission to install apps from that file manager.

The produced artifact is a debug APK for testing, not a Play Store release. The app still requires feature validation and device testing before production use.
