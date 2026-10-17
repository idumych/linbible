/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Bookmarks, labels and StudyPads. Data model mirrors AndBible's BookmarkEntities and is stored in AndBible's
 * bookmarks.sqlite3 format (see Db.kt).
 */
package org.linbible

import kotlinx.serialization.Serializable
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.sword.SwordBook
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.passage.VerseRange
import org.crosswire.jsword.versification.system.Versifications
import java.io.File
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

const val UNLABELED_LABEL_ID = "00000000-0000-ab1e-0000-001abe1ed001"
const val PARAGRAPH_BREAK_LABEL_ID = "00000000-0000-ab1e-0000-ba4a64a30001"
const val SPEAK_LABEL_ID = "00000000-0000-ab1e-0000-5bea400001a1"
const val AI_LABEL_ID = "00000000-0000-ab1e-0000-a100000001a1"
const val DEFAULT_LABEL_COLOR: Int = (255 shl 24) or (145 shl 16) or (167 shl 8) or 255

@Serializable
data class EditAction(val mode: String? = null, val content: String? = null)

@Serializable
data class Label(
    val id: String = newId(),
    var name: String = "",
    var color: Int = DEFAULT_LABEL_COLOR,
    var markerStyle: Boolean = false,
    var markerStyleWholeVerse: Boolean = false,
    var underlineStyle: Boolean = false,
    var underlineStyleWholeVerse: Boolean = true,
    var hideStyle: Boolean = false,
    var hideStyleWholeVerse: Boolean = false,
    var favourite: Boolean = false,
    var customIcon: String? = null,
    var type: String? = null,
) {
    val isUnlabeled get() = id == UNLABELED_LABEL_ID || name == "__UNLABELED__"
    val isParagraphBreak get() = id == PARAGRAPH_BREAK_LABEL_ID || name == "__PARAGRAPH_BREAK_LABEL__"
    val isSpeak get() = id == SPEAK_LABEL_ID || name == "__SPEAK_LABEL__"
    val isAi get() = id == AI_LABEL_ID || name == "__AI_LABEL__"
    val isSpecial get() = isUnlabeled || isParagraphBreak || isSpeak || isAi
    val displayName get() = when {
        isUnlabeled -> "Unlabeled"
        isParagraphBreak -> "Paragraph break"
        isSpeak -> "Speak"
        isAi -> "AI"
        else -> name
    }
}

@Serializable
data class BookmarkToLabel(
    val bookmarkId: String,
    val labelId: String,
    var orderNumber: Int = -1,
    var indentLevel: Int = 0,
    var expandContent: Boolean = true,
)

@Serializable
data class BibleBookmark(
    val id: String = newId(),
    /** Ordinals in KJVA versification, used for lookup independent of document versification. */
    var kjvOrdinalStart: Int,
    var kjvOrdinalEnd: Int,
    /** Original versification and ordinals of the document the bookmark was made in. */
    var v11n: String,
    var ordinalStart: Int,
    var ordinalEnd: Int,
    var bookInitials: String? = null,
    var startOffset: Int? = null,
    var endOffset: Int? = null,
    var wholeVerse: Boolean = true,
    var notes: String? = null,
    var createdAt: Long = System.currentTimeMillis(),
    var lastUpdatedOn: Long = System.currentTimeMillis(),
    var primaryLabelId: String? = null,
    var customIcon: String? = null,
    var editAction: EditAction? = null,
    var notesContentType: String? = null,
    var notesSourcePromptId: String? = null,
    var playbackSettings: String? = null,
    var type: String? = null,
    var sourcePromptId: String? = null,
) {
    val verseRange: VerseRange get() {
        val v = Versifications.instance().getVersification(v11n)
        return VerseRange(v, Verse(v, ordinalStart), Verse(v, ordinalEnd))
    }
    val kjvRange: IntRange get() = kjvOrdinalStart..kjvOrdinalEnd
}

