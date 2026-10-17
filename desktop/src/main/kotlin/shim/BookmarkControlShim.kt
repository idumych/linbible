/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * The part of AndBible's BookmarkControl that its AI tools use, ported from BookmarkControl.kt. It works directly on
 * the bookmark database with AndBible's own BookmarkDao (through MiniRoom); after every change LinBible's in-memory
 * bookmark model is reloaded and the open windows are refreshed.
 */
@file:Suppress("unused")

package net.bible.android.control.bookmark

import net.bible.android.database.IdType
import net.bible.android.database.bookmarks.BookmarkDao
import net.bible.android.database.bookmarks.BookmarkEntities.BaseBookmarkToLabel
import net.bible.android.database.bookmarks.BookmarkEntities.BaseBookmarkWithNotes
import net.bible.android.database.bookmarks.BookmarkEntities.BibleBookmarkToLabel
import net.bible.android.database.bookmarks.BookmarkEntities.BibleBookmarkWithNotes
import net.bible.android.database.bookmarks.BookmarkEntities.GenericBookmarkToLabel
import net.bible.android.database.bookmarks.BookmarkEntities.GenericBookmarkWithNotes
import net.bible.android.database.bookmarks.BookmarkEntities.Label
import net.bible.android.database.bookmarks.BookmarkEntities.StudyPadTextEntryText
import net.bible.android.database.bookmarks.BookmarkEntities.StudyPadTextEntryWithText
import net.bible.android.database.bookmarks.AI_LABEL_ID
import net.bible.android.database.bookmarks.AI_LABEL_NAME
import net.bible.android.database.bookmarks.BookmarkStyle
import net.bible.android.database.bookmarks.TextContentType
import net.bible.android.database.bookmarks.UNLABELED_LABEL_ID
import net.bible.android.database.bookmarks.UNLABELED_NAME
import net.bible.android.misc.OsisFragment
import net.bible.service.sword.SwordContentFacade
import org.crosswire.jsword.book.sword.SwordBook
import org.crosswire.jsword.passage.NoSuchKeyException
import org.linbible.Bookmarks
import org.linbible.DbCategory
import org.linbible.MiniRoom
import org.linbible.OsisError
import org.linbible.Sword
import net.bible.android.common.toV11n

class BookmarkToLabelAddedOrUpdatedEvent(val bookmarkToLabel: BaseBookmarkToLabel)

class BookmarkControl {
    val dao: BookmarkDao = MiniRoom.dao { DbCategory.BOOKMARKS.db }

    init {
        // AI tools that write bookmark-label links directly announce it with this event
        net.bible.android.control.event.ABEventBus.listen(BookmarkToLabelAddedOrUpdatedEvent::class.java) { changed() }
    }

    /** LinBible's bookmark model caches the database; reload it and refresh windows after changes. */
    private fun changed() = Bookmarks.databaseChangedExternally()

    fun bibleBookmarkById(id: IdType): BibleBookmarkWithNotes? = dao.bibleBookmarkById(id)
    fun genericBookmarkById(id: IdType): GenericBookmarkWithNotes? = dao.genericBookmarkById(id)
    fun labelById(id: IdType): Label? = dao.labelById(id)
    fun labelsForBookmark(bookmark: BaseBookmarkWithNotes): List<Label> = dao.labelsForBookmark(bookmark)
    val assignableLabels: List<Label> get() = dao.allLabelsSortedByName()

    private fun getOrCreateSpecialLabel(id: IdType, create: () -> Label): Label =
        dao.labelById(id) ?: create().also { dao.insert(it); changed() }

    val aiLabel: Label get() = getOrCreateSpecialLabel(AI_LABEL_ID) {
        Label(id = AI_LABEL_ID, name = AI_LABEL_NAME, color = android.graphics.Color.argb(255, 100, 100, 255),
            markerStyle = true, markerStyleWholeVerse = true, customIcon = "robot")
    }

