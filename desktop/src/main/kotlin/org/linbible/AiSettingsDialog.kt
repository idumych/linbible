/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * AI settings (AndBible's AiSettingsActivity, AiProvidersActivity, AiModelsActivity and PromptEditActivity in one
 * window): disclaimer, providers with API keys and models, prompts, and general agent settings. Everything is stored
 * in AndBible's AI settings database through AndBible's own DAOs and repositories.
 */
package org.linbible

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label as FxLabel
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.PasswordField
import javafx.scene.control.ScrollPane
import javafx.scene.control.Spinner
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.BorderPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.bible.android.activity.R
import net.bible.android.database.IdType
import net.bible.service.common.CommonUtils
import net.bible.service.db.DatabaseContainer
import net.bible.service.llm.AgentPrompt
import net.bible.service.llm.ApiFormat
import net.bible.service.llm.DynamicModelService
import net.bible.service.llm.LlmConfiguredModel
import net.bible.service.llm.LlmCostTracker
import net.bible.service.llm.LlmProcessingService
import net.bible.service.llm.LlmProvider
import net.bible.service.llm.LlmProviderConfig
import net.bible.service.llm.PromptContext
import net.bible.service.llm.PromptRepository
import net.bible.service.llm.agent.PermissionMode
import net.bible.service.llm.getApiKey
import net.bible.service.llm.removeApiKey
import net.bible.service.llm.setApiKey

object AiSettingsDialog {
    private fun s(id: Int, vararg a: Any?) = net.bible.android.BibleApplication.application.getString(id, *a)
    private val db get() = DatabaseContainer.instance.aiSettingsDb
    private val io = CoroutineScope(Dispatchers.IO)
    private var stage: Stage? = null

