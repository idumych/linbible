/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Minimal stand-in for the requery SQLite API used by AndBible's MyBible / MySword / e-Sword backends (JDBC).
 */
package io.requery.android.database.sqlite;

import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import org.sqlite.SQLiteConfig;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.SQLException;

public class SQLiteDatabase implements Closeable {
    public static final int OPEN_READWRITE = 0;
    public static final int OPEN_READONLY = 1;
    public static final int CONFLICT_IGNORE = 4;

    private final String path;
    private final Connection connection;

    private SQLiteDatabase(String path, Connection connection) {
        this.path = path;
        this.connection = connection;
    }

    public static SQLiteDatabase openDatabase(String path, Object factory, int flags) {
        try {
            SQLiteConfig config = new SQLiteConfig();
            config.setReadOnly((flags & OPEN_READONLY) != 0);
            return new SQLiteDatabase(path, config.createConnection("jdbc:sqlite:" + path));
        } catch (SQLException e) {
            throw new SQLiteException("Could not open " + path + ": " + e.getMessage(), e);
        }
    }

    public String getPath() { return path; }

    public synchronized Cursor rawQuery(String sql, Object[] selectionArgs) {
        return new Cursor(connection, sql, selectionArgs);
    }

    public synchronized void execSQL(String sql) {
        try (var st = connection.createStatement()) { st.execute(sql); } catch (SQLException e) { throw new SQLiteException(e.getMessage(), e); }
    }

    public boolean isOpen() {
        try { return !connection.isClosed(); } catch (SQLException e) { return false; }
    }

    public int getVersion() {
        try (Cursor c = rawQuery("PRAGMA user_version", null)) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }

    @Override public void close() {
        try { connection.close(); } catch (SQLException ignored) { }
    }
}
