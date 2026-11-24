package com.fpmod.dsd;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Software DSD decoder. Reads a DSF or DFF (uncompressed DSD) file and presents
 * it as a virtual 24-bit stereo PCM WAV file at 88.2 kHz, so Android's standard
 * player can play it.
 *
 * Decimation is a single linear-phase FIR (Kaiser-windowed sinc), evaluated a byte
 * at a time through lookup tables. Pass band is flat to about 20 kHz; the DSD
 * noise above about 50 kHz is strongly attenuated before the rate is reduced.
 */
public final class DsdWav {

    public static final int OUT_RATE = 88200;
    private static final int OUT_CHANNELS = 2;
    private static final int OUT_BYTES_PER_FRAME = OUT_CHANNELS * 3;
    private static final int HEADER_SIZE = 44;
    private static final int BLOCK_FRAMES = 4096;
    /** DSD idle pattern, decodes to (near) silence. */
    private static final byte SILENCE = 0x69;
    /** +3 dB: SACD's peak level (about 71% modulation) maps to just under full scale. */
    private static final float GAIN = 1.4125f;
    private static final double CUTOFF_HZ = 37000.0;
    private static final double KAISER_BETA = 9.0;

    private final FileChannel ch;
    private final boolean dsf;
    private final boolean lsbFirst;
    private final int channels;
    private final long dsdRate;
    private final long dataStart;
    private final long bytesPerChannel;  // valid DSD bytes per channel
    private final int dsfBlock;          // DSF block size per channel

    // DST-compressed DFF
    private DstDecoder dst;
    private long dstPos, dstEnd;         // next unscanned position inside the DST chunk, and its end
    private long[] dstOff;               // frame data offsets (lazily filled)
    private int[] dstLen;
    private int dstScanned, dstFrames, dstFrameBytes;
    private final long[] dstCacheIdx = {-1, -1};
    private final byte[][] dstCache = new byte[2][];
    private int dstCacheNext;
    private byte[] dstIn = new byte[0];

    private final int bytesPerFrame;     // DSD bytes per channel per output frame
    private final int taps;              // filter length in bytes
    private final float[] lut;           // [taps][256]
    private final long frames;
    private final byte[] header;

    // Decoded block cache
    private long cacheBlock = -1;
    private final byte[] cache = new byte[BLOCK_FRAMES * OUT_BYTES_PER_FRAME];
    private int cacheLen;
    private byte[][] chanBuf = new byte[0][];
    private byte[] rawBuf = new byte[0];
    private final float[] mixL = new float[BLOCK_FRAMES];
    private final float[] mixR = new float[BLOCK_FRAMES];

    private static final byte[] BITREV = new byte[256];
    static {
        for (int i = 0; i < 256; i++) {
            int r = 0;
            for (int b = 0; b < 8; b++) if ((i & (1 << b)) != 0) r |= 0x80 >> b;
            BITREV[i] = (byte) r;
        }
    }

