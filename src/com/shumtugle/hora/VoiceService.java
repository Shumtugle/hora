package com.shumtugle.hora;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the voice. Runs in its own process, so a crash in synthesis cannot take
 * the UI down, and the voice stays loaded between requests while the process lives.
 * A new request interrupts the current one.
 */
public final class VoiceService extends Service {
    private static final String ACTION_SPEAK = "com.shumtugle.hora.action.SPEAK";
    private static final String ACTION_STOP = "com.shumtugle.hora.action.STOP";
    private static final String ACTION_PROBE = "com.shumtugle.hora.action.PROBE";
    /** Sent to this app's screens when nothing is left to say. */
    static final String ACTION_IDLE = "com.shumtugle.hora.action.IDLE";
    private static final String EXTRA_TEXT = "text";
    private static final String ACTION_RECORD = "com.shumtugle.hora.RECORD";
    /** Sent to this app only while a recording goes on: done and total paragraphs, then the outcome. */
    static final String ACTION_RECORD_STATE = "com.shumtugle.hora.RECORD_STATE";
    static final String EXTRA_DONE = "done";
    static final String EXTRA_TOTAL = "total";
    /** Present when finished: the saved file's name, or empty when it failed or was not saved. */
    static final String EXTRA_FILE = "file";
    static final String EXTRA_SECONDS = "seconds";
    static final String EXTRA_ERROR = "error";
    private static final String EXTRA_SPEED = "speed";
    private static final String EXTRA_PAUSE = "pause";
    private static final String EXTRA_VOICE = "voice";
    private static final String CHANNEL = "speech";
    private static final int NOTE_ID = 1;
    private static final int PREVIEW_CHARS = 80;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicInteger pending = new AtomicInteger();

    static void speak(Context c, String text) {
        speak(c, text, 0);
    }

    /** Speaks with a given voice (1..COUNT); 0 leaves it to the narrator role. */
    static void speak(Context c, String text, int voice) {
        speak(c, text, voice, 1f);
    }

