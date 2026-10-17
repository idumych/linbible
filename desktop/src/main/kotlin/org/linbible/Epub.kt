/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * EPUB books, port of AndBible's EpubBook, EpubBackendState, EpubOptimization and EpubSearch.
 *
 * An EPUB is unpacked into ~/.sword/epub/<file name>, then "optimized" once: every spine document gets BibleView
 * anchors (BVA elements) and is split into fragments of roughly 500 anchors, stored gzipped in <dir>/optimized and
 * indexed in an EpubDatabase (same schema as AndBible's Room database). Fragment ids are the keys of the book, so
 * the splitting must stay identical to AndBible's for bookmarks to sync.
 */
package org.linbible

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.Books
import org.crosswire.jsword.book.basic.AbstractBookDriver
import org.crosswire.jsword.book.sword.AbstractKeyBackend
import org.crosswire.jsword.book.sword.SwordBookMetaData
import org.crosswire.jsword.book.sword.SwordGenBook
import org.crosswire.jsword.book.sword.state.OpenFileState
import org.crosswire.jsword.index.IndexStatus
import org.crosswire.jsword.passage.DefaultKeyList
import org.crosswire.jsword.passage.DefaultLeafKeyList
import org.crosswire.jsword.passage.Key
import org.jdom2.Document
import org.jdom2.Element
import org.jdom2.Namespace
import org.jdom2.filter.Filters
import org.jdom2.input.SAXBuilder
import org.jdom2.output.Format
import org.jdom2.output.XMLOutputter
import org.jdom2.xpath.XPathFactory
import java.io.File
import java.io.StringReader
import java.net.URLDecoder
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipInputStream

private val xhtmlNs: Namespace = Namespace.getNamespace("ns", "http://www.w3.org/1999/xhtml")
private val svgNs: Namespace = Namespace.getNamespace("svg", "http://www.w3.org/2000/svg")
private val xlinkNs: Namespace = Namespace.getNamespace("xlink", "http://www.w3.org/1999/xlink")
private val sanitizeRe = Regex("[^a-zA-z0-9]")

fun epubInitials(dirName: String): String = "Epub-" + dirName.replace(sanitizeRe, "_")

private fun saxBuilder() = SAXBuilder().apply {
    // EPUB XHTML often declares a DTD; never fetch it
    setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    expandEntities = false
}
private fun xp() = XPathFactory.instance()

private val hrefRe = Regex("""^([^#]+)?#?(.*)$""")
private fun fileAndId(href: String): Pair<String, String>? = hrefRe.matchEntire(href)?.let { it.groupValues[1] to it.groupValues[2] }

class EpubFragment(val originalId: String, val ordinalStart: Int, val ordinalEnd: Int, var id: Long = 0) {
    var element: Element? = null
    val fragFileName: String get() = id.toString().padStart(3, '0') + ".xhtml.gz"
}

/** AndBible's EpubDao over JDBC (database file in AndBible's EpubDatabase format, version 2). */
class EpubDao(file: File) : AutoCloseable {
    private val c: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    init {
        c.createStatement().use { st ->
            st.execute("CREATE TABLE IF NOT EXISTS `EpubHtmlToFrag` (`htmlId` TEXT NOT NULL, `fragId` INTEGER NOT NULL, PRIMARY KEY(`htmlId`))")
            st.execute("CREATE TABLE IF NOT EXISTS `EpubFragment` (`originalId` TEXT NOT NULL, `ordinalStart` INTEGER NOT NULL, `ordinalEnd` INTEGER NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS `StyleSheet` (`origId` TEXT NOT NULL, `styleSheetFile` TEXT NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
            st.execute("CREATE INDEX IF NOT EXISTS `index_StyleSheet_origId` ON `StyleSheet` (`origId`)")
            st.execute("CREATE TABLE IF NOT EXISTS `EpubMeta` (`id` INTEGER NOT NULL, `totalCharacters` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            st.execute("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            st.execute("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'b5106d138f8ff91075378a776b0cbb21')")
            st.execute("PRAGMA user_version = 2")
        }
    }

    private fun frags(sql: String, vararg args: Any?): List<EpubFragment> = synchronized(this) {
        c.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(EpubFragment(rs.getString("originalId"), rs.getInt("ordinalStart"), rs.getInt("ordinalEnd"), rs.getLong("id"))) }
            }
        }
    }

    fun getFragment(htmlId: String) = frags("SELECT f.* FROM EpubFragment f JOIN EpubHtmlToFrag e2f ON e2f.fragId = f.id WHERE e2f.htmlId=?", htmlId).firstOrNull()
    fun getFragment(id: Long) = frags("SELECT * from EpubFragment WHERE id=?", id).firstOrNull()

    @Volatile private var fragmentCache: List<EpubFragment>? = null
    fun fragments(): List<EpubFragment> = fragmentCache ?: frags("SELECT f.* FROM EpubFragment f").also { fragmentCache = it }

    fun styleSheets(origId: String): List<String> = synchronized(this) {
        c.prepareStatement("SELECT styleSheetFile FROM StyleSheet WHERE origId=?").use { st ->
            st.setString(1, origId); st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    fun insert(frag: EpubFragment): Long = synchronized(this) {
        fragmentCache = null
        c.prepareStatement("INSERT INTO EpubFragment (originalId, ordinalStart, ordinalEnd) VALUES (?, ?, ?)").use { st ->
            st.setString(1, frag.originalId); st.setInt(2, frag.ordinalStart); st.setInt(3, frag.ordinalEnd); st.executeUpdate()
        }
        c.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
    }

    fun insertHtmlToFrag(htmlId: String, fragId: Long) = synchronized(this) {
        c.prepareStatement("INSERT OR REPLACE INTO EpubHtmlToFrag (htmlId, fragId) VALUES (?, ?)").use { st ->
            st.setString(1, htmlId); st.setLong(2, fragId); st.executeUpdate()
        }
    }

    fun insertStyleSheet(origId: String, file: String) = synchronized(this) {
        c.prepareStatement("INSERT INTO StyleSheet (origId, styleSheetFile) VALUES (?, ?)").use { st ->
            st.setString(1, origId); st.setString(2, file); st.executeUpdate()
        }
    }

    fun getMeta(): Int? = synchronized(this) {
        c.createStatement().use { st -> st.executeQuery("SELECT totalCharacters FROM EpubMeta WHERE id = 0").use { if (it.next()) it.getInt(1) else null } }
    }

    fun setMeta(total: Int) = synchronized(this) {
        c.prepareStatement("INSERT OR REPLACE INTO EpubMeta (id, totalCharacters) VALUES (0, ?)").use { it.setInt(1, total); it.executeUpdate() }
    }

    fun clear() = synchronized(this) {
        fragmentCache = null
        c.createStatement().use { st -> listOf("EpubFragment", "EpubHtmlToFrag", "StyleSheet", "EpubMeta").forEach { st.execute("DELETE FROM $it") } }
    }

    fun <T> tx(f: () -> T): T = synchronized(this) {
        c.autoCommit = false
        try { f().also { c.commit() } } catch (e: Throwable) { c.rollback(); throw e } finally { c.autoCommit = true }
    }

    override fun close() { c.close() }
}

/** Full text search of an EPUB book (EpubSearch, SQLite FTS5). */
class EpubSearch(file: File) {
    private val c: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    val isIndexed: Boolean get() = synchronized(this) {
        c.prepareStatement("SELECT name FROM sqlite_master WHERE type='table' AND name='SearchIndex'").use { it.executeQuery().use { rs -> rs.next() } }
    }
    fun deleteIndex() = synchronized(this) { c.createStatement().use { it.execute("DROP TABLE IF EXISTS SearchIndex") } }
    fun createTable() = synchronized(this) {
        c.createStatement().use { it.execute("CREATE VIRTUAL TABLE SearchIndex USING FTS5(contentText, frag_id UNINDEXED, ordinal UNINDEXED)") }
    }
    fun add(rows: List<Triple<String, Long, Int>>) = synchronized(this) {
        c.autoCommit = false
        c.prepareStatement("INSERT OR IGNORE INTO SearchIndex (contentText, frag_id, ordinal) VALUES (?, ?, ?)").use { st ->
            for ((t, f, o) in rows) { st.setString(1, t); st.setLong(2, f); st.setInt(3, o); st.addBatch() }
            st.executeBatch()
        }
        c.commit(); c.autoCommit = true
    }
    class Result(val fragId: Long, val ordinal: Int, val text: String)
    fun search(text: String): List<Result> = synchronized(this) {
        c.prepareStatement("SELECT frag_id, ordinal, highlight(SearchIndex, 0, '<b>', '</b>') FROM SearchIndex WHERE contentText MATCH ?").use { st ->
            st.setString(1, text)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(Result(rs.getString(1).toLong(), rs.getString(2).toInt(), rs.getString(3))) } }
        }
    }
}