    val labelUnlabelled: Label get() = getOrCreateSpecialLabel(UNLABELED_LABEL_ID) {
        Label(id = UNLABELED_LABEL_ID, name = UNLABELED_NAME, color = BookmarkStyle.BLUE_HIGHLIGHT.backgroundColor)
    }

    fun addOrUpdateBibleBookmark(bookmark: BibleBookmarkWithNotes, labels: Set<IdType>? = null, updateNotes: Boolean = false): BibleBookmarkWithNotes =
        addOrUpdateBookmark(bookmark, labels, updateNotes) as BibleBookmarkWithNotes

    fun addOrUpdateGenericBookmark(bookmark: GenericBookmarkWithNotes, labels: Set<IdType>? = null, updateNotes: Boolean = false): GenericBookmarkWithNotes =
        addOrUpdateBookmark(bookmark, labels, updateNotes) as GenericBookmarkWithNotes

    fun addOrUpdateBookmark(bookmark: BaseBookmarkWithNotes, labels: Set<IdType>? = null, updateNotes: Boolean = false): BaseBookmarkWithNotes {
        val notes = bookmark.noteEntity
        if (bookmark.new) {
            dao.insert(bookmark.bookmarkEntity)
            if (notes != null) dao.insert(notes)
            bookmark.new = false
        } else {
            dao.update(bookmark.bookmarkEntity)
            if (updateNotes) {
                if (notes != null) dao.upsert(notes) else dao.deleteBookmarkNotes(bookmark)
            }
        }
        val labelIdsInDb = labels?.mapNotNull { dao.labelById(it)?.id }
        if (labelIdsInDb != null) {
            val existing = dao.labelsForBookmark(bookmark).map { it.id }.toSet()
            val toBeDeleted = existing.filterNot { labelIdsInDb.contains(it) }
            val toBeAdded = labelIdsInDb.filterNot { existing.contains(it) }
            dao.deleteLabelsFromBookmark(bookmark, toBeDeleted)
            when (bookmark) {
                is BibleBookmarkWithNotes -> dao.insertBookmarkToLabels(toBeAdded.filter { !it.isEmpty }.map { labelId ->
                    BibleBookmarkToLabel(bookmark.id, labelId, orderNumber = dao.countStudyPadEntities(labelId))
                })
                is GenericBookmarkWithNotes -> dao.insertGenericBookmarkToLabels(toBeAdded.filter { !it.isEmpty }.map { labelId ->
                    GenericBookmarkToLabel(bookmark.id, labelId, orderNumber = dao.countStudyPadEntities(labelId))
                })
            }
            if (labelIdsInDb.find { it == bookmark.primaryLabelId } == null) {
                bookmark.primaryLabelId = labelIdsInDb.firstOrNull()
                dao.update(bookmark.bookmarkEntity)
            }
        }
        addText(bookmark)
        addLabels(bookmark)
        changed()
        return bookmark
    }

    fun setLabelsForBookmark(bookmark: BaseBookmarkWithNotes, labels: List<Label>) =
        addOrUpdateBookmark(bookmark, labels.map { it.id }.toSet())

    fun insertOrUpdateLabel(label: Label): Label {
        label.name = label.name.trim()
        if (label.id.isEmpty) throw RuntimeException("Illegal empty label.id")
        if (label.new) {
            dao.insert(label)
            label.new = false
        } else {
            dao.update(label)
        }
        changed()
        return label
    }

    fun deleteBookmark(bookmark: BaseBookmarkWithNotes) {
        val labels = labelsForBookmark(bookmark)
        dao.delete(bookmark)
        labels.forEach { sanitizeStudyPadOrder(it.id) }
        changed()
    }

