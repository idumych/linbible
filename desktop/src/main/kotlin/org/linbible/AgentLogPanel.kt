/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Panel showing what the AI agent does (AndBible's AgentLogWidget): log entries of the running session with their
 * status, costs and details, and a Stop button.
 */
package org.linbible

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.Tooltip
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import net.bible.android.control.event.ABEventBus
import net.bible.service.common.CommonUtils
import net.bible.service.llm.agent.AgentLogEntry
import net.bible.service.llm.agent.AgentLogUpdatedEvent
import net.bible.service.llm.agent.AgentPermissionWaitingEvent
import net.bible.service.llm.agent.AgentSessionManager
import net.bible.service.llm.agent.AgentSessionStatusChangedEvent
import net.bible.service.llm.agent.AgentStopReason
import net.bible.service.llm.agent.EntryStatus
import net.bible.service.llm.agent.LogEntryType

object AgentLogPanel {
    private var panel: BorderPane? = null
    private val list = ListView<AgentLogEntry>()
    private val title = Label("AI assistant")
    private val stop = Button("Stop")
    private var app: MainApp? = null

    private fun icon(e: AgentLogEntry) = when {
        e.type == LogEntryType.ERROR || e.status == EntryStatus.FAILED -> "✗"
        e.type == LogEntryType.LLM_COMMENT -> "💬"
        e.type == LogEntryType.ACTION && e.status == EntryStatus.PENDING -> "⏳"
        e.type == LogEntryType.ACTION -> "✓"
        else -> "•"
    }

    fun install(app: MainApp, container: VBox) {
        this.app = app
        list.setCellFactory {
            object : ListCell<AgentLogEntry>() {
                override fun updateItem(item: AgentLogEntry?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (item == null || empty) { text = null; graphic = null; tooltip = null; return }
                    val cost = item.costInfo?.let { "   ($it)" } ?: ""
                    text = "${icon(item)}  ${item.message}$cost" + (item.details?.takeIf { it.isNotBlank() }?.let { "\n     ${it.replace("\n", " ").take(200)}" } ?: "")
                    tooltip = item.details?.let { Tooltip(it).apply { isWrapText = true; maxWidth = 600.0 } }
                    style = if (item.type == LogEntryType.ERROR) "-fx-text-fill: #d04040" else ""
                }
            }
        }
        list.prefHeight = 170.0
        stop.setOnAction { AgentSessionManager.stopAgent(Ai.workspaceId) }
        val close = Button("✕").apply { tooltip = Tooltip("Hide"); setOnAction { hide() } }
        val header = HBox(8.0, title, Region().also { HBox.setHgrow(it, Priority.ALWAYS) }, stop, close).apply {
            alignment = Pos.CENTER_LEFT; padding = Insets(2.0, 6.0, 2.0, 6.0); styleClass += "window-header"
        }
        panel = BorderPane(list, header, null, null, null).apply { isVisible = false; isManaged = false }
        container.children.add(0, panel)

        ABEventBus.listen(AgentLogUpdatedEvent::class.java) { Platform.runLater { refresh() } }
        ABEventBus.listen(AgentSessionStatusChangedEvent::class.java) { e ->
            Platform.runLater {
                refresh()
                stop.isDisable = !e.isRunning
                title.text = if (e.isRunning) "AI assistant – working…" else "AI assistant"
                if (!e.isRunning && e.stopReason == AgentStopReason.COMPLETED && CommonUtils.aiSettings.autoHideAgentLogOnCompletion) hide()
            }
        }
        ABEventBus.listen(AgentPermissionWaitingEvent::class.java) { e ->
            Platform.runLater { if (e.waiting) app.toast("The AI assistant is waiting for your permission") }
        }
    }

    private fun refresh() {
        val entries = AgentSessionManager.getLogEntries(Ai.workspaceId)
        list.items.setAll(entries)
        if (entries.isNotEmpty()) list.scrollTo(entries.size - 1)
        list.refresh()
    }

    fun show(app: MainApp) {
        val p = panel ?: return
        p.isVisible = true; p.isManaged = true
        refresh()
    }

    fun hide() {
        val p = panel ?: return
        p.isVisible = false; p.isManaged = false
    }

    fun toggle(app: MainApp) { if (panel?.isVisible == true) hide() else show(app) }
}
