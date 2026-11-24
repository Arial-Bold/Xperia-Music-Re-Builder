package com.fpmod.dsd;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes ReplayGain into M4A/MP4 files as iTunes freeform tags
 * (moov/udta/meta/ilst/"----", mean "com.apple.iTunes", name "replaygain_track_gain" ...),
 * the way foobar2000, Mp3tag and rsgain store them.
 *
 * The tags live in the moov box. If it can be rewritten where it is (it stays the same size,
 * shrinks, grows into a following "free" box, or is the last thing in the file) only moov is
 * written. Otherwise the file is rebuilt with moov resized and every chunk offset (stco/co64)
 * that points past it shifted, plus some padding so the next change fits in place.
 */
final class Mp4 {
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_MOOV = 64 * 1024 * 1024;
    private static final int PADDING = 4096;

    private Mp4() { }

    /** A box: where it starts, its total size, header length and type. */
    private static final class Box {
        final long pos, size; final int hdr; final String type;
        Box(long pos, long size, int hdr, String type) { this.pos = pos; this.size = size; this.hdr = hdr; this.type = type; }
        long end() { return pos + size; }
    }

    static String problem(RgWriter.Rw f) throws IOException {
        try {
            List<Box> top = topLevel(f);
            Box moov = find(top, "moov");
            if (moov == null) return "M4A/MP4 file without a moov box";
            if (moov.size > MAX_MOOV) return "M4A/MP4 header is unusually large";
            if (moov.hdr != 8) return "unusual M4A/MP4 layout";
            return null;
        } catch (IOException e) {
            return "damaged M4A/MP4 file";
        }
    }

    static void write(RgWriter.Rw f, List<String[]> pairs, RgWriter.Temp temp) throws IOException {
        String why = problem(f);
        if (why != null) throw new IOException(why);
        long fileLen = f.size();
        List<Box> top = topLevel(f);
        Box moovBox = find(top, "moov");
        byte[] moov = RgWriter.read(f, moovBox.pos, (int) moovBox.size);
        if (moov.length != moovBox.size) throw new IOException("MP4: truncated moov");

        byte[] newMoov = withTags(moov, pairs);
        long delta = newMoov.length - moov.length;

        int idx = top.indexOf(moovBox);
        // free/skip boxes right after moov count as one block of spare room
        long freeAfter = 0;
        for (int i = idx + 1; i < top.size(); i++) {
            Box x = top.get(i);
            if (!(x.type.equals("free") || x.type.equals("skip"))) break;
            freeAfter += x.size;
        }
        boolean onlyFreeAfter = true;
        for (int i = idx + 1; i < top.size(); i++) {
            String t = top.get(i).type;
            if (!t.equals("free") && !t.equals("skip")) { onlyFreeAfter = false; break; }
        }

        if (delta == 0) { f.write(ByteBuffer.wrap(newMoov), moovBox.pos); return; }
        if (delta <= -8 && freeAfter == 0) {                   // shrank: leave the gap as a free box
            f.write(ByteBuffer.wrap(concat(newMoov, free((int) -delta))), moovBox.pos);
            return;
        }
        if (freeAfter > 0) {                                   // take the room from the following free boxes
            long room = freeAfter - delta;
            if (room == 0) { f.write(ByteBuffer.wrap(newMoov), moovBox.pos); return; }
            if (room >= 8 && room <= Integer.MAX_VALUE) {
                f.write(ByteBuffer.wrap(concat(newMoov, free((int) room))), moovBox.pos);
                return;
            }
        }
        if (onlyFreeAfter) {                                   // moov is at the end: just rewrite the tail
            f.write(ByteBuffer.wrap(newMoov), moovBox.pos);
            long newLen = moovBox.pos + newMoov.length;
            if (newLen < f.size()) f.truncate(newLen);
            return;
        }

        // moov is in front of the audio and there's no room: rebuild, moving everything after it
        if (contains(moov, "mvex")) throw new IOException("fragmented MP4 files aren't supported for writing");
        long shift = newMoov.length + PADDING - moov.length;
        shiftOffsets(newMoov, moovBox.end(), shift, fileLen);
        RgWriter.rebuild(f, temp, moovBox.pos, moovBox.end(), ByteBuffer.wrap(concat(newMoov, free(PADDING))));
    }

    // ------------------------------------------------------------------ tags

