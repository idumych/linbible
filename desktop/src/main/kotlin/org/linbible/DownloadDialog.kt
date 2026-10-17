/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Document download / management. Repositories are the same as AndBible's (res/raw/repositories.txt).
 */
package org.linbible

import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.SelectionMode
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.BookCategory
import org.crosswire.jsword.book.Books
import org.crosswire.jsword.book.sword.SwordBookMetaData
import org.crosswire.jsword.book.install.Installer
import org.crosswire.jsword.book.install.sword.HttpsSwordInstaller
import org.crosswire.jsword.index.IndexManagerFactory
import java.io.File
import java.util.Locale

object Repositories {
    data class Repo(val name: String, val installer: Installer)

    /** Same list and priority order as AndBible's RepoFactory. */
    val repos: List<Repo> by lazy {
        val defs = listOf(
            Triple("AndBible", "andbible.github.io", "/data/andbible/zip" to "/data/andbible"),
            Triple("CrossWire", "crosswire.org", "/ftpmirror/pub/sword/packages/rawzip" to "/ftpmirror/pub/sword/raw"),
            Triple("eBible", "ebible.org", "/sword/zip" to "/sword"),
            Triple("Lockman (CrossWire)", "crosswire.org", "/ftpmirror/pub/sword/lockmanpackages" to "/ftpmirror/pub/sword/lockmanraw"),
            Triple("Wycliffe (CrossWire)", "crosswire.org", "/ftpmirror/pub/sword/wycliffepackages/rawzip" to "/ftpmirror/pub/sword/wyclifferaw"),
            Triple("AndBible Extra", "andbible.github.io", "/andbible-extra/zip" to "/andbible-extra"),
            Triple("IBT", "ibtrussia.org", "/ftpmirror/pub/modsword/rawzip" to "/ftpmirror/pub/modsword/raw"),
            Triple("STEP Bible (Tyndale)", "public.modules.stepbible.org", "/packages" to "/catalog"),
            Triple("Crosswire Beta", "crosswire.org", "/ftpmirror/pub/sword/betapackages/rawzip" to "/ftpmirror/pub/sword/betaraw"),
        )
        defs.map { (name, host, dirs) ->
            Repo(name, HttpsSwordInstaller().apply {
                this.host = host
                packageDirectory = dirs.first
                catalogDirectory = dirs.second
            })
        }
    }

    private val acceptable = setOf(BookCategory.BIBLE, BookCategory.COMMENTARY, BookCategory.DICTIONARY, BookCategory.GENERAL_BOOK, BookCategory.MAPS)

    data class Available(val book: Book, val repo: String)

    /** Downloads catalogues (if needed) and returns the de-duplicated list of available documents. */
    fun available(refresh: Boolean, onRepoError: (String, Throwable) -> Unit): List<Available> {
        val result = LinkedHashMap<String, Available>()
        for (r in repos) {
            try {
                if (refresh || r.installer.books.isEmpty()) r.installer.reloadBookList()
                for (b in r.installer.books) {
                    if (b.bookCategory !in acceptable) continue
                    if (b.initials !in result) result[b.initials] = Available(b, r.name)
                }
            } catch (e: Throwable) {
                Log.w("Repository ${r.name} failed: ${e.message}")
                onRepoError(r.name, e)
            }
        }
        return result.values.sortedBy { it.book.name.lowercase() }
    }

    fun install(a: Available) {
        val repo = repos.first { it.name == a.repo }
        repo.installer.install(a.book)
    }
}

class DownloadDialog private constructor(private val app: MainApp) {
    private val stage = Stage()
    private val all = FXCollections.observableArrayList<Repositories.Available>()
    private val filtered = FilteredList(all)
    private val table = TableView(filtered)
    private val search = TextField()
    private val language = ComboBox<String>()
    private val category = ComboBox<String>()
    private val status = Label()
    private val spinner = ProgressIndicator().apply { setPrefSize(18.0, 18.0); isVisible = false }
    private val details = TextArea().apply { isEditable = false; isWrapText = true; prefRowCount = 6 }

    private val installedTable = TableView<Book>()
    private val tabs = TabPane()

    init {
        stage.initOwner(app.stage)
        stage.title = "Documents — LinBible"
        buildAvailable()
        buildInstalled()
        val root = BorderPane(tabs)
        root.padding = Insets(8.0)
        val scene = Scene(root, 900.0, 640.0)
        scene.stylesheets += app.stage.scene.stylesheets
        scene.root.styleClass += app.stage.scene.root.styleClass
        stage.scene = scene
        stage.setOnHidden { instance = null }
    }

