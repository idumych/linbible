/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Minimal stand-ins for the Android/Room APIs used by AndBible's database migrations, which LinBible compiles
 * unmodified (see build.gradle.kts). Backed by JDBC (sqlite-jdbc).
 */
@file:Suppress("unused")

package androidx.sqlite.db

import android.database.Cursor
import java.sql.Connection

class SupportSQLiteDatabase(val connection: Connection) {
    fun execSQL(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    fun execSQL(sql: String, bindArgs: Array<out Any?>) {
        connection.prepareStatement(sql).use { st ->
            bindArgs.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.execute()
        }
    }

    fun query(sql: String): Cursor = Cursor(connection, sql, emptyArray())
    fun query(sql: String, bindArgs: Array<out Any?>): Cursor = Cursor(connection, sql, arrayOf(*bindArgs))

    var version: Int
        get() = connection.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        set(v) = execSQL("PRAGMA user_version = $v")
}
