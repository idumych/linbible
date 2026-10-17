/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
package org.linbible

import org.slf4j.LoggerFactory

object Log {
    private val log = LoggerFactory.getLogger("LinBible")
    val debug = System.getenv("LINBIBLE_DEBUG") != null

    fun i(msg: String) = log.info(msg)
    fun d(msg: String) { if (debug) log.info(msg) }
    fun w(msg: String) = log.warn(msg)
    fun e(msg: String, t: Throwable? = null) = log.error(msg, t)
}
