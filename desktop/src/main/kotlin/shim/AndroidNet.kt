/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
@file:Suppress("unused")

package android.net

/** android.net.Uri.encode: percent-encodes everything except letters, digits and _-!.~'()* */
object Uri {
    @JvmStatic fun encode(s: String?): String? {
        if (s == null) return null
        val out = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch.isLetterOrDigit() && c < 128 || ch in "_-!.~'()*") out.append(ch) else out.append('%').append("%02X".format(c))
        }
        return out.toString()
    }
    @JvmStatic fun decode(s: String?): String? = s?.let { java.net.URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }
}
