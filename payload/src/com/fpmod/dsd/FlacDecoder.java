package com.fpmod.dsd;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Pure-Java FLAC decoder, used by the ReplayGain scan so it doesn't depend on the phone's
 * media components (some, Samsung's in particular, are slow when reading large files in many
 * small pieces). Reads the file in large blocks. Supports everything the FLAC format allows up to
 * 24 bits per sample and 8 channels; anything else throws, and the scan falls back to the system decoder.
 */
final class FlacDecoder {

    /** Receives decoded blocks: samples[channel][i], i < n, as signed integers of bitsPerSample. */
    interface Sink { void block(int[][] samples, int n) throws IOException; }

    final int sampleRate, channels, bitsPerSample;
    final long totalSamples;
    final byte[] md5 = new byte[16];

    private final FileChannel ch;
    private final long fileLen;
    private long fileOffset;              // file position of buf[0]
    private byte[] buf = new byte[1 << 20];
    private int len;                       // valid bytes in buf
    private int bi;                        // next byte to load into the bit cache
    private long cache;                    // bit cache, MSB aligned
    private int cbits;                     // valid bits in cache
    private final int maxBlock;
    private int[][] out;
    private long[] lpcTmp = new long[0];

    FlacDecoder(FileChannel ch) throws IOException {
        this.ch = ch;
        this.fileLen = ch.size();
        fileOffset = 0;
        refill(0);
        int p = 0;
        if (len >= 10 && buf[0] == 'I' && buf[1] == 'D' && buf[2] == '3') {
            int size = ((buf[6] & 0x7f) << 21) | ((buf[7] & 0x7f) << 14) | ((buf[8] & 0x7f) << 7) | (buf[9] & 0x7f);
            p = 10 + size + ((buf[5] & 0x10) != 0 ? 10 : 0);
            refill(p); p = 0;
        }
        if (len < 4 || buf[p] != 'f' || buf[p + 1] != 'L' || buf[p + 2] != 'a' || buf[p + 3] != 'C') throw new IOException("not FLAC");
        long pos = fileOffset + p + 4;
        int rate = 0, chans = 0, bps = 0, maxB = 0;
        long total = 0;
        boolean last = false, haveInfo = false;
        while (!last) {
            byte[] h = readAt(pos, 4);
            if (h.length < 4) throw new IOException("FLAC: truncated metadata");
            last = (h[0] & 0x80) != 0;
            int type = h[0] & 0x7f;
            int blen = ((h[1] & 0xff) << 16) | ((h[2] & 0xff) << 8) | (h[3] & 0xff);
            if (type == 0) {
                byte[] s = readAt(pos + 4, 34);
                if (s.length < 34) throw new IOException("FLAC: truncated STREAMINFO");
                maxB = ((s[2] & 0xff) << 8) | (s[3] & 0xff);
                rate = ((s[10] & 0xff) << 12) | ((s[11] & 0xff) << 4) | ((s[12] & 0xff) >>> 4);
                chans = ((s[12] >>> 1) & 7) + 1;
                bps = (((s[12] & 1) << 4) | ((s[13] & 0xff) >>> 4)) + 1;
                total = ((long) (s[13] & 0x0f) << 32) | ((s[14] & 0xffL) << 24) | ((s[15] & 0xffL) << 16) | ((s[16] & 0xffL) << 8) | (s[17] & 0xffL);
                System.arraycopy(s, 18, md5, 0, 16);
                haveInfo = true;
            }
            pos += 4 + blen;
            if (pos > fileLen) throw new IOException("FLAC: bad metadata");
        }
        if (!haveInfo || rate == 0) throw new IOException("FLAC: no STREAMINFO");
        if (bps > 24) throw new IOException("FLAC: more than 24 bits");
        if (chans > 8) throw new IOException("FLAC: too many channels");
        sampleRate = rate; channels = chans; bitsPerSample = bps; totalSamples = total;
        maxBlock = maxB > 0 ? maxB : 65535;
        out = new int[8][maxBlock];
        refill(pos);
    }

    /** Decodes the whole stream into the sink. */
    void decode(Sink sink) throws IOException {
        long done = 0;
        while (true) {
            if (totalSamples > 0 && done >= totalSamples) break;
            // keep at least a large frame's worth of data ahead
            int at = bytePos();
            if (len - at < 256 * 1024 && fileOffset + len < fileLen) { refill(fileOffset + at); }
            at = bytePos();
            if (len - at < 2) break;                               // end of file
            if ((buf[at] & 0xff) != 0xff || (buf[at + 1] & 0xfe) != 0xf8) {
                if (done > 0 && (totalSamples == 0 || fileLen - (fileOffset + at) < 4096)) break;   // trailing tag
                throw new IOException("FLAC: lost frame sync");
            }
            int n = frame();
            if (bi > len + 8) throw new IOException("FLAC: frame runs past the data read");
            if (totalSamples > 0 && done + n > totalSamples) n = (int) (totalSamples - done);
            sink.block(out, n);
            done += n;
        }
    }

