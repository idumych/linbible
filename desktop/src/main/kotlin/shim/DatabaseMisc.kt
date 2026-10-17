/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
@file:Suppress("unused")

package net.bible.android.database

import kotlinx.serialization.json.Json

/** AndBible's shared Json (WorkspaceEntities.kt), used by its type converters. */
val json = Json {
    allowStructuredMapKeys = true
    encodeDefaults = true
}

/** The part of AndBible's WorkspaceEntities that its bookmark entities refer to. */
class WorkspaceEntities {
    data class WorkspaceLabelOverride(
        val workspaceId: IdType,
        val labelId: IdType,
        val overrideMode: Int? = null,
    ) {
        val hasOverride: Boolean get() = overrideMode != null
        companion object {
            const val MODE_HIGHLIGHT = 0
            const val MODE_UNDERLINE = 1
            const val MODE_MARKER = 2
            const val MODE_HIDDEN = 3
        }
    }
}

enum class LogEntryTypes { UPSERT, DELETE }

/** A row of the sync change log (SyncableRoomDatabase.kt). */
data class LogEntry(
    val tableName: String,
    val entityId1: IdType,
    val entityId2: IdType,
    val type: LogEntryTypes,
    val lastUpdated: Long,
    val sourceDevice: String,
)
