package com.shumtugle.hora;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A quiet background under a book read aloud: noise of a chosen colour, rain,
 * surf, or slow low music. Nothing here is a recording. The noises are made
 * sample by sample; the music is a short score written on the spot and played
 * by the phone's own synthesizer, a new one each time the last ends. It starts
 * with the reading, stops with it, and always stays well below the voice.
 */
final class Bed {
    static final int OFF = 0;
    static final int BROWN = 1;
    static final int PINK = 2;
    static final int WHITE = 3;
    static final int RAIN = 4;
    static final int SURF = 5;
    static final int MUSIC = 6;
    static final int KINDS = 7;

    private static final int RATE = 24000;
    private static final int BLOCK = 2400;
    /**
     * Gain at the top of the slider. Each noise is evened out to about the same
     * loudness first, so the default (35) sits some fifteen decibels under the
     * voice and the very top still a little below it.
     */
    private static final float CEILING = 0.68f;
    private static final float FADE_IN_S = 3f;
    /** After the last words: a breath of the background alone, then it fades away. */
    private static final long FADE_AFTER_MS = 2500;
    private static final long FADE_OUT_MS = 8000;

    private static Bed running;

    private final Context context;
    private final int kind;
    private volatile boolean stopped;
    /** When the fade-out begins, by the uptime clock; 0 while none is asked for. */
    private volatile long fadeFrom;
    private Thread thread;
    private MediaPlayer music;

    private Bed(Context c, int kind) {
        this.context = c.getApplicationContext();
        this.kind = kind;
    }

    /** Starts the chosen background, if any; a running one is left alone. */
    static synchronized void start(Context c) {
        int kind = Prefs.bed(c);
        if (running != null) {
            if (running.kind == kind && running.fadeFrom == 0) {
                return;
            }
            running.halt();
            running = null;
        }
        if (kind == OFF) {
            return;
        }
        running = new Bed(c, kind);
        running.begin();
        Diag.log(c, "bed: on, kind " + kind);
    }

    static synchronized void stop() {
        if (running != null) {
            running.halt();
            running = null;
        }
    }