    /** Returns true if the first bytes look like a DSF or DFF file. */
    public static boolean looksLikeDsd(FileChannel ch) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(16);
        if (readFully(ch, b, 0) < 16) return false;
        String id = ascii(b, 0);
        if (id.equals("DSD ")) return true;
        return id.equals("FRM8") && ascii(b, 12).equals("DSD ");
    }

    public DsdWav(FileChannel ch) throws IOException {
        this.ch = ch;
        ByteBuffer b = ByteBuffer.allocate(16);
        readFully(ch, b, 0);
        String id = ascii(b, 0);
        if (id.equals("DSD ")) {
            dsf = true;
            long[] v = parseDsf();
            channels = (int) v[0]; dsdRate = v[1]; lsbFirst = v[2] == 1;
            bytesPerChannel = v[3]; dsfBlock = (int) v[4]; dataStart = v[5];
        } else if (id.equals("FRM8")) {
            dsf = false;
            long[] v = parseDff();
            channels = (int) v[0]; dsdRate = v[1]; lsbFirst = false;
            dsfBlock = 0; dataStart = v[3];
            if (v[4] == 1) {
                dst = new DstDecoder(channels, dsdRate);
                dstFrameBytes = dst.frameBytesPerChannel();
                dstFrames = (int) v[5];
                dstPos = v[3]; dstEnd = v[6];
                dstOff = new long[dstFrames]; dstLen = new int[dstFrames];
                bytesPerChannel = (long) dstFrames * dstFrameBytes;
            } else {
                bytesPerChannel = v[2];
            }
        } else {
            throw new IOException("Not a DSD file");
        }
        if (channels < 1 || channels > 6) throw new IOException("Unsupported channel count " + channels);
        if (dsdRate % OUT_RATE != 0) throw new IOException("Unsupported DSD rate " + dsdRate);
        int decim = (int) (dsdRate / OUT_RATE);           // 32 for DSD64 ... 256 for DSD512
        if (decim < 8 || decim % 8 != 0 || decim > 256) throw new IOException("Unsupported DSD rate " + dsdRate);
        bytesPerFrame = decim / 8;
        taps = bytesPerFrame * 16;                         // 512 taps at DSD64, scaled with rate
        lut = buildLut(taps, dsdRate);
        frames = bytesPerChannel / bytesPerFrame;
        header = buildHeader(frames * OUT_BYTES_PER_FRAME);
    }

    public int channels() { return channels; }
    public long dsdRate() { return dsdRate; }
    public long frames() { return frames; }

    /** Size of the virtual WAV file in bytes. */
    public long size() { return HEADER_SIZE + frames * OUT_BYTES_PER_FRAME; }

    /** Reads from the virtual WAV file. Returns -1 at end of file. */
    public synchronized int readAt(long pos, byte[] dst, int off, int len) throws IOException {
        long size = size();
        if (pos >= size) return -1;
        if (len <= 0) return 0;
        int done = 0;
        while (done < len && pos < size) {
            if (pos < HEADER_SIZE) {
                int n = (int) Math.min(len - done, HEADER_SIZE - pos);
                System.arraycopy(header, (int) pos, dst, off + done, n);
                pos += n; done += n;
                continue;
            }
            long dataPos = pos - HEADER_SIZE;
            long blockBytes = (long) BLOCK_FRAMES * OUT_BYTES_PER_FRAME;
            long block = dataPos / blockBytes;
            if (block != cacheBlock) decodeBlock(block);
            int inBlock = (int) (dataPos - block * blockBytes);
            int n = Math.min(len - done, cacheLen - inBlock);
            if (n <= 0) break;
            System.arraycopy(cache, inBlock, dst, off + done, n);
            pos += n; done += n;
        }
        return done == 0 ? -1 : done;
    }

    // ---------------------------------------------------------------- decoding

    private void decodeBlock(long block) throws IOException {
        long f0 = block * BLOCK_FRAMES;
        int nf = (int) Math.min(BLOCK_FRAMES, frames - f0);
        if (nf <= 0) { cacheBlock = block; cacheLen = 0; return; }

        // DSD byte range needed per channel (filter centred on each output frame)
        int half = taps / 2 - bytesPerFrame / 2;
        long b0 = f0 * bytesPerFrame - half;
        int span = (nf - 1) * bytesPerFrame + taps;
        if (chanBuf.length != channels || chanBuf[0].length < span) {
            chanBuf = new byte[channels][span];
        }
        readChannels(b0, span);

        java.util.Arrays.fill(mixL, 0, nf, 0f);
        java.util.Arrays.fill(mixR, 0, nf, 0f);
        for (int c = 0; c < channels; c++) {
            float wl, wr;
            if (channels == 1) { wl = 1f; wr = 1f; }
            else if (channels == 2) { wl = c == 0 ? 1f : 0f; wr = c == 1 ? 1f : 0f; }
            else {
                // Order used by DSF and DFF multichannel: FL FR C LFE SL SR (or FL FR C SL SR for 5.0)
                int role = roleOf(c);
                final float k = 0.7071f, s = 0.5f;          // centre/surround at -3 dB, overall -6 dB
                switch (role) {
                    case 0: wl = s; wr = 0; break;          // FL
                    case 1: wl = 0; wr = s; break;          // FR
                    case 2: wl = s * k; wr = s * k; break;  // C
                    case 4: wl = s * k; wr = 0; break;      // SL
                    case 5: wl = 0; wr = s * k; break;      // SR
                    default: wl = 0; wr = 0;                // LFE dropped
                }
            }
            if (wl == 0 && wr == 0) continue;
            filterChannel(chanBuf[c], nf, wl, wr);
        }

        int o = 0;
        for (int i = 0; i < nf; i++) {
            o = put24(cache, o, mixL[i] * GAIN);
            o = put24(cache, o, mixR[i] * GAIN);
        }
        cacheBlock = block;
        cacheLen = nf * OUT_BYTES_PER_FRAME;
    }

    private int roleOf(int c) {
        if (channels == 3) return c;                  // FL FR C
        if (channels == 4) return c == 2 ? 4 : c == 3 ? 5 : c; // FL FR SL SR (quad)
        if (channels == 5) return c <= 2 ? c : c + 1; // FL FR C SL SR
        return c;                                     // 6: FL FR C LFE SL SR
    }

    private void filterChannel(byte[] src, int nf, float wl, float wr) {
        final float[] t = lut;
        final int n = taps, step = bytesPerFrame;
        int base = 0;
        for (int i = 0; i < nf; i++, base += step) {
            float acc = 0f;
            int li = 0;
            for (int k = 0; k < n; k++, li += 256) acc += t[li + (src[base + k] & 0xff)];
            mixL[i] += acc * wl;
            mixR[i] += acc * wr;
        }
    }

    /** Fills chanBuf[c][0..span) with channel bytes b0..b0+span, MSB-first; silence outside the file. */
    private void readChannels(long b0, int span) throws IOException {
        for (int c = 0; c < channels; c++) java.util.Arrays.fill(chanBuf[c], 0, span, SILENCE);
        long lo = Math.max(0, b0), hi = Math.min(bytesPerChannel, b0 + span);
        if (lo >= hi) return;
        if (dst != null) {
            readDst(b0, lo, hi);
            return;
        }
        if (dsf) {
            long blk0 = lo / dsfBlock, blk1 = (hi - 1) / dsfBlock;
            int groupBytes = dsfBlock * channels;
            int need = (int) ((blk1 - blk0 + 1) * groupBytes);
            if (rawBuf.length < need) rawBuf = new byte[need];
            int got = readFully(ch, ByteBuffer.wrap(rawBuf, 0, need), dataStart + blk0 * groupBytes);
            for (long i = lo; i < hi; i++) {
                long blk = i / dsfBlock;
                int inBlk = (int) (i - blk * dsfBlock);
                int gbase = (int) ((blk - blk0) * groupBytes) + inBlk;
                int dstIdx = (int) (i - b0);
                for (int c = 0; c < channels; c++) {
                    int idx = gbase + c * dsfBlock;
                    if (idx < got) {
                        byte v = rawBuf[idx];
                        chanBuf[c][dstIdx] = lsbFirst ? BITREV[v & 0xff] : v;
                    }
                }
            }
        } else {
            int need = (int) ((hi - lo) * channels);
            if (rawBuf.length < need) rawBuf = new byte[need];
            int got = readFully(ch, ByteBuffer.wrap(rawBuf, 0, need), dataStart + lo * channels);
            int p = 0;
            for (long i = lo; i < hi; i++) {
                int dstIdx = (int) (i - b0);
                for (int c = 0; c < channels; c++, p++) if (p < got) chanBuf[c][dstIdx] = rawBuf[p];
            }
        }
    }

    private static int put24(byte[] d, int o, float v) {
        if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
        int s = Math.round(v * 8388607f);
        d[o] = (byte) s; d[o + 1] = (byte) (s >> 8); d[o + 2] = (byte) (s >> 16);
        return o + 3;
    }

    // ------------------------------------------------------------------ filter

    static float[] buildLut(int tapBytes, long dsdRate) {
        int n = tapBytes * 8;
        double[] h = new double[n];
        double fc = CUTOFF_HZ / dsdRate;      // normalised cutoff (cycles/sample)
        double mid = (n - 1) / 2.0, sum = 0;
        double i0b = besselI0(KAISER_BETA);
        for (int i = 0; i < n; i++) {
            double x = i - mid;
            double sinc = x == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * x) / (Math.PI * x);
            double r = x / mid;
            double w = besselI0(KAISER_BETA * Math.sqrt(Math.max(0, 1 - r * r))) / i0b;
            h[i] = sinc * w;
            sum += h[i];
        }
        float[] lut = new float[tapBytes * 256];
        for (int k = 0; k < tapBytes; k++) {
            for (int v = 0; v < 256; v++) {
                double acc = 0;
                for (int j = 0; j < 8; j++) {           // MSB is the earliest bit
                    int bit = (v >> (7 - j)) & 1;
                    acc += (bit == 1 ? 1 : -1) * h[k * 8 + j];
                }
                lut[k * 256 + v] = (float) (acc / sum);
            }
        }
        return lut;
    }

    private static double besselI0(double x) {
        double sum = 1, term = 1, q = x * x / 4;
        for (int k = 1; k < 60; k++) { term *= q / (k * (double) k); sum += term; if (term < 1e-12 * sum) break; }
        return sum;
    }

    // ----------------------------------------------------------------- parsing

    /** Returns {channels, rate, lsbFirst, bytesPerChannel, blockSize, dataStart}. */
    private long[] parseDsf() throws IOException {
        ByteBuffer b = ByteBuffer.allocate(28 + 52 + 12).order(ByteOrder.LITTLE_ENDIAN);
        if (readFully(ch, b, 0) < b.capacity()) throw new IOException("Truncated DSF");
        long dsdSize = b.getLong(4);
        long fmtPos = dsdSize;                         // normally 28
        ByteBuffer f = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
        readFully(ch, f, fmtPos);
        if (!ascii(f, 0).equals("fmt ")) throw new IOException("DSF: no fmt chunk");
        long fmtSize = f.getLong(4);
        int formatId = f.getInt(16);
        int chanNum = f.getInt(24);
        long rate = f.getInt(28) & 0xffffffffL;
        int bits = f.getInt(32);
        long samples = f.getLong(36);
        int block = f.getInt(44);
        if (formatId != 0) throw new IOException("DSF: unsupported format");
        if (block <= 0) block = 4096;
        ByteBuffer d = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        long dataPos = fmtPos + fmtSize;
        readFully(ch, d, dataPos);
        if (!ascii(d, 0).equals("data")) throw new IOException("DSF: no data chunk");
        long dataSize = d.getLong(4) - 12;
        long bpc = (samples + 7) / 8;
        long maxBpc = dataSize / chanNum;
        if (bpc > maxBpc) bpc = maxBpc;
        return new long[]{chanNum, rate, bits == 1 ? 1 : 0, bpc, block, dataPos + 12};
    }

    /**
     * Returns {channels, rate, bytesPerChannel, dataStart, isDst, dstFrames, dstEnd}.
     * For DST, dataStart is the first sub-chunk inside the "DST " chunk.
     */
    private long[] parseDff() throws IOException {
        long fileLen = ch.size();
        ByteBuffer hdr = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        long pos = 16;                                  // after "FRM8" size "DSD "
        int chans = 0; long rate = 0; long dataStart = -1, dataLen = 0;
        boolean dstCmpr = false, dstChunk = false;
        long dstFrames = 0, dstEndPos = 0;
        while (pos + 12 <= fileLen) {
            hdr.clear(); hdr.limit(12);
            readFully(ch, hdr, pos);
            String id = ascii(hdr, 0);
            long size = hdr.getLong(4);
            long body = pos + 12;
            if (size < 0) break;
            if (id.equals("PROP")) {
                long p = body + 4, end = body + size;   // skip "SND "
                while (p + 12 <= end) {
                    hdr.clear(); hdr.limit(12);
                    readFully(ch, hdr, p);
                    String sid = ascii(hdr, 0);
                    long ssize = hdr.getLong(4);
                    ByteBuffer s = ByteBuffer.allocate((int) Math.max(0, Math.min(ssize, 16))).order(ByteOrder.BIG_ENDIAN);
                    readFully(ch, s, p + 12);
                    if (sid.equals("FS  ") && ssize >= 4) rate = s.getInt(0) & 0xffffffffL;
                    else if (sid.equals("CHNL") && ssize >= 2) chans = s.getShort(0) & 0xffff;
                    else if (sid.equals("CMPR") && ssize >= 4) dstCmpr = ascii(s, 0).equals("DST ");
                    p += 12 + ssize + (ssize & 1);
                }
            } else if (id.equals("DSD ")) {
                dataStart = body; dataLen = size; break;
            } else if (id.equals("DST ")) {
                dstChunk = true;
                dstEndPos = Math.min(body + size, fileLen);
                // first sub-chunk should be FRTE: number of frames (u32), frame rate (u16)
                hdr.clear(); hdr.limit(16);
                readFully(ch, hdr, body);
                if (ascii(hdr, 0).equals("FRTE")) {
                    long fsz = hdr.getLong(4);
                    dstFrames = hdr.getInt(12) & 0xffffffffL;
                    dataStart = body + 12 + fsz + (fsz & 1);
                } else {
                    dataStart = body;
                }
                break;
            }
            pos = body + size + (size & 1);
        }
        if (chans == 0 || rate == 0 || dataStart < 0) throw new IOException("DFF: missing chunks");
        if (dstChunk || dstCmpr) {
            if (!dstChunk) throw new IOException("DFF: no DST data");
            if (dstFrames <= 0 || dstFrames > 50_000_000L) throw new IOException("DFF: bad DST frame count");
            return new long[]{chans, rate, 0, dataStart, 1, dstFrames, dstEndPos};
        }
        if (dataStart + dataLen > fileLen) dataLen = fileLen - dataStart;
        return new long[]{chans, rate, dataLen / chans, dataStart, 0, 0, 0};
    }

    // ------------------------------------------------------------------- DST

    /** Finds frame f's data position by scanning DSTF chunks up to it. */
    private boolean locateDst(int f) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
        while (dstScanned <= f && dstPos + 12 <= dstEnd) {
            h.clear();
            if (readFully(ch, h, dstPos) < 12) break;
            String id = ascii(h, 0);
            long size = h.getLong(4);
            if (size < 0) break;
            if (id.equals("DSTF")) {
                dstOff[dstScanned] = dstPos + 12;
                dstLen[dstScanned] = (int) Math.min(size, 16 * 1024 * 1024);
                dstScanned++;
            }
            dstPos += 12 + size + (size & 1);
        }
        return f < dstScanned;
    }

    /** Returns decoded, byte-interleaved DSD for frame f (silence if missing or broken). */
    private byte[] dstFrame(int f) throws IOException {
        for (int i = 0; i < 2; i++) if (dstCacheIdx[i] == f) return dstCache[i];
        int slot = dstCacheNext; dstCacheNext ^= 1;
        int total = dstFrameBytes * channels;
        if (dstCache[slot] == null) dstCache[slot] = new byte[total];
        byte[] out = dstCache[slot];
        boolean ok = false;
        if (locateDst(f)) {
            int n = dstLen[f];
            if (dstIn.length < n) dstIn = new byte[n];
            int got = readFully(ch, ByteBuffer.wrap(dstIn, 0, n), dstOff[f]);
            try {
                dst.decode(dstIn, got, out);
                ok = true;
            } catch (IOException e) {
                ok = false;
            }
        }
        if (!ok) java.util.Arrays.fill(out, SILENCE);
        dstCacheIdx[slot] = f;
        return out;
    }

    private void readDst(long b0, long lo, long hi) throws IOException {
        long i = lo;
        while (i < hi) {
            int f = (int) (i / dstFrameBytes);
            long fStart = (long) f * dstFrameBytes;
            int from = (int) (i - fStart);
            int to = (int) Math.min(dstFrameBytes, hi - fStart);
            byte[] fr = dstFrame(f);
            for (int k = from; k < to; k++) {
                int dstIdx = (int) (fStart + k - b0);
                int base = k * channels;
                for (int c = 0; c < channels; c++) chanBuf[c][dstIdx] = fr[base + c];
            }
            i = fStart + to;
        }
    }

    private byte[] buildHeader(long dataBytes) {
        long riff = 36 + dataBytes;
        if (riff > 0xffffffffL) riff = 0xffffffffL;
        long data = Math.min(dataBytes, 0xffffffffL - 36);
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt((int) riff).put(new byte[]{'W', 'A', 'V', 'E'});
        b.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) OUT_CHANNELS)
                .putInt(OUT_RATE).putInt(OUT_RATE * OUT_BYTES_PER_FRAME)
                .putShort((short) OUT_BYTES_PER_FRAME).putShort((short) 24);
        b.put(new byte[]{'d', 'a', 't', 'a'}).putInt((int) data);
        return b.array();
    }

    private static String ascii(ByteBuffer b, int at) {
        char[] c = new char[4];
        for (int i = 0; i < 4; i++) c[i] = (char) (b.get(at + i) & 0xff);
        return new String(c);
    }

    private static int readFully(FileChannel ch, ByteBuffer b, long pos) throws IOException {
        int total = 0;
        while (b.hasRemaining()) {
            int n = ch.read(b, pos + total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }
}