    fun findOrphanedBookmarks(labelIdsToDelete: List<IdType>): List<BaseBookmarkWithNotes> {
        val result = mutableListOf<BaseBookmarkWithNotes>()
        for (labelId in labelIdsToDelete) {
            for (b in dao.bookmarksWithLabel(labelId)) {
                if (dao.labelsForBookmark(b.id).map { it.id }.all { it in labelIdsToDelete }) result.add(b)
            }
            for (b in dao.genericBookmarksWithLabel(labelId)) {
                if (dao.labelsForGenericBookmark(b.id).map { it.id }.all { it in labelIdsToDelete }) result.add(b)
            }
        }
        return result.distinct()
    }

    fun deleteLabels(labelIdList: List<IdType>, deleteOrphanedBookmarks: Boolean = false) {
        if (deleteOrphanedBookmarks) findOrphanedBookmarks(labelIdList).forEach { dao.delete(it) }
        dao.deleteLabelsByIds(labelIdList)
        changed()
    }

    fun removeBibleBookmarkLabel(bookmarkId: IdType, labelId: IdType) {
        val bookmark = dao.bibleBookmarkById(bookmarkId)!!
        setLabelsForBookmark(bookmark, labelsForBookmark(bookmark).filter { it.id != labelId })
    }

    fun removeGenericBookmarkLabel(bookmarkId: IdType, labelId: IdType) {
        val bookmark = dao.genericBookmarkById(bookmarkId)!!
        setLabelsForBookmark(bookmark, labelsForBookmark(bookmark).filter { it.id != labelId })
    }

    fun updateStudyPadTextEntryText(id: IdType, text: String) {
        dao.update(StudyPadTextEntryText(id, text))
        changed()
    }

    fun createStudyPadEntryWithText(
        labelId: IdType,
        orderNumber: Int? = null,
        text: String,
        contentType: TextContentType? = null,
        sourcePromptId: IdType? = null,
        indentLevel: Int = 0,
    ): StudyPadTextEntryWithText {
        val actualOrderNumber = orderNumber ?: dao.countStudyPadEntities(labelId)
        val entry = StudyPadTextEntryWithText(labelId = labelId, orderNumber = actualOrderNumber, indentLevel = indentLevel,
            text = text, contentType = contentType, sourcePromptId = sourcePromptId)
        dao.insert(entry.studyPadTextEntryEntity)
        dao.insert(entry.studyPadTextEntryTextEntity)
        incrementOrderNumbersFrom(labelId, actualOrderNumber, newStudyPadTextEntry = entry)
        changed()
        return entry
    }

    // ---------------------------------------------------------------- StudyPad search (BookmarkControl.searchStudyPadsByContent)

    fun searchStudyPadsByContent(searchText: String): List<StudyPadSearchResult> {
        val searchPattern = "%$searchText%"
        val results = mutableMapOf<IdType, MutableList<ContentMatch>>()
        for (entry in dao.searchStudyPadTextEntriesByContent(searchPattern)) {
            val snippet = generateTextSnippet(entry.text, searchText)
            results.getOrPut(entry.labelId) { mutableListOf() } +=
                ContentMatch(entry.id, EntryType.TEXT_ENTRY, snippet.text, snippet.matchStart, snippet.matchEnd)
        }
        for (b in dao.searchBibleBookmarkNotesByContent(searchPattern)) {
            for (label in dao.labelsForBookmark(b)) {
                val snippet = generateTextSnippet(b.notes ?: "", searchText)
                results.getOrPut(label.id) { mutableListOf() } +=
                    ContentMatch(b.id, EntryType.BOOKMARK_NOTE, snippet.text, snippet.matchStart, snippet.matchEnd)
            }
        }
        for (b in dao.searchGenericBookmarkNotesByContent(searchPattern)) {
            for (label in dao.labelsForBookmark(b)) {
                val snippet = generateTextSnippet(b.notes ?: "", searchText)
                results.getOrPut(label.id) { mutableListOf() } +=
                    ContentMatch(b.id, EntryType.BOOKMARK_NOTE, snippet.text, snippet.matchStart, snippet.matchEnd)
            }
        }
        val searchResults = results.mapNotNull { (labelId, matches) ->
            val label = dao.labelById(labelId) ?: return@mapNotNull null
            if (label.isSpecialLabel) null else StudyPadSearchResult(label, matches.size, matches)
        }
        return searchResults.sortedWith(compareByDescending<StudyPadSearchResult> { it.matchCount }.thenBy { it.label.name.lowercase() })
    }

