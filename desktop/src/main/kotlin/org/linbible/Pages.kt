/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
package org.linbible

import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.BookCategory
import org.crosswire.jsword.book.sword.SwordBook
import org.crosswire.jsword.passage.Key
import org.crosswire.jsword.passage.Passage
import org.crosswire.jsword.passage.PassageKeyFactory
import org.crosswire.jsword.passage.RestrictionType
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.passage.VerseRange
import org.crosswire.jsword.versification.Versification
import org.crosswire.jsword.versification.system.Versifications

/** What a reading window shows. */
sealed class Page {
    abstract val title: String
    open val book: Book? get() = null

    /** Bible text, a whole chapter at a time, with infinite scrolling inside the Bible book. */
    data class Bible(override val book: SwordBook, val verse: Verse, val highlight: VerseRange? = null) : Page() {
        override val title: String get() = "${book.abbreviation} – ${verse.name}"
    }

    /** Commentary, a verse block at a time, following the Bible verse. */
    data class Commentary(override val book: SwordBook, val verse: Verse) : Page() {
        override val title: String get() = "${book.abbreviation} – ${verse.name}"
    }

    /** A keyed entry of a dictionary, general book, or a specific commentary key. */
    data class Keyed(override val book: Book, val key: Key, val highlight: IntRange? = null) : Page() {
        override val title: String get() = "${book.abbreviation} – ${key.name}"
    }

    data class Multi(val items: List<Pair<Book, Key>>, val compare: Boolean, override val title: String) : Page()
    data class StudyPad(val labelId: String, val entryId: String? = null) : Page() {
        override val title: String get() = "StudyPad – ${Bookmarks.label(labelId)?.displayName ?: ""}"
    }
    data class MyNotes(val range: VerseRange) : Page() {
        override val title: String get() = "My Notes – ${range.name}"
    }
    data class Message(val text: String) : Page() {
        override val title: String get() = "LinBible"
    }

    val category: BookCategory? get() = when (this) {
        is Bible -> BookCategory.BIBLE
        is Commentary -> BookCategory.COMMENTARY
        is Keyed -> book.bookCategory
        else -> null
    }

    /** The Bible verse this page is at, if it is verse based. */
    val currentVerse: Verse? get() = when (this) {
        is Bible -> verse
        is Commentary -> verse
        is Keyed -> (key as? VerseRange)?.start ?: (key as? Verse)
        else -> null
    }

    fun toState(sync: Boolean, weight: Double): WindowState? = when (this) {
        is Bible -> WindowState("BIBLE", book.initials, verse.osisRef, sync, weight)
        is Commentary -> WindowState("COMMENTARY", book.initials, verse.osisRef, sync, weight)
        is Keyed -> WindowState(book.bookCategory.name, book.initials, key.name, sync, weight)
        else -> null
    }

    companion object {
        fun defaultBible(): Page = Sword.bibles.firstOrNull()?.let { Bible(it, Verse(it.versification, org.crosswire.jsword.versification.BibleBook.JOHN, 1, 1)) }
            ?: Message("No Bibles are installed yet. Use Library → Download Documents to install one, for example the KJV.")

        fun fromState(s: WindowState): Page? {
            val book = Sword.book(s.bookInitials) ?: return null
            return try {
                when (s.category) {
                    "BIBLE" -> (book as? SwordBook)?.let { Bible(it, parseVerse(it.versification, s.key) ?: firstVerse(it)) }
                    "COMMENTARY" -> (book as? SwordBook)?.let { Commentary(it, parseVerse(it.versification, s.key) ?: firstVerse(it)) }
                    else -> Keyed(book, s.key?.let { runCatching { book.getKey(it) }.getOrNull() } ?: firstKey(book) ?: return null)
                }
            } catch (e: Exception) {
                Log.e("Could not restore window $s", e); null
            }
        }

        fun firstVerse(book: SwordBook): Verse {
            val v11n = book.versification
            val first = (0 until v11n.bookCount).map { v11n.getBook(it) }.firstOrNull { b -> runCatching { book.contains(Verse(v11n, b, 1, 1)) }.getOrDefault(false) }
                ?: v11n.firstBook
            return Verse(v11n, first, 1, 1)
        }

        fun firstKey(book: Book): Key? = runCatching {
            val global = book.globalKeyList
            if (global.cardinality > 0) global.get(0) else null
        }.getOrNull()

        fun parseVerse(v11n: Versification, ref: String?): Verse? {
            if (ref.isNullOrBlank()) return null
            return try {
                val p = PassageKeyFactory.instance().getKey(v11n, ref) as Passage
                if (p.isEmpty) null else p.getRangeAt(0, RestrictionType.NONE).start
            } catch (e: Exception) { null }
        }

        fun parsePassage(v11n: Versification, ref: String): Passage? = try {
            (PassageKeyFactory.instance().getKey(v11n, ref) as Passage).takeIf { !it.isEmpty }
        } catch (e: Exception) { null }

        fun v11n(name: String?): Versification =
            name?.let { runCatching { Versifications.instance().getVersification(it) }.getOrNull() } ?: KJVA
    }
}
