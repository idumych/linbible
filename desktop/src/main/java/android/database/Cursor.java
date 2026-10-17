/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Minimal stand-in for android.database.Cursor over JDBC, for AndBible code compiled unmodified. Rows are read
 * eagerly, so moveToFirst() works at any time like on Android.
 */
package android.database;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class Cursor implements Closeable {
    private final List<Object[]> rows = new ArrayList<>();
    private final List<String> columns = new ArrayList<>();
    private int position = -1;

    public Cursor(Connection connection, String sql, Object[] args) {
        try (PreparedStatement st = connection.prepareStatement(sql)) {
            if (args != null) for (int i = 0; i < args.length; i++) st.setObject(i + 1, args[i]);
            try (ResultSet rs = st.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                for (int i = 1; i <= n; i++) columns.add(md.getColumnLabel(i));
                while (rs.next()) {
                    Object[] row = new Object[n];
                    for (int i = 0; i < n; i++) row[i] = rs.getObject(i + 1);
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            throw new android.database.sqlite.SQLiteException(e.getMessage(), e);
        }
    }

    private Object value(int i) {
        if (position < 0 || position >= rows.size())
            throw new CursorIndexOutOfBoundsException("Index " + position + " requested, with a size of " + rows.size());
        return rows.get(position)[i];
    }

    public int getCount() { return rows.size(); }
    public int getPosition() { return position; }
    public boolean moveToFirst() { position = 0; return !rows.isEmpty(); }
    public boolean moveToNext() { if (position < rows.size()) position++; return position < rows.size(); }
    public boolean moveToPosition(int p) { position = p; return p >= 0 && p < rows.size(); }
    public boolean isAfterLast() { return position >= rows.size(); }
    public boolean isLast() { return !rows.isEmpty() && position == rows.size() - 1; }
    public boolean isFirst() { return !rows.isEmpty() && position == 0; }
    public String[] getColumnNames() { return columns.toArray(new String[0]); }
    public int getColumnCount() { return columns.size(); }
    public int getColumnIndex(String name) { return columns.indexOf(name); }
    public int getColumnIndexOrThrow(String name) {
        int i = columns.indexOf(name);
        if (i < 0) throw new IllegalArgumentException("column '" + name + "' does not exist");
        return i;
    }
    public String getColumnName(int i) { return columns.get(i); }
    public boolean isNull(int i) { return value(i) == null; }
    public String getString(int i) { Object v = value(i); return v == null ? null : (v instanceof byte[] ? new String((byte[]) v) : v.toString()); }
    public byte[] getBlob(int i) { Object v = value(i); return v instanceof byte[] ? (byte[]) v : (v == null ? null : v.toString().getBytes()); }
    private Number number(int i) {
        Object v = value(i);
        if (v == null) return 0;
        if (v instanceof Number) return (Number) v;
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return 0; }
    }
    public int getInt(int i) { return number(i).intValue(); }
    public long getLong(int i) { return number(i).longValue(); }
    public short getShort(int i) { return number(i).shortValue(); }
    public double getDouble(int i) { return number(i).doubleValue(); }
    public float getFloat(int i) { return number(i).floatValue(); }
    @Override public void close() { rows.clear(); }
    public boolean isClosed() { return false; }
}
