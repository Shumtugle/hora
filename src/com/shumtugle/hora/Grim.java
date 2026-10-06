package com.shumtugle.hora;

/**
 * Makeup for a voice sample, applied once before the model hears it. The model
 * carries the sample's colour over to all of its speech, so shaping the sample
 * shapes the voice.
 *
 * Timbre is a tilt around the middle of the speech band: warmer lifts the low
 * end and dims the top, brighter does the opposite. Pitch moves the sample by
 * semitones while keeping its length: stretch in time, then resample back.
 */
final class Grim {
    static final int TIMBRE_MIN = -10;
    static final int TIMBRE_MAX = 10;
    static final int PITCH_MIN = -4;
    static final int PITCH_MAX = 4;
    static final int AIR_MAX = 10;
    /** Breath noise at the top of the air scale, relative to the speech level. */
    private static final double AIR_AT_MAX = 0.12;
    private static final double AIR_HZ = 4500.0;

    /** Shelf gains at the extremes of the timbre scale, in dB. */
    private static final double HIGH_DB_AT_MAX = 6.0;
    private static final double LOW_DB_AT_MAX = 5.0;
    private static final double LOW_SHELF_HZ = 280.0;
    private static final double HIGH_SHELF_HZ = 2600.0;
    private static final float PEAK_LIMIT = 0.95f;

    private Grim() {
    }

    static boolean isPlain(int timbre, int pitch) {
        return timbre == 0 && pitch == 0;
    }

