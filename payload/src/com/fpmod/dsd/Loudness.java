package com.fpmod.dsd;

/**
 * EBU R128 / ITU-R BS.1770-4 integrated loudness and sample peak. Pure Java.
 * K-weighting coefficients are computed for any sample rate (same formulas as libebur128).
 * Feed interleaved float samples (-1..1). Block energies are kept so several tracks can be
 * combined into an album loudness.
 */
public final class Loudness {
    private final int channels;
    private final double[] weight;
    // per-channel filter state (two biquads)
    private final double[][] z;
    private final double b0, b1, b2, a1, a2;        // pre-filter (high shelf)
    private final double c0, c1, c2, d1, d2;        // RLB high-pass
    private final int segLen;                       // 100 ms in frames
    private int segPos;
    private final double[] segSum;
    private final double[] lastSegs = new double[4]; // weighted energies of last 4 segments
    private int segCount;
    private double peak;
    private final DoubleList blocks = new DoubleList();

    public Loudness(int channels, int rate) {
        this.channels = channels;
        weight = new double[channels];
        for (int c = 0; c < channels; c++) weight[c] = channelWeight(channels, c);
        z = new double[channels][4];
        double fs = rate;

        double f0 = 1681.974450955533, G = 3.999843853973347, Q = 0.7071752369554196;
        double K = Math.tan(Math.PI * f0 / fs);
        double Vh = Math.pow(10.0, G / 20.0);
        double Vb = Math.pow(Vh, 0.4996667741545416);
        double a0 = 1.0 + K / Q + K * K;
        b0 = (Vh + Vb * K / Q + K * K) / a0;
        b1 = 2.0 * (K * K - Vh) / a0;
        b2 = (Vh - Vb * K / Q + K * K) / a0;
        a1 = 2.0 * (K * K - 1.0) / a0;
        a2 = (1.0 - K / Q + K * K) / a0;

        f0 = 38.13547087602444; Q = 0.5003270373238773;
        K = Math.tan(Math.PI * f0 / fs);
        double e0 = 1.0 + K / Q + K * K;
        c0 = 1.0; c1 = -2.0; c2 = 1.0;
        d1 = 2.0 * (K * K - 1.0) / e0;
        d2 = (1.0 - K / Q + K * K) / e0;

        segLen = Math.max(1, (int) Math.round(rate / 10.0));
        segSum = new double[channels];
    }

    /** BS.1770 weights: L R C = 1, LFE = 0, surrounds = 1.41 (5.1 order L R C LFE Ls Rs). */
    private static double channelWeight(int channels, int c) {
        if (channels == 6) { if (c == 3) return 0.0; if (c >= 4) return 1.41; }
        if (channels == 5 && c >= 3) return 1.41;
        return 1.0;
    }

    public void add(float[] interleaved, int frames) {
        final int ch = channels;
        for (int f = 0; f < frames; f++) {
            int base = f * ch;
            for (int c = 0; c < ch; c++) {
                double x = interleaved[base + c];
                double ax = x < 0 ? -x : x;
                if (ax > peak) peak = ax;
                double[] s = z[c];
                // biquad 1 (direct form II transposed)
                double y = b0 * x + s[0];
                s[0] = b1 * x - a1 * y + s[1];
                s[1] = b2 * x - a2 * y;
                // biquad 2
                double w = c0 * y + s[2];
                s[2] = c1 * y - d1 * w + s[3];
                s[3] = c2 * y - d2 * w;
                segSum[c] += w * w;
            }
            if (++segPos == segLen) endSegment();
        }
    }

    private void endSegment() {
        double e = 0;
        for (int c = 0; c < channels; c++) { e += weight[c] * segSum[c] / segLen; segSum[c] = 0; }
        segPos = 0;
        lastSegs[segCount & 3] = e;
        segCount++;
        if (segCount >= 4) {
            blocks.add((lastSegs[0] + lastSegs[1] + lastSegs[2] + lastSegs[3]) / 4.0);
        }
    }

    public double samplePeak() { return peak; }

    public double[] blockEnergies() { return blocks.toArray(); }

    /** Integrated loudness in LUFS, or -70 if silent. */
    public double integrated() { return integrated(blocks.toArray()); }

    public static double integrated(double[] energies) {
        double absThr = Math.pow(10.0, (-70.0 + 0.691) / 10.0);
        double sum = 0; int n = 0;
        for (double e : energies) if (e > absThr) { sum += e; n++; }
        if (n == 0) return -70.0;
        double relThr = (sum / n) * Math.pow(10.0, -10.0 / 10.0);
        double sum2 = 0; int n2 = 0;
        for (double e : energies) if (e > absThr && e > relThr) { sum2 += e; n2++; }
        if (n2 == 0) return -70.0;
        return -0.691 + 10.0 * Math.log10(sum2 / n2);
    }

    /** ReplayGain 2.0 gain for a loudness (reference -18 LUFS). */
    public static double gainDb(double lufs) { return -18.0 - lufs; }

    static final class DoubleList {
        private double[] a = new double[1024];
        private int n;
        void add(double v) { if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2); a[n++] = v; }
        double[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }
}
