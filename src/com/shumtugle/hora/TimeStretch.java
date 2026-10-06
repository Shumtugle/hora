package com.shumtugle.hora;

/**
 * Changes speech tempo without changing pitch (waveform-similarity overlap-add).
 * Frames of 30 ms are placed at a fixed output hop; each input frame is chosen
 * within a small window so that it continues the previous one as smoothly as
 * possible, which avoids the phasing a plain overlap-add would produce.
 */
final class TimeStretch {
    private TimeStretch() {
    }

    /** @param tempo output speed: below 1 is slower, above 1 is faster */
    static float[] apply(float[] x, int rate, float tempo) {
        if (x.length == 0 || Math.abs(tempo - 1f) < 0.01f) {
            return x;
        }
        int n = Math.max(64, rate * 30 / 1000) & ~1;
        int hop = n / 2;
        int delta = rate * 12 / 1000;
        float[] win = new float[n];
        for (int i = 0; i < n; i++) {
            win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / n));
        }
        int frames = (int) Math.ceil(x.length / (hop * (double) tempo)) + 1;
        float[] out = new float[frames * hop + n];
        float[] norm = new float[out.length];
        int prev = 0;
        for (int k = 0; k < frames; k++) {
            int nominal = (int) Math.round(k * hop * (double) tempo);
            int pos;
            if (k == 0) {
                pos = 0;
            } else {
                pos = bestMatch(x, prev + hop, nominal, delta, n);
            }
            if (pos >= x.length) {
                break;
            }
            int o = k * hop;
            for (int i = 0; i < n; i++) {
                int src = pos + i;
                float v = src < x.length ? x[src] : 0f;
                out[o + i] += v * win[i];
                norm[o + i] += win[i];
            }
            prev = pos;
        }
        int len = Math.min(out.length, (int) Math.round(x.length / (double) tempo));
        float[] y = new float[len];
        for (int i = 0; i < len; i++) {
            y[i] = norm[i] > 1e-3f ? out[i] / norm[i] : 0f;
        }
        return y;
    }

    /** Position near nominal whose frame best continues the one at target. */
    private static int bestMatch(float[] x, int target, int nominal, int delta, int n) {
        int from = Math.max(0, nominal - delta);
        int to = Math.min(x.length - 1, nominal + delta);
        if (target >= x.length || from > to) {
            return Math.max(0, Math.min(nominal, x.length - 1));
        }
        int best = Math.max(0, Math.min(nominal, x.length - 1));
        double bestScore = -Double.MAX_VALUE;
        int len = Math.min(n, x.length - target);
        for (int p = from; p <= to; p += 2) {
            double s = 0;
            int lim = Math.min(len, x.length - p);
            for (int i = 0; i < lim; i += 2) {
                s += x[p + i] * x[target + i];
            }
            if (s > bestScore) {
                bestScore = s;
                best = p;
            }
        }
        return best;
    }
}
