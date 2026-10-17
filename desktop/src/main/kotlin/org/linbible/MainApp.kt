/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
package org.linbible

import javafx.animation.PauseTransition
import javafx.application.Application
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.ChoiceDialog
import kotlin.math.roundToInt
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckMenuItem
import javafx.scene.control.ComboBox
import javafx.scene.control.Label as FxLabel
import javafx.scene.control.ListCell
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.control.ProgressBar
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.SplitPane
import javafx.scene.control.TextField
import javafx.scene.control.TextInputDialog
import javafx.scene.control.ToolBar
import javafx.scene.control.Tooltip
import javafx.scene.image.Image
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import javafx.util.Duration
import javafx.util.StringConverter
import org.crosswire.common.progress.JobManager
import org.crosswire.common.progress.Progress
import org.crosswire.common.progress.WorkEvent
import org.crosswire.common.progress.WorkListener
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.BookCategory
import org.crosswire.jsword.book.sword.SwordBook
import org.crosswire.jsword.passage.Key
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.passage.VerseRange
import org.crosswire.jsword.versification.BibleBook
import java.util.concurrent.Executors

class MainApp : Application() {
    val executor = Executors.newFixedThreadPool(3) { r -> Thread(r, "content").apply { isDaemon = true } }
    val links = Links(this)

    lateinit var stage: Stage
    val windows = mutableListOf<BibleWindow>()
    var activeWindow: BibleWindow? = null
        private set
    private var linksWindow: BibleWindow? = null

    private val split = SplitPane()
    private val statusLabel = FxLabel()
    private val progressBar = ProgressBar().apply { isVisible = false; prefWidth = 160.0 }
    private val progressLabel = FxLabel()
    private val toastDelay = PauseTransition(Duration.seconds(4.0))

    // navigation toolbar
    private val docCombo = ComboBox<Book>()
    private val bookCombo = ComboBox<BibleBook>()
    private val chapterCombo = ComboBox<Int>()
    private val refField = TextField()
    private val indexButton = Button("Index…")
    private var updatingToolbar = false

    private val history = HashMap<BibleWindow, ArrayDeque<Page>>()
    private val future = HashMap<BibleWindow, ArrayDeque<Page>>()

    var nightMode = false
        private set
    private val nightItem = CheckMenuItem("Night mode")

    override fun start(primaryStage: Stage) {
        stage = primaryStage
        val s = Settings.state
        nightMode = if (s.followSystemTheme) systemPrefersDark() ?: s.nightMode else s.nightMode

        split.orientation = if (s.verticalSplit) Orientation.VERTICAL else Orientation.HORIZONTAL
        val root = BorderPane()
        root.top = VBox(buildMenu(), buildToolbar())
        root.center = split
        root.bottom = buildStatusBar()

        val vb = javafx.stage.Screen.getPrimary().visualBounds
        val scene = Scene(root, s.windowWidth.coerceAtMost(vb.width), s.windowHeight.coerceAtMost(vb.height))
        scene.stylesheets += MainApp::class.java.getResource("/app.css")!!.toExternalForm()
        scene.addEventFilter(KeyEvent.KEY_PRESSED, ::onKey)
        stage.scene = scene
        stage.title = "LinBible"
        MainApp::class.java.getResourceAsStream("/icons/linbible-256.png")?.let { stage.icons += Image(it) }
        stage.isMaximized = s.maximized
        applyTheme()

        // restore windows
        val restored = s.windows.mapNotNull { ws -> Page.fromState(ws)?.let { it to ws } }
        if (restored.isEmpty()) addWindow(Page.defaultBible())
        else restored.forEach { (page, ws) -> addWindow(page, ws.synchronized) }
        restored.mapIndexed { i, (_, ws) -> i to ws.weight }.let { weights -> setDividers(weights.map { it.second }) }
        setActive(windows.getOrNull(s.activeWindow) ?: windows.first())

        stage.setOnCloseRequest { saveState() }
        stage.show()
        Log.d("render scale ${stage.renderScaleX}, screen ${javafx.stage.Screen.getPrimary().outputScaleX}")

        Sword.onBooksChanged { Platform.runLater { booksChanged() } }
        JobManager.addWorkListener(object : WorkListener {
            override fun workProgressed(ev: WorkEvent) = Platform.runLater { showProgress(ev.job) }
            override fun workStateChanged(ev: WorkEvent) = Platform.runLater { showProgress(ev.job) }
        })

        val snapshot = System.getenv("LINBIBLE_SNAPSHOT")
        if (snapshot != null) debugSnapshot(snapshot)
        else if (Sword.bibles.isEmpty()) Platform.runLater { firstRun() }
    }

