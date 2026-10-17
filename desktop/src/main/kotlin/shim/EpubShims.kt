/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
@file:Suppress("unused")

package net.bible.service.sword.epub

import org.crosswire.jsword.book.Book
import org.crosswire.jsword.passage.Key

val Book.isEpub: Boolean get() = org.linbible.Epub.isEpub(this)

class EpubBackendInfo(private val state: org.linbible.EpubState) {
    val tocKeys: List<Key> get() = state.tocKeys.map { it.first }
}

val Book.epubBackend: EpubBackendInfo? get() = org.linbible.Epub.state(this)?.let { EpubBackendInfo(it) }
