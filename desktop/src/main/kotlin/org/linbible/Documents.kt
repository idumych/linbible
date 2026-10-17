/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * JSON documents handed to BibleView. Ported from AndBible's ClientPageObjects.kt; field names
 * must match bibleview-js/src/types/documents.ts and client-objects.ts.
 */
package org.linbible

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.BookCategory
import org.crosswire.jsword.book.FeatureType
import org.crosswire.jsword.book.sword.SwordBook
import org.crosswire.jsword.book.sword.SwordBookMetaData
import org.crosswire.jsword.passage.Key
import org.crosswire.jsword.passage.NoSuchVerseException
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.passage.VerseRange
import org.crosswire.jsword.passage.VerseRangeFactory
import org.crosswire.jsword.versification.BibleBook
import org.crosswire.jsword.versification.BookName
import org.crosswire.jsword.versification.Versification
import org.jdom2.Element
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

private val sanitizeRegex = Regex("""[^\p{L}\d]""")
fun sanitizeId(s: String): String = s.replace(sanitizeRegex, "_")

val Key.uniqueId: String get() =
    if (this is VerseRange) "ordinal-${start.ordinal}-${end.ordinal}" else sanitizeId(osisID)

val VerseRange.onlyNumber: String get() = if (cardinality > 1) "${start.verse}-${end.verse}" else "${start.verse}"

val VerseRange.abbreviated: String get() = synchronized(BookName::class.java) {
    val was = BookName.isFullBookName()
    BookName.setFullBookName(false)
    try { name } finally { BookName.setFullBookName(was) }
}

/** Whole chapter (including the book intro "chapter 0" together with chapter 1), as in AndBible. */
fun wholeChapter(v11n: Versification, book: BibleBook, chapter: Int): VerseRange {
    val start = if (chapter == 1) 0 else chapter
    val end = if (chapter == 0) 1 else chapter
    return VerseRange(v11n, Verse(v11n, book, start, 0), Verse(v11n, book, end, v11n.getLastVerse(book, end)))
}

object Documents {

    fun error(message: String, severity: String = "NORMAL"): JsonObject = buildJsonObject {
        str("id", UUID.randomUUID().toString())
        str("type", "error")
        str("errorMessage", message)
        str("severity", severity)
    }

    fun osisFragment(xml: Element, key: Key, book: Book): JsonObject {
        val hasHebrew = book.hasFeature(FeatureType.HEBREW_DEFINITIONS)
        val hasGreek = book.hasFeature(FeatureType.GREEK_DEFINITIONS)
        val featureType = when {
            hasHebrew && hasGreek -> "hebrew-and-greek"
            hasHebrew -> "hebrew"
            hasGreek -> "greek"
            else -> null
        }
        return buildJsonObject {
            str("xml", Content.elementToString(xml))
            str("key", "${book.initials}--${key.uniqueId}")
            str("keyName", key.name)
            str("v11n", (book as? SwordBook)?.versification?.name)
            str("bookCategory", book.bookCategory.name)
            str("bookInitials", book.initials)
            str("bookAbbreviation", book.abbreviation)
            str("osisRef", key.osisRef)
            bool("isNewTestament", key is VerseRange && key.start.book.ordinal >= BibleBook.MATT.ordinal)
            put("features", if (featureType != null) buildJsonObject { str("type", featureType); str("keyName", key.name) } else JsonObject(emptyMap()))
            range("ordinalRange", if (key is VerseRange) key.start.ordinal..key.end.ordinal else null)
            str("language", book.language.code)
            str("direction", if (book.isLeftToRight) "ltr" else "rtl")
            bool("hasStrongs", book.hasFeature(FeatureType.STRONGS_NUMBERS))
        }
    }

    private fun annotateRef(xml: Element, book: Book): VerseRange? {
        val ref = xml.getChild("div")?.getAttribute("annotateRef")?.value ?: return null
        if (book !is SwordBook) return null
        return try { VerseRangeFactory.fromString(book.versification, ref) } catch (e: NoSuchVerseException) { null }
    }

