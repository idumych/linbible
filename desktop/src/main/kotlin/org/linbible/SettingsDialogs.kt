/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
package org.linbible

import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.CheckBox
import javafx.scene.control.ColorPicker
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.Spinner
import javafx.scene.control.TextField
import javafx.scene.layout.BorderPane
import javafx.scene.layout.GridPane
import javafx.scene.text.Font
import javafx.stage.Stage
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.passage.Key

class DisplaySettingsDialog(private val app: MainApp) {
    private val stage = Stage()

    init {
        stage.initOwner(app.stage)
        stage.title = "Display settings — LinBible"
        val d = Settings.state.display
        val grid = GridPane().apply { hgap = 12.0; vgap = 8.0; padding = Insets(14.0) }
        var row = 0
        fun changed() = app.displaySettingsChanged()

        fun spinner(label: String, min: Int, max: Int, get: () -> Int, set: (Int) -> Unit) {
            val s = Spinner<Int>(min, max, get()).apply { isEditable = true; prefWidth = 90.0 }
            s.valueProperty().addListener { _, _, v -> set(v); changed() }
            grid.addRow(row++, Label(label), s)
        }
        fun check(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            val c = CheckBox(label).apply { isSelected = get() }
            c.selectedProperty().addListener { _, _, v -> set(v); changed() }
            grid.add(c, 0, row++, 2, 1)
        }
        fun color(label: String, get: () -> Int, set: (Int) -> Unit) {
            val p = ColorPicker(argbToColor(get()))
            p.setOnAction { set(colorToArgb(p.value)); changed() }
            grid.addRow(row++, Label(label), p)
        }

        val fonts = ComboBox<String>().apply {
            // AndBible add-on fonts first (served to BibleView from the add-on modules), then generic and system fonts
            items.setAll(Addons.fonts.keys.sorted() + listOf("sans-serif", "serif", "monospace") + Font.getFamilies())
            value = d.fontFamily
            isEditable = false
            setOnAction { d.fontFamily = value; changed() }
        }
        grid.addRow(row++, Label("Font"), fonts)
        spinner("Text size", 8, 60, { d.fontSize }) { d.fontSize = it }
        spinner("Line spacing", 5, 40, { d.lineSpacing }) { d.lineSpacing = it }
        spinner("Max text width (mm)", 50, 1000, { d.maxWidthMm }) { d.maxWidthMm = it }
        spinner("Left margin (mm)", 0, 60, { d.marginLeftMm }) { d.marginLeftMm = it }
        spinner("Right margin (mm)", 0, 60, { d.marginRightMm }) { d.marginRightMm = it }
        val strongs = ComboBox<String>().apply {
            items.setAll("Hidden", "Inline", "As links")
            selectionModel.select(d.strongsMode.coerceIn(0, 2))
            setOnAction { d.strongsMode = selectionModel.selectedIndex; changed() }
        }
        grid.addRow(row++, Label("Strong's numbers"), strongs)
        check("Morphology", { d.showMorphology }) { d.showMorphology = it }
        check("Footnotes", { d.showFootNotes }) { d.showFootNotes = it }
        check("Footnotes inline", { d.showFootNotesInline }) { d.showFootNotesInline = it }
        check("Cross references", { d.showXrefs }) { d.showXrefs = it }
        check("Expand cross references", { d.expandXrefs }) { d.expandXrefs = it }
        check("Words of Christ in red", { d.showRedLetters }) { d.showRedLetters = it }
        check("Section titles", { d.showSectionTitles }) { d.showSectionTitles = it }
        check("Verse numbers", { d.showVerseNumbers }) { d.showVerseNumbers = it }
        check("Each verse on its own line", { d.showVersePerLine }) { d.showVersePerLine = it }
        check("Show bookmarks", { d.showBookmarks }) { d.showBookmarks = it }
        check("Show my notes", { d.showMyNotes }) { d.showMyNotes = it }
        check("Justify text", { d.justifyText }) { d.justifyText = it }
        check("Hyphenation", { d.hyphenation }) { d.hyphenation = it }
        color("Day background", { d.dayBackground }) { d.dayBackground = it }
        color("Day text", { d.dayTextColor }) { d.dayTextColor = it }
        color("Night background", { d.nightBackground }) { d.nightBackground = it }
        color("Night text", { d.nightTextColor }) { d.nightTextColor = it }
        // Background images from add-on modules (or installed with Library → Install from file)
        fun background(label: String, get: () -> String?, set: (String?) -> Unit) {
            val none = "(none)"
            val box = ComboBox<String>().apply {
                items.setAll(listOf(none) + Addons.backgroundImages.keys.sorted())
                value = get()?.takeIf { it in items } ?: none
                setOnAction { set(value?.takeIf { it != none }); changed() }
            }
            grid.addRow(row++, Label(label), box)
        }
        background("Day background image", { d.dayBackgroundImage }) { d.dayBackgroundImage = it }
        spinner("Day image opacity (%)", 0, 100, { d.dayBackgroundImageOpacity }) { d.dayBackgroundImageOpacity = it }
        background("Night background image", { d.nightBackgroundImage }) { d.nightBackgroundImage = it }
        spinner("Night image opacity (%)", 0, 100, { d.nightBackgroundImageOpacity }) { d.nightBackgroundImageOpacity = it }
        val night = CheckBox("Night mode").apply { isSelected = app.nightMode }
        night.selectedProperty().addListener { _, _, v -> app.setNightMode(v) }
        grid.add(night, 0, row++, 2, 1)

        val scroll = javafx.scene.control.ScrollPane(grid).apply { isFitToWidth = true }
        stage.scene = Scene(BorderPane(scroll), 440.0, 680.0).also {
            it.stylesheets += app.stage.scene.stylesheets
            it.root.styleClass += app.stage.scene.root.styleClass
        }
    }

