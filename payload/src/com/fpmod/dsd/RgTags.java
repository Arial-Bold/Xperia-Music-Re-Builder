package com.fpmod.dsd;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * Reads ReplayGain values from an audio file. Pure Java, no Android dependencies.
 *
 * Supported: FLAC and Ogg Vorbis comments, Opus R128 tags, ID3v2 TXXX frames
 * (MP3, and ID3 chunks inside WAV, AIFF, DSF and DFF), MP4/M4A iTunes freeform
 * atoms, and APEv2 tags at the end of the file.
 */
public final class RgTags {
    public static final float NONE = Float.NaN;

    public float trackGain = NONE, albumGain = NONE, trackPeak = NONE, albumPeak = NONE;
    private boolean insane;   // some ReplayGain value was present but impossible
    public String source = "none";

    public boolean hasAny() {
        return !Float.isNaN(trackGain) || !Float.isNaN(albumGain);
    }

    @Override public String toString() {
        return String.format(Locale.US, "%s track=%s album=%s tpeak=%s apeak=%s",
                source, trackGain, albumGain, trackPeak, albumPeak);
    }

    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset UTF16 = Charset.forName("UTF-16");
    private static final Charset UTF16BE = Charset.forName("UTF-16BE");
    private static final int MAX_TAG = 8 * 1024 * 1024;   // ignore absurd tag sizes

    private final FileChannel ch;
    private final long len;

    private RgTags(FileChannel ch) throws IOException {
        this.ch = ch;
        this.len = ch.size();
    }

    public static RgTags read(FileChannel ch) {
        RgTags t;
        try {
            t = new RgTags(ch);
            t.parse();
        } catch (Throwable e) {
            t = null;
        }
        if (t == null) { try { t = new RgTags(ch); } catch (IOException e) { return null; } }
        return t;
    }

    private void parse() throws IOException {
        byte[] h = bytes(0, 32);
        if (h.length < 12) return;
        String m4 = ascii(h, 0, 4);
        long start = 0;
        if (m4.startsWith("ID3")) {
            long end = id3At(0);
            if (hasAny()) { source = "id3v2"; return; }
            start = end;                     // FLAC with an ID3 prefix
            h = bytes(start, 32);
            if (h.length < 12) { apeFallback(); return; }
            m4 = ascii(h, 0, 4);
        }
        if (m4.equals("fLaC")) { flac(start + 4); source = "flac"; }
        else if (m4.equals("OggS")) { ogg(); }
        else if (ascii(h, 4, 4).equals("ftyp")) { mp4(); source = "mp4"; }
        else if (m4.equals("RIFF") && ascii(h, 8, 4).equals("WAVE")) { iffChunks(12, false); source = "wav"; }
        else if (m4.equals("FORM") && (ascii(h, 8, 4).startsWith("AIF"))) { iffChunks(12, true); source = "aiff"; }
        else if (m4.equals("DSD ")) {
            long meta = le64(h, 20);
            if (meta > 0 && meta < len) id3At(meta);
            source = "dsf";
        } else if (m4.equals("FRM8")) { dffChunks(); source = "dff"; }
        if (!hasAny()) apeFallback();
    }

    // ------------------------------------------------------------------ values

    /** False if the file carries impossible ReplayGain values (they are ignored for playback). */
    public boolean saneRaw() { return !insane; }

    private static boolean saneGain(float g) { return !Float.isNaN(g) && !Float.isInfinite(g) && g >= -40f && g <= 40f; }
    private static boolean sanePeak(float p) { return !Float.isNaN(p) && !Float.isInfinite(p) && p > 0f && p <= 10f; }

    private void put(String key, String value) {
        if (key == null || value == null) return;
        String k = key.trim().toUpperCase(Locale.US);
        if (k.startsWith("REPLAYGAIN_") || k.startsWith("R128_")) {
            float raw = k.startsWith("R128_") ? q78(value) : num(value);
            boolean isPeak = k.endsWith("_PEAK");
            if (k.equals("REPLAYGAIN_REFERENCE_LOUDNESS")) return;
            if (Float.isNaN(raw) || (isPeak ? !sanePeak(raw) : !saneGain(k.startsWith("R128_") ? raw + 5f : raw))) {
                insane = true;      // e.g. "Infinity", absurd peaks: ignore rather than mute the track
                return;
            }
        }
        float v;
        if (k.equals("REPLAYGAIN_TRACK_GAIN")) { v = num(value); if (!Float.isNaN(v)) trackGain = v; }
        else if (k.equals("REPLAYGAIN_ALBUM_GAIN")) { v = num(value); if (!Float.isNaN(v)) albumGain = v; }
        else if (k.equals("REPLAYGAIN_TRACK_PEAK")) { v = num(value); if (!Float.isNaN(v)) trackPeak = v; }
        else if (k.equals("REPLAYGAIN_ALBUM_PEAK")) { v = num(value); if (!Float.isNaN(v)) albumPeak = v; }
        // Opus: Q7.8 integers relative to -23 LUFS; ReplayGain uses about -18 LUFS, so add 5 dB.
        else if (k.equals("R128_TRACK_GAIN") && Float.isNaN(trackGain)) { v = q78(value); if (!Float.isNaN(v)) trackGain = v + 5f; }
        else if (k.equals("R128_ALBUM_GAIN") && Float.isNaN(albumGain)) { v = q78(value); if (!Float.isNaN(v)) albumGain = v + 5f; }
    }

