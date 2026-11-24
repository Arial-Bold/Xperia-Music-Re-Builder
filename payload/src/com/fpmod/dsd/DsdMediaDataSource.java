package com.fpmod.dsd;

import android.media.MediaDataSource;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;

/** Feeds a DSD file to MediaPlayer as decoded PCM (a virtual WAV file). */
final class DsdMediaDataSource extends MediaDataSource {
    private final ParcelFileDescriptor pfd;
    private final FileInputStream in;
    private final DsdWav wav;

    DsdMediaDataSource(ParcelFileDescriptor pfd, FileInputStream in, DsdWav wav) {
        this.pfd = pfd;
        this.in = in;
        this.wav = wav;
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        return wav.readAt(position, buffer, offset, size);
    }

    @Override
    public long getSize() {
        return wav.size();
    }

    @Override
    public void close() throws IOException {
        try { in.close(); } catch (IOException ignored) { }
        pfd.close();
    }
}