    fun show() { stage.show(); stage.toFront() }
}

/** Filterable list of the entries of a dictionary or general book. */
class KeyChooserDialog(private val app: MainApp, private val window: BibleWindow, private val book: Book) {
    private val stage = Stage()

    init {
        stage.initOwner(app.stage)
        stage.title = "${book.name} — index"
        val filter = TextField().apply { promptText = "Filter" }
        // EPUB books are listed by their table of contents (entries may point inside a fragment)
        val list = ListView<Pair<Key, String?>>()
        val all: List<Pair<Key, String?>> = Epub.state(book)?.tocKeys?.takeIf { it.isNotEmpty() }
            ?: KeyNavigation.keys(book).map { it to null }
        val isGenBook = book.bookCategory == org.crosswire.jsword.book.BookCategory.GENERAL_BOOK && !Epub.isEpub(book)
        list.setCellFactory {
            object : ListCell<Pair<Key, String?>>() {
                override fun updateItem(item: Pair<Key, String?>?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = item?.first?.let { if (isGenBook) "    ".repeat(KeyNavigation.depth(it)) + it.name else it.name }
                }
            }
        }
        fun apply() {
            val f = filter.text.trim().lowercase()
            list.items.setAll(if (f.isEmpty()) all else all.filter { it.first.name.lowercase().contains(f) }.sortedBy { !it.first.name.lowercase().startsWith(f) })
        }
        filter.textProperty().addListener { _, _, _ -> apply() }
        apply()
        (window.page as? Page.Keyed)?.key?.let { k -> list.items.firstOrNull { it.first == k }?.let { list.selectionModel.select(it); list.scrollTo(it) } }
        fun choose() {
            val (k, htmlId) = list.selectionModel.selectedItem ?: return
            app.navigate(window, Page.Keyed(book, k, htmlId = htmlId))
            stage.close()
        }
        list.setOnMouseClicked { if (it.clickCount == 2) choose() }
        list.setOnKeyPressed { if (it.code == javafx.scene.input.KeyCode.ENTER) choose() }
        filter.setOnAction { if (list.selectionModel.selectedItem == null) list.selectionModel.selectFirst(); choose() }
        stage.scene = Scene(BorderPane(list, filter, null, null, null).apply { padding = Insets(8.0) }, 420.0, 600.0).also {
            it.stylesheets += app.stage.scene.stylesheets
            it.root.styleClass += app.stage.scene.root.styleClass
        }
    }

    fun show() { stage.show(); stage.toFront() }
}
