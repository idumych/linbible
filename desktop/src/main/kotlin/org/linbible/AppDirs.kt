/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
package org.linbible

import java.io.File

/** XDG-compliant locations for LinBible's own data. SWORD modules live in ~/.sword (shared with other SWORD apps). */
object AppDirs {
    private val home = File(System.getProperty("user.home"))

    private fun xdg(env: String, fallback: String): File =
        System.getenv(env)?.takeIf { it.isNotBlank() }?.let { File(it) } ?: File(home, fallback)

    val config: File = File(xdg("XDG_CONFIG_HOME", ".config"), "linbible").apply { mkdirs() }
    val data: File = File(xdg("XDG_DATA_HOME", ".local/share"), "linbible").apply { mkdirs() }
    val cache: File = File(xdg("XDG_CACHE_HOME", ".cache"), "linbible").apply { mkdirs() }

    /** SWORD module directory; JSword downloads here and reads it, as do Xiphos / BibleTime. */
    val sword: File = File(home, ".sword").apply {
        mkdirs()
        File(this, "mods.d").mkdirs()
        File(this, "modules").mkdirs()
    }
}
