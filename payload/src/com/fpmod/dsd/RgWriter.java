package com.fpmod.dsd;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes ReplayGain tags into FLAC, MP3, WAV, AIFF, DSF and DFF files. Pure Java.
 * Tags are rewritten in place whenever there is room (audio untouched). Otherwise the file
 * is rebuilt through a temporary copy.
 */
public final class RgWriter {

    /** Random-access file access, so Android can supply a content-URI descriptor. */
    public interface Rw {
        long size() throws IOException;
        int read(ByteBuffer b, long pos) throws IOException;
        void write(ByteBuffer b, long pos) throws IOException;
        void truncate(long size) throws IOException;
    }

    /** Somewhere to stage a rebuilt file. */
    public interface Temp {
        Rw create() throws IOException;
        void delete();
    }

    public static final class Values {
        public double trackGain, trackPeak, albumGain = Double.NaN, albumPeak = Double.NaN;
    }

    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String[] KEYS = {"REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_PEAK", "REPLAYGAIN_ALBUM_GAIN", "REPLAYGAIN_ALBUM_PEAK"};

    private RgWriter() { }

    /** Which formats can be written. Returns null if supported, else the reason. */
    public static String unsupportedReason(Rw f) throws IOException {
        byte[] h = read(f, 0, 32);
        String m = ascii(h, 0, 4);
        if (m.startsWith("ID3")) {
            long end = 10 + syncsafe(h, 6) + ((h[5] & 0x10) != 0 ? 10 : 0);
            byte[] h2 = read(f, end, 4);
            if (ascii(h2, 0, 4).equals("fLaC")) return null;
            return mp3Problem(h);
        }
        if (m.equals("fLaC")) return null;
        if (m.equals("RIFF") && ascii(h, 8, 4).equals("WAVE")) return null;
        if (m.equals("FORM") && ascii(h, 8, 3).equals("AIF")) return null;
        if (m.equals("DSD ")) return null;
        if (m.equals("FRM8")) return null;
        if (looksLikeMpegAudio(h)) return null;
        if (ascii(h, 4, 4).equals("ftyp")) return "M4A/MP4 files aren't supported for writing";
        if (m.equals("OggS")) return "Ogg/Opus files aren't supported for writing";
        if (m.equals("wvpk")) return "WavPack files aren't supported for writing";
        return "unknown format";
    }

    private static String mp3Problem(byte[] h) {
        int ver = h[3] & 0xff, flags = h[5] & 0xff;
        if (ver < 3 || ver > 4) return "old ID3v2." + ver + " tag";
        if ((flags & 0x80) != 0 && ver == 3) return "unsynchronised ID3 tag";
        if ((flags & 0x40) != 0) return "ID3 extended header";
        return null;
    }

    private static boolean looksLikeMpegAudio(byte[] h) {
        return (h[0] & 0xff) == 0xff && (h[1] & 0xe0) == 0xe0;
    }

    /** Writes the tags. Throws on any problem; the file is only changed when everything checks out. */
    public static void write(Rw f, Values v, Temp temp) throws IOException {
        String why = unsupportedReason(f);
        if (why != null) throw new IOException(why);
        byte[] h = read(f, 0, 32);
        String m = ascii(h, 0, 4);
        if (m.startsWith("ID3")) {
            long end = 10 + syncsafe(h, 6) + ((h[5] & 0x10) != 0 ? 10 : 0);
            if (ascii(read(f, end, 4), 0, 4).equals("fLaC")) { flac(f, end, v, temp); return; }
            mp3(f, v, temp);
        } else if (m.equals("fLaC")) flac(f, 0, v, temp);
        else if (m.equals("RIFF")) iff(f, v, false, false);
        else if (m.equals("FORM")) iff(f, v, true, false);
        else if (m.equals("FRM8")) iff(f, v, true, true);
        else if (m.equals("DSD ")) dsf(f, v);
        else mp3(f, v, temp);
    }

    static String fmtGain(double g) { return String.format(Locale.US, "%.2f dB", g); }
    static String fmtPeak(double p) { return String.format(Locale.US, "%.6f", p); }

    private static List<String[]> pairs(Values v) {
        List<String[]> l = new ArrayList<>();
        l.add(new String[]{KEYS[0], fmtGain(v.trackGain)});
        l.add(new String[]{KEYS[1], fmtPeak(v.trackPeak)});
        if (!Double.isNaN(v.albumGain)) {
            l.add(new String[]{KEYS[2], fmtGain(v.albumGain)});
            l.add(new String[]{KEYS[3], fmtPeak(v.albumPeak)});
        }
        return l;
    }

