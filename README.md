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
| Room databases (bookmarks, plans, AI, progress…)    | `Db.kt` + `MiniRoom.kt`: identical SQLite schemas, upstream migrations and DAOs |
| Cloud sync (Google Drive / Nextcloud)               | `CloudSync.kt`: same sync protocol, WebDAV only      |
| AI assistant (`net.bible.service.llm`)              | upstream code compiled as is, JavaFX UI in `Ai.kt`   |
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
- **Reading plans**, including the plans shipped with AndBible and plans from add-ons (Navigate → Reading plan)
- **Reading progress** per book and chapter, and **memorize mode** for verses (Navigate → Reading progress,
  context menu → Memorize)
- **EPUB**, **MyBible**, **MySword** and **e-Sword** documents (Library → Install from file…), including EPUB
  table of contents and full-text search
- AndBible **add-ons**: fonts, background images, reading plans and AI prompt packs (signature-checked)
- **Backup & restore** in AndBible's format, so backups move between AndBible and LinBible in both directions
  (Library → Backup & restore)
- **Cloud sync** with Nextcloud or any WebDAV server, compatible with AndBible's Nextcloud sync, so phone and desktop
  share bookmarks, StudyPads, AI settings and reading progress (Library → Cloud sync). Google Drive sync is not
  available, because it relies on Google Play services.
- **AI assistant** with AndBible's prompts and agent tools: any OpenAI-compatible provider (OpenAI, OpenRouter,
  Ollama, …), Anthropic or Gemini; explains passages, writes StudyPads and bookmarks, shows its work in a log panel
  and asks before changing anything (AI menu, or "Ask AI" in the verse context menu). API keys are stored in the
  desktop keyring through `secret-tool` when available.

Not ported: workspaces (LinBible has a single set of windows) and AndBible's Android-only features (widgets,
notifications, Bluetooth headset controls).

## Data locations

- `~/.sword` holds SWORD modules. This is the standard location, so they are shared with Xiphos, BibleTime, etc.
  Documents in other formats and add-ons go into subfolders of it (`epub`, `mybible`, `mysword`, `esword`, `ttf`,
  `background`, `prompts`).
- `~/.local/share/linbible/databases` holds the AndBible-format databases (bookmarks, reading plans, progress, AI
  settings and AI documents); `~/.local/share/linbible` also holds search indexes.
- `~/.config/linbible/state.json` holds open windows and display settings; `device-id` identifies this computer
  for cloud sync. Secrets go to `secrets.properties` (mode 600) only if no keyring is available.

Bookmarks from LinBible 0.1 (`bookmarks.json`) are imported on first start.

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

GPL-3.0-or-later, the same as AndBible and JSword. See [LICENSE](LICENSE).