    /**
     * As below, with air: breath noise above the speech band, following the
     * loudness of the speech. The model copies breathiness from its sample as
     * faithfully as it copies colour, so a breathy sample makes a breathy voice.
     */
    static float[] apply(float[] x, int rate, int timbre, int pitch, int air) {
        float[] y = apply(x, rate, timbre, pitch);
        if (y == null || y.length == 0 || air <= 0) {
            return y;
        }
        y = y == x ? x.clone() : y;
        double gain = AIR_AT_MAX * Math.min(air, AIR_MAX) / AIR_MAX;
        // A fixed seed: the same setting always gives the same voice.
        java.util.Random random = new java.util.Random(7);
        float[] noise = new float[y.length];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (float) random.nextGaussian();
        }
        // Fourth-order high-pass: two second-order sections.
        noise = highPass(highPass(noise, rate, AIR_HZ), rate, AIR_HZ);
        double env = 0;
        double k = Math.exp(-1.0 / (0.008 * rate));
        float peak = 0f;
        for (int i = 0; i < y.length; i++) {
            env = k * env + (1 - k) * y[i] * y[i];
            y[i] += (float) (noise[i] * Math.sqrt(env) * gain);
            peak = Math.max(peak, Math.abs(y[i]));
        }
        if (peak > PEAK_LIMIT) {
            float s = PEAK_LIMIT / peak;
            for (int i = 0; i < y.length; i++) {
                y[i] *= s;
            }
        }
        return y;
    }

    /** Second-order high-pass (bilinear transform, Butterworth quality). */
    private static float[] highPass(float[] x, int rate, double hz) {
        double w = 2 * Math.PI * hz / rate;
        double cos = Math.cos(w);
        double alpha = Math.sin(w) / (2 * Math.sqrt(0.5));
        double a0 = 1 + alpha;
        double b0 = (1 + cos) / 2 / a0;
        double b1 = -(1 + cos) / a0;
        double b2 = (1 + cos) / 2 / a0;
        double a1 = -2 * cos / a0;
        double a2 = (1 - alpha) / a0;
        float[] y = new float[x.length];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x[i];
            y2 = y1;
            y1 = v;
            y[i] = (float) v;
        }
        return y;
    }

    /** Returns a new buffer; the input is left untouched. */
    static float[] apply(float[] x, int rate, int timbre, int pitch) {
        if (x == null || x.length == 0 || isPlain(timbre, pitch)) {
            return x;
        }
        double rmsBefore = rms(x);
        float[] y = x.clone();
        if (timbre != 0) {
            double t = clamp(timbre, TIMBRE_MIN, TIMBRE_MAX) / (double) TIMBRE_MAX;
            // Warmer is negative: more low end, less top.
            y = shelf(y, rate, LOW_SHELF_HZ, -t * LOW_DB_AT_MAX, false);
            y = shelf(y, rate, HIGH_SHELF_HZ, t * HIGH_DB_AT_MAX, true);
        }
        if (pitch != 0) {
            y = shift(y, rate, clamp(pitch, PITCH_MIN, PITCH_MAX));
        }
        // Loudness stays where it was, so the model does not hear a louder or quieter speaker.
        double rmsAfter = rms(y);
        if (rmsAfter > 1e-9 && rmsBefore > 1e-9) {
            float g = (float) (rmsBefore / rmsAfter);
            float peak = 0f;
            for (int i = 0; i < y.length; i++) {
                y[i] *= g;
                peak = Math.max(peak, Math.abs(y[i]));
            }
            if (peak > PEAK_LIMIT) {
                float k = PEAK_LIMIT / peak;
                for (int i = 0; i < y.length; i++) {
                    y[i] *= k;
                }
            }
        }
        return y;
    }

    /** Pitch shift by semitones, length preserved. */
    private static float[] shift(float[] x, int rate, int semitones) {
        double r = Math.pow(2.0, semitones / 12.0);
        // Longer by r at the same pitch, then read r times faster: same length, pitch times r.
        float[] longer = TimeStretch.apply(x, rate, (float) (1.0 / r));
        if (r > 1.0) {
            // Reading faster folds the top of the band down; cut it first.
            longer = lowpass(longer, rate, 0.45 * rate / r);
            longer = lowpass(longer, rate, 0.45 * rate / r);
        }
        int n = x.length;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double p = i * r;
            int k = (int) p;
            if (k + 1 >= longer.length) {
                break;
            }
            // Cubic (Catmull-Rom): linear reading would dull the top of the band.
            double f = p - k;
            double y0 = longer[Math.max(0, k - 1)];
            double y1 = longer[k];
            double y2 = longer[k + 1];
            double y3 = longer[Math.min(longer.length - 1, k + 2)];
            out[i] = (float) (y1 + 0.5 * f * (y2 - y0 + f * (2 * y0 - 5 * y1 + 4 * y2 - y3
                    + f * (3 * (y1 - y2) + y3 - y0))));
        }
        return out;
    }

    /** Second-order shelf (bilinear transform, slope 1). */
    private static float[] shelf(float[] x, int rate, double hz, double db, boolean high) {
        if (Math.abs(db) < 0.05) {
            return x;
        }
        double a = Math.pow(10.0, db / 40.0);
        double w = 2 * Math.PI * hz / rate;
        double cs = Math.cos(w);
        double alpha = Math.sin(w) / 2 * Math.sqrt(2.0);
        double sq = 2 * Math.sqrt(a) * alpha;
        double b0, b1, b2, a0, a1, a2;
        if (high) {
            b0 = a * ((a + 1) + (a - 1) * cs + sq);
            b1 = -2 * a * ((a - 1) + (a + 1) * cs);
            b2 = a * ((a + 1) + (a - 1) * cs - sq);
            a0 = (a + 1) - (a - 1) * cs + sq;
            a1 = 2 * ((a - 1) - (a + 1) * cs);
            a2 = (a + 1) - (a - 1) * cs - sq;
        } else {
            b0 = a * ((a + 1) - (a - 1) * cs + sq);
            b1 = 2 * a * ((a - 1) - (a + 1) * cs);
            b2 = a * ((a + 1) - (a - 1) * cs - sq);
            a0 = (a + 1) + (a - 1) * cs + sq;
            a1 = -2 * ((a - 1) + (a + 1) * cs);
            a2 = (a + 1) + (a - 1) * cs - sq;
        }
        return biquad(x, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
    }

    private static float[] lowpass(float[] x, int rate, double hz) {
        double w = 2 * Math.PI * Math.min(hz, 0.49 * rate) / rate;
        double cs = Math.cos(w);
        double alpha = Math.sin(w) / (2 * 0.7071);
        double a0 = 1 + alpha;
        return biquad(x, (1 - cs) / 2 / a0, (1 - cs) / a0, (1 - cs) / 2 / a0,
                -2 * cs / a0, (1 - alpha) / a0);
    }

    private static float[] biquad(float[] x, double b0, double b1, double b2, double a1, double a2) {
        float[] y = new float[x.length];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x[i];
            y2 = y1;
            y1 = v;
            y[i] = (float) v;
        }
        return y;
    }

    private static double rms(float[] x) {
        double s = 0;
        for (float v : x) {
            s += v * (double) v;
        }
        return Math.sqrt(s / Math.max(1, x.length));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