@Serializable
data class GenericBookmark(
    val id: String = newId(),
    var key: String,
    var bookInitials: String,
    var ordinalStart: Int? = null,
    var ordinalEnd: Int? = null,
    var startOffset: Int? = null,
    var endOffset: Int? = null,
    var wholeVerse: Boolean = true,
    var notes: String? = null,
    var createdAt: Long = System.currentTimeMillis(),
    var lastUpdatedOn: Long = System.currentTimeMillis(),
    var primaryLabelId: String? = null,
    var customIcon: String? = null,
    var editAction: EditAction? = null,
    var notesContentType: String? = null,
    var notesSourcePromptId: String? = null,
    var playbackSettings: String? = null,
    var sourcePromptId: String? = null,
)

@Serializable
data class StudyPadTextEntry(
    val id: String = newId(),
    val labelId: String,
    var orderNumber: Int,
    var indentLevel: Int = 0,
    var text: String = "",
    var contentType: String? = null,
    var sourcePromptId: String? = null,
)

@Serializable
data class BookmarkData(
    val labels: MutableList<Label> = mutableListOf(),
    val bibleBookmarks: MutableList<BibleBookmark> = mutableListOf(),
    val genericBookmarks: MutableList<GenericBookmark> = mutableListOf(),
    val bibleBookmarkToLabels: MutableList<BookmarkToLabel> = mutableListOf(),
    val genericBookmarkToLabels: MutableList<BookmarkToLabel> = mutableListOf(),
    val studyPadEntries: MutableList<StudyPadTextEntry> = mutableListOf(),
)

/** Change notifications sent to all open BibleView windows. */
interface BookmarkListener {
    fun bookmarksChanged(bible: List<BibleBookmark>, generic: List<GenericBookmark>) {}
    fun bookmarksDeleted(ids: List<String>) {}
    fun noteModified(id: String, lastUpdatedOn: Long, notes: String?) {}
    fun labelsChanged(labels: List<Label>) {}
    fun bookmarkToLabelChanged(btl: BookmarkToLabel, generic: Boolean) {}
    fun studyPadChanged(labelId: String, newEntry: StudyPadTextEntry?) {}
    fun studyPadEntryDeleted(id: String) {}
}

object Bookmarks {
    private var data = BookmarkData()
    private val listeners = mutableListOf<BookmarkListener>()
    private val textCache = HashMap<String, String>()

    fun addListener(l: BookmarkListener) { listeners += l }
    fun removeListener(l: BookmarkListener) { listeners -= l }
    private fun notify(f: BookmarkListener.() -> Unit) = listeners.toList().forEach { runCatching { it.f() }.onFailure { e -> Log.e("listener", e) } }

    private val legacyFile = File(AppDirs.data, "bookmarks.json")
    private lateinit var rows: RowSync

    fun load() {
        Databases.onChanged(DbCategory.BOOKMARKS) { javafx.application.Platform.runLater { externallyChanged() } }
        reloadFromDb()
        if (legacyFile.exists() && data.bibleBookmarks.isEmpty() && data.genericBookmarks.isEmpty()) importLegacyJson()
        ensureSpecialLabel(UNLABELED_LABEL_ID)
        ensureSpecialLabel(PARAGRAPH_BREAK_LABEL_ID)
        ensureSpecialLabel(SPEAK_LABEL_ID)
        save()
    }

    /** Re-reads everything from the database, e.g. after sync applied changes or a backup was restored. */
    @Synchronized
    fun reloadFromDb() {
        val db = DbCategory.BOOKMARKS.db
        rows = RowSync(db, BookmarkTables.specs)
        val r = BookmarkTables.specs.associate { it.name to it.read(db) }
        rows.reset(r)
        data = BookmarkTables.fromRows(r)
        textCache.clear()
    }

    /** The database was changed directly (AndBible code via BookmarkControl): reload now, refresh windows later. */
    fun databaseChangedExternally() {
        reloadFromDb()
        javafx.application.Platform.runLater {
            notify { labelsChanged(labels) }
            notify { bookmarksChanged(bibleBookmarks, genericBookmarks) }
        }
    }

