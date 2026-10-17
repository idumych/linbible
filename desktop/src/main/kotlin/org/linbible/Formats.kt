/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Documents in other formats. MyBible (*.SQLite3), MySword (*.mybible) and e-Sword (*.bblx, *.bbli) use AndBible's
 * own backends (net.bible.service.sword.*, compiled unmodified); EPUB books are handled by Epub.kt.
 */
package org.linbible

import net.bible.service.sword.esword.addESwordBook
import net.bible.service.sword.esword.addManuallyInstalledESwordBooks
import net.bible.service.sword.mybible.addManuallyInstalledMyBibleBooks
import net.bible.service.sword.mybible.addMyBibleBook
import net.bible.service.sword.mysword.addManuallyInstalledMySwordBooks
import net.bible.service.sword.mysword.addMySwordBook
import java.io.File

object Formats {
    fun register(f: File) {
        when (f.parentFile.name) {
            "mybible" -> addMyBibleBook(f)
            "mysword" -> addMySwordBook(f)
            "esword" -> addESwordBook(f)
        }
    }

    fun registerAll() {
        runCatching { addManuallyInstalledMyBibleBooks() }.onFailure { Log.e("MyBible modules", it) }
        runCatching { addManuallyInstalledMySwordBooks() }.onFailure { Log.e("MySword modules", it) }
        runCatching { addManuallyInstalledESwordBooks() }.onFailure { Log.e("e-Sword modules", it) }
        runCatching { Epub.registerAll() }.onFailure { Log.e("EPUB books", it) }
    }

    fun installEpub(f: File) = Epub.install(f)
}
