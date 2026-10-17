# LinBible

A Linux desktop port of [AndBible: Bible Study](https://github.com/AndBible/and-bible), packaged as an AppImage.

## How the port works

AndBible is an Android app, but its two core parts are platform independent:

- **BibleView**, the Vue.js/TypeScript frontend that renders Bible text, notes, bookmarks, StudyPads and the
  Strong's/morphology popups (`app/bibleview-js`), and
- **JSword** (AndBible's fork), the pure-Java SWORD library that reads modules, installs them and searches.

LinBible reuses both unchanged and replaces the Android layer (~95k lines of Kotlin/Android UI) with a Kotlin + JavaFX
desktop shell:

| AndBible (Android)                                  | LinBible (desktop)                                   |
|-----------------------------------------------------|------------------------------------------------------|
| Android `WebView` + `WebViewAssetLoader`            | JavaFX `WebView` + loopback HTTP server (`AssetServer.kt`) |
| `BibleJavascriptInterface` (`window.android`)       | `JsBridge.kt` (same methods, same call signatures)   |
| `BibleView.kt` document loading / infinite scroll   | `BibleWindow.kt`                                     |
| `ClientPageObjects.kt` (JSON documents)             | `Documents.kt`                                       |
| `SwordContentFacade` (OSIS + BVA anchors)           | `Content.kt` (anchor logic ported verbatim)          |
| Room bookmark database                              | `Bookmarks.kt` (JSON file, same data model)          |
| `LinkControl` / `UriAnalyzer`                       | `Links.kt`                                           |
| Activities (download, search, labels, settings)     | JavaFX dialogs                                       |

The only change to the upstream frontend is applied at build time by `scripts/build-frontend.sh`:
`window.location.assign(<custom url>)` (which Android intercepts with `shouldOverrideUrlLoading`) becomes
`window.android.openLink(...)`.

## Features

- Bibles, commentaries, dictionaries and general books (SWORD modules)
- Split windows (side by side or stacked) that follow each other's verse, plus a separate links window
- Infinite scrolling through chapters, commentary blocks and general-book sections
- Strong's numbers and morphology with dictionary popups and "find all occurrences"
- Footnotes, cross references, red letters, section titles and the other AndBible display options; night mode
- Bookmarks with labels, notes, highlight styles and **StudyPads**; "My Notes" per chapter
- Compare translations
- Full-text search (builds the JSword/Lucene index on first use)
- Downloading documents from the same repositories as AndBible (AndBible, CrossWire, eBible, Lockman,
  Wycliffe, IBT, STEP Bible, Crosswire Beta)
- Copy verses with reference; text-to-speech via `spd-say`/`espeak-ng` if installed

Not ported (yet): AI features, reading plans, memorize mode, reading progress, EPUB / MyBible / MySword
documents, cloud sync, and AndBible add-on fonts and styles.

## Data locations

- `~/.sword` holds SWORD modules. This is the standard location, so they are shared with Xiphos, BibleTime, etc.
- `~/.local/share/linbible` holds bookmarks (`bookmarks.json`) and search indexes.
- `~/.config/linbible/state.json` holds open windows and display settings.

## HiDPI

JavaFX on Linux only picks up an integer `GDK_SCALE`, so LinBible works out the desktop's scale itself at startup.
It checks `GDK_SCALE`/`GDK_DPI_SCALE` first, then `Xft.dpi` (set by KDE Plasma and GNOME when scaling, including
fractional scaling under XWayland), then GNOME's `scaling-factor`, then KDE's `kwinrc`. To override the result, use
**View → Interface scale…** (takes effect after restart) or set `LINBIBLE_SCALE=1.5` for a single run.

## Building

Requirements: `git`, `curl`, `rsync`, Node.js 24 / npm. A JDK (Liberica 21 "full", which includes JavaFX) and
`appimagetool` are downloaded into `tools/` automatically.

```sh
scripts/build-appimage.sh          # → LinBible-<version>-x86_64.AppImage
```

On first use, the script clones And Bible at the pinned commit into `upstream/`, together with its JSword
submodule.

For development:

```sh
scripts/build-frontend.sh
cd desktop
JAVA_HOME=../tools/jdk-21.0.12.1-full ./gradlew run
```

Setting `LINBIBLE_DEBUG=1` forwards all BibleView console output to the terminal. `LINBIBLE_JAVA_OPTS` passes
extra JVM options to the AppImage launcher.

## License

GPL-3.0-or-later, the same as AndBible and JSword.
