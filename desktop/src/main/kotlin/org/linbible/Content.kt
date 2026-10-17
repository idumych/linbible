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
            } else if (book.bookCategory != BookCategory.BIBLE && !Epub.isEpub(book)) {
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
        val handler = CanonicalTextHandler()
        BookData(book, key).saxEventProvider.provideSAXEvents(handler)
        handler.text.trim()
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

    // IMPORTANT (AndBible): the logic of this function must never change, or bookmark locations in non-Bible books
    // (and EPUBs) are messed up.
    fun addAnchors(frag: Element, lang: String = "en", isEpub: Boolean = false): Int {
        var ordinal = 0
        fun addContent(span: Element, textContent: String) {
            if (isEpub) {
                for ((t, isRef) in bibleRefSplit(textContent)) {
                    if (!isRef) {
                        span.addContent(Text(t))
                    } else {
                        val osisRef = resolveRef(t, KJVA)?.osisRef
                        if (osisRef == null) {
                            span.addContent(Text(t))
                        } else {
                            val refNode = Element("reference", xhtml)
                            refNode.addContent(Text(t))
                            refNode.setAttribute("osisRef", osisRef)
                            span.addContent(refNode)
                        }
                    }
                }
            } else {
                span.addContent(Text(textContent))
            }
        }
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
                    addContent(bva, textContent)
                    parent.addContent(pos++, bva)
                }
            }
        }
        return ordinal
    }

    // Detect bible references, like 1 John 2:3-4, 4:5-6:7, 4-5 (SwordContentFacade.bibleRefRe)
    private val bibleRefRe = Regex("" +
        """(((\d\.?\p{Z}+)?\p{Lu}\p{L}+\.?)\p{Z}+((\d+)(:\d+)?)(\p{Pd}\d+(:\d+)?)?)""" +
        """([,;]?\p{Z}*(\d+:\d+|\d+)(?!\.?\p{Z}*\p{L})(\p{Pd}\d+(:\d+)?)?)*"""
    )

    fun bibleRefSplit(text: String): List<Pair<String, Boolean>> {
        val pieces = mutableListOf<Pair<String, Boolean>>()
        var last = 0
        for (m in bibleRefRe.findAll(text)) {
            val prev = text.slice(last until m.range.first)
            if (prev.isNotEmpty()) pieces.add(prev to false)
            pieces.add(m.groupValues[0] to true)
            last = m.range.last + 1
        }
        val leftover = text.slice(last until text.length)
        if (leftover.isNotEmpty()) pieces.add(leftover to false)
        return pieces
    }

    private val dashesRe = Regex("""\p{Pd}""")

    /** SwordContentFacade.resolveRef (without fuzzy book-name matching). */
    fun resolveRef(ref: String, v11n: org.crosswire.jsword.versification.Versification): Key? {
        val searchRef = ref.replace(dashesRe, "-")
        val names = org.crosswire.jsword.versification.BibleNames.instance()
        return synchronized(names) {
            val orig = names.enableFuzzy
            names.enableFuzzy = false
            try {
                val k = runCatching { org.crosswire.jsword.passage.PassageKeyFactory.instance().getKey(v11n, searchRef) }.getOrNull()
                val chapter = runCatching { k?.getRangeAt(0, org.crosswire.jsword.passage.RestrictionType.NONE)?.start?.chapter }.getOrNull()
                if (k != null && chapter == 0) null else k
            } finally {
                names.enableFuzzy = orig
            }
        }
    }

    /** Ordinal range of BVA anchors within a non-Bible fragment. */
    fun ordinalRangeFor(book: Book, key: Key): IntRange? = Epub.state(book)?.getOrdinalRange(key) ?: try {
        val ords = bvaQuery.evaluate(readOsisFragment(book, key)).map { it.getAttributeValue("ordinal").toInt() }
        if (ords.isEmpty()) 0..0 else ords.min()..ords.max()
    } catch (e: OsisError) {
        null
    }

    /** Texts of the BVA anchors within [range] (SwordContentFacade.getTextWithinOrdinalsAsString). */
    fun textsWithinOrdinals(book: Book, key: Key, range: IntRange): List<String> = try {
        bvaQuery.evaluate(readOsisFragment(book, key))
            .filter { it.getAttributeValue("ordinal").toInt() in range }
            .map { it.value }
    } catch (e: OsisError) {
        emptyList()
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

/**
 * Port of AndBible's OsisToCanonicalTextSaxHandler: the canonical text of an OSIS fragment, without notes and titles,
 * keeping the original spacing between words and punctuation.
 */
private class CanonicalTextHandler : org.xml.sax.helpers.DefaultHandler() {
    private val out = StringBuilder()
    private val stack = java.util.ArrayDeque<Boolean>()
    private var spaceJustWritten = true
    val text: String get() = out.toString()

    override fun startDocument() { out.setLength(0); stack.clear(); stack.push(true) }

    override fun startElement(uri: String?, localName: String?, qName: String, attrs: org.xml.sax.Attributes?) {
        val name = if (!localName.isNullOrEmpty()) localName else qName
        when {
            attrs?.getValue("canonical") == "true" -> stack.push(true)
            name == OSISUtil.OSIS_ELEMENT_VERSE -> { stack.push(true); spaceJustWritten = true }
            name == "milestone" -> { write(attrs?.getValue("marker") ?: ""); stack.push(false) }
            name == OSISUtil.OSIS_ELEMENT_NOTE || name == OSISUtil.OSIS_ELEMENT_TITLE -> stack.push(false)
            name == OSISUtil.OSIS_ELEMENT_Q -> { write(attrs?.getValue("marker") ?: ""); stack.push(true) }
            name == OSISUtil.OSIS_ELEMENT_L || name == OSISUtil.OSIS_ELEMENT_LB || name == OSISUtil.OSIS_ELEMENT_P -> {
                write(" "); stack.push(stack.peek())
            }
            else -> stack.push(stack.peek())
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String) {
        val name = if (!localName.isNullOrEmpty()) localName else qName
        if (name == OSISUtil.OSIS_ELEMENT_VERSE) write(" ")
        stack.pop()
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (stack.peek() == true) write(String(ch, start, length))
    }

    private fun write(s: String) {
        if (s.isEmpty()) return
        if (s.isNotBlank()) {
            out.append(s)
            spaceJustWritten = s.last().isWhitespace()
        } else if (!spaceJustWritten) {
            out.append(' ')
            spaceJustWritten = true
        }
    }
}