class EpubState(val epubDir: File) : OpenFileState {
    private val dcNs = Namespace.getNamespace("dc", "http://purl.org/dc/elements/1.1/")
    private val opfNs = Namespace.getNamespace("ns", "http://www.idpf.org/2007/opf")
    private val containerNs = Namespace.getNamespace("ns", "urn:oasis:names:tc:opendocument:xmlns:container")
    private val ncxNs = Namespace.getNamespace("ns", "http://www.daisy.org/z3986/2005/ncx/")

    private val metaInfo = saxBuilder().build(File(epubDir, "META-INF/container.xml"))
    private val contentFileName = xp().compile("//ns:rootfile", Filters.element(), null, containerNs).evaluateFirst(metaInfo).getAttribute("full-path").value
    private val contentXmlFile = File(epubDir, contentFileName)
    val rootFolder: File = contentXmlFile.parentFile!!
    private val content = saxBuilder().build(contentXmlFile)

    val fileToId: Map<String, String> = xp().compile("//ns:manifest/ns:item", Filters.element(), null, opfNs).evaluate(content).associate {
        URLDecoder.decode(it.getAttribute("href").value, "UTF-8") to it.getAttribute("id").value
    }
    val idToFile = fileToId.entries.associate { it.value to it.key }

    private val tocFile: File? = (xp().compile("//ns:manifest/ns:item[@media-type='application/x-dtbncx+xml']", Filters.element(), null, opfNs).evaluateFirst(content)
        ?: xp().compile("//ns:manifest/ns:item[@id='toc']", Filters.element(), null, opfNs).evaluateFirst(content)
        ?: xp().compile("//ns:manifest/ns:item[@properties='nav']", Filters.element(), null, opfNs).evaluateFirst(content))
        ?.getAttribute("href")?.value?.let { File(rootFolder, it) }
    private val toc: Document? = tocFile?.let { runCatching { saxBuilder().build(it) }.getOrNull() }

