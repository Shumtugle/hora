package com.shumtugle.hora;

import java.util.Random;

/**
 * Three more backgrounds, made sample by sample from nothing but numbers: a
 * fire, a record, and a soft beat. None of them pretends to be a recording:
 * a fire is a low roar that flickers with dry crackles and now and then a log
 * that pops; a record is hiss, dust and a low rumble with one tick a turn at
 * thirty-three and a third; the beat is a slow kick, snare and hats with a
 * little swing over long pad chords. Peaks are rounded off so that no crackle
 * or drum ever jumps out over a word. Plain Java, so it can be heard off the
 * phone as well.
 */
final class BedSynth {
    static final int FIRE = 0;
    static final int VINYL = 1;
    static final int BEAT = 2;

    /** Brings each kind to about the loudness of the brown noise, so the slider means the same. */
    private static final double[] SCALE = {0.238, 1.384, 0.908};
    /** Where the rounding of peaks begins: some eight times the usual loudness, so only crackles and drums meet it. */
    private static final double KNEE = 1.25;

    private final int kind;
    private final int rate;
    private final Random random = new Random();
    private long n;

    BedSynth(int kind, int rate) {
        this.kind = kind;
        this.rate = rate;
        setUp();
    }

    double next() {
        double x;
        switch (kind) {
            case FIRE:
                x = fire();
                break;
            case VINYL:
                x = vinyl();
                break;
            default:
                x = beat();
                break;
        }
        n++;
        x *= SCALE[kind];
        return KNEE * Math.tanh(x / KNEE);
    }

    // ---- small tools --------------------------------------------------------

    /** A second-order filter of the usual cookbook kind, one sample at a time. */
    static final class Biquad {
        private double b0, b1, b2, a1, a2, x1, x2, y1, y2;

        static Biquad low(double f, double rate) {
            return make(f, rate, 0.7071, 0);
        }

        static Biquad high(double f, double rate) {
            return make(f, rate, 0.7071, 1);
        }

        private static Biquad make(double f, double rate, double q, int type) {
            Biquad b = new Biquad();
            double w = 2 * Math.PI * Math.min(f, rate * 0.45) / rate;
            double cos = Math.cos(w);
            double alpha = Math.sin(w) / (2 * q);
            double a0 = 1 + alpha;
            if (type == 0) {
                b.b0 = (1 - cos) / 2 / a0;
                b.b1 = (1 - cos) / a0;
                b.b2 = (1 - cos) / 2 / a0;
            } else {
                b.b0 = (1 + cos) / 2 / a0;
                b.b1 = -(1 + cos) / a0;
                b.b2 = (1 + cos) / 2 / a0;
            }
            b.a1 = -2 * cos / a0;
            b.a2 = (1 - alpha) / a0;
            return b;
        }

        double run(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }

    /**
     * Short noise bursts at random moments, each dying away on its own and of its own size.
     * Up to a handful sound at once.
     */
    final class Bursts {
        private final double perSample;
        private final double lengthMs;
        private final double shape;
        private final double sigma;
        private final int[] left = new int[8];
        private final double[] fall = new double[8];
        private final double[] env = new double[8];

        Bursts(double perSecond, double lengthMs, double shape, double sigma) {
            this.perSample = perSecond / rate;
            this.lengthMs = lengthMs;
            this.shape = shape;
            this.sigma = sigma;
        }

        double next() {
            if (random.nextDouble() < perSample) {
                for (int i = 0; i < left.length; i++) {
                    if (left[i] <= 0) {
                        int len = Math.max(2, (int) (rate * lengthMs * (0.5 + random.nextDouble()) / 1000));
                        left[i] = len;
                        fall[i] = Math.exp(-shape / len);
                        env[i] = Math.exp(random.nextGaussian() * sigma);
                        break;
                    }
                }
            }
            double s = 0;
            for (int i = 0; i < left.length; i++) {
                if (left[i] > 0) {
                    s += random.nextGaussian() * env[i];
                    env[i] *= fall[i];
                    left[i]--;
                }
            }
            return s;
        }
    }

    // ---- the fire -----------------------------------------------------------

