package com.fpmod.dsd;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * Entry point called from the player before it sets a data source.
 * If the track is a DSF/DFF file, it is decoded in software and the player is
 * given PCM instead; otherwise nothing happens and normal playback continues.
 */
public final class DsdSupport {
    private static final String TAG = "DsdSupport";
    public static final String KEY_ENABLED = "fpmod_dsd_software";

    private DsdSupport() { }

    /**
     * @return true if the track was DSD and the decoded source has been set on {@code mp}.
     * @throws IOException if the track is DSD but can't be decoded (reported as a playback error).
     */
    public static boolean trySetDataSource(MediaPlayer mp, Context context, Uri uri) throws IOException {
        if (mp == null || context == null || uri == null) return false;
        try {
            // The player runs in its own process; MODE_MULTI_PROCESS picks up changes made in Settings.
            @SuppressWarnings("deprecation")
            android.content.SharedPreferences p = context.getSharedPreferences(context.getPackageName() + "_preferences",
                    android.content.Context.MODE_MULTI_PROCESS);
            if (!p.getBoolean(KEY_ENABLED, true)) return false;   // let the phone's own decoder handle DSD
        } catch (Throwable ignored) { }
        String scheme = uri.getScheme();
        if (scheme != null && !scheme.equals("content") && !scheme.equals("file")) return false;

        ParcelFileDescriptor pfd;
        try {
            pfd = context.getContentResolver().openFileDescriptor(uri, "r");
        } catch (Exception e) {
            return false;   // let the normal path report it
        }
        if (pfd == null) return false;

        FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
        boolean handedOver = false;
        try {
            FileChannel ch = in.getChannel();
            if (!DsdWav.looksLikeDsd(ch)) return false;
            DsdWav wav = new DsdWav(ch);
            Log.i(TAG, "Software DSD: " + wav.channels() + " ch, " + wav.dsdRate() + " Hz -> "
                    + DsdWav.OUT_RATE + " Hz 24-bit");
            mp.setDataSource(new DsdMediaDataSource(pfd, in, wav));
            handedOver = true;
            return true;
        } catch (IOException e) {
            Log.w(TAG, "DSD decode failed: " + e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            Log.w(TAG, "DSD check failed", e);
            return false;
        } finally {
            if (!handedOver) {
                try { in.close(); } catch (IOException ignored) { }
                try { pfd.close(); } catch (IOException ignored) { }
            }
        }
    }
}
