/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
@file:Suppress("unused")

package net.bible.android.control.link

import org.crosswire.jsword.book.Book
import org.crosswire.jsword.passage.Key

val Book.isHebrewDef get() = bookMetaData.getValues("Feature")?.contains("HebrewDef") == true
val Book.isGreekDef get() = bookMetaData.getValues("Feature")?.contains("GreekDef") == true

class BookKey(val key: Key, val document: Book?)

/** The part of AndBible's LinkControl used by its AI tools. */
class LinkControl {
    /** Opens a page of an AI / My Document in the links window (LinkControl.openAIDocument). */
    fun openAIDocument(documentInitials: String, pageKey: String) {
        val app = org.linbible.MainApp.instance ?: return
        val book = org.crosswire.jsword.book.Books.installed().getBook(documentInitials) ?: return
        val key = runCatching { book.getKey(pageKey) }.getOrNull() ?: return
        app.openInLinks(org.linbible.Page.Keyed(book, key), app.activeWindow)
    }

    fun openStudyPad(labelId: net.bible.android.database.IdType, scrollToEntryId: net.bible.android.database.IdType?) {
        org.linbible.MainApp.instance?.openStudyPad(labelId.toString(), scrollToEntryId?.toString())
    }

    fun getStrongsKey(book: Book, key: String): BookKey? =
        org.linbible.Links.strongsKeyFor(book, key)?.let { BookKey(it, book) }
}