    internal fun generateTextSnippet(fullText: String, searchText: String, contextChars: Int = 50): StudyPadSearchResultTextSnippet {
        val matchIndex = fullText.lowercase().indexOf(searchText.lowercase())
        if (matchIndex == -1) return StudyPadSearchResultTextSnippet(fullText.take(contextChars * 2), 0, 0)
        val snippetStart = maxOf(0, matchIndex - contextChars)
        val snippetEnd = minOf(fullText.length, matchIndex + searchText.length + contextChars)
        val prefix = if (snippetStart > 0) "..." else ""
        val suffix = if (snippetEnd < fullText.length) "..." else ""
        val matchStartInSnippet = prefix.length + (matchIndex - snippetStart)
        return StudyPadSearchResultTextSnippet(prefix + fullText.substring(snippetStart, snippetEnd) + suffix,
            matchStartInSnippet, matchStartInSnippet + searchText.length)
    }

    // ---------------------------------------------------------------- texts & order (BookmarkControl helpers)

    private fun addLabels(b: BaseBookmarkWithNotes) {
        val btl = dao.getBookmarkToLabelsForBookmark(b)
        b.setBaseBookmarkToLabels(btl)
        b.labelIds = btl.map { it.labelId }
    }

    internal fun addText(b: BaseBookmarkWithNotes) = when (b) {
        is BibleBookmarkWithNotes -> addBibleText(b)
        is GenericBookmarkWithNotes -> addGenericText(b)
        else -> throw RuntimeException("Illegal type")
    }

    private fun addGenericText(b: GenericBookmarkWithNotes) {
        val book = b.book ?: return
        try {
            val key = b.bookKey ?: book.getKey(b.key)
            if (b.ordinalStart == null || b.ordinalEnd == null) {
                b.osisFragment = OsisFragment(SwordContentFacade.readOsisFragment(book, key), key, book)
                val all = SwordContentFacade.getTextWithinOrdinalsAsString(book, key, SwordContentFacade.ordinalRangeFor(book, key))
                b.text = all.firstOrNull()?.take(200)?.trim() ?: ""
                b.fullText = b.text; b.startText = ""; b.endText = ""
            } else {
                addText(b, SwordContentFacade.getTextWithinOrdinalsAsString(book, key, b.ordinalStart!!..b.ordinalEnd!!), b.wholeVerse)
            }
        } catch (e: OsisError) {
            b.text = e.message
        } catch (e: NoSuchKeyException) {
            b.text = e.message
        }
    }

    private fun addText(b: BaseBookmarkWithNotes, texts: List<String>, wholeVerse: Boolean = false) {
        val result = computeBookmarkTexts(texts, b.startOffset, b.endOffset, wholeVerse) ?: run {
            b.startText = ""; b.endText = ""; b.text = ""; b.fullText = ""
            return
        }
        b.startText = result.startText
        b.text = result.text
        b.endText = result.endText
        b.fullText = result.fullText
    }

    private fun addBibleText(b: BibleBookmarkWithNotes) {
        val book = (b.book as? SwordBook) ?: Sword.bibles.firstOrNull() ?: return
        b.osisFragment = try {
            OsisFragment(SwordContentFacade.readOsisFragment(book, b.verseRange.toV11n(book.versification)), b.verseRange, book)
        } catch (e: OsisError) { null }
        val verseTexts = b.verseRange.map { SwordContentFacade.getCanonicalText(book, it, true) }
        addText(b, verseTexts, b.wholeVerse || b.book == null)
    }

