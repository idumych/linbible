/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
@file:Suppress("unused")

package android.util

import org.slf4j.LoggerFactory

object Log {
    private fun l(tag: String) = LoggerFactory.getLogger(tag)
    @JvmStatic fun i(tag: String, msg: String): Int { l(tag).info(msg); return 0 }
    @JvmStatic fun d(tag: String, msg: String): Int { l(tag).debug(msg); return 0 }
    @JvmStatic fun w(tag: String, msg: String): Int { l(tag).warn(msg); return 0 }
    @JvmStatic fun w(tag: String, msg: String, t: Throwable?): Int { l(tag).warn(msg, t); return 0 }
    @JvmStatic fun e(tag: String, msg: String): Int { l(tag).error(msg); return 0 }
    @JvmStatic fun e(tag: String, msg: String, t: Throwable?): Int { l(tag).error(msg, t); return 0 }
}