    private double roarAcc;
    private Biquad roarLow;
    private double flicker = 1, flickerTo = 1;
    private Bursts crackles;
    private Bursts pops;
    private Biquad crackleHigh, crackleLow, popHigh, popLow, hissHigh;

    // ---- the record ---------------------------------------------------------

    private Biquad vinylHissHigh, vinylHissLow, rumbleLow, dustHigh, vinylPopHigh, tickHigh;
    private Bursts vinylPops;
    private int turn;
    private int tickLeft;

    // ---- the beat -----------------------------------------------------------

    private static final double BPM = 84;
    private static final int[][] CHORDS = {{62, 65, 69, 72}, {60, 64, 67, 71}, {58, 62, 65, 69}, {60, 64, 67, 72}};
    private static final double[][] CHORD_HZ = new double[CHORDS.length][];

    static {
        for (int c = 0; c < CHORDS.length; c++) {
            CHORD_HZ[c] = new double[CHORDS[c].length];
            for (int i = 0; i < CHORDS[c].length; i++) {
                CHORD_HZ[c][i] = 440 * Math.pow(2, (CHORDS[c][i] - 69) / 12.0);
            }
        }
    }

    private float[] kick, snare, hat;
    private final float[][] hitSound = new float[12][];
    private final int[] hitAt = new int[12];
    private final double[] hitGain = new double[12];
    private int beatSamples;
    private long nextBeat;
    private int beatNo;
    private Biquad padLow;

    private void setUp() {
        if (kind == FIRE) {
            roarLow = Biquad.low(300, rate);
            crackles = new Bursts(9, 4, 3, 0.9);
            pops = new Bursts(0.35, 30, 4, 0.5);
            crackleHigh = Biquad.high(1800, rate);
            crackleLow = Biquad.low(7000, rate);
            popHigh = Biquad.high(400, rate);
            popLow = Biquad.low(2200, rate);
            hissHigh = Biquad.high(3000, rate);
        } else if (kind == VINYL) {
            vinylHissHigh = Biquad.high(2500, rate);
            vinylHissLow = Biquad.low(9000, rate);
            rumbleLow = Biquad.low(60, rate);
            dustHigh = Biquad.high(1500, rate);
            vinylPops = new Bursts(0.6, 3, 2, 0.6);
            vinylPopHigh = Biquad.high(600, rate);
            tickHigh = Biquad.high(800, rate);
            turn = (int) (rate * 60 / 33.333);
        } else {
            beatSamples = (int) (rate * 60 / BPM);
            kick = drum(0.35, 0);
            snare = drum(0.18, 1);
            hat = drum(0.04, 2);
            padLow = Biquad.low(900, rate);
        }
    }

    private double fire() {
        double w = random.nextGaussian();
        roarAcc = 0.995 * roarAcc + w * 0.05;
        // The flame breathes: a slow wandering of the roar's strength.
        if (n % (rate / 3) == 0) {
            flickerTo = 0.6 + random.nextDouble() * 0.7;
        }
        flicker += (flickerTo - flicker) * 0.00012 * 24000.0 / rate;
        double roar = roarLow.run(roarAcc) * flicker * 2.2;
        double crackle = crackleLow.run(crackleHigh.run(crackles.next()));
        double pop = popLow.run(popHigh.run(pops.next())) * 3;
        double hiss = hissHigh.run(random.nextGaussian()) * 0.04;
        return roar * 0.6 + crackle * 1.6 + pop + hiss;
    }

    private double vinyl() {
        double hiss = vinylHissLow.run(vinylHissHigh.run(random.nextGaussian())) * 0.12;
        double rumble = rumbleLow.run(random.nextGaussian()) * 0.6;
        double dot = random.nextDouble() < 22.0 / rate
                ? Math.exp(random.nextGaussian() * 0.8) * (random.nextBoolean() ? 1 : -1) : 0;
        double dust = dustHigh.run(dot) * 0.9;
        double pop = vinylPopHigh.run(vinylPops.next()) * 1.2;
        // One tick a turn, where the record was once scratched.
        if ((n + (long) (rate * 0.7)) % turn == 0) {
            tickLeft = 60 * rate / 24000;
        }
        double tick = 0;
        if (tickLeft > 0) {
            int k = 60 * rate / 24000 - tickLeft;
            tick = Math.exp(-k / (12.0 * rate / 24000)) * 0.9;
            tickLeft--;
        }
        tick = tickHigh.run(tick);
        double wobble = 1 + 0.03 * Math.sin(2 * Math.PI * (33.333 / 60) * n / rate);
        return (hiss + rumble + dust + pop + tick) * wobble;
    }

