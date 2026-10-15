package com.shumtugle.hora;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;

/**
 * The microphone: listens on its own thread, hands the words over while they come and the whole
 * line once the speaker stops. One listening at a time in the whole app. What was heard never goes
 * to the journal, only how long it took and how it ended.
 */
final class Listen {
    /** Hears the listening. Every call comes on the main thread. */
    interface Heard {
        /** The words so far; they may still change. */
        void words(String sofar);

        /** How loud the room is now, from 0 to 1. */
        void level(float loud);

        /**
         * The listening is over. The line is empty when nothing was understood; failed says the
         * microphone itself let it down, so that can be told apart from silence.
         */
        void done(String line, boolean failed);
    }

    private static final String SLOT = "hear";
    private static final String MARKER = ".unpacked";
    /** A tenth of a second of sound: what the screen redraws at, and what the model is fed. */
    private static final int PIECE = Hearing.RATE / 10;
    /** However the talk goes, the microphone closes after this long. */
    private static final long MOST_MS = 30000;
    /** The model stays in memory this long after the last listening, so the next one starts at once. */
    private static final long KEEP_MS = 120000;

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The loaded model; touched only under the lock. */
    private static Hearing kept;
    private static long lastUsed;
    private static Listen current;

    private final Context app;
    private final Heard to;
    private volatile boolean stopNow;
    private volatile boolean cancelled;