    /** Called after the database changed underneath us; tells all windows to refresh. */
    fun externallyChanged() {
        reloadFromDb()
        notify { labelsChanged(labels) }
        notify { bookmarksChanged(bibleBookmarks, genericBookmarks) }
    }

    private fun importLegacyJson() {
        try {
            data = json.decodeFromString(BookmarkData.serializer(), legacyFile.readText())
            Log.i("Imported ${data.bibleBookmarks.size + data.genericBookmarks.size} bookmarks from ${legacyFile.name}")
            // Earlier LinBible versions stored special labels with their id as name
            data.labels.filter { it.isSpecial }.forEach { it.name = specialName(it.id) }
            save()
            legacyFile.renameTo(File(legacyFile.path + ".imported"))
        } catch (e: Exception) {
            Log.e("Could not import ${legacyFile.name}", e)
        }
    }

    private fun specialName(id: String) = when (id) {
        UNLABELED_LABEL_ID -> "__UNLABELED__"
        PARAGRAPH_BREAK_LABEL_ID -> "__PARAGRAPH_BREAK_LABEL__"
        SPEAK_LABEL_ID -> "__SPEAK_LABEL__"
        AI_LABEL_ID -> "__AI_LABEL__"
        else -> id
    }

    private fun ensureSpecialLabel(id: String) {
        if (data.labels.none { it.id == id }) {
            data.labels += when (id) {
                PARAGRAPH_BREAK_LABEL_ID -> Label(id = id, name = specialName(id), color = 0, underlineStyleWholeVerse = false,
                    hideStyle = true, hideStyleWholeVerse = true)
                SPEAK_LABEL_ID -> Label(id = id, name = specialName(id), color = (255 shl 24) or (255 shl 16), underlineStyleWholeVerse = false)
                else -> Label(id = id, name = specialName(id), color = DEFAULT_LABEL_COLOR, underlineStyleWholeVerse = false)
            }
        }
    }

    @Synchronized
    private fun save() {
        try {
            rows.write(BookmarkTables.toRows(data))
        } catch (e: Exception) {
            Log.e("Saving bookmarks failed", e)
            app?.toast("Saving bookmarks failed: ${e.message}")
        }
    }

    /** Set by the UI so storage errors can be shown. */
    var app: MainApp? = null

    // ---------------- labels ----------------

    val labels: List<Label> get() = data.labels.toList()
    val userLabels: List<Label> get() = data.labels.filter { !it.isSpecial }.sortedBy { it.name.lowercase() }
    fun label(id: String?): Label? = data.labels.find { it.id == id }

    fun saveLabel(label: Label) {
        val idx = data.labels.indexOfFirst { it.id == label.id }
        if (idx >= 0) data.labels[idx] = label else data.labels += label
        save()
        notify { labelsChanged(listOf(label)) }
    }

    fun deleteLabel(id: String) {
        val l = label(id) ?: return
        if (l.isSpecial) return
        data.labels.remove(l)
        data.bibleBookmarkToLabels.removeIf { it.labelId == id }
        data.genericBookmarkToLabels.removeIf { it.labelId == id }
        data.studyPadEntries.removeIf { it.labelId == id }
        data.bibleBookmarks.filter { it.primaryLabelId == id }.forEach { it.primaryLabelId = null }
        data.genericBookmarks.filter { it.primaryLabelId == id }.forEach { it.primaryLabelId = null }
        save()
        notify { labelsChanged(labels) }
    }

    fun labelIdsFor(bookmarkId: String, generic: Boolean): List<String> =
        (if (generic) data.genericBookmarkToLabels else data.bibleBookmarkToLabels).filter { it.bookmarkId == bookmarkId }.map { it.labelId }