    private fun col(title: String, width: Double, f: (Repositories.Available) -> String) =
        TableColumn<Repositories.Available, String>(title).apply {
            prefWidth = width
            setCellValueFactory { SimpleStringProperty(f(it.value)) }
        }

    private fun langName(b: Book): String = runCatching { b.language.name }.getOrNull() ?: b.language.code
    private fun catName(c: BookCategory): String = c.toString()
    private fun installed(b: Book) = Books.installed().getBook(b.initials) != null

    private fun buildAvailable() {
        table.columns.addAll(
            col("", 30.0) { if (installed(it.book)) "✓" else "" },
            col("Initials", 110.0) { it.book.initials },
            col("Name", 340.0) { it.book.name },
            col("Language", 120.0) { langName(it.book) },
            col("Type", 110.0) { catName(it.book.bookCategory) },
            col("Source", 120.0) { it.repo },
        )
        table.selectionModel.selectionMode = SelectionMode.MULTIPLE
        table.placeholder = Label("Press \"Refresh\" to load the list of available documents")
        table.selectionModel.selectedItemProperty().addListener { _, _, a -> details.text = a?.let { describe(it.book, it.repo) } ?: "" }
        table.setRowFactory {
            object : javafx.scene.control.TableRow<Repositories.Available>() {
                init { setOnMouseClicked { e -> if (e.clickCount == 2 && item != null) install(listOf(item)) } }
            }
        }

        search.promptText = "Filter by name or initials"
        search.textProperty().addListener { _, _, _ -> applyFilter() }
        language.promptText = "Language"
        language.setOnAction { applyFilter() }
        category.items.setAll(listOf("All types") + listOf(BookCategory.BIBLE, BookCategory.COMMENTARY, BookCategory.DICTIONARY, BookCategory.GENERAL_BOOK, BookCategory.MAPS).map { catName(it) })
        category.value = "All types"
        category.setOnAction { applyFilter() }

        val refresh = Button("Refresh").apply { setOnAction { load(refresh = true) } }
        val install = Button("Install selected").apply {
            isDefaultButton = true
            setOnAction { install(table.selectionModel.selectedItems.toList()) }
        }
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        HBox.setHgrow(search, Priority.ALWAYS)
        val top = HBox(8.0, search, language, category, refresh).apply { alignment = Pos.CENTER_LEFT; padding = Insets(0.0, 0.0, 8.0, 0.0) }
        val bottom = VBox(6.0, details, HBox(8.0, spinner, status, spacer, install).apply { alignment = Pos.CENTER_LEFT })
        bottom.padding = Insets(8.0, 0.0, 0.0, 0.0)
        val pane = BorderPane(table, top, null, bottom, null)
        tabs.tabs += Tab("Available", pane).apply { isClosable = false }
    }

    private fun buildInstalled() {
        fun c(title: String, w: Double, f: (Book) -> String) = TableColumn<Book, String>(title).apply {
            prefWidth = w; setCellValueFactory { SimpleStringProperty(f(it.value)) }
        }
        installedTable.columns.addAll(
            c("Initials", 110.0) { it.initials },
            c("Name", 360.0) { it.name },
            c("Language", 120.0) { langName(it) },
            c("Type", 110.0) { catName(it.bookCategory) },
            c("Search index", 100.0) { if (IndexManagerFactory.getIndexManager().isIndexed(it)) "yes" else "" },
        )
        installedTable.selectionModel.selectionMode = SelectionMode.MULTIPLE
        val info = TextArea().apply { isEditable = false; isWrapText = true; prefRowCount = 6 }
        installedTable.selectionModel.selectedItemProperty().addListener { _, _, b -> info.text = b?.let { describe(it, null) } ?: "" }
        val remove = Button("Uninstall selected").apply { setOnAction { uninstall(installedTable.selectionModel.selectedItems.toList()) } }
        val index = Button("Build search index").apply {
            setOnAction {
                installedTable.selectionModel.selectedItems.toList().forEach { b -> SearchDialog.buildIndex(app, b) { refreshInstalled() } }
            }
        }
        val folder = Button("Open module folder").apply { setOnAction { app.openBrowser(AppDirs.sword.toURI().toString()) } }
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        val bottom = VBox(6.0, info, HBox(8.0, folder, spacer, index, remove))
        bottom.padding = Insets(8.0, 0.0, 0.0, 0.0)
        tabs.tabs += Tab("Installed", BorderPane(installedTable, null, null, bottom, null)).apply {
            isClosable = false
            setOnSelectionChanged { if (isSelected) refreshInstalled() }
        }
        refreshInstalled()
    }

