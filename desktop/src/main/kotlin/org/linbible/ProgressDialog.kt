/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Reading progress window: port of AndBible's ReadingProgressActivity (reading and memorization tabs,
 * book/chapter heat maps, calendar heat map), ReadHistoryDialog and ReadingProgressSettings.
 */
package org.linbible

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.ContextMenu
import javafx.scene.control.Dialog
import javafx.scene.control.Label as FxLabel
import javafx.scene.control.ListView
import javafx.scene.control.MenuItem
import javafx.scene.control.ScrollPane
import javafx.scene.control.SelectionMode
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.layout.BorderPane
import javafx.scene.layout.FlowPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.stage.Stage
import org.crosswire.jsword.book.sword.SwordBook
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.passage.VerseRange
import org.crosswire.jsword.versification.BibleBook
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ProgressDialog private constructor(private val app: MainApp) : ProgressListener {
    private val stage = Stage()
    private val tabs = TabPane()
    private val readingTab = Tab("Reading")
    private val memorizeTab = Tab("Memorization")

    init {
        stage.title = "Reading Progress"
        stage.initOwner(app.stage)
        tabs.tabs.addAll(readingTab, memorizeTab)
        tabs.tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
        stage.scene = Scene(tabs, 860.0, 680.0).also { styleLike(app, it) }
        Progress.addListener(this)
        stage.setOnHidden { Progress.removeListener(this); instance = null }
        refresh()
    }

    override fun chapterReadStatusChanged(kjvBookOrdinal: Int, chapter: Int, count: Int) = later()
    override fun memorizationChanged(addedMemorized: List<Int>, removedMemorized: List<Int>, addedTargets: List<Int>, removedTargets: List<Int>) = later()
    override fun activeCycleChanged() = later()
    private fun later() = javafx.application.Platform.runLater { if (stage.isShowing) refresh() }

    private var selectedBook: BibleBook? = null
    private var selectedMemBook: BibleBook? = null

    private fun refresh() {
        readingTab.content = ScrollPane(readingContent()).apply { isFitToWidth = true }
        memorizeTab.content = ScrollPane(memorizeContent()).apply { isFitToWidth = true }
    }

    private fun bible(): SwordBook? = (app.activeWindow?.page as? Page.Bible)?.book ?: Sword.bibles.firstOrNull()

    private fun bookName(b: BibleBook): String = runCatching { KJVA.getPreferredNameInLocale(b, Locale.getDefault()) }.getOrDefault(b.osis)
    private fun shortName(b: BibleBook): String = runCatching { KJVA.getShortName(b) }.getOrDefault(b.osis)

    private fun heading(text: String) = FxLabel(text).apply { style = "-fx-font-size: 1.15em; -fx-font-weight: bold"; padding = Insets(8.0, 0.0, 2.0, 0.0) }

    private fun stat(value: String, label: String) = VBox(0.0,
        FxLabel(value).apply { style = "-fx-font-size: 1.6em; -fx-font-weight: bold" },
        FxLabel(label).apply { opacity = 0.75 }).apply { alignment = Pos.CENTER; minWidth = 150.0 }

    /** Heat colour for 0..1 (AndBible ReadingProgressColors: grey -> green). */
    private fun heat(f: Float, base: Color = Color.web("#2e9e4f")): Color =
        if (f <= 0f) Color.gray(0.5, 0.18) else base.deriveColor(0.0, 0.35 + 0.65 * f.coerceIn(0f, 1f).toDouble(), 1.0, 0.35 + 0.65 * f.coerceIn(0f, 1f).toDouble())

    private fun cell(text: String, color: Color, width: Double, tip: String, onClick: () -> Unit): Button = Button(text).apply {
        minWidth = width; prefWidth = width; minHeight = 30.0
        style = "-fx-background-color: ${web(color)}; -fx-background-radius: 4; -fx-padding: 2 4 2 4"
        tooltip = Tooltip(tip)
        setOnAction { onClick() }
    }

    private fun web(c: Color) = "rgba(${(c.red * 255).toInt()},${(c.green * 255).toInt()},${(c.blue * 255).toInt()},${"%.2f".format(Locale.ROOT, c.opacity)})"

    // ---------------------------------------------------------------- reading tab

    private fun readingContent(): VBox {
        val cycle = Progress.currentCycle()
        val total = Progress.totalReadChapters(cycle)
        val pct = total * 100.0 / Progress.totalBibleChapters
        val summary = HBox(16.0,
            stat("$total / ${Progress.totalBibleChapters}", "chapters read"),
            stat(Progress.distinctReadDays(cycle).toString(), "active days"),
            stat("%.1f %%".format(pct), "of the Bible"),
        ).apply { alignment = Pos.CENTER }

        val latest = Progress.latestCycle()
        val cycleBox = ComboBox<Int>().apply {
            items.setAll((1..maxOf(latest, cycle)).toList())
            value = cycle
            setOnAction { value?.let { if (it != Progress.currentCycle()) Progress.setActiveCycle(it) } }
        }
        val newCycle = Button("Start new reading cycle").apply {
            tooltip = Tooltip("Start reading through the Bible again; earlier cycles are kept")
            setOnAction {
                if (app.ask("New reading cycle", "Start a new reading cycle? Progress of earlier cycles is kept and can be viewed by choosing the cycle.")) Progress.startNewCycle()
            }
        }
        val autoTrack = CheckBox("Mark chapters read automatically while scrolling").apply {
            isSelected = Settings.state.display.autoTrackReading
            setOnAction { Settings.state.display.autoTrackReading = isSelected; app.displaySettingsChanged() }
        }
        val cycleRow = HBox(8.0, FxLabel("Reading cycle"), cycleBox, newCycle, Region().also { HBox.setHgrow(it, Priority.ALWAYS) }, autoTrack)
            .apply { alignment = Pos.CENTER_LEFT }

        val progress = Progress.bookReadingProgress(cycle)
        val books = FlowPane(4.0, 4.0)
        for (b in Progress.scriptureBooks) {
            val f = progress[b] ?: 0f
            books.children += cell(shortName(b), heat(f), 62.0, "${bookName(b)}: ${(f * 100).toInt()} % of chapters read") {
                selectedBook = b; refresh()
            }
        }

        val box = VBox(8.0, summary, cycleRow, heading("Books"), books)
        selectedBook?.let { box.children.addAll(chapterDetail(it, cycle)) }
        box.children += heading("Calendar (last 12 months)")
        box.children += calendar(Progress.readingCalendar(yearAgo(), System.currentTimeMillis(), cycle)) { day -> showDayHistory(day) }
        box.padding = Insets(12.0)
        return box
    }

    private fun chapterDetail(b: BibleBook, cycle: Int): List<javafx.scene.Node> {
        val counts = Progress.chapterCountsForBook(b, cycle)
        val max = (counts.values.maxOrNull() ?: 1).coerceAtLeast(1)
        val chapters = FlowPane(4.0, 4.0)
        for (ch in 1..KJVA.getLastChapter(b)) {
            val n = counts[ch] ?: 0
            val btn = cell(ch.toString(), heat(if (n == 0) 0f else 0.4f + 0.6f * n / max), 40.0,
                "${bookName(b)} $ch – read $n time${if (n == 1) "" else "s"}") { openChapter(b, ch) }
            btn.contextMenu = ContextMenu(
                MenuItem("Open").apply { setOnAction { openChapter(b, ch) } },
                MenuItem("Mark as read").apply { setOnAction { markRead(b, ch) } },
                MenuItem("Read history…").apply { setOnAction { showChapterHistory(app, b, ch) } },
            )
            chapters.children += btn
        }
        val header = HBox(8.0, heading("${bookName(b)} – chapters"), Region().also { HBox.setHgrow(it, Priority.ALWAYS) },
            Button("History…").apply { setOnAction { showHistory("${bookName(b)} read history") { Progress.historyForBook(b) } } },
            Button("Close").apply { setOnAction { selectedBook = null; refresh() } }).apply { alignment = Pos.CENTER_LEFT }
        return listOf(header, FxLabel("Click a chapter to open it; right-click for more.").apply { opacity = 0.7 }, chapters)
    }

    private fun openChapter(b: BibleBook, ch: Int) {
        app.openPassage(VerseRange(KJVA, Verse(KJVA, b, ch, 1), Verse(KJVA, b, ch, KJVA.getLastVerse(b, ch))).let {
            org.crosswire.jsword.passage.RangedPassage(KJVA).apply { add(it) }
        })
    }

    private fun markRead(b: BibleBook, ch: Int) {
        Progress.recordChapterRead(KJVA, b, ch, bible()?.initials ?: "", "MANUAL")
    }

    private fun yearAgo(): Long = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -364) }.let { Progress.localDayStart(it.timeInMillis) }

    /** GitHub-style calendar heat map: one column per week, one row per weekday. */
    private fun calendar(counts: Map<Long, Int>, onDay: (Long) -> Unit): javafx.scene.Node {
        val grid = GridPane().apply { hgap = 2.0; vgap = 2.0 }
        val max = (counts.values.maxOrNull() ?: 1).coerceAtLeast(1)
        val cal = Calendar.getInstance().apply { timeInMillis = yearAgo() }
        // start on the first day of that week
        while (cal.get(Calendar.DAY_OF_WEEK) != cal.firstDayOfWeek) cal.add(Calendar.DAY_OF_YEAR, -1)
        val today = Progress.localDayStart(System.currentTimeMillis())
        var col = 0
        val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
        while (Progress.localDayStart(cal.timeInMillis) <= today) {
            for (row in 0 until 7) {
                val day = Progress.localDayStart(cal.timeInMillis)
                if (day > today) break
                val n = counts[day] ?: 0
                val r = Rectangle(11.0, 11.0, heat(if (n == 0) 0f else 0.3f + 0.7f * n / max)).apply { arcWidth = 3.0; arcHeight = 3.0 }
                Tooltip.install(r, Tooltip("${fmt.format(Date(day))}: $n chapter${if (n == 1) "" else "s"}"))
                r.setOnMouseClicked { onDay(day) }
                grid.add(r, col, row)
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            col++
        }
        return grid
    }

    private fun showDayHistory(day: Long) =
        showHistory("Read on ${DateFormat.getDateInstance(DateFormat.LONG).format(Date(day))}") { Progress.historyForDay(day) }

    private fun showHistory(title: String, load: () -> List<Progress.ReadEntry>) = historyDialog(app, title, load)

    // ---------------------------------------------------------------- memorization tab

    private fun memorizeContent(): VBox {
        val (inTargets, targetTotal) = Progress.targetProgress()
        val summary = HBox(16.0,
            stat(Progress.totalMemorizedVerses().toString(), "verses memorized"),
            stat(if (targetTotal == 0) "–" else "$inTargets / $targetTotal", "of target verses"),
        ).apply { alignment = Pos.CENTER }

        val targets = VBox(4.0)
        val allTargets = Progress.targets()
        if (allTargets.isEmpty()) targets.children += FxLabel("No memorization targets yet. Select verses and choose Memorize.").apply { opacity = 0.7 }
        for (t in allTargets) {
            val m = Progress.countMemorizedIn(t.start, t.end)
            val bar = javafx.scene.control.ProgressBar(m.toDouble() / t.verseCount).apply { prefWidth = 160.0 }
            targets.children += HBox(8.0, FxLabel(t.range.name).apply { minWidth = 220.0 }, bar, FxLabel("$m / ${t.verseCount}"),
                Region().also { HBox.setHgrow(it, Priority.ALWAYS) },
                Button("Practice").apply { setOnAction { practice(t.range) } },
                Button("Remove").apply {
                    setOnAction { if (app.ask("Remove target", "Remove memorization target ${t.range.name}?")) Progress.removeTarget(t) }
                }).apply { alignment = Pos.CENTER_LEFT }
        }

        val memorized = VBox(4.0)
        val ranges = Progress.memorizedRanges()
        if (ranges.isEmpty()) memorized.children += FxLabel("Nothing memorized yet.").apply { opacity = 0.7 }
        val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
        for (r in ranges) {
            memorized.children += HBox(8.0, FxLabel(r.range.name).apply { minWidth = 220.0 }, FxLabel(fmt.format(Date(r.latestAt))).apply { opacity = 0.7 },
                Region().also { HBox.setHgrow(it, Priority.ALWAYS) },
                Button("Open").apply { setOnAction { app.openPassage(org.crosswire.jsword.passage.RangedPassage(KJVA).apply { add(r.range) }) } },
                Button("Practice").apply { setOnAction { practice(r.range) } },
                Button("Unmark").apply {
                    setOnAction { if (app.ask("Unmark", "Mark ${r.range.name} as not memorized?")) Progress.unmarkMemorized(r.range) }
                }).apply { alignment = Pos.CENTER_LEFT }
        }

        val progress = Progress.bookMemorizationProgress()
        val books = FlowPane(4.0, 4.0)
        for (b in Progress.scriptureBooks) {
            val f = progress[b] ?: 0f
            books.children += cell(shortName(b), heat(if (f > 0) 0.3f + 0.7f * f else 0f, Color.web("#7a5cd6")), 62.0,
                "${bookName(b)}: ${"%.1f".format(f * 100)} % memorized") { selectedMemBook = b; refresh() }
        }
        val box = VBox(8.0, summary, heading("Targets"), targets, heading("Memorized passages"), memorized, heading("Books"), books)
        selectedMemBook?.let { b ->
            val chapters = FlowPane(4.0, 4.0)
            for (ch in 1..KJVA.getLastChapter(b)) {
                val s = Verse(KJVA, b, ch, 1).ordinal
                val total = KJVA.getLastVerse(b, ch)
                val m = Progress.countMemorizedIn(s, s + total - 1)
                chapters.children += cell(ch.toString(), heat(if (m == 0) 0f else 0.3f + 0.7f * m / total, Color.web("#7a5cd6")), 40.0,
                    "${bookName(b)} $ch – $m of $total verses memorized") { openChapter(b, ch) }
            }
            box.children.addAll(HBox(8.0, heading("${bookName(b)} – chapters"), Region().also { HBox.setHgrow(it, Priority.ALWAYS) },
                Button("Close").apply { setOnAction { selectedMemBook = null; refresh() } }).apply { alignment = Pos.CENTER_LEFT }, chapters)
        }
        box.children += heading("Calendar (last 12 months)")
        box.children += calendar(Progress.memorizationCalendar(yearAgo(), System.currentTimeMillis())) {}
        box.padding = Insets(12.0)
        return box
    }

    private fun practice(kjvRange: VerseRange) {
        val b = bible() ?: run { app.toast("No Bible installed"); return }
        app.openMemorize(b, kjvRange.toV11n(b.versification), app.activeWindow)
    }

    companion object {
        private var instance: ProgressDialog? = null

        fun show(app: MainApp, tab: Int = 0) {
            val d = instance ?: ProgressDialog(app).also { instance = it }
            d.refresh()
            d.tabs.selectionModel.select(if (tab == 1) 1 else 0)
            d.stage.show(); d.stage.toFront()
        }

        fun showChapterHistory(app: MainApp, kjvBook: BibleBook, chapter: Int) {
            val name = runCatching { KJVA.getPreferredNameInLocale(kjvBook, Locale.getDefault()) }.getOrDefault(kjvBook.osis)
            historyDialog(app, "$name $chapter read history") { Progress.historyForChapter(kjvBook, chapter) }
        }

        /** AndBible's ReadHistoryDialog: list of reads, with deletion of selected entries. */
        fun historyDialog(app: MainApp, title: String, load: () -> List<Progress.ReadEntry>) {
            val list = ListView<Progress.ReadEntry>()
            list.selectionModel.selectionMode = SelectionMode.MULTIPLE
            val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            list.setCellFactory {
                object : javafx.scene.control.ListCell<Progress.ReadEntry>() {
                    override fun updateItem(item: Progress.ReadEntry?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = if (item == null || empty) null else {
                            val name = runCatching { KJVA.getShortName(item.book) }.getOrDefault(item.book.osis)
                            val src = when (item.source) { "AUTO_SCROLL" -> " (scrolling)"; "AUTO_TTS" -> " (speech)"; else -> "" }
                            "$name ${item.chapter} — ${fmt.format(Date(item.readAt))}${if (item.bookInitials.isNotEmpty()) " · ${item.bookInitials}" else ""}$src"
                        }
                    }
                }
            }
            list.items.setAll(load())
            val dlg = Dialog<ButtonType>().apply {
                initOwner(app.stage); this.title = title; headerText = null
                val delete = Button("Delete selected").apply {
                    setOnAction {
                        val sel = list.selectionModel.selectedItems.toList()
                        if (sel.isNotEmpty() && app.ask("Delete", "Delete ${sel.size} read record${if (sel.size == 1) "" else "s"}?")) {
                            Progress.deleteEntries(sel); list.items.setAll(load())
                        }
                    }
                }
                dialogPane.content = VBox(8.0, if (list.items.isEmpty()) FxLabel("No reads recorded.") else list, delete).apply { prefWidth = 480.0 }
                dialogPane.buttonTypes += ButtonType.CLOSE
            }
            dlg.dialogPane.scene?.let { styleLike(app, it) }
            dlg.show()
        }

        /** AndBible's ReadingProgressSettings activity. */
        fun showSettings(app: MainApp) {
            val d = Settings.state.display
            val b = Progress.settingsBundle()
            val showProgress = CheckBox("Show reading progress bar").apply { isSelected = d.showReadingProgress }
            val markButton = CheckBox("Show \"mark as read\" button at chapter ends").apply { isSelected = d.showMarkAsReadButton }
            val indicators = CheckBox("Show memorization indicators").apply { isSelected = d.showMemorizationIndicators }
            val autoTrack = CheckBox("Mark chapters read automatically while scrolling").apply { isSelected = d.autoTrackReading }
            val autoMem = CheckBox("Mark verses memorized automatically after practice").apply { isSelected = b.autoMarkMemorized }
            val includeRef = CheckBox("Include the reference when practising").apply { isSelected = b.memorizeIncludeReference }
            val fullWords = CheckBox("Type full words when practising").apply { isSelected = b.memorizeTypeFullWords }
            val heatmap = CheckBox("Show error heat map").apply { isSelected = b.memorizeErrorHeatmap }
            val hideUsed = CheckBox("Hide used words in scramble mode").apply { isSelected = b.memorizeScrambleHideUsed }
            val visibility = ComboBox<String>().apply { items.setAll("light", "dim", "hidden"); value = b.memorizeWordVisibility }
            val dlg = Dialog<ButtonType>().apply {
                initOwner(app.stage); title = "Reading progress settings"; headerText = null
                dialogPane.content = VBox(8.0, FxLabel("Reading").apply { style = "-fx-font-weight: bold" }, showProgress, markButton, autoTrack,
                    FxLabel("Memorization").apply { style = "-fx-font-weight: bold" }, indicators, autoMem, includeRef, fullWords, heatmap, hideUsed,
                    HBox(8.0, FxLabel("Word visibility"), visibility).apply { alignment = Pos.CENTER_LEFT })
                dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
            }
            dlg.dialogPane.scene?.let { styleLike(app, it) }
            if (dlg.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return
            d.showReadingProgress = showProgress.isSelected
            d.showMarkAsReadButton = markButton.isSelected
            d.showMemorizationIndicators = indicators.isSelected
            d.autoTrackReading = autoTrack.isSelected
            app.displaySettingsChanged()
            Progress.setSettingsBundle(json.encodeToString(ReadingProgressSettingsBundle.serializer(), ReadingProgressSettingsBundle(
                autoMem.isSelected, fullWords.isSelected, visibility.value ?: "light", heatmap.isSelected, hideUsed.isSelected, includeRef.isSelected)))
        }
    }
}