    val fileToTitle: Map<String?, String>? = toc?.let { t ->
        xp().compile("//ns:navPoint/ns:content", Filters.element(), null, ncxNs).evaluate(t).reversed().associate {
            val textElem = xp().compile("../ns:navLabel/ns:text", Filters.element(), null, ncxNs).evaluateFirst(it)
            val fileName = fileAndId(it.getAttribute("src").value)?.first?.let { f -> URLDecoder.decode(f, "UTF-8") }
            fileName to (textElem?.text ?: "")
        }
    }

    private fun queryMetadata(key: String): String? = xp().compile("//dc:$key", Filters.element(), null, dcNs).evaluateFirst(content)?.value

    fun fileForOriginalId(id: String): File? = idToFile[id]?.let { File(rootFolder, it) }

    val fragDir get() = File(epubDir, "optimized")
    private val versionFile get() = File(fragDir, "version.txt")
    val optimizeLockFile get() = File(epubDir, "optimize.lock")
    val optimizerVersion: Long get() = runCatching { versionFile.readText().trim().toLong() }.getOrDefault(1)

    private val initials = epubInitials(epubDir.name)
    private val appDbFile: File get() = File(File(AppDirs.data, "epub").also { it.mkdirs() }, "epub-$initials.sqlite3")
    private val dao: EpubDao
    private val search: EpubSearch

    init {
        var epubDbFile = File(epubDir, "epub-$initials.sqlite3.gz")
        if (!epubDbFile.exists()) epubDbFile = File(epubDir, "optimized.sqlite3.gz")
        if (!epubDbFile.exists()) {
            optimize()
            GZIPOutputStream(epubDbFile.outputStream()).use { out -> appDbFile.inputStream().use { it.copyTo(out) } }
        } else if (!appDbFile.exists()) {
            GZIPInputStream(epubDbFile.inputStream()).use { input -> appDbFile.outputStream().use { input.copyTo(it) } }
        }
        dao = EpubDao(appDbFile)
        search = EpubSearch(File(epubDir, "epub-$initials-search.sqlite3"))
    }