    // ------------------------------------------------------------------ frame

    private int frame() throws IOException {
        bits(15);                                           // sync + reserved
        bits(1);                                            // blocking strategy
        int bsCode = bits(4), srCode = bits(4), chCode = bits(4), ssCode = bits(3);
        bits(1);
        // frame/sample number, UTF-8 style
        int first = bits(8);
        int extra = 0;
        if ((first & 0x80) != 0) { int m = 0x40; while ((first & m) != 0) { extra++; m >>= 1; } }
        for (int i = 0; i < extra; i++) bits(8);
        int blockSize;
        switch (bsCode) {
            case 0: throw new IOException("FLAC: reserved block size");
            case 1: blockSize = 192; break;
            case 6: blockSize = bits(8) + 1; break;
            case 7: blockSize = bits(16) + 1; break;
            default: blockSize = bsCode <= 5 ? 576 << (bsCode - 2) : 256 << (bsCode - 8);
        }
        if (srCode == 12) bits(8); else if (srCode == 13 || srCode == 14) bits(16); else if (srCode == 15) throw new IOException("FLAC: bad sample rate");
        int bps;
        switch (ssCode) {
            case 0: bps = bitsPerSample; break;
            case 1: bps = 8; break;
            case 2: bps = 12; break;
            case 4: bps = 16; break;
            case 5: bps = 20; break;
            case 6: bps = 24; break;
            default: throw new IOException("FLAC: unsupported sample size");
        }
        bits(8);                                            // CRC-8
        if (blockSize > out[0].length) { out = new int[8][blockSize]; }
        int nch;
        if (chCode < 8) nch = chCode + 1;
        else if (chCode <= 10) nch = 2;
        else throw new IOException("FLAC: reserved channel layout");
        if (nch > 8) throw new IOException("FLAC: too many channels");
        for (int c = 0; c < nch; c++) {
            boolean side = (chCode == 8 && c == 1) || (chCode == 9 && c == 0) || (chCode == 10 && c == 1);
            subframe(out[c], blockSize, bps + (side ? 1 : 0));
        }
        int[] a = out[0], b = out[1];
        switch (chCode) {
            case 8:  for (int i = 0; i < blockSize; i++) b[i] = a[i] - b[i]; break;                 // left/side
            case 9:  for (int i = 0; i < blockSize; i++) a[i] += b[i]; break;                       // side/right
            case 10:                                                                              // mid/side
                for (int i = 0; i < blockSize; i++) {
                    int side = b[i];
                    int mid = (a[i] << 1) | (side & 1);
                    a[i] = (mid + side) >> 1;
                    b[i] = (mid - side) >> 1;
                }
                break;
            default:
        }
        // byte align, then CRC-16
        cache <<= (cbits & 7); cbits -= (cbits & 7);
        bits(16);
        return blockSize;
    }

    private void subframe(int[] s, int n, int bps) throws IOException {
        if (bits(1) != 0) throw new IOException("FLAC: bad subframe");
        int type = bits(6);
        int wasted = 0;
        if (bits(1) != 0) { wasted = unary() + 1; bps -= wasted; }
        if (bps <= 0 || bps > 32) throw new IOException("FLAC: bad sample size");
        if (type == 0) {
            int v = sbits(bps);
            for (int i = 0; i < n; i++) s[i] = v;
        } else if (type == 1) {
            for (int i = 0; i < n; i++) s[i] = sbits(bps);
        } else if (type >= 8 && type <= 12) {
            int order = type - 8;
            if (order > n) throw new IOException("FLAC: bad order");
            for (int i = 0; i < order; i++) s[i] = sbits(bps);
            residual(s, n, order);
            fixed(s, n, order);
        } else if (type >= 32) {
            int order = (type & 31) + 1;
            if (order > n) throw new IOException("FLAC: bad order");
            for (int i = 0; i < order; i++) s[i] = sbits(bps);
            int precision = bits(4) + 1;
            if (precision == 16) throw new IOException("FLAC: bad LPC precision");
            int shift = sbits(5);
            if (shift < 0) throw new IOException("FLAC: negative LPC shift");
            int[] coef = new int[order];
            for (int i = 0; i < order; i++) coef[i] = sbits(precision);
            residual(s, n, order);
            lpc(s, n, order, coef, shift);
        } else {
            throw new IOException("FLAC: reserved subframe type");
        }
        if (wasted > 0) for (int i = 0; i < n; i++) s[i] <<= wasted;
    }