    /** Generic OSIS document (commentaries, dictionaries, general books). */
    fun osis(book: Book, key: Key, highlight: IntRange? = null, block: CommentaryBlock? = null): JsonObject {
        val xml = Content.readOsisFragment(book, key)
        val annotate = annotateRef(xml, book)
        val ordRange = if (book.bookCategory != BookCategory.BIBLE) Content.ordinalRangeFor(book, key) else null
        return buildJsonObject {
            str("id", sanitizeId("${book.initials}-${key.uniqueId}"))
            str("type", "osis")
            put("osisFragment", osisFragment(xml, key, book))
            range("ordinalRange", ordRange)
            put("readingProgress", JsonNull)
            str("bookInitials", book.initials)
            str("bookCategory", book.bookCategory.name)
            str("bookAbbreviation", book.abbreviation)
            str("bookName", book.name)
            str("key", key.uniqueId)
            str("annotateRef", annotate?.osisRef ?: key.osisRef)
            str("osisRef", (annotate ?: key).osisRef)
            str("v11n", (book as? SwordBook)?.versification?.name)
            arr("genericBookmarks", Bookmarks.genericBookmarksFor(book, key.osisRef).map { clientGenericBookmark(it) })
            range("highlightedOrdinalRange", highlight)
            bool("isNativeHtml", false)
            bool("isMyDocument", false)
            bool("isAiDocument", false)
            str("myDocumentPageId", null)
            str("sourcePromptId", null)
            str("sourcePromptName", null)
            str("sourceModelName", null)
            arr("aiDocMarkers", emptyList())
            put("commentaryRange", if (block == null || block.start == block.end) JsonNull else buildJsonObject {
                str("startOsisRef", block.start.osisRef)
                str("endOsisRef", block.end.osisRef)
                str("name", VerseRange(block.start.versification, block.start, block.end).name)
            })
        }
    }

    fun bible(book: SwordBook, range: VerseRange, originalKey: VerseRange? = null): JsonObject {
        val v11n = book.versification
        val vr = range.toV11n(v11n)
        val base = osis(book, vr)
        val xml = Content.readOsisFragment(book, vr)
        val hasChapter = xml.getChild("div")?.getChild("chapter") != null
        val sourceType = book.getProperty(SwordBookMetaData.KEY_SOURCE_TYPE)?.toString()?.lowercase(Locale.ROOT)
        val original = originalKey?.toV11n(v11n)
        return JsonObject(base + buildJsonObject {
            str("type", "bible")
            arr("bookmarks", Bookmarks.bibleBookmarksIn(vr).map { clientBibleBookmark(it, v11n) })
            str("bibleBookName", v11n.getPreferredNameInLocale(vr.start.book, Locale.getDefault()))
            range("ordinalRange", vr.start.ordinal..vr.end.ordinal)
            bool("addChapter", sourceType == "gbf" || !hasChapter)
            num("chapterNumber", vr.start.chapter)
            range("originalOrdinalRange", original?.let { it.start.ordinal..it.end.ordinal })
            str("v11n", v11n.name)
            ints("memorizedOrdinals", emptyList())
            ints("targetOrdinals", emptyList())
            num("chapterReadCount", 0)
            put("readingProgress", JsonNull)
        })
    }

    fun multi(fragments: List<Pair<Book, Key>>, compare: Boolean): JsonObject {
        val frags = fragments.mapNotNull { (book, key) ->
            try { book to osisFragment(Content.readOsisFragment(book, key), key, book) } catch (e: OsisError) { null }
        }
        val strongs = !compare && frags.any { (b, _) ->
            b.hasFeature(FeatureType.HEBREW_DEFINITIONS) || b.hasFeature(FeatureType.GREEK_DEFINITIONS) || b.hasFeature(FeatureType.GREEK_PARSE)
        }
        return buildJsonObject {
            str("id", UUID.randomUUID().toString())
            str("type", "multi")
            arr("osisFragments", frags.map { it.second })
            bool("compare", compare)
            str("contentType", if (strongs) "strongs" else null)
        }
    }

