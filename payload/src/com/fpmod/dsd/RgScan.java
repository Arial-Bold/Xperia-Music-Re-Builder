package com.fpmod.dsd;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Add ReplayGain to files": finds tracks without ReplayGain, asks Android for permission
 * to change them, measures each (EBU R128, like rsgain) and writes the tags into the files.
 */
public final class RgScan {
    private static final String TAG = "RgScan";
    private static final String CHANNEL = "fpmod_rg_scan";
    private static final int NOTIF_ID = 0x5251;
    private static final int REQ = 0x5252;
    private static final int BATCH = 400;

    private static final class Track {
        long id; Uri uri; String name, albumKey, album;
        Loudness meter; double lufs, peak; double[] blocks;
    }

    private static volatile boolean running, stop;
    private static volatile String progress = "";

    private RgScan() { }

    /** Settings row click. */
    public static void start(final Context ctx) {
        final Activity act = activity(ctx);
        if (act == null) return;
        if (running) {
            new AlertDialog.Builder(act).setTitle("ReplayGain scan running")
                    .setMessage(progress + "\n\nStop the scan? Files already done keep their tags.")
                    .setPositiveButton("Stop", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { stop = true; }
                    })
                    .setNegativeButton("Keep going", null).show();
            return;
        }
        Toast.makeText(act, "Looking for tracks without ReplayGain…", Toast.LENGTH_SHORT).show();
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Track> todo = new ArrayList<>();
                final Map<String, Integer> skipped = new LinkedHashMap<>();
                String err = null;
                try { find(act, todo, skipped); } catch (Throwable e) { err = e.toString(); Log.w(TAG, "find failed", e); }
                final String error = err;
                ui.post(new Runnable() {
                    @Override public void run() { confirm(act, todo, skipped, error); }
                });
            }
        }, "RgScanFind").start();
    }

    private static Activity activity(Context c) {
        for (int i = 0; i < 10 && c != null; i++) {
            if (c instanceof Activity) return (Activity) c;
            if (!(c instanceof ContextWrapper)) return null;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    // -------------------------------------------------------------- find

    private static void find(Context ctx, List<Track> todo, Map<String, Integer> skipped) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        Uri base = Uri.parse("content://media/external/audio/media");
        Cursor c = cr.query(base, new String[]{"_id", "_display_name", "album", "relative_path"}, "is_music != 0", null, "relative_path, album, _display_name");
        if (c == null) return;
        try {
            while (c.moveToNext()) {
                Track t = new Track();
                t.id = c.getLong(0);
                t.uri = ContentUris.withAppendedId(base, t.id);
                t.name = c.getString(1);
                t.album = c.isNull(2) ? "" : c.getString(2);
                String rel = c.isNull(3) ? "" : c.getString(3);
                t.albumKey = t.album.isEmpty() ? null : rel + "|" + t.album;
                ParcelFileDescriptor pfd = null;
                try {
                    pfd = cr.openFileDescriptor(t.uri, "r");
                    if (pfd == null) continue;
                    FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
                    FileChannel ch = in.getChannel();
                    RgTags tags = RgTags.read(ch);
                    if (tags != null && !Float.isNaN(tags.trackGain) && tags.saneRaw()) continue;   // already tagged (and sensible)
                    String why = RgWriter.unsupportedReason(readOnly(ch));
                    if (why != null) { skipped.put(why, (skipped.containsKey(why) ? skipped.get(why) : 0) + 1); continue; }
                    todo.add(t);
                } catch (Exception e) {
                    skipped.put("couldn't open", (skipped.containsKey("couldn't open") ? skipped.get("couldn't open") : 0) + 1);
                } finally {
                    if (pfd != null) try { pfd.close(); } catch (IOException ignored) { }
                }
            }
        } finally {
            c.close();
        }
    }

    private static void confirm(final Activity act, final List<Track> todo, Map<String, Integer> skipped, String error) {
        if (error != null) { Toast.makeText(act, "Couldn't read your library: " + error, Toast.LENGTH_LONG).show(); return; }
        int skipCount = 0;
        StringBuilder sk = new StringBuilder();
        for (Map.Entry<String, Integer> e : skipped.entrySet()) {
            skipCount += e.getValue();
            sk.append("\n• ").append(e.getValue()).append(" — ").append(e.getKey());
        }
        if (todo.isEmpty()) {
            new AlertDialog.Builder(act).setTitle("ReplayGain")
                    .setMessage("Every track that can be tagged already has ReplayGain." + (skipCount > 0 ? "\n\nNot supported:" + sk : ""))
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        String msg = todo.size() + " track" + (todo.size() == 1 ? "" : "s") + " will be measured and tagged."
                + (skipCount > 0 ? "\n\nSkipped:" + sk : "")
                + "\n\nThis changes your music files. Make sure you have a backup."
                + "\n\nAndroid will ask you to allow the Music app to modify them.";
        new AlertDialog.Builder(act).setTitle("Add ReplayGain to files")
                .setMessage(msg)
                .setPositiveButton("Continue", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { requestWrite(act, todo, 0); }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    // ------------------------------------------------- write permission

    private static void requestWrite(final Activity act, final List<Track> todo, final int from) {
        if (from >= todo.size()) { startWorker(act.getApplicationContext(), todo); return; }
        final int to = Math.min(todo.size(), from + BATCH);
        List<Uri> uris = new ArrayList<>();
        for (int i = from; i < to; i++) uris.add(todo.get(i).uri);
        try {
            PendingIntent pi = MediaStore.createWriteRequest(act.getContentResolver(), uris);
            final Application app = act.getApplication();
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity a) {
                    if (a != act) return;
                    app.unregisterActivityLifecycleCallbacks(this);
                    Uri probe = todo.get(from).uri;
                    int granted = act.checkUriPermission(probe, android.os.Process.myPid(), android.os.Process.myUid(),
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    if (granted != 0) {
                        Toast.makeText(act, "Not allowed to change the files, so nothing was done", Toast.LENGTH_LONG).show();
                        return;
                    }
                    requestWrite(act, todo, to);
                }
                @Override public void onActivityCreated(Activity a, Bundle b) { }
                @Override public void onActivityStarted(Activity a) { }
                @Override public void onActivityPaused(Activity a) { }
                @Override public void onActivityStopped(Activity a) { }
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                @Override public void onActivityDestroyed(Activity a) { }
            });
            act.startIntentSenderForResult(pi.getIntentSender(), REQ, null, 0, 0, 0);
        } catch (Throwable e) {
            Log.w(TAG, "write request failed", e);
            Toast.makeText(act, "Couldn't ask for permission: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ----------------------------------------------------------- worker

    private static void startWorker(final Context app, final List<Track> todo) {
        running = true; stop = false;
        final Handler ui = new Handler(Looper.getMainLooper());
        // Several albums are measured at once, one per core (up to 4), keeping some cores free.
        final int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        Toast.makeText(app, "Measuring " + todo.size() + " tracks (" + threads + " at a time). Progress is in your notifications.", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override public void run() {
                final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
                final java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
                final java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
                final List<String> failures = java.util.Collections.synchronizedList(new ArrayList<String>());
                final long[] lastNotice = {0};
                Map<String, List<Track>> groups = new LinkedHashMap<>();
                for (Track t : todo) {
                    String k = t.albumKey != null ? t.albumKey : "single:" + t.id;
                    List<Track> l = groups.get(k);
                    if (l == null) { l = new ArrayList<>(); groups.put(k, l); }
                    l.add(t);
                }
                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
                List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
                for (final List<Track> album : groups.values()) {
                    jobs.add(pool.submit(new Runnable() {
                        @Override public void run() {
                            if (stop) return;
                            List<double[]> energies = new ArrayList<>();
                            double albumPeak = 0;
                            List<Track> measured = new ArrayList<>();
                            for (Track t : album) {
                                if (stop) return;
                                try {
                                    measure(app, t);
                                    measured.add(t);
                                    energies.add(t.blocks);
                                    albumPeak = Math.max(albumPeak, t.peak);
                                } catch (Throwable e) {
                                    if (stop) return;
                                    failed.incrementAndGet(); failures.add(t.name + ": " + e.getMessage());
                                    Log.w(TAG, "measure failed " + t.name, e);
                                }
                                int d = done.incrementAndGet();
                                progress = "Measured " + d + " of " + todo.size() + " \u2014 " + t.name;
                                synchronized (lastNotice) {
                                    long now = System.currentTimeMillis();
                                    if (now - lastNotice[0] > 1000) { lastNotice[0] = now; showNotice(app, progress, d, todo.size(), true); }
                                }
                            }
                            if (stop) return;   // album unfinished: its tracks are left for the next scan
                            double albumLufs = Double.NaN;
                            if (album.get(0).albumKey != null && !measured.isEmpty()) {
                                int n = 0;
                                for (double[] e : energies) n += e.length;
                                double[] all = new double[n];
                                int p = 0;
                                for (double[] e : energies) { System.arraycopy(e, 0, all, p, e.length); p += e.length; }
                                albumLufs = Loudness.integrated(all);
                            }
                            for (Track t : measured) {
                                RgWriter.Values v = new RgWriter.Values();
                                v.trackGain = Loudness.gainDb(t.lufs);
                                v.trackPeak = t.peak;
                                if (!Double.isNaN(albumLufs)) { v.albumGain = Loudness.gainDb(albumLufs); v.albumPeak = albumPeak; }
                                try { writeTags(app, t, v); ok.incrementAndGet(); }
                                catch (Throwable e) { failed.incrementAndGet(); failures.add(t.name + ": " + e.getMessage()); Log.w(TAG, "write failed " + t.name, e); }
                                t.blocks = null;
                            }
                        }
                    }));
                }
                pool.shutdown();
                for (java.util.concurrent.Future<?> j : jobs) {
                    try { j.get(); } catch (Throwable e) { Log.w(TAG, "album job failed", e); }
                }
                running = false;
                int okN = ok.get(), failN = failed.get();
                final String summary = (stop ? "Stopped. " : "") + "ReplayGain added to " + okN + " track" + (okN == 1 ? "" : "s")
                        + (failN > 0 ? ", " + failN + " failed" : "") + ".";
                final String detail = failures.isEmpty() ? summary : summary + "\n" + join(new ArrayList<>(failures), 5);
                progress = summary;
                showNotice(app, detail, 0, 0, false);
                ui.post(new Runnable() { @Override public void run() { Toast.makeText(app, summary, Toast.LENGTH_LONG).show(); } });
            }
        }, "RgScan").start();
    }

    private static String join(List<String> l, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size() && i < max; i++) b.append(i > 0 ? "\n" : "").append(l.get(i));
        if (l.size() > max) b.append("\n…and ").append(l.size() - max).append(" more");
        return b.toString();
    }

    // ---------------------------------------------------------- measure

    private static void measure(Context ctx, Track t) throws Exception {
        ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(t.uri, "r");
        if (pfd == null) throw new IOException("can't open");
        try {
            FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
            FileChannel ch = in.getChannel();
            if (DsdWav.looksLikeDsd(ch)) measureDsd(ch, t);
            else measureCodec(pfd, t);
        } finally {
            pfd.close();
        }
        t.lufs = t.meter.integrated();
        t.peak = t.meter.samplePeak();
        t.blocks = t.meter.blockEnergies();
        t.meter = null;
        if (Double.isNaN(t.lufs) || Double.isInfinite(t.lufs) || t.lufs <= -69.9) throw new IOException("measured as silent, so no tags were written");
        if (Double.isNaN(t.peak) || Double.isInfinite(t.peak) || t.peak <= 0 || t.peak > 4) throw new IOException("peak level out of range (" + t.peak + ")");
    }

    private static void measureDsd(FileChannel ch, Track t) throws IOException {
        DsdWav w = new DsdWav(ch);
        Loudness m = new Loudness(2, DsdWav.OUT_RATE);
        byte[] buf = new byte[6 * 8192];
        float[] f = new float[2 * 8192];
        long pos = 44, end = w.size();
        while (pos < end && !stop) {
            int n = w.readAt(pos, buf, 0, buf.length);
            if (n <= 0) break;
            int frames = n / 6;
            for (int i = 0; i < frames * 2; i++) {
                int o = i * 3;
                int s = (buf[o] & 0xff) | ((buf[o + 1] & 0xff) << 8) | (buf[o + 2] << 16);
                f[i] = s / 8388608f;
            }
            m.add(f, frames);
            pos += n;
        }
        t.meter = m;
    }

    private static void measureCodec(ParcelFileDescriptor pfd, Track t) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(pfd.getFileDescriptor());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new IOException("no audio track");
            ex.selectTrack(track);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            codec = openDecoder(mime, fmt);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int encoding = 2;
            Loudness m = null;
            float[] fb = new float[0];
            short[] sb = new short[0];
            int[] ib32 = new int[0];
            byte[] b24 = new byte[0];
            while (!outDone && !stop) {
                boolean progressed = false;
                // hand the decoder every free input buffer without waiting
                while (!inDone) {
                    int ii = codec.dequeueInputBuffer(0);
                    if (ii < 0) break;
                    ByteBuffer ib = codec.getInputBuffer(ii);
                    int n = ex.readSampleData(ib, 0);
                    if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                    else { codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    progressed = true;
                }
                // take everything that's decoded; only wait when nothing else could happen
                while (!outDone) {
                    int oi = codec.dequeueOutputBuffer(info, progressed ? 0 : 5000);
                    if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = codec.getOutputFormat();
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        encoding = of.containsKey("pcm-encoding") ? of.getInteger("pcm-encoding") : 2;
                        m = new Loudness(channels, rate);
                        continue;
                    }
                    if (oi < 0) continue;
                    progressed = true;
                    // Take the format from this buffer: some decoders report one sample format and deliver another.
                    MediaFormat bf = codec.getOutputFormat(oi);
                    if (bf != null) {
                        int bc = bf.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? bf.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : channels;
                        int br = bf.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? bf.getInteger(MediaFormat.KEY_SAMPLE_RATE) : rate;
                        int be = bf.containsKey("pcm-encoding") ? bf.getInteger("pcm-encoding") : 2;
                        if (m == null || bc != channels || br != rate) { channels = bc; rate = br; m = new Loudness(channels, rate); }
                        encoding = be;
                    }
                    if (m == null) m = new Loudness(channels, rate);
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size);
                        ByteBuffer b = ob.slice().order(ByteOrder.nativeOrder());
                        int bps = encoding == 4 || encoding == 22 ? 4 : encoding == 21 ? 3 : 2;
                        int samples = info.size / bps;
                        if (fb.length < samples) fb = new float[samples];
                        // bulk copies: far faster than reading one sample at a time
                        switch (encoding) {
                            case 4:
                                b.asFloatBuffer().get(fb, 0, samples);
                                break;
                            case 22:
                                if (ib32.length < samples) ib32 = new int[samples];
                                b.asIntBuffer().get(ib32, 0, samples);
                                for (int i = 0; i < samples; i++) fb[i] = ib32[i] / 2147483648f;
                                break;
                            case 21:
                                if (b24.length < samples * 3) b24 = new byte[samples * 3];
                                b.get(b24, 0, samples * 3);
                                for (int i = 0, o = 0; i < samples; i++, o += 3)
                                    fb[i] = ((b24[o] & 0xff) | ((b24[o + 1] & 0xff) << 8) | (b24[o + 2] << 16)) / 8388608f;
                                break;
                            default:
                                if (sb.length < samples) sb = new short[samples];
                                b.asShortBuffer().get(sb, 0, samples);
                                for (int i = 0; i < samples; i++) fb[i] = sb[i] / 32768f;
                        }
                        for (int i = 0; i < samples; i++) {
                            float x = fb[i];
                            if (x != x || x > 4f || x < -4f) throw new IOException("decoder returned unreadable audio");
                        }
                        m.add(fb, samples / channels);
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
            if (stop) throw new IOException("stopped");
            if (m == null) throw new IOException("no audio decoded");
            t.meter = m;
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Throwable ignored) { } codec.release(); }
            ex.release();
        }
    }

    /**
     * Prefers Android's own software decoder. Some phones (Samsung especially) otherwise hand
     * out a decoder that sends the audio to a separate audio processor: fine for playback,
     * slow when several songs are decoded at full speed at once.
     */
    private static MediaCodec openDecoder(String mime, MediaFormat fmt) throws IOException {
        String best = null;
        int bestScore = 0;
        try {
            for (android.media.MediaCodecInfo ci : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (ci.isEncoder()) continue;
                boolean fits = false;
                for (String t : ci.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) { fits = true; break; }
                if (!fits) continue;
                String n = ci.getName();
                int score = 0;
                if (n.startsWith("c2.android.") || n.startsWith("OMX.google.")) score = 3;
                else { try { if (ci.isSoftwareOnly()) score = 2; } catch (Throwable ignored) { } }
                if (score > bestScore) { bestScore = score; best = n; }
            }
        } catch (Throwable ignored) { }
        if (best != null) {
            MediaCodec c = null;
            try {
                c = MediaCodec.createByCodecName(best);
                c.configure(fmt, null, null, 0);
                return c;
            } catch (Throwable e) {
                if (c != null) try { c.release(); } catch (Throwable ignored) { }
            }
        }
        MediaCodec c = MediaCodec.createDecoderByType(mime);
        c.configure(fmt, null, null, 0);
        return c;
    }

    // ------------------------------------------------------------ write

    private static void writeTags(final Context ctx, Track t, RgWriter.Values v) throws Exception {
        ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(t.uri, "rw");
        if (pfd == null) throw new IOException("can't open for writing");
        try {
            final FileChannel rd = new FileInputStream(pfd.getFileDescriptor()).getChannel();
            final FileChannel wr = new FileOutputStream(pfd.getFileDescriptor()).getChannel();
            RgWriter.Rw rw = new RgWriter.Rw() {
                public long size() throws IOException { return rd.size(); }
                public int read(ByteBuffer b, long p) throws IOException { return rd.read(b, p); }
                public void write(ByteBuffer b, long p) throws IOException { while (b.hasRemaining()) p += wr.write(b, p); }
                public void truncate(long s) throws IOException { wr.truncate(s); }
            };
            final File tmp = new File(ctx.getCacheDir(), "rg_rebuild_" + Thread.currentThread().getId() + ".tmp");
            RgWriter.Temp temp = new RgWriter.Temp() {
                RandomAccessFile raf;
                public RgWriter.Rw create() throws IOException {
                    raf = new RandomAccessFile(tmp, "rw");
                    raf.setLength(0);
                    final FileChannel c = raf.getChannel();
                    return new RgWriter.Rw() {
                        public long size() throws IOException { return c.size(); }
                        public int read(ByteBuffer b, long p) throws IOException { return c.read(b, p); }
                        public void write(ByteBuffer b, long p) throws IOException { while (b.hasRemaining()) p += c.write(b, p); }
                        public void truncate(long s) throws IOException { c.truncate(s); }
                    };
                }
                public void delete() {
                    try { if (raf != null) raf.close(); } catch (IOException ignored) { }
                    tmp.delete();
                }
            };
            RgWriter.write(rw, v, temp);
            wr.force(true);
            // check what we wrote
            RgTags back = RgTags.read(rd);
            if (back == null || Float.isNaN(back.trackGain) || Math.abs(back.trackGain - v.trackGain) > 0.01)
                throw new IOException("tag check after writing failed");
        } finally {
            pfd.close();
        }
    }

    // ------------------------------------------------------- notification

    private static void showNotice(Context ctx, String text, int done, int total, boolean ongoing) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "ReplayGain scan", NotificationManager.IMPORTANCE_LOW));
            int icon = ctx.getResources().getIdentifier(ongoing ? "stat_sys_download" : "stat_sys_download_done", "drawable", "android");
            Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(icon)
                    .setContentTitle(ongoing ? "Adding ReplayGain" : "ReplayGain scan finished")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setOngoing(ongoing)
                    .setOnlyAlertOnce(true);
            if (ongoing) b.setProgress(total, done, false);
            nm.notify(NOTIF_ID, b.build());
        } catch (Throwable e) {
            Log.w(TAG, "notification failed", e);
        }
    }

    // read-only Rw for format checks
    private static RgWriter.Rw readOnly(final FileChannel ch) {
        return new RgWriter.Rw() {
            public long size() throws IOException { return ch.size(); }
            public int read(ByteBuffer b, long p) throws IOException { return ch.read(b, p); }
            public void write(ByteBuffer b, long p) throws IOException { throw new IOException("read only"); }
            public void truncate(long s) throws IOException { throw new IOException("read only"); }
        };
    }

    @SuppressWarnings("unused")
    private static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.US); }
}
