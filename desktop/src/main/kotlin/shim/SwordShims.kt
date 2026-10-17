/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
@file:Suppress("unused")

package net.bible.service.sword

import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.BookCategory
import org.crosswire.jsword.book.Books
import org.crosswire.jsword.book.FeatureType
import org.crosswire.jsword.passage.Key
import org.jdom2.Element
import org.linbible.Content

/** AndBible's SwordContentFacade, implemented with LinBible's Content. */
object SwordContentFacade {
    fun readOsisFragment(book: Book?, key: Key?): Element {
        if (book == null || key == null) throw org.linbible.OsisError("No content")
        return Content.readOsisFragment(book, key)
    }
    fun getCanonicalText(book: Book?, key: Key?, compatibleOffsets: Boolean = false): String =
        if (book == null || key == null) "" else Content.plainText(book, key)
    fun getTextWithinOrdinalsAsString(book: Book, key: Key, ordinalRange: IntRange): List<String> =
        Content.textsWithinOrdinals(book, key, ordinalRange)
    fun search(bible: Book, searchText: String?): Key = bible.find(searchText)
    fun evictBook(initials: String) = Content.clearCache()
    fun ordinalRangeFor(book: Book, key: Key): IntRange = Content.ordinalRangeFor(book, key) ?: 0..0
}

object SwordDocumentFacade {
    fun getBooks(bookCategory: BookCategory): List<Book> = Books.installed().getBooks { it.bookCategory == bookCategory }
    fun getDocumentByInitials(initials: String?): Book? = Books.installed().getBook(initials)
    private fun dictionaries(type: FeatureType) = Books.installed().books.filter { it.hasFeature(type) }
    val defaultRobinsonGreekMorphology: List<Book> get() = dictionaries(FeatureType.GREEK_PARSE)
    val defaultStrongsGreekDictionary: List<Book> get() = dictionaries(FeatureType.GREEK_DEFINITIONS)
    val defaultStrongsHebrewDictionary: List<Book> get() = dictionaries(FeatureType.HEBREW_DEFINITIONS)
    val bibles: List<Book> get() = getBooks(BookCategory.BIBLE)
}
