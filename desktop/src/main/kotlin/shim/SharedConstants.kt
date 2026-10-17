/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
package net.bible.android

import java.io.File

/** AndBible's SharedConstants, as far as the compiled upstream document backends need it. */
object SharedConstants {
    val modulesDir: File get() = org.linbible.OtherFormats.modulesDir
}
