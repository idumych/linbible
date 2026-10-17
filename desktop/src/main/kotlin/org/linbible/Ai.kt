/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Integration of AndBible's AI assistant (compiled from AndBible's net.bible.service.llm sources) into LinBible:
 * running prompts (AgentForegroundService), the prompt selector (LlmDialogHelper), AI document data for BibleView
 * and the reactions to AndBible's AI events.
 */
package org.linbible

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label as FxLabel
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextArea
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.layout.VBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.bible.android.activity.R
import net.bible.android.control.event.ABEventBus
import net.bible.android.control.page.DocumentCategory
import net.bible.android.database.IdType
import net.bible.android.database.LogEntry
import net.bible.android.database.LogEntryTypes
import net.bible.android.database.mydocument.AiDocMarkerInfo
import net.bible.android.view.activity.base.Dialogs
import net.bible.android.view.activity.page.Selection
import net.bible.service.common.CommonUtils
import net.bible.service.db.DatabaseContainer
import net.bible.service.db.MyDocumentsUpdatedViaSyncEvent
import net.bible.service.llm.AgentPrompt
import net.bible.service.llm.LlmProvider
import net.bible.service.llm.PromptContext
import net.bible.service.llm.PromptRepository
import net.bible.service.llm.agent.AgentSessionManager
import net.bible.service.sword.mydocument.AiDocPagesChangedEvent
import net.bible.service.sword.mydocument.MyDocumentBookManager
import net.bible.service.sword.mydocument.MyDocumentUpdatedEvent
import net.bible.service.sword.mydocument.isMyDocument
import net.bible.service.sword.mydocument.myDocumentId
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.passage.Key
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.passage.VerseRange
import org.crosswire.jsword.versification.Versification

object Ai {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: MainApp

    val workspaceId: IdType get() = net.bible.android.control.page.window.WindowControl.instance.windowRepository.id

    fun init(app: MainApp) {
        this.app = app
        Dialogs.owner = app.stage
        runCatching { MyDocumentBookManager.registerAllDocuments() }.onFailure { Log.e("Registering AI documents failed", it) }

        ABEventBus.listen(AiDocPagesChangedEvent::class.java) { e ->
            Platform.runLater { app.windows.forEach { it.aiDocMarkersChanged(e) } }
        }
        ABEventBus.listen(MyDocumentUpdatedEvent::class.java) { e ->
            Platform.runLater {
                Content.clearCache()
                app.windows.filter { it.page.book?.initials == e.initials }.forEach { it.reload() }
            }
        }
        // AI documents changed by cloud sync (AndBible's reactToUpdates)
        Databases.onChanged(DbCategory.MYDOCUMENTS) {
            val entries = DbCategory.MYDOCUMENTS.db.query("SELECT tableName, entityId1, entityId2, type, lastUpdated, sourceDevice FROM LogEntry") {
                LogEntry(it.getString(1), IdType.fromByteArray(it.getBytes(2))!!, IdType.fromByteArray(it.getBytes(3)) ?: IdType.empty(),
                    LogEntryTypes.valueOf(it.getString(4)), it.getLong(5), it.getString(6))
            }
            Platform.runLater { ABEventBus.post(MyDocumentsUpdatedViaSyncEvent(entries)) }
        }
        Databases.onChanged(DbCategory.AI_SETTINGS) { Platform.runLater { app.updateAllConfigs() } }
    }

    // ---------------------------------------------------------------- running prompts (AgentForegroundService)

    fun executePrompt(prompt: AgentPrompt, selection: Selection, userSpecification: String? = null, modelOverrideId: IdType? = null) {
        AgentLogPanel.show(app)
        scope.launch {
            try {
                AgentSessionManager.executePrompt(prompt, selection, userSpecification = userSpecification, modelOverrideId = modelOverrideId)
            } catch (e: Exception) {
                Log.e("Agent execution failed", e)
            }
        }.also { AgentSessionManager.getOrCreateSession(workspaceId).job = it }
    }

    private fun startRegenerate(pageId: IdType, window: BibleWindow, instructions: String?, keepPrevious: Boolean, freshRun: Boolean, modelOverrideId: IdType?) {
        window.showMessage(Strings.get(R.string.ai_document_regenerating))
        AgentLogPanel.show(app)
        scope.launch {
            try {
                AgentSessionManager.regenerateAIDocument(pageId, targetWindowId = window.windowId, additionalInstructions = instructions,
                    keepPrevious = keepPrevious, freshRun = freshRun, modelOverrideId = modelOverrideId)
            } catch (e: Exception) {
                Log.e("Regeneration failed", e)
            }
        }.also { AgentSessionManager.getOrCreateSession(workspaceId).job = it }
    }

