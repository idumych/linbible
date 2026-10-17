/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
@file:Suppress("unused")

package net.bible.android.control.page

import org.crosswire.jsword.book.BookCategory

enum class DocumentCategory {
    BIBLE, COMMENTARY, DICTIONARY, GENERAL_BOOK, MAPS, MYNOTE;
    val bookCategory: BookCategory get() = BookCategory.valueOf(this.name)
}

/** CommentaryBlockResolver.renderCommentaryFragmentXml */
internal fun renderCommentaryFragmentXml(book: org.crosswire.jsword.book.Book, key: org.crosswire.jsword.passage.Key): String? {
    val xml = org.jdom2.output.XMLOutputter(org.jdom2.output.Format.getRawFormat()).outputString(net.bible.service.sword.SwordContentFacade.readOsisFragment(book, key))
    return if (xml.isBlank() || xml == "<div/>") null else xml
}