    private void residual(int[] s, int n, int order) throws IOException {
        int method = bits(2);
        if (method > 1) throw new IOException("FLAC: reserved residual coding");
        int paramBits = method == 0 ? 4 : 5, escape = method == 0 ? 15 : 31;
        int partOrder = bits(4);
        int parts = 1 << partOrder;
        int per = n >> partOrder;
        if (per < order || (per << partOrder) != n) throw new IOException("FLAC: bad partitions");
        int i = order;
        for (int p = 0; p < parts; p++) {
            int count = p == 0 ? per - order : per;
            int k = bits(paramBits);
            if (k == escape) {
                int raw = bits(5);
                for (int j = 0; j < count; j++) s[i++] = raw == 0 ? 0 : sbits(raw);
            } else {
                for (int j = 0; j < count; j++) {
                    int q = unary();
                    int v = (q << k) | bits(k);
                    s[i++] = (v >>> 1) ^ -(v & 1);
                }
            }
        }
    }

    private static void fixed(int[] s, int n, int order) {
        switch (order) {
            case 0: break;
            case 1: for (int i = 1; i < n; i++) s[i] += s[i - 1]; break;
            case 2: for (int i = 2; i < n; i++) s[i] += 2 * s[i - 1] - s[i - 2]; break;
            case 3: for (int i = 3; i < n; i++) s[i] += 3 * s[i - 1] - 3 * s[i - 2] + s[i - 3]; break;
            case 4: for (int i = 4; i < n; i++) s[i] += 4 * s[i - 1] - 6 * s[i - 2] + 4 * s[i - 3] - s[i - 4]; break;
            default:
        }
    }

    private static void lpc(int[] s, int n, int order, int[] coef, int shift) {
        for (int i = order; i < n; i++) {
            long sum = 0;
            for (int j = 0; j < order; j++) sum += (long) coef[j] * s[i - 1 - j];
            s[i] += (int) (sum >> shift);
        }
    }

    // ------------------------------------------------------------- bit input

    private int bits(int n) {
        if (n == 0) return 0;
        if (cbits < n) fill();
        int v = (int) (cache >>> (64 - n));
        cache <<= n;
        cbits -= n;
        return v;
    }

    private int sbits(int n) {
        int v = bits(n);
        return n == 32 ? v : (v << (32 - n)) >> (32 - n);
    }

    private int unary() {
        int z = 0;
        while (true) {
            if (cbits == 0) fill();
            int lz = Long.numberOfLeadingZeros(cache);
            if (lz < cbits) {
                z += lz;
                cache <<= lz + 1;
                cbits -= lz + 1;
                return z;
            }
            z += cbits;
            cache = 0;
            cbits = 0;
            if (z > 1 << 20) throw new IllegalStateException("FLAC: runaway code");
        }
    }

    private void fill() {
        while (cbits <= 56) {
            int b = bi < len ? buf[bi] & 0xff : 0;
            bi++;
            cache |= (long) b << (56 - cbits);
            cbits += 8;
        }
    }

    /** Index in buf of the next unread whole byte (bit position must be byte aligned). */
    private int bytePos() { return bi - (cbits >> 3); }

    /** Loads the buffer starting at a file position and resets the bit reader there. */
    private void refill(long filePos) throws IOException {
        fileOffset = filePos;
        len = 0;
        ByteBuffer b = ByteBuffer.wrap(buf);
        while (b.hasRemaining()) {
            int r = ch.read(b, filePos + b.position());
            if (r <= 0) break;
        }
        len = b.position();
        bi = 0; cache = 0; cbits = 0;
    }

    private byte[] readAt(long pos, int n) throws IOException {
        if (pos >= fileOffset && pos + n <= fileOffset + len) {
            return java.util.Arrays.copyOfRange(buf, (int) (pos - fileOffset), (int) (pos - fileOffset) + n);
        }
        ByteBuffer b = ByteBuffer.allocate(n);
        while (b.hasRemaining()) {
            int r = ch.read(b, pos + b.position());
            if (r <= 0) break;
        }
        return java.util.Arrays.copyOf(b.array(), b.position());
    }
}