    /**
     * The book is over: the background stays a moment after the last words,
     * then fades out and stops by itself, instead of playing on for ever.
     */
    static synchronized void fadeOut(Context c) {
        if (running == null || running.fadeFrom != 0) {
            return;
        }
        final Bed b = running;
        b.fadeFrom = SystemClock.uptimeMillis() + FADE_AFTER_MS;
        Diag.log(c, "bed: fading out after the end");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                b.fadeAndHalt();
            }
        }, "bed-fade");
        t.start();
    }

    /** 1 before the fade, falling to 0 at its end. */
    private float fadeLeft(long now) {
        long from = fadeFrom;
        if (from == 0 || now <= from) {
            return 1f;
        }
        return Math.max(0f, 1f - (now - from) / (float) FADE_OUT_MS);
    }

    /** Lowers the music step by step, then lets the background go; the noise lowers itself sample by sample. */
    private void fadeAndHalt() {
        while (!stopped) {
            long now = SystemClock.uptimeMillis();
            float left = fadeLeft(now);
            synchronized (Bed.class) {
                if (music != null) {
                    float v = Math.min(1f, 1.2f * level(context) / CEILING) * left * left;
                    try {
                        music.setVolume(v, v);
                    } catch (RuntimeException ignored) {
                        // Released in between.
                    }
                }
                if (left <= 0f) {
                    halt();
                    if (running == this) {
                        running = null;
                    }
                    Diag.log(context, "bed: faded out");
                    return;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** After a change in settings: a running background follows it at once. */
    static synchronized void refresh(Context c) {
        if (running != null) {
            running.halt();
            running = null;
            start(c);
        }
    }

    /** A playing background takes the volume from the setting again; the noise does so by itself. */
    static synchronized void retune(Context c) {
        if (running != null && running.music != null && running.fadeFrom == 0) {
            float v = Math.min(1f, 1.2f * level(c) / CEILING);
            try {
                running.music.setVolume(v, v);
            } catch (RuntimeException ignored) {
                // Released in between.
            }
        }
    }

    /** Volume from the setting, 0..1, curved so the low half of the slider stays truly quiet. */
    static float level(Context c) {
        float v = Prefs.bedVolume(c) / 100f;
        return CEILING * v * (float) Math.sqrt(v);
    }

    private void begin() {
        if (kind == MUSIC) {
            playMusic();
            return;
        }
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                noiseLoop();
            }
        }, "bed");
        thread.start();
    }

    private void halt() {
        stopped = true;
        if (thread != null) {
            thread.interrupt();
        }
        if (music != null) {
            try {
                music.release();
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            music = null;
        }
    }

    // ---- noise -------------------------------------------------------------

    private void noiseLoop() {
        AudioTrack track = null;
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, BLOCK * 4 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
            Noise n = new Noise(kind, RATE);
            float[] buf = new float[BLOCK];
            long made = 0;
            long blockMs = BLOCK * 1000L / RATE;
            while (!stopped) {
                float gain = level(context);
                n.fill(buf);
                long now = SystemClock.uptimeMillis();
                float from = fadeLeft(now);
                float to = fadeLeft(now + blockMs);
                for (int i = 0; i < BLOCK; i++) {
                    float fade = Math.min(1f, (made + i) / (FADE_IN_S * RATE));
                    float out = from + (to - from) * i / BLOCK;
                    buf[i] *= gain * fade * out * out;
                }
                made += BLOCK;
                track.write(buf, 0, BLOCK, AudioTrack.WRITE_BLOCKING);
            }
        } catch (RuntimeException e) {
            Diag.log(context, "bed: noise stopped", e);
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (RuntimeException ignored) {
                    // Never started.
                }
                track.release();
            }
        }
    }

    /**
     * The noises, sample by sample. White is flat; pink falls by half its power
     * an octave; brown falls by a quarter, a deep rumble. Rain is pink with
     * scattered soft drops; surf is brown breathing on a slow swell that grows
     * brighter on its crest.
     */
    static final class Noise {
        private final int kind;
        private final int rate;
        private final Random random = new Random();
        private double b0, b1, b2, b3, b4, b5, b6;
        private double brown;
        private double drop;
        private double dropLow;
        private double phase;
        private double period;
        private long t;

        Noise(int kind, int rate) {
            this.kind = kind;
            this.rate = rate;
            this.period = 8 + random.nextDouble() * 4;
        }

        void fill(float[] out) {
            for (int i = 0; i < out.length; i++) {
                out[i] = (float) next();
            }
        }

        private double white() {
            return random.nextGaussian() * 0.3;
        }

        private double pink(double w) {
            // A long-known filter that turns white noise pink within a fraction of a decibel.
            b0 = 0.99886 * b0 + w * 0.0555179;
            b1 = 0.99332 * b1 + w * 0.0750759;
            b2 = 0.96900 * b2 + w * 0.1538520;
            b3 = 0.86650 * b3 + w * 0.3104856;
            b4 = 0.55000 * b4 + w * 0.5329522;
            b5 = -0.7616 * b5 - w * 0.0168980;
            double p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362;
            b6 = w * 0.115926;
            return p * 0.25;
        }

        private double brown(double w) {
            brown = (brown + 0.02 * w) / 1.02;
            return brown * 3.5;
        }

        private double next() {
            double w = white();
            t++;
            switch (kind) {
                case WHITE:
                    return w * 0.45;
                case PINK:
                    return pink(w) * 0.7;
                case RAIN: {
                    double hiss = pink(w) * 0.8;
                    // Now and then a drop: a short soft burst that dies away.
                    if (random.nextDouble() < 25.0 / rate) {
                        drop = 0.3 + random.nextDouble() * 0.7;
                    }
                    drop *= 0.996;
                    dropLow += (w * drop - dropLow) * 0.35;
                    return (hiss + dropLow * 0.9) * 0.8;
                }
                case SURF: {
                    phase += 2 * Math.PI / (period * rate);
                    if (phase > 2 * Math.PI) {
                        phase -= 2 * Math.PI;
                        period = 8 + random.nextDouble() * 4;
                    }
                    double swell = 0.5 + 0.5 * Math.sin(phase);
                    swell *= swell;
                    return (brown(w) * (0.25 + 0.75 * swell) + pink(w) * 0.5 * swell * swell * swell) * 1.6;
                }
                case BROWN:
                default:
                    return brown(w) * 1.5;
            }
        }
    }

    // ---- music -------------------------------------------------------------

    /** A score of a few minutes, then the next, never the same twice. */
    private void playMusic() {
        if (stopped || fadeFrom != 0) {
            return;
        }
        try {
            File f = new File(context.getCacheDir(), "bed.mid");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(Score.write(new Random()));
            }
            final MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            p.setDataSource(f.getPath());
            p.prepare();
            float v = Math.min(1f, 1.2f * level(context) / CEILING);
            p.setVolume(v, v);
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    synchronized (Bed.class) {
                        if (music == mp) {
                            mp.release();
                            music = null;
                            playMusic();
                        }
                    }
                }
            });
            music = p;
            p.start();
        } catch (IOException | RuntimeException e) {
            Diag.log(context, "bed: no music", e);
        }
    }

    /**
     * Slow low music written as a small MIDI file: soft pads in the lower
     * octaves, one chord drifting into the next, each held for many seconds.
     */
    static final class Score {
        private static final int TICKS = 480;
        private static final int PAD = 89;
        /** A mode with no sharp edges: the notes of D dorian. */
        private static final int[] SCALE = {2, 4, 5, 7, 9, 11, 12};
        /** D below middle C: low, but still heard on a phone speaker. */
        private static final int ROOT = 50;
        private static final int SECONDS = 180;

        static byte[] write(Random r) {
            List<int[]> events = new ArrayList<int[]>();
            int at = 0;
            int degree = 0;
            int index = 0;
            while (at < SECONDS * TICKS) {
                // Neighbouring chords overlap, so they take turns on two channels:
                // the end of one never silences the same note just begun in the next.
                int ch = index++ % 2;
                int hold = (10 + r.nextInt(7)) * TICKS;
                int[] chord = {0, 2, 4};
                for (int k = 0; k < chord.length; k++) {
                    int step = degree + chord[k];
                    int pitch = ROOT + 12 * (step / SCALE.length) + SCALE[step % SCALE.length] - 2;
                    int vel = 26 + r.nextInt(16);
                    int start = at + k * (r.nextInt(TICKS) + TICKS / 2);
                    events.add(new int[] {start, 0x90 | ch, pitch, vel});
                    events.add(new int[] {at + hold, 0x80 | ch, pitch, 0});
                }
                // A low root under every other chord.
                if (r.nextBoolean()) {
                    int bass = ROOT - 12 + SCALE[degree % SCALE.length] - 2;
                    events.add(new int[] {at, 0x90 | ch, bass, 22 + r.nextInt(10)});
                    events.add(new int[] {at + hold, 0x80 | ch, bass, 0});
                }
                // Neighbouring chords: the next degree moves by a step or a third.
                int[] moves = {-2, -1, 1, 2, 3};
                degree = Math.floorMod(degree + moves[r.nextInt(moves.length)], SCALE.length);
                at += hold - (2 + r.nextInt(3)) * TICKS;
            }
            java.util.Collections.sort(events, new java.util.Comparator<int[]>() {
                @Override
                public int compare(int[] a, int[] b) {
                    return a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]);
                }
            });
            ByteArrayOutputStream track = new ByteArrayOutputStream();
            // One beat a second, so a tick is 1/480 s.
            varLen(track, 0);
            track.write(new byte[] {(byte) 0xFF, 0x51, 0x03, 0x0F, 0x42, 0x40}, 0, 6);
            for (int ch = 0; ch < 2; ch++) {
                varLen(track, 0);
                track.write(0xC0 | ch);
                track.write(PAD);
            }
            int last = 0;
            for (int[] e : events) {
                varLen(track, e[0] - last);
                last = e[0];
                track.write(e[1]);
                track.write(e[2]);
                track.write(e[3]);
            }
            // A long tail so the last chord rings out.
            varLen(track, 4 * TICKS);
            track.write(new byte[] {(byte) 0xFF, 0x2F, 0x00}, 0, 3);
            byte[] body = track.toByteArray();
            ByteArrayOutputStream file = new ByteArrayOutputStream();
            file.write(new byte[] {'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 1, (byte) (TICKS >> 8), (byte) TICKS}, 0, 14);
            file.write(new byte[] {'M', 'T', 'r', 'k'}, 0, 4);
            int n = body.length;
            file.write(new byte[] {(byte) (n >> 24), (byte) (n >> 16), (byte) (n >> 8), (byte) n}, 0, 4);
            file.write(body, 0, n);
            return file.toByteArray();
        }

        private static void varLen(ByteArrayOutputStream out, int v) {
            int buffer = v & 0x7F;
            while ((v >>= 7) > 0) {
                buffer <<= 8;
                buffer |= ((v & 0x7F) | 0x80);
            }
            while (true) {
                out.write(buffer & 0xFF);
                if ((buffer & 0x80) != 0) {
                    buffer >>= 8;
                } else {
                    break;
                }
            }
        }
    }
}
