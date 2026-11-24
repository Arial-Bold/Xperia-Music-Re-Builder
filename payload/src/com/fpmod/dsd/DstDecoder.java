package com.fpmod.dsd;

import java.io.IOException;

/**
 * Direct Stream Transfer (DST) decoder, ISO/IEC 14496-3 subpart 10.
 * Java port of FFmpeg's libavcodec/dstdec.c by Peter Ross (LGPL-2.1-or-later).
 *
 * Decodes one DST frame (1/75 s) into byte-interleaved DSD, MSB first:
 * out[(i / 8) * channels + ch], bit (7 - i % 8).
 */
final class DstDecoder {
    private static final int MAX_CHANNELS = 6;
    private static final int MAX_ELEMENTS = 2 * MAX_CHANNELS;

    private static final int[][] FSETS_CODE_PRED = {{-8}, {-16, 8}, {-9, -5, 6}};
    private static final int[][] PROBS_CODE_PRED = {{-8}, {-16, 8}, {-24, 24, -8}};

    private static final class Table {
        int elements;
        final int[] length = new int[MAX_ELEMENTS];
        final int[][] coeff = new int[MAX_ELEMENTS][128];
    }

    private final int channels;
    private final int samplesPerFrame;     // bits per channel per frame
    private final Table fsets = new Table(), probs = new Table();
    private final short[][] filter = new short[MAX_ELEMENTS][16 * 256];   // [element][tap byte * 256 + status byte]
    private final long[] status0 = new long[MAX_CHANNELS], status1 = new long[MAX_CHANNELS];
    private final int[] mapF = new int[MAX_CHANNELS], mapP = new int[MAX_CHANNELS];
    private final boolean[] halfProb = new boolean[MAX_CHANNELS];

    // bit reader
    private byte[] buf;
    private int bufLen;
    private long bitPos;
    // arithmetic decoder
    private int acA, acC;

    DstDecoder(int channels, long dsdRate) throws IOException {
        if (channels < 1 || channels > MAX_CHANNELS) throw new IOException("DST: unsupported channel count " + channels);
        long fs44 = dsdRate / 44100;
        samplesPerFrame = (int) (588 * fs44);
        if (samplesPerFrame <= 0 || (samplesPerFrame & 7) != 0 || dsdRate > 512L * 44100) throw new IOException("DST: unsupported rate " + dsdRate);
        this.channels = channels;
    }

    /** Bytes per channel in one decoded frame. */
    int frameBytesPerChannel() { return samplesPerFrame / 8; }

    /** Decodes frame data (len bytes) into out (frameBytesPerChannel * channels bytes). */
    void decode(byte[] data, int len, byte[] out) throws IOException {
        int nbytes = samplesPerFrame / 8;
        int total = nbytes * channels;
        if (len <= 1) throw new IOException("DST: empty frame");
        buf = data; bufLen = len; bitPos = 0;

        if (bit() == 0) {                       // uncompressed frame
            bit();
            if (bits(6) != 0) throw new IOException("DST: bad uncompressed frame");
            int n = Math.min(len - 1, total);
            System.arraycopy(data, 1, out, 0, n);
            java.util.Arrays.fill(out, n, total, (byte) 0x69);
            return;
        }

        // Segmentation: only "same segmentation, end of channel" is used in practice
        if (bit() == 0 || bit() == 0 || bit() == 0) throw new IOException("DST: unsupported segmentation");

        boolean sameMap = bit() == 1;
        readMap(fsets, mapF);
        if (sameMap) {
            probs.elements = fsets.elements;
            System.arraycopy(mapF, 0, mapP, 0, MAX_CHANNELS);
        } else {
            readMap(probs, mapP);
        }

        for (int ch = 0; ch < channels; ch++) halfProb[ch] = bit() == 1;

        readTable(fsets, FSETS_CODE_PRED, 7, 9, true, 0);
        readTable(probs, PROBS_CODE_PRED, 6, 7, false, 1);

        if (bit() != 0) throw new IOException("DST: bad arithmetic data marker");
        acA = 4095;
        acC = bits(12);

        buildFilter();

        for (int ch = 0; ch < channels; ch++) {
            status0[ch] = 0xAAAAAAAAAAAAAAAAL;
            status1[ch] = 0xAAAAAAAAAAAAAAAAL;
        }
        java.util.Arrays.fill(out, 0, total, (byte) 0);

        acGet(probDstXBit(fsets.coeff[0][0]));   // DST_X_Bit, unused

        final int chs = channels;
        for (int i = 0; i < samplesPerFrame; i++) {
            final int byteBase = (i >> 3) * chs;
            final int shift = 7 - (i & 7);
            for (int ch = 0; ch < chs; ch++) {
                final int felem = mapF[ch];
                final short[] f = filter[felem];
                final long s0 = status0[ch], s1 = status1[ch];
                int sum = f[(int) (s0) & 0xff] + f[256 + ((int) (s0 >>> 8) & 0xff)]
                        + f[512 + ((int) (s0 >>> 16) & 0xff)] + f[768 + ((int) (s0 >>> 24) & 0xff)]
                        + f[1024 + ((int) (s0 >>> 32) & 0xff)] + f[1280 + ((int) (s0 >>> 40) & 0xff)]
                        + f[1536 + ((int) (s0 >>> 48) & 0xff)] + f[1792 + ((int) (s0 >>> 56) & 0xff)]
                        + f[2048 + ((int) (s1) & 0xff)] + f[2304 + ((int) (s1 >>> 8) & 0xff)]
                        + f[2560 + ((int) (s1 >>> 16) & 0xff)] + f[2816 + ((int) (s1 >>> 24) & 0xff)]
                        + f[3072 + ((int) (s1 >>> 32) & 0xff)] + f[3328 + ((int) (s1 >>> 40) & 0xff)]
                        + f[3584 + ((int) (s1 >>> 48) & 0xff)] + f[3840 + ((int) (s1 >>> 56) & 0xff)];
                final short predict = (short) sum;

                int prob;
                if (!halfProb[ch] || i >= fsets.length[felem]) {
                    int pelem = mapP[ch];
                    int index = Math.abs((int) predict) >> 3;
                    int lim = probs.length[pelem] - 1;
                    prob = probs.coeff[pelem][index < lim ? index : lim];
                } else {
                    prob = 128;
                }

                int residual = acGet(prob);
                int v = ((predict >> 15) ^ residual) & 1;
                out[byteBase + ch] |= (byte) (v << shift);

                status1[ch] = (s1 << 1) | (s0 >>> 63);
                status0[ch] = (s0 << 1) | v;
            }
        }
    }

