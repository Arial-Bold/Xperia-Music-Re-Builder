package com.fpmod.dsd;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.FileInputStream;

/**
 * Gives DSD files proper tags, duration and cover art in the app's library.
 *
 * Android's media scanner can't read DSF/DFF tags. During the app's own library sync
 * we read them and store them as "tag overrides" (the table the app's Edit info screen
 * uses), so the app applies them with its own album/artist grouping logic.
 */
public final class DsdTags {
    private static final String TAG = "DsdTags";
    private static final String OWN = "fpmod_dsd_tag";

    private DsdTags() { }

    // ------------------------------------------------------------ library sync

    /** Called by MusicInfoProvider.syncMediaStoreAudioData just before tag overrides are applied. */
    public static void syncAudioTags(SQLiteDatabase db) {
        if (db == null) return;
        Cursor c = null;
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + OWN + " (path TEXT PRIMARY KEY, mtime INTEGER, tag_rowid INTEGER, duration INTEGER, album_artist TEXT)");
            c = db.rawQuery("SELECT _id, _data, volume_name, relative_path, _display_name, date_modified, title, artist, album, genre, track, year, duration, album_artist FROM audio WHERE lower(_display_name) LIKE '%.dsf' OR lower(_display_name) LIKE '%.dff'", null);
            int n = 0;
            while (c.moveToNext()) {
                try {
                    if (syncOne(db, c)) n++;
                } catch (Throwable e) {
                    Log.w(TAG, "DSD tag sync failed for " + c.getString(1), e);
                }
            }
            if (n > 0) Log.i(TAG, "Updated tags for " + n + " DSD file(s)");
        } catch (Throwable e) {
            Log.w(TAG, "DSD tag sync failed", e);
        } finally {
            if (c != null) c.close();
        }
    }

    private static boolean syncOne(SQLiteDatabase db, Cursor c) {
        long audioId = c.getLong(0);
        String path = c.getString(1);
        String vol = c.getString(2), rel = c.getString(3), name = c.getString(4);
        long mtime = c.isNull(5) ? 0 : c.getLong(5);
        if (path == null || vol == null || rel == null || name == null) return false;

        long tagRow = -1;
        Cursor t = db.rawQuery("SELECT _id FROM audio_tag WHERE volume_name = ? AND relative_path = ? AND _display_name = ?",
                new String[]{vol, rel, name});
        try { if (t.moveToFirst()) tagRow = t.getLong(0); } finally { t.close(); }

        long ownTagRow = -1, ownMtime = -1, ownDuration = 0;
        String ownAlbumArtist = null;
        boolean ownExists = false;
        Cursor o = db.rawQuery("SELECT mtime, tag_rowid, duration, album_artist FROM " + OWN + " WHERE path = ?", new String[]{path});
        try {
            if (o.moveToFirst()) {
                ownExists = true;
                ownMtime = o.getLong(0); ownTagRow = o.getLong(1); ownDuration = o.getLong(2);
                ownAlbumArtist = o.isNull(3) ? null : o.getString(3);
            }
        } finally { o.close(); }

        boolean userEdited = tagRow >= 0 && (!ownExists || ownTagRow != tagRow);
        boolean upToDate = ownExists && ownMtime == mtime && (tagRow >= 0 || ownTagRow < 0);
        long duration = ownDuration;
        String albumArtist = ownAlbumArtist;
        boolean changed = false;

        if (!upToDate) {
            DsdInfo info = readInfo(path, false);
            if (info == null) return false;
            duration = info.durationMs;
            albumArtist = info.albumArtist;
            long newTagRow = ownTagRow;
            if (!userEdited && info.hasText()) {
                ContentValues v = new ContentValues();
                v.put("audio_id", audioId);
                v.put("volume_name", vol);
                v.put("relative_path", rel);
                v.put("_display_name", name);
                v.put("title", pick(info.title, c.getString(6)));
                v.put("artist", pick(info.artist, c.getString(7)));
                v.put("album", pick(info.album, c.getString(8)));
                String genre = pick(info.genre, c.getString(9));
                if (genre != null) v.put("genre", genre);
                int track = info.track > 0 ? (info.disc > 0 ? info.disc * 1000 + info.track : info.track)
                        : (c.isNull(10) ? 0 : c.getInt(10));
                if (track > 0) v.put("track", track);
                int year = 0;
                try { if (info.year != null) year = Integer.parseInt(info.year); } catch (NumberFormatException ignored) { }
                if (year <= 0 && !c.isNull(11)) year = c.getInt(11);
                if (year > 0) v.put("year", year);
                if (tagRow >= 0) {
                    db.update("audio_tag", v, "_id = ?", new String[]{String.valueOf(tagRow)});
                    newTagRow = tagRow;
                } else {
                    newTagRow = db.insert("audio_tag", null, v);
                }
            } else if (userEdited) {
                newTagRow = -2;   // remember the user owns this one
            }
            ContentValues ov = new ContentValues();
            ov.put("path", path);
            ov.put("mtime", mtime);
            ov.put("tag_rowid", newTagRow);
            ov.put("duration", duration);
            if (albumArtist != null) ov.put("album_artist", albumArtist);
            db.insertWithOnConflict(OWN, null, ov, SQLiteDatabase.CONFLICT_REPLACE);
            changed = true;
        }

        // The media scanner reports no duration or album artist for DSD; fill them in every sync.
        ContentValues a = new ContentValues();
        long curDuration = c.isNull(12) ? 0 : c.getLong(12);
        if (duration > 0 && curDuration != duration) a.put("duration", duration);
        if (albumArtist != null && c.isNull(13)) a.put("album_artist", albumArtist);
        if (a.size() > 0) {
            db.update("audio", a, "_id = ?", new String[]{String.valueOf(audioId)});
            changed = true;
        }
        return changed;
    }

    private static String pick(String fromFile, String current) {
        return fromFile != null && !fromFile.isEmpty() ? fromFile : current;
    }

    static DsdInfo readInfo(String path, boolean picture) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(path);
            return DsdInfo.read(in.getChannel(), picture);
        } catch (Exception e) {
            Log.w(TAG, "Can't read " + path + ": " + e.getMessage());
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------- cover art

    /** Replaces MediaMetadataRetriever.setDataSource(path) in the app's embedded-art code. */
    public static void mmrSetDataSource(MediaMetadataRetriever mmr, String path) {
        if (DsdInfo.isDsdName(path)) {
            try { mmr.setDataSource(path); } catch (RuntimeException ignored) { }
            return;
        }
        mmr.setDataSource(path);
    }

    /** Replaces MediaMetadataRetriever.getEmbeddedPicture() in the app's embedded-art code. */
    public static byte[] embeddedPicture(MediaMetadataRetriever mmr, String path) {
        if (DsdInfo.isDsdName(path)) {
            DsdInfo info = readInfo(path, true);
            return info != null ? info.picture : null;
        }
        return mmr.getEmbeddedPicture();
    }

    // --------------------------------------------------------------- HD badge

    /** Replaces the hi-res scan's MediaMetadataRetriever.setDataSource: DSD can't be opened by Android, so don't fail. */
    public static void mmrSetDataSourceTrack(MediaMetadataRetriever mmr, android.content.Context ctx, android.net.Uri uri, Object trackInfo) {
        if (isDsdTrack(trackInfo)) {
            try { mmr.setDataSource(ctx, uri); } catch (RuntimeException ignored) { }
            return;
        }
        mmr.setDataSource(ctx, uri);
    }

    /** True for DSD tracks, by any of the MIME types phones use or by file extension. */
    public static boolean isDsdTrack(Object trackInfo) {
        String mime = field(trackInfo, "mimeType"), path = field(trackInfo, "dataPath");
        if (mime != null) {
            String m = mime.toLowerCase(java.util.Locale.US);
            if (m.equals("audio/dsd") || m.equals("audio/x-dsd") || m.equals("audio/dsf") || m.equals("audio/x-dsf")
                    || m.equals("audio/dff") || m.equals("audio/x-dff")) return true;
        }
        return DsdInfo.isDsdName(path);
    }

    private static String field(Object o, String name) {
        if (o == null) return null;
        try {
            java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(o);
            return v instanceof String ? (String) v : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Sony's HD list never re-checks tracks it already knows, so DSD files checked before
     * DSD was recognised stay without the badge. Once per version of this rule, ask the
     * HD sync to rebuild its list from scratch (Sony's own switch for this).
     */
    public static void forceHdRescanOnce(android.content.Context ctx) {
        if (ctx == null) return;
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("fpmod_state", android.content.Context.MODE_PRIVATE);
            if (sp.getBoolean("hd_rescan_dsd_v1", false)) return;
            Class<?> c = Class.forName("com.sonymobile.music.common.ServiceProcessPreferenceUtils");
            c.getMethod("setClearHighResMediaTable", android.content.Context.class, boolean.class).invoke(null, ctx, true);
            sp.edit().putBoolean("hd_rescan_dsd_v1", true).apply();
            Log.i(TAG, "Asked for a full HD list rebuild so DSD tracks get re-checked");
        } catch (Throwable e) {
            Log.w(TAG, "Couldn't request HD rebuild", e);
        }
    }
}