    private fun sanitizeStudyPadOrder(labelId: IdType) {
        val bookmarkToLabels = dao.getBookmarkToLabelsForLabel(labelId)
        val genericBookmarkToLabels = dao.getGenericBookmarkToLabelsForLabel(labelId)
        val entries = dao.studyPadTextEntriesByLabelId(labelId)
        val all = ArrayList<Any>().apply { addAll(entries); addAll(bookmarkToLabels); addAll(genericBookmarkToLabels) }
        all.sortBy { when (it) { is BaseBookmarkToLabel -> it.orderNumber; is StudyPadTextEntryWithText -> it.orderNumber; else -> 0 } }
        val changedBtl = mutableListOf<BibleBookmarkToLabel>()
        val changedGbtl = mutableListOf<GenericBookmarkToLabel>()
        val changedEntries = mutableListOf<StudyPadTextEntryWithText>()
        for ((count, it) in all.withIndex()) when (it) {
            is BibleBookmarkToLabel -> if (it.orderNumber != count) { it.orderNumber = count; changedBtl += it }
            is GenericBookmarkToLabel -> if (it.orderNumber != count) { it.orderNumber = count; changedGbtl += it }
            is StudyPadTextEntryWithText -> if (it.orderNumber != count) { it.orderNumber = count; changedEntries += it }
        }
        dao.updateBibleBookmarkToLabels(changedBtl)
        dao.updateGenericBookmarkToLabels(changedGbtl)
        dao.updateStudyPadTextEntries(changedEntries.map { it.studyPadTextEntryEntity })
    }

    private fun incrementOrderNumbersFrom(labelId: IdType, fromOrder: Int, newStudyPadTextEntry: StudyPadTextEntryWithText? = null) {
        val btl = dao.getBookmarkToLabelsForLabel(labelId).filter { it.orderNumber >= fromOrder }.onEach { it.orderNumber++ }
        val gbtl = dao.getGenericBookmarkToLabelsForLabel(labelId).filter { it.orderNumber >= fromOrder }.onEach { it.orderNumber++ }
        val entries = dao.studyPadTextEntriesByLabelId(labelId)
            .filter { it.orderNumber >= fromOrder && it.id != newStudyPadTextEntry?.id }.onEach { it.orderNumber++ }
        dao.updateBibleBookmarkToLabels(btl)
        dao.updateGenericBookmarkToLabels(gbtl)
        dao.updateStudyPadTextEntries(entries.map { it.studyPadTextEntryEntity })
    }
}

/** Display texts computed for a bookmark (BookmarkControl.kt). */
internal data class BookmarkTexts(val startText: String, val text: String, val endText: String, val fullText: String)

internal fun computeBookmarkTexts(texts: List<String>, startOffset: Int?, endOffset: Int?, wholeVerse: Boolean): BookmarkTexts? {
    val firstVerse = texts.firstOrNull() ?: return null
    val start0 = (if (wholeVerse) 0 else startOffset ?: 0).coerceIn(0, firstVerse.length)
    return if (texts.size == 1) {
        val end0 = (if (wholeVerse) firstVerse.length else endOffset ?: firstVerse.length).coerceIn(start0, firstVerse.length)
        val startText = firstVerse.substring(0, start0)
        val text = firstVerse.substring(start0, end0).trim()
        val endText = firstVerse.substring(end0)
        BookmarkTexts(startText, text, endText, "$startText$text$endText".trim())
    } else {
        val startText = firstVerse.substring(0, start0)
        val startSelection = firstVerse.substring(start0)
        val lastVerse = texts.last()
        val end0 = (if (wholeVerse) lastVerse.length else endOffset ?: lastVerse.length).coerceIn(0, lastVerse.length)
        val endSelection = lastVerse.substring(0, end0)
        val endText = lastVerse.substring(end0)
        val middle = if (texts.size > 2) texts.slice(1 until texts.size - 1).joinToString(" ") else ""
        val text = "$startSelection$middle$endSelection".trim()
        BookmarkTexts(startText, text, endText, "$startText$text$endText".trim())
    }
}
