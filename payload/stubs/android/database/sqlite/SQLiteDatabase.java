package android.database.sqlite;
public final class SQLiteDatabase { public static final int CONFLICT_REPLACE = 5;
 public void execSQL(String s){} public android.database.Cursor rawQuery(String s, String[] a){return null;}
 public long insert(String t, String n, android.content.ContentValues v){return 0;}
 public int update(String t, android.content.ContentValues v, String w, String[] a){return 0;}
 public long insertWithOnConflict(String t, String n, android.content.ContentValues v, int c){return 0;} }