    fun bookmarkToLabels(bookmarkId: String, generic: Boolean): List<BookmarkToLabel> =
        (if (generic) data.genericBookmarkToLabels else data.bibleBookmarkToLabels).filter { it.bookmarkId == bookmarkId }

    // ---------------- bible bookmarks ----------------

    val bibleBookmarks: List<BibleBookmark> get() = data.bibleBookmarks.toList()
    val genericBookmarks: List<GenericBookmark> get() = data.genericBookmarks.toList()

    fun bibleBookmark(id: String) = data.bibleBookmarks.find { it.id == id }
    fun genericBookmark(id: String) = data.genericBookmarks.find { it.id == id }

    fun bibleBookmarksIn(range: VerseRange): List<BibleBookmark> {
        val kjv = range.toV11n(KJVA)
        val r = kjv.start.ordinal..kjv.end.ordinal
        return data.bibleBookmarks.filter { it.kjvOrdinalStart <= r.last && it.kjvOrdinalEnd >= r.first }
    }

    fun genericBookmarksFor(book: Book, key: String): List<GenericBookmark> =
        data.genericBookmarks.filter { it.bookInitials == book.initials && it.key == key }

    fun addBibleBookmark(book: SwordBook?, range: VerseRange, startOffset: Int?, endOffset: Int?, wholeVerse: Boolean, labels: Collection<String>): BibleBookmark {
        val kjv = range.toV11n(KJVA)
        val b = BibleBookmark(
            kjvOrdinalStart = kjv.start.ordinal, kjvOrdinalEnd = kjv.end.ordinal,
            v11n = range.versification.name, ordinalStart = range.start.ordinal, ordinalEnd = range.end.ordinal,
            bookInitials = book?.initials, startOffset = startOffset, endOffset = endOffset, wholeVerse = wholeVerse,
        )
        data.bibleBookmarks += b
        labels.forEach { data.bibleBookmarkToLabels += BookmarkToLabel(b.id, it) }
        b.primaryLabelId = labels.firstOrNull()
        save()
        notify { bookmarksChanged(listOf(b), emptyList()) }
        return b
    }

    fun addGenericBookmark(book: Book, key: String, ordinals: IntRange?, startOffset: Int?, endOffset: Int?, wholeVerse: Boolean, labels: Collection<String>): GenericBookmark {
        val b = GenericBookmark(
            key = key, bookInitials = book.initials, ordinalStart = ordinals?.first, ordinalEnd = ordinals?.last,
            startOffset = startOffset, endOffset = endOffset, wholeVerse = wholeVerse,
        )
        data.genericBookmarks += b
        labels.forEach { data.genericBookmarkToLabels += BookmarkToLabel(b.id, it) }
        b.primaryLabelId = labels.firstOrNull()
        save()
        notify { bookmarksChanged(emptyList(), listOf(b)) }
        return b
    }

    private fun changed(id: String) {
        bibleBookmark(id)?.let { it.lastUpdatedOn = System.currentTimeMillis(); save(); notify { bookmarksChanged(listOf(it), emptyList()) }; return }
        genericBookmark(id)?.let { it.lastUpdatedOn = System.currentTimeMillis(); save(); notify { bookmarksChanged(emptyList(), listOf(it)) } }
    }

    fun update(id: String, f: (BibleBookmark?, GenericBookmark?) -> Unit) {
        f(bibleBookmark(id), genericBookmark(id))
        changed(id)
    }

    /** Saves a note without a full bookmark refresh, so an open note editor is not disturbed. */
    fun setNote(id: String, note: String?) {
        val n = note?.takeIf { it.isNotBlank() }
        val now = System.currentTimeMillis()
        bibleBookmark(id)?.let { it.notes = n; it.lastUpdatedOn = now }
        genericBookmark(id)?.let { it.notes = n; it.lastUpdatedOn = now }
        save()
        notify { noteModified(id, now, n) }
    }

