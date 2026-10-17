/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Files installed outside the SWORD module system, kept like AndBible in sub folders of the modules folder (~/.sword):
 *   ttf/         fonts               -> synthetic "And Bible" add-on module providing the font (TtfBook.kt)
 *   background/  background images   -> synthetic add-on module providing the image (BackgroundImageBook.kt)
 *   mybible/ mysword/ esword/ epub/  documents in other formats (see Formats.kt)
 */
package org.linbible

import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.Books
import org.crosswire.jsword.book.basic.AbstractBookDriver
import org.crosswire.jsword.book.sword.NullBackend
import org.crosswire.jsword.book.sword.SwordBook
import org.crosswire.jsword.book.sword.SwordBookMetaData
import org.crosswire.jsword.book.sword.SwordBookPath
import java.io.File

object OtherFormats {
    val modulesDir: File get() = SwordBookPath.getSwordDownloadDir()

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    /** Where an installed copy of [f] goes. */
    fun target(f: File): File {
        val ext = f.extension.lowercase()
        val dir = when {
            ext == "ttf" -> "ttf"
            ext in imageExtensions -> "background"
            ext == "sqlite3" -> "mybible"
            ext == "mybible" -> "mysword"
            ext == "bblx" || ext == "bbli" -> "esword"
            ext == "epub" -> "epub"
            ext == "csv" -> "prompts"
            else -> throw IllegalArgumentException("Unsupported file ${f.name}")
        }
        return File(File(modulesDir, dir), f.name)
    }

    fun installFile(f: File) {
        val t = target(f)
        if (t.extension.lowercase() == "epub") { Formats.installEpub(f); Addons.reload(); return }
        t.parentFile.mkdirs()
        f.copyTo(t, overwrite = true)
        when (t.parentFile.name) {
            "ttf" -> addTtfBook(t)
            "background" -> addBackgroundImageBook(t)
            "mybible", "mysword", "esword" -> Formats.register(t)
            "prompts" -> Llm.installPromptPack(t)
        }
        Addons.reload()
    }

    /** Registers everything found in the modules folder (AndBible's addManuallyInstalled… functions). */
    fun registerAll() {
        File(modulesDir, "ttf").listFiles()?.filter { it.isFile && it.extension.lowercase() == "ttf" }?.forEach { runCatching { addTtfBook(it) } }
        File(modulesDir, "background").listFiles()?.filter { it.isFile && it.extension.lowercase() in imageExtensions }
            ?.forEach { runCatching { addBackgroundImageBook(it) } }
        Formats.registerAll()
        Addons.reload()
    }

    // ---------------------------------------------------------------- fonts (TtfBook.kt)

    private class FileBookDriver(private val name: String, private val file: (Book) -> File) : AbstractBookDriver() {
        override fun getBooks(): Array<Book> = emptyArray()
        override fun getDriverName(): String = name
        override fun isDeletable(book: Book): Boolean = file(book).canWrite()
        override fun delete(book: Book) {
            file(book).delete()
            Books.installed().removeBook(book)
            Addons.reload()
        }
    }

    private fun markerFile(book: Book, marker: String, dir: String): File {
        val fileName = (book.bookMetaData.getProperty(marker) as String? ?: "").split(";").getOrNull(1) ?: ""
        return File(File(modulesDir, dir), fileName)
    }

    private val ttfDriver = FileBookDriver("TtfSwordDriver") { markerFile(it, "AndBibleProvidesFont", "ttf") }
    private val backgroundDriver = FileBookDriver("BackgroundImageSwordDriver") { markerFile(it, "AndBibleProvidesBackgroundImage", "background") }

    private fun addSyntheticBook(initials: String, conf: String, dir: File, driver: AbstractBookDriver) {
        val md = SwordBookMetaData(conf.toByteArray(), initials)
        md.location = dir.toURI()
        md.driver = driver
        Books.installed().addBook(SwordBook(md, NullBackend()))
    }

    fun addTtfBook(file: File) {
        if (!(file.canRead() && file.isFile && file.extension.lowercase() == "ttf")) return
        val fontName = file.nameWithoutExtension
        val initials = "TTF_$fontName"
        if (Books.installed().getBook(initials) != null) return
        addSyntheticBook(initials, """
[$initials]
Description=$fontName
Category=And Bible
ModDrv=RawGenBook
DataPath=./ttf/
Encoding=UTF-8
AndBibleProvidesFont=$fontName;${file.name}
AndBibleMinimumVersion=892
""", file.parentFile, ttfDriver)
    }

    fun addBackgroundImageBook(file: File) {
        if (!(file.canRead() && file.isFile && file.extension.lowercase() in imageExtensions)) return
        if (Books.installed().books.any { (it.bookMetaData.getProperty("AndBibleProvidesBackgroundImage") as String?)?.endsWith(";${file.name}") == true }) return
        val displayName = file.nameWithoutExtension
        val sanitized = displayName.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "image" }
        var initials = "BGIMG_$sanitized"
        var i = 2
        while (Books.installed().getBook(initials) != null) initials = "BGIMG_${sanitized}_${i++}"
        addSyntheticBook(initials, """
[$initials]
Description=$displayName
Category=And Bible
ModDrv=RawGenBook
DataPath=./background/
Encoding=UTF-8
AndBibleProvidesBackgroundImage=$displayName;${file.name}
AndBibleMinimumVersion=1112
""", file.parentFile, backgroundDriver)
    }
}
