/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
@file:Suppress("unused")

package android.graphics

/** android.graphics.Color's int-based helpers. */
object Color {
    const val BLACK = -0x1000000
    const val WHITE = -0x1
    const val TRANSPARENT = 0
    @JvmStatic fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
    @JvmStatic fun rgb(r: Int, g: Int, b: Int): Int = argb(255, r, g, b)
    @JvmStatic fun alpha(c: Int) = c ushr 24
    @JvmStatic fun red(c: Int) = (c shr 16) and 0xFF
    @JvmStatic fun green(c: Int) = (c shr 8) and 0xFF
    @JvmStatic fun blue(c: Int) = c and 0xFF
    @JvmStatic fun parseColor(s: String): Int {
        val hex = s.removePrefix("#")
        val v = hex.toLong(16).toInt()
        return if (hex.length == 6) v or (0xFF shl 24) else v
    }
}
