/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
package org.linbible

import org.crosswire.common.util.CWProject
import org.crosswire.common.util.WebResource
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.BookCategory
import org.crosswire.jsword.book.Books
import org.crosswire.jsword.book.BooksEvent
import org.crosswire.jsword.book.BooksListener
import org.crosswire.jsword.book.FeatureType
import org.crosswire.jsword.book.sword.SwordBook
import org.crosswire.jsword.book.sword.SwordBookPath
import org.crosswire.jsword.index.lucene.LuceneIndexManager
import org.crosswire.jsword.passage.PassageKeyFactory
import org.crosswire.jsword.passage.PassageType
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.passage.VerseRange
import org.crosswire.jsword.versification.Versification
import org.crosswire.jsword.versification.VersificationConverter
import org.crosswire.jsword.versification.system.SystemKJVA
import org.crosswire.jsword.versification.system.Versifications
import java.io.File
import kotlin.math.max

/** KJV with apocrypha; bookmarks are stored in this versification, as in AndBible. */
val KJVA: Versification by lazy { Versifications.instance().getVersification(SystemKJVA.V11N_NAME) }

private val converter = VersificationConverter()

fun Verse.toV11n(v11n: Versification): Verse =
    if (versification == v11n) this else converter.convert(this, v11n)

fun VerseRange.toV11n(v11n: Versification?): VerseRange {
    if (v11n == null || versification == v11n) return this
    val s = Verse(start.versification, start.book, max(start.chapter, 1), max(start.verse, 1))
    val e = Verse(end.versification, end.book, max(end.chapter, 1), max(end.verse, 1))
    return VerseRange(v11n, s.toV11n(v11n), e.toV11n(v11n))
}

object Sword {
    private val listeners = mutableListOf<() -> Unit>()

    fun init() {
        // JSword's writable home (search indexes, caches) must be set before anything touches CWProject.
        val jswordHome = File(AppDirs.data, "jsword").apply { mkdirs() }
        File(jswordHome, LuceneIndexManager.DIR_LUCENE).mkdirs()
        System.setProperty("jsword.home", jswordHome.absolutePath)
        CWProject.setHome("jsword.home", jswordHome.absolutePath, null)
        CWProject.instance().setFrontendName("linbible")
        SwordBookPath.setDownloadDir(AppDirs.sword)
        Log.i("JSword home: ${CWProject.instance().writableProjectDir}")
        PassageKeyFactory.setDefaultType(PassageType.MIX)
        WebResource.setTimeout(15000)
        Books.installed().addBooksListener(object : BooksListener {
            override fun bookAdded(ev: BooksEvent) = fire()
            override fun bookRemoved(ev: BooksEvent) = fire()
        })
    }

    fun onBooksChanged(l: () -> Unit) { listeners += l }
    private fun fire() = listeners.toList().forEach { runCatching { it() } }

    val installed: List<Book> get() = Books.installed().books.sortedBy { it.initials.lowercase() }

    fun byCategory(cat: BookCategory): List<Book> = installed.filter { it.bookCategory == cat }

    val bibles: List<SwordBook> get() = byCategory(BookCategory.BIBLE).filterIsInstance<SwordBook>()
    val commentaries: List<SwordBook> get() = byCategory(BookCategory.COMMENTARY).filterIsInstance<SwordBook>()
    val dictionaries: List<Book> get() = byCategory(BookCategory.DICTIONARY)
    val generalBooks: List<Book> get() = byCategory(BookCategory.GENERAL_BOOK)

    fun book(initials: String?): Book? = initials?.let { runCatching { Books.installed().getBook(it) }.getOrNull() }

    fun greekDictionaries(): List<Book> = installed.filter { it.hasFeature(FeatureType.GREEK_DEFINITIONS) }
    fun hebrewDictionaries(): List<Book> = installed.filter { it.hasFeature(FeatureType.HEBREW_DEFINITIONS) }
    fun greekMorphology(): List<Book> = installed.filter { it.hasFeature(FeatureType.GREEK_PARSE) }
    fun hebrewMorphology(): List<Book> = installed.filter { it.hasFeature(FeatureType.HEBREW_PARSE) }
}
