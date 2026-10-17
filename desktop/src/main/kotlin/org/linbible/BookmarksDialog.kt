/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
package org.linbible

import javafx.beans.property.SimpleStringProperty
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ColorPicker
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label as FxLabel
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TableColumn
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.TextInputDialog
import javafx.scene.layout.BorderPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.stage.Stage
import javafx.util.StringConverter
import org.crosswire.jsword.book.sword.SwordBook

fun argbToColor(c: Int): Color = Color.rgb((c shr 16) and 0xff, (c shr 8) and 0xff, c and 0xff, ((c ushr 24) and 0xff) / 255.0)
fun colorToArgb(c: Color): Int =
    ((c.opacity * 255).toInt() shl 24) or ((c.red * 255).toInt() shl 16) or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()

private fun styleLike(app: MainApp, scene: Scene) {
    scene.stylesheets += app.stage.scene.stylesheets
    scene.root.styleClass += app.stage.scene.root.styleClass
}

class BookmarksDialog(private val app: MainApp, labelsTab: Boolean = false) {
    private val stage = Stage()

    private sealed class Row(val id: String, val ref: String, val text: String, val note: String, val labels: String, val created: Long)
    private class BibleRow(val b: BibleBookmark) : Row(b.id, b.verseRange.name, Bookmarks.text(b), b.notes ?: "",
        Bookmarks.labelIdsFor(b.id, false).mapNotNull { Bookmarks.label(it)?.displayName }.joinToString(), b.createdAt)
    private class GenericRow(val g: GenericBookmark) : Row(g.id, "${g.bookInitials}: ${g.key}", Bookmarks.text(g), g.notes ?: "",
        Bookmarks.labelIdsFor(g.id, true).mapNotNull { Bookmarks.label(it)?.displayName }.joinToString(), g.createdAt)

    private val table = TableView<Row>()
    private val labelFilter = ComboBox<Label?>()
    private val textFilter = TextField()
    private val labelList = ListView<Label>()

    init {
        stage.initOwner(app.stage)
        stage.title = "Bookmarks — LinBible"
        val tabs = TabPane(Tab("Bookmarks", buildBookmarks()).apply { isClosable = false }, Tab("Labels & StudyPads", buildLabels()).apply { isClosable = false })
        if (labelsTab) tabs.selectionModel.select(1)
        val scene = Scene(BorderPane(tabs).apply { padding = Insets(8.0) }, 900.0, 600.0)
        styleLike(app, scene)
        stage.scene = scene
        refresh()
    }

    fun show() { stage.show(); stage.toFront() }

    private fun buildBookmarks(): BorderPane {
        fun c(t: String, w: Double, f: (Row) -> String) = TableColumn<Row, String>(t).apply { prefWidth = w; setCellValueFactory { SimpleStringProperty(f(it.value)) } }
        table.columns.addAll(c("Reference", 140.0) { it.ref }, c("Text", 380.0) { it.text }, c("Labels", 140.0) { it.labels }, c("Note", 200.0) { it.note.replace(Regex("<[^>]+>"), " ") })
        table.columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        table.placeholder = FxLabel("No bookmarks. Click a verse in the text to bookmark it.")
        table.setRowFactory { object : TableRow<Row>() { init { setOnMouseClicked { e -> if (e.clickCount == 2 && item != null) open(item) } } } }

        labelFilter.converter = object : StringConverter<Label?>() {
            override fun toString(l: Label?) = l?.displayName ?: "All labels"
            override fun fromString(s: String?) = null
        }
        labelFilter.setOnAction { refresh() }
        textFilter.promptText = "Filter"
        textFilter.textProperty().addListener { _, _, _ -> refresh() }
        HBox.setHgrow(textFilter, Priority.ALWAYS)
        val open = Button("Open").apply { setOnAction { table.selectionModel.selectedItem?.let { open(it) } } }
        val labels = Button("Labels…").apply { setOnAction { table.selectionModel.selectedItem?.let { LabelAssignDialog(app, it.id).show(); refresh() } } }
        val del = Button("Delete").apply {
            setOnAction {
                val sel = table.selectionModel.selectedItems.toList()
                if (sel.isNotEmpty() && app.ask("Delete", "Delete ${sel.size} bookmark(s)?")) { Bookmarks.delete(sel.map { it.id }); refresh() }
            }
        }
        table.selectionModel.selectionMode = javafx.scene.control.SelectionMode.MULTIPLE
        val top = HBox(8.0, labelFilter, textFilter).apply { padding = Insets(0.0, 0.0, 8.0, 0.0) }
        val bottom = HBox(8.0, Region().also { HBox.setHgrow(it, Priority.ALWAYS) }, labels, del, open).apply { padding = Insets(8.0, 0.0, 0.0, 0.0) }
        return BorderPane(table, top, null, bottom, null)
    }