    private static boolean isRgKey(String k) {
        String u = k.trim().toUpperCase(Locale.US);
        for (String s : KEYS) if (s.equals(u)) return true;
        return u.equals("REPLAYGAIN_REFERENCE_LOUDNESS");
    }

    // -------------------------------------------------------------------- FLAC

    private static void flac(Rw f, long start, Values v, Temp temp) throws IOException {
        long pos = start + 4;
        List<byte[]> blocks = new ArrayList<>();   // full blocks incl. header, padding dropped
        byte[] vorbis = null;
        int vorbisIndex = -1;
        boolean last = false;
        while (!last) {
            byte[] bh = read(f, pos, 4);
            if (bh.length < 4) throw new IOException("FLAC: truncated metadata");
            last = (bh[0] & 0x80) != 0;
            int type = bh[0] & 0x7f;
            int len = ((bh[1] & 0xff) << 16) | ((bh[2] & 0xff) << 8) | (bh[3] & 0xff);
            if (type == 127) throw new IOException("FLAC: bad metadata block");
            if (type != 1) {
                byte[] body = read(f, pos + 4, len);
                if (body.length != len) throw new IOException("FLAC: truncated block");
                if (type == 4) { vorbis = body; vorbisIndex = blocks.size(); }
                byte[] full = new byte[4 + len];
                full[0] = (byte) type; full[1] = bh[1]; full[2] = bh[2]; full[3] = bh[3];
                System.arraycopy(body, 0, full, 4, len);
                blocks.add(full);
            }
            pos += 4 + len;
            if (blocks.isEmpty() && type != 0) throw new IOException("FLAC: STREAMINFO missing");
        }
        long audioStart = pos;
        byte[] check = read(f, audioStart, 2);
        if (check.length < 2 || (check[0] & 0xff) != 0xff || (check[1] & 0xfc) != 0xf8) throw new IOException("FLAC: audio frames not where expected");

        byte[] newVorbis = buildVorbis(vorbis, pairs(v));
        byte[] vb = new byte[4 + newVorbis.length];
        vb[0] = 4; put24(vb, 1, newVorbis.length);
        System.arraycopy(newVorbis, 0, vb, 4, newVorbis.length);
        if (vorbisIndex >= 0) blocks.set(vorbisIndex, vb); else blocks.add(1, vb);

        long metaLen = 0;
        for (byte[] b : blocks) metaLen += b.length;
        long region = audioStart - (start + 4);
        long spare = region - metaLen;
        if (spare == 0 || spare >= 4) {
            // fits: rewrite metadata in place, fill the rest with padding
            ByteBuffer out = ByteBuffer.allocate((int) region);
            writeBlocks(out, blocks, spare >= 4 ? (int) (spare - 4) : -1);
            f.write((ByteBuffer) out.flip(), start + 4);
        } else {
            int padding = 8192;
            ByteBuffer meta = ByteBuffer.allocate((int) (metaLen + 4 + padding));
            writeBlocks(meta, blocks, padding);
            meta.flip();
            rebuild(f, temp, start + 4, audioStart, meta);
        }
    }

    private static void writeBlocks(ByteBuffer out, List<byte[]> blocks, int padding) {
        for (int i = 0; i < blocks.size(); i++) {
            byte[] b = blocks.get(i).clone();
            boolean isLast = padding < 0 && i == blocks.size() - 1;
            b[0] = (byte) ((b[0] & 0x7f) | (isLast ? 0x80 : 0));
            out.put(b);
        }
        if (padding >= 0) {
            byte[] ph = new byte[4];
            ph[0] = (byte) (0x80 | 1); put24(ph, 1, padding);
            out.put(ph);
            out.put(new byte[padding]);
        }
    }