    // ---------------------------------------------------------------- prompt selector (LlmDialogHelper)

    private object Strings {
        fun get(id: Int, vararg args: Any?) = net.bible.android.BibleApplication.application.getString(id, *args)
    }

    fun showPromptSelector(selection: Selection, context: PromptContext = PromptContext.VERSE_SELECTION, documentCategory: DocumentCategory? = null) {
        if (!Llm.configured) {
            if (app.ask("AI assistant", "No AI provider is set up yet. Open the AI settings now?")) AiSettingsDialog.show(app)
            return
        }
        scope.launch {
            val grouped = PromptRepository.promptsForContextGrouped(context, documentCategory)
            val favoriteIds = PromptRepository.favoritePromptIds()
            Platform.runLater {
                val root = TreeItem<Any>("root")
                val all = grouped.values.flatten()
                val favorites = all.filter { it.id in favoriteIds }
                fun group(name: String, prompts: List<AgentPrompt>, expanded: Boolean) {
                    if (prompts.isEmpty()) return
                    root.children += TreeItem<Any>(name).apply { isExpanded = expanded; prompts.forEach { children += TreeItem<Any>(it) } }
                }
                group(Strings.get(R.string.prompt_category_favorites), favorites, true)
                grouped[null]?.let { group(Strings.get(R.string.prompt_category_uncategorized), it, true) }
                for ((category, prompts) in grouped) if (category != null) group(category.name, prompts, favorites.isEmpty() && root.children.size < 2)
                if (root.children.isEmpty()) { app.toast("No AI prompts for this context"); return@runLater }
                if (root.children.none { it.isExpanded }) root.children.first().isExpanded = true

                val tree = TreeView(root).apply {
                    isShowRoot = false
                    prefWidth = 520.0; prefHeight = 460.0
                    setCellFactory {
                        object : javafx.scene.control.TreeCell<Any>() {
                            override fun updateItem(item: Any?, empty: Boolean) {
                                super.updateItem(item, empty)
                                if (empty || item == null) { text = null; graphic = null; return }
                                if (item is AgentPrompt) {
                                    text = null
                                    graphic = VBox(1.0, FxLabel(item.name).apply { style = "-fx-font-weight: bold" },
                                        FxLabel(item.description ?: "").apply { opacity = 0.7; isWrapText = true; maxWidth = 440.0 })
                                } else { text = item.toString(); graphic = null; style = "-fx-font-weight: bold" }
                            }
                        }
                    }
                }
                val dlg = Dialog<ButtonType>().apply {
                    initOwner(app.stage); title = Strings.get(R.string.select_llm_prompt)
                    dialogPane.content = tree
                    dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
                }
                tree.setOnMouseClicked { e -> if (e.clickCount == 2 && tree.selectionModel.selectedItem?.value is AgentPrompt) {
                    (dlg.dialogPane.lookupButton(ButtonType.OK) as javafx.scene.control.Button).fire()
                } }
                if (dlg.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return@runLater
                val prompt = tree.selectionModel.selectedItem?.value as? AgentPrompt ?: return@runLater
                if (prompt.specifyBeforeRun) showSpecifyBeforeRunDialog(prompt, selection) else maybeAskModel(prompt, selection, null)
            }
        }
    }

    private fun showSpecifyBeforeRunDialog(prompt: AgentPrompt, selection: Selection) {
        val text = TextArea().apply { promptText = Strings.get(R.string.specify_before_run_hint); prefRowCount = 4; isWrapText = true }
        val dlg = Dialog<ButtonType>().apply {
            initOwner(app.stage); title = Strings.get(R.string.specify_before_run_title)
            dialogPane.content = text
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        }
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return
        val spec = text.text.trim().ifEmpty { return }
        maybeAskModel(prompt, selection, spec)
    }

    private fun maybeAskModel(prompt: AgentPrompt, selection: Selection, userSpecification: String?) {
        if (CommonUtils.aiSettings.askModelBeforeRun && prompt.configuredModelId == null) {
            chooseModel(allowSetDefault = true) { modelId, setDefault ->
                if (setDefault) scope.launch {
                    if (PromptRepository.isBuiltIn(prompt.id)) PromptRepository.setBuiltinPromptModelOverride(prompt.id, modelId)
                    else { prompt.configuredModelId = modelId; DatabaseContainer.instance.aiSettingsDb.agentPromptDao().update(prompt) }
                }
                executePrompt(prompt, selection, userSpecification, modelId)
            }
        } else executePrompt(prompt, selection, userSpecification)
    }

    /** Model selection list (showModelSelectionDialog). */
    fun chooseModel(allowSetDefault: Boolean, onChosen: (IdType, Boolean) -> Unit) {
        val db = DatabaseContainer.instance.aiSettingsDb
        val defaultModelId = CommonUtils.aiSettings.defaultModelId
        val models = db.llmConfiguredModelDao().all().sortedByDescending { it.id == defaultModelId }
        val providers = db.llmProviderConfigDao().all().associateBy { it.id }
        val list = ListView<String>().apply {
            items.setAll(models.map { m ->
                val provider = providers[m.providerConfigId]?.displayName ?: "?"
                "${if (LlmProvider.isModelSupported(m.modelId)) "✓ " else ""}${m.modelId} — $provider${if (m.id == defaultModelId) " ★" else ""}"
            })
            prefHeight = 260.0
            selectionModel.selectFirst()
        }
        val setDefault = CheckBox(Strings.get(R.string.set_default_model_for_prompt)).apply { isVisible = allowSetDefault }
        val dlg = Dialog<ButtonType>().apply {
            initOwner(app.stage); title = Strings.get(R.string.select_model_before_run_title)
            dialogPane.content = VBox(8.0, list, setDefault)
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        }
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return
        val i = list.selectionModel.selectedIndex.takeIf { it >= 0 } ?: return
        onChosen(models[i].id, setDefault.isSelected)
    }

    fun showRegenerateDialog(pageId: IdType, window: BibleWindow) {
        val text = TextArea().apply { promptText = Strings.get(R.string.ai_regenerate_instructions_hint); prefRowCount = 3; isWrapText = true }
        val keep = CheckBox(Strings.get(R.string.ai_regenerate_keep_previous))
        val fresh = CheckBox(Strings.get(R.string.ai_regenerate_fresh_run))
        val regen = ButtonType(Strings.get(R.string.ai_document_regenerate), javafx.scene.control.ButtonBar.ButtonData.OK_DONE)
        val dlg = Dialog<ButtonType>().apply {
            initOwner(app.stage); title = Strings.get(R.string.ai_regenerate_title)
            dialogPane.content = VBox(8.0, text, keep, fresh).apply { prefWidth = 480.0 }
            dialogPane.buttonTypes.addAll(regen, ButtonType.CANCEL)
        }
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) != regen) return
        val instructions = text.text.trim().ifEmpty { null }
        if (!CommonUtils.aiSettings.askModelBeforeRun) {
            startRegenerate(pageId, window, instructions, keep.isSelected, fresh.isSelected, null); return
        }
        val prompt = MyDocumentBookManager.getAIDocumentPage(pageId)?.sourcePromptId?.let { PromptRepository.promptById(it) }
        if (prompt?.configuredModelId != null) startRegenerate(pageId, window, instructions, keep.isSelected, fresh.isSelected, null)
        else chooseModel(allowSetDefault = false) { id, _ -> startRegenerate(pageId, window, instructions, keep.isSelected, fresh.isSelected, id) }
    }

    // ---------------------------------------------------------------- BibleView documents (ClientPageObjects)

    /** AI generation metadata of a My Document page (CurrentPageBase.currentPageContent). */
    fun myDocumentFields(book: Book, key: Key): JsonObject = buildJsonObject {
        val isMyDoc = book.isMyDocument
        val dao = DatabaseContainer.instance.myDocumentDb.myDocumentDao()
        val page = if (isMyDoc) book.myDocumentId?.let { id -> dao.pageByKey(id, key.osisRef?.takeIf { it.isNotEmpty() } ?: key.name) } else null
        val promptId = page?.sourcePromptId
        val cache = promptId?.let { dao.getCacheEntry(page.id) }
        val promptName = promptId?.let { PromptRepository.promptById(it)?.name ?: Strings.get(R.string.ai_unknown_prompt) }
        put("isMyDocument", isMyDoc)
        put("isAiDocument", book.initials == MyDocumentBookManager.AI_DOCUMENTS_INITIALS)
        put("myDocumentPageId", page?.id?.toString())
        put("sourcePromptId", promptId?.toString())
        put("sourcePromptName", promptName)
        put("sourceModelName", cache?.sourceModelName)
        put("aiDocMarkers", JsonArray(runCatching { dao.aiDocMarkersForPage(book.initials, key.osisRef) }.getOrDefault(emptyList()).map { markerJson(it, null) }))
    }

    fun bibleAiDocMarkers(kjvRange: VerseRange, v11n: Versification): JsonArray = JsonArray(runCatching {
        DatabaseContainer.instance.myDocumentDb.myDocumentDao().aiDocMarkersForRange(kjvRange.start.ordinal, kjvRange.end.ordinal)
    }.getOrDefault(emptyList()).map { markerJson(it, v11n) })

    /** ClientAiDocMarker */
    fun markerJson(m: AiDocMarkerInfo, v11n: Versification?): JsonObject {
        val start = m.kjvOrdinalStart?.let { if (v11n != null) Verse(KJVA, it).toV11n(v11n).ordinal else it }
        val end = m.kjvOrdinalEnd?.let { if (v11n != null) Verse(KJVA, it).toV11n(v11n).ordinal else it }
        val abbreviated = if (start != null && end != null) {
            val t = v11n ?: KJVA
            runCatching { VerseRange(t, Verse(t, start), Verse(t, end)).abbreviated }.getOrDefault("")
        } else ""
        val aiLabelId = net.bible.android.database.bookmarks.AI_DOC_LABEL_ID
        return buildJsonObject {
            put("id", m.pageId.toString())
            put("type", "ai-doc-marker")
            put("hashCode", kotlin.math.abs(m.pageId.hashCode()))
            put("ordinalRange", if (start != null && end != null) JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(start), kotlinx.serialization.json.JsonPrimitive(end))) else kotlinx.serialization.json.JsonNull)
            put("offsetRange", kotlinx.serialization.json.JsonNull)
            put("labels", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(aiLabelId))))
            put("primaryLabelId", aiLabelId)
            put("bookmarkToLabels", JsonArray(emptyList()))
            put("bookInitials", m.documentInitials)
            put("bookName", m.documentInitials)
            put("bookAbbreviation", m.documentInitials)
            put("createdAt", 0); put("lastUpdatedOn", 0)
            put("text", m.pageTitle); put("fullText", m.pageTitle)
            put("notes", kotlinx.serialization.json.JsonNull); put("notesContentType", kotlinx.serialization.json.JsonNull)
            put("hasNote", false); put("wholeVerse", true); put("customIcon", "robot")
            put("editAction", buildJsonObject { put("mode", kotlinx.serialization.json.JsonNull); put("content", kotlinx.serialization.json.JsonNull) })
            put("sourcePromptId", m.sourcePromptId?.toString())
            put("verseRangeAbbreviated", abbreviated)
            put("title", m.pageTitle)
            put("documentInitials", m.documentInitials)
            put("pageKey", m.pageKey)
            put("sourceBookInitials", m.sourceBookInitials)
            put("sourceBookKey", m.sourceBookKey)
        }
    }

    // ---------------------------------------------------------------- AI document pages (BibleJavascriptInterface)

    fun openAiDocPage(documentInitials: String, pageKey: String, from: BibleWindow?) {
        val book = org.crosswire.jsword.book.Books.installed().getBook(documentInitials) ?: return
        val key = runCatching { book.getKey(pageKey) }.getOrNull() ?: return
        app.openInLinks(Page.Keyed(book, key), from)
    }

    fun openAiDocPageChooser(markersJson: String, from: BibleWindow?) {
        val markers = json.parseToJsonElement(markersJson).jsonArray.map { it.jsonObject }
        if (markers.isEmpty()) return
        fun open(m: JsonObject) = openAiDocPage(m["documentInitials"]!!.jsonPrimitive.content, m["pageKey"]!!.jsonPrimitive.content, from)
        if (markers.size == 1) { open(markers[0]); return }
        val list = ListView<String>().apply { items.setAll(markers.map { it["title"]?.jsonPrimitive?.content ?: "" }); prefHeight = 240.0; selectionModel.selectFirst() }
        val dlg = Dialog<ButtonType>().apply {
            initOwner(app.stage); title = Strings.get(R.string.ai_doc_choose_page)
            dialogPane.content = list; dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        }
        list.setOnMouseClicked { if (it.clickCount == 2) (dlg.dialogPane.lookupButton(ButtonType.OK) as javafx.scene.control.Button).fire() }
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) list.selectionModel.selectedIndex.takeIf { it >= 0 }?.let { open(markers[it]) }
    }

    fun deleteAiDocPage(pageId: IdType, window: BibleWindow) {
        val ok = Alert(Alert.AlertType.CONFIRMATION, Strings.get(R.string.ai_document_delete_confirmation), ButtonType.YES, ButtonType.NO).apply {
            initOwner(app.stage); headerText = null
        }.showAndWait().orElse(ButtonType.NO) == ButtonType.YES
        if (!ok) return
        MyDocumentBookManager.deleteAIDocumentPage(pageId)
        if (app.windows.size > 1) app.closeWindow(window) else window.reload()
    }
}
