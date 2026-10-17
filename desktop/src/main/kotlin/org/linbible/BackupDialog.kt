/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * "Backup & restore" (AndBible's BackupActivity) and "Install from file" (AndBible's InstallZip).
 */
package org.linbible

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label as FxLabel
import javafx.scene.control.ListView
import javafx.scene.control.SelectionMode
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Stage
import org.crosswire.jsword.book.Book
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

object BackupDialog {
    private fun timestamp() = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())

    private fun saveChooser(app: MainApp, title: String, name: String): File? = FileChooser().apply {
        this.title = title
        initialFileName = name
        initialDirectory = File(System.getProperty("user.home")).takeIf { it.isDirectory }
    }.showSaveDialog(app.stage)

    fun show(app: MainApp) {
        val stage = Stage().apply { title = "Backup & restore"; initOwner(app.stage) }
        fun button(text: String, help: String, action: () -> Unit) = VBox(2.0,
            Button(text).apply { maxWidth = Double.MAX_VALUE; setOnAction { action() } },
            FxLabel(help).apply { isWrapText = true; opacity = 0.75 })
        val box = VBox(14.0,
            button("Back up app data…", "Bookmarks, labels, StudyPads, reading plan progress, reading progress and AI settings, " +
                "in AndBible's backup format (.abdb.zip).") { backupDatabases(app) },
            button("Restore or import a backup…", "Restore (replace) or import (merge) an AndBible or LinBible backup (.abdb.zip), " +
                "for example one made by AndBible on your phone.") { chooseAndInstall(app, listOf("*.abdb.zip", "*.zip")) },
            button("Export StudyPads…", "Share StudyPads with their bookmarks as a file AndBible and LinBible can import.") { exportStudyPads(app) },
            button("Back up documents…", "Save installed SWORD modules as an AndBible module backup (.abmd.zip).") { backupModules(app) },
            button("Install from file…", "Install modules (.zip, .abmd.zip), EPUB books, MyBible / MySword / e-Sword files, " +
                "fonts, background images, reading plans or AI prompt packs.") { chooseAndInstall(app) },
        ).apply { padding = Insets(16.0); prefWidth = 520.0 }
        stage.scene = Scene(box).also { styleLike(app, it) }
        stage.show()
    }

    private fun backupDatabases(app: MainApp) {
        val f = saveChooser(app, "Back up app data", "dbBackup-linbible-${timestamp()}${Backup.DATABASE_BACKUP_SUFFIX}") ?: return
        runTask(app, "Backing up…") {
            Backup.exportDatabases(f)
            Platform.runLater { app.info("Backup", "Backup saved to $f") }
        }
    }

    private fun exportStudyPads(app: MainApp) {
        val list = ListView<Label>().apply {
            items.setAll(Bookmarks.userLabels)
            selectionModel.selectionMode = SelectionMode.MULTIPLE
            setCellFactory { object : javafx.scene.control.ListCell<Label>() {
                override fun updateItem(item: Label?, empty: Boolean) { super.updateItem(item, empty); text = if (empty) null else item?.displayName }
            } }
            prefHeight = 300.0
        }
        val dlg = Dialog<ButtonType>().apply {
            initOwner(app.stage); title = "Export StudyPads"; headerText = "Choose the StudyPads (labels) to export"
            dialogPane.content = list
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        }
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return
        val labels = list.selectionModel.selectedItems.toList()
        if (labels.isEmpty()) return
        val name = if (labels.size > 1) "StudyPads" else labels.first().name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val f = saveChooser(app, "Export StudyPads", "$name${Backup.DATABASE_BACKUP_SUFFIX}") ?: return
        runTask(app, "Exporting…") {
            Backup.exportStudyPads(labels.map { it.id }, f)
            Platform.runLater { app.toast("StudyPads exported to $f") }
        }
    }

    private fun backupModules(app: MainApp) {
        val list = ListView<Book>().apply {
            items.setAll(Sword.installed.filter { it.bookMetaData.location != null }.sortedBy { it.name.lowercase() })
            selectionModel.selectionMode = SelectionMode.MULTIPLE
            setCellFactory { object : javafx.scene.control.ListCell<Book>() {
                override fun updateItem(item: Book?, empty: Boolean) { super.updateItem(item, empty); text = if (empty || item == null) null else "${item.initials} – ${item.name}" }
            } }
            prefHeight = 360.0
        }
        val all = CheckBox("All documents").apply { setOnAction { if (isSelected) list.selectionModel.selectAll() else list.selectionModel.clearSelection() } }
        val dlg = Dialog<ButtonType>().apply {
            initOwner(app.stage); title = "Back up documents"; headerText = "Choose the documents to back up"
            dialogPane.content = VBox(8.0, all, list)
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        }
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return
        val books = list.selectionModel.selectedItems.toList()
        if (books.isEmpty()) return
        val f = saveChooser(app, "Back up documents", "modules-${timestamp()}${Backup.MODULE_BACKUP_SUFFIX}") ?: return
        runTask(app, "Backing up documents…") {
            Backup.exportModules(books, f)
            Platform.runLater { app.toast("Documents saved to $f") }
        }
    }

    private fun runTask(app: MainApp, status: String, task: () -> Unit) {
        app.toast(status)
        app.executor.submit {
            try { task() } catch (e: Throwable) {
                Log.e("$status failed", e)
                Platform.runLater { app.info("Error", "${status.removeSuffix("…")} failed: ${e.message}") }
            }
        }
    }

    // ---------------------------------------------------------------- install from file

    fun chooseAndInstall(app: MainApp, patterns: List<String>? = null) {
        val chooser = FileChooser().apply {
            title = "Install from file"
            initialDirectory = File(System.getProperty("user.home")).takeIf { it.isDirectory }
            if (patterns != null) extensionFilters += FileChooser.ExtensionFilter("Backups", patterns)
            extensionFilters += FileChooser.ExtensionFilter("Supported files", "*.zip", "*.abdb.zip", "*.abmd.zip", "*.epub",
                "*.SQLite3", "*.sqlite3", "*.mybible", "*.bblx", "*.bbli", "*.ttf", "*.properties", "*.csv", "*.png", "*.jpg", "*.jpeg", "*.webp")
            extensionFilters += FileChooser.ExtensionFilter("All files", "*")
        }
        val f = chooser.showOpenDialog(app.stage) ?: return
        install(app, f)
    }

    /** Dispatches a file to the right installer, like AndBible's InstallZip.installFromFile. */
    fun install(app: MainApp, f: File) {
        val name = f.name.lowercase()
        try {
            when {
                name.endsWith(".properties") -> {
                    val target = File(ReadingPlans.userPlanDir.also { it.mkdirs() }, f.name)
                    if (target.exists() && !app.ask("Reading plan", "Replace the installed reading plan ${f.name}?")) return
                    f.copyTo(target, overwrite = true)
                    app.toast("Reading plan installed")
                }
                name.endsWith(".epub") -> runTask(app, "Installing ${f.name}…") {
                    OtherFormats.installFile(f)
                    Platform.runLater { app.toast("Installed ${f.name}") }
                }
                name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp") ||
                    name.endsWith(".ttf") || name.endsWith(".sqlite3") || name.endsWith(".mybible") || name.endsWith(".bblx") ||
                    name.endsWith(".bbli") || name.endsWith(".csv") -> {
                    if (OtherFormats.target(f).exists() && !app.ask("Install", "${f.name} is already installed. Replace it?")) return
                    runTask(app, "Installing ${f.name}…") {
                        OtherFormats.installFile(f)
                        Platform.runLater { app.toast("Installed ${f.name}") }
                    }
                }
                name.endsWith(".zip") -> installZip(app, f)
                else -> app.info("Install", "${f.name} is not a file type LinBible can install.")
            }
        } catch (e: Exception) {
            Log.e("Install of $f failed", e)
            app.info("Install failed", "Could not install ${f.name}: ${e.message}")
        }
    }

    private fun installZip(app: MainApp, f: File) {
        val manifestType = runCatching {
            java.util.zip.ZipFile(f).use { z ->
                z.getEntry(Backup.MANIFEST)?.let { e -> z.getInputStream(e).readBytes().decodeToString() }
            }?.let { json.decodeFromString(Backup.Manifest.serializer(), it).backupType }
        }.getOrNull()
        val hasDb = runCatching { java.util.zip.ZipFile(f).use { z -> z.entries().asSequence().any { it.name.startsWith("db/") } } }.getOrDefault(false)
        if (manifestType == "DB_BACKUP" || manifestType == "STUDYPAD_EXPORT" || hasDb) { restore(app, f); return }
        val existing = Backup.checkModuleZip(f)
        if (existing.isNotEmpty() && !app.ask("Install", "These files already exist and will be replaced:\n${existing.take(10).joinToString("\n")}" +
                if (existing.size > 10) "\n…" else "")) return
        runTask(app, "Installing ${f.name}…") {
            Backup.installModuleZip(f)
            Platform.runLater { app.toast("Installed ${f.name}") }
        }
    }

    private fun restore(app: MainApp, f: File) {
        val contents = Backup.open(f)
        try {
            if (contents.databases.isEmpty()) {
                app.info("Restore", "The backup does not contain any data LinBible can use.")
                return
            }
            if (contents.isStudyPadExport) {
                val db = contents.databases[DbCategory.BOOKMARKS] ?: return
                val (first, labels, bookmarks) = Backup.bookmarkStats(db)
                if (app.ask("Import StudyPads", "Import $labels StudyPad(s) (\"$first\"…) with $bookmarks bookmark(s)?")) {
                    Backup.import(DbCategory.BOOKMARKS, db)
                    app.toast("StudyPads imported")
                }
                return
            }
            var done = 0
            for ((c, db) in contents.databases) {
                val stats = if (c == DbCategory.BOOKMARKS) runCatching {
                    val (first, labels, bookmarks) = Backup.bookmarkStats(db); " ($bookmarks bookmarks, $labels labels, e.g. \"$first\")"
                }.getOrDefault("") else ""
                val restoreBtn = ButtonType("Restore (replace)", ButtonBar.ButtonData.YES)
                val importBtn = ButtonType("Import (merge)", ButtonBar.ButtonData.NO)
                val skipBtn = ButtonType("Skip", ButtonBar.ButtonData.CANCEL_CLOSE)
                val choice = Alert(Alert.AlertType.CONFIRMATION, "", restoreBtn, importBtn, skipBtn).apply {
                    initOwner(app.stage); title = "Restore backup"; headerText = c.title + stats
                    contentText = "Restore replaces your current ${c.title.lowercase()} with the backup. " +
                        "Import adds the items from the backup to your current ones."
                }.showAndWait().orElse(skipBtn)
                when (choice) {
                    restoreBtn -> if (app.ask("Restore", "Replace all your ${c.title.lowercase()}? This cannot be undone.")) {
                        Backup.restore(c, db); done++
                    }
                    importBtn -> { Backup.import(c, db); done++ }
                }
            }
            if (done > 0) app.toast("Backup restored")
        } catch (e: Exception) {
            Log.e("Restore failed", e)
            app.info("Restore failed", e.message ?: e.toString())
        } finally {
            contents.cleanup()
        }
    }
}