    private static byte[] buildVorbis(byte[] old, List<String[]> add) throws IOException {
        String vendor = "SonyMusicFairphoneMod";
        List<byte[]> comments = new ArrayList<>();
        if (old != null) {
            ByteBuffer b = ByteBuffer.wrap(old).order(ByteOrder.LITTLE_ENDIAN);
            int vl = b.getInt();
            if (vl < 0 || vl > b.remaining()) throw new IOException("FLAC: bad vendor string");
            vendor = new String(old, 4, vl, UTF8);
            b.position(4 + vl);
            int n = b.getInt();
            for (int i = 0; i < n; i++) {
                int l = b.getInt();
                if (l < 0 || l > b.remaining()) throw new IOException("FLAC: bad comment");
                byte[] c = new byte[l];
                b.get(c);
                String s = new String(c, UTF8);
                int eq = s.indexOf('=');
                if (eq > 0 && isRgKey(s.substring(0, eq))) continue;
                comments.add(c);
            }
        }
        for (String[] p : add) comments.add((p[0] + "=" + p[1]).getBytes(UTF8));
        byte[] vb = vendor.getBytes(UTF8);
        int len = 4 + vb.length + 4;
        for (byte[] c : comments) len += 4 + c.length;
        ByteBuffer out = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(vb.length).put(vb).putInt(comments.size());
        for (byte[] c : comments) out.putInt(c.length).put(c);
        if (len >= (1 << 24)) throw new IOException("FLAC: comments too large");
        return out.array();
    }

    // ------------------------------------------------------------------- ID3v2

    /** Builds a new ID3v2 tag: the old tag's frames minus ReplayGain TXXX, plus ours. */
    static byte[] buildId3(byte[] oldTag, List<String[]> add, int minSize) throws IOException {
        int ver = 4;
        List<byte[]> frames = new ArrayList<>();
        if (oldTag != null && oldTag.length >= 10) {
            ver = oldTag[3] & 0xff;
            int flags = oldTag[5] & 0xff;
            if (ver < 3 || ver > 4) throw new IOException("old ID3v2." + ver + " tag");
            if ((flags & 0x40) != 0) throw new IOException("ID3 extended header");
            if ((flags & 0x80) != 0 && ver == 3) throw new IOException("unsynchronised ID3 tag");
            int size = syncsafe(oldTag, 6);
            int p = 10, end = Math.min(oldTag.length, 10 + size);
            while (p + 10 <= end) {
                if (oldTag[p] == 0) break;
                String id = ascii(oldTag, p, 4);
                int fs = ver == 4 ? syncsafe(oldTag, p + 4) : be32(oldTag, p + 4);
                if (fs < 0 || p + 10 + fs > end) throw new IOException("ID3: bad frame size");
                byte[] fr = java.util.Arrays.copyOfRange(oldTag, p, p + 10 + fs);
                boolean drop = false;
                if (id.equals("TXXX") && fs > 1) {
                    int fflags = ((fr[8] & 0xff) << 8) | (fr[9] & 0xff);
                    boolean plain = ver == 3 ? (fflags & 0x00C0) == 0 : (fflags & 0x000F) == 0;
                    if (plain) {
                        String desc = txxxDesc(fr, 10, fs);
                        if (desc != null && isRgKey(desc)) drop = true;
                    }
                }
                if (!drop) frames.add(fr);
                p += 10 + fs;
            }
        }
        for (String[] kv : add) frames.add(txxx(kv[0], kv[1], ver));
        int body = 0;
        for (byte[] fr : frames) body += fr.length;
        int total = Math.max(body, minSize);
        if (total > 0x0FFFFFFF) throw new IOException("ID3 tag too large");
        ByteBuffer out = ByteBuffer.allocate(10 + total);
        out.put(new byte[]{'I', 'D', '3', (byte) ver, 0, 0});
        out.put(syncsafeBytes(total));
        for (byte[] fr : frames) out.put(fr);
        return out.array();   // remaining bytes are zero padding
    }

    private static String txxxDesc(byte[] fr, int off, int len) {
        int enc = fr[off] & 0xff;
        int p = off + 1, end = off + len;
        if (enc == 1 || enc == 2) {
            int q = p;
            while (q + 1 < end && !(fr[q] == 0 && fr[q + 1] == 0)) q += 2;
            if (q + 1 >= end) return null;
            return new String(fr, p, q - p, enc == 1 ? Charset.forName("UTF-16") : Charset.forName("UTF-16BE"));
        }
        int q = p;
        while (q < end && fr[q] != 0) q++;
        if (q >= end) return null;
        return new String(fr, p, q - p, enc == 3 ? UTF8 : LATIN1);
    }

