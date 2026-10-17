/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
@file:Suppress("unused")

package net.bible.android.misc

import org.crosswire.jsword.book.Book
import org.crosswire.jsword.passage.Key
import org.jdom2.Element

/** AndBible's OsisFragment (ClientPageObjects.kt), as attached to bookmarks. */
class OsisFragment(val xml: Element, val key: Key, private val book: Book) {
    val xmlStr: String get() = org.linbible.Content.elementToString(xml)
    val hasChapter: Boolean get() = xml.getChild("div")?.getChild("chapter") != null
}

fun elementToString(e: Element): String = org.linbible.Content.elementToString(e)