    /** Returns moov with the ReplayGain items replaced. */
    static byte[] withTags(byte[] moov, List<String[]> pairs) throws IOException {
        Box udta = child(moov, 8, moov.length, "udta");
        byte[] newUdta;
        if (udta == null) {
            newUdta = box("udta", newMeta(pairs));
        } else {
            byte[] u = slice(moov, udta);
            Box meta = child(u, udta.hdr, u.length, "meta");
            byte[] newMetaBox;
            if (meta == null) {
                newMetaBox = newMeta(pairs);
            } else {
                byte[] m = slice(u, meta);
                int kids = meta.hdr + (isFullBoxMeta(m, meta.hdr) ? 4 : 0);
                Box ilst = child(m, kids, m.length, "ilst");
                byte[] newIlst = ilst == null ? box("ilst", items(null, 0, 0, pairs)) : rebuildIlst(m, ilst, pairs);
                newMetaBox = ilst == null ? insert(m, m.length, newIlst) : replace(m, ilst, newIlst);
            }
            newUdta = meta == null ? insert(u, u.length, newMetaBox) : replace(u, meta, newMetaBox);
        }
        return udta == null ? insert(moov, moov.length, newUdta) : replace(moov, udta, newUdta);
    }

    /** QuickTime files sometimes write meta without the 4 version/flags bytes. */
    private static boolean isFullBoxMeta(byte[] m, int hdr) {
        if (m.length < hdr + 8) return true;
        String t = new String(m, hdr + 4, 4, LATIN1);
        return !(t.equals("hdlr") || t.equals("ilst") || t.equals("keys"));
    }

    private static byte[] newMeta(List<String[]> pairs) throws IOException {
        byte[] hdlr = new byte[33];
        ByteBuffer h = ByteBuffer.wrap(hdlr);
        h.putInt(33).put("hdlr".getBytes(LATIN1)).putInt(0).putInt(0).put("mdir".getBytes(LATIN1)).put("appl".getBytes(LATIN1));
        byte[] ilst = box("ilst", items(null, 0, 0, pairs));
        byte[] body = concat(new byte[4], concat(hdlr, ilst));    // version 0, flags 0
        return box("meta", body);
    }

    private static byte[] rebuildIlst(byte[] m, Box ilst, List<String[]> pairs) throws IOException {
        byte[] l = slice(m, ilst);
        return box("ilst", items(l, ilst.hdr, l.length, pairs));
    }

