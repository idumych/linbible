/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Cloud sync settings (AndBible's SyncSettingsActivity with the Nextcloud account settings).
 */
package org.linbible

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Label as FxLabel
import javafx.scene.control.PasswordField
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Stage
import java.text.DateFormat
import java.util.Date

object SyncDialog {
    /** Installs the question callback and the periodic sync (AndBible's MainBibleActivity.periodicSync). */
    fun setup(app: MainApp) {
        CloudSync.ask = { title, message, options ->
            System.getenv("LINBIBLE_SYNC_ANSWER")?.toIntOrNull() ?: CloudSync.onUi {
                val buttons = options.mapIndexed { i, o ->
                    ButtonType(o, if (i == options.lastIndex) ButtonBar.ButtonData.CANCEL_CLOSE else ButtonBar.ButtonData.OTHER)
                }
                val r = Alert(Alert.AlertType.CONFIRMATION, message, *buttons.toTypedArray()).apply {
                    initOwner(app.stage); this.title = title; headerText = null
                }.showAndWait().orElse(null)
                r?.let { buttons.indexOf(it) }?.takeIf { it >= 0 }
            }
        }
        CloudSync.statusListeners += { running, msg ->
            Platform.runLater {
                when {
                    running -> app.toast(msg ?: "Synchronizing…")
                    msg != null -> app.toast("Sync: $msg")
                }
            }
        }
        val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sync").apply { isDaemon = true } }
        timer.schedule({ syncIfEnabled(force = true) }, 5, java.util.concurrent.TimeUnit.SECONDS)
        timer.scheduleWithFixedDelay({ syncIfEnabled(force = false) }, 60, 60, java.util.concurrent.TimeUnit.SECONDS)
    }

    private const val SYNC_INTERVAL = 5 * 60 * 1000L

    fun anyEnabled() = CloudSync.syncable.any { Settings.isSyncEnabled(it) }

    @Volatile var lastTouched: Long = System.currentTimeMillis()

    fun syncIfEnabled(force: Boolean) {
        if (!Settings.state.syncAutomatically || !anyEnabled() || !CloudSync.configured()) return
        try {
            val now = System.currentTimeMillis()
            if (force || (now - maxOf(Settings.state.lastSynchronized, lastTouched) > SYNC_INTERVAL && CloudSync.hasChanges())) {
                CloudSync.synchronize()
            }
        } catch (e: Throwable) {
            Log.e("Periodic sync failed", e)
        }
    }

    /** Before quitting: upload local changes (bounded wait). */
    fun syncBeforeExit() {
        if (!Settings.state.syncAutomatically || !anyEnabled() || !CloudSync.configured()) return
        if (!runCatching { CloudSync.hasChanges() }.getOrDefault(false)) return
        val t = Thread { CloudSync.synchronize() }.apply { isDaemon = true; start() }
        t.join(20_000)
    }

    fun show(app: MainApp) {
        val s = Settings.state
        val stage = Stage().apply { title = "Cloud sync"; initOwner(app.stage) }
        val url = TextField(s.syncServerUrl ?: "").apply { promptText = "https://cloud.example.org" }
        val user = TextField(s.syncUsername ?: "")
        val password = PasswordField().apply { promptText = if (CloudSync.configured()) "(saved)" else "App password" }
        val folder = TextField(s.syncFolderPath ?: "").apply { promptText = "Optional, e.g. AndBible" }
        val status = FxLabel().apply { isWrapText = true }
        val grid = GridPane().apply {
            hgap = 8.0; vgap = 8.0
            addRow(0, FxLabel("Nextcloud server"), url)
            addRow(1, FxLabel("User name"), user)
            addRow(2, FxLabel("App password"), password)
            addRow(3, FxLabel("Folder"), folder)
            url.prefColumnCount = 28
        }
        val help = FxLabel("Use the same Nextcloud account and folder as in AndBible on your phone (Settings → Cloud sync → " +
            "Nextcloud). Create an app password in Nextcloud under Settings → Security.").apply { isWrapText = true; opacity = 0.75 }

        val checks = CloudSync.syncable.map { c ->
            CheckBox().apply {
                isSelected = Settings.isSyncEnabled(c)
                fun label() {
                    val last = CloudSync.lastSynchronized(c)?.takeIf { it > 0 }?.let {
                        " (last synchronized ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))})"
                    } ?: ""
                    text = c.title + last
                }
                label()
                setOnAction {
                    if (!isSelected) { Settings.setSyncEnabled(c, false); return@setOnAction }
                    if (!CloudSync.configured()) { isSelected = false; status.text = "Sign in first."; return@setOnAction }
                    Settings.setSyncEnabled(c, true)
                    status.text = "Synchronizing…"
                    app.executor.submit {
                        val ok = CloudSync.synchronize()
                        Platform.runLater {
                            isSelected = Settings.isSyncEnabled(c); label()
                            status.text = if (ok) "Synchronized." else "Synchronization had problems; see the status bar."
                        }
                    }
                }
            }
        }
        val auto = CheckBox("Synchronize automatically").apply {
            isSelected = s.syncAutomatically
            setOnAction { s.syncAutomatically = isSelected; Settings.save() }
        }

        val signIn = Button("Sign in").apply {
            isDefaultButton = true
            setOnAction {
                val u = url.text.trim(); val n = user.text.trim()
                if (u.isEmpty() || n.isEmpty()) { status.text = "Enter the server and user name."; return@setOnAction }
                val pw = password.text.ifEmpty { Secrets.load("$n@$u") ?: "" }
                status.text = "Connecting…"
                app.executor.submit {
                    try {
                        WebDavAdapter(u, n, pw, folder.text.trim()).verify()
                        Secrets.store("$n@$u", pw)
                        s.syncServerUrl = u; s.syncUsername = n; s.syncFolderPath = folder.text.trim().ifEmpty { null }
                        Settings.save()
                        CloudSync.signIn()
                        Platform.runLater { status.text = "Signed in. Choose what to synchronize."; password.clear(); password.promptText = "(saved)" }
                    } catch (e: Exception) {
                        Log.e("Sign in failed", e)
                        Platform.runLater { status.text = "Sign in failed: ${e.message}" }
                    }
                }
            }
        }
        val syncNow = Button("Synchronize now").apply {
            setOnAction {
                status.text = "Synchronizing…"
                app.executor.submit {
                    val ok = runCatching { CloudSync.synchronize() }.getOrDefault(false)
                    Platform.runLater { status.text = if (ok) "Synchronized." else "Synchronization failed or nothing is enabled." }
                }
            }
        }
        val signOut = Button("Sign out").apply {
            setOnAction {
                if (!app.ask("Sign out", "Stop synchronizing and forget the account on this computer? Data on this computer and in the cloud is kept.")) return@setOnAction
                s.syncUsername?.let { n -> s.syncServerUrl?.let { u -> Secrets.clear("$n@$u") } }
                CloudSync.signOut()
                s.syncServerUrl = null; s.syncUsername = null; Settings.save()
                checks.forEach { it.isSelected = false }
                status.text = "Signed out."
            }
        }
        val box = VBox(12.0, grid, help, HBox(8.0, signIn, signOut).apply { alignment = Pos.CENTER_LEFT },
            FxLabel("Synchronize").apply { style = "-fx-font-weight: bold" }, VBox(6.0, *checks.toTypedArray()), auto,
            HBox(8.0, syncNow), status).apply { padding = Insets(16.0); prefWidth = 560.0 }
        stage.scene = Scene(box).also { styleLike(app, it) }
        stage.show()
    }
}
