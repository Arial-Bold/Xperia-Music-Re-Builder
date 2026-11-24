package com.fpmod.dsd;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;

/**
 * Reads tags, cover art and duration from DSF and DFF files.
 * DSF: ID3v2 tag at the metadata pointer. DFF: "ID3 " chunk (common) and DIIN title/artist.
 * Pure Java.
 */
public final class DsdInfo {
    public String title, artist, album, albumArtist, genre, year;
    public int track, disc;
    public byte[] picture;            // front cover if present, else first picture
    public long durationMs;

    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset UTF16 = Charset.forName("UTF-16");
    private static final Charset UTF16BE = Charset.forName("UTF-16BE");
    private static final int MAX_TAG = 32 * 1024 * 1024;

    private final FileChannel ch;
    private final long len;
    private final boolean wantPicture;
    private int pictureType = -1;

    private DsdInfo(FileChannel ch, boolean wantPicture) throws IOException {
        this.ch = ch; this.len = ch.size(); this.wantPicture = wantPicture;
    }

    public static boolean isDsdName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.US);
        return n.endsWith(".dsf") || n.endsWith(".dff");
    }

    /** Returns null if the file isn't DSD or can't be read. */
    public static DsdInfo read(FileChannel ch, boolean wantPicture) {
        try {
            DsdInfo d = new DsdInfo(ch, wantPicture);
            return d.parse() ? d : null;
        } catch (Throwable e) {
            return null;
        }
    }

    public boolean hasText() {
        return title != null || artist != null || album != null;
    }

    private boolean parse() throws IOException {
        byte[] h = bytes(0, 32);
        if (h.length < 16) return false;
        String id = ascii(h, 0, 4);
        if (id.equals("DSD ")) {
            try { durationMs = new DsdWav(ch).frames() * 1000L / DsdWav.OUT_RATE; } catch (IOException ignored) { }
            if (h.length >= 28) {
                long meta = le64(h, 20);
                if (meta > 0 && meta < len) id3(meta);
            }
            return true;
        }
        if (id.equals("FRM8") && ascii(h, 12, 4).equals("DSD ")) {
            try { durationMs = new DsdWav(ch).frames() * 1000L / DsdWav.OUT_RATE; } catch (IOException ignored) { }
            long pos = 16;
            for (int guard = 0; guard < 100000 && pos + 12 <= len; guard++) {
                byte[] c = bytes(pos, 12);
                String cid = ascii(c, 0, 4);
                long size = be64(c, 4);
                if (size < 0) break;
                if (cid.equals("ID3 ")) id3(pos + 12);
                else if (cid.equals("DIIN")) diin(pos + 12, pos + 12 + size);
                pos += 12 + size + (size & 1);
            }
            return true;
        }
        return false;
    }

    // DFF edited master info: DITI (title), DIAR (artist)
    private void diin(long s, long e) throws IOException {
        long p = s;
        while (p + 12 <= e) {
            byte[] c = bytes(p, 12);
            String id = ascii(c, 0, 4);
            long size = be64(c, 4);
            if (size < 0 || p + 12 + size > e) break;
            if ((id.equals("DITI") || id.equals("DIAR")) && size >= 4) {
                byte[] b = bytes(p + 12, (int) Math.min(size, 4096));
                int n = be32(b, 0);
                if (n > 0 && 4 + n <= b.length) {
                    String v = clean(new String(b, 4, n, LATIN1));
                    if (id.equals("DITI") && title == null) title = v;
                    if (id.equals("DIAR") && artist == null) artist = v;
                }
            }
            p += 12 + size + (size & 1);
        }
    }

    private void id3(long pos) throws IOException {
        byte[] h = bytes(pos, 10);
        if (h.length < 10 || !ascii(h, 0, 3).equals("ID3")) return;
        int ver = h[3] & 0xff, flags = h[5] & 0xff;
        int size = syncsafe(h, 6);
        if (ver < 2 || ver > 4 || size <= 0) return;
        byte[] b = bytes(pos + 10, Math.min(size, MAX_TAG));
        if ((flags & 0x80) != 0 && ver < 4) b = unsync(b, 0, b.length);
        int p = 0;
        if ((flags & 0x40) != 0 && ver >= 3) p += ver == 4 ? syncsafe(b, 0) : be32(b, 0) + 4;
        int idLen = ver == 2 ? 3 : 4, hdr = ver == 2 ? 6 : 10;
        while (p + hdr <= b.length) {
            String id = ascii(b, p, idLen);
            if (id.isEmpty() || id.charAt(0) == 0) break;
            int fs = ver == 2 ? (((b[p + 3] & 0xff) << 16) | ((b[p + 4] & 0xff) << 8) | (b[p + 5] & 0xff))
                    : ver == 4 ? syncsafe(b, p + 4) : be32(b, p + 4);
            int fflags = ver == 2 ? 0 : (((b[p + 8] & 0xff) << 8) | (b[p + 9] & 0xff));
            int data = p + hdr;
            if (fs <= 0 || data + fs > b.length) break;
            byte[] f = java.util.Arrays.copyOfRange(b, data, data + fs);
            boolean skip = false;
            if (ver == 4) {
                if ((fflags & 0x000C) != 0) skip = true;
                int dli = (fflags & 0x0001) != 0 ? 4 : 0;
                if (!skip && (fflags & 0x0002) != 0) f = unsync(f, dli, f.length - dli);
                else if (!skip && dli > 0) f = java.util.Arrays.copyOfRange(f, dli, f.length);
            }
            if (!skip) frame(id, f, ver);
            p = data + fs;
        }
    }

    private void frame(String id, byte[] f, int ver) {
        if (id.equals("APIC") || id.equals("PIC")) { if (wantPicture) apic(f, ver == 2); return; }
        if (id.charAt(0) != 'T' || id.equals("TXXX") || id.equals("TXX") || f.length < 2) return;
        String v = text(f);
        if (v == null || v.isEmpty()) return;
        if (id.equals("TIT2") || id.equals("TT2")) title = v;
        else if (id.equals("TPE1") || id.equals("TP1")) artist = v;
        else if (id.equals("TALB") || id.equals("TAL")) album = v;
        else if (id.equals("TPE2") || id.equals("TP2")) albumArtist = v;
        else if (id.equals("TCON") || id.equals("TCO")) genre = genre(v);
        else if (id.equals("TRCK") || id.equals("TRK")) track = leadingInt(v);
        else if (id.equals("TPOS") || id.equals("TPA")) disc = leadingInt(v);
        else if (id.equals("TDRC") || id.equals("TYER") || id.equals("TYE") || id.equals("TDOR")) {
            if (year == null || id.equals("TDRC") || id.equals("TYER") || id.equals("TYE")) {
                int y = leadingInt(v);
                if (y > 0) year = String.valueOf(y);
            }
        }
    }

    private void apic(byte[] f, boolean v22) {
        int enc = f[0] & 0xff, p = 1;
        if (v22) p += 3;
        else { while (p < f.length && f[p] != 0) p++; p++; }   // MIME type
        if (p >= f.length) return;
        int type = f[p++] & 0xff;
        // description, terminated according to encoding
        if (enc == 1 || enc == 2) { while (p + 1 < f.length && !(f[p] == 0 && f[p + 1] == 0)) p += 2; p += 2; }
        else { while (p < f.length && f[p] != 0) p++; p++; }
        if (p >= f.length) return;
        boolean better = picture == null || (type == 3 && pictureType != 3);
        if (better) { picture = java.util.Arrays.copyOfRange(f, p, f.length); pictureType = type; }
    }

    private static String text(byte[] f) {
        int enc = f[0] & 0xff;
        String s;
        switch (enc) {
            case 1: s = new String(f, 1, f.length - 1, UTF16); break;
            case 2: s = new String(f, 1, f.length - 1, UTF16BE); break;
            case 3: s = new String(f, 1, f.length - 1, UTF8); break;
            default: s = new String(f, 1, f.length - 1, LATIN1);
        }
        // multiple values are NUL-separated; keep them joined with " / "
        String[] parts = s.split("\u0000");
        StringBuilder b = new StringBuilder();
        for (String part : parts) {
            String c = clean(part);
            if (c.isEmpty()) continue;
            if (b.length() > 0) b.append(" / ");
            b.append(c);
        }
        return b.toString();
    }

    private static String clean(String s) {
        if (s.startsWith("﻿")) s = s.substring(1);
        return s.trim();
    }

    private static String genre(String v) {
        // "(17)" or "17" -> keep as-is unless it's only a number reference, then drop the brackets
        if (v.matches("\\(\\d+\\).+")) return v.substring(v.indexOf(')') + 1).trim();
        return v;
    }

    private static int leadingInt(String v) {
        int n = 0, i = 0;
        while (i < v.length() && Character.isWhitespace(v.charAt(i))) i++;
        boolean any = false;
        while (i < v.length() && Character.isDigit(v.charAt(i)) && n < 100000) { n = n * 10 + (v.charAt(i) - '0'); i++; any = true; }
        return any ? n : 0;
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

    private static String ascii(byte[] b, int o, int n) { return o + n > b.length ? "" : new String(b, o, n, LATIN1); }
    private static int syncsafe(byte[] b, int o) { return ((b[o] & 0x7f) << 21) | ((b[o + 1] & 0x7f) << 14) | ((b[o + 2] & 0x7f) << 7) | (b[o + 3] & 0x7f); }
    private static int be32(byte[] b, int o) { return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff); }
    private static long be64(byte[] b, int o) { return ((long) be32(b, o) << 32) | (be32(b, o + 4) & 0xffffffffL); }
    private static long le64(byte[] b, int o) {
        return ByteBuffer.wrap(b, o, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }
}
