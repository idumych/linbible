/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * One reading window: a WebView running AndBible's BibleView frontend. Ported from AndBible's BibleView.kt.
 */
package org.linbible

import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label as FxLabel
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.web.WebView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import netscape.javascript.JSObject
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.BookCategory
import org.crosswire.jsword.book.sword.SwordBook
import org.crosswire.jsword.passage.Key
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.passage.VerseRange
import java.util.Locale
import kotlin.math.abs

class BibleWindow(
    val app: MainApp,
    initialPage: Page,
    var synchronized: Boolean = true,
    val isLinksWindow: Boolean = false,
) : BookmarkListener, ProgressListener {
    var page: Page = initialPage
        private set
    /** Stable id for AndBible's window API (AI tools). */
    val windowId: net.bible.android.database.IdType = net.bible.android.database.IdType()

    val webView = WebView()
    private val engine = webView.engine
    /** Strong reference: JavaFX only weakly references objects handed to JavaScript. */
    private val bridge = JsBridge(this)

    val titleLabel = FxLabel()
    private val syncBox = CheckBox("Sync")
    private val header = HBox(6.0)
    val root = BorderPane()

    private var clientReady = false
    private var labelsUploaded = false
    private var disposed = false
    private var generation = 0
    private var pending: Initial? = null
    var editing = false

    // Loaded content bounds, for infinite scrolling
    private var minChapter = -1
    private var maxChapter = -1
    private var firstVerse: Verse? = null
    private var lastVerse: Verse? = null
    private var firstKey: Key? = null
    private var lastKey: Key? = null
    private var blocks: CommentaryBlocks? = null

    private class Initial(
        val doc: JsonObject,
        val jumpToOrdinal: Int? = null,
        val jumpToAnchor: Int? = null,
        val jumpToId: String? = null,
        val highlight: IntRange? = null,
    )

    init {
        header.styleClass += "window-header"
        header.alignment = Pos.CENTER_LEFT
        header.padding = Insets(2.0, 6.0, 2.0, 8.0)
        titleLabel.styleClass += "window-title"
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        syncBox.isSelected = synchronized
        syncBox.tooltip = Tooltip("Follow the verse of other synchronized windows")
        syncBox.selectedProperty().addListener { _, _, v -> synchronized = v }
        val close = Button("✕").apply {
            styleClass += "window-close"
            tooltip = Tooltip("Close window")
            setOnAction { app.closeWindow(this@BibleWindow) }
        }
        header.children.addAll(titleLabel, spacer)
        if (!isLinksWindow) header.children += syncBox
        header.children += close
        root.top = header
        root.center = webView
        header.setOnMouseClicked { app.setActive(this) }

        webView.isContextMenuEnabled = false
        webView.addEventFilter(MouseEvent.MOUSE_PRESSED) { app.setActive(this) }
        webView.setOnContextMenuRequested { e -> showContextMenu(e.screenX, e.screenY) }

        engine.isJavaScriptEnabled = true
        engine.loadWorker.stateProperty().addListener { _, _, state ->
            if (state == Worker.State.SUCCEEDED) boot()
            if (state == Worker.State.FAILED) Log.e("BibleView failed to load: ${engine.loadWorker.exception}")
        }
        engine.setOnAlert { Log.i("JS alert: ${it.data}") }
        Bookmarks.addListener(this)
        Progress.addListener(this)
        // Layout-dependent overlays (memorization indicators) are recomputed on set_config, as on Android
        // where resizing a window re-sends the configuration.
        val resized = javafx.animation.PauseTransition(javafx.util.Duration.millis(400.0)).apply {
            setOnFinished { if (clientReady && !disposed) updateConfig() }
        }
        webView.widthProperty().addListener { _, _, _ -> resized.playFromStart() }
        loadFrontend()
        show(initialPage)
    }

    private fun loadFrontend() {
        clientReady = false
        labelsUploaded = false
        val lang = Locale.getDefault().toLanguageTag()
        val rtl = false
        val enc = { l: List<String> -> java.net.URLEncoder.encode(l.joinToString(","), Charsets.UTF_8) }
        engine.load("${AssetServer.baseUrl}/index.html?lang=$lang&fontModuleNames=${enc(Addons.fontModuleNames)}" +
            "&styleModuleNames=${enc(Addons.styleModuleNames)}&featureModuleNames=${enc(Addons.featureModuleNames)}" +
            "&rtl=$rtl&night=${app.nightMode}")
    }

    private fun boot() {
        val window = engine.executeScript("window") as JSObject
        window.setMember("android", bridge)
        engine.executeScript(
            """
            (function() {
                if (window.__linbibleBooted) return;
                window.__linbibleBooted = true;
                const a = window.android;
                const verbose = ${Log.debug};
                const fmt = (args) => args.map(x => { try { return typeof x === 'string' ? x : JSON.stringify(x) } catch (e) { return String(x) } }).join(' ');
                window.onerror = (msg, src, line, col, err) => a.console('error', msg + ' @' + src + ':' + line + (err && err.stack ? '\n' + err.stack : ''));
                window.addEventListener('unhandledrejection', e => a.console('error', 'Unhandled rejection: ' + (e.reason && (e.reason.stack || e.reason))));
                for (const k of ['log', 'info', 'warn', 'error']) {
                    const orig = console[k].bind(console);
                    console[k] = function(...args) {
                        if (verbose || k === 'warn' || k === 'error') { try { a.console(k, fmt(args)) } catch (e) {} }
                        orig(...args);
                    };
                }
                import(window.__bibleViewEntry).catch(e => a.console('error', 'Failed to load BibleView: ' + e + '\n' + e.stack));
            })();
            """.trimIndent()
        )
    }

    // ------------------------------------------------------------------ JS plumbing

    fun exec(js: String) {
        if (Platform.isFxApplicationThread()) execNow(js) else Platform.runLater { execNow(js) }
    }

    private fun execNow(js: String) {
        if (disposed || !clientReady) return
        try {
            engine.executeScript(js)
        } catch (e: Exception) {
            Log.e("JS execution failed: ${e.message}\n${js.take(300)}")
        }
    }

    fun emit(event: String, vararg args: String) =
        exec("bibleView.emit(${jsString(event)}${args.joinToString("") { ", $it" }});")

    fun response(callId: Int, value: String) = exec("bibleView.response($callId, $value);")

    fun setClientReady() {
        Log.d("Client ready: ${page.title}")
        clientReady = true
        // Called synchronously from inside Vue's mount; defer like Android's async evaluateJavascript
        // so that all event listeners are registered before the document arrives.
        Platform.runLater { replaceDocument() }
    }

    // ------------------------------------------------------------------ config

    private val docV11n get() = when (val p = page) {
        is Page.Bible -> p.book.versification
        is Page.MyNotes -> KJVA
        is Page.Memorize -> p.range.versification
        else -> null
    }

    fun configCommand(initial: Boolean): String {
        val s = Settings.state
        val active = app.activeWindow === this
        val hasIndicator = active && app.windows.size > 1
        val appSettings = buildJsonObject {
            bool("activeWindow", active)
            bool("isBottomWindow", false)
            bool("hasActiveIndicator", hasIndicator)
            bool("nightMode", app.nightMode)
            bool("errorBox", false)
            strs("favouriteLabels", Bookmarks.labels.filter { it.favourite }.map { it.id })
            strs("recentLabels", s.recentLabels.filter { Bookmarks.label(it) != null })
            put("studyPadCursors", buildJsonObject { s.studyPadCursors.forEach { (k, v) -> put(k, v) } })
            strs("autoAssignLabels", s.autoAssignLabels)
            strs("hideCompareDocuments", s.hideCompareDocuments)
            bool("limitAmbiguousModalSize", false)
            str("windowId", "w${System.identityHashCode(this)}")
            strs("disableBibleModalButtons", if (Llm.configured) emptyList() else listOf("LLM_ACTION"))
            strs("disableGenericModalButtons", if (Llm.configured) emptyList() else listOf("LLM_ACTION"))
            bool("monochromeMode", false)
            bool("einkMode", false)
            bool("colorEinkMode", false)
            bool("disableAnimations", false)
            num("fontSizeMultiplier", 1.0)
            strs("enabledExperimentalFeatures", emptyList())
            bool("disableClickToEdit", false)
            bool("llmConfigured", Llm.configured)
            str("notesContentType", "HTML")
        }
        val payload = buildJsonObject {
            put("config", s.display.toConfigJson())
            put("appSettings", appSettings)
            bool("initial", initial)
        }
        return "bibleView.emit('set_config', $payload);"
    }

    fun updateConfig() {
        exec(configCommand(false))
        webView.pageFill = if (app.nightMode) javafx.scene.paint.Color.web("#121212") else javafx.scene.paint.Color.WHITE
    }

    fun updateActive() {
        header.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("active"), app.activeWindow === this)
        val active = app.activeWindow === this
        emit("set_active", buildJsonObject { bool("isActive", active); bool("hasActiveIndicator", active && app.windows.size > 1) }.toString())
    }

    // ------------------------------------------------------------------ page loading

    /** Shows [newPage]. Document building happens on a worker thread. */
    /** Frontend state of the current document (memorize mode progress), see JsBridge.saveState. */
    var jsState: String? = null

    fun show(newPage: Page) {
        if (newPage != page) jsState = null
        page = newPage
        titleLabel.text = newPage.title
        val gen = ++generation
        app.windowPageChanged(this)
        app.executor.submit {
            val initial = try {
                buildInitial(newPage)
            } catch (e: OsisError) {
                Initial(Documents.error(e.message ?: "Error"))
            } catch (e: Throwable) {
                Log.e("Could not load ${newPage.title}", e)
                Initial(Documents.error("Could not load ${newPage.title}: ${e.message}", "ERROR"))
            }
            Platform.runLater {
                if (gen != generation || disposed) return@runLater
                pending = initial
                if (clientReady) replaceDocument()
            }
        }
    }

    fun reload() = show(page)

    private fun buildInitial(p: Page): Initial = when (p) {
        is Page.Bible -> {
            val v = p.verse
            val chapterRange = wholeChapter(v.versification, v.book, v.chapter)
            minChapter = v.chapter; maxChapter = if (v.chapter == 0) 1 else v.chapter
            val hl = p.highlight?.toV11n(v.versification)
            Initial(
                Documents.bible(p.book, chapterRange, hl),
                jumpToOrdinal = if (v.verse <= 1 && hl == null) null else v.ordinal,
                highlight = hl?.let { it.start.ordinal..it.end.ordinal },
            )
        }
        is Page.Commentary -> {
            val b = CommentaryBlocks(p.book).also { blocks = it }
            val block = b.resolveNearest(p.verse)
                ?: throw OsisError("${p.book.initials} has no commentary near ${p.verse.name}")
            firstVerse = block.start; lastVerse = block.end
            Initial(Documents.osis(p.book, block.start, block = block))
        }
        is Page.Keyed -> {
            firstKey = p.key; lastKey = p.key
            Initial(Documents.osis(p.book, p.key, p.highlight), jumpToAnchor = p.highlight?.first, highlight = p.highlight,
                jumpToId = p.htmlId?.takeIf { p.highlight == null })
        }
        is Page.Multi -> Initial(Documents.multi(p.items, p.compare))
        is Page.StudyPad -> {
            val label = Bookmarks.label(p.labelId) ?: throw OsisError("StudyPad not found")
            Initial(Documents.studyPad(label), jumpToId = p.entryId?.let { "o-${abs(it.hashCode())}" })
        }
        is Page.MyNotes -> Initial(Documents.myNotes(p.range))
        is Page.Memorize -> Initial(Documents.memorize(p.book, p.range, jsState))
        is Page.Message -> Initial(Documents.error(p.text))
    }

    private fun replaceDocument() {
        val init = pending ?: return
        pending = null
        if (!labelsUploaded) {
            emit("update_labels", kotlinx.serialization.json.JsonArray(Bookmarks.labels.map { Documents.clientLabel(it) }).toString())
            labelsUploaded = true
        }
        val p = page
        val setup = buildJsonObject {
            num("jumpToOrdinal", init.jumpToOrdinal)
            num("jumpToAnchor", init.jumpToAnchor)
            str("jumpToId", init.jumpToId)
            num("topOffset", 0)
            num("bottomOffset", 0)
            num("ordinalStart", init.highlight?.first)
            num("ordinalEnd", init.highlight?.last)
            bool("highlight", init.highlight != null)
            str("bookInitials", p.book?.initials)
            str("osisRef", when (p) { is Page.Keyed -> p.key.osisRef; is Page.Bible -> p.verse.osisRef; else -> null })
        }
        exec(
            """
            bibleView.emit("clear_document");
            ${configCommand(true)}
            bibleView.emit("add_documents", ${init.doc});
            bibleView.emit("setup_content", $setup);
            bibleView.emit("set_title", ${jsString(p.title)});
            """.trimIndent()
        )
        updateActive()
        webView.pageFill = if (app.nightMode) javafx.scene.paint.Color.web("#121212") else javafx.scene.paint.Color.WHITE
    }

    // ------------------------------------------------------------------ infinite scroll

    fun requestMore(callId: Int, toEnd: Boolean) {
        val p = page
        val gen = generation
        fun respond(build: () -> JsonObject?) {
            app.executor.submit {
                val doc = try { build() } catch (e: Exception) { Log.e("requestMore", e); null }
                Platform.runLater { if (gen == generation) response(callId, doc?.toString() ?: "null") }
            }
        }
        when (p) {
            is Page.Bible -> {
                val v11n = p.book.versification
                val bibleBook = p.verse.book
                val chap = if (toEnd) maxChapter + 1 else minChapter - 1
                if (chap < 1 || chap > v11n.getLastChapter(bibleBook)) { response(callId, "null"); return }
                if (toEnd) maxChapter = chap else minChapter = chap
                respond { Documents.bible(p.book, wholeChapter(v11n, bibleBook, chap)) }
            }
            is Page.Commentary -> {
                val b = blocks ?: run { response(callId, "null"); return }
                val anchor = (if (toEnd) lastVerse else firstVerse) ?: run { response(callId, "null"); return }
                respond {
                    val block = (if (toEnd) b.nextBlock(anchor) else b.prevBlock(anchor)) ?: return@respond null
                    Platform.runLater { if (toEnd) lastVerse = block.end else firstVerse = block.start }
                    Documents.osis(p.book, block.start, block = block)
                }
            }
            is Page.Keyed -> {
                if (p.book.bookCategory != BookCategory.GENERAL_BOOK) { response(callId, "null"); return }
                val anchor = (if (toEnd) lastKey else firstKey) ?: run { response(callId, "null"); return }
                val next = KeyNavigation.step(p.book, anchor, if (toEnd) 1 else -1)
                if (next == null) { response(callId, "null"); return }
                if (toEnd) lastKey = next else firstKey = next
                respond { Documents.osis(p.book, next) }
            }
            else -> response(callId, "null")
        }
    }

    // ------------------------------------------------------------------ position tracking & sync

    fun scrolledToOrdinal(keyStr: String, ordinal: Int) {
        val p = page
        val newPage: Page = when (p) {
            is Page.Bible -> {
                val v11n = p.book.versification
                if (ordinal > v11n.maximumOrdinal()) return
                val verse = v11n.decodeOrdinal(ordinal)
                if (verse == p.verse) return
                p.copy(verse = verse, highlight = null)
            }
            is Page.Commentary -> {
                val verse = Page.parseVerse(p.book.versification, keyStr) ?: return
                if (verse == p.verse) return
                p.copy(verse = verse)
            }
            is Page.Keyed -> {
                if (keyStr == p.key.osisRef) return
                val key = runCatching { p.book.getKey(keyStr) }.getOrNull() ?: return
                p.copy(key = key, highlight = null)
            }
            else -> return
        }
        page = newPage
        titleLabel.text = newPage.title
        app.windowPageChanged(this)
        if (app.activeWindow === this) newPage.currentVerse?.let { app.verseChanged(this, it) }
    }

    /** Follows the verse of another (active) synchronized window. */
    fun syncTo(verse: Verse) {
        when (val p = page) {
            is Page.Bible -> {
                val v = verse.toV11n(p.book.versification)
                val loaded = v.book == p.verse.book && (v.chapter in minChapter..maxChapter || (v.chapter == 0 && minChapter <= 1))
                if (loaded && clientReady && pending == null) {
                    page = p.copy(verse = v, highlight = null)
                    titleLabel.text = page.title
                    emit("scroll_to_verse", jsString("o-${v.ordinal}"), "{now: false, highlight: false, ordinalStart: ${v.ordinal}, ordinalEnd: null}")
                } else {
                    show(Page.Bible(p.book, v))
                }
            }
            is Page.Commentary -> {
                val cv = verse.toV11n(p.book.versification)
                val v = if (cv.verse == 0) Verse(cv.versification, cv.book, cv.chapter, 1) else cv
                val f = firstVerse; val l = lastVerse
                if (f != null && l != null && v.ordinal in f.ordinal..l.ordinal && f.book == v.book) return
                show(Page.Commentary(p.book, v))
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ bookmarks

    override fun bookmarksChanged(bible: List<BibleBookmark>, generic: List<GenericBookmark>) {
        if (!clientReady) return
        val v11n = docV11n
        val items = bible.map { Documents.clientBibleBookmark(it, v11n, withFragment = page is Page.StudyPad) } +
            generic.map { Documents.clientGenericBookmark(it, withFragment = page is Page.StudyPad) }
        emit("add_or_update_bookmarks", kotlinx.serialization.json.JsonArray(items).toString())
    }

    override fun bookmarksDeleted(ids: List<String>) = emit("delete_bookmarks", ids.joinToString(",", "[", "]") { jsString(it) })

    override fun noteModified(id: String, lastUpdatedOn: Long, notes: String?) =
        emit("bookmark_note_modified", "{id: ${jsString(id)}, lastUpdatedOn: $lastUpdatedOn, notes: ${jsString(notes)}}")

    override fun labelsChanged(labels: List<Label>) {
        emit("update_labels", kotlinx.serialization.json.JsonArray(labels.map { Documents.clientLabel(it) }).toString())
        if (page is Page.StudyPad && Bookmarks.label((page as Page.StudyPad).labelId) == null) {
            show(Page.Message("This StudyPad has been deleted."))
        }
    }

    override fun bookmarkToLabelChanged(btl: BookmarkToLabel, generic: Boolean) {
        val p = page as? Page.StudyPad ?: return
        if (p.labelId != btl.labelId) return
        emit("add_or_update_bookmark_to_label", Documents.bookmarkToLabel(btl, generic).toString())
    }

    override fun studyPadChanged(labelId: String, newEntry: StudyPadTextEntry?) {
        val p = page as? Page.StudyPad ?: return
        if (p.labelId != labelId) return
        val change = Bookmarks.lastStudyPadChange ?: return
        val payload = buildJsonObject {
            put("studyPadTextEntry", newEntry?.let { Documents.studyPadEntry(it) } ?: kotlinx.serialization.json.JsonNull)
            arr("bookmarkToLabelsOrdered", change.btl.map { Documents.bookmarkToLabel(it, false) })
            arr("genericBookmarkToLabelsOrdered", change.gbtl.map { Documents.bookmarkToLabel(it, true) })
            arr("studyPadItemsOrdered", change.entries.map { Documents.studyPadEntry(it) })
        }
        emit("add_or_update_study_pad", payload.toString())
    }

    override fun studyPadEntryDeleted(id: String) {
        if (page is Page.StudyPad) emit("delete_study_pad_text_entry", jsString(id))
    }

    data class Selection(
        val book: Book?,
        val osisRef: String?,
        val startOrdinal: Int,
        val endOrdinal: Int,
        val startOffset: Int? = null,
        val endOffset: Int? = null,
        val bookmarks: List<String> = emptyList(),
        val text: String = "",
    ) {
        val swordBook get() = book as? SwordBook
        val isBible get() = book?.bookCategory == BookCategory.BIBLE
        val verseRange: VerseRange? get() {
            val v11n = swordBook?.versification ?: return null
            return VerseRange(v11n, v11n.decodeOrdinal(startOrdinal), v11n.decodeOrdinal(maxOf(startOrdinal, endOrdinal)))
        }
    }

    fun makeBookmark(sel: Selection, wholeVerse: Boolean, openNotes: Boolean) {
        val book = sel.book ?: return
        val labels = Settings.state.autoAssignLabels.filter { Bookmarks.label(it) != null }
        val offsetsValid = !wholeVerse && sel.startOffset != null && sel.endOffset != null
        val id = if (sel.isBible) {
            val range = sel.verseRange ?: return
            Bookmarks.addBibleBookmark(sel.swordBook, range, sel.startOffset.takeIf { offsetsValid }, sel.endOffset.takeIf { offsetsValid }, wholeVerse || !offsetsValid, labels).id
        } else {
            val key = sel.osisRef ?: return
            Bookmarks.addGenericBookmark(book, key, sel.startOrdinal..maxOf(sel.startOrdinal, sel.endOrdinal),
                sel.startOffset.takeIf { offsetsValid }, sel.endOffset.takeIf { offsetsValid }, wholeVerse || !offsetsValid, labels).id
        }
        if (labels.isEmpty() || openNotes) {
            emit("bookmark_clicked", jsString(id), "{openLabels: true, openNotes: $openNotes}")
        }
    }

    // ------------------------------------------------------------------ context menu / selection

    fun querySelection(): Any? {
        val raw = runCatching { engine.executeScript("JSON.stringify(bibleView.querySelection ? bibleView.querySelection() : null)") as? String }.getOrNull()
            ?: return null
        val el = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        if (el is kotlinx.serialization.json.JsonPrimitive) return el.contentOrNull
        if (el !is JsonObject) return null
        val o = el.jsonObject
        return Selection(
            book = Sword.book(o["bookInitials"]?.jsonPrimitive?.contentOrNull),
            osisRef = o["osisRef"]?.jsonPrimitive?.contentOrNull,
            startOrdinal = o["startOrdinal"]?.jsonPrimitive?.intOrNull ?: return null,
            endOrdinal = o["endOrdinal"]?.jsonPrimitive?.intOrNull ?: return null,
            startOffset = o["startOffset"]?.jsonPrimitive?.intOrNull,
            endOffset = o["endOffset"]?.jsonPrimitive?.intOrNull,
            bookmarks = o["bookmarks"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            text = o["text"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }

    private var contextMenu: ContextMenu? = null

    private fun showContextMenu(x: Double, y: Double) {
        contextMenu?.hide()
        val sel = querySelection()
        val text = when (sel) {
            is Selection -> sel.text
            is String -> sel
            else -> ""
        }.trim()
        val menu = ContextMenu()
        fun item(label: String, enabled: Boolean = true, action: () -> Unit) =
            MenuItem(label).apply { isDisable = !enabled; setOnAction { action() } }

        menu.items += item("Copy", text.isNotEmpty()) { app.copyToClipboard(text) }
        if (sel is Selection) {
            menu.items += SeparatorMenuItem()
            menu.items += item("Bookmark selection") { makeBookmark(sel, wholeVerse = false, openNotes = false) }
            menu.items += item("Bookmark selection with note") { makeBookmark(sel, wholeVerse = false, openNotes = true) }
            if (sel.isBible) {
                val range = sel.verseRange
                menu.items += item("Bookmark verses") { makeBookmark(sel, wholeVerse = true, openNotes = false) }
                menu.items += item("Copy verses with reference") { range?.let { app.copyVerses(sel.swordBook!!, it) } }
                menu.items += item("Compare translations") { range?.let { app.openCompare(it) } }
                menu.items += item("My notes for this chapter") { range?.let { app.openMyNotes(it.start) } }
                menu.items += item("Memorize") {
                    range?.let { Progress.addTargetIfNeeded(it); app.openMemorize(sel.swordBook!!, it, this) }
                }
            }
            if (sel.bookmarks.isNotEmpty()) {
                menu.items += item("Remove bookmarks in selection") { Bookmarks.delete(sel.bookmarks) }
            }
        }
        if (sel is Selection && Llm.configured) {
            menu.items += SeparatorMenuItem()
            menu.items += item("Ask AI…") {
                val book = sel.book ?: return@item
                val s = if (sel.osisRef != null) net.bible.android.view.activity.page.Selection(book.initials, sel.osisRef, sel.startOrdinal, sel.endOrdinal, sel.text)
                    else net.bible.android.view.activity.page.Selection(book.initials, sel.startOrdinal, sel.endOrdinal, sel.text)
                Ai.showPromptSelector(s, net.bible.service.llm.PromptContext.VERSE_SELECTION,
                    page.category?.let { runCatching { net.bible.android.control.page.DocumentCategory.valueOf(it.name) }.getOrNull() })
            }
        }
        if (text.isNotEmpty()) {
            menu.items += SeparatorMenuItem()
            menu.items += item("Search for \"${text.take(30)}${if (text.length > 30) "…" else ""}\"") { app.showSearch(text) }
            menu.items += item("Look up in dictionaries") { app.lookupInDictionaries(text) }
        }
        menu.items += SeparatorMenuItem()
        menu.items += item("Reload window") { loadFrontend(); reload() }
        contextMenu = menu
        menu.show(webView, x, y)
    }

    fun findInPage(text: String, forward: Boolean = true) {
        exec("window.find(${jsString(text)}, false, ${!forward}, true);")
    }

    // ------------------------------------------------------------------ AI documents

    /** AiDocPagesChangedEvent: refresh AI document markers (BibleView.onEvent). */
    fun aiDocMarkersChanged(e: net.bible.service.sword.mydocument.AiDocPagesChangedEvent) {
        val v11n = (page as? Page.Bible)?.book?.versification
        if (e.markers.isNotEmpty()) {
            val arr = kotlinx.serialization.json.JsonArray(e.markers.map { Ai.markerJson(it, v11n) })
            exec("bibleView.emit('add_or_update_ai_doc_markers', $arr)")
        }
        if (e.deletedPageIds.isNotEmpty()) {
            exec("bibleView.emit('delete_ai_doc_markers', ${e.deletedPageIds.joinToString(",", "[", "]") { jsString(it.toString()) }})")
        }
    }

    /** Shows a message instead of the document (e.g. while an AI page is regenerated). */
    fun showMessage(text: String) {
        pending = Initial(Documents.error(text, "NORMAL"))
        if (clientReady) replaceDocument()
    }

    // ------------------------------------------------------------------ reading progress & memorization events

    override fun memorizationChanged(addedMemorized: List<Int>, removedMemorized: List<Int>, addedTargets: List<Int>, removedTargets: List<Int>) {
        val p = page
        if (p !is Page.Bible && p !is Page.Memorize) return
        val v11n = docV11n ?: return
        if (addedMemorized.isEmpty() && removedMemorized.isEmpty() && addedTargets.isEmpty() && removedTargets.isEmpty()) {
            Platform.runLater { reload() }; return
        }
        fun conv(l: List<Int>) = l.joinToString(",", "[", "]") { Verse(KJVA, it).toV11n(v11n).ordinal.toString() }
        Platform.runLater {
            exec("bibleView.emit('update_memorization_data', {addedMemorized: ${conv(addedMemorized)}, " +
                "removedMemorized: ${conv(removedMemorized)}, addedTargets: ${conv(addedTargets)}, removedTargets: ${conv(removedTargets)}})")
        }
    }

    override fun chapterReadStatusChanged(kjvBookOrdinal: Int, chapter: Int, count: Int) {
        val p = page as? Page.Bible ?: return
        if (p.verse.toV11n(KJVA).book.ordinal != kjvBookOrdinal) return
        Platform.runLater { exec("bibleView.emit('update_chapter_read_status', {chapter: $chapter, count: $count})") }
    }

    override fun activeCycleChanged() {
        val p = page as? Page.Bible ?: return
        if (minChapter < 0 || maxChapter < 0) return
        val counts = (minChapter..maxChapter).map { it to Progress.chapterReadCount(p.book.versification, p.verse.book, it) }
        Platform.runLater { counts.forEach { (c, n) -> exec("bibleView.emit('update_chapter_read_status', {chapter: $c, count: $n})") } }
    }

    override fun settingsChanged() {
        val bundle = Progress.settingsBundleJson()
        Platform.runLater { exec("bibleView.emit('update_reading_progress_settings', $bundle)"); updateConfig() }
    }

    fun dispose() {
        disposed = true
        Bookmarks.removeListener(this)
        Progress.removeListener(this)
        engine.load("about:blank")
    }
}