    fun myNotes(range: VerseRange): JsonObject {
        val kjv = range.toV11n(KJVA)
        val bms = Bookmarks.bibleBookmarksIn(kjv).filter { !it.notes.isNullOrBlank() }
        return buildJsonObject {
            str("id", kjv.uniqueId)
            str("type", "notes")
            arr("bookmarks", bms.map { clientBibleBookmark(it, KJVA) })
            range("ordinalRange", kjv.start.ordinal..kjv.end.ordinal)
            str("verseRange", kjv.name)
        }
    }

    fun studyPad(label: Label): JsonObject {
        Bookmarks.normalizeStudyPadOrder(label.id)
        val bms = Bookmarks.bibleBookmarksWithLabel(label.id)
        val gbms = Bookmarks.genericBookmarksWithLabel(label.id)
        return buildJsonObject {
            str("id", "journal_${label.id}")
            str("type", "journal")
            arr("bookmarks", bms.map { clientBibleBookmark(it, null, withFragment = true) })
            arr("genericBookmarks", gbms.map { clientGenericBookmark(it, withFragment = true) })
            arr("bookmarkToLabels", Bookmarks.bibleBookmarkToLabelsFor(label.id).map { bookmarkToLabel(it, false) })
            arr("genericBookmarkToLabels", Bookmarks.genericBookmarkToLabelsFor(label.id).map { bookmarkToLabel(it, true) })
            arr("journalTextEntries", Bookmarks.studyPadEntries(label.id).map { studyPadEntry(it) })
            put("label", clientLabel(label))
        }
    }

    fun studyPadEntry(e: StudyPadTextEntry): JsonObject = buildJsonObject {
        str("id", e.id)
        str("labelId", e.labelId)
        num("orderNumber", e.orderNumber)
        num("indentLevel", e.indentLevel)
        str("text", e.text)
        str("contentType", null)
        str("sourcePromptId", null)
        str("type", "journal")
        num("hashCode", abs(e.id.hashCode()))
    }

    fun bookmarkToLabel(b: BookmarkToLabel, generic: Boolean): JsonObject = buildJsonObject {
        str("bookmarkId", b.bookmarkId)
        str("labelId", b.labelId)
        num("orderNumber", b.orderNumber)
        num("indentLevel", b.indentLevel)
        bool("expandContent", b.expandContent)
        str("type", if (generic) "GenericBookmarkToLabel" else "BibleBookmarkToLabel")
    }

    fun clientLabel(l: Label): JsonObject = buildJsonObject {
        str("id", l.id)
        str("name", l.displayName.trim())
        put("style", buildJsonObject {
            num("color", l.color)
            bool("isSpeak", l.isSpeak)
            bool("isParagraphBreak", l.isParagraphBreak)
            bool("underline", l.underlineStyle)
            bool("underlineWholeVerse", l.underlineStyleWholeVerse)
            bool("markerStyle", l.markerStyle)
            bool("markerStyleWholeVerse", l.markerStyleWholeVerse)
            bool("hideStyle", l.hideStyle)
            bool("hideStyleWholeVerse", l.hideStyleWholeVerse)
            str("customIcon", l.customIcon)
        })
        bool("isRealLabel", !l.isSpecial)
    }

    private fun labelsOf(id: String, generic: Boolean): List<String> =
        Bookmarks.labelIdsFor(id, generic).ifEmpty { listOf(UNLABELED_LABEL_ID) }

    private fun editAction(e: EditAction?) = buildJsonObject { str("mode", e?.mode); str("content", e?.content) }