    private static final java.util.regex.Pattern NUM =
            java.util.regex.Pattern.compile("[+-]?[0-9]+(?:[.,][0-9]+)?|[+-]?[.,][0-9]+");

    static float num(String s) {
        if (s == null) return NONE;
        java.util.regex.Matcher m = NUM.matcher(s);
        if (!m.find()) return NONE;
        try {
            return Float.parseFloat(m.group().replace(',', '.'));
        } catch (NumberFormatException e) {
            return NONE;
        }
    }

    private static float q78(String s) {
        try { return Integer.parseInt(s.trim()) / 256f; } catch (NumberFormatException e) { return NONE; }
    }

    // -------------------------------------------------------------------- FLAC

    private void flac(long pos) throws IOException {
        for (int guard = 0; guard < 128 && pos + 4 <= len; guard++) {
            byte[] bh = bytes(pos, 4);
            int type = bh[0] & 0x7f;
            boolean last = (bh[0] & 0x80) != 0;
            int size = ((bh[1] & 0xff) << 16) | ((bh[2] & 0xff) << 8) | (bh[3] & 0xff);
            if (type == 4) { vorbisComments(bytes(pos + 4, Math.min(size, MAX_TAG)), 0); return; }
            pos += 4 + size;
            if (last) return;
        }
    }

