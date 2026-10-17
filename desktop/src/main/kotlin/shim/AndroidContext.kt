/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
@file:Suppress("unused")

package android.content

open class Context {
    fun getString(id: Int): String = net.bible.android.Strings.get(id)
    fun getString(id: Int, vararg args: Any?): String = net.bible.android.Strings.format(net.bible.android.Strings.get(id), args)
}
