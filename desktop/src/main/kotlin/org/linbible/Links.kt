/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Handles links clicked inside BibleView. Ported from AndBible's BibleView.openLink,
 * LinkControl and UriAnalyzer.
 */
package org.linbible

import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.BookCategory
import org.crosswire.jsword.book.sword.SwordBook
import org.crosswire.jsword.passage.Key
import org.crosswire.jsword.passage.NoSuchKeyException
import org.crosswire.jsword.passage.Passage
import org.crosswire.jsword.passage.RestrictionType
import org.crosswire.jsword.passage.VerseRange
import java.net.URLDecoder

class Links(private val app: MainApp) {

    private fun parseQuery(url: String): List<Pair<String, String>> {
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return emptyList()
        return q.split('&').filter { it.isNotEmpty() }.map {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            dec(k) to dec(v)
        }
    }

    private fun dec(s: String) = try { URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8) } catch (e: Exception) { s }

    fun open(url: String, from: BibleWindow?) {
        Log.d("Open link $url")
        val scheme = url.substringBefore(':', "").lowercase()
        val params = parseQuery(url)
        fun param(name: String) = params.firstOrNull { it.first == name }?.second
        when (scheme) {
            "osis" -> {
                val osisRef = param("osis")
                val docName = param("doc")
                val ordinal = param("ordinal")?.toIntOrNull()
                val v11n = Page.v11n(param("v11n"))
                val book = Sword.book(docName)
                if (book != null && ordinal != null && osisRef != null) {
                    val key = runCatching { book.getKey(osisRef) }.getOrNull()
                    if (key != null) { app.openInLinks(Page.Keyed(book, key, ordinal..ordinal), from); return }
                }
                val ref = osisRef ?: param("content") ?: return
                val forceDoc = param("force-doc") == "true"
                openReference(ref, v11n, if (forceDoc || book?.bookCategory != BookCategory.BIBLE) book else book, from)
            }
            "multi" -> {
                val v11n = Page.v11n(param("v11n"))
                val bible = defaultBible(from) ?: return
                val items = params.filter { it.first == "osis" }.mapNotNull { (_, ref) ->
                    Page.parsePassage(v11n, ref)?.let { bible to (it.toV11n(bible.versification) as Key) }
                }
                if (items.isNotEmpty()) app.openInLinks(Page.Multi(items, false, items.joinToString(", ") { it.second.name }), from)
            }
            "ab-w" -> {
                val items = params.flatMap { (type, value) -> lookupWord(type, value.replace('_', ' ')) }
                if (items.isEmpty()) app.toast("No Strong's or morphology dictionary installed for this word")
                else app.openInLinks(Page.Multi(items, false, items.joinToString(", ") { it.second.name }), from)
            }
            "strongs" -> {
                val ref = url.substringAfter("://").trim('/')
                val items = lookupWord("strong", ref)
                if (items.isEmpty()) app.toast("No Strong's dictionary installed") else app.openInLinks(Page.Multi(items, false, ref), from)
            }
            "morphology" -> {
                val rest = url.substringAfter("://")
                val items = lookupWord(rest.substringBefore('/'), rest.substringAfter('/'))
                if (items.isNotEmpty()) app.openInLinks(Page.Multi(items, false, rest), from)
            }
            "ab-find-all" -> {
                val type = param("type") ?: ""
                var name = (param("name") ?: return).lowercase()
                if (!(name.startsWith("g") || name.startsWith("h"))) name = type.take(1) + name
                app.showSearch("strong:${name.uppercase()}")
            }
            "my-notes" -> {
                val ordinal = param("ordinal")?.toIntOrNull() ?: return
                app.openMyNotes(Page.v11n(param("v11n")).decodeOrdinal(ordinal))
            }
            "journal" -> {
                val id = param("id") ?: return
                app.openStudyPad(id, param("entryId") ?: param("bookmarkId"))
            }
            "sword" -> openSword(url, from)
            "download" -> app.showDownloads(param("initials"))
            "http", "https" -> app.openBrowser(url)
            "ab-error" -> {}
            else -> {
                // Plain references like "bible:John.3.16" or "gdef:01234"
                val (book, key) = analyze(url) ?: run { Log.w("Unsupported link $url"); return }
                showKey(book, key, from)
            }
        }
    }

    private fun defaultBible(from: BibleWindow?): SwordBook? =
        (from?.page?.book as? SwordBook)?.takeIf { it.bookCategory == BookCategory.BIBLE }
            ?: (app.activeWindow?.page?.book as? SwordBook)?.takeIf { it.bookCategory == BookCategory.BIBLE }
            ?: app.windows.mapNotNull { it.page as? Page.Bible }.firstOrNull()?.book
            ?: Sword.bibles.firstOrNull()

    private fun openReference(ref: String, v11n: org.crosswire.jsword.versification.Versification, book: Book?, from: BibleWindow?) {
        if (book != null && book.bookCategory != BookCategory.BIBLE) {
            val key = try { book.getKey(ref) } catch (e: NoSuchKeyException) { null }
            if (key != null) { showKey(book, key, from); return }
        }
        val bible = (book as? SwordBook)?.takeIf { it.bookCategory == BookCategory.BIBLE } ?: defaultBible(from) ?: run {
            app.toast("No Bible installed"); return
        }
        val passage = Page.parsePassage(v11n, ref) ?: Page.parsePassage(bible.versification, ref) ?: run {
            app.toast("Could not understand reference $ref"); return
        }
        showPassage(bible, passage, from)
    }

    fun showPassage(bible: SwordBook, passage: Passage, from: BibleWindow?) {
        val ranges = (0 until passage.countRanges(RestrictionType.NONE)).map { passage.getRangeAt(it, RestrictionType.NONE) }
        if (ranges.size > 1) {
            val items = ranges.map { bible to (it.toV11n(bible.versification) as Key) }
            app.openInLinks(Page.Multi(items, false, passage.name), from)
        } else {
            val r = ranges.first().toV11n(bible.versification)
            val start = if (r.start.verse == 0) org.crosswire.jsword.passage.Verse(r.versification, r.start.book, r.start.chapter, 1) else r.start
            app.openInLinks(Page.Bible(bible, start, highlight = r), from)
        }
    }

    private fun showKey(book: Book, key: Key, from: BibleWindow?) {
        if (book.bookCategory == BookCategory.BIBLE && book is SwordBook) {
            val p = key as? Passage ?: Page.parsePassage(book.versification, key.osisRef)
            if (p != null) { showPassage(book, p, from); return }
        }
        app.openInLinks(Page.Keyed(book, key), from)
    }

    private fun openSword(url: String, from: BibleWindow?) {
        val (book, key) = analyze(url) ?: return
        showKey(book, key, from)
    }

    /** Resolves "protocol:book/key" style references (UriAnalyzer). */
    private fun analyze(url: String): Pair<Book, Key>? {
        val protocol = url.substringBefore(':')
        var ref = url.substringAfter(':').trim('/')
        if (ref.isEmpty()) return null
        when (protocol) {
            "gdef" -> return lookupWord("strong", "G$ref").firstOrNull()
            "hdef" -> return lookupWord("strong", "H$ref").firstOrNull()
            "strong" -> return lookupWord("strong", ref).firstOrNull()
            "robinson" -> return lookupWord("robinson", ref).firstOrNull()
        }
        val bible = defaultBible(null)
        val book: Book? = if (ref.contains('/')) {
            val name = dec(ref.substringBefore('/'))
            ref = dec(ref.substringAfter('/'))
            if (name.equals("bible", true)) bible else Sword.book(name)
        } else {
            val candidate = Sword.book(dec(ref.substringBefore('.')))
            if (candidate != null && candidate.bookCategory != BookCategory.BIBLE) {
                ref = dec(ref.substringAfter('.')); candidate
            } else {
                ref = dec(ref); bible
            }
        }
        book ?: return null
        if (book.hasFeature(org.crosswire.jsword.book.FeatureType.GREEK_DEFINITIONS) || book.hasFeature(org.crosswire.jsword.book.FeatureType.HEBREW_DEFINITIONS)) {
            strongsKey(book, ref)?.let { return book to it }
        }
        val key = runCatching { book.getKey(ref.replace(Regex("_(\\d+)_")) { it.groupValues[1].toInt().toChar().toString() }) }.getOrNull() ?: return null
        return book to key
    }

    /** Dictionary entries for a Strong's number ("strong") or morphology code ("robinson", ...). */
    fun lookupWord(type: String, value: String): List<Pair<Book, Key>> {
        val v = value.trim()
        if (v.isEmpty()) return emptyList()
        return when (type.lowercase()) {
            "strong", "lemma.strong" -> {
                val books = when {
                    v.startsWith("G", true) -> Sword.greekDictionaries()
                    v.startsWith("H", true) -> Sword.hebrewDictionaries()
                    else -> Sword.greekDictionaries() + Sword.hebrewDictionaries()
                }
                books.mapNotNull { b -> strongsKey(b, v)?.let { b to it } }
            }
            "robinson", "morph.robinson", "robinson-x", "strongmorph" ->
                Sword.greekMorphology().mapNotNull { b -> runCatching { b.getKey(v) }.getOrNull()?.let { b to it } }
            "packard", "morph" ->
                (Sword.greekMorphology() + Sword.hebrewMorphology()).mapNotNull { b -> runCatching { b.getKey(v) }.getOrNull()?.let { b to it } }
            else -> emptyList()
        }
    }

    fun strongsKey(book: Book, key: String): Key? = strongsKeyFor(book, key)

    companion object {
    private val keyStyle = HashMap<String, Int>()

    /** Strong's dictionaries use differing key conventions (G3056, 03056, 3056, ...). */
    fun strongsKeyFor(book: Book, key: String): Key? = synchronized(keyStyle) {
        val m = Regex("^([GHgh]?)(0*)([0-9]+).*").find(key) ?: return runCatching { book.getKey(key) }.getOrNull()
        val category = m.groupValues[1].uppercase().ifEmpty {
            if (book.hasFeature(org.crosswire.jsword.book.FeatureType.HEBREW_DEFINITIONS)) "H" else "G"
        }
        val base = m.groupValues[3]
        val padded = base.padStart(5, '0')
        val options = listOf(key, padded, padded + "\r", category + base, base)
        val preferred = keyStyle[book.initials]
        val order = if (preferred != null) listOf(preferred) + options.indices.filter { it != preferred } else options.indices.toList()
        for (i in order) {
            val k = try { book.getKey(options[i]) } catch (e: NoSuchKeyException) { null } catch (e: Exception) { null }
            if (k != null && k.cardinality > 0) { keyStyle[book.initials] = i; return k }
        }
        return null
    }
    }
}

