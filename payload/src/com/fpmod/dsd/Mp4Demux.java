package com.fpmod.dsd;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Minimal MP4/M4A audio demuxer for the ReplayGain scan: finds the first audio track and lists its
 * frames, so they can go straight to Android's decoder without the phone's own file reader (which on
 * some phones hangs or crawls on certain files). Supports AAC (incl. HE-AAC), ALAC and MP3 in MP4.
 * Pure Java apart from nothing: the decoder setup data is returned as bytes.
 */
final class Mp4Demux {
    String mime;            // audio/mp4a-latm, audio/alac or audio/mpeg
    int sampleRate, channels;
    byte[] csd;             // decoder setup data (AAC AudioSpecificConfig or ALAC config), may be null
    long[] offsets;         // file position of each frame
    int[] sizes;            // size of each frame
    int maxSize;

    private final FileChannel ch;
    private final long fileLen;
    // read-ahead buffer
    private final byte[] buf = new byte[1 << 20];
    private long bufPos = -1;
    private int bufLen;

    private Mp4Demux(FileChannel ch) throws IOException { this.ch = ch; this.fileLen = ch.size(); }

    /** Returns null if the file isn't MP4; throws if it is but can't be read. */
    static Mp4Demux open(FileChannel ch) throws IOException {
        Mp4Demux d = new Mp4Demux(ch);
        byte[] h = d.readAt(0, 8);
        if (h.length < 8 || !(h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p')) return null;
        d.parse();
        return d;
    }

    private void parse() throws IOException {
        // top level: find moov
        long p = 0, moovPos = -1, moovSize = 0;
        while (p + 8 <= fileLen) {
            byte[] h = readAt(p, 16);
            long size = u32(h, 0);
            int hdr = 8;
            if (size == 1) { size = s64(h, 8); hdr = 16; } else if (size == 0) size = fileLen - p;
            if (size < hdr) throw new IOException("MP4: bad box");
            if (type(h, 4).equals("moov")) { moovPos = p + hdr; moovSize = size - hdr; break; }
            p += size;
        }
        if (moovPos < 0) throw new IOException("MP4: no moov");
        if (moovSize > 64L * 1024 * 1024) throw new IOException("MP4: header too large");
        byte[] m = readAt(moovPos, (int) moovSize);
        if (m.length != moovSize) throw new IOException("MP4: truncated header");

        for (int[] trak : children(m, 0, m.length)) {
            if (!type(m, trak[0] + 4).equals("trak")) continue;
            int[] mdia = child(m, trak, "mdia");
            if (mdia == null) continue;
            int[] hdlr = child(m, mdia, "hdlr");
            if (hdlr == null || !type(m, hdlr[1] + 8).equals("soun")) continue;
            int[] stbl = child(m, child(m, mdia, "minf"), "stbl");
            if (stbl == null) continue;
            readTrack(m, stbl);
            return;
        }
        throw new IOException("MP4: no audio track");
    }

    private void readTrack(byte[] m, int[] stbl) throws IOException {
        int[] stsd = child(m, stbl, "stsd");
        if (stsd == null) throw new IOException("MP4: no sample description");
        int e = stsd[1] + 8;                                    // first entry (after version/flags + count)
        if (e + 36 > stsd[2]) throw new IOException("MP4: bad sample description");
        int entrySize = (int) u32(m, e);
        String fmt = type(m, e + 4);
        int entryEnd = e + entrySize;
        if (fmt.equals("enca") || fmt.equals("drms")) throw new IOException("protected (DRM) file");
        int body = e + 8;
        int version = u16(m, body + 8);
        channels = u16(m, body + 16);
        sampleRate = (int) (u32(m, body + 24) >>> 16);
        int kids = body + 28 + (version == 1 ? 16 : version == 2 ? 36 : 0);
        if (version == 2) {
            sampleRate = (int) Math.round(Double.longBitsToDouble(s64(m, body + 32)));
            channels = (int) u32(m, body + 40);
        }
        if (fmt.equals("mp4a")) {
            int[] esds = find(m, kids, entryEnd, "esds");
            if (esds == null) throw new IOException("MP4: AAC without esds");
            esds(m, esds[1] + 4, esds[2]);
        } else if (fmt.equals("alac")) {
            int[] alac = find(m, kids, entryEnd, "alac");
            if (alac == null || alac[2] - alac[1] < 28) throw new IOException("MP4: ALAC without config");
            csd = java.util.Arrays.copyOfRange(m, alac[1] + 4, alac[1] + 28);   // ALACSpecificConfig
            mime = "audio/alac";
            channels = csd[9] & 0xff;
            sampleRate = (int) u32(csd, 20);
        } else if (fmt.equals(".mp3") || fmt.equals("mp3 ")) {
            mime = "audio/mpeg";
        } else {
            throw new IOException("MP4: unsupported audio type " + fmt.trim());
        }
        if (channels <= 0 || sampleRate <= 0) throw new IOException("MP4: bad audio format");

        // frame table
        int[] stsz = child(m, stbl, "stsz"), stsc = child(m, stbl, "stsc");
        int[] stco = child(m, stbl, "stco"), co64 = child(m, stbl, "co64");
        if (stsz == null || stsc == null || (stco == null && co64 == null)) throw new IOException("MP4: no frame table");
        int fixed = (int) u32(m, stsz[1] + 4);
        int count = (int) u32(m, stsz[1] + 8);
        if (count < 0 || count > 50_000_000) throw new IOException("MP4: bad frame count");
        sizes = new int[count];
        for (int i = 0; i < count; i++) {
            sizes[i] = fixed != 0 ? fixed : (int) u32(m, stsz[1] + 12 + i * 4);
            if (sizes[i] > maxSize) maxSize = sizes[i];
        }
        boolean big = co64 != null;
        int[] co = big ? co64 : stco;
        int chunks = (int) u32(m, co[1] + 4);
        long[] chunkOff = new long[chunks];
        for (int i = 0; i < chunks; i++) chunkOff[i] = big ? s64(m, co[1] + 8 + i * 8) : u32(m, co[1] + 8 + i * 4);
        int entries = (int) u32(m, stsc[1] + 4);
        offsets = new long[count];
        int sample = 0;
        for (int i = 0; i < entries && sample < count; i++) {
            int b = stsc[1] + 8 + i * 12;
            int first = (int) u32(m, b) - 1, per = (int) u32(m, b + 4);
            int next = i + 1 < entries ? (int) u32(m, b + 12) - 1 : chunks;
            for (int c = first; c < next && c < chunks && sample < count; c++) {
                long off = chunkOff[c];
                for (int k = 0; k < per && sample < count; k++) { offsets[sample] = off; off += sizes[sample]; sample++; }
            }
        }
        if (sample != count) throw new IOException("MP4: frame table doesn't add up");
        for (int i = 0; i < count; i++) if (offsets[i] < 0 || offsets[i] + sizes[i] > fileLen) throw new IOException("MP4: frame outside the file");
    }

    /** ES descriptor -> decoder config -> decoder specific info. */
    private void esds(byte[] m, int p, int end) throws IOException {
        while (p < end) {
            int tag = m[p++] & 0xff;
            int len = 0;
            for (int i = 0; i < 4 && p < end; i++) { int b = m[p++] & 0xff; len = (len << 7) | (b & 0x7f); if ((b & 0x80) == 0) break; }
            if (tag == 0x03) {
                int flags = m[p + 2] & 0xff;
                p += 3;
                if ((flags & 0x80) != 0) p += 2;
                if ((flags & 0x40) != 0) p += 1 + (m[p] & 0xff);
                if ((flags & 0x20) != 0) p += 2;
                continue;                                   // descend
            }
            if (tag == 0x04) {
                int object = m[p] & 0xff;
                if (object == 0x40 || object == 0x66 || object == 0x67 || object == 0x68) mime = "audio/mp4a-latm";
                else if (object == 0x69 || object == 0x6B) mime = "audio/mpeg";
                else throw new IOException("MP4: unsupported audio codec 0x" + Integer.toHexString(object));
                p += 13;
                continue;                                   // descend
            }
            if (tag == 0x05) {
                if (p + len > end) throw new IOException("MP4: bad decoder config");
                csd = java.util.Arrays.copyOfRange(m, p, p + len);
                return;
            }
            p += len;
        }
        if (mime == null) throw new IOException("MP4: no decoder config");
        if (mime.equals("audio/mp4a-latm") && csd == null) throw new IOException("MP4: AAC without decoder config");
    }

    /** Copies frame i into dst (cleared first). Reads ahead in large blocks. */
    int readFrame(int i, ByteBuffer dst) throws IOException {
        int n = sizes[i];
        long off = offsets[i];
        dst.clear();
        if (n > dst.remaining()) throw new IOException("MP4: frame larger than decoder buffer");
        if (n > buf.length) { dst.put(readAt(off, n)); return n; }
        if (bufPos < 0 || off < bufPos || off + n > bufPos + bufLen) {
            bufPos = off;
            ByteBuffer b = ByteBuffer.wrap(buf);
            while (b.hasRemaining()) { int r = ch.read(b, off + b.position()); if (r <= 0) break; }
            bufLen = b.position();
            if (bufLen < n) throw new IOException("MP4: frame cut short");
        }
        dst.put(buf, (int) (off - bufPos), n);
        return n;
    }

    // ------------------------------------------------------------------ boxes

    /** Children of a box region: each is {start, bodyStart, end}. */
    private static java.util.List<int[]> children(byte[] m, int s, int e) throws IOException {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        int p = s;
        while (p + 8 <= e) {
            long size = u32(m, p);
            int hdr = 8;
            if (size == 1) { size = s64(m, p + 8); hdr = 16; } else if (size == 0) size = e - p;
            if (size < hdr || p + size > e) break;
            out.add(new int[]{p, p + hdr, (int) (p + size)});
            p += (int) size;
        }
        return out;
    }

    private static int[] child(byte[] m, int[] parent, String t) throws IOException {
        if (parent == null) return null;
        for (int[] c : children(m, parent[1], parent[2])) if (type(m, c[0] + 4).equals(t)) return c;
        return null;
    }

    /** Depth-first search for a box type (sample entries can nest esds inside a QuickTime "wave"). */
    private static int[] find(byte[] m, int s, int e, String t) throws IOException {
        for (int[] c : children(m, s, e)) {
            String ty = type(m, c[0] + 4);
            if (ty.equals(t)) return c;
            if (ty.equals("wave")) { int[] r = find(m, c[1], c[2], t); if (r != null) return r; }
        }
        return null;
    }

    private byte[] readAt(long pos, int n) throws IOException {
        if (pos >= fileLen) return new byte[0];
        n = (int) Math.min(n, fileLen - pos);
        ByteBuffer b = ByteBuffer.allocate(n);
        while (b.hasRemaining()) { int r = ch.read(b, pos + b.position()); if (r <= 0) break; }
        return java.util.Arrays.copyOf(b.array(), b.position());
    }

    private static String type(byte[] b, int o) { return o + 4 <= b.length ? new String(b, o, 4, java.nio.charset.StandardCharsets.ISO_8859_1) : ""; }
    private static int u16(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }
    private static long u32(byte[] b, int o) { return ((b[o] & 0xffL) << 24) | ((b[o + 1] & 0xffL) << 16) | ((b[o + 2] & 0xffL) << 8) | (b[o + 3] & 0xffL); }
    private static long s64(byte[] b, int o) { return (u32(b, o) << 32) | u32(b, o + 4); }
}
