/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Simplified port of AndBible's CommentaryBlockResolver: a commentary entry often covers several
 * verses (stored as identical entries); such a run of verses is shown as one block.
 */
package org.linbible

import org.crosswire.jsword.book.sword.SwordBook
import org.crosswire.jsword.passage.Verse

data class CommentaryBlock(val start: Verse, val end: Verse)

class CommentaryBlocks(private val book: SwordBook) {
    private val v11n = book.versification
    private val cache = HashMap<Int, String?>()

    private fun render(v: Verse): String? = cache.getOrPut(v.ordinal) {
        try {
            // Verse 0 holds chapter introductions, which the commentary page cannot show as a block
            if (v.verse == 0 || !book.contains(v)) null else Content.plainText(book, v).ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun next(v: Verse): Verse? = if (v.ordinal >= v11n.maximumOrdinal()) null else v11n.decodeOrdinal(v.ordinal + 1)
    private fun prev(v: Verse): Verse? = if (v.ordinal <= 0) null else v11n.decodeOrdinal(v.ordinal - 1)

    /** Block containing [verse], or null when the verse has no entry. */
    fun resolve(verse: Verse): CommentaryBlock? {
        val content = render(verse) ?: return null
        var start = verse
        while (true) { val p = prev(start) ?: break; if (render(p) == content) start = p else break }
        var end = verse
        while (true) { val n = next(end) ?: break; if (render(n) == content) end = n else break }
        return CommentaryBlock(start, end)
    }

    /** Block containing [verse], or else the nearest following (then preceding) block. */
    fun resolveNearest(verse: Verse): CommentaryBlock? =
        resolve(verse) ?: nextBlock(verse) ?: prevBlock(verse)

    fun nextBlock(after: Verse, maxSteps: Int = 5000): CommentaryBlock? {
        var v = after
        repeat(maxSteps) {
            v = next(v) ?: return null
            if (render(v) != null) return resolve(v)
        }
        return null
    }

    fun prevBlock(before: Verse, maxSteps: Int = 5000): CommentaryBlock? {
        var v = before
        repeat(maxSteps) {
            v = prev(v) ?: return null
            if (render(v) != null) return resolve(v)
        }
        return null
    }
}