    /** Development aid: LINBIBLE_SNAPSHOT=file.png [LINBIBLE_SNAPSHOT_SCRIPT=…] writes a screenshot and exits. */
    private fun debugSnapshot(path: String) {
        val steps = System.getenv("LINBIBLE_SNAPSHOT_SCRIPT")?.split(";")?.filter { it.isNotBlank() } ?: emptyList()
        var delay = 6.0
        for (step in steps) {
            PauseTransition(Duration.seconds(delay)).apply {
                setOnFinished { Platform.runLater {
                    val (cmd, arg) = step.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    Log.i("snapshot script: $cmd $arg")
                    when (cmd) {
                        "goto" -> goToReference(arg)
                        "link" -> links.open(arg, activeWindow)
                        "js" -> activeWindow?.exec(arg)
                        "night" -> setNightMode(arg == "on")
                        "window" -> newWindow(BookCategory.valueOf(arg))
                        "shot" -> writeSnapshot(arg)
                        "bookmark" -> (activeWindow?.page as? Page.Bible)?.let { p ->
                            val r = Page.parsePassage(p.book.versification, arg)!!.toV11n(p.book.versification)
                            activeWindow!!.makeBookmark(BibleWindow.Selection(p.book, null, r.start.ordinal, r.end.ordinal), true, true)
                        }
                        "label" -> Bookmarks.saveLabel(Label(name = arg, color = (0xFFFFA500).toInt()))
                        "labelall" -> Bookmarks.bibleBookmarks.forEach { b -> Bookmarks.setLabels(b.id, Bookmarks.userLabels.map { it.id }) }
                        "studypad" -> Bookmarks.userLabels.firstOrNull()?.let { openStudyPad(it.id, null) }
                        "repos" -> executor.submit { Log.i("repos: " + Repositories.available(false) { n, e -> Log.e("repo $n failed", e) }.size + " documents") }
                        "index" -> Sword.bibles.firstOrNull()?.let { SearchDialog.buildIndex(this@MainApp, it) { Log.i("index done") } }
                        "search" -> showSearch(arg)
                    }
                } }
            }.play()
            delay += 4.0
        }
        PauseTransition(Duration.seconds(delay)).apply {
            setOnFinished { writeSnapshot(path); saveState(); Platform.exit() }
        }.play()
    }