    fun clientBibleBookmark(b: BibleBookmark, v11n: Versification?, withFragment: Boolean = false): JsonObject {
        val book = Sword.book(b.bookInitials) as? SwordBook
        val vr = b.verseRange
        val target = vr.toV11n(v11n)
        val notes = b.notes?.takeIf { it.isNotBlank() }
        val text = Bookmarks.text(b)
        val fragment = if (withFragment) {
            val fb = book ?: Sword.bibles.firstOrNull()
            fb?.let { runCatching { val r = vr.toV11n(it.versification); osisFragment(Content.readOsisFragment(it, r), r, it) }.getOrNull() }
        } else null
        return buildJsonObject {
            str("id", b.id)
            num("hashCode", abs(b.id.hashCode()))
            range("ordinalRange", target.start.ordinal..target.end.ordinal)
            range("originalOrdinalRange", vr.start.ordinal..vr.end.ordinal)
            put("offsetRange", if (b.wholeVerse || book == null || b.startOffset == null) JsonNull
                else JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(b.startOffset), kotlinx.serialization.json.JsonPrimitive(b.endOffset))))
            strs("labels", labelsOf(b.id, false))
            str("bookInitials", book?.initials)
            str("bookName", book?.name)
            str("bookAbbreviation", book?.abbreviation)
            num("createdAt", b.createdAt)
            num("lastUpdatedOn", b.lastUpdatedOn)
            str("notes", notes)
            bool("hasNote", notes != null)
            str("verseRange", vr.name)
            str("verseRangeOnlyNumber", vr.onlyNumber)
            str("verseRangeAbbreviated", vr.abbreviated)
            str("text", text)
            str("osisRef", vr.osisRef)
            str("v11n", (book?.versification ?: KJVA).name)
            str("fullText", text)
            arr("bookmarkToLabels", Bookmarks.bookmarkToLabels(b.id, false).map { bookmarkToLabel(it, false) })
            put("osisFragment", fragment ?: JsonNull)
            str("type", "bookmark")
            str("primaryLabelId", b.primaryLabelId ?: labelsOf(b.id, false).first())
            bool("wholeVerse", b.wholeVerse || book == null)
            str("customIcon", b.customIcon)
            str("notesContentType", null)
            put("editAction", editAction(b.editAction))
            str("sourcePromptId", null)
        }
    }

    fun clientGenericBookmark(g: GenericBookmark, withFragment: Boolean = false): JsonObject {
        val book = Sword.book(g.bookInitials)
        val key = book?.let { runCatching { it.getKey(g.key) }.getOrNull() }
        val notes = g.notes?.takeIf { it.isNotBlank() }
        val text = Bookmarks.text(g)
        val fragment = if (withFragment && book != null && key != null)
            runCatching { osisFragment(Content.readOsisFragment(book, key), key, book) }.getOrNull() else null
        return buildJsonObject {
            str("id", g.id)
            str("key", g.key)
            str("keyName", key?.name ?: g.key)
            num("hashCode", abs(g.id.hashCode()))
            range("ordinalRange", if (g.ordinalStart != null && g.ordinalEnd != null) g.ordinalStart!!..g.ordinalEnd!! else null)
            put("offsetRange", if (g.wholeVerse || g.startOffset == null) JsonNull
                else JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(g.startOffset), kotlinx.serialization.json.JsonPrimitive(g.endOffset))))
            strs("labels", labelsOf(g.id, true))
            str("bookInitials", g.bookInitials)
            str("bookName", book?.name ?: g.bookInitials)
            str("bookAbbreviation", book?.abbreviation ?: g.bookInitials)
            num("createdAt", g.createdAt)
            num("lastUpdatedOn", g.lastUpdatedOn)
            str("notes", notes)
            bool("hasNote", notes != null)
            str("text", text)
            str("fullText", text)
            str("highlightedText", text)
            arr("bookmarkToLabels", Bookmarks.bookmarkToLabels(g.id, true).map { bookmarkToLabel(it, true) })
            put("osisFragment", fragment ?: JsonNull)
            str("type", "generic-bookmark")
            str("primaryLabelId", g.primaryLabelId ?: labelsOf(g.id, true).first())
            bool("wholeVerse", g.wholeVerse)
            str("customIcon", g.customIcon)
            str("notesContentType", null)
            put("editAction", editAction(g.editAction))
            str("sourcePromptId", null)
        }
    }
}
