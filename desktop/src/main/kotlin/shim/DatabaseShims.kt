/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * AndBible's DatabaseContainer, as far as its compiled AI and document code needs it. DAOs are implemented by
 * MiniRoom on top of LinBible's AndBible-format databases.
 */
@file:Suppress("unused")

package net.bible.service.db

import net.bible.android.database.mydocument.MyDocumentDao
import net.bible.service.llm.AgentPromptDao
import net.bible.service.llm.BuiltinPromptOverrideDao
import net.bible.service.llm.GlobalAiSettingsDao
import net.bible.service.llm.LlmConfiguredModelDao
import net.bible.service.llm.LlmProviderConfigDao
import net.bible.service.llm.LlmRawLogRecordDao
import net.bible.service.llm.LlmUsageRecordDao
import net.bible.service.llm.PromptCategoryDao
import org.linbible.DbCategory
import org.linbible.MiniRoom

class AiSettingsDb {
    private val db = { DbCategory.AI_SETTINGS.db }
    private val agentPrompt: AgentPromptDao = MiniRoom.dao(db)
    private val providers: LlmProviderConfigDao = MiniRoom.dao(db)
    private val models: LlmConfiguredModelDao = MiniRoom.dao(db)
    private val global: GlobalAiSettingsDao = MiniRoom.dao(db)
    private val usage: LlmUsageRecordDao = MiniRoom.dao(db)
    private val rawLog: LlmRawLogRecordDao = MiniRoom.dao(db)
    private val categories: PromptCategoryDao = MiniRoom.dao(db)
    private val overrides: BuiltinPromptOverrideDao = MiniRoom.dao(db)
    fun agentPromptDao() = agentPrompt
    fun llmProviderConfigDao() = providers
    fun llmConfiguredModelDao() = models
    fun globalAiSettingsDao() = global
    fun llmUsageRecordDao() = usage
    fun llmRawLogRecordDao() = rawLog
    fun promptCategoryDao() = categories
    fun builtinPromptOverrideDao() = overrides
}

class MyDocumentDb {
    private val dao: MyDocumentDao = MiniRoom.dao { DbCategory.MYDOCUMENTS.db }
    fun myDocumentDao() = dao
}

/** Posted after cloud sync changed AI documents (AndBible's DatabaseContainer event). */
class MyDocumentsUpdatedViaSyncEvent(val updated: List<net.bible.android.database.LogEntry>)
class AiSettingsUpdatedViaSyncEvent(val updated: List<net.bible.android.database.LogEntry>)

class BookmarkDb {
    private val dao: net.bible.android.database.bookmarks.BookmarkDao = MiniRoom.dao { DbCategory.BOOKMARKS.db }
    fun bookmarkDao() = dao
}

class DatabaseContainer {
    val aiSettingsDb = AiSettingsDb()
    val myDocumentDb = MyDocumentDb()
    val bookmarkDb = BookmarkDb()

    companion object {
        val instance: DatabaseContainer by lazy { DatabaseContainer() }
        val ready get() = true
    }
}