    private fun refreshInstalled() {
        installedTable.items.setAll(Sword.installed.sortedWith(compareBy({ it.bookCategory.ordinal }, { it.name.lowercase() })))
        table.refresh()
    }

    private fun describe(b: Book, repo: String?): String {
        val m = b.bookMetaData
        val about = (m.getProperty(SwordBookMetaData.KEY_ABOUT) ?: "").toString()
            .replace("\\par", "\n").replace(Regex("\\\\pard|\\\\qc|\\\\b0?|\\\\i0?"), "").trim()
        return buildString {
            appendLine("${b.name} (${b.initials})")
            append("Language: ${langName(b)}   Type: ${catName(b.bookCategory)}")
            m.getProperty("Version")?.let { append("   Version: $it") }
            repo?.let { append("   Source: $it") }
            appendLine()
            (m.getProperty("DistributionLicense") ?: m.getProperty(SwordBookMetaData.KEY_DISTRIBUTION_LICENSE))?.let { appendLine("License: $it") }
            if (about.isNotBlank()) { appendLine(); append(about) }
        }
    }

    private fun applyFilter() {
        val text = search.text.trim().lowercase()
        val lang = language.value.takeUnless { it == null || it == "All languages" }
        val cat = category.value.takeUnless { it == null || it == "All types" }
        filtered.setPredicate { a ->
            (text.isEmpty() || a.book.name.lowercase().contains(text) || a.book.initials.lowercase().contains(text)) &&
                (lang == null || langName(a.book) == lang) &&
                (cat == null || catName(a.book.bookCategory) == cat)
        }
        status.text = "${filtered.size} of ${all.size} documents"
    }

    fun load(refresh: Boolean) {
        spinner.isVisible = true
        status.text = "Loading document lists…"
        val errors = mutableListOf<String>()
        app.executor.submit {
            val list = Repositories.available(refresh) { name, _ -> synchronized(errors) { errors += name } }
            Platform.runLater {
                spinner.isVisible = false
                all.setAll(list)
                val prevLang = language.value
                val langs = list.map { langName(it.book) }.distinct().sorted()
                language.items.setAll(listOf("All languages") + langs)
                language.value = prevLang ?: Locale.getDefault().displayLanguage.takeIf { it in langs } ?: "All languages"
                applyFilter()
                if (errors.isNotEmpty()) status.text = status.text + " — unavailable: " + errors.joinToString()
            }
        }
    }

    private fun install(items: List<Repositories.Available>) {
        if (items.isEmpty()) return
        for (a in items) {
            status.text = "Installing ${a.book.initials}…"
            app.executor.submit {
                try {
                    Repositories.install(a)
                    Platform.runLater {
                        status.text = "Installed ${a.book.initials}"
                        app.toast("Installed ${a.book.name}")
                        table.refresh(); refreshInstalled()
                    }
                } catch (e: Throwable) {
                    Log.e("Install of ${a.book.initials} failed", e)
                    Platform.runLater { status.text = "Installing ${a.book.initials} failed: ${e.message}" }
                }
            }
        }
    }

    private fun uninstall(books: List<Book>) {
        if (books.isEmpty()) return
        if (!app.ask("Uninstall", "Uninstall ${books.joinToString { it.initials }}?")) return
        for (b in books) {
            try {
                runCatching { IndexManagerFactory.getIndexManager().deleteIndex(b) }
                b.driver.delete(b)
                KeyNavigation.invalidate(b)
            } catch (e: Exception) {
                Log.e("Uninstall failed", e)
                app.info("Uninstall failed", "Could not uninstall ${b.initials}: ${e.message}\n\nModule files are in ${File(AppDirs.sword, "modules")}")
            }
        }
        refreshInstalled()
    }

    companion object {
        private var instance: DownloadDialog? = null

        fun show(app: MainApp, initials: String?, installedTab: Boolean, cat: BookCategory?) {
            val d = instance ?: DownloadDialog(app).also { instance = it; it.load(refresh = false) }
            if (initials != null) d.search.text = initials
            if (cat != null) { d.category.value = d.catName(cat); d.applyFilter() }
            d.tabs.selectionModel.select(if (installedTab) 1 else 0)
            d.stage.show()
            d.stage.toFront()
        }
    }
}