    /** A drum sound made once: 0 kick, 1 snare, 2 hat. */
    private float[] drum(double seconds, int which) {
        int len = (int) (rate * seconds);
        float[] out = new float[len];
        Biquad hi = Biquad.high(which == 2 ? 7000 : 1200, rate);
        Biquad lo = Biquad.low(5000, rate);
        double phase = 0;
        for (int i = 0; i < len; i++) {
            double t = i / (double) rate;
            double v;
            if (which == 0) {
                phase += 2 * Math.PI * (48 + 70 * Math.exp(-t * 28)) / rate;
                v = Math.sin(phase) * Math.exp(-t * 9);
            } else if (which == 1) {
                double noise = lo.run(hi.run(random.nextGaussian())) * 0.5;
                v = (noise + Math.sin(2 * Math.PI * 190 * t) * 0.4) * Math.exp(-t * 22);
            } else {
                v = hi.run(random.nextGaussian()) * Math.exp(-t * 90) * 0.35;
            }
            out[i] = (float) v;
        }
        return out;
    }

    private void hit(float[] sound, double gain, int delay) {
        for (int i = 0; i < hitSound.length; i++) {
            if (hitSound[i] == null) {
                hitSound[i] = sound;
                hitAt[i] = -delay;
                hitGain[i] = gain;
                return;
            }
        }
    }

    private double beat() {
        if (n >= nextBeat) {
            int bar = beatNo % 4;
            int half = beatSamples / 2;
            if (bar == 0 || bar == 2) {
                hit(kick, 0.9, 0);
            } else if (bar == 3 && beatNo % 8 == 7) {
                hit(kick, 0.9, half);
            }
            if (bar == 1 || bar == 3) {
                hit(snare, 0.55, 0);
            }
            hit(hat, beatNo % 2 == 1 ? 0.6 : 0.9, 0);
            // The off-beat hat comes a breath late: a little swing.
            hit(hat, 0.45, half + (int) (0.018 * rate));
            beatNo++;
            nextBeat += beatSamples;
        }
        double drums = 0;
        for (int i = 0; i < hitSound.length; i++) {
            float[] s = hitSound[i];
            if (s == null) {
                continue;
            }
            int at = hitAt[i]++;
            if (at >= 0) {
                if (at >= s.length) {
                    hitSound[i] = null;
                    continue;
                }
                drums += s[at] * hitGain[i];
            }
        }
        return drums + pad() * 1.4;
    }

    /** Long chords, two bars each, swelling in and out, two slightly detuned saws a note. */
    private double pad() {
        double bar2 = beatSamples * 8.0;
        double t = n / (double) rate;
        int k = (int) (n / bar2);
        double sum = 0;
        for (int which = 0; which < 2; which++) {
            int chord = k - which;
            if (chord < 0) {
                continue;
            }
            double start = chord * bar2 / rate;
            double length = bar2 / rate + 1.0;
            double tl = t - start;
            if (tl < 0 || tl > length) {
                continue;
            }
            double env = Math.min(1, tl / 1.2) * Math.min(1, Math.max(0, (length - tl) / 1.0));
            double[] freqs = CHORD_HZ[chord % CHORD_HZ.length];
            for (int i = 0; i < freqs.length; i++) {
                double f = freqs[i];
                for (int d = 0; d < 2; d++) {
                    double ph = f * (1 + (d == 0 ? -0.0012 : 0.0012)) * tl;
                    sum += (2 * (ph - Math.floor(ph)) - 1) * env * 0.05;
                }
            }
        }
        return padLow.run(sum);
    }
}
