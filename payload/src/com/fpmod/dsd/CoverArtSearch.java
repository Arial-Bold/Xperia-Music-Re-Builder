package com.fpmod.dsd;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Download image" for album covers: searches MusicBrainz for the album, collects front
 * covers from the Cover Art Archive that are at least 800x800, lets the user pick one,
 * and hands it to the edit screen exactly like a picked local image.
 */
public final class CoverArtSearch {
    private static final String TAG = "CoverArtSearch";
    private static final String UA = "SonyMusicFairphoneMod/1.1 ( https://github.com/Arial-Bold )";
    private static final int MIN_SIZE = 800;
    private static final int MAX_CANDIDATES = 6;
    private static final int MAX_RELEASES = 15;

    private static final int THREADS = 6;

    private static final class Candidate {
        String label;
        String fullUrl;   // downloaded only when picked
        int w, h;
        Bitmap thumb;
        int order;
    }

    private CoverArtSearch() { }

    /** Called from EditMusicInfoBaseFragment.onActionSelected for the new action. */
    public static void start(final Object fragment, final Object imageType) {
        final Context ctx = (Context) get(fragment, "mActivity");
        if (ctx == null) return;
        if (imageType == null || !"ALBUM".equals(((Enum<?>) imageType).name())) {
            Toast.makeText(ctx, "Online images are only available for album covers", Toast.LENGTH_LONG).show();
            return;
        }
        final String album = text(get(fragment, "mAlbum"));
        if (album.isEmpty()) {
            Toast.makeText(ctx, "Enter the album name first", Toast.LENGTH_LONG).show();
            return;
        }
        final String artist = findArtist(fragment, ctx, album);
        if (artist.isEmpty()) {
            Toast.makeText(ctx, "Enter the artist name first — album names alone match too many releases", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(ctx, "Searching MusicBrainz for \u201C" + album + "\u201D by " + artist + "\u2026", Toast.LENGTH_SHORT).show();
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Candidate> found = new ArrayList<>();
                String error = null;
                try {
                    search(album, artist, found);
                } catch (Throwable e) {
                    Log.w(TAG, "Search failed", e);
                    String why = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    error = "Couldn't reach MusicBrainz: " + why;
                }
                final String err = error;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (err != null) { Toast.makeText(ctx, err, Toast.LENGTH_LONG).show(); return; }
                        if (found.isEmpty()) {
                            Toast.makeText(ctx, "No covers of at least " + MIN_SIZE + "×" + MIN_SIZE + " found for this album", Toast.LENGTH_LONG).show();
                            return;
                        }
                        choose(fragment, imageType, ctx, found);
                    }
                });
            }
        }, "CoverArtSearch").start();
    }

    // ------------------------------------------------------------------ search

    private static void search(String album, String artist, List<Candidate> out) throws Exception {
        List<String[]> releases = new ArrayList<>();       // {mbid, label, title, artist, releaseGroupId}
        String q = "release:\"" + esc(album) + "\" AND artist:\"" + esc(artist) + "\"";
        findReleases(q, releases);
        rank(releases, album, artist);
        if (releases.isEmpty()) {
            q = "release:(" + words(album) + ") AND artist:(" + words(artist) + ")";
            findReleases(q, releases);
            rank(releases, album, artist);
        }
        // One lookup per release group: all editions in a group share the group's chosen cover.
        List<String[]> groups = new ArrayList<>();
        Set<String> seenGroups = new HashSet<>();
        for (String[] r : releases) {
            String key = r[4].isEmpty() ? "r:" + r[0] : r[4];
            if (seenGroups.add(key)) groups.add(r);
            if (groups.size() >= MAX_CANDIDATES + 4) break;
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(THREADS);
        List<java.util.concurrent.Future<Candidate>> futures = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            final String[] r = groups.get(i);
            final int order = i;
            futures.add(pool.submit(new java.util.concurrent.Callable<Candidate>() {
                @Override public Candidate call() {
                    try { return candidate(r, order); }
                    catch (Throwable e) { Log.w(TAG, "Skipping " + r[0] + ": " + e); return null; }
                }
            }));
        }
        pool.shutdown();
        Set<String> seenImages = new HashSet<>();
        for (java.util.concurrent.Future<Candidate> f : futures) {
            Candidate c;
            try { c = f.get(40, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable e) { c = null; }
            if (c == null || !seenImages.add(c.fullUrl)) continue;
            out.add(c);
            if (out.size() >= MAX_CANDIDATES) break;
        }
        pool.shutdownNow();
    }

    /** Checks one release (group) for a big enough front cover; downloads only a thumbnail. */
    private static Candidate candidate(String[] r, int order) throws Exception {
        String[] urls = r[4].isEmpty() ? null : frontImageUrls("release-group/" + r[4]);
        if (urls == null) urls = frontImageUrls("release/" + r[0]);
        if (urls == null) return null;
        String full = null;
        int[] size = urls[0].isEmpty() ? null : imageSize(urls[0]);
        if (size != null && size[0] >= MIN_SIZE && size[1] >= MIN_SIZE) full = urls[0];
        else if (!urls[1].isEmpty()) {
            size = imageSize(urls[1]);
            if (size != null && size[0] >= MIN_SIZE && size[1] >= MIN_SIZE) full = urls[1];
        }
        if (full == null) return null;
        Candidate c = new Candidate();
        c.fullUrl = full; c.w = size[0]; c.h = size[1]; c.order = order;
        c.label = r[1] + "\n" + c.w + "\u00D7" + c.h;
        byte[] t = urls[2].isEmpty() ? null : get(urls[2], 2 * 1024 * 1024);
        if (t != null) c.thumb = BitmapFactory.decodeByteArray(t, 0, t.length, new BitmapFactory.Options());
        return c;
    }

    /** Reads just the start of an image to get its width and height. */
    private static int[] imageSize(String url) throws Exception {
        int[] limits = {64 * 1024, 1024 * 1024};
        for (int lim : limits) {
            byte[] head = getHead(url, lim);
            if (head == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(head, 0, head.length, o);
            if (o.outWidth > 0 && o.outHeight > 0) return new int[]{o.outWidth, o.outHeight};
            if (head.length < lim) return null;      // whole file read and still unreadable
        }
        return null;
    }

    /** Exact title + artist matches first, then exact title, keeping MusicBrainz's order otherwise. */
    private static void rank(List<String[]> rs, final String album, final String artist) {
        final String a = norm(album), b = norm(artist);
        java.util.Collections.sort(rs, new java.util.Comparator<String[]>() {
            @Override public int compare(String[] x, String[] y) { return score(y) - score(x); }
            private int score(String[] r) {
                int sc = 0;
                if (norm(r[2]).equals(a)) sc += 2;
                if (norm(r[3]).equals(b)) sc += 1;
                return sc;
            }
        });
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static boolean tooSmall(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        return o.outWidth < MIN_SIZE || o.outHeight < MIN_SIZE;
    }

    private static void findReleases(String query, List<String[]> out) throws Exception {
        String url = "https://musicbrainz.org/ws/2/release/?fmt=json&limit=" + MAX_RELEASES + "&query=" + URLEncoder.encode(query, "UTF-8");
        byte[] body = get(url, 4 * 1024 * 1024);
        if (body == null) return;
        JSONObject j = new JSONObject(new String(body, "UTF-8"));
        JSONArray rs = j.optJSONArray("releases");
        if (rs == null) return;
        for (int i = 0; i < rs.length(); i++) {
            JSONObject r = rs.getJSONObject(i);
            String id = r.optString("id", "");
            if (id.isEmpty()) continue;
            StringBuilder label = new StringBuilder(r.optString("title", ""));
            JSONArray ac = r.optJSONArray("artist-credit");
            if (ac != null && ac.length() > 0) {
                StringBuilder a = new StringBuilder();
                for (int k = 0; k < ac.length(); k++) {
                    JSONObject c = ac.getJSONObject(k);
                    a.append(c.optString("name", "")).append(c.optString("joinphrase", ""));
                }
                label.append(" — ").append(a);
            }
            String date = r.optString("date", ""), country = r.optString("country", "");
            StringBuilder extra = new StringBuilder();
            if (date.length() >= 4) extra.append(date.substring(0, 4));
            if (!country.isEmpty()) extra.append(extra.length() > 0 ? ", " : "").append(country);
            if (extra.length() > 0) label.append(" (").append(extra).append(")");
            String credit = "";
            if (ac != null) {
                StringBuilder a2 = new StringBuilder();
                for (int k = 0; k < ac.length(); k++) {
                    JSONObject c = ac.getJSONObject(k);
                    a2.append(c.optString("name", "")).append(c.optString("joinphrase", ""));
                }
                credit = a2.toString();
            }
            JSONObject rg = r.optJSONObject("release-group");
            String rgid = rg != null ? rg.optString("id", "") : "";
            out.add(new String[]{id, label.toString(), r.optString("title", ""), credit, rgid});
        }
    }

    /** Returns {1200px URL, original URL, 250px URL} for the front cover of "release/ID" or "release-group/ID". */
    private static String[] frontImageUrls(String path) throws Exception {
        byte[] body = get("https://coverartarchive.org/" + path, 1024 * 1024);
        if (body == null) return null;
        JSONArray images = new JSONObject(new String(body, "UTF-8")).optJSONArray("images");
        if (images == null) return null;
        for (int i = 0; i < images.length(); i++) {
            JSONObject im = images.getJSONObject(i);
            if (!im.optBoolean("front", false)) continue;
            String orig = im.optString("image", "");
            JSONObject th = im.optJSONObject("thumbnails");
            String big = th != null ? th.optString("1200", "") : "";
            String small = th != null ? th.optString("250", th.optString("small", "")) : "";
            // The 1200px file often exists even when the listing leaves it out.
            if (big.isEmpty() && !orig.isEmpty()) big = orig.replaceFirst("\\.[A-Za-z]{3,4}$", "-1200.jpg");
            if (small.isEmpty() && !orig.isEmpty()) small = orig.replaceFirst("\\.[A-Za-z]{3,4}$", "-250.jpg");
            return new String[]{https(big), https(orig), https(small)};
        }
        return null;
    }

    private static String https(String u) {
        return u == null || u.isEmpty() ? "" : u.replaceFirst("^http://", "https://");
    }

    /** HTTP GET following redirects (including http -> https). Returns null on 404. */
    private static long lastMb;

    private static byte[] get(String url, int maxBytes) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                return fetch(url, maxBytes, false);
            } catch (RetryException e) {
                if (attempt >= 3) throw new java.io.IOException("server busy (HTTP 503), try again in a minute");
                Thread.sleep(1500L * (attempt + 1));
            }
        }
    }

    private static final class RetryException extends java.io.IOException { }

    /** Like get(), but asks for and keeps only the first maxBytes bytes. */
    private static byte[] getHead(String url, int maxBytes) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                return fetch(url, maxBytes, true);
            } catch (RetryException e) {
                if (attempt >= 3) throw new java.io.IOException("server busy (HTTP 503)");
                Thread.sleep(1500L * (attempt + 1));
            }
        }
    }

    private static byte[] fetch(String url, int maxBytes, boolean headOnly) throws Exception {
        for (int hop = 0; hop < 6; hop++) {
            if (url.contains("musicbrainz.org")) {      // MusicBrainz allows about 1 request per second
                synchronized (CoverArtSearch.class) {
                    long wait = lastMb + 1100 - System.currentTimeMillis();
                    if (wait > 0) Thread.sleep(wait);
                    lastMb = System.currentTimeMillis();
                }
            }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", url.contains("/ws/2/") || url.contains("coverartarchive.org/release/") && !url.matches(".*\\.(jpg|jpeg|png|gif)$") ? "application/json" : "*/*");
            if (headOnly) c.setRequestProperty("Range", "bytes=0-" + (maxBytes - 1));
            int code = c.getResponseCode();
            if (code == 503) { c.disconnect(); throw new RetryException(); }
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) return null;
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code == 404) { c.disconnect(); return null; }
            if (code != 200 && !(headOnly && code == 206)) {
                String host = new URL(url).getHost();
                c.disconnect();
                throw new java.io.IOException("HTTP " + code + " from " + host);
            }
            InputStream in = c.getInputStream();
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() >= maxBytes) {
                        if (headOnly) break;
                        throw new java.io.IOException("Too large");
                    }
                }
                return bo.toByteArray();
            } finally {
                in.close();
                c.disconnect();
            }
        }
        throw new java.io.IOException("Too many redirects");
    }

    // ------------------------------------------------------------- choose/apply

    private static void choose(final Object fragment, final Object imageType, final Context ctx, final List<Candidate> list) {
        final int pad = (int) (12 * ctx.getResources().getDisplayMetrics().density);
        final int size = (int) (72 * ctx.getResources().getDisplayMetrics().density);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return list.size(); }
            @Override public Object getItem(int i) { return list.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                Candidate c = list.get(i);
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(pad * 2, pad, pad * 2, pad);
                row.setGravity(16 /* Gravity.CENTER_VERTICAL */);
                ImageView iv = new ImageView(ctx);
                iv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(c.thumb);
                TextView tv = new TextView(ctx);
                tv.setText(c.label);
                tv.setPadding(pad, 0, 0, 0);
                tv.setTextSize(15);
                row.addView(iv);
                row.addView(tv);
                return row;
            }
        };
        new AlertDialog.Builder(ctx)
                .setTitle("Choose a cover")
                .setAdapter(adapter, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        download(fragment, imageType, ctx, list.get(which));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void download(final Object fragment, final Object imageType, final Context ctx, final Candidate c) {
        Toast.makeText(ctx, "Downloading cover\u2026", Toast.LENGTH_SHORT).show();
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                byte[] data = null;
                try { data = get(c.fullUrl, 30 * 1024 * 1024); } catch (Throwable e) { Log.w(TAG, "Download failed", e); }
                final byte[] got = data;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (got == null) { Toast.makeText(ctx, "Couldn't download that cover", Toast.LENGTH_LONG).show(); return; }
                        apply(fragment, imageType, ctx, got);
                    }
                });
            }
        }, "CoverArtDownload").start();
    }

    private static void apply(Object fragment, Object imageType, Context ctx, byte[] data) {
        try {
            File dir = ctx.getExternalFilesDir("/cache/");
            if (dir == null) throw new java.io.IOException("No cache folder");
            File f = new File(dir, "tempAlbumOnline.jpg");
            FileOutputStream os = new FileOutputStream(f);
            try { os.write(data); } finally { os.close(); }

            // Same steps the edit screen takes when it resumes with a picked image.
            Object info = get(fragment, "mEditMusicInfo");
            call(info, "setAlbumArt", new Class<?>[]{File.class}, f);
            call(info, "setAlbumArtChanged", new Class<?>[]{boolean.class}, true);
            Object activity = get(fragment, "mActivity");
            call(activity, "setLocalImageData", new Class<?>[]{Pair.class}, (Object) null);
            Object first = get(fragment, "mIsFirstStart");
            boolean firstStart = first instanceof Boolean && (Boolean) first;
            call(fragment, "setValues", new Class<?>[]{Context.class, boolean.class, boolean.class}, ctx, !firstStart, true);
        } catch (Throwable e) {
            Log.w(TAG, "Couldn't apply cover", e);
            Toast.makeText(ctx, "Couldn't use that image", Toast.LENGTH_LONG).show();
        }
    }

    // --------------------------------------------------------------- reflection

    private static Object get(Object o, String name) {
        if (o == null) return null;
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(o, args);
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(name);
    }

    /** Artist for the search: the screen's field, then the edit data, then the album's tracks. */
    private static String findArtist(Object fragment, Context ctx, String album) {
        String a = clean(text(get(fragment, "mArtist")));
        Object info = get(fragment, "mEditMusicInfo");
        if (a.isEmpty() && info != null) a = clean(str(get(info, "mArtist")));
        if (a.isEmpty() && info != null) a = clean(str(get(info, "mOriginalArtist")));
        if (a.isEmpty()) a = clean(trackArtist(ctx, album));
        return a;
    }

    private static String trackArtist(Context ctx, String album) {
        android.database.Cursor c = null;
        try {
            c = ctx.getContentResolver().query(android.net.Uri.parse("content://media/external/audio/media"),
                    new String[]{"artist"}, "album = ?", new String[]{album}, null);
            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
            String best = "";
            int bestN = 0;
            while (c != null && c.moveToNext()) {
                String v = clean(c.getString(0));
                if (v.isEmpty()) continue;
                Integer n = counts.get(v);
                n = n == null ? 1 : n + 1;
                counts.put(v, n);
                if (n > bestN) { bestN = n; best = v; }
            }
            return best;
        } catch (Throwable e) {
            return "";
        } finally {
            if (c != null) c.close();
        }
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.trim();
        String l = s.toLowerCase(Locale.US);
        if (l.equals("<unknown>") || l.equals("unknown") || l.equals("unknown artist") || l.equals("various artists") || l.equals("various")) return "";
        return s;
    }

    private static String str(Object o) { return o instanceof String ? (String) o : ""; }

    private static String text(Object editText) {
        if (!(editText instanceof TextView)) return "";
        CharSequence cs = ((TextView) editText).getText();
        return cs == null ? "" : cs.toString().trim();
    }

    /** Plain words only, for the loose fallback query (no search-syntax characters). */
    private static String words(String s) {
        return s.replaceAll("[^\\p{L}\\p{N}\\s]", " ").trim().replaceAll("\\s+", " ");
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
