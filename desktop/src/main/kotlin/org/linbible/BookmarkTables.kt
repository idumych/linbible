/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Row mapping between LinBible's bookmark model and AndBible's bookmarks.sqlite3 tables (BookmarkEntities.kt).
 */
package org.linbible

object BookmarkTables {
    private val label = TableSpec("Label", listOf("id"), listOf("id", "name", "color", "markerStyle", "markerStyleWholeVerse",
        "underlineStyle", "underlineStyleWholeVerse", "hideStyle", "hideStyleWholeVerse", "favourite", "type", "customIcon"), setOf("id"))
    private val bible = TableSpec("BibleBookmark", listOf("id"), listOf("kjvOrdinalStart", "kjvOrdinalEnd", "ordinalStart",
        "ordinalEnd", "v11n", "playbackSettings", "id", "createdAt", "book", "startOffset", "endOffset", "primaryLabelId",
        "lastUpdatedOn", "wholeVerse", "type", "customIcon", "sourcePromptId", "editAction_mode", "editAction_content"),
        setOf("id", "primaryLabelId", "sourcePromptId"))
    private val bibleNotes = TableSpec("BibleBookmarkNotes", listOf("bookmarkId"),
        listOf("bookmarkId", "notes", "contentType", "sourcePromptId"), setOf("bookmarkId", "sourcePromptId"))
    private val bibleToLabel = TableSpec("BibleBookmarkToLabel", listOf("bookmarkId", "labelId"),
        listOf("bookmarkId", "labelId", "orderNumber", "indentLevel", "expandContent"), setOf("bookmarkId", "labelId"))
    private val generic = TableSpec("GenericBookmark", listOf("id"), listOf("id", "key", "createdAt", "bookInitials",
        "ordinalStart", "ordinalEnd", "startOffset", "endOffset", "primaryLabelId", "lastUpdatedOn", "wholeVerse",
        "playbackSettings", "customIcon", "sourcePromptId", "editAction_mode", "editAction_content"),
        setOf("id", "primaryLabelId", "sourcePromptId"))
    private val genericNotes = TableSpec("GenericBookmarkNotes", listOf("bookmarkId"),
        listOf("bookmarkId", "notes", "contentType", "sourcePromptId"), setOf("bookmarkId", "sourcePromptId"))
    private val genericToLabel = TableSpec("GenericBookmarkToLabel", listOf("bookmarkId", "labelId"),
        listOf("bookmarkId", "labelId", "orderNumber", "indentLevel", "expandContent"), setOf("bookmarkId", "labelId"))
    private val entry = TableSpec("StudyPadTextEntry", listOf("id"),
        listOf("id", "labelId", "orderNumber", "indentLevel", "contentType", "sourcePromptId"), setOf("id", "labelId", "sourcePromptId"))
    private val entryText = TableSpec("StudyPadTextEntryText", listOf("studyPadTextEntryId"),
        listOf("studyPadTextEntryId", "text"), setOf("studyPadTextEntryId"))

    /** Parents before children. */
    val specs = listOf(label, bible, generic, entry, bibleNotes, bibleToLabel, genericNotes, genericToLabel, entryText)

    fun toRows(d: BookmarkData): Map<String, List<Map<String, Any?>>> = mapOf(
        label.name to d.labels.map {
            mapOf("id" to it.id, "name" to it.name, "color" to it.color, "markerStyle" to it.markerStyle,
                "markerStyleWholeVerse" to it.markerStyleWholeVerse, "underlineStyle" to it.underlineStyle,
                "underlineStyleWholeVerse" to it.underlineStyleWholeVerse, "hideStyle" to it.hideStyle,
                "hideStyleWholeVerse" to it.hideStyleWholeVerse, "favourite" to it.favourite, "type" to it.type,
                "customIcon" to it.customIcon)
        },
        bible.name to d.bibleBookmarks.map {
            mapOf("kjvOrdinalStart" to it.kjvOrdinalStart, "kjvOrdinalEnd" to it.kjvOrdinalEnd, "ordinalStart" to it.ordinalStart,
                "ordinalEnd" to it.ordinalEnd, "v11n" to it.v11n, "playbackSettings" to it.playbackSettings, "id" to it.id,
                "createdAt" to it.createdAt, "book" to it.bookInitials, "startOffset" to it.startOffset, "endOffset" to it.endOffset,
                "primaryLabelId" to it.primaryLabelId, "lastUpdatedOn" to it.lastUpdatedOn, "wholeVerse" to it.wholeVerse,
                "type" to it.type, "customIcon" to it.customIcon, "sourcePromptId" to it.sourcePromptId,
                "editAction_mode" to it.editAction?.mode, "editAction_content" to it.editAction?.content)
        },
        bibleNotes.name to d.bibleBookmarks.filter { it.notes != null }.map {
            mapOf("bookmarkId" to it.id, "notes" to it.notes, "contentType" to it.notesContentType, "sourcePromptId" to it.notesSourcePromptId)
        },
        bibleToLabel.name to d.bibleBookmarkToLabels.map(::btlRow),
        generic.name to d.genericBookmarks.map {
            mapOf("id" to it.id, "key" to it.key, "createdAt" to it.createdAt, "bookInitials" to it.bookInitials,
                "ordinalStart" to it.ordinalStart, "ordinalEnd" to it.ordinalEnd, "startOffset" to it.startOffset,
                "endOffset" to it.endOffset, "primaryLabelId" to it.primaryLabelId, "lastUpdatedOn" to it.lastUpdatedOn,
                "wholeVerse" to it.wholeVerse, "playbackSettings" to it.playbackSettings, "customIcon" to it.customIcon,
                "sourcePromptId" to it.sourcePromptId, "editAction_mode" to it.editAction?.mode,
                "editAction_content" to it.editAction?.content)
        },
        genericNotes.name to d.genericBookmarks.filter { it.notes != null }.map {
            mapOf("bookmarkId" to it.id, "notes" to it.notes, "contentType" to it.notesContentType, "sourcePromptId" to it.notesSourcePromptId)
        },
        genericToLabel.name to d.genericBookmarkToLabels.map(::btlRow),
        entry.name to d.studyPadEntries.map {
            mapOf("id" to it.id, "labelId" to it.labelId, "orderNumber" to it.orderNumber, "indentLevel" to it.indentLevel,
                "contentType" to it.contentType, "sourcePromptId" to it.sourcePromptId)
        },
        entryText.name to d.studyPadEntries.map { mapOf("studyPadTextEntryId" to it.id, "text" to it.text) },
    )