    private val metaData: SwordBookMetaData by lazy {
        val title = queryMetadata("title") ?: epubDir.name
        val description = queryMetadata("description") ?: epubDir.name
        val language = queryMetadata("language") ?: "en"
        val conf = """
[$initials]
Description=$title
Abbreviation=$title
Category=General Books
AndBibleEpubModule=1
AndBibleEpubDir=${epubDir.toRelativeString(OtherFormats.modulesDir)}
Lang=$language
Version=$optimizerVersion
Encoding=UTF-8
SourceType=OSIS
ModDrv=RawGenBook
DistributionLicense=Unknown
About=${description.replace("\n", " ")}
"""
        SwordBookMetaData(conf.toByteArray(), initials).apply {
            driver = EpubDriver
            indexStatus = if (search.isIndexed) IndexStatus.DONE else IndexStatus.UNDONE
        }
    }

    private fun fragment(key: Key): EpubFragment? = key.osisRef.toLongOrNull()?.let { dao.getFragment(it) }

    fun styleSheets(key: Key): List<File> {
        val frag = fragment(key) ?: return emptyList()
        val file = fileForOriginalId(frag.originalId) ?: return emptyList()
        return dao.styleSheets(frag.originalId).map { File(file.parentFile, it).canonicalFile }
    }

    private fun keyFor(frag: EpubFragment, label: String? = null): Key {
        val filePath = idToFile[frag.originalId]
        val name = label ?: filePath?.let { fp -> fileToTitle?.get(fp) } ?: "(nameless)"
        return DefaultLeafKeyList(name, "${frag.id}")
    }

    /** Table of contents: keys with the html id inside the fragment to scroll to. */
    val tocKeys: List<Pair<Key, String?>> get() {
        val t = toc ?: return emptyList()
        return xp().compile("//ns:navPoint/ns:content", Filters.element(), null, ncxNs).evaluate(t).mapNotNull { c ->
            val label = xp().compile("../ns:navLabel/ns:text", Filters.element(), null, ncxNs).evaluateFirst(c)?.text
            val fai = fileAndId(c.getAttribute("src").value)
            val fileName = fai?.first?.let { URLDecoder.decode(it, "UTF-8") }
            val htmlId = fai?.second?.ifEmpty { null }
            val id = fileToId[fileName] ?: return@mapNotNull null
            val frag = dao.getFragment(if (htmlId == null) id else "$id#$htmlId") ?: return@mapNotNull null
            keyFor(frag, label) to htmlId
        }
    }

    fun read(key: Key): String {
        val frag = fragment(key) ?: return ""
        val f = File(fragDir, frag.fragFileName)
        if (!f.exists()) { Log.e("EPUB fragment file missing: $f"); return "" }
        return GZIPInputStream(f.inputStream()).use { String(it.readBytes()) }
    }

    val keys: List<Key> get() = dao.fragments().map { keyFor(it) }
    fun indexOf(key: Key) = keys.indexOf(key)
    fun get(index: Int) = keys[index]
    val cardinality get() = dao.fragments().size

    fun getKey(originalKey: String, htmlId: String): Key? =
        dao.getFragment(if (htmlId.isNotEmpty()) "$originalKey#$htmlId" else originalKey)?.let { keyFor(it) }

    fun getOrdinalRange(key: Key): IntRange = fragment(key)?.let { it.ordinalStart..it.ordinalEnd } ?: 0..0
    val bookOrdinalSpan: Int get() = dao.fragments().sumOf { it.ordinalEnd - it.ordinalStart + 1 }
    fun fragmentOffset(key: Key): Int {
        val target = fragment(key)?.id ?: return 0
        var offset = 0
        for (f in dao.fragments().sortedBy { it.id }) {
            if (f.id == target) break
            offset += f.ordinalEnd - f.ordinalStart + 1
        }
        return offset
    }

    private val bvaQuery get() = xp().compile("//ns:BVA", Filters.element(), null, xhtmlNs)

