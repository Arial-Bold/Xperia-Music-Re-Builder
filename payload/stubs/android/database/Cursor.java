package android.database;
public interface Cursor extends java.io.Closeable { boolean moveToNext(); boolean moveToFirst(); long getLong(int i); int getInt(int i); String getString(int i); boolean isNull(int i); void close(); }
