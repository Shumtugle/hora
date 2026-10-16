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
    /** Sent to this app's screens when nothing is left to say. */
    static final String ACTION_IDLE = "com.shumtugle.hora.action.IDLE";
    private static final String EXTRA_TEXT = "text";
    private static final String EXTRA_SPEED = "speed";
    private static final String EXTRA_PAUSE = "pause";
    private static final String EXTRA_VOICE = "voice";
    /** The bot speaking for itself: the phone's voice may say it, when asked for. */
    private static final String EXTRA_ANSWER = "answer";
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
        speak(c, text, voice, pace, false);
    }

    /**
     * The bot's own words (an answer, the time, the weather), with the given voice; said by the
     * phone's voice instead when the person asked for it. A voice introducing itself, or a text
     * read aloud, is never handed over: that is speak().
     */
    static void answer(Context c, String text, int voice) {
        speak(c, text, voice, 1f, true);
    }

    private static void speak(Context c, String text, int voice, float pace, boolean answer) {
        Intent i = new Intent(c, VoiceService.class)
                .putExtra(EXTRA_ANSWER, answer)
                .setAction(ACTION_SPEAK)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_SPEED, Math.max(Prefs.SPEED_MIN, Prefs.speedShared(c) * pace))
                .putExtra(EXTRA_PAUSE, Prefs.pauseMsShared(c))
                .putExtra(EXTRA_VOICE, voice);
        c.startForegroundService(i);
    }

    /** Stops whatever is being spoken. */
    static void stop(Context c) {
        c.startService(new Intent(c, VoiceService.class).setAction(ACTION_STOP));
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
            // Before Android 13 a receiver cannot be closed to others; a permission only this app holds does it.
            registerReceiver(hush, new android.content.IntentFilter(Hush.ACTION), Herald.SELF, null);
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
                        intent.getIntExtra(EXTRA_VOICE, 0),
                        intent.getBooleanExtra(EXTRA_ANSWER, false));
                return START_NOT_STICKY;
            }
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

    private void enqueue(final String text, final float speed, final int pauseMs, final int voiceNo,
            final boolean answer) {
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
                    // The bot's words go to the phone's voice when asked for; with no voice pack yet,
                    // every phrase does, so that it is not lost.
                    boolean phone = answer && SpareVoice.wanted(VoiceService.this);
                    Voice voice = null;
                    if (!phone) {
                        voice = Voice.get(VoiceService.this);
                        try {
                            voice.prepare();
                        } catch (Voice.Missing m) {
                            phone = true;
                        }
                    }
                    String spoken = phone ? TextPrep.clean(text)
                            : voice.normalize(Lexicon.get(VoiceService.this).applyRules(TextPrep.clean(text)));
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
                    Voice.Cancel stop = new Voice.Cancel() {
                        @Override
                        public boolean cancelled() {
                            return gen != generation.get();
                        }
                    };
                    // A book reading meanwhile is lowered under the answer, not stopped.
                    BookPlayer.duck(true);
                    try {
                        if (phone) {
                            SpareVoice.say(VoiceService.this, spoken, stop);
                        } else {
                            voice.speak(spoken, SpeechLanguage.locale(), speed, pauseMs, stop, voiceNo);
                        }
                    } finally {
                        BookPlayer.duck(false);
                        Hush.end(VoiceService.this);
                        audio.abandonAudioFocusRequest(focus);
                    }
                    if (!phone) {
                        float r = voice.takeRatio();
                        if (r > 0) {
                            Prefs.setLastRatio(VoiceService.this, r);
                        }
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
                // The same as the launcher icon: the app comes forward as it was left.
                Intent.makeMainActivity(new android.content.ComponentName(this, DayActivity.class))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                PendingIntent.FLAG_IMMUTABLE);
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