    val totalCharacters: Int get() {
        dao.getMeta()?.let { return it }
        var total = 0
        var failed = false
        for (f in dao.fragments()) {
            try {
                total += bvaQuery.evaluate(saxBuilder().build(StringReader(read(keyFor(f))))).sumOf { it.text.length }
            } catch (e: Exception) { failed = true }
        }
        if (!failed) dao.setMeta(total)
        return total
    }

    fun getResource(path: String) = File(rootFolder, path)

    // ---------------------------------------------------------------- search

    val isIndexed get() = search.isIndexed
    fun deleteSearchIndex() { search.deleteIndex(); bookMetaData.indexStatus = IndexStatus.UNDONE }

    fun buildSearchIndex(progress: (Int, Int) -> Unit = { _, _ -> }) {
        if (search.isIndexed) return
        bookMetaData.indexStatus = IndexStatus.CREATING
        search.createTable()
        val frags = dao.fragments()
        frags.forEachIndexed { i, f ->
            progress(i, frags.size)
            val doc = saxBuilder().build(StringReader(read(keyFor(f))))
            search.add(bvaQuery.evaluate(doc).map { Triple(it.text, f.id, it.getAttribute("ordinal").value.toInt()) })
        }
        bookMetaData.indexStatus = IndexStatus.DONE
    }

    class Hit(val key: Key, val ordinal: Int, val text: String)
    fun search(text: String): List<Hit> = search.search(text).mapNotNull { r ->
        val f = dao.getFragment(r.fragId) ?: return@mapNotNull null
        Hit(keyFor(f), r.ordinal, r.text)
    }

    fun delete() {
        epubDir.deleteRecursively()
        dao.close()
        appDbFile.delete()
    }

    override fun close() {}
    override fun releaseResources() {}
    override fun getBookMetaData(): SwordBookMetaData = metaData
    private var lastAccess = 0L
    override fun getLastAccess() = lastAccess
    override fun setLastAccess(l: Long) { lastAccess = l }

    // ---------------------------------------------------------------- optimization (EpubOptimization.kt)

    private fun readOriginal(origId: String): Pair<Document?, Int> {
        val file = fileForOriginalId(origId) ?: return null to 0
        val parentFolder = file.parentFile!!
        fun epubSrc(src: String) = "/epub/" + File(parentFolder, src).toRelativeString(rootFolder)
        val doc = saxBuilder().build(file)
        val e = doc.rootElement
        for (img in xp().compile("//svg:image[@xlink:href]", Filters.element(), null, svgNs, xlinkNs).evaluate(e)) {
            img.setAttribute("href", epubSrc(img.getAttribute("href", xlinkNs).value), xlinkNs)
        }
        for (img in xp().compile("//ns:img", Filters.element(), null, xhtmlNs).evaluate(e)) {
            img.setAttribute("src", epubSrc(img.getAttribute("src").value))
        }
        val urlRe = Regex("""^https?://.*""")
        for (a in xp().compile("//ns:a", Filters.element(), null, xhtmlNs).evaluate(e)) {
            val href = a.getAttribute("href")?.value ?: continue
            val fai = fileAndId(href)
            if (fai != null && !urlRe.matches(href)) {
                val fileStr = URLDecoder.decode(fai.first, "UTF-8")
                val fileName = if (fileStr.isEmpty()) null else File(parentFolder, fileStr).toRelativeString(rootFolder)
                a.name = "epubRef"
                a.setAttribute("to-key", fileName?.let { fileToId[it] } ?: origId)
                a.setAttribute("to-id", fai.second)
            } else {
                a.name = "epubA"
            }
        }
        val body = xp().compile("//ns:body", Filters.element(), null, xhtmlNs).evaluateFirst(e) ?: return null to 0
        val maxOrdinal = Content.addAnchors(body, bookLanguage, isEpub = true)
        return doc to maxOrdinal
    }

    private val bookLanguage get() = queryMetadata("language") ?: "en"