    /** Existing items minus old ReplayGain ones, then ours. */
    private static byte[] items(byte[] l, int s, int e, List<String[]> pairs) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (l != null) {
            for (Box b : children(l, s, e)) {
                if (b.type.equals("----")) {
                    String name = freeformName(l, b);
                    if (name != null && RgWriter.isRgKey(name)) continue;
                }
                out.write(l, (int) b.pos, (int) b.size);
            }
        }
        for (String[] p : pairs) out.write(freeform(p[0].toLowerCase(java.util.Locale.US), p[1]));
        return out.toByteArray();
    }

    private static String freeformName(byte[] l, Box item) throws IOException {
        for (Box b : children(l, (int) item.pos + item.hdr, (int) item.end())) {
            if (b.type.equals("name") && b.size >= b.hdr + 4) {
                int off = (int) b.pos + b.hdr + 4;
                return new String(l, off, (int) b.end() - off, UTF8);
            }
        }
        return null;
    }

    private static byte[] freeform(String name, String value) {
        byte[] mean = "com.apple.iTunes".getBytes(UTF8), nm = name.getBytes(UTF8), val = value.getBytes(UTF8);
        int meanLen = 12 + mean.length, nameLen = 12 + nm.length, dataLen = 16 + val.length;
        ByteBuffer b = ByteBuffer.allocate(8 + meanLen + nameLen + dataLen);
        b.putInt(b.capacity()).put("----".getBytes(LATIN1));
        b.putInt(meanLen).put("mean".getBytes(LATIN1)).putInt(0).put(mean);
        b.putInt(nameLen).put("name".getBytes(LATIN1)).putInt(0).put(nm);
        b.putInt(dataLen).put("data".getBytes(LATIN1)).putInt(1).putInt(0).put(val);   // type 1 = UTF-8 text
        return b.array();
    }

    // --------------------------------------------------------------- offsets

    /** Adds shift to every chunk offset at or after moovEnd (the data that moves). */
    static void shiftOffsets(byte[] moov, long moovEnd, long shift, long fileLen) throws IOException {
        walk(moov, 8, moov.length, moovEnd, shift);
    }

    private static void walk(byte[] b, int s, int e, long moovEnd, long shift) throws IOException {
        for (Box x : children(b, s, e)) {
            int body = (int) x.pos + x.hdr;
            switch (x.type) {
                case "trak": case "mdia": case "minf": case "stbl":
                    walk(b, body, (int) x.end(), moovEnd, shift);
                    break;
                case "stco": {
                    ByteBuffer bb = ByteBuffer.wrap(b);
                    long n = bb.getInt(body + 4) & 0xffffffffL;
                    if (body + 8 + n * 4 > x.end()) throw new IOException("MP4: damaged stco");
                    for (int i = 0; i < n; i++) {
                        int at = body + 8 + i * 4;
                        long o = bb.getInt(at) & 0xffffffffL;
                        if (o >= moovEnd) {
                            o += shift;
                            if (o > 0xffffffffL) throw new IOException("MP4 too large to retag");
                            bb.putInt(at, (int) o);
                        }
                    }
                    break;
                }
                case "co64": {
                    ByteBuffer bb = ByteBuffer.wrap(b);
                    long n = bb.getInt(body + 4) & 0xffffffffL;
                    if (body + 8 + n * 8 > x.end()) throw new IOException("MP4: damaged co64");
                    for (int i = 0; i < n; i++) {
                        int at = body + 8 + i * 8;
                        long o = bb.getLong(at);
                        if (o >= moovEnd) bb.putLong(at, o + shift);
                    }
                    break;
                }
                default:
            }
        }
    }

    private static boolean contains(byte[] moov, String type) throws IOException {
        return child(moov, 8, moov.length, type) != null;
    }

    // ------------------------------------------------------------------ boxes

    private static List<Box> topLevel(RgWriter.Rw f) throws IOException {
        List<Box> out = new ArrayList<>();
        long len = f.size(), p = 0;
        for (int guard = 0; p + 8 <= len && guard < 100000; guard++) {
            byte[] h = RgWriter.read(f, p, 16);
            long size = be32(h, 0) & 0xffffffffL;
            int hdr = 8;
            if (size == 1) { if (h.length < 16) throw new IOException("MP4: truncated"); size = be64(h, 8); hdr = 16; }
            else if (size == 0) size = len - p;
            if (size < hdr || p + size > len) {
                if (find(out, "moov") != null && find(out, "mdat") != null) break;   // trailing junk
                throw new IOException("MP4: bad box size");
            }
            out.add(new Box(p, size, hdr, new String(h, 4, 4, LATIN1)));
            p += size;
        }
        return out;
    }

    private static List<Box> children(byte[] b, int s, int e) throws IOException {
        List<Box> out = new ArrayList<>();
        int p = s;
        while (p + 8 <= e) {
            long size = be32(b, p) & 0xffffffffL;
            int hdr = 8;
            if (size == 1) { if (p + 16 > e) throw new IOException("MP4: truncated box"); size = be64(b, p + 8); hdr = 16; }
            else if (size == 0) size = e - p;
            if (size < hdr || p + size > e) throw new IOException("MP4: bad box size");
            out.add(new Box(p, size, hdr, new String(b, p + 4, 4, LATIN1)));
            p += (int) size;
        }
        return out;
    }

    private static Box child(byte[] b, int s, int e, String type) throws IOException {
        for (Box x : children(b, s, e)) if (x.type.equals(type)) return x;
        return null;
    }

    private static Box find(List<Box> l, String type) {
        for (Box b : l) if (b.type.equals(type)) return b;
        return null;
    }

    private static byte[] slice(byte[] b, Box x) { return java.util.Arrays.copyOfRange(b, (int) x.pos, (int) x.end()); }

    /** parent with child x swapped for repl, parent's size updated. */
    private static byte[] replace(byte[] parent, Box x, byte[] repl) throws IOException {
        byte[] out = new byte[parent.length - (int) x.size + repl.length];
        System.arraycopy(parent, 0, out, 0, (int) x.pos);
        System.arraycopy(repl, 0, out, (int) x.pos, repl.length);
        System.arraycopy(parent, (int) x.end(), out, (int) x.pos + repl.length, parent.length - (int) x.end());
        return resize(out);
    }

    private static byte[] insert(byte[] parent, int at, byte[] add) throws IOException {
        byte[] out = new byte[parent.length + add.length];
        System.arraycopy(parent, 0, out, 0, at);
        System.arraycopy(add, 0, out, at, add.length);
        System.arraycopy(parent, at, out, at + add.length, parent.length - at);
        return resize(out);
    }

    private static byte[] resize(byte[] b) throws IOException {
        long old = be32(b, 0) & 0xffffffffL;
        ByteBuffer bb = ByteBuffer.wrap(b);
        if (old == 1) bb.putLong(8, b.length);
        else {
            if (b.length > 0xffffffffL) throw new IOException("MP4: box too large");
            bb.putInt(0, b.length);
        }
        return b;
    }

    private static byte[] box(String type, byte[] body) {
        ByteBuffer b = ByteBuffer.allocate(8 + body.length);
        b.putInt(8 + body.length).put(type.getBytes(LATIN1)).put(body);
        return b.array();
    }

    private static byte[] free(int size) {
        byte[] b = new byte[size];
        ByteBuffer.wrap(b).putInt(size).put("free".getBytes(LATIN1));
        return b;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] o = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    private static int be32(byte[] b, int o) { return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff); }
    private static long be64(byte[] b, int o) { return ((long) be32(b, o) << 32) | (be32(b, o + 4) & 0xffffffffL); }
}