    /** The same, with the voice's own pace scaled for this phrase only. */
    static void speak(Context c, String text, int voice, float pace) {
        Intent i = new Intent(c, VoiceService.class)
                .setAction(ACTION_SPEAK)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_SPEED, Math.max(Prefs.SPEED_MIN, Prefs.speed(c) * pace))
                .putExtra(EXTRA_PAUSE, Prefs.pauseMs(c))
                .putExtra(EXTRA_VOICE, voice);
        c.startForegroundService(i);
    }

    /** Stops whatever is being spoken. */
    static void stop(Context c) {
        c.startService(new Intent(c, VoiceService.class).setAction(ACTION_STOP));
    }

    /** Synthesizes a fixed phrase at normal speed, saves it to Downloads and plays it. */
    static void probe(Context c) {
        c.startForegroundService(new Intent(c, VoiceService.class).setAction(ACTION_PROBE));
    }

    /** Reads the given text with the narrator, as the book would, and saves it to Downloads. */
    static void record(Context c, String text) {
        record(c, text, 0);
    }

    /** Records with a given voice (1..COUNT); 0 leaves it to the narrator role. */
    static void record(Context c, String text, int voice) {
        c.startForegroundService(new Intent(c, VoiceService.class).setAction(ACTION_RECORD)
                .putExtra(EXTRA_TEXT, text).putExtra(EXTRA_VOICE, voice));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** The quiet tile, or any other way of silencing all of Hora. */
    private final android.content.BroadcastReceiver hush = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            generation.incrementAndGet();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(hush, new android.content.IntentFilter(Hush.ACTION), RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(hush, new android.content.IntentFilter(Hush.ACTION));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SPEAK.equals(intent.getAction())) {
            String text = intent.getStringExtra(EXTRA_TEXT);
            startForeground(NOTE_ID, notification(text == null ? "" : text));
            if (text != null && !text.trim().isEmpty()) {
                enqueue(text,
                        intent.getFloatExtra(EXTRA_SPEED, 1.0f),
                        intent.getIntExtra(EXTRA_PAUSE, 0),
                        intent.getIntExtra(EXTRA_VOICE, 0));
                return START_NOT_STICKY;
            }
        } else if (intent != null && ACTION_PROBE.equals(intent.getAction())) {
            startForeground(NOTE_ID, notification(getString(R.string.probe)));
            runProbe(null);
            return START_NOT_STICKY;
        } else if (intent != null && ACTION_RECORD.equals(intent.getAction())) {
            startForeground(NOTE_ID, notification(getString(R.string.recording)));
            runRecord(intent.getStringExtra(EXTRA_TEXT), intent.getIntExtra(EXTRA_VOICE, 0));
            return START_NOT_STICKY;
        } else {
            // Stop, or an unknown request: interrupt whatever is playing.
            generation.incrementAndGet();
        }
        finishIfIdle();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(hush);
        generation.incrementAndGet();
        worker.shutdown();
        super.onDestroy();
    }

    private void enqueue(final String text, final float speed, final int pauseMs, final int voiceNo) {
        final int gen = generation.incrementAndGet();
        pending.incrementAndGet();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                Awake.hold(VoiceService.this);
                try {
                    if (gen != generation.get()) {
                        return;
                    }
                    Voice voice = Voice.get(VoiceService.this);
                    try {
                        voice.prepare();
                    } catch (Voice.Missing m) {
                        // No voice pack yet: the phone's own voice says it, so the answer is not lost.
                        SpareVoice.say(VoiceService.this, TextPrep.clean(text), new Voice.Cancel() {
                            @Override
                            public boolean cancelled() {
                                return gen != generation.get();
                            }
                        });
                        return;
                    }
                    String spoken = voice.normalize(Lexicon.get(VoiceService.this).applyRules(TextPrep.clean(text)));
                    // Speech asks for the floor and gives way when another sound takes it.
                    android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                    android.media.AudioFocusRequest focus = new android.media.AudioFocusRequest.Builder(
                            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                    // The assistant's usage, as the voice's own sound has.
                                    .setUsage(16)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setOnAudioFocusChangeListener(new android.media.AudioManager.OnAudioFocusChangeListener() {
                                @Override
                                public void onAudioFocusChange(int change) {
                                    if (change == android.media.AudioManager.AUDIOFOCUS_LOSS
                                            || change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                                        if (gen == generation.get()) {
                                            generation.incrementAndGet();
                                            Diag.mark(VoiceService.this, "speech: gave way to another sound");
                                        }
                                    }
                                }
                            }, main)
                            .build();
                    audio.requestAudioFocus(focus);
                    Hush.begin(VoiceService.this, new Runnable() {
                        @Override
                        public void run() {
                            if (gen == generation.get()) {
                                generation.incrementAndGet();
                                Diag.mark(VoiceService.this, "speech: stopped by the headphone button");
                            }
                        }
                    });
                    try {
                        voice.speak(spoken, SpeechLanguage.locale(), speed, pauseMs,
                                new Voice.Cancel() {
                                    @Override
                                    public boolean cancelled() {
                                        return gen != generation.get();
                                    }
                                }, voiceNo);
                    } finally {
                        Hush.end(VoiceService.this);
                        audio.abandonAudioFocusRequest(focus);
                    }
                    float r = voice.takeRatio();
                    if (r > 0) {
                        Prefs.setLastRatio(VoiceService.this, r);
                    }
                } catch (Throwable t) {
                    // Nothing to report to: the request is dropped.
                } finally {
                    Awake.let(VoiceService.this);
                    pending.decrementAndGet();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            finishIfIdle();
                        }
                    });
                }
            }
        });
    }

    /** Renders the probe phrase, or the given text at the book's pace, saves it and plays it. */
    private void runProbe(final String given) {
        final int gen = generation.incrementAndGet();
        pending.incrementAndGet();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    Voice voice = Voice.get(VoiceService.this);
                    voice.prepare();
                    boolean own = given != null && !given.trim().isEmpty();
                    String text = own ? given : SpeechLanguage.resources(VoiceService.this)
                            .getString(R.string.probe_text);
                    long t0 = System.nanoTime();
                    float[] audio = own
                            ? voice.render(voice.normalize(Lexicon.get(VoiceService.this).applyRules(TextPrep.clean(text))),
                                    Prefs.speedShared(VoiceService.this), Prefs.pauseMsShared(VoiceService.this))
                            : voice.render(voice.normalize(text), 1.0f, 300);
                    double spent = (System.nanoTime() - t0) / 1e9;
                    int rate = voice.sampleRate();
                    voice.takeRatio();
                    String name = WavOut.saveRecording(VoiceService.this,
                            getString(own ? R.string.record_reading_name : R.string.record_probe_name), audio, rate);
                    message = getString(R.string.probe_done, name,
                            audio.length / (double) rate, spent);
                    voice.play(audio, new Voice.Cancel() {
                        @Override
                        public boolean cancelled() {
                            return gen != generation.get();
                        }
                    });
                } catch (Throwable t) {
                    message = getString(R.string.probe_failed, String.valueOf(t.getMessage()));
                }
                final String shown = message;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        android.widget.Toast.makeText(VoiceService.this, shown,
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });
                pending.decrementAndGet();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        finishIfIdle();
                    }
                });
            }
        });
    }

    /**
     * Reads a text paragraph by paragraph the way the book is read, telling
     * the screen how far it got, then saves the whole and plays nothing:
     * the file is the result.
     */
    private void runRecord(final String text, final int voiceNo) {
        final int gen = generation.incrementAndGet();
        pending.incrementAndGet();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                Intent end = new Intent(ACTION_RECORD_STATE).setPackage(getPackageName());
                try {
                    Voice voice = Voice.get(VoiceService.this);
                    voice.prepare();
                    java.util.List<String> paras = new java.util.ArrayList<String>();
                    for (String p : (text == null ? "" : text).split("\\n")) {
                        if (!p.trim().isEmpty()) {
                            paras.add(p.trim());
                        }
                    }
                    int rate = voice.sampleRate();
                    float speed = Prefs.speedShared(VoiceService.this);
                    int pause = Prefs.pauseMsShared(VoiceService.this);
                    java.util.List<float[]> parts = new java.util.ArrayList<float[]>();
                    int total = 0;
                    Voice.Cancel cancel = new Voice.Cancel() {
                        @Override
                        public boolean cancelled() {
                            return gen != generation.get();
                        }
                    };
                    for (int i = 0; i < paras.size() && !cancel.cancelled(); i++) {
                        tell(i, paras.size());
                        String spoken = voice.normalize(Lexicon.get(VoiceService.this)
                                .applyRules(TextPrep.clean(paras.get(i))));
                        float[] a = voice.render(spoken, SpeechLanguage.locale(), speed, pause, cancel, null, false,
                                voiceNo);
                        parts.add(a);
                        total += a.length;
                    }
                    tell(paras.size(), paras.size());
                    float[] all = new float[total];
                    int at = 0;
                    for (float[] a : parts) {
                        System.arraycopy(a, 0, all, at, a.length);
                        at += a.length;
                    }
                    Prefs.setLastRatio(VoiceService.this, voice.takeRatio());
                    int narrator = voiceNo > 0 ? voiceNo : Prefs.roleShared(VoiceService.this, Cast.NARRATOR);
                    end.putExtra(EXTRA_SECONDS, all.length / (float) rate);
                    end.putExtra(EXTRA_FILE, cancel.cancelled() ? ""
                            : WavOut.saveRecording(VoiceService.this, getString(R.string.record_reading_name), all, rate));
                    Diag.mark(VoiceService.this, String.format(java.util.Locale.ROOT,
                            "record: %d paragraphs, %.1f s", paras.size(), all.length / (float) rate));
                } catch (Throwable t) {
                    end.putExtra(EXTRA_FILE, "").putExtra(EXTRA_ERROR, String.valueOf(t.getMessage()));
                    Diag.log(VoiceService.this, "record: failed", t);
                }
                sendBroadcast(end);
                pending.decrementAndGet();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        finishIfIdle();
                    }
                });
            }
        });
    }

    private void tell(int done, int total) {
        sendBroadcast(new Intent(ACTION_RECORD_STATE).setPackage(getPackageName())
                .putExtra(EXTRA_DONE, done).putExtra(EXTRA_TOTAL, total));
    }

    private void finishIfIdle() {
        if (pending.get() == 0) {
            sendBroadcast(new Intent(ACTION_IDLE).setPackage(getPackageName()));
            stopForeground(true);
            stopSelf();
        }
    }

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.channel), NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, VoiceService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        String preview = text.length() > PREVIEW_CHARS
                ? text.substring(0, PREVIEW_CHARS).trim() + "…" : text;
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(getString(R.string.speaking))
                .setContentText(preview)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_note),
                        getString(R.string.stop), stop).build())
                .build();
    }
}