    private fun optimize() {
        val ordinalsPerFragment = 500
        fun splitPoint(doc: Document, p: Int): Element? = xp().compile(
            "//*[descendant::ns:BVA[@ordinal='$p'] and (following-sibling::ns:p or preceding-sibling::ns:p or self::ns:p)]",
            Filters.element(), null, xhtmlNs).evaluateFirst(doc)
        fun removeBefore(ele: Element) { val parent = ele.parentElement ?: return; repeat(parent.indexOf(ele)) { parent.removeContent(0) } }
        fun removeAfter(ele: Element) {
            val parent = ele.parentElement ?: return
            val idx = parent.indexOf(ele) + 1
            repeat(parent.contentSize - idx) { parent.removeContent(idx) }
        }
        fun extractBetween(orig: Document, s1: Int?, s2: Int?): Document? {
            val doc = orig.clone()
            val e1 = s1?.let { splitPoint(doc, it) }
            val e2 = s2?.let { splitPoint(doc, it) }
            if (e1 == e2) return null
            if (e1 != null) {
                removeBefore(e1)
                var p = e1.parentElement
                while (p != null) { removeBefore(p); p = p.parentElement }
            }
            if (e2 != null) {
                var p = e2.parentElement
                while (p?.parentElement != null) { removeAfter(p); p = p.parentElement }
                removeAfter(e2)
                e2.detach()
            }
            return doc
        }
        fun splitIntoN(doc: Document, range: IntRange, n: Int): List<Document> {
            if (n == 0) return listOf(doc)
            val first = range.first
            val piece = (range.last - first) / n
            val firstFrag = extractBetween(doc, null, first + piece) ?: return listOf(doc)
            var p1 = first + piece
            var p2 = first + piece * 2
            val docs = mutableListOf(firstFrag)
            while (p2 < range.last) {
                extractBetween(doc, p1, p2)?.let { p1 = p2; docs.add(it) }
                p2 += piece
            }
            if (p1 < range.last) extractBetween(doc, p1, null)?.let { docs.add(it) }
            return docs
        }
        fun ordinalRange(doc: Document): IntRange? {
            val bvas = bvaQuery.evaluate(doc)
            if (bvas.isEmpty()) return null
            return bvas.first().getAttribute("ordinal").intValue..bvas.last().getAttribute("ordinal").intValue
        }

        fragDir.deleteRecursively()
        fragDir.mkdirs()
        optimizeLockFile.writeBytes(byteArrayOf(1))
        appDbFile.delete()
        val writeDao = EpubDao(appDbFile)
        val originalIds = xp().compile("//ns:spine/ns:itemref", Filters.element(), null, opfNs).evaluate(content).map { it.getAttribute("idref").value }
        writeDao.tx {
            for (k in originalIds) {
                val (orig, maxOrdinal) = readOriginal(k)
                orig ?: continue
                val fragments = splitIntoN(orig, 0..maxOrdinal, maxOrdinal / ordinalsPerFragment).mapNotNull { d ->
                    ordinalRange(d)?.let { r -> EpubFragment(k, r.first, r.last).apply { element = d.rootElement } }
                }.ifEmpty { listOf(EpubFragment(k, 0, 0).apply { element = orig.rootElement }) }
                for (f in fragments) f.id = writeDao.insert(f)
                writeDao.insertHtmlToFrag(k, fragments[0].id)
                val head = orig.rootElement.children.find { it.name == "head" }
                head?.children?.filter { it.name == "link" && it.getAttribute("type")?.value == "text/css" }
                    ?.forEach { writeDao.insertStyleSheet(k, it.getAttribute("href").value) }
                for (f in fragments) {
                    val body = xp().compile("//ns:body", Filters.element(), null, xhtmlNs).evaluateFirst(f.element)
                    body.name = "div"
                    GZIPOutputStream(File(fragDir, f.fragFileName).outputStream()).use { it.write(Content.elementToString(body).toByteArray()) }
                    for (id in xp().compile("//*[@id]", Filters.element(), null, xhtmlNs).evaluate(f.element).map { it.getAttribute("id").value }) {
                        writeDao.insertHtmlToFrag("$k#$id", f.id)
                    }
                    f.element = null
                }
                fileForOriginalId(k)?.delete()
            }
        }
        versionFile.writeText("2")
        writeDao.close()
        optimizeLockFile.delete()
    }
}

class EpubBackend(val state: EpubState, md: SwordBookMetaData) : AbstractKeyBackend<EpubState>(md) {
    override fun initState(): EpubState = state
    override fun readIndex(): Key = DefaultKeyList(null, bookMetaData.name).apply { for (k in state.keys) addAll(k) }
    override fun getCardinality(): Int = state.cardinality
    override fun iterator(): MutableIterator<Key> = state.keys.toMutableList().iterator()
    override fun get(index: Int): Key = state.get(index)
    override fun indexOf(that: Key): Int = state.indexOf(that)
    override fun readRawContent(state: EpubState, key: Key): String = state.read(key)
}

