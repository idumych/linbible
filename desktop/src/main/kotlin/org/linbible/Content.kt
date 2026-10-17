/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Text extraction ported from AndBible's SwordContentFacade.
 */
package org.linbible

import org.apache.commons.text.StringEscapeUtils
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.BookCategory
import org.crosswire.jsword.book.BookData
import org.crosswire.jsword.book.Books
import org.crosswire.jsword.book.OSISUtil
import org.crosswire.jsword.passage.Key
import org.jdom2.Element
import org.jdom2.Namespace
import org.jdom2.Text
import org.jdom2.filter.Filters
import org.jdom2.output.Format
import org.jdom2.output.XMLOutputter
import org.jdom2.output.support.AbstractXMLOutputProcessor
import org.jdom2.output.support.FormatStack
import org.jdom2.xpath.XPathFactory
import java.io.Writer
import java.util.Collections

class OsisError(message: String) : Exception(message)

object Content {
    private const val CACHE_SIZE = 40

    private val fragmentCache: MutableMap<String, Element> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Element>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Element>?) = size > CACHE_SIZE
        })

    fun clearCache() = fragmentCache.clear()

    /** Reads the OSIS XML of [key] in [book], adding BibleView anchors (BVA) to non-Bible texts. */
    @Throws(OsisError::class)
    fun readOsisFragment(book: Book, key: Key): Element {
        val cacheKey = "${book.initials}-${key.osisRef}"
        fragmentCache[cacheKey]?.let { return it }
        if (Books.installed().getBook(book.initials) == null) {
            throw OsisError("Document ${book.initials} is not installed")
        }
        return synchronized(book) {
            val frag = try {
                BookData(book, key).osisFragment
            } catch (e: Exception) {
                Log.e("Error reading ${book.initials} $key", e)
                throw OsisError("Could not read ${key.name} from ${book.initials}: ${e.message}")
            }
            if (book.bookCategory == BookCategory.COMMENTARY && key.cardinality == 1) {
                val verse = frag.getChild("verse") ?: throw OsisError("${key.name} not found in ${book.initials}")
                val verseContent = verse.content.toList()
                verse.removeContent()
                frag.removeContent()
                frag.addContent(verseContent)
                addAnchors(frag)
            } else if (book.bookCategory != BookCategory.BIBLE) {
                addAnchors(frag)
            }
            fragmentCache[cacheKey] = frag
            frag
        }
    }

    fun elementToString(e: Element): String {
        val processor = object : AbstractXMLOutputProcessor() {
            override fun printText(out: Writer, fstack: FormatStack, text: Text) {
                // OSIS may contain html-encoded entities; avoid double encoding.
                text.text = StringEscapeUtils.unescapeHtml4(text.text)
                super.printText(out, fstack, text)
            }
        }
        return XMLOutputter(Format.getRawFormat(), processor).outputString(e)
    }

    /** Plain text of [key] (no notes, no headings), used for copying, bookmarks and search results. */
    fun plainText(book: Book, key: Key): String = try {
        OSISUtil.getCanonicalText(BookData(book, key).osisFragment).replace(Regex("\\s+"), " ").trim()
    } catch (e: Exception) {
        Log.e("plainText failed for ${book.initials} $key", e)
        ""
    }

    /** True if [book] has an entry for (any part of) [key]. */
    fun contains(book: Book, key: Key): Boolean = try {
        key.any { book.contains(it) }
    } catch (e: Exception) {
        false
    }

    // ---- BibleView anchors (ported verbatim; logic must stay identical to AndBible for bookmark compatibility) ----

    private const val TARGET_MAX_LENGTH = 150
    private val regexCache = HashMap<Int, Regex>()

    private fun getCutRegex(targetLength: Int): Regex = synchronized(regexCache) {
        regexCache.getOrPut(targetLength) { Regex("""(.{$targetLength}\p{Z}\p{L}+\p{Z}+)(\p{L}+\p{Z}.*)""") }
    }

    private fun cutLongSentences(p: String): List<String> {
        val newPieces = mutableListOf<String>()
        if (p.length > TARGET_MAX_LENGTH) {
            val m = getCutRegex(p.length / 2).find(p)
            if (m == null) {
                newPieces.add(p)
            } else {
                for (part in listOf(p.slice(0 until m.range.first) + m.groupValues[1], m.groupValues[2])) {
                    if (part.length > TARGET_MAX_LENGTH * 1.1) newPieces.addAll(cutLongSentences(part))
                    else newPieces.add(part)
                }
            }
        } else {
            newPieces.add(p)
        }
        return newPieces
    }

    private val splitMatch = Regex("" +
        """((\d{2,}|\D)""" +
        """(([.,;:!?。，；]["'\p{Pf}]?\p{Z}+)|(\p{Z}*\p{Pd}\p{Z}*)))""" +
        """(["'¡¿\p{Pi}]?\p{L})"""
    )

    fun splitSentences(text: String): List<String> {
        val pieces = mutableListOf<String>()
        var lastStartPosition = 0
        val currentPiece = StringBuilder()
        fun addStr(s: String) {
            if (s.length > TARGET_MAX_LENGTH) pieces.addAll(cutLongSentences(s)) else pieces.add(s)
        }
        for (m in splitMatch.findAll(text)) {
            currentPiece.append(text.slice(lastStartPosition until m.range.first) + m.groupValues[1])
            addStr(currentPiece.toString())
            currentPiece.clear()
            currentPiece.append(m.groupValues[6])
            lastStartPosition = m.range.last + 1
        }
        currentPiece.append(text.slice(lastStartPosition until text.length))
        if (currentPiece.isNotEmpty()) addStr(currentPiece.toString())
        return pieces
    }

    private val textQuery = XPathFactory.instance().compile(".//text()[not(ancestor::note)]", Filters.text())
    private val xhtml = Namespace.getNamespace("http://www.w3.org/1999/xhtml")
    private val bvaQuery = XPathFactory.instance().compile(".//ns:BVA", Filters.element(), null, Namespace.getNamespace("ns", "http://www.w3.org/1999/xhtml"))

    fun addAnchors(frag: Element): Int {
        var ordinal = 0
        for (content in textQuery.evaluate(frag)) {
            if (content.text.trim().isEmpty()) continue
            val parent = content.parentElement
            val textContents = splitSentences(content.text)
            if (textContents.isNotEmpty()) {
                var pos = parent.indexOf(content)
                content.detach()
                for (textContent in textContents) {
                    val bva = Element("BVA", xhtml)
                    bva.setAttribute("ordinal", "${ordinal++}")
                    bva.addContent(Text(textContent))
                    parent.addContent(pos++, bva)
                }
            }
        }
        return ordinal
    }

    /** Ordinal range of BVA anchors within a non-Bible fragment. */
    fun ordinalRangeFor(book: Book, key: Key): IntRange? = try {
        val ords = bvaQuery.evaluate(readOsisFragment(book, key)).map { it.getAttributeValue("ordinal").toInt() }
        if (ords.isEmpty()) 0..0 else ords.min()..ords.max()
    } catch (e: OsisError) {
        null
    }

    /** Text of the BVA anchors within [range], for generic (non-Bible) bookmarks. */
    fun textWithinOrdinals(book: Book, key: Key, range: IntRange): String = try {
        bvaQuery.evaluate(readOsisFragment(book, key))
            .filter { it.getAttributeValue("ordinal").toInt() in range }
            .joinToString(" ") { it.value.trim() }
    } catch (e: OsisError) {
        ""
    }
}
