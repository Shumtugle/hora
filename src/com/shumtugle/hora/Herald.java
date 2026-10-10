package com.shumtugle.hora;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Person;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reads the gist of chosen apps' notifications, into headphones only.
 * No headphones: silence. Headphones gone: silence at once, mid-word.
 * The journal gets the app's name and nothing of what was said.
 * Runs in the voice process so the loaded voice is shared with the book.
 */
public final class Herald extends NotificationListenerService {
    private static final int MAX_CHARS = 300;
    private static final long REPEAT_WINDOW_MS = 30 * 60 * 1000L;
    private static final int REMEMBERED = 128;
    private static final int QUEUE_LIMIT = 4;
    private static final int STEP = 2048;
    // AudioAttributes.USAGE_ASSISTANT; not available at the compile-time API level.
    private static final int USAGE_ASSISTANT = 16;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger waiting = new AtomicInteger();
    /** Recently read texts and notification keys, with the time they were seen. */
    private final Map<String, Long> seen = new LinkedHashMap<String, Long>(16, 0.75f, true);
    private AudioManager audio;
    private volatile AudioTrack current;
    private volatile boolean cut;
    /** Why the last reading was cut short, for the journal. */
    private volatile String why = "headphones gone";
    /** The notification being read now, and those gone before their turn came. */
    private volatile String readingKey;
    private final java.util.Set<String> gone = java.util.Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > REMEMBERED;
                }
            });
    private boolean listening;

    private final AudioDeviceCallback devices = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            if (!Earpiece.present(audio)) {
                cutNow();
            }
        }
    };

    /** Another sound took the floor: a voice message, a call, a video. Hora stops; ducking is the system's. */
    private final AudioManager.OnAudioFocusChangeListener yield = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int change) {
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                cutNow("another sound");
            }
        }
    };

    /** The quiet tile, or any other way of silencing all of Hora. */
    private final BroadcastReceiver hush = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            cutNow("quiet tile");
        }
    };

    /** Fires before the system moves sound to the speaker. */
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            cutNow();
        }
    };

    /** Opens the system page where this listener is let in. */
    static void openAccess(android.app.Activity a) {
        android.content.Intent i;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            i = new android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            new android.content.ComponentName(a, Herald.class).flattenToString());
        } else {
            i = new android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        try {
            a.startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    /** Whether the system lets this listener see notifications. */
    static boolean granted(Context c) {
        String on = android.provider.Settings.Secure.getString(c.getContentResolver(),
                "enabled_notification_listeners");
        if (on == null) {
            return false;
        }
        android.content.ComponentName me = new android.content.ComponentName(c, Herald.class);
        for (String s : on.split(":")) {
            if (me.equals(android.content.ComponentName.unflattenFromString(s))) {
                return true;
            }
        }
        return false;
    }

    /** The last battery level seen, to say a warning once when it crosses a line, not on every change. */
    private int lastLevel = -1;

    /** The two lines the battery is watched at, in percent: a word at the first, another at the second. */
    private static final int LOW = 15;
    private static final int VERY_LOW = 5;

    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            int plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
            if (level < 0 || scale <= 0) {
                return;
            }
            int pct = level * 100 / scale;
            int before = lastLevel;
            lastLevel = pct;
            if (before < 0 || plugged != 0 || !Prefs.batteryWarnShared(Herald.this)) {
                return;
            }
            if (before >= LOW && pct < LOW || before >= VERY_LOW && pct < VERY_LOW) {
                final String said = SpeechLanguage.resources(Herald.this).getString(
                        pct < VERY_LOW ? R.string.battery_very_low : R.string.battery_low, pct);
                waiting.incrementAndGet();
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            read("battery", said, null);
                        } catch (Throwable t) {
                            Diag.mark(Herald.this, "herald: battery word failed, " + t.getClass().getSimpleName());
                        } finally {
                            waiting.decrementAndGet();
                        }
                    }
                });
            }
        }
    };

    @Override
    public void onListenerConnected() {
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (!listening) {
            audio.registerAudioDeviceCallback(devices, main);
            registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            // The battery is a system broadcast: it reaches this listener as long as it is connected.
            registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(hush, new IntentFilter(Hush.ACTION), RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(hush, new IntentFilter(Hush.ACTION));
            }
            listening = true;
        }
        Diag.mark(this, "herald: connected");
    }

    @Override
    public void onListenerDisconnected() {
        cutNow();
        stopListening();
        Diag.mark(this, "herald: disconnected");
    }

    @Override
    public void onDestroy() {
        cutNow();
        stopListening();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void stopListening() {
        if (listening) {
            audio.unregisterAudioDeviceCallback(devices);
            unregisterReceiver(noisy);
            unregisterReceiver(battery);
            unregisterReceiver(hush);
            listening = false;
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankings) {
        String pkg = sbn.getPackageName();
        if (pkg.equals(getPackageName()) || !Prefs.heraldHearsShared(this, pkg)) {
            return;
        }
        Notification n = sbn.getNotification();
        int skip = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE
                | Notification.FLAG_GROUP_SUMMARY;
        if ((n.flags & skip) != 0) {
            return;
        }
        Ranking r = new Ranking();
        if (rankings != null && rankings.getRanking(sbn.getKey(), r)) {
            if (r.getImportance() < NotificationManager.IMPORTANCE_DEFAULT
                    || r.isAmbient() || !r.matchesInterruptionFilter()) {
                return;
            }
        }
        String app = label(pkg);
        if (quietNow()) {
            Diag.mark(this, "herald: quiet hours, " + app);
            return;
        }
        String said = gist(n, app);
        if (said == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (seen) {
            forgetOld(now);
            boolean update = seen.containsKey(sbn.getKey());
            seen.put(sbn.getKey(), now);
            if (update && (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0) {
                return;
            }
            String same = pkg + '\n' + said;
            if (seen.containsKey(same)) {
                return;
            }
            seen.put(same, now);
        }
        if (waiting.get() >= QUEUE_LIMIT) {
            Diag.mark(this, "herald: dropped, queue full, " + app);
            return;
        }
        // A new post under a key once swept away is news again.
        gone.remove(sbn.getKey());
        waiting.incrementAndGet();
        final String a = app;
        final String s = said;
        final String key = sbn.getKey();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    read(a, s, key);
                } catch (Throwable t) {
                    Diag.mark(Herald.this, "herald: failed, " + a + ", " + t.getClass().getSimpleName());
                } finally {
                    waiting.decrementAndGet();
                }
            }
        });
    }

    /**
     * The notification left the shade: opened, read elsewhere or swept away. If it is being
     * read, the reading stops; if it waits its turn, it will not be read.
     */
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankings, int reason) {
        String key = sbn.getKey();
        gone.add(key);
        if (key.equals(readingKey)) {
            cutNow("notification gone");
        }
    }

    private void forgetOld(long now) {
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (now - e.getValue() > REPEAT_WINDOW_MS || seen.size() > REMEMBERED) {
                it.remove();
            }
        }
    }

    /** What to say about a notification, or null when there is nothing worth saying. */
    private String gist(Notification n, String app) {
        Bundle x = n.extras;
        if (x == null) {
            return null;
        }
        String title = str(x.getCharSequence(Notification.EXTRA_TITLE));
        String body = str(x.getCharSequence(Notification.EXTRA_TEXT));
        String conversation = str(x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        Parcelable[] messages = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        android.content.res.Resources res = SpeechLanguage.resources(this);
        String out;
        if (messages != null && messages.length > 0 && messages[messages.length - 1] instanceof Bundle) {
            Bundle last = (Bundle) messages[messages.length - 1];
            String text = str(last.getCharSequence("text"));
            String sender = str(last.getCharSequence("sender"));
            if (sender == null && android.os.Build.VERSION.SDK_INT >= 28) {
                Object p = last.getParcelable("sender_person");
                if (p instanceof Person) {
                    sender = str(((Person) p).getName());
                }
            }
            if (sender == null) {
                // No sender means the user's own reply.
                return null;
            }
            if (text == null) {
                return null;
            }
            out = conversation != null && !conversation.equals(sender)
                    ? res.getString(R.string.herald_in_group, sender, conversation, text)
                    : res.getString(R.string.herald_writes, sender, text);
        } else if (Notification.CATEGORY_MESSAGE.equals(n.category) && title != null && body != null) {
            out = res.getString(R.string.herald_writes, title, body);
        } else {
            String rest = title != null && body != null ? title + ". " + body
                    : title != null ? title : body;
            if (rest == null) {
                return null;
            }
            out = res.getString(R.string.herald_other, app, rest);
        }
        return shorten(out);
    }

    private static String shorten(String s) {
        if (s.length() <= MAX_CHARS) {
            return s;
        }
        int space = s.lastIndexOf(' ', MAX_CHARS);
        return s.substring(0, space > MAX_CHARS / 2 ? space : MAX_CHARS).trim() + "\u2026";
    }

    private static String str(CharSequence cs) {
        if (cs == null) {
            return null;
        }
        String s = cs.toString().replace('\n', ' ').trim();
        return s.isEmpty() ? null : s;
    }

    private String label(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** On the worker thread: pauses the book, speaks, gives the book back. */
    private void read(String app, String said, String key) throws Exception {
        if (key != null && gone.remove(key)) {
            Diag.mark(this, "herald: gone before its turn, " + app);
            return;
        }
        if (quietNow()) {
            Diag.mark(this, "herald: quiet hours, " + app);
            return;
        }
        if (!mayStart()) {
            Diag.mark(this, "herald: quiet, no headphones, " + app);
            return;
        }
        boolean held = onMain(true);
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes())
                .setOnAudioFocusChangeListener(yield, main)
                .build();
        Awake.hold(this);
        try {
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Diag.mark(this, "herald: quiet, no focus, " + app);
                return;
            }
            why = "headphones gone";
            readingKey = key;
            Hush.begin(this, new Runnable() {
                @Override
                public void run() {
                    cutNow("headphone button");
                }
            });
            boolean whole = speak(said);
            Diag.mark(this, (whole ? "herald: read, " : "herald: cut, " + why + ", ") + app);
        } finally {
            readingKey = null;
            Hush.end(this);
            audio.abandonAudioFocusRequest(focus);
            Awake.let(this);
            if (held) {
                onMain(false);
            }
        }
    }

    private boolean quietNow() {
        java.util.Calendar now = java.util.Calendar.getInstance();
        return Prefs.quietAtShared(this,
                now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE));
    }

    /** Headphones are in and the phone is not in a call. */
    private boolean mayStart() {
        return audio.getMode() == AudioManager.MODE_NORMAL && Earpiece.present(audio);
    }

    /** Holds or releases the book on the main thread and waits for it. */
    private boolean onMain(final boolean hold) throws InterruptedException {
        final boolean[] result = new boolean[1];
        final CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                if (hold) {
                    result[0] = BookPlayer.holdForNews();
                } else {
                    BookPlayer.releaseAfterNews();
                }
                done.countDown();
            }
        });
        done.await(2, TimeUnit.SECONDS);
        return result[0];
    }

    /** Returns true when everything was said, false when cut short. */
    private boolean speak(String said) throws Exception {
        Voice voice = Voice.get(this);
        try {
            voice.prepare();
        } catch (Voice.Missing m) {
            // No voice pack yet: the phone's own voice reads it, still only into the headphones.
            if (Earpiece.find(audio) == null) {
                return false;
            }
            cut = false;
            return SpareVoice.say(this, TextPrep.clean(said), new Voice.Cancel() {
                @Override
                public boolean cancelled() {
                    return cut || !Earpiece.present(audio);
                }
            });
        }
        String spoken = voice.normalize(Lexicon.get(this).applyRules(TextPrep.clean(said)));
        int rate = voice.sampleRate();
        float speed = Prefs.speedShared(this);
        int pauseMs = Prefs.pauseMsShared(this);
        int reader = Beta.voice(this, Cast.HERALD, Prefs.roleShared(this, Cast.HERALD));
        AudioDeviceInfo ear = Earpiece.find(audio);
        if (ear == null) {
            return false;
        }
        AudioTrack out = openTrack(rate);
        out.setPreferredDevice(ear);
        cut = false;
        current = out;
        try {
            boolean started = false;
            boolean[] inside = {false};
            for (String piece : Voice.chunks(spoken, SpeechLanguage.locale())) {
                String chunk = Foreign.seal(piece, inside);
                if (stopped(out)) {
                    break;
                }
                float[] samples = voice.synthesizeVoice(chunk, speed, reader);
                if (stopped(out)) {
                    break;
                }
                if (!started) {
                    out.play();
                    started = true;
                }
                write(out, samples);
                if (pauseMs > 0) {
                    write(out, new float[rate * pauseMs / 1000]);
                }
            }
            if (stopped(out)) {
                return false;
            }
            // Let the queued tail play out, still watching the headphones.
            long deadline = System.currentTimeMillis() + written * 1000L / rate + 1000;
            while (out.getPlaybackHeadPosition() < written
                    && System.currentTimeMillis() < deadline) {
                if (stopped(out)) {
                    return false;
                }
                Thread.sleep(40);
            }
            return true;
        } finally {
            current = null;
            try {
                out.pause();
                out.flush();
            } catch (IllegalStateException ignored) {
                // Already released by a cut.
            }
            out.release();
            written = 0;
        }
    }

    private int written;

    private void write(AudioTrack out, float[] data) {
        for (int off = 0; off < data.length && !stopped(out); off += STEP) {
            int n = out.write(data, off, Math.min(STEP, data.length - off), AudioTrack.WRITE_BLOCKING);
            if (n > 0) {
                written += n;
            }
        }
    }

    /** Stop when told to, when the headphones are gone, or when the sound went elsewhere. */
    private boolean stopped(AudioTrack out) {
        if (cut || !Earpiece.present(audio)) {
            return true;
        }
        AudioDeviceInfo routed = out.getRoutedDevice();
        return routed != null && !Earpiece.isPrivate(routed);
    }

    /** Silences at once: whatever is queued is thrown away. */
    private void cutNow() {
        cutNow("headphones gone");
    }

    private void cutNow(String reason) {
        why = reason;
        cut = true;
        AudioTrack t = current;
        if (t != null) {
            try {
                t.pause();
                t.flush();
            } catch (IllegalStateException ignored) {
                // Finished meanwhile.
            }
        }
    }

    private static AudioAttributes attributes() {
        AudioAttributes.Builder b = new AudioAttributes.Builder()
                .setUsage(USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            b.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE);
        }
        return b.build();
    }

    private static AudioTrack openTrack(int rate) {
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        return new AudioTrack.Builder()
                .setAudioAttributes(attributes())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(min, rate))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }
}