object EpubDriver : AbstractBookDriver() {
    override fun getBooks(): Array<Book> = emptyArray()
    override fun getDriverName(): String = "EpubSwordDriver"
    override fun isDeletable(book: Book): Boolean = true
    override fun delete(book: Book) {
        Epub.state(book)?.delete()
        Books.installed().removeBook(book)
    }
}

object Epub {
    private val epubDir: File get() = File(OtherFormats.modulesDir, "epub")

    fun state(book: Book?): EpubState? = ((book as? SwordGenBook)?.backend as? EpubBackend)?.state
    fun isEpub(book: Book?) = state(book) != null

    fun addBook(dir: File) {
        if (!(dir.canRead() && dir.isDirectory)) return
        if (File(dir, "optimize.lock").exists()) { Log.w("EPUB optimization of $dir failed earlier; removing it"); dir.deleteRecursively(); return }
        if (Books.installed().getBook(epubInitials(dir.name)) != null) return
        val state = EpubState(dir)
        Books.installed().addBook(SwordGenBook(state.bookMetaData, EpubBackend(state, state.bookMetaData)))
    }

    fun registerAll() {
        epubDir.listFiles()?.filter { it.isDirectory }?.forEach { d ->
            try { addBook(d) } catch (e: Exception) { Log.e("Could not open EPUB $d", e) }
        }
    }

    fun install(f: File) {
        val dir = File(epubDir, f.name)
        Books.installed().getBook(epubInitials(f.name))?.let { b -> state(b)?.delete(); Books.installed().removeBook(b) }
        dir.deleteRecursively()
        dir.mkdirs()
        ZipInputStream(f.inputStream()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.name.contains("..")) continue
                val out = File(dir, e.name)
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile.mkdirs()
                out.outputStream().use { zin.copyTo(it) }
            }
        }
        try { addBook(dir) } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }

    /** /epub/<path>: resources of EPUB books (images); the book is found by the path. */
    fun resource(path: String): File? {
        val books = Books.installed().books.mapNotNull { state(it) }
        return books.map { it.getResource(path) }.firstOrNull { it.isFile }
    }

    private val colorRe = Regex("""\b(background-color|background|background-image|color):[^;]+;""")
    private val bodyRe = Regex("""\bbody\s*\{[^}]*\}""")
    private val lineHeightRe = Regex("""\bline-height:[^;]*;""")
    private val styleRe = Regex("""^([^/]+)/([^/]+)/style.css$""")

    /** /module-style/epub/<initials>/<key>/style.css and resources it refers to. */
    fun serveStyle(ex: HttpExchange, path: String, send: (ByteArray, String) -> Unit): Boolean {
        styleRe.matchEntire(path)?.let { m ->
            val book = Books.installed().getBook(m.groupValues[1])
            val st = state(book)
            val key = runCatching { book?.getKey(m.groupValues[2]) }.getOrNull()
            if (st == null || key == null) { ex.sendResponseHeaders(404, -1); return true }
            val css = st.styleSheets(key).filter { it.exists() }.joinToString("\n") { it.readText() }
                .replace(colorRe, "").replace(bodyRe, "").replace(lineHeightRe, "")
            send(css.toByteArray(), "text/css; charset=utf-8")
            return true
        }
        val initials = path.substringBefore('/')
        val st = state(Books.installed().getBook(initials))
        val f = st?.getResource(path.substringAfter('/'))
        if (f == null || !f.isFile) { ex.sendResponseHeaders(404, -1); return true }
        send(f.readBytes(), java.net.URLConnection.guessContentTypeFromName(f.name) ?: "application/octet-stream")
        return true
    }

    /** ReadingProgressInfo.forEpub */
    fun readingProgress(book: Book, key: Key): JsonObject? {
        val st = state(book) ?: return null
        return buildJsonObject {
            put("kind", "book")
            put("fragmentOffset", st.fragmentOffset(key))
            put("bookOrdinalSpan", st.bookOrdinalSpan)
            put("charCount", st.totalCharacters)
        }
    }
}