    private fun writeSnapshot(path: String) {
        val img = stage.scene.snapshot(null)
        javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(img, null), "png", java.io.File(path))
        Log.i("Snapshot written to $path")
    }

    override fun stop() {
        AssetServer.stop()
        executor.shutdownNow()
    }

    // ------------------------------------------------------------------ layout

    private fun buildMenu(): MenuBar {
        fun item(text: String, accel: String? = null, action: () -> Unit) = MenuItem(text).apply {
            if (accel != null) accelerator = KeyCombination.keyCombination(accel)
            setOnAction { action() }
        }

        val file = Menu("_Library").apply {
            items.addAll(
                item("Download documents…", "Shortcut+D") { showDownloads() },
                item("Installed documents…") { showDownloads(installedTab = true) },
                SeparatorMenuItem(),
                item("Quit", "Shortcut+Q") { saveState(); Platform.exit() },
            )
        }
        val nav = Menu("_Navigate").apply {
            items.addAll(
                item("Go to reference…", "Shortcut+L") { refField.requestFocus(); refField.selectAll() },
                item("Previous chapter", "Shortcut+Page_Up") { activeWindow?.let { navigateChapter(it, -1) } },
                item("Next chapter", "Shortcut+Page_Down") { activeWindow?.let { navigateChapter(it, +1) } },
                SeparatorMenuItem(),
                item("Back", "Alt+Left") { goBack() },
                item("Forward", "Alt+Right") { goForward() },
            )
        }
        val search = Menu("_Search").apply {
            items.addAll(
                item("Search Bible…", "Shortcut+F") { showSearch(null) },
                item("Find in window…", "Shortcut+Shift+F") { findInWindow() },
            )
        }
        val studyPadMenu = Menu("StudyPads")
        val bookmarks = Menu("_Bookmarks").apply {
            items.addAll(
                item("All bookmarks…", "Shortcut+B") { BookmarksDialog(this@MainApp).show() },
                item("Labels and StudyPads…") { BookmarksDialog(this@MainApp, labelsTab = true).show() },
                studyPadMenu,
                SeparatorMenuItem(),
                item("My notes for this chapter") { activeWindow?.page?.currentVerse?.let { openMyNotes(it) } },
            )
            setOnShowing {
                studyPadMenu.items.setAll(Bookmarks.userLabels.map { l -> item(l.displayName) { openStudyPad(l.id, null) } })
                if (studyPadMenu.items.isEmpty()) studyPadMenu.items += MenuItem("(no labels yet)").apply { isDisable = true }
            }
        }
        nightItem.accelerator = KeyCombination.keyCombination("Shortcut+Shift+N")
        nightItem.setOnAction { setNightMode(nightItem.isSelected) }
        val view = Menu("_View").apply {
            items.addAll(
                nightItem,
                item("Display settings…", "Shortcut+Comma") { DisplaySettingsDialog(this@MainApp).show() },
                SeparatorMenuItem(),
                item("Larger text", "Shortcut+Equals") { changeFontSize(+1) },
                item("Smaller text", "Shortcut+Minus") { changeFontSize(-1) },
                item("Interface scale…") { chooseUiScale() },
                SeparatorMenuItem(),
                toggle("Strong's numbers", { it.strongsMode != 0 }) { d, v -> d.strongsMode = if (v) 2 else 0 },
                toggle("Morphology", { it.showMorphology }) { d, v -> d.showMorphology = v },
                toggle("Footnotes", { it.showFootNotes }) { d, v -> d.showFootNotes = v },
                toggle("Cross references", { it.showXrefs }) { d, v -> d.showXrefs = v },
                toggle("Red letters", { it.showRedLetters }) { d, v -> d.showRedLetters = v },
                toggle("Section titles", { it.showSectionTitles }) { d, v -> d.showSectionTitles = v },
                toggle("Verse numbers", { it.showVerseNumbers }) { d, v -> d.showVerseNumbers = v },
                toggle("Each verse on its own line", { it.showVersePerLine }) { d, v -> d.showVersePerLine = v },
                toggle("Bookmarks", { it.showBookmarks }) { d, v -> d.showBookmarks = v },
            )
        }
        val win = Menu("_Window").apply {
            items.addAll(
                item("New Bible window", "Shortcut+T") { newWindow(BookCategory.BIBLE) },
                item("New commentary window") { newWindow(BookCategory.COMMENTARY) },
                item("New dictionary window") { newWindow(BookCategory.DICTIONARY) },
                item("New general book window") { newWindow(BookCategory.GENERAL_BOOK) },
                SeparatorMenuItem(),
                item("Close window", "Shortcut+W") { activeWindow?.let { closeWindow(it) } },
                item("Toggle split direction") {
                    split.orientation = if (split.orientation == Orientation.HORIZONTAL) Orientation.VERTICAL else Orientation.HORIZONTAL
                    Settings.state.verticalSplit = split.orientation == Orientation.VERTICAL
                },
                item("Equalize window sizes") { setDividers(windows.map { 1.0 }) },
            )
        }
        val help = Menu("_Help").apply {
            items.addAll(
                item("AndBible documentation") { openBrowser("https://docs.andbible.org/en/latest/") },
                item("About LinBible") { about() },
            )
        }
        return MenuBar(file, nav, search, bookmarks, view, win, help)
    }

    private fun toggle(text: String, get: (DisplaySettings) -> Boolean, set: (DisplaySettings, Boolean) -> Unit) =
        CheckMenuItem(text).apply {
            isSelected = get(Settings.state.display)
            setOnAction { set(Settings.state.display, isSelected); displaySettingsChanged() }
            parentMenuProperty().addListener { _, _, m -> m?.setOnShown { isSelected = get(Settings.state.display) } }
        }

    private fun buildToolbar(): ToolBar {
        val back = Button("◀").apply { tooltip = Tooltip("Back (Alt+Left)"); setOnAction { goBack() } }
        val fwd = Button("▶").apply { tooltip = Tooltip("Forward (Alt+Right)"); setOnAction { goForward() } }
        val prev = Button("⏮").apply { tooltip = Tooltip("Previous chapter / entry"); setOnAction { activeWindow?.let { navigateChapter(it, -1) } } }
        val next = Button("⏭").apply { tooltip = Tooltip("Next chapter / entry"); setOnAction { activeWindow?.let { navigateChapter(it, +1) } } }

        docCombo.promptText = "Document"
        docCombo.prefWidth = 220.0
        docCombo.converter = object : StringConverter<Book>() {
            override fun toString(b: Book?) = b?.let { "${it.initials} – ${it.name}" } ?: ""
            override fun fromString(s: String?) = null
        }
        docCombo.setButtonCell(object : ListCell<Book>() {
            override fun updateItem(item: Book?, empty: Boolean) { super.updateItem(item, empty); text = item?.initials ?: "" }
        })
        docCombo.setOnAction { if (!updatingToolbar) docCombo.value?.let { changeDocument(it) } }

        bookCombo.prefWidth = 160.0
        bookCombo.converter = object : StringConverter<BibleBook>() {
            override fun toString(b: BibleBook?) = b?.let { bookName(it) } ?: ""
            override fun fromString(s: String?) = null
        }
        bookCombo.setOnAction {
            if (updatingToolbar) return@setOnAction
            val b = bookCombo.value ?: return@setOnAction
            goToVerse { v11n -> Verse(v11n, b, 1, 1) }
        }
        chapterCombo.prefWidth = 75.0
        chapterCombo.setOnAction {
            if (updatingToolbar) return@setOnAction
            val c = chapterCombo.value ?: return@setOnAction
            val b = bookCombo.value ?: return@setOnAction
            goToVerse { v11n -> Verse(v11n, b, c, 1) }
        }
        refField.promptText = "Go to… (e.g. John 3:16)"
        refField.prefColumnCount = 18
        refField.setOnAction { goToReference(refField.text) }
        indexButton.setOnAction { activeWindow?.let { w -> (w.page as? Page.Keyed)?.let { KeyChooserDialog(this, w, it.book).show() } } }

        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        val searchBtn = Button("Search").apply { setOnAction { showSearch(null) } }
        val bmBtn = Button("Bookmarks").apply { setOnAction { BookmarksDialog(this@MainApp).show() } }
        val dlBtn = Button("Download").apply { setOnAction { showDownloads() } }
        return ToolBar(back, fwd, docCombo, bookCombo, chapterCombo, indexButton, prev, next, refField, spacer, searchBtn, bmBtn, dlBtn)
    }

    private fun buildStatusBar(): HBox {
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        toastDelay.setOnFinished { statusLabel.text = "" }
        return HBox(8.0, statusLabel, spacer, progressLabel, progressBar).apply {
            padding = Insets(3.0, 8.0, 3.0, 8.0)
            alignment = Pos.CENTER_LEFT
            styleClass += "status-bar"
        }
    }

    private fun setDividers(weights: List<Double>) {
        if (weights.size < 2) return
        Platform.runLater {
            val total = weights.sum()
            var acc = 0.0
            val pos = weights.dropLast(1).map { acc += it; acc / total }
            split.setDividerPositions(*pos.toDoubleArray())
        }
    }

    // ------------------------------------------------------------------ windows

    fun addWindow(page: Page, sync: Boolean = true): BibleWindow {
        val w = BibleWindow(this, page, sync)
        val idx = linksWindow?.let { windows.size } ?: windows.size
        windows.add(idx, w)
        split.items.add(minOf(idx, split.items.size), w.root)
        setActive(w)
        refreshActiveIndicators()
        return w
    }

    private fun newWindow(cat: BookCategory) {
        val verse = activeWindow?.page?.currentVerse
        val page: Page = when (cat) {
            BookCategory.BIBLE -> {
                val b = Sword.bibles.firstOrNull { it != activeWindow?.page?.book } ?: Sword.bibles.firstOrNull()
                b?.let { Page.Bible(it, verse?.toV11n(it.versification) ?: Page.firstVerse(it)) }
            }
            BookCategory.COMMENTARY -> Sword.commentaries.firstOrNull()?.let { Page.Commentary(it, verse?.toV11n(it.versification) ?: Page.firstVerse(it)) }
            else -> Sword.byCategory(cat).firstOrNull()?.let { b -> Page.firstKey(b)?.let { Page.Keyed(b, it) } }
        } ?: run {
            if (ask("No ${cat.toString().lowercase()} installed", "You have no ${cat.toString().lowercase()} documents installed. Download some now?"))
                showDownloads(category = cat)
            return
        }
        addWindow(page)
        setDividers(windows.map { 1.0 })
    }

    fun closeWindow(w: BibleWindow) {
        if (w === linksWindow) {
            linksWindow = null
        } else if (windows.count { it !== linksWindow } <= 1) {
            toast("The last window cannot be closed"); return
        }
        windows.remove(w)
        split.items.remove(w.root)
        w.dispose()
        history.remove(w); future.remove(w)
        if (activeWindow === w) setActive(windows.firstOrNull { it !== linksWindow } ?: windows.first())
        refreshActiveIndicators()
    }

    fun setActive(w: BibleWindow) {
        if (activeWindow === w) return
        val old = activeWindow
        activeWindow = w
        old?.updateActive()
        w.updateActive()
        updateToolbar()
    }

    private fun refreshActiveIndicators() = windows.forEach { it.updateActive() }

    /** Shows a page in the dedicated links window (created on demand next to the other windows). */
    fun openInLinks(page: Page, from: BibleWindow?) {
        val lw = linksWindow
        if (lw != null && from !== lw) {
            pushHistory(lw)
            lw.show(page)
            setActive(lw)
            return
        }
        if (lw != null) { // link clicked inside the links window itself
            pushHistory(lw); lw.show(page); return
        }
        val w = BibleWindow(this, page, synchronized = false, isLinksWindow = true)
        w.titleLabel.styleClass += "links-title"
        linksWindow = w
        windows += w
        split.items += w.root
        setDividers(windows.map { if (it === w) 0.7 else 1.0 })
        setActive(w)
    }

    /** Navigation initiated by the user: remembered in history. */
    fun navigate(w: BibleWindow, page: Page) {
        pushHistory(w)
        w.show(page)
    }

    private fun pushHistory(w: BibleWindow) {
        val h = history.getOrPut(w) { ArrayDeque() }
        if (h.lastOrNull() != w.page) h.addLast(w.page)
        while (h.size > 100) h.removeFirst()
        future[w]?.clear()
    }

    private fun goBack() {
        val w = activeWindow ?: return
        val prev = history[w]?.removeLastOrNull() ?: return
        future.getOrPut(w) { ArrayDeque() }.addLast(w.page)
        w.show(prev)
        if (w.synchronized) prev.currentVerse?.let { verseChanged(w, it) }
    }

    private fun goForward() {
        val w = activeWindow ?: return
        val next = future[w]?.removeLastOrNull() ?: return
        history.getOrPut(w) { ArrayDeque() }.addLast(w.page)
        w.show(next)
        if (w.synchronized) next.currentVerse?.let { verseChanged(w, it) }
    }

    fun windowPageChanged(w: BibleWindow) {
        if (w === activeWindow) updateToolbar()
    }

    /** The active window moved to [verse]: synchronized windows follow. */
    fun verseChanged(source: BibleWindow, verse: Verse) {
        if (!source.synchronized) return
        windows.filter { it !== source && it.synchronized && it !== linksWindow }.forEach { it.syncTo(verse) }
    }

    fun navigateChapter(w: BibleWindow, delta: Int) {
        val p = w.page
        val newPage: Page = when (p) {
            is Page.Bible -> {
                val v = stepChapter(p.book, p.verse, delta) ?: return
                p.copy(verse = v, highlight = null)
            }
            is Page.Commentary -> {
                val b = CommentaryBlocks(p.book)
                val block = (if (delta > 0) b.nextBlock(b.resolve(p.verse)?.end ?: p.verse) else b.prevBlock(b.resolve(p.verse)?.start ?: p.verse)) ?: return
                p.copy(verse = block.start)
            }
            is Page.Keyed -> {
                val k = KeyNavigation.step(p.book, p.key, delta) ?: return
                Page.Keyed(p.book, k)
            }
            else -> return
        }
        navigate(w, newPage)
        if (w === activeWindow) newPage.currentVerse?.let { verseChanged(w, it) }
    }

    private fun stepChapter(book: SwordBook, verse: Verse, delta: Int): Verse? {
        val v11n = book.versification
        var b = verse.book
        var c = verse.chapter + delta
        if (c < 1) {
            b = v11n.getPreviousBook(b) ?: return null
            c = v11n.getLastChapter(b)
        } else if (c > v11n.getLastChapter(b)) {
            b = v11n.getNextBook(b) ?: return null
            c = 1
        }
        return Verse(v11n, b, c, 1)
    }

    private fun goToVerse(make: (org.crosswire.jsword.versification.Versification) -> Verse) {
        val w = activeWindow ?: return
        val p = w.page
        val page: Page = when (p) {
            is Page.Bible -> p.copy(verse = make(p.book.versification), highlight = null)
            is Page.Commentary -> p.copy(verse = make(p.book.versification))
            else -> return
        }
        navigate(w, page)
        page.currentVerse?.let { verseChanged(w, it) }
    }

    fun goToReference(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val w = activeWindow ?: return
        when (val p = w.page) {
            is Page.Keyed -> {
                val keys = KeyNavigation.keys(p.book)
                val k = keys.firstOrNull { it.name.equals(t, true) }
                    ?: keys.firstOrNull { it.name.startsWith(t, true) }
                    ?: keys.firstOrNull { it.name.contains(t, true) }
                if (k == null) toast("\"$t\" not found in ${p.book.initials}") else navigate(w, Page.Keyed(p.book, k))
            }
            else -> {
                val bible = (p.book as? SwordBook) ?: Sword.bibles.firstOrNull() ?: return
                val passage = Page.parsePassage(bible.versification, t) ?: run { toast("Could not understand \"$t\""); return }
                val range = passage.toV11n(bible.versification)
                val v11n = range.versification
                val start = if (range.start.verse == 0) Verse(v11n, range.start.book, range.start.chapter, 1) else range.start
                // Highlight only explicit verse references, not whole chapters
                val wholeChapter = range.start.verse <= 1 && range.end.verse == v11n.getLastVerse(range.end.book, range.end.chapter)
                val highlight = if (wholeChapter) null else range
                val page: Page = when (p) {
                    is Page.Commentary -> p.copy(verse = start)
                    is Page.Bible -> p.copy(verse = start, highlight = highlight)
                    else -> Page.Bible(bible, start, highlight)
                }
                navigate(w, page)
                verseChanged(w, start)
                w.webView.requestFocus()
            }
        }
    }

    private fun changeDocument(book: Book) {
        val w = activeWindow ?: return
        val p = w.page
        if (p.book == book) return
        val verse = p.currentVerse
        val page: Page = when (book.bookCategory) {
            BookCategory.BIBLE -> Page.Bible(book as SwordBook, verse?.toV11n(book.versification) ?: Page.firstVerse(book))
            BookCategory.COMMENTARY -> Page.Commentary(book as SwordBook, verse?.toV11n(book.versification) ?: Page.firstVerse(book))
            else -> {
                val sameKey = (p as? Page.Keyed)?.key?.name?.let { name -> runCatching { book.getKey(name) }.getOrNull() }
                Page.Keyed(book, sameKey ?: Page.firstKey(book) ?: return)
            }
        }
        navigate(w, page)
    }

    private fun bookName(b: BibleBook): String {
        val v11n = (activeWindow?.page?.book as? SwordBook)?.versification ?: KJVA
        return runCatching { v11n.getPreferredNameInLocale(b, java.util.Locale.getDefault()) }.getOrDefault(b.osis)
    }

    private fun updateToolbar() {
        val w = activeWindow ?: return
        val p = w.page
        updatingToolbar = true
        try {
            val cat = p.category
            val docs = if (cat != null) Sword.byCategory(cat) else emptyList()
            docCombo.items.setAll(docs)
            docCombo.value = p.book
            docCombo.isDisable = cat == null
            val verse = p.currentVerse
            val sword = p.book as? SwordBook
            val verseBased = (p is Page.Bible || p is Page.Commentary) && sword != null && verse != null
            bookCombo.isVisible = verseBased; bookCombo.isManaged = verseBased
            chapterCombo.isVisible = verseBased; chapterCombo.isManaged = verseBased
            indexButton.isVisible = p is Page.Keyed; indexButton.isManaged = p is Page.Keyed
            if (verseBased) {
                val v11n = sword!!.versification
                val books = (0 until v11n.bookCount).map { v11n.getBook(it) }.toList().filter {
                    it != BibleBook.INTRO_BIBLE && it != BibleBook.INTRO_OT && it != BibleBook.INTRO_NT
                }
                if (bookCombo.items != books) bookCombo.items.setAll(books)
                bookCombo.value = verse!!.book
                chapterCombo.items.setAll((1..v11n.getLastChapter(verse.book)).toList())
                chapterCombo.value = verse.chapter.coerceAtLeast(1)
            }
            refField.promptText = if (p is Page.Keyed) "Find entry…" else "Go to… (e.g. John 3:16)"
            stage.title = "${p.title} — LinBible"
        } finally {
            updatingToolbar = false
        }
    }

    private fun booksChanged() {
        Content.clearCache()
        updateToolbar()
        if (windows.size == 1 && windows[0].page is Page.Message) {
            val page = Page.defaultBible()
            if (page !is Page.Message) windows[0].show(page)
        }
    }

    // ------------------------------------------------------------------ settings

    fun displaySettingsChanged() {
        Settings.save()
        updateAllConfigs()
    }

    fun updateAllConfigs() = windows.forEach { it.updateConfig() }

    private fun changeFontSize(delta: Int) {
        val d = Settings.state.display
        d.fontSize = (d.fontSize + delta).coerceIn(8, 60)
        displaySettingsChanged()
        toast("Text size ${d.fontSize}")
    }

    fun setNightMode(on: Boolean) {
        nightMode = on
        Settings.state.nightMode = on
        Settings.state.followSystemTheme = false
        applyTheme()
        updateAllConfigs()
    }

    private fun applyTheme() {
        nightItem.isSelected = nightMode
        val root = stage.scene?.root ?: return
        root.styleClass.remove("dark")
        if (nightMode) root.styleClass += "dark"
        windows.forEach { it.webView.pageFill = if (nightMode) Color.web("#121212") else Color.WHITE }
    }

    private fun systemPrefersDark(): Boolean? = try {
        val p = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "color-scheme").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        if (p.exitValue() == 0) out.contains("dark") else null
    } catch (e: Exception) {
        null
    }

    private fun saveState() {
        val s = Settings.state
        val main = windows.filter { it !== linksWindow }
        val positions = split.dividerPositions.toList()
        val bounds = (listOf(0.0) + positions + listOf(1.0)).zipWithNext { a, b -> b - a }
        s.windows = main.mapNotNull { w -> w.page.toState(w.synchronized, bounds.getOrElse(windows.indexOf(w)) { 1.0 }) }
            .ifEmpty { s.windows }
        s.activeWindow = main.indexOf(activeWindow).coerceAtLeast(0)
        s.maximized = stage.isMaximized
        if (!stage.isMaximized) { s.windowWidth = stage.width; s.windowHeight = stage.height }
        Settings.save()
    }

    // ------------------------------------------------------------------ actions used by windows

    fun openStudyPad(labelId: String, entryId: String?) {
        if (Bookmarks.label(labelId) == null) return
        openInLinks(Page.StudyPad(labelId, entryId), null)
    }

    fun openMyNotes(verse: Verse) {
        val kjv = verse.toV11n(KJVA)
        openInLinks(Page.MyNotes(wholeChapter(KJVA, kjv.book, kjv.chapter.coerceAtLeast(1))), null)
    }

    fun openCompare(range: VerseRange) {
        val bibles = Sword.bibles
        val items = bibles.mapNotNull { b ->
            val r = runCatching { range.toV11n(b.versification) }.getOrNull() ?: return@mapNotNull null
            if (Content.contains(b, r)) b to (r as Key) else null
        }
        openInLinks(Page.Multi(items, true, "Compare ${range.name}"), activeWindow)
    }

    fun lookupInDictionaries(text: String) {
        val word = text.trim().split(Regex("\\s+")).firstOrNull()?.trim { !it.isLetterOrDigit() } ?: return
        val items = Sword.dictionaries.mapNotNull { d ->
            val k = runCatching { d.getKey(word) }.getOrNull()
                ?: KeyNavigation.keys(d).firstOrNull { it.name.equals(word, true) }
            k?.let { d to it }
        }
        if (items.isEmpty()) toast("\"$word\" not found in installed dictionaries")
        else openInLinks(Page.Multi(items, false, word), activeWindow)
    }

    fun copyVerses(book: SwordBook, range: VerseRange) {
        val text = Content.plainText(book, range)
        copyToClipboard("$text\n— ${range.name} (${book.abbreviation})")
        toast("Copied ${range.name}")
    }

    fun copyToClipboard(text: String) {
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(text) })
    }

    fun copyHtmlToClipboard(html: String) {
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putHtml(html); putString(html.replace(Regex("<[^>]+>"), "")) })
        toast("Copied")
    }

    fun speak(text: String) {
        try {
            ProcessBuilder("spd-say", "--wait", text).start()
        } catch (e: Exception) {
            try { ProcessBuilder("espeak-ng", text).start() } catch (e2: Exception) {
                toast("Text-to-speech needs speech-dispatcher (spd-say) or espeak-ng installed")
            }
        }
    }

    fun openBrowser(url: String) {
        try { hostServices.showDocument(url) } catch (e: Exception) {
            runCatching { ProcessBuilder("xdg-open", url).start() }
        }
    }

    private fun chooseUiScale() {
        fun pct(v: Double) = "${(v * 100).roundToInt()}%"
        val auto = "Automatic (${pct(HiDpi.detected)})"
        val choices = listOf(auto) + listOf(1.0, 1.25, 1.5, 1.75, 1.9, 2.0, 2.25, 2.5, 3.0).map(::pct)
        val current = Settings.state.uiScale?.let(::pct) ?: auto
        val dlg = ChoiceDialog(current, choices).apply {
            initOwner(stage); title = "Interface scale"; headerText = null
            contentText = "Scale (currently ${pct(HiDpi.applied)}, takes effect after restart):"
        }
        val picked = dlg.showAndWait().orElse(null) ?: return
        val value = if (picked == auto) null else picked.removeSuffix("%").toDouble() / 100
        if (value == Settings.state.uiScale) return
        Settings.state.uiScale = value
        Settings.save()
        info("Interface scale", "Restart LinBible to apply the new scale.")
    }

    fun toast(text: String) {
        statusLabel.text = text
        toastDelay.playFromStart()
    }

    fun info(title: String, content: String) {
        Alert(Alert.AlertType.INFORMATION).apply {
            initOwner(stage); this.title = title; headerText = null
            contentText = content.replace(Regex("<[^>]+>"), "")
        }.showAndWait()
    }

    fun ask(title: String, question: String): Boolean =
        Alert(Alert.AlertType.CONFIRMATION, question, ButtonType.YES, ButtonType.NO).apply {
            initOwner(stage); this.title = title; headerText = null
        }.showAndWait().orElse(ButtonType.NO) == ButtonType.YES

    fun askReference(title: String): VerseRange? {
        val dlg = TextInputDialog().apply { initOwner(stage); this.title = title; headerText = null; contentText = "Reference:" }
        val text = dlg.showAndWait().orElse(null) ?: return null
        val v11n = (activeWindow?.page?.book as? SwordBook)?.versification ?: KJVA
        return Page.parsePassage(v11n, text)?.toV11n(v11n)
    }

    fun showAssignLabels(bookmarkId: String) = LabelAssignDialog(this, bookmarkId).show()

    fun showSearch(text: String?) = SearchDialog.show(this, text)

    fun showDownloads(initials: String? = null, installedTab: Boolean = false, category: BookCategory? = null) =
        DownloadDialog.show(this, initials, installedTab, category)

    /** Opens a Bible/commentary page at [verse] in the active (or first Bible) window. */
    fun openVerse(verse: Verse, highlight: VerseRange? = null, bible: SwordBook? = null) {
        val w = windows.firstOrNull { it === activeWindow && it.page is Page.Bible }
            ?: windows.firstOrNull { it.page is Page.Bible && it !== linksWindow }
            ?: activeWindow ?: return
        val book = bible ?: (w.page.book as? SwordBook)?.takeIf { it.bookCategory == BookCategory.BIBLE } ?: Sword.bibles.firstOrNull() ?: return
        val v = verse.toV11n(book.versification)
        navigate(w, Page.Bible(book, v, highlight?.toV11n(book.versification)))
        setActive(w)
        verseChanged(w, v)
    }

    fun openKey(book: Book, key: Key, inWindow: BibleWindow? = null) {
        val w = inWindow ?: windows.firstOrNull { it.page.book?.bookCategory == book.bookCategory } ?: activeWindow ?: return
        navigate(w, Page.Keyed(book, key))
    }

    private fun findInWindow() {
        val w = activeWindow ?: return
        val dlg = TextInputDialog().apply { initOwner(stage); title = "Find in window"; headerText = null; contentText = "Find:" }
        dlg.showAndWait().ifPresent { w.findInPage(it) }
    }

    private fun onKey(e: KeyEvent) {
        if (activeWindow?.editing == true) return
        if (e.target is TextField) return
        if (e.isAltDown || e.isControlDown || e.isMetaDown) return
        // Bare-key shortcuts that do not interfere with text entry
        when (e.code) {
            KeyCode.F3 -> { findInWindow(); e.consume() }
            else -> {}
        }
    }

    private fun showProgress(job: Progress) {
        if (job.isFinished) {
            progressBar.isVisible = false
            progressLabel.text = ""
            toast("${job.jobName}: done")
        } else {
            progressBar.isVisible = true
            progressBar.progress = if (job.work > 0) job.work / 100.0 else -1.0
            progressLabel.text = job.sectionName ?: job.jobName
        }
    }

    private fun firstRun() {
        if (ask("Welcome to LinBible", "No Bibles are installed yet.\n\nWould you like to open the download manager to install some documents (for example the King James Version)?")) {
            showDownloads(category = BookCategory.BIBLE)
        }
    }

    private fun about() {
        Alert(Alert.AlertType.INFORMATION).apply {
            initOwner(stage)
            title = "About LinBible"
            headerText = "LinBible ${BuildInfo.VERSION}"
            contentText = """
                A Linux desktop port of AndBible: Bible Study.

                Bible text rendering, bookmarks, StudyPads and module handling come from
                AndBible (https://github.com/AndBible/and-bible) and its JSword library.

                Documents are stored in ~/.sword and are shared with other SWORD applications.

                License: GNU General Public License v3 or later.
            """.trimIndent()
        }.showAndWait()
    }
}

object BuildInfo {
    val VERSION: String = MainApp::class.java.`package`?.implementationVersion ?: "0.1.0"
}

fun main(args: Array<String>) {
    System.setProperty("prism.lcdtext", "false")
    Settings.load()
    HiDpi.apply()
    Sword.init()
    Bookmarks.load()
    AssetServer.start()
    Application.launch(MainApp::class.java, *args)
}