    private Listen(Context app, Heard to) {
        this.app = app;
        this.to = to;
    }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Main thread only. Starts listening; an earlier listening, if any, is dropped. */
    static Listen start(Context c, Heard to) {
        if (current != null) {
            current.cancel();
        }
        final Listen l = new Listen(c.getApplicationContext(), to);
        current = l;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                l.run();
            }
        }, "listen");
        t.start();
        return l;
    }

    /** Ends now with what has been heard so far, as if the speaker had paused. */
    void stop() {
        stopNow = true;
    }

    /** Ends now and drops what was heard: done() is not called. */
    void cancel() {
        cancelled = true;
        stopNow = true;
        if (current == this) {
            current = null;
        }
    }

    private void run() {
        String line = "";
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        AudioFocusRequest focus = null;
        AudioRecord rec = null;
        long began = SystemClock.elapsedRealtime();
        String ending = "stopped";
        boolean failed = false;
        synchronized (LOCK) {
            try {
                body: {
                    if (!allowed(app)) {
                        ending = "no microphone permission";
                        failed = true;
                        break body;
                    }
                    // The model first, the microphone after: the first load may take seconds, and
                    // the microphone is not held open, lit, while nobody can be heard yet.
                    Hearing h = model();
                    if (stopNow) {
                        ending = cancelled ? "cancelled while loading" : "stopped while loading";
                        break body;
                    }
                    int min = AudioRecord.getMinBufferSize(Hearing.RATE, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT);
                    // Two seconds of room, so a slow moment on the phone loses nothing.
                    rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Hearing.RATE,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, Hearing.RATE * 4));
                    if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                        ending = "microphone not available";
                        failed = true;
                        break body;
                    }
                    // A playing book or voice is made quieter, not stopped, while the microphone is open.
                    // Whatever takes the sound back while listening (a book resumed, a word read aloud)
                    // would be heard as a request, so the listening ends with what it has.
                    focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                                @Override
                                public void onAudioFocusChange(int change) {
                                    if (change < 0) {
                                        stopNow = true;
                                    }
                                }
                            }, MAIN)
                            .build();
                    if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        Diag.log(app, "listen: the sound was not given, listening anyway");
                    }
                    rec.startRecording();
                    long opened = SystemClock.elapsedRealtime();
                    h.begin();
                    short[] pcm = new short[PIECE];
                    float[] f = new float[PIECE];
                    ending = "paused";
                    while (true) {
                        if (stopNow) {
                            ending = cancelled ? "cancelled" : "stopped";
                            break;
                        }
                        if (SystemClock.elapsedRealtime() - opened > MOST_MS) {
                            ending = "too long";
                            break;
                        }
                        int n = rec.read(pcm, 0, PIECE);
                        if (n <= 0) {
                            ending = "microphone gave nothing: " + n;
                            failed = true;
                            break;
                        }
                        double sum = 0;
                        float[] piece = n == PIECE ? f : new float[n];
                        for (int i = 0; i < n; i++) {
                            float v = pcm[i] / 32768f;
                            piece[i] = v;
                            sum += v * v;
                        }
                        final float loud = loudness(Math.sqrt(sum / n));
                        final boolean changed = h.feed(piece);
                        final String sofar = h.words();
                        post(new Runnable() {
                            @Override
                            public void run() {
                                to.level(loud);
                                if (changed) {
                                    to.words(sofar);
                                }
                            }
                        });
                        if (h.finished()) {
                            break;
                        }
                    }
                    line = cancelled ? "" : Hearing.asLine(h.end());
                }
            } catch (Throwable e) {
                Diag.log(app, "listen: failed", e);
                ending = "failed";
                failed = true;
                line = "";
            } finally {
                if (rec != null) {
                    try {
                        rec.stop();
                    } catch (IllegalStateException ignored) {
                        // Never started: nothing to stop.
                    }
                    rec.release();
                }
                if (focus != null) {
                    audio.abandonAudioFocusRequest(focus);
                }
                lastUsed = SystemClock.elapsedRealtime();
                MAIN.removeCallbacks(FORGET);
                MAIN.postDelayed(FORGET, KEEP_MS + 1000);
            }
        }
        Diag.mark(app, "listen: " + ending + " after " + (SystemClock.elapsedRealtime() - began) + " ms, "
                + (line.isEmpty() ? "nothing heard" : words(line) + " words"));
        final String result = line;
        final boolean broke = failed;
        post(new Runnable() {
            @Override
            public void run() {
                if (current == Listen.this) {
                    current = null;
                }
                to.done(result, broke);
            }
        });
    }

    /** Called with the lock held: the model, unpacked and loaded once. */
    private Hearing model() throws java.io.IOException {
        if (kept != null) {
            return kept;
        }
        long t0 = SystemClock.elapsedRealtime();
        File dir = new File(app.getFilesDir(), SLOT);
        File marker = new File(dir, MARKER);
        // Unpacked again only when the model itself changed, not with every new build of the app.
        String stamp = BuildConfigLite.assetStamp(app, SLOT + "/stamp.txt");
        if (!marker.isFile() || !stamp.equals(Voice.readSmall(marker))) {
            unpack(dir, stamp);
        }
        try {
            kept = new Hearing(dir, 2);
        } catch (RuntimeException e) {
            // A model that would not load is unpacked afresh next time rather than trusted forever.
            marker.delete();
            throw e;
        }
        Diag.mark(app, "listen: model ready in " + (SystemClock.elapsedRealtime() - t0) + " ms");
        return kept;
    }

    /**
     * Copies the model out of the app into a side folder, each file forced to the disk, then puts
     * the folder in place and marks it last: a crash or a lost battery midway leaves no marker,
     * so a half-copied model is never taken for a whole one.
     */
    private void unpack(File dir, String stamp) throws java.io.IOException {
        File side = new File(app.getFilesDir(), SLOT + ".part");
        Voice.deleteTree(side);
        if (!side.mkdirs()) {
            throw new java.io.IOException("cannot create " + side);
        }
        byte[] buf = new byte[1 << 16];
        try {
            copyOut(side, buf);
        } catch (java.io.IOException e) {
            // A full disk must not keep a dead half-copy around.
            Voice.deleteTree(side);
            throw e;
        }
        Voice.deleteTree(dir);
        if (!side.renameTo(dir)) {
            Voice.deleteTree(side);
            throw new java.io.IOException("cannot put " + side + " in place");
        }
        java.io.FileOutputStream m = new java.io.FileOutputStream(new File(dir, MARKER));
        try {
            m.write(stamp.getBytes("UTF-8"));
            m.getFD().sync();
        } finally {
            m.close();
        }
    }

    private void copyOut(File side, byte[] buf) throws java.io.IOException {
        for (String name : Hearing.FILES) {
            java.io.InputStream in = app.getAssets().open(SLOT + "/" + name);
            try {
                java.io.FileOutputStream out = new java.io.FileOutputStream(new File(side, name));
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    out.getFD().sync();
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
        }
    }

    /** Lets the model go after a while unused; a listening in progress keeps it. */
    private static final Runnable FORGET = new Runnable() {
        @Override
        public void run() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    synchronized (LOCK) {
                        if (kept != null && SystemClock.elapsedRealtime() - lastUsed >= KEEP_MS) {
                            kept.release();
                            kept = null;
                        }
                    }
                }
            }, "listen-forget").start();
        }
    };

    private void post(final Runnable r) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (!cancelled) {
                    r.run();
                }
            }
        });
    }

    /** Room loudness on a scale the eye reads: a whisper near the bottom, a raised voice near the top. */
    static float loudness(double rms) {
        if (rms <= 0) {
            return 0f;
        }
        double db = 20 * Math.log10(rms);
        return (float) Math.max(0, Math.min(1, (db + 55) / 40));
    }

    private static int words(String s) {
        return s.trim().isEmpty() ? 0 : s.trim().split("\\s+").length;
    }
}