    private static byte[] txxx(String desc, String value, int ver) {
        byte enc = (byte) (ver == 4 ? 3 : 0);
        Charset cs = ver == 4 ? UTF8 : LATIN1;
        byte[] d = desc.getBytes(cs), val = value.getBytes(cs);
        int size = 1 + d.length + 1 + val.length;
        ByteBuffer b = ByteBuffer.allocate(10 + size);
        b.put(new byte[]{'T', 'X', 'X', 'X'});
        if (ver == 4) b.put(syncsafeBytes(size)); else b.putInt(size);
        b.put((byte) 0).put((byte) 0);
        b.put(enc).put(d).put((byte) 0).put(val);
        return b.array();
    }

    private static void mp3(Rw f, Values v, Temp temp) throws IOException {
        byte[] h = read(f, 0, 10);
        byte[] old = null;
        long oldEnd = 0;
        if (ascii(h, 0, 3).equals("ID3")) {
            if ((h[5] & 0x10) != 0) throw new IOException("ID3 footer not supported");
            int size = syncsafe(h, 6);
            old = read(f, 0, 10 + size);
            oldEnd = 10 + size;
        }
        byte[] tag = buildId3(old, pairs(v), 0);
        if (old != null && tag.length <= oldEnd) {
            byte[] fit = buildId3(old, pairs(v), (int) (oldEnd - 10));
            f.write(ByteBuffer.wrap(fit), 0);
            return;
        }
        byte[] padded = buildId3(old, pairs(v), tag.length - 10 + 4096);
        rebuild(f, temp, 0, oldEnd, ByteBuffer.wrap(padded));
    }

    // ---------------------------------------------------------- WAV/AIFF/DFF

    private static void iff(Rw f, Values v, boolean bigEndian, boolean dff) throws IOException {
        long fileLen = f.size();
        int hdr = dff ? 12 : 8;
        long pos = dff ? 16 : 12;
        long id3Pos = -1, id3Size = 0, lastEnd = pos;
        while (pos + hdr <= fileLen) {
            byte[] c = read(f, pos, hdr);
            String id = ascii(c, 0, 4);
            long size = dff ? be64(c, 4) : bigEndian ? (be32(c, 4) & 0xffffffffL) : (le32(c, 4) & 0xffffffffL);
            if (size < 0 || pos + hdr + size > fileLen) {
                if (id.equalsIgnoreCase("id3 ")) throw new IOException("damaged ID3 chunk");
                break;
            }
            if (id.equalsIgnoreCase("id3 ")) { id3Pos = pos; id3Size = size; }
            pos += hdr + size + (size & 1);
            lastEnd = pos;
        }
        byte[] old = null;
        if (id3Pos >= 0) old = read(f, id3Pos + hdr, (int) id3Size);
        if (old != null && !ascii(old, 0, 3).equals("ID3")) old = null;
        String chunkId = dff ? "ID3 " : bigEndian ? "ID3 " : "id3 ";

        byte[] tag = buildId3(old, pairs(v), 0);
        if (id3Pos >= 0 && tag.length <= id3Size) {
            byte[] fit = buildId3(old, pairs(v), (int) id3Size - 10);
            f.write(ByteBuffer.wrap(fit), id3Pos + hdr);
            return;
        }
        long writeAt;
        if (id3Pos >= 0) {
            long oldChunkEnd = id3Pos + hdr + id3Size + (id3Size & 1);
            if (oldChunkEnd < lastEnd || oldChunkEnd < fileLen - 1) throw new IOException("ID3 chunk isn't at the end of the file");
            writeAt = id3Pos;
        } else {
            writeAt = lastEnd;
            if (writeAt > fileLen) writeAt = fileLen;
        }
        byte[] padded = buildId3(old, pairs(v), tag.length - 10 + 1024);
        long size = padded.length;
        ByteBuffer chunk = ByteBuffer.allocate((int) (hdr + size + (size & 1))).order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        chunk.put(chunkId.getBytes(LATIN1));
        if (dff) chunk.putLong(size); else chunk.putInt((int) size);
        chunk.put(padded);
        chunk.flip();
        long newLen = writeAt + chunk.remaining();
        f.write(chunk, writeAt);
        if (newLen < f.size()) f.truncate(newLen);
        // container size
        ByteBuffer cs = ByteBuffer.allocate(dff ? 8 : 4).order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        if (dff) cs.putLong(newLen - 12); else cs.putInt((int) (newLen - 8));
        cs.flip();
        f.write(cs, 4);
    }

    // --------------------------------------------------------------------- DSF