    fun delete(ids: List<String>) {
        data.bibleBookmarks.removeIf { it.id in ids }
        data.genericBookmarks.removeIf { it.id in ids }
        data.bibleBookmarkToLabels.removeIf { it.bookmarkId in ids }
        data.genericBookmarkToLabels.removeIf { it.bookmarkId in ids }
        save()
        notify { bookmarksDeleted(ids) }
    }

    fun setLabels(id: String, labelIds: Collection<String>) {
        val generic = genericBookmark(id) != null
        val list = if (generic) data.genericBookmarkToLabels else data.bibleBookmarkToLabels
        list.removeIf { it.bookmarkId == id && it.labelId !in labelIds }
        val existing = list.filter { it.bookmarkId == id }.map { it.labelId }.toSet()
        labelIds.filter { it !in existing }.forEach { list += BookmarkToLabel(id, it, orderNumber = nextOrderNumber(it)) }
        update(id) { b, g ->
            val primary = b?.primaryLabelId ?: g?.primaryLabelId
            val newPrimary = if (primary in labelIds) primary else labelIds.firstOrNull()
            b?.primaryLabelId = newPrimary; g?.primaryLabelId = newPrimary
        }
    }

    fun toggleLabel(id: String, labelId: String) {
        val current = labelIdsFor(id, genericBookmark(id) != null)
        setLabels(id, if (labelId in current) current - labelId else current + labelId)
    }

    fun removeLabel(id: String, labelId: String) = setLabels(id, labelIdsFor(id, genericBookmark(id) != null) - labelId)

    fun setPrimaryLabel(id: String, labelId: String) = update(id) { b, g -> b?.primaryLabelId = labelId; g?.primaryLabelId = labelId }

    fun updateBookmarkToLabel(btl: BookmarkToLabel, generic: Boolean) {
        val list = if (generic) data.genericBookmarkToLabels else data.bibleBookmarkToLabels
        val idx = list.indexOfFirst { it.bookmarkId == btl.bookmarkId && it.labelId == btl.labelId }
        if (idx >= 0) list[idx] = btl else list += btl
        save()
        notify { bookmarkToLabelChanged(btl, generic) }
    }

    // ---------------- text ----------------

    fun text(b: BibleBookmark): String = textCache.getOrPut("b-${b.id}-${b.lastUpdatedOn}") {
        val book = (Sword.book(b.bookInitials) as? SwordBook) ?: Sword.bibles.firstOrNull() ?: return ""
        Content.plainText(book, b.verseRange.toV11n(book.versification))
    }

    fun text(g: GenericBookmark): String = textCache.getOrPut("g-${g.id}-${g.lastUpdatedOn}") {
        val book = Sword.book(g.bookInitials) ?: return ""
        val key = runCatching { book.getKey(g.key) }.getOrNull() ?: return ""
        val s = g.ordinalStart
        val e = g.ordinalEnd
        if (s != null && e != null) Content.textWithinOrdinals(book, key, s..e)
        else Content.plainText(book, key).take(500)
    }

    // ---------------- StudyPads ----------------

    fun studyPadEntries(labelId: String) = data.studyPadEntries.filter { it.labelId == labelId }
    fun studyPadEntry(id: String) = data.studyPadEntries.find { it.id == id }
    fun bibleBookmarksWithLabel(labelId: String) = data.bibleBookmarkToLabels.filter { it.labelId == labelId }
        .mapNotNull { btl -> bibleBookmark(btl.bookmarkId) }
    fun genericBookmarksWithLabel(labelId: String) = data.genericBookmarkToLabels.filter { it.labelId == labelId }
        .mapNotNull { btl -> genericBookmark(btl.bookmarkId) }
    fun bibleBookmarkToLabelsFor(labelId: String) = data.bibleBookmarkToLabels.filter { it.labelId == labelId }
    fun genericBookmarkToLabelsFor(labelId: String) = data.genericBookmarkToLabels.filter { it.labelId == labelId }

