/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * AndBible add-on modules (SWORD modules of category "And Bible"): fonts, styles, features, background images,
 * reading plans and AI prompt packs. Port of AndBible's AndBibleAddons.
 */
package org.linbible

import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.BookCategory
import org.crosswire.jsword.book.Books
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object Addons {
    /** AndBible version whose add-ons LinBible supports (AndroidManifest versionCode of the ported commit). */
    const val ANDBIBLE_VERSION = 1118L

    class ProvidedFont(val book: Book, val name: String, val path: String) {
        val file: File get() = File(File(book.bookMetaData.location), path)
    }
    class ProvidedBackgroundImage(val book: Book, val name: String, val path: String) {
        val file: File get() = File(File(book.bookMetaData.location), path)
    }
    class ReadingPlanAddon(val book: Book, val fileName: String, val isDateBased: Boolean) {
        val file: File get() = File(File(book.bookMetaData.location), fileName)
    }
    class PromptPack(val moduleName: String, val file: File)

    private var cached: List<Book>? = null

    val addons: List<Book>
        @Synchronized get() = cached ?: Books.installed().getBooks { b ->
            b.bookCategory == BookCategory.AND_BIBLE &&
                ((b.bookMetaData.getProperty("AndBibleMinimumVersion") as String?)?.toLongOrNull() ?: 0) <= ANDBIBLE_VERSION
        }.toList().also { cached = it }

    private fun Book.values(key: String): List<String> = bookMetaData.getValues(key)?.map { it.toString() } ?: emptyList()

    val fonts: Map<String, ProvidedFont> get() = buildMap {
        for (book in addons) for (v in book.values("AndBibleProvidesFont")) {
            val parts = v.split(";")
            val f = ProvidedFont(book, parts[0], parts.getOrElse(1) { "" })
            if (f.file.canRead()) put(f.name, f) else Log.w("Could not read font file ${f.file}")
        }
    }

    val fontsByModule: Map<String, List<ProvidedFont>> get() = fonts.values.groupBy { it.book.initials }

    val backgroundImages: Map<String, ProvidedBackgroundImage> get() = buildMap {
        for (book in addons) {
            val v = book.values("AndBibleProvidesBackgroundImage").firstOrNull() ?: continue
            val parts = v.split(";")
            val img = ProvidedBackgroundImage(book, parts[0], parts.getOrElse(1) { "" })
            if (img.file.canRead()) put(book.initials, img)
        }
    }

    val readingPlans: Map<String, ReadingPlanAddon> get() = buildMap {
        for (book in addons) for (fileName in book.values("AndBibleProvidesReadingPlan")) {
            val dateBased = (book.bookMetaData.getProperty("AndBibleReadingPlanDateBased") as String?).equals("True", true)
            val code = File(File(book.bookMetaData.location), fileName).nameWithoutExtension
            put(code, ReadingPlanAddon(book, fileName, dateBased))
        }
    }

    val promptPacks: List<PromptPack> get() = addons.mapNotNull { book ->
        val csv = book.bookMetaData.getProperty("AndBibleProvidesPrompts") as String? ?: return@mapNotNull null
        File(File(book.bookMetaData.location), csv).takeIf { it.canRead() }?.let { PromptPack(book.initials, it) }
    }

    val fontModuleNames get() = addons.filter { it.values("AndBibleProvidesFont").isNotEmpty() }.map { it.initials }
    val backgroundImageModuleNames get() = addons.filter { it.values("AndBibleProvidesBackgroundImage").isNotEmpty() }.map { it.initials }
    val featureModuleNames get() = addons.filter { it.values("AndBibleProvidesFeature").isNotEmpty() }.map { it.initials }
    val styleModuleNames get() = addons.filter { it.values("AndBibleProvidesStyle").isNotEmpty() }.map { it.initials }

    val listeners = mutableListOf<() -> Unit>()

    /** Called when modules were installed or removed. */
    fun reload() {
        synchronized(this) { cached = null }
        listeners.toList().forEach { it() }
        net.bible.android.control.event.ABEventBus.post(net.bible.service.common.ReloadAddonsEvent())
    }

    /** Feature add-ons contain code, so (like AndBible) they must be signed by the AndBible project key. */
    fun verifySignature(file: File, signatureFile: File): Boolean = runCatching {
        if (!signatureFile.canRead()) return false
        val pem = Addons::class.java.getResourceAsStream("/publickey.pem")!!.bufferedReader().readText()
        val der = Base64.getMimeDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
        val sig = Signature.getInstance("SHA1withRSA")
        sig.initVerify(key)
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; sig.update(buf, 0, n) }
        }
        val sigBytes = ByteArray(key.modulus.bitLength() / 8)
        signatureFile.inputStream().use { it.read(sigBytes) }
        sig.verify(sigBytes)
    }.getOrElse { Log.w("Signature check failed for $file: ${it.message}"); false }
}