    private fun btlRow(b: BookmarkToLabel) = mapOf("bookmarkId" to b.bookmarkId, "labelId" to b.labelId,
        "orderNumber" to b.orderNumber, "indentLevel" to b.indentLevel, "expandContent" to b.expandContent)

    private fun Map<String, Any?>.s(k: String) = this[k] as String?
    private fun Map<String, Any?>.i(k: String) = (this[k] as Number?)?.toInt()
    private fun Map<String, Any?>.l(k: String) = (this[k] as Number?)?.toLong()
    private fun Map<String, Any?>.b(k: String) = (this[k] as Number?)?.toInt() == 1
    private fun Map<String, Any?>.editAction() =
        if (s("editAction_mode") == null && s("editAction_content") == null) null
        else EditAction(s("editAction_mode"), s("editAction_content"))

    fun fromRows(r: Map<String, List<Map<String, Any?>>>): BookmarkData {
        val bNotes = r[bibleNotes.name]!!.associateBy { it.s("bookmarkId") }
        val gNotes = r[genericNotes.name]!!.associateBy { it.s("bookmarkId") }
        val texts = r[entryText.name]!!.associate { it.s("studyPadTextEntryId") to it.s("text") }
        return BookmarkData(
            labels = r[label.name]!!.map {
                Label(id = it.s("id")!!, name = it.s("name") ?: "", color = it.i("color") ?: 0, markerStyle = it.b("markerStyle"),
                    markerStyleWholeVerse = it.b("markerStyleWholeVerse"), underlineStyle = it.b("underlineStyle"),
                    underlineStyleWholeVerse = it.b("underlineStyleWholeVerse"), hideStyle = it.b("hideStyle"),
                    hideStyleWholeVerse = it.b("hideStyleWholeVerse"), favourite = it.b("favourite"),
                    customIcon = it.s("customIcon"), type = it.s("type"))
            }.toMutableList(),
            bibleBookmarks = r[bible.name]!!.map {
                val n = bNotes[it.s("id")]
                BibleBookmark(id = it.s("id")!!, kjvOrdinalStart = it.i("kjvOrdinalStart")!!, kjvOrdinalEnd = it.i("kjvOrdinalEnd")!!,
                    v11n = it.s("v11n")!!, ordinalStart = it.i("ordinalStart")!!, ordinalEnd = it.i("ordinalEnd")!!,
                    bookInitials = it.s("book"), startOffset = it.i("startOffset"), endOffset = it.i("endOffset"),
                    wholeVerse = it.b("wholeVerse"), notes = n?.s("notes"), createdAt = it.l("createdAt") ?: 0,
                    lastUpdatedOn = it.l("lastUpdatedOn") ?: 0, primaryLabelId = it.s("primaryLabelId"),
                    customIcon = it.s("customIcon"), editAction = it.editAction(), notesContentType = n?.s("contentType"),
                    notesSourcePromptId = n?.s("sourcePromptId"), playbackSettings = it.s("playbackSettings"),
                    type = it.s("type"), sourcePromptId = it.s("sourcePromptId"))
            }.toMutableList(),
            genericBookmarks = r[generic.name]!!.map {
                val n = gNotes[it.s("id")]
                GenericBookmark(id = it.s("id")!!, key = it.s("key")!!, bookInitials = it.s("bookInitials") ?: "",
                    ordinalStart = it.i("ordinalStart"), ordinalEnd = it.i("ordinalEnd"), startOffset = it.i("startOffset"),
                    endOffset = it.i("endOffset"), wholeVerse = it.b("wholeVerse"), notes = n?.s("notes"),
                    createdAt = it.l("createdAt") ?: 0, lastUpdatedOn = it.l("lastUpdatedOn") ?: 0,
                    primaryLabelId = it.s("primaryLabelId"), customIcon = it.s("customIcon"), editAction = it.editAction(),
                    notesContentType = n?.s("contentType"), notesSourcePromptId = n?.s("sourcePromptId"),
                    playbackSettings = it.s("playbackSettings"), sourcePromptId = it.s("sourcePromptId"))
            }.toMutableList(),
            bibleBookmarkToLabels = r[bibleToLabel.name]!!.map(::btl).toMutableList(),
            genericBookmarkToLabels = r[genericToLabel.name]!!.map(::btl).toMutableList(),
            studyPadEntries = r[entry.name]!!.map {
                StudyPadTextEntry(id = it.s("id")!!, labelId = it.s("labelId")!!, orderNumber = it.i("orderNumber")!!,
                    indentLevel = it.i("indentLevel") ?: 0, text = texts[it.s("id")] ?: "", contentType = it.s("contentType"),
                    sourcePromptId = it.s("sourcePromptId"))
            }.toMutableList(),
        )
    }

    private fun btl(it: Map<String, Any?>) = BookmarkToLabel(it.s("bookmarkId")!!, it.s("labelId")!!,
        it.i("orderNumber") ?: -1, it.i("indentLevel") ?: 0, it.b("expandContent"))
}