    private fun refresh() {
        val sel = labelFilter.value
        labelFilter.items.setAll(listOf<Label?>(null) + Bookmarks.labels.filter { !it.isSpeak }.sortedBy { it.displayName.lowercase() })
        labelFilter.value = sel?.let { s -> labelFilter.items.firstOrNull { it?.id == s.id } }
        val f = textFilter.text.trim().lowercase()
        val lid = labelFilter.value?.id
        fun matchLabel(id: String, generic: Boolean): Boolean {
            if (lid == null) return true
            val ls = Bookmarks.labelIdsFor(id, generic)
            return if (lid == UNLABELED_LABEL_ID) ls.isEmpty() else lid in ls
        }
        val rows = Bookmarks.bibleBookmarks.filter { matchLabel(it.id, false) }.sortedBy { it.kjvOrdinalStart }.map { BibleRow(it) } +
            Bookmarks.genericBookmarks.filter { matchLabel(it.id, true) }.map { GenericRow(it) }
        table.items.setAll(rows.filter { f.isEmpty() || it.ref.lowercase().contains(f) || it.text.lowercase().contains(f) || it.note.lowercase().contains(f) || it.labels.lowercase().contains(f) })
        labelList.items.setAll(Bookmarks.userLabels)
    }

    private fun open(r: Row) {
        when (r) {
            is BibleRow -> {
                val book = Sword.book(r.b.bookInitials) as? SwordBook
                app.openVerse(r.b.verseRange.start, r.b.verseRange, book?.takeIf { it.bookCategory == org.crosswire.jsword.book.BookCategory.BIBLE })
            }
            is GenericRow -> {
                val book = Sword.book(r.g.bookInitials) ?: return
                val key = runCatching { book.getKey(r.g.key) }.getOrNull() ?: return
                app.openInLinks(Page.Keyed(book, key, r.g.ordinalStart?.let { s -> s..(r.g.ordinalEnd ?: s) }), null)
            }
        }
    }

    private fun buildLabels(): BorderPane {
        labelList.setCellFactory {
            object : ListCell<Label>() {
                override fun updateItem(item: Label?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = item?.let { if (it.favourite) "★ ${it.displayName}" else it.displayName }
                    graphic = item?.let { Rectangle(14.0, 14.0, argbToColor(it.color)) }
                }
            }
        }
        labelList.setOnMouseClicked { e -> if (e.clickCount == 2) labelList.selectionModel.selectedItem?.let { app.openStudyPad(it.id, null) } }
        val new = Button("New label…").apply { setOnAction { LabelEditor.edit(app, null)?.let { refresh() } } }
        val edit = Button("Edit…").apply { setOnAction { labelList.selectionModel.selectedItem?.let { LabelEditor.edit(app, it); refresh() } } }
        val del = Button("Delete").apply {
            setOnAction {
                val l = labelList.selectionModel.selectedItem ?: return@setOnAction
                if (app.ask("Delete label", "Delete label \"${l.displayName}\"? Bookmarks are kept, StudyPad text entries are deleted.")) { Bookmarks.deleteLabel(l.id); refresh() }
            }
        }
        val open = Button("Open StudyPad").apply { setOnAction { labelList.selectionModel.selectedItem?.let { app.openStudyPad(it.id, null) } } }
        val bottom = HBox(8.0, new, edit, del, Region().also { HBox.setHgrow(it, Priority.ALWAYS) }, open).apply { padding = Insets(8.0, 0.0, 0.0, 0.0) }
        val hint = FxLabel("Each label is also a StudyPad: a document combining your notes with the bookmarks carrying that label.").apply { isWrapText = true; padding = Insets(0.0, 0.0, 8.0, 0.0) }
        return BorderPane(labelList, hint, null, bottom, null)
    }
}