    // ---------------------------------------------------------------- pieces

    private void readMap(Table t, int[] map) throws IOException {
        t.elements = 1;
        map[0] = 0;
        if (bit() == 0) {
            for (int ch = 1; ch < channels; ch++) {
                int nb = log2(t.elements) + 1;
                map[ch] = bits(nb);
                if (map[ch] == t.elements) {
                    t.elements++;
                    if (t.elements >= MAX_ELEMENTS) throw new IOException("DST: too many elements");
                } else if (map[ch] > t.elements) {
                    throw new IOException("DST: bad map");
                }
            }
        } else {
            java.util.Arrays.fill(map, 0);
        }
    }

    private void readTable(Table t, int[][] codePred, int lengthBits, int coeffBits, boolean signed, int offset) throws IOException {
        for (int i = 0; i < t.elements; i++) {
            t.length[i] = bits(lengthBits) + 1;
            if (bit() == 0) {
                for (int j = 0; j < t.length[i]; j++) t.coeff[i][j] = (signed ? sbits(coeffBits) : bits(coeffBits)) + offset;
            } else {
                int method = bits(2);
                if (method == 3) throw new IOException("DST: bad coding method");
                for (int j = 0; j < method + 1; j++)
                    t.coeff[i][j] = (signed ? sbits(coeffBits) : bits(coeffBits)) + offset;
                int lsbSize = bits(3);
                for (int j = method + 1; j < t.length[i]; j++) {
                    int x = 0;
                    for (int k = 0; k < method + 1; k++) x += codePred[method][k] * t.coeff[i][j - k - 1];
                    int c = srGolomb(lsbSize);
                    if (x >= 0) c -= (x + 4) / 8;
                    else c += (-x + 3) / 8;
                    if (!signed && (c < offset || c >= offset + (1 << coeffBits))) throw new IOException("DST: bad coefficient");
                    t.coeff[i][j] = c;
                }
            }
        }
    }

    private void buildFilter() throws IOException {
        for (int i = 0; i < fsets.elements; i++) {
            int length = fsets.length[i];
            int[] coeff = fsets.coeff[i];
            for (int j = 0; j < 16; j++) {
                int total = Math.max(0, Math.min(8, length - j * 8));
                short[] row = filter[i];
                int base = j * 256;
                for (int k = 0; k < 256; k++) {
                    long v = 0;
                    for (int l = 0; l < total; l++) v += (((k >> l) & 1) * 2 - 1) * (long) coeff[j * 8 + l];
                    if ((short) v != v) throw new IOException("DST: filter overflow");
                    row[base + k] = (short) v;
                }
            }
        }
    }

    private int acGet(int p) {
        int a = acA;
        int k = (a >>> 8) | ((a >>> 7) & 1);
        int q = k * p;
        int aq = a - q;
        int e;
        if (Integer.compareUnsigned(acC, aq) < 0) {
            e = 1;
            a = aq;
        } else {
            e = 0;
            a = q;
            acC -= aq;
        }
        if (a < 2048) {
            int n = 11 - log2(a);
            a <<= n;
            acC = (acC << n) | bits(n);
        }
        acA = a;
        return e;
    }

    private static int probDstXBit(int c) {
        return (Integer.reverse(c & 127) >>> 24 >> 1) + 1;
    }

    private int srGolomb(int k) throws IOException {
        // JPEG-LS style: unary prefix (zeros ending in a one), then k bits
        int q = 0;
        while (bit() == 0) {
            if (++q > 32768 || bitPos > (long) bufLen * 8 + 64) throw new IOException("DST: bad golomb code");
        }
        int v = (q << k) | (k > 0 ? bits(k) : 0);
        if (v != 0 && bit() == 1) v = -v;
        return v;
    }

    private static int log2(int x) {
        return x <= 0 ? 0 : 31 - Integer.numberOfLeadingZeros(x);
    }

    // Bits beyond the end of the frame read as zero, like FFmpeg's padded buffers.
    private int bit() {
        long p = bitPos++;
        int idx = (int) (p >>> 3);
        if (idx >= bufLen) return 0;
        return (buf[idx] >> (7 - (int) (p & 7))) & 1;
    }

    private int bits(int n) {
        if (n == 0) return 0;
        long p = bitPos;
        bitPos = p + n;
        int idx = (int) (p >>> 3);
        int off = (int) (p & 7);
        // gather up to 32 bits starting at byte idx
        long w = 0;
        for (int i = 0; i < 5; i++) {
            int bi = idx + i;
            w = (w << 8) | (bi < bufLen ? (buf[bi] & 0xff) : 0);
        }
        return (int) ((w >>> (40 - off - n)) & ((1L << n) - 1));
    }

    private int sbits(int n) {
        int v = bits(n);
        return (v << (32 - n)) >> (32 - n);
    }
}