/** Listing and stepping through keys of dictionaries and general books. */
object KeyNavigation {
    private val cache = HashMap<String, List<Key>>()

    fun keys(book: Book): List<Key> = synchronized(cache) {
        cache.getOrPut(book.initials) {
            val result = ArrayList<Key>()
            try {
                val global = book.globalKeyList
                if (book.bookCategory == BookCategory.GENERAL_BOOK) {
                    fun walk(k: Key) {
                        if (k.name.isNotEmpty()) result.add(k)
                        for (i in 0 until k.childCount) walk(k.get(i))
                    }
                    walk(global)
                    if (result.isEmpty()) global.forEach { result.add(it) }
                } else {
                    global.forEach { result.add(it) }
                }
            } catch (e: Exception) {
                Log.e("Could not list keys of ${book.initials}", e)
            }
            result
        }
    }

    fun invalidate(book: Book) = synchronized(cache) { cache.remove(book.initials) }

    fun step(book: Book, key: Key, delta: Int): Key? {
        val list = keys(book)
        val idx = list.indexOfFirst { it == key || it.osisRef == key.osisRef }
        if (idx < 0) return null
        return list.getOrNull(idx + delta)
    }

    fun depth(key: Key): Int {
        var d = 0
        var p = key.parent
        while (p != null && p.name.isNotEmpty()) { d++; p = p.parent }
        return d
    }
}

fun Passage.toV11n(v11n: org.crosswire.jsword.versification.Versification): VerseRange {
    val r = getRangeAt(0, RestrictionType.NONE)
    return r.toV11n(v11n)
}
