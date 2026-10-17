/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
@file:Suppress("unused")

package androidx.room.migration

import androidx.sqlite.db.SupportSQLiteDatabase

abstract class Migration(@JvmField val startVersion: Int, @JvmField val endVersion: Int) {
    abstract fun migrate(db: SupportSQLiteDatabase)
}
