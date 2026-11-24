package com.fpmod.dsd;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Applies ReplayGain per MediaPlayer by scaling its volume. The app's own volume
 * changes (fades, ducking) go through {@link #setVolume} so both are combined.
 */
public final class ReplayGain {
    private static final String TAG = "ReplayGain";

    public static final String KEY_MODE = "fpmod_rg_mode";          // off | track | album
    public static final String KEY_PREAMP = "fpmod_rg_preamp";      // dB, as string
    public static final String KEY_UNTAGGED = "fpmod_rg_untagged";  // dB, as string
    public static final String KEY_NOCLIP = "fpmod_rg_noclip";      // boolean

    private static final class State { float factor = 1f, l = 1f, r = 1f; }
    private static final Map<MediaPlayer, State> STATES = new WeakHashMap<>();

    private ReplayGain() { }

    private static synchronized State state(MediaPlayer mp) {
        State s = STATES.get(mp);
        if (s == null) { s = new State(); STATES.put(mp, s); }
        return s;
    }

    /** Replaces MediaPlayer.setVolume calls in the app. */
    public static void setVolume(MediaPlayer mp, float l, float r) {
        State s = state(mp);
        s.l = l; s.r = r;
        mp.setVolume(l * s.factor, r * s.factor);
    }

    /** Called after a data source is set; works out and applies this track's gain. */
    public static void apply(MediaPlayer mp, Context context, Uri uri) {
        if (mp == null || context == null) return;
        float factor = 1f;
        try {
            factor = factorFor(context, uri);
        } catch (Throwable e) {
            Log.w(TAG, "ReplayGain lookup failed", e);
        }
        State s = state(mp);
        s.factor = factor;
        try {
            mp.setVolume(s.l * factor, s.r * factor);
        } catch (Throwable e) {
            Log.w(TAG, "setVolume failed", e);
        }
    }

    @SuppressWarnings("deprecation")
    static float factorFor(Context context, Uri uri) {
        // The player runs in its own process; MODE_MULTI_PROCESS re-reads changes made in Settings.
        SharedPreferences p = context.getSharedPreferences(context.getPackageName() + "_preferences",
                Context.MODE_MULTI_PROCESS);
        String mode = p.getString(KEY_MODE, "off");
        if (mode == null || mode.equals("off") || uri == null) return 1f;
        float preamp = parse(p.getString(KEY_PREAMP, "0"));
        float untagged = parse(p.getString(KEY_UNTAGGED, "0"));
        boolean noClip = true;   // clipping prevention is always on

        String scheme = uri.getScheme();
        if (scheme != null && !scheme.equals("content") && !scheme.equals("file")) return dbToFactor(untagged, Float.NaN, noClip);

        RgTags t = null;
        ParcelFileDescriptor pfd = null;
        FileInputStream in = null;
        try {
            pfd = context.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd != null) {
                in = new FileInputStream(pfd.getFileDescriptor());
                t = RgTags.read(in.getChannel());
            }
        } catch (Exception e) {
            Log.w(TAG, "Can't read tags: " + e.getMessage());
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
        }
        return compute(t, mode.equals("album"), preamp, untagged, noClip);
    }

    /** Pure logic, also used by the tests. */
    public static float compute(RgTags t, boolean album, float preamp, float untagged, boolean noClip) {
        float gain = Float.NaN, peak = Float.NaN;
        if (t != null) {
            if (album) {
                gain = !Float.isNaN(t.albumGain) ? t.albumGain : t.trackGain;
                peak = !Float.isNaN(t.albumGain) ? t.albumPeak : t.trackPeak;
            } else {
                gain = !Float.isNaN(t.trackGain) ? t.trackGain : t.albumGain;
                peak = !Float.isNaN(t.trackGain) ? t.trackPeak : t.albumPeak;
            }
        }
        if (Float.isNaN(gain)) return dbToFactor(untagged, Float.NaN, noClip);
        Log.i(TAG, "gain " + gain + " dB, peak " + peak + ", preamp " + preamp + " (" + (t != null ? t.source : "") + ")");
        return dbToFactor(gain + preamp, peak, noClip);
    }

    private static float dbToFactor(float db, float peak, boolean noClip) {
        float f = (float) Math.pow(10.0, db / 20.0);
        if (noClip && !Float.isNaN(peak) && peak > 0f) f = Math.min(f, 1f / peak);
        // MediaPlayer volume can only attenuate.
        if (f > 1f) f = 1f;
        if (f < 0f || Float.isNaN(f)) f = 1f;
        return f;
    }

    private static float parse(String s) {
        float v = RgTags.num(s == null ? "" : s);
        return Float.isNaN(v) ? 0f : v;
    }
}