    private fun nextOrderNumber(labelId: String): Int =
        ((data.bibleBookmarkToLabels.filter { it.labelId == labelId }.map { it.orderNumber }) +
            data.genericBookmarkToLabels.filter { it.labelId == labelId }.map { it.orderNumber } +
            studyPadEntries(labelId).map { it.orderNumber }).maxOrNull()?.plus(1) ?: 0

    /** Creates a new text entry right after [afterOrderNumber], shifting later items down. */
    fun createStudyPadEntry(labelId: String, afterOrderNumber: Int): StudyPadTextEntry {
        val newOrder = afterOrderNumber + 1
        val shiftedBtl = mutableListOf<BookmarkToLabel>()
        val shiftedGBtl = mutableListOf<BookmarkToLabel>()
        val shiftedEntries = mutableListOf<StudyPadTextEntry>()
        data.bibleBookmarkToLabels.filter { it.labelId == labelId && it.orderNumber >= newOrder }.forEach { it.orderNumber++; shiftedBtl += it }
        data.genericBookmarkToLabels.filter { it.labelId == labelId && it.orderNumber >= newOrder }.forEach { it.orderNumber++; shiftedGBtl += it }
        studyPadEntries(labelId).filter { it.orderNumber >= newOrder }.forEach { it.orderNumber++; shiftedEntries += it }
        val entry = StudyPadTextEntry(labelId = labelId, orderNumber = newOrder)
        data.studyPadEntries += entry
        save()
        lastStudyPadChange = StudyPadChange(entry, shiftedBtl, shiftedGBtl, shiftedEntries)
        notify { studyPadChanged(labelId, entry) }
        return entry
    }

    data class StudyPadChange(
        val newEntry: StudyPadTextEntry?,
        val btl: List<BookmarkToLabel>,
        val gbtl: List<BookmarkToLabel>,
        val entries: List<StudyPadTextEntry>,
    )
    var lastStudyPadChange: StudyPadChange? = null
        private set

    fun updateStudyPadEntry(entry: StudyPadTextEntry) {
        val idx = data.studyPadEntries.indexOfFirst { it.id == entry.id }
        if (idx < 0) return
        val old = data.studyPadEntries[idx]
        data.studyPadEntries[idx] = entry.copy(text = if (entry.text.isEmpty()) old.text else entry.text)
        save()
    }

    fun updateStudyPadEntryText(id: String, text: String) {
        studyPadEntry(id)?.text = text
        save()
    }

    fun deleteStudyPadEntry(id: String) {
        data.studyPadEntries.removeIf { it.id == id }
        save()
        notify { studyPadEntryDeleted(id) }
    }

    fun updateOrderNumbers(labelId: String, bookmarks: List<Pair<String, Int>>, generic: List<Pair<String, Int>>, entries: List<Pair<String, Int>>) {
        val btl = bookmarks.mapNotNull { (id, n) -> data.bibleBookmarkToLabels.find { it.bookmarkId == id && it.labelId == labelId }?.apply { orderNumber = n } }
        val gbtl = generic.mapNotNull { (id, n) -> data.genericBookmarkToLabels.find { it.bookmarkId == id && it.labelId == labelId }?.apply { orderNumber = n } }
        val es = entries.mapNotNull { (id, n) -> studyPadEntry(id)?.apply { orderNumber = n } }
        save()
        lastStudyPadChange = StudyPadChange(null, btl, gbtl, es)
        notify { studyPadChanged(labelId, null) }
    }

    /** Ensures bookmarks in a StudyPad have unique order numbers (newly labelled ones have -1). */
    fun normalizeStudyPadOrder(labelId: String) {
        var next = nextOrderNumber(labelId)
        var changed = false
        data.bibleBookmarkToLabels.filter { it.labelId == labelId && it.orderNumber < 0 }.forEach { it.orderNumber = next++; changed = true }
        data.genericBookmarkToLabels.filter { it.labelId == labelId && it.orderNumber < 0 }.forEach { it.orderNumber = next++; changed = true }
        if (changed) save()
    }
}