    private void vorbisComments(byte[] b, int off) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(off);
        int vendor = bb.getInt();
        if (vendor < 0 || vendor > bb.remaining()) return;
        bb.position(bb.position() + vendor);
        int count = bb.getInt();
        for (int i = 0; i < count && bb.remaining() >= 4; i++) {
            int l = bb.getInt();
            if (l < 0 || l > bb.remaining()) return;
            String c = new String(b, bb.position(), l, UTF8);
            bb.position(bb.position() + l);
            int eq = c.indexOf('=');
            if (eq > 0) put(c.substring(0, eq), c.substring(eq + 1));
        }
    }

    // --------------------------------------------------------------------- Ogg

    private void ogg() throws IOException {
        // Reassemble the first three packets from the first pages.
        java.io.ByteArrayOutputStream cur = new java.io.ByteArrayOutputStream();
        byte[][] packets = new byte[3][];
        int np = 0;
        long pos = 0;
        while (np < 3 && pos + 27 <= len && pos < 4 * 1024 * 1024) {
            byte[] ph = bytes(pos, 27);
            if (!ascii(ph, 0, 4).equals("OggS")) break;
            int segs = ph[26] & 0xff;
            byte[] lace = bytes(pos + 27, segs);
            long dp = pos + 27 + segs;
            int total = 0;
            for (byte l : lace) total += l & 0xff;
            byte[] data = bytes(dp, total);
            int o = 0;
            for (int i = 0; i < segs && np < 3; i++) {
                int l = lace[i] & 0xff;
                cur.write(data, o, Math.min(l, data.length - o));
                o += l;
                if (l < 255) { packets[np++] = cur.toByteArray(); cur.reset(); }
            }
            pos = dp + total;
        }
        if (np < 2) return;
        byte[] p0 = packets[0], p1 = packets[1];
        if (p0.length >= 8 && ascii(p0, 0, 8).equals("OpusHead")) {
            source = "opus";
            if (p1.length >= 8 && ascii(p1, 0, 8).equals("OpusTags")) vorbisComments(p1, 8);
        } else if (p0.length >= 7 && p0[0] == 1 && ascii(p0, 1, 6).equals("vorbis")) {
            source = "vorbis";
            if (p1.length >= 7 && p1[0] == 3 && ascii(p1, 1, 6).equals("vorbis")) vorbisComments(p1, 7);
        } else if (p0.length >= 5 && p0[0] == 0x7f && ascii(p0, 1, 4).equals("FLAC")) {
            source = "oggflac";
            if (p1.length >= 4 && (p1[0] & 0x7f) == 4) vorbisComments(p1, 4);
        }
    }

    // ------------------------------------------------------------------- ID3v2

    /** Parses an ID3v2 tag at pos. Returns the position just after the tag. */
    private long id3At(long pos) throws IOException {
        byte[] h = bytes(pos, 10);
        if (h.length < 10 || !ascii(h, 0, 3).equals("ID3")) return pos;
        int ver = h[3] & 0xff, flags = h[5] & 0xff;
        int size = syncsafe(h, 6);
        long end = pos + 10 + size + ((flags & 0x10) != 0 ? 10 : 0);
        if (ver < 2 || ver > 4 || size <= 0) return end;
        byte[] b = bytes(pos + 10, Math.min(size, MAX_TAG));
        if ((flags & 0x80) != 0 && ver < 4) b = unsync(b, 0, b.length);
        int p = 0;
        if ((flags & 0x40) != 0 && ver >= 3) {          // extended header
            int ext = ver == 4 ? syncsafe(b, 0) : be32(b, 0) + 4;
            p += ext;
        }
        int idLen = ver == 2 ? 3 : 4, hdrLen = ver == 2 ? 6 : 10;
        while (p + hdrLen <= b.length) {
            String id = ascii(b, p, idLen);
            if (id.charAt(0) == 0) break;
            int fs = ver == 2 ? (((b[p + 3] & 0xff) << 16) | ((b[p + 4] & 0xff) << 8) | (b[p + 5] & 0xff))
                    : ver == 4 ? syncsafe(b, p + 4) : be32(b, p + 4);
            int fflags = ver == 2 ? 0 : (((b[p + 8] & 0xff) << 8) | (b[p + 9] & 0xff));
            int data = p + hdrLen;
            if (fs < 0 || data + fs > b.length) break;
            if (id.equals("TXXX") || id.equals("TXX")) {
                byte[] f = java.util.Arrays.copyOfRange(b, data, data + fs);
                if (ver == 4) {
                    int skip = (fflags & 0x0001) != 0 ? 4 : 0;   // data length indicator
                    if ((fflags & 0x000C) != 0) { p = data + fs; continue; }  // compressed/encrypted
                    if ((fflags & 0x0002) != 0) f = unsync(f, skip, f.length - skip); else if (skip > 0) f = java.util.Arrays.copyOfRange(f, skip, f.length);
                }
                txxx(f);
            }
            p = data + fs;
        }
        return end;
    }

    private void txxx(byte[] f) {
        if (f.length < 2) return;
        int enc = f[0] & 0xff;
        String all;
        switch (enc) {
            case 1: all = new String(f, 1, f.length - 1, UTF16); break;
            case 2: all = new String(f, 1, f.length - 1, UTF16BE); break;
            case 3: all = new String(f, 1, f.length - 1, UTF8); break;
            default: all = new String(f, 1, f.length - 1, LATIN1);
        }
        int z = all.indexOf('\0');
        if (z < 0) return;
        String desc = all.substring(0, z);
        String val = all.substring(z + 1);
        if (val.startsWith("﻿")) val = val.substring(1);
        int z2 = val.indexOf('\0');
        if (z2 >= 0) val = val.substring(0, z2);
        put(desc, val);
    }

    private static byte[] unsync(byte[] b, int off, int n) {
        byte[] o = new byte[n];
        int j = 0;
        for (int i = off; i < off + n; i++) {
            o[j++] = b[i];
            if ((b[i] & 0xff) == 0xff && i + 1 < off + n && b[i + 1] == 0) i++;
        }
        return java.util.Arrays.copyOf(o, j);
    }

    // ------------------------------------------------------------ WAV/AIFF/DFF

    private void iffChunks(long pos, boolean bigEndian) throws IOException {
        for (int guard = 0; guard < 4096 && pos + 8 <= len; guard++) {
            byte[] c = bytes(pos, 8);
            String id = ascii(c, 0, 4);
            long size = bigEndian ? (be32(c, 4) & 0xffffffffL) : (le32(c, 4) & 0xffffffffL);
            if (id.equalsIgnoreCase("id3 ")) { id3At(pos + 8); if (hasAny()) return; }
            pos += 8 + size + (size & 1);
        }
    }

    private void dffChunks() throws IOException {
        long pos = 16;
        for (int guard = 0; guard < 4096 && pos + 12 <= len; guard++) {
            byte[] c = bytes(pos, 12);
            String id = ascii(c, 0, 4);
            long size = be64(c, 4);
            if (id.equals("ID3 ")) { id3At(pos + 12); if (hasAny()) return; }
            if (size < 0) return;
            pos += 12 + size + (size & 1);
        }
    }

    // --------------------------------------------------------------------- MP4

    private void mp4() throws IOException {
        long[] moov = findAtom(0, len, "moov");
        if (moov == null) return;
        long[] udta = findAtom(moov[0], moov[1], "udta");
        if (udta == null) return;
        long[] meta = findAtom(udta[0], udta[1], "meta");
        if (meta == null) return;
        long ms = meta[0] + 4;                          // full box: version/flags
        long[] ilst = findAtom(ms, meta[1], "ilst");
        if (ilst == null) {                              // some writers omit the full-box header
            ilst = findAtom(meta[0], meta[1], "ilst");
            if (ilst == null) return;
        }
        long p = ilst[0];
        while (p + 8 <= ilst[1]) {
            byte[] h = bytes(p, 8);
            long size = be32(h, 0) & 0xffffffffL;
            if (size < 8) break;
            if (ascii(h, 4, 4).equals("----")) freeform(p + 8, p + size);
            p += size;
        }
    }

    private void freeform(long s, long e) throws IOException {
        String name = null, value = null;
        long p = s;
        while (p + 8 <= e) {
            byte[] h = bytes(p, 8);
            long size = be32(h, 0) & 0xffffffffL;
            if (size < 8 || p + size > e) break;
            String t = ascii(h, 4, 4);
            int body = (int) Math.min(size - 8, 4096);
            if (t.equals("name") && body >= 4) name = new String(bytes(p + 12, body - 4), UTF8);
            else if (t.equals("data") && body >= 8) value = new String(bytes(p + 16, body - 8), UTF8);
            p += size;
        }
        put(name, value);
    }

    /** Finds a child atom in [s,e). Returns {bodyStart, end} or null. */
    private long[] findAtom(long s, long e, String type) throws IOException {
        long p = s;
        for (int guard = 0; guard < 10000 && p + 8 <= e; guard++) {
            byte[] h = bytes(p, 16);
            if (h.length < 8) return null;
            long size = be32(h, 0) & 0xffffffffL;
            int hdr = 8;
            if (size == 1 && h.length >= 16) { size = be64(h, 8); hdr = 16; }
            else if (size == 0) size = e - p;
            if (size < hdr) return null;
            if (ascii(h, 4, 4).equals(type)) return new long[]{p + hdr, Math.min(p + size, e)};
            p += size;
        }
        return null;
    }

    // ------------------------------------------------------------------- APEv2

    private void apeFallback() throws IOException {
        if (len < 32) return;
        long end = len;
        byte[] v1 = bytes(len - 128, 3);
        if (len >= 128 && ascii(v1, 0, 3).equals("TAG")) end -= 128;
        byte[] f = bytes(end - 32, 32);
        if (!ascii(f, 0, 8).equals("APETAGEX")) return;
        int size = le32(f, 12), count = le32(f, 16);
        if (size < 32 || size > MAX_TAG) return;
        byte[] b = bytes(end - size, size - 32);
        int p = 0;
        for (int i = 0; i < count && p + 8 < b.length; i++) {
            int vl = le32(b, p);
            int fl = le32(b, p + 4);
            int k = p + 8, z = k;
            while (z < b.length && b[z] != 0) z++;
            if (z >= b.length || vl < 0 || z + 1 + vl > b.length) break;
            String key = new String(b, k, z - k, LATIN1);
            if (((fl >> 1) & 3) == 0) put(key, new String(b, z + 1, vl, UTF8));
            p = z + 1 + vl;
        }
        if (hasAny()) source = source.equals("none") ? "ape" : source + "+ape";
    }

    // ----------------------------------------------------------------- helpers

    private byte[] bytes(long pos, int n) throws IOException {
        if (pos < 0 || n <= 0 || pos >= len) return new byte[0];
        n = (int) Math.min(n, len - pos);
        ByteBuffer b = ByteBuffer.allocate(n);
        int total = 0;
        while (b.hasRemaining()) {
            int r = ch.read(b, pos + total);
            if (r < 0) break;
            total += r;
        }
        return total == n ? b.array() : java.util.Arrays.copyOf(b.array(), total);
    }

    private static String ascii(byte[] b, int o, int n) {
        if (o + n > b.length) return "";
        return new String(b, o, n, LATIN1);
    }

    private static int syncsafe(byte[] b, int o) {
        return ((b[o] & 0x7f) << 21) | ((b[o + 1] & 0x7f) << 14) | ((b[o + 2] & 0x7f) << 7) | (b[o + 3] & 0x7f);
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    private static long be64(byte[] b, int o) {
        return ((long) be32(b, o) << 32) | (be32(b, o + 4) & 0xffffffffL);
    }

    private static long le64(byte[] b, int o) {
        return (le32(b, o) & 0xffffffffL) | ((long) le32(b, o + 4) << 32);
    }
}