object LabelEditor {
    /** Creates (label == null) or edits a label. Returns the saved label, or null if cancelled. */
    fun edit(app: MainApp, label: Label?): Label? {
        val l = label?.copy() ?: Label(name = "")
        val dlg = Dialog<ButtonType>()
        dlg.initOwner(app.stage)
        dlg.title = if (label == null) "New label" else "Edit label"
        val name = TextField(l.name)
        val color = ColorPicker(argbToColor(l.color))
        val style = ComboBox<String>().apply { items.setAll("Highlight", "Underline", "Marker", "Hidden"); value = when {
            l.underlineStyle || l.underlineStyleWholeVerse -> "Underline"; l.markerStyle || l.markerStyleWholeVerse -> "Marker"
            l.hideStyle || l.hideStyleWholeVerse -> "Hidden"; else -> "Highlight" } }
        val whole = CheckBox("Apply style to whole verse").apply { isSelected = l.underlineStyleWholeVerse || l.markerStyleWholeVerse || l.hideStyleWholeVerse }
        val fav = CheckBox("Favourite (shown first)").apply { isSelected = l.favourite }
        val grid = GridPane().apply {
            hgap = 8.0; vgap = 8.0; padding = Insets(12.0)
            addRow(0, FxLabel("Name"), name); addRow(1, FxLabel("Colour"), color); addRow(2, FxLabel("Style"), style)
            add(whole, 1, 3); add(fav, 1, 4)
        }
        dlg.dialogPane.content = grid
        dlg.dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        dlg.dialogPane.lookupButton(ButtonType.OK).disableProperty().bind(name.textProperty().isEmpty)
        javafx.application.Platform.runLater { name.requestFocus() }
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null
        l.name = name.text.trim()
        l.color = colorToArgb(color.value)
        l.favourite = fav.isSelected
        val w = whole.isSelected
        l.underlineStyle = false; l.underlineStyleWholeVerse = false; l.markerStyle = false; l.markerStyleWholeVerse = false
        l.hideStyle = false; l.hideStyleWholeVerse = false
        when (style.value) {
            "Underline" -> if (w) l.underlineStyleWholeVerse = true else l.underlineStyle = true
            "Marker" -> if (w) l.markerStyleWholeVerse = true else l.markerStyle = true
            "Hidden" -> if (w) l.hideStyleWholeVerse = true else l.hideStyle = true
        }
        Bookmarks.saveLabel(l)
        return l
    }
}

/** Assigns labels to a bookmark (AndBible's ManageLabels activity in "assign" mode). */
class LabelAssignDialog(private val app: MainApp, private val bookmarkId: String) {
    fun show() {
        val generic = Bookmarks.genericBookmark(bookmarkId) != null
        if (!generic && Bookmarks.bibleBookmark(bookmarkId) == null) return
        val selected = Bookmarks.labelIdsFor(bookmarkId, generic).toMutableSet()
        val dlg = Dialog<ButtonType>()
        dlg.initOwner(app.stage)
        dlg.title = "Assign labels"
        val box = VBox(6.0)
        val auto = CheckBox("Assign these labels automatically to new bookmarks").apply {
            isSelected = Settings.state.autoAssignLabels.isNotEmpty() && Settings.state.autoAssignLabels.toSet() == selected
        }
        fun rebuild() {
            box.children.setAll(Bookmarks.userLabels.sortedWith(compareBy({ !it.favourite }, { it.name.lowercase() })).map { l ->
                CheckBox(l.displayName).apply {
                    graphic = Rectangle(12.0, 12.0, argbToColor(l.color))
                    isSelected = l.id in selected
                    selectedProperty().addListener { _, _, v -> if (v) selected += l.id else selected -= l.id }
                }
            })
            if (box.children.isEmpty()) box.children += FxLabel("No labels yet — create one below.")
        }
        rebuild()
        val newBtn = Button("New label…").apply { setOnAction { LabelEditor.edit(app, null)?.let { selected += it.id; rebuild() } } }
        val scroll = javafx.scene.control.ScrollPane(box).apply { isFitToWidth = true; prefHeight = 320.0; prefWidth = 340.0 }
        dlg.dialogPane.content = VBox(10.0, scroll, HBox(8.0, newBtn).apply { alignment = Pos.CENTER_LEFT }, auto)
        dlg.dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return
        Bookmarks.setLabels(bookmarkId, selected.toList())
        selected.forEach { Settings.addRecentLabel(it) }
        Settings.state.autoAssignLabels = if (auto.isSelected) selected.toList() else emptyList()
        Settings.save()
        app.updateAllConfigs()
    }
}

@Suppress("unused")
private fun askText(app: MainApp, title: String, initial: String = ""): String? =
    TextInputDialog(initial).apply { initOwner(app.stage); this.title = title; headerText = null }.showAndWait().orElse(null)
