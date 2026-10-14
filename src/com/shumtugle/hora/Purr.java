package com.shumtugle.hora;

/**
 * A short purr that stands for a notification where it must not be read aloud: on a speaker,
 * in a car. Made here, not recorded: tonal pips of a few milliseconds, low and soft, closer at
 * first and spreading out, a little lower each time, half a second in all, with no ringing tail.
 */
final class Purr {
    private Purr() {
    }

    /** The purr at the given rate, its peak at the given level. */
    static float[] make(int rate, float peak) {
        float[] y = new float[(int) (rate * 0.5f)];
        double t = 0;
        double step = 0.038;
        double f = 480;
        while (t < 0.42) {
            double amp = t < 0.3 ? 1.0 : 0.8 - (t - 0.3) * 3;
            pip(y, rate, (int) (t * rate), f, amp);
            t += step;
            step *= 1.03;
            f *= 0.995;
        }
        // A short soft low-pass, so nothing clicks on a small speaker.
        float[] out = new float[y.length];
        double norm = 0;
        for (int k = 0; k < 24; k++) {
            norm += Math.exp(-k / 6.0);
        }
        float max = 1e-6f;
        for (int i = 0; i < y.length; i++) {
            double s = 0;
            for (int k = 0; k < 24 && i - k + 12 >= 0; k++) {
                int j = i - k + 12;
                if (j < y.length) {
                    s += y[j] * Math.exp(-k / 6.0);
                }
            }
            out[i] = (float) (s / norm);
            max = Math.max(max, Math.abs(out[i]));
        }
        for (int i = 0; i < out.length; i++) {
            out[i] = out[i] / max * peak;
        }
        return out;
    }

    private static void pip(float[] y, int rate, int at, double f, double amp) {
        int n = (int) (rate * 0.011);
        int rise = Math.max(1, (int) (rate * 0.0015));
        for (int i = 0; i < n && at + i < y.length; i++) {
            double t = i / (double) rate;
            double env = Math.exp(-t / (0.011 / 3.2)) * (i < rise ? i / (double) rise : 1.0);
            y[at + i] += (float) (amp * env * (Math.sin(2 * Math.PI * f * t) + 0.35 * Math.sin(4 * Math.PI * f * t + 0.6)));
        }
    }
}
