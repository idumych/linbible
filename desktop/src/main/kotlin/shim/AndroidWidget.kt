/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
@file:Suppress("unused")

package android.widget

/** Toasts appear in LinBible's status bar. */
class Toast private constructor(private val text: String) {
    fun show() { javafx.application.Platform.runLater { org.linbible.MainApp.instance?.toast(text) } }
    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1
        @JvmStatic fun makeText(context: android.content.Context, resId: Int, duration: Int) = Toast(context.getString(resId))
        @JvmStatic fun makeText(context: android.content.Context, text: CharSequence, duration: Int) = Toast(text.toString())
    }
}
