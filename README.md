# Adient NFC Writer V4

An Android app for generating images and flashing them to WaveShare passive, NFC-powered e-ink displays (primary target: the 2.9" model).

## Features

- **Text editor with emoji** — compose text, render it to a 1-bit bitmap sized for the target display.
- **Image import from gallery** — pick any photo, crop/resize to the display resolution.
- **WYSIWYG graphics editor** — freehand drawing via an embedded [JSPaint](https://jspaint.app) WebView.
- **NFC flashing** — tap the phone to a WaveShare NFC e-ink tag to write the current image.
- **No special permissions** beyond NFC and Internet.

## Supported Hardware

| Display | Resolution | Status |
|---|---|---|
| WaveShare 2.9" NFC e-paper | 296 × 128 | Primary target, tested |

Other WaveShare NFC e-paper sizes (2.13", 4.2", 7.5") are covered in the V2/V3 codebase — see [`Adient_NFC_Writer_Documentation.md`](Adient_NFC_Writer_Documentation.md) for that architecture.

## Architecture

```
MainActivity
  ├─ TextEditor        → renders text+emoji to bitmap
  ├─ ImagePicker       → gallery → crop → bitmap
  └─ WysiwygEditor     → JSPaint WebView → JS bridge → bitmap
        │
        ▼
   NfcFlasher (WaveShare SDK)
        │
        ▼
   WaveShare NFC e-paper tag
```

- **UI:** Kotlin, Android Views
- **Graphics editor:** HTML + JavaScript (JSPaint) hosted in a `WebView`, with a JS ↔ Kotlin bridge for image capture and canvas reset
- **NFC:** WaveShare Android SDK (`NFC.jar` + display-specific libs in `app/libs/`)

## Build

Requirements:
- Android Studio Hedgehog or later
- Android SDK (compileSdk 33)
- WaveShare NFC SDK jars placed in `app/libs/`

```bash
./gradlew assembleDebug
# or, for a release build:
./gradlew assembleRelease
```

Install on a connected device:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Launch the app.
2. Choose a source for your image:
   - **New Text** — type, pick font size, add emoji.
   - **Pick Image** — choose from gallery, crop to fit.
   - **Draw** — open the JSPaint editor and sketch freehand.
3. Tap **Flash**.
4. Hold the phone against the NFC e-paper tag until the transfer completes and the display refreshes.

## Project Layout

```
app/                          Android module (Kotlin source, resources, JSPaint assets)
gradle/ build.gradle …        Gradle build system
Adient_NFC_Writer_Documentation.md   Detailed V2/V3 protocol & architecture notes
preview-webview.bat           Local helper to preview the JSPaint WebView in a desktop browser
nfc_trace.txt                 Captured NFC transaction log for reference
```

## License

MIT — see [LICENSE](LICENSE).