    /** AndBible requires accepting the AI disclaimer before any provider is configured. */
    private fun ensureDisclaimer(app: MainApp): Boolean {
        if (CommonUtils.aiSettings.aiDisclaimerAccepted) return true
        val text = buildString {
            appendLine(s(R.string.ai_disclaimer_intro)); appendLine()
            appendLine(s(R.string.ai_disclaimer_approach)); appendLine()
            appendLine(s(R.string.ai_disclaimer_responsibility)); appendLine()
            listOf(R.string.ai_disclaimer_point1, R.string.ai_disclaimer_point2, R.string.ai_disclaimer_point3, R.string.ai_disclaimer_point4,
                R.string.ai_disclaimer_point5, R.string.ai_disclaimer_point6, R.string.ai_disclaimer_point7, R.string.ai_disclaimer_point8,
                R.string.ai_disclaimer_point9).forEach { appendLine("• " + s(it)); appendLine() }
        }
        val accept = ButtonType(s(R.string.ai_disclaimer_accept_button), javafx.scene.control.ButtonBar.ButtonData.OK_DONE)
        val dlg = Dialog<ButtonType>().apply {
            initOwner(app.stage); title = s(R.string.ai_disclaimer_dialog_title)
            dialogPane.content = ScrollPane(FxLabel(text).apply { isWrapText = true; maxWidth = 560.0; padding = Insets(8.0) }).apply { prefHeight = 420.0; prefWidth = 600.0; isFitToWidth = true }
            dialogPane.buttonTypes.addAll(accept, ButtonType.CANCEL)
        }
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) != accept) return false
        CommonUtils.aiSettings.aiDisclaimerAccepted = true
        return true
    }

    fun show(app: MainApp, tab: Int = 0) {
        if (!ensureDisclaimer(app)) return
        stage?.let { it.toFront(); return }
        val st = Stage().apply { title = s(R.string.ai_settings_sync_title); initOwner(app.stage) }
        stage = st
        st.setOnHidden { stage = null; app.updateAllConfigs() }
        val tabs = TabPane(
            Tab("Providers & models", providersPane(app, st)),
            Tab("Prompts", promptsPane(app, st)),
            Tab("General", generalPane(app)),
        ).apply { tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE; selectionModel.select(tab) }
        st.scene = Scene(tabs, 860.0, 620.0).also { styleLike(app, it) }
        st.show()
    }

    // ---------------------------------------------------------------- providers & models

    private fun providersPane(app: MainApp, owner: Stage): BorderPane {
        val providers = ListView<LlmProviderConfig>()
        val models = ListView<LlmConfiguredModel>()
        val info = FxLabel().apply { isWrapText = true; opacity = 0.8 }
        fun defaultId() = CommonUtils.aiSettings.defaultModelId
        fun reloadProviders() { providers.items.setAll(db.llmProviderConfigDao().all()) }
        fun reloadModels() {
            val p = providers.selectionModel.selectedItem
            models.items.setAll(if (p == null) emptyList() else db.llmConfiguredModelDao().getByProvider(p.id))
            info.text = "Total usage on all devices: ${LlmCostTracker.formatTokenCount(LlmCostTracker.getTotalUsage().totalTokens)} tokens, " +
                "~${LlmCostTracker.formatCost(LlmCostTracker.getTotalCost())}"
        }
        providers.setCellFactory { object : ListCell<LlmProviderConfig>() {
            override fun updateItem(item: LlmProviderConfig?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null else item.displayName + (if (item.getApiKey().isBlank()) "  (no API key)" else "")
            }
        } }
        models.setCellFactory { object : ListCell<LlmConfiguredModel>() {
            override fun updateItem(item: LlmConfiguredModel?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null else {
                    val usage = LlmCostTracker.getCumulativeUsage(item.id)
                    val cost = LlmCostTracker.getCumulativeCost(item.id)
                    val price = if (item.inputPricePerMillion > 0 || item.outputPricePerMillion > 0)
                        "  ${LlmCostTracker.formatPriceCompact(item.inputPricePerMillion)} / ${LlmCostTracker.formatPriceCompact(item.outputPricePerMillion)} per M tokens" else ""
                    "${if (item.id == defaultId()) "★ " else ""}${item.modelId}$price" +
                        (if (usage.totalTokens > 0) "\n    used ${LlmCostTracker.formatTokenCount(usage.totalTokens)} tokens, ~${LlmCostTracker.formatCost(cost)}" else "")
                }
            }
        } }
        providers.selectionModel.selectedItemProperty().addListener { _, _, _ -> reloadModels() }

        val addProvider = Button("Add provider…").apply { setOnAction { addProviderDialog(owner)?.let { reloadProviders(); providers.selectionModel.select(providers.items.indexOfFirst { p -> p.id == it }) } } }
        val editKey = Button("API key…").apply {
            setOnAction {
                val p = providers.selectionModel.selectedItem ?: return@setOnAction
                val key = PasswordField().apply { promptText = "API key" }
                val dlg = Dialog<ButtonType>().apply {
                    initOwner(owner); title = "API key for ${p.displayName}"
                    dialogPane.content = VBox(8.0, FxLabel("The key is stored in your desktop keyring."), key)
                    dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
                }
                if (dlg.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK && key.text.isNotBlank()) { p.setApiKey(key.text.trim()); providers.refresh() }
            }
        }
        val removeProvider = Button("Remove").apply {
            setOnAction {
                val p = providers.selectionModel.selectedItem ?: return@setOnAction
                if (!app.ask("Remove provider", "Remove ${p.displayName} and its models?")) return@setOnAction
                val modelIds = db.llmConfiguredModelDao().getByProvider(p.id).map { it.id }
                db.llmConfiguredModelDao().deleteByProvider(p.id)
                if (CommonUtils.aiSettings.defaultModelId in modelIds) CommonUtils.aiSettings.defaultModelId = db.llmConfiguredModelDao().all().firstOrNull()?.id
                db.llmProviderConfigDao().delete(p)
                p.removeApiKey()
                reloadProviders(); reloadModels()
            }
        }
        val addModel = Button("Add model…").apply { setOnAction {
            val p = providers.selectionModel.selectedItem ?: return@setOnAction
            addModelDialog(owner, p) { reloadModels() }
        } }
        val setDefault = Button("Set as default").apply { setOnAction {
            val m = models.selectionModel.selectedItem ?: return@setOnAction
            CommonUtils.aiSettings.defaultModelId = m.id; models.refresh()
        } }
        val test = Button("Test").apply { setOnAction {
            val m = models.selectionModel.selectedItem ?: return@setOnAction
            val p = providers.selectionModel.selectedItem ?: return@setOnAction
            info.text = "Testing ${m.modelId}…"
            io.launch {
                val r = runCatching { LlmProcessingService.testApiConnection(p.resolveProvider(), m.modelId, p.getApiKey()) }
                Platform.runLater { info.text = r.fold({ "${m.modelId} works." }, { "Test failed: ${it.message}" }) }
            }
        } }
        val removeModel = Button("Remove").apply { setOnAction {
            val m = models.selectionModel.selectedItem ?: return@setOnAction
            db.llmConfiguredModelDao().delete(m)
            if (CommonUtils.aiSettings.defaultModelId == m.id) CommonUtils.aiSettings.defaultModelId = db.llmConfiguredModelDao().all().firstOrNull()?.id
            reloadModels()
        } }
        reloadProviders(); providers.selectionModel.selectFirst(); reloadModels()
        val left = BorderPane(providers, FxLabel("Providers").apply { style = "-fx-font-weight: bold"; padding = Insets(0.0, 0.0, 4.0, 0.0) }, null,
            HBox(6.0, addProvider, editKey, removeProvider).apply { padding = Insets(6.0, 0.0, 0.0, 0.0) }, null).apply { prefWidth = 330.0 }
        val right = BorderPane(models, FxLabel("Models (★ = default)").apply { style = "-fx-font-weight: bold"; padding = Insets(0.0, 0.0, 4.0, 0.0) }, null,
            VBox(6.0, HBox(6.0, addModel, setDefault, test, removeModel), info).apply { padding = Insets(6.0, 0.0, 0.0, 0.0) }, null)
        return BorderPane(HBox(12.0, left, right).also { HBox.setHgrow(right, Priority.ALWAYS) }).apply { padding = Insets(12.0) }
    }

    private fun addProviderDialog(owner: Stage): IdType? {
        val type = ComboBox<LlmProvider>().apply {
            items.setAll(LlmProvider.entries)
            setCellFactory { object : ListCell<LlmProvider>() { override fun updateItem(item: LlmProvider?, empty: Boolean) { super.updateItem(item, empty); text = item?.displayName } } }
            buttonCell = cellFactory.call(null)
            value = LlmProvider.GEMINI
        }
        val name = TextField().apply { promptText = "Name" }
        val endpoint = TextField().apply { promptText = "https://host/v1" }
        val format = ComboBox<ApiFormat>().apply { items.setAll(ApiFormat.entries); value = ApiFormat.OPENAI }
        val key = PasswordField().apply { promptText = "API key" }
        val keyLink = javafx.scene.control.Hyperlink()
        fun update() {
            val custom = type.value == LlmProvider.CUSTOM
            name.isDisable = !custom; endpoint.isDisable = !custom; format.isDisable = !custom
            if (!custom) { name.text = type.value.displayName; endpoint.text = type.value.endpoint }
            keyLink.text = type.value.apiKeyUrl?.let { "Get an API key: $it" } ?: ""
        }
        keyLink.setOnAction { type.value.apiKeyUrl?.let { MainApp.instance?.openBrowser(it) } }
        type.setOnAction { update() }; update()
        val grid = GridPane().apply {
            hgap = 8.0; vgap = 8.0
            addRow(0, FxLabel("Provider"), type); addRow(1, FxLabel("Name"), name); addRow(2, FxLabel("Endpoint"), endpoint)
            addRow(3, FxLabel("API format"), format); addRow(4, FxLabel("API key"), key); add(keyLink, 1, 5)
        }
        val dlg = Dialog<ButtonType>().apply {
            initOwner(owner); title = "Add AI provider"
            dialogPane.content = grid; dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        }
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null
        val p = type.value
        val config = LlmProviderConfig(providerType = p.name, displayName = name.text.trim().ifEmpty { p.displayName },
            endpoint = if (p == LlmProvider.CUSTOM) endpoint.text.trim() else null, apiFormat = if (p == LlmProvider.CUSTOM) format.value else null,
            orderNumber = db.llmProviderConfigDao().getCount())
        db.llmProviderConfigDao().insert(config)
        if (key.text.isNotBlank()) config.setApiKey(key.text.trim())
        // AndBible's easy setup adds the provider's first recommended model and makes it the default if none is set
        p.models.firstOrNull()?.let { m ->
            val model = LlmConfiguredModel.create(config.id, m)
            db.llmConfiguredModelDao().insert(model)
            if (CommonUtils.aiSettings.defaultModelId == null) CommonUtils.aiSettings.defaultModelId = model.id
        }
        return config.id
    }

    private fun addModelDialog(owner: Stage, p: LlmProviderConfig, done: () -> Unit) {
        val box = ComboBox<String>().apply { isEditable = true; items.setAll(p.resolveAvailableModels()); prefWidth = 360.0 }
        val status = FxLabel()
        val refresh = Button("Fetch model list").apply {
            isDisable = !p.resolveProvider().supportsDynamicModels
            setOnAction {
                status.text = "Fetching…"
                io.launch {
                    val models = runCatching { DynamicModelService.fetchModels(p.resolveEndpoint(), p.getApiKey(), p.resolveProvider().name) }.getOrNull()
                    Platform.runLater {
                        if (models != null) { box.items.setAll(models.map { it.id }); status.text = "${models.size} models" } else status.text = "Could not fetch models"
                    }
                }
            }
        }
        val dlg = Dialog<ButtonType>().apply {
            initOwner(owner); title = "Add model to ${p.displayName}"
            dialogPane.content = VBox(8.0, box, HBox(8.0, refresh, status).apply { alignment = Pos.CENTER_LEFT })
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        }
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return
        val id = (box.editor.text.ifBlank { box.value ?: "" }).trim().ifEmpty { return }
        if (db.llmConfiguredModelDao().getByProvider(p.id).any { it.modelId == id }) return
        val model = LlmConfiguredModel.create(p.id, id, db.llmConfiguredModelDao().getByProvider(p.id).size)
        db.llmConfiguredModelDao().insert(model)
        if (CommonUtils.aiSettings.defaultModelId == null) CommonUtils.aiSettings.defaultModelId = model.id
        done()
    }

    // ---------------------------------------------------------------- prompts

    private fun promptsPane(app: MainApp, owner: Stage): BorderPane {
        val list = ListView<AgentPrompt>()
        fun reload() { list.items.setAll(PromptRepository.allPromptsIncludingHidden()) }
        list.setCellFactory { object : ListCell<AgentPrompt>() {
            override fun updateItem(item: AgentPrompt?, empty: Boolean) {
                super.updateItem(item, empty)
                if (empty || item == null) { text = null; graphic = null; return }
                val tags = buildList {
                    if (PromptRepository.isBuiltIn(item.id)) add("built-in")
                    if (PromptRepository.isAddon(item.id)) add("add-on: ${item.sourceModule}")
                    if (PromptRepository.isBuiltIn(item.id) && PromptRepository.isBuiltInPromptHidden(item.id)) add("hidden")
                    if (PromptRepository.isFavorite(item.id)) add("★")
                }
                text = null
                graphic = VBox(1.0, FxLabel(item.name + if (tags.isNotEmpty()) "  [${tags.joinToString()}]" else "").apply { style = "-fx-font-weight: bold" },
                    FxLabel(item.description ?: "").apply { opacity = 0.7; isWrapText = true; maxWidth = 720.0 })
            }
        } }
        reload()
        val sel = { list.selectionModel.selectedItem }
        val new = Button("New prompt…").apply { setOnAction { editPromptDialog(owner, null)?.let { reload() } } }
        val edit = Button("Edit…").apply { setOnAction {
            val p = sel() ?: return@setOnAction
            if (PromptRepository.isReadOnly(p.id)) {
                if (app.ask("Read-only prompt", "\"${p.name}\" can't be changed. Create an editable copy?")) {
                    PromptRepository.copyPrompt(p.id)?.let { id -> PromptRepository.promptById(id)?.let { copy -> editPromptDialog(owner, copy) } }
                    reload()
                }
            } else editPromptDialog(owner, p)?.let { reload() }
        } }
        val fav = Button("Favorite").apply { setOnAction { sel()?.let { PromptRepository.toggleFavorite(it.id); list.refresh() } } }
        val hide = Button("Hide / show").apply { setOnAction {
            val p = sel() ?: return@setOnAction
            if (PromptRepository.isBuiltIn(p.id)) { PromptRepository.setBuiltInPromptHidden(p.id, !PromptRepository.isBuiltInPromptHidden(p.id)); list.refresh() }
        } }
        val delete = Button("Delete").apply { setOnAction {
            val p = sel() ?: return@setOnAction
            if (PromptRepository.isReadOnly(p.id)) { app.toast("Built-in prompts can only be hidden"); return@setOnAction }
            if (app.ask("Delete prompt", "Delete \"${p.name}\"?")) { PromptRepository.deletePrompt(p); reload() }
        } }
        list.setOnMouseClicked { if (it.clickCount == 2) edit.fire() }
        return BorderPane(list, null, null, HBox(6.0, new, edit, fav, hide, delete).apply { padding = Insets(8.0, 0.0, 0.0, 0.0) }, null).apply { padding = Insets(12.0) }
    }

    fun editPrompt(app: MainApp, id: IdType) {
        val p = PromptRepository.promptById(id) ?: return
        if (PromptRepository.isReadOnly(id)) { show(app, 1); return }
        editPromptDialog(app.stage, p)
    }

    /** PromptEditActivity: returns the saved prompt id. */
    private fun editPromptDialog(owner: javafx.stage.Window, prompt: AgentPrompt?): IdType? {
        val p = prompt ?: AgentPrompt(showIn = setOf(PromptContext.VERSE_SELECTION))
        val name = TextField(p.name)
        val desc = TextField(p.description ?: "")
        val template = TextArea(p.promptTemplate).apply { prefRowCount = 10; isWrapText = true }
        val contexts = PromptContext.entries.associateWith { c -> CheckBox(c.name.lowercase().replace('_', ' ')).apply { isSelected = c in p.showIn } }
        val specify = CheckBox(s(R.string.prompt_edit_before_run)).apply { isSelected = p.specifyBeforeRun }
        val noDoc = CheckBox("Don't create a document (actions only)").apply { isSelected = p.noDocumentCreation }
        val transform = CheckBox("Text transformation (no tools, keeps formatting)").apply { isSelected = p.isTextTransformation }
        val bibleOnly = CheckBox("Only for Bible windows").apply { isSelected = p.bibleOnly }
        val docs = CheckBox("Include list of installed documents").apply { isSelected = p.autoIncludeDocuments }
        val comms = CheckBox("Include commentaries on the selected verses").apply { isSelected = p.autoIncludeCommentaries }
        val strict = CheckBox("Cache results per translation and selected text").apply { isSelected = p.strictContextMatching }
        val models = listOf<LlmConfiguredModel?>(null) + db.llmConfiguredModelDao().all()
        val model = ComboBox<LlmConfiguredModel?>().apply {
            items.setAll(models)
            setCellFactory { object : ListCell<LlmConfiguredModel?>() { override fun updateItem(item: LlmConfiguredModel?, empty: Boolean) { super.updateItem(item, empty); text = if (empty) null else item?.modelId ?: "(default model)" } } }
            buttonCell = cellFactory.call(null)
            value = models.firstOrNull { it?.id == p.configuredModelId }
        }
        val categories = listOf<net.bible.service.llm.PromptCategory?>(null) + PromptRepository.userCategories()
        val category = ComboBox<net.bible.service.llm.PromptCategory?>().apply {
            items.setAll(categories)
            setCellFactory { object : ListCell<net.bible.service.llm.PromptCategory?>() { override fun updateItem(item: net.bible.service.llm.PromptCategory?, empty: Boolean) { super.updateItem(item, empty); text = if (empty) null else item?.name ?: "(none)" } } }
            buttonCell = cellFactory.call(null)
            value = categories.firstOrNull { it?.id == p.categoryId }
        }
        val newCategory = Button("New category…").apply { setOnAction {
            val n = javafx.scene.control.TextInputDialog().apply { initOwner(owner); headerText = "Category name" }.showAndWait().orElse(null)?.trim() ?: return@setOnAction
            if (n.isEmpty()) return@setOnAction
            val c = net.bible.service.llm.PromptCategory(name = n, orderNumber = categories.size)
            PromptRepository.insertCategory(c)
            category.items.add(c); category.value = c
        } }
        val grid = GridPane().apply {
            hgap = 8.0; vgap = 8.0
            addRow(0, FxLabel("Name"), name); addRow(1, FxLabel("Description"), desc)
            addRow(2, FxLabel("Prompt"), template)
            addRow(3, FxLabel("Show in"), VBox(4.0, *contexts.values.toTypedArray()))
            addRow(4, FxLabel("Options"), VBox(4.0, specify, noDoc, transform, bibleOnly, docs, comms, strict))
            addRow(5, FxLabel("Model"), model)
            addRow(6, FxLabel("Category"), HBox(8.0, category, newCategory))
            GridPane.setHgrow(template, Priority.ALWAYS)
        }
        val dlg = Dialog<ButtonType>().apply {
            initOwner(owner); title = if (prompt == null) "New prompt" else "Edit prompt"
            dialogPane.content = ScrollPane(grid).apply { isFitToWidth = true; prefWidth = 720.0; prefHeight = 600.0 }
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        }
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null
        p.name = name.text.trim().ifEmpty { "Prompt" }
        p.description = desc.text.trim().ifEmpty { null }
        p.promptTemplate = template.text
        p.showIn = contexts.filterValues { it.isSelected }.keys
        p.specifyBeforeRun = specify.isSelected; p.noDocumentCreation = noDoc.isSelected; p.isTextTransformation = transform.isSelected
        p.bibleOnly = bibleOnly.isSelected; p.autoIncludeDocuments = docs.isSelected; p.autoIncludeCommentaries = comms.isSelected
        p.strictContextMatching = strict.isSelected
        p.configuredModelId = model.value?.id
        p.categoryId = category.value?.id
        if (prompt == null) PromptRepository.insertPrompt(p) else PromptRepository.updatePrompt(p)
        return p.id
    }

    // ---------------------------------------------------------------- general

    private fun generalPane(app: MainApp): ScrollPane {
        val ai = CommonUtils.aiSettings
        val language = TextField(ai.aiLanguage ?: "").apply { promptText = s(R.string.ai_language_app_default, ai.aiDisplayLanguage) }
        val mode = ComboBox<PermissionMode>().apply { items.setAll(PermissionMode.entries); value = ai.agentPermissionMode }
        val iterations = Spinner<Int>(0, 200, ai.maxIterations)
        val askModel = CheckBox(s(R.string.ask_model_before_run_title)).apply { isSelected = ai.askModelBeforeRun }
        val autoHide = CheckBox("Hide the AI activity log when a task completes").apply { isSelected = ai.autoHideAgentLogOnCompletion }
        val commentaryTokens = Spinner<Int>(1000, 200000, ai.commentaryMaxResponseTokens, 1000)
        val excluded = TextField(ai.aiExcludedDocuments.joinToString(", ")).apply { promptText = "Document initials, comma separated" }
        val systemPrompt = TextArea(ai.customAgentSystemPrompt ?: "").apply { prefRowCount = 5; isWrapText = true; promptText = "Built-in agent system prompt" }
        val save = Button("Save").apply {
            setOnAction {
                ai.aiLanguage = language.text.trim().ifEmpty { null }
                ai.agentPermissionMode = mode.value
                ai.maxIterations = iterations.value
                ai.askModelBeforeRun = askModel.isSelected
                ai.autoHideAgentLogOnCompletion = autoHide.isSelected
                ai.commentaryMaxResponseTokens = commentaryTokens.value
                ai.aiExcludedDocuments = excluded.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                ai.customAgentSystemPrompt = systemPrompt.text.trim().ifEmpty { null }
                app.toast("AI settings saved")
            }
        }
        val grid = GridPane().apply {
            hgap = 10.0; vgap = 10.0; padding = Insets(12.0)
            addRow(0, FxLabel(s(R.string.ai_language_title)), language)
            addRow(1, FxLabel("Tool permissions"), mode)
            addRow(2, FxLabel("Max. agent steps (0 = unlimited)"), iterations)
            addRow(3, FxLabel(""), askModel)
            addRow(4, FxLabel(""), autoHide)
            addRow(5, FxLabel("Commentary size limit (tokens)"), commentaryTokens)
            addRow(6, FxLabel("Documents the AI must not read"), excluded)
            addRow(7, FxLabel("Custom agent system prompt"), systemPrompt)
            add(save, 1, 8)
            GridPane.setHgrow(systemPrompt, Priority.ALWAYS)
        }
        return ScrollPane(grid).apply { isFitToWidth = true }
    }
}
