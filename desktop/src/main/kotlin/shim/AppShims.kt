/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Stand-ins for the parts of AndBible's application classes that its compiled AI and document code uses:
 * string resources, the event bus, settings and the database container.
 */
@file:Suppress("unused")

package net.bible.android

import java.io.InputStream

/** Context-like application object; strings come from AndBible's English strings.xml (see build.gradle.kts). */
class BibleApplication : android.content.Context() {
    fun openRawResource(id: Int): InputStream = Strings.raw(id)
    val resources get() = this
    val applicationComponent = ApplicationComponent()
    val cacheDir: java.io.File get() = org.linbible.AppDirs.cache
    val filesDir: java.io.File get() = org.linbible.AppDirs.data

    companion object {
        val application = BibleApplication()
    }
}

/** AndBible's Dagger component: the controllers used by its AI code. */
class ApplicationComponent {
    private val bookmarks by lazy { net.bible.android.control.bookmark.BookmarkControl() }
    fun bookmarkControl() = bookmarks
    private val links by lazy { net.bible.android.control.link.LinkControl() }
    fun linkControl() = links
    fun windowControl() = net.bible.android.control.page.window.WindowControl.instance
}

object Strings {
    private val table: List<String> by lazy {
        val text = Strings::class.java.getResourceAsStream("/android-strings.txt")!!.bufferedReader().readText()
        text.split("\n").map { decodeLine(it) }
    }

    /** Undo the build-time line encoding (\\ and \n), then Android's resource escaping and quoting. */
    private fun decodeLine(line: String): String {
        val raw = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) {
                when (line[i + 1]) { '\\' -> raw.append('\\'); 'n' -> raw.append('\n'); else -> raw.append(c).append(line[i + 1]) }
                i += 2
            } else { raw.append(c); i++ }
        }
        return androidUnescape(raw.toString())
    }

    fun androidUnescape(s: String): String {
        var t = s.trim()
        val quoted = t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")
        if (quoted) t = t.substring(1, t.length - 1) else t = t.replace(Regex("\\s+"), " ")
        val out = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length) {
                val n = t[i + 1]
                when (n) {
                    'n' -> out.append('\n'); 't' -> out.append('\t')
                    'u' -> if (i + 5 < t.length) { out.append(t.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                    else -> out.append(n)
                }
                i += 2
            } else { out.append(c); i++ }
        }
        return out.toString()
    }

    fun get(id: Int): String = table.getOrElse(id) { "?$id" }

    fun format(fmt: String, args: Array<out Any?>): String =
        try { String.format(fmt, *args) } catch (e: Exception) { fmt }

    fun raw(id: Int): InputStream {
        val name = when (id) { 0 -> "llm_agent_system_prompt.md"; else -> "llm_text_transformation_system_prompt.md" }
        return Strings::class.java.getResourceAsStream("/raw/$name")!!
    }
}
