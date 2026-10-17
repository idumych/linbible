/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
package org.linbible

import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.TableColumn
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.stage.Stage
import javafx.util.StringConverter
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.sword.SwordBook
import org.crosswire.jsword.index.IndexManagerFactory
import org.crosswire.jsword.index.search.SearchType
import org.crosswire.jsword.passage.Key
import org.crosswire.jsword.passage.Verse

class SearchDialog private constructor(private val app: MainApp) {
    private val stage = Stage()
    private val query = TextField()
    private val bible = ComboBox<SwordBook>()
    private val type = ComboBox<SearchType>()
    private val section = ComboBox<String>()
    private val status = Label()
    private val spinner = ProgressIndicator().apply { setPrefSize(18.0, 18.0); isVisible = false }

    data class Hit(val key: Key, val reference: String, val text: String)

    private val table = TableView<Hit>()

    init {
        stage.initOwner(app.stage)
        stage.title = "Search — LinBible"
        query.promptText = "Words, a phrase, or strong:G3056"
        query.setOnAction { search() }
        HBox.setHgrow(query, Priority.ALWAYS)

        bible.converter = object : StringConverter<SwordBook>() {
            override fun toString(b: SwordBook?) = b?.initials ?: ""
            override fun fromString(s: String?) = null
        }
        type.items.setAll(SearchType.ALL_WORDS, SearchType.ANY_WORDS, SearchType.PHRASE)
        type.value = SearchType.ALL_WORDS
        type.converter = object : StringConverter<SearchType>() {
            override fun toString(t: SearchType?) = when (t) {
                SearchType.ALL_WORDS -> "All words"; SearchType.ANY_WORDS -> "Any word"; SearchType.PHRASE -> "Phrase"; else -> ""
            }
            override fun fromString(s: String?) = null
        }
        section.items.setAll("Whole Bible", "Old Testament", "New Testament", "Current book")
        section.value = "Whole Bible"

        val go = Button("Search").apply { isDefaultButton = true; setOnAction { search() } }
        val top = HBox(8.0, query, bible, type, section, go).apply { alignment = Pos.CENTER_LEFT; padding = Insets(0.0, 0.0, 8.0, 0.0) }

        table.columns.addAll(
            TableColumn<Hit, String>("Reference").apply { prefWidth = 140.0; setCellValueFactory { SimpleStringProperty(it.value.reference) } },
            TableColumn<Hit, String>("Text").apply { prefWidth = 620.0; setCellValueFactory { SimpleStringProperty(it.value.text) } },
        )
        table.columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        table.placeholder = Label("No results")
        table.setRowFactory {
            object : TableRow<Hit>() {
                init { setOnMouseClicked { e -> if (e.clickCount == 2 && item != null) open(item) } }
            }
        }
        table.setOnKeyPressed { e -> if (e.code == javafx.scene.input.KeyCode.ENTER) table.selectionModel.selectedItem?.let { open(it) } }

        val bottom = HBox(8.0, spinner, status).apply { alignment = Pos.CENTER_LEFT; padding = Insets(6.0, 0.0, 0.0, 0.0) }
        val root = BorderPane(table, top, null, bottom, null).apply { padding = Insets(8.0) }
        stage.scene = Scene(root, 860.0, 600.0).also {
            it.stylesheets += app.stage.scene.stylesheets
            it.root.styleClass += app.stage.scene.root.styleClass
        }
        stage.setOnHidden { instance = null }
    }

    private fun refreshBibles() {
        bible.items.setAll(Sword.bibles)
        val current = app.activeWindow?.page?.book as? SwordBook
        bible.value = bible.items.firstOrNull { it == current && it.bookCategory == org.crosswire.jsword.book.BookCategory.BIBLE }
            ?: bible.value?.takeIf { it in bible.items } ?: bible.items.firstOrNull()
    }

    private fun open(hit: Hit) {
        val b = bible.value ?: return
        val v = when (val k = hit.key) {
            is Verse -> k
            else -> Page.parseVerse(b.versification, k.osisRef) ?: return
        }
        app.openVerse(v, org.crosswire.jsword.passage.VerseRange(v.versification, v, v), b)
    }

    private fun search() {
        val book = bible.value ?: run { status.text = "No Bible installed"; return }
        val text = query.text.trim()
        if (text.isEmpty()) return
        if (!IndexManagerFactory.getIndexManager().isIndexed(book)) {
            if (app.ask("Search index needed", "${book.initials} has no search index yet. Build it now? This takes a minute or two.")) {
                spinner.isVisible = true
                status.text = "Building search index for ${book.initials}…"
                buildIndex(app, book) { spinner.isVisible = false; search() }
            }
            return
        }
        val isStrongs = text.startsWith("strong:", true)
        val decorated = if (isStrongs) text else type.value.decorate(text)
        val restrict = when (section.value) {
            "Old Testament" -> "+[Gen-Mal] "
            "New Testament" -> "+[Mat-Rev] "
            "Current book" -> app.activeWindow?.page?.currentVerse?.let { "+[${it.book.osis}] " } ?: ""
            else -> ""
        }
        spinner.isVisible = true
        status.text = "Searching…"
        app.executor.submit {
            try {
                val result = book.find(restrict + decorated)
                val hits = ArrayList<Hit>()
                for (k in result) {
                    if (hits.size >= 2000) break
                    hits += Hit(k, k.name, Content.plainText(book, k))
                }
                val total = result.cardinality
                Log.i("Search \"$decorated\" in ${book.initials}: $total results")
                Platform.runLater {
                    spinner.isVisible = false
                    table.items.setAll(hits)
                    status.text = if (total > hits.size) "$total verses (showing first ${hits.size})" else "$total verses"
                }
            } catch (e: Exception) {
                Log.e("Search failed", e)
                Platform.runLater { spinner.isVisible = false; status.text = "Search failed: ${e.message}" }
            }
        }
    }

    companion object {
        private var instance: SearchDialog? = null

        fun show(app: MainApp, text: String?) {
            val d = instance ?: SearchDialog(app).also { instance = it }
            d.refreshBibles()
            if (text != null) d.query.text = text
            d.stage.show()
            d.stage.toFront()
            d.query.requestFocus()
            if (text != null) d.search()
        }

        private val indexing = HashSet<String>()

        fun buildIndex(app: MainApp, book: Book, done: () -> Unit) {
            if (!indexing.add(book.initials)) return
            app.toast("Building search index for ${book.initials}…")
            app.executor.submit {
                try {
                    IndexManagerFactory.getIndexManager().scheduleIndexCreation(book)
                } catch (e: Throwable) {
                    Log.e("Indexing failed", e)
                }
                Platform.runLater {
                    indexing.remove(book.initials)
                    app.toast("Search index for ${book.initials} ready")
                    done()
                }
            }
        }
    }
}