    private static void dsf(Rw f, Values v) throws IOException {
        byte[] h = read(f, 0, 28);
        ByteBuffer hb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        long fileLen = f.size();
        long meta = hb.getLong(20);
        // the data chunk must end before the tag
        byte[] fmt = read(f, 28, 12);
        long fmtSize = ByteBuffer.wrap(fmt, 4, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        byte[] data = read(f, 28 + fmtSize, 12);
        if (!ascii(data, 0, 4).equals("data")) throw new IOException("DSF: no data chunk");
        long dataEnd = 28 + fmtSize + ByteBuffer.wrap(data, 4, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        byte[] old = null;
        long writeAt;
        if (meta > 0) {
            if (meta < dataEnd || meta >= fileLen) throw new IOException("DSF: bad tag position");
            old = read(f, meta, (int) Math.min(fileLen - meta, 64L * 1024 * 1024));
            if (!ascii(old, 0, 3).equals("ID3")) throw new IOException("DSF: tag isn't ID3");
            old = java.util.Arrays.copyOf(old, Math.min(old.length, 10 + syncsafe(old, 6)));
            writeAt = meta;
        } else {
            writeAt = Math.max(dataEnd, Math.min(fileLen, dataEnd));
            if (fileLen > dataEnd) writeAt = fileLen;      // keep anything already after the data
        }
        byte[] tag = buildId3(old, pairs(v), 0);
        byte[] padded = buildId3(old, pairs(v), tag.length - 10 + 1024);
        f.write(ByteBuffer.wrap(padded), writeAt);
        long newLen = writeAt + padded.length;
        if (newLen < f.size()) f.truncate(newLen);
        ByteBuffer hdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putLong(newLen).putLong(writeAt).flip();
        f.write(hdr, 12);
    }

    // --------------------------------------------------------------- rebuilding

    /** Replaces bytes [from, to) of the file with head, via a temporary copy. */
    private static void rebuild(Rw f, Temp temp, long from, long to, ByteBuffer head) throws IOException {
        long len = f.size();
        Rw t = temp.create();
        try {
            long w = 0;
            w = copy(f, 0, from, t, w);
            int hl = head.remaining();
            t.write(head, w); w += hl;
            w = copy(f, to, len - to, t, w);
            long newLen = w;
            if (t.size() != newLen) throw new IOException("temporary copy is incomplete");
            // copy back over the original
            copy(t, 0, newLen, f, 0);
            if (f.size() > newLen) f.truncate(newLen);
            if (f.size() != newLen) throw new IOException("rewrite is incomplete");
        } finally {
            temp.delete();
        }
    }

    private static long copy(Rw src, long srcPos, long n, Rw dst, long dstPos) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(1 << 20);
        long done = 0;
        while (done < n) {
            b.clear();
            b.limit((int) Math.min(b.capacity(), n - done));
            int r = src.read(b, srcPos + done);
            if (r <= 0) throw new IOException("unexpected end of file");
            b.flip();
            dst.write(b, dstPos + done);
            done += r;
        }
        return dstPos + n;
    }

    // ------------------------------------------------------------------ helpers

    static byte[] read(Rw f, long pos, int n) throws IOException {
        long len = f.size();
        if (pos < 0 || pos >= len || n <= 0) return new byte[0];
        n = (int) Math.min(n, len - pos);
        ByteBuffer b = ByteBuffer.allocate(n);
        int total = 0;
        while (b.hasRemaining()) {
            int r = f.read(b, pos + total);
            if (r <= 0) break;
            total += r;
        }
        return total == n ? b.array() : java.util.Arrays.copyOf(b.array(), total);
    }

    private static String ascii(byte[] b, int o, int n) { return o + n > b.length ? "" : new String(b, o, n, LATIN1); }
    private static int syncsafe(byte[] b, int o) { return ((b[o] & 0x7f) << 21) | ((b[o + 1] & 0x7f) << 14) | ((b[o + 2] & 0x7f) << 7) | (b[o + 3] & 0x7f); }
    private static byte[] syncsafeBytes(int v) { return new byte[]{(byte) ((v >> 21) & 0x7f), (byte) ((v >> 14) & 0x7f), (byte) ((v >> 7) & 0x7f), (byte) (v & 0x7f)}; }
    private static int be32(byte[] b, int o) { return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff); }
    private static int le32(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24); }
    private static long be64(byte[] b, int o) { return ((long) be32(b, o) << 32) | (be32(b, o + 4) & 0xffffffffL); }
    private static void put24(byte[] b, int o, int v) { b[o] = (byte) (v >> 16); b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) v; }
}
