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
    /** A route hint that waited longer than this is about a turn already passed, and is dropped. */
    private static final long ROUTE_STALE_MS = 5_000;
    /** "Hear an example" on the notifications screen; the screen lives in another process. */
    static final String ACTION_ROUTE_SAMPLE = "com.shumtugle.hora.ROUTE_SAMPLE";
    /** "Hear an example" of the morning line, said now with what is true now. */
    static final String ACTION_MORNING_SAMPLE = "com.shumtugle.hora.MORNING_SAMPLE";
    /** Held only by this app (signature level): who may send to the reader's receivers before Android 13. */
    static final String SELF = "com.shumtugle.hora.permission.SELF";
    /** The morning line is not said before this hour. */
    private static final int MORNING_FROM_HOUR = 4;
    // AudioAttributes.USAGE_ASSISTANT; not available at the compile-time API level.
    private static final int USAGE_ASSISTANT = 16;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Makes route phrases into sound ahead of their turn, apart from the reading worker. */
    private final ExecutorService maker = Executors.newSingleThreadExecutor();
    /** Route phrases made into sound, by voice, pace, pause, decoder and words. */
    private final Map<String, Made> made = java.util.Collections.synchronizedMap(
            new LinkedHashMap<String, Made>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Made> e) {
                    return size() > 16;
                }
            });

    /** A phrase made into sound, with the rate it was made at. */
    private static final class Made {
        final float[] pcm;
        final int rate;

        Made(float[] pcm, int rate) {
            this.pcm = pcm;
            this.rate = rate;
        }
    }
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
    /** A notification is being read now; a route hint may cut it short. */
    private volatile boolean newsSpeaking;
    /** The route hint waiting to be said; a newer one replaces it. */
    private final java.util.concurrent.atomic.AtomicReference<NavWhisper.Said> route =
            new java.util.concurrent.atomic.AtomicReference<NavWhisper.Said>();
    private final java.util.Set<String> gone = java.util.Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > REMEMBERED;
                }
            });
    private boolean listening;
    /** What the reader had to stay silent about, until it may say there are new notifications. */
    private volatile Missed missed;
    private final java.util.concurrent.atomic.AtomicBoolean toldQueued = new java.util.concurrent.atomic.AtomicBoolean();
    /** The listener is bound and sees the shade; the service is gone for good. */
    private volatile boolean connected;
    private volatile boolean destroyed;
    /** Since when a call has kept Hora silent with something to tell, elapsed time; zero when not. */
    private long callSince;
    /** The call mode has lasted so long that looking is given up until it ends for real. */
    private boolean callGivenUp;
    /** Tellings that found the sound still taken (often the call's own, for a moment after it ends). */
    private final AtomicInteger tellTries = new AtomicInteger();
    /** A call this long is a mode some app forgot to give back: stop looking until the next event. */
    private static final long CALL_GIVE_UP_MS = 2 * 60 * 60 * 1000L;
    /** How a reading ended. */
    private static final int SILENT = 0;
    private static final int CUT = 1;
    private static final int CUT_BY_USER = 2;
    private static final int WHOLE = 3;

    private final AudioDeviceCallback devices = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            // Headphones on: the morning line, or what came meanwhile, once the sound has settled on them.
            // A connection after four in the morning may wait for the line; one in the night may not.
            java.util.Calendar now = java.util.Calendar.getInstance();
            morningGaveUp = false;
            for (AudioDeviceInfo d : added) {
                if (Earpiece.isPrivate(d)) {
                    if (!earsIn) {
                        earsIn = true;
                        long t = android.os.SystemClock.elapsedRealtime();
                        // A link that dropped for a moment and came back is the same wearing, not a new one.
                        if (t - earsOutAt > BLIP_MS) {
                            // The morning line's wait is counted from the moment headphones came, not from the first try.
                            arrivalAt = t;
                            arrivalDay = now.get(java.util.Calendar.HOUR_OF_DAY) >= MORNING_FROM_HOUR ? today(now) : 0;
                            heldWhy = null;
                            Diag.mark(Herald.this, "herald: headphones on");
                        }
                    }
                    break;
                }
            }
            main.removeCallbacks(arrived);
            main.postDelayed(arrived, 1500);
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            if (!Earpiece.present(audio)) {
                if (earsIn) {
                    earsIn = false;
                    earsOutAt = android.os.SystemClock.elapsedRealtime();
                }
                if (!openAir) {
                    cutNow();
                }
                return;
            }
            int on = readingOn;
            for (AudioDeviceInfo d : removed) {
                if (on != 0 && d.getId() == on) {
                    // The device a reading began on is gone: the words do not follow the sound elsewhere.
                    cutNow("device changed");
                    return;
                }
            }
        }
    };

    /**
     * Main thread. Headphones came: the first time today brings the morning line, any other time
     * the new notifications. When the line was held back at a connection made today after four
     * (quiet hours, a call, do not disturb) and the headphones stay on, it looks again every minute,
     * that day only and until it gives up; headphones left on through the night never wake anyone.
     */
    private final Runnable arrived = new Runnable() {
        @Override
        public void run() {
            main.removeCallbacks(this);
            askIfNew();
            if (!morningGaveUp && morningOwed() && arrivalDay == today(java.util.Calendar.getInstance())
                    && morningStale()) {
                // Held back too long, the line is no longer a morning: today it is not said at all, even
                // if speaking is allowed again by now (a sleeping phone may miss every minute's look).
                // What came meanwhile is still told the usual way.
                dropMorning("held too long by " + (heldWhy != null ? heldWhy : heldBy()));
                lookAgain.run();
                return;
            }
            if (!morningGaveUp && morningDue()) {
                queueMorning(false);
                return;
            }
            if (!morningGaveUp && morningOwed() && arrivalDay == today(java.util.Calendar.getInstance())) {
                if (heldWhy == null) {
                    heldWhy = heldBy();
                    Diag.mark(Herald.this, "herald: morning line held, " + heldWhy);
                }
                main.postDelayed(this, 60_000);
            }
            lookAgain.run();
        }
    };

    /** Main thread. The day of the last wearing begun after four in the morning, or zero. */
    private int arrivalDay;
    /** When headphones last came, elapsed time; the morning line waits no longer than allowed after it. */
    private volatile long arrivalAt = android.os.SystemClock.elapsedRealtime();
    /** Main thread. Headphones are in, as last seen by the device callback, and since when they were out. */
    private boolean earsIn;
    private long earsOutAt = -BLIP_MS - 1;
    /** Headphones gone and back within this are one wearing: a wireless link drops for a moment now and then. */
    private static final long BLIP_MS = 60_000;
    /** Main thread. Why the line waits, as written down for this connection; null before that. */
    private String heldWhy;
    /** A morning line that could not be said this long after the headphones came is not said that day. */
    private static final long MORNING_STALE_MS = 30 * 60_000L;
    /** Main thread. The morning line failed to be heard too often; tried again at the next connection. */
    private boolean morningGaveUp;
    /** A morning line is on its way: fetched or waiting its turn. */
    private final java.util.concurrent.atomic.AtomicBoolean morningQueued =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Morning lines that found the sound taken, tried again a few times a few seconds apart. */
    private final AtomicInteger morningTries = new AtomicInteger();

    /** An answer about a device came from the notification: what waits may now be told. */
    private final BroadcastReceiver earsChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            main.post(lookAgain);
        }
    };

    /**
     * The output a reading began on, or zero. Private words must not move with the sound to
     * another device (a car taking over in the middle of a message): if the output changes, the
     * reading stops there.
     */
    private volatile int readingOn;

    /** Whether the sound left the device the reading began on. */
    private boolean deviceMoved() {
        int on = readingOn;
        if (on == 0) {
            return false;
        }
        AudioDeviceInfo now = Earpiece.find(audio);
        return now == null || now.getId() != on;
    }

    /** Worker thread. When the last purr was played; purrs closer than this are one. */
    private long lastPurr;
    private static final long PURR_APART_MS = 10_000;
    private static final String EARS_CHANNEL = "ears";

    /** "Hear an example" of the morning line. */
    private final BroadcastReceiver morningSample = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            queueMorning(true);
        }
    };

    /**
     * Main thread. Tells about new notifications as soon as Hora may speak again. While a call
     * lasts it looks every few seconds, in quiet hours every minute; headphones put on and the
     * end of do not disturb call it themselves.
     */
    private final Runnable lookAgain = new Runnable() {
        @Override
        public void run() {
            main.removeCallbacks(this);
            if (audio == null) {
                return;
            }
            boolean inCallMode = audio.getMode() != AudioManager.MODE_NORMAL;
            if (!inCallMode) {
                callSince = 0;
                callGivenUp = false;
            }
            Missed m = missed;
            if (destroyed || !connected || m == null || m.isEmpty() || !Prefs.missedSayShared(Herald.this)) {
                return;
            }
            if (mayTell()) {
                queueTell();
            } else if (inCallMode) {
                if (callGivenUp) {
                    return;
                }
                long now = android.os.SystemClock.elapsedRealtime();
                if (callSince == 0) {
                    callSince = now;
                }
                long inCall = now - callSince;
                if (inCall < CALL_GIVE_UP_MS) {
                    // Often at first, as most calls are short; then rarely.
                    main.postDelayed(this, inCall < 10 * 60_000 ? 3000 : 30_000);
                } else {
                    // A mode some app forgot to give back: wait for it to end, not for the clock.
                    callGivenUp = true;
                }
            } else if (quietNow()) {
                main.postDelayed(this, 60_000);
            }
            // Otherwise the headphones are off or the phone is set not to disturb; their own events look again.
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

    /** "Hear an example": an early hint for a right turn, into the headphones. */
    private final BroadcastReceiver sample = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            routeSoon(NavWhisper.sample(Herald.this));
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
                            read("battery", said, null, null);
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
        if (missed == null) {
            missed = new Missed(this);
        }
        connected = true;
        if (!listening) {
            audio.registerAudioDeviceCallback(devices, main);
            registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            // The battery is a system broadcast: it reaches this listener as long as it is connected.
            registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(hush, new IntentFilter(Hush.ACTION), RECEIVER_NOT_EXPORTED);
                registerReceiver(sample, new IntentFilter(ACTION_ROUTE_SAMPLE), RECEIVER_NOT_EXPORTED);
                registerReceiver(morningSample, new IntentFilter(ACTION_MORNING_SAMPLE), RECEIVER_NOT_EXPORTED);
                registerReceiver(earsChanged, new IntentFilter(EarsAnswer.ACTION_CHANGED), RECEIVER_NOT_EXPORTED);
            } else {
                // Before Android 13 a receiver cannot be closed to others; a permission only this app holds does it.
                registerReceiver(hush, new IntentFilter(Hush.ACTION), SELF, null);
                registerReceiver(sample, new IntentFilter(ACTION_ROUTE_SAMPLE), SELF, null);
                registerReceiver(morningSample, new IntentFilter(ACTION_MORNING_SAMPLE), SELF, null);
                registerReceiver(earsChanged, new IntentFilter(EarsAnswer.ACTION_CHANGED), SELF, null);
            }
            listening = true;
        }
        Diag.mark(this, "herald: connected");
        // Whatever waited when this process was ended is told once Hora may speak.
        main.postDelayed(lookAgain, 3000);
    }

    @Override
    public void onInterruptionFilterChanged(int filter) {
        main.post(lookAgain);
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        main.removeCallbacks(lookAgain);
        main.removeCallbacks(arrived);
        cutNow();
        stopListening();
        // Cards taken away while not listening would never say goodbye: the route starts afresh.
        NavWhisper.forgetAll();
        main.removeCallbacks(letRoute);
        RouteHold.let(this);
        Diag.mark(this, "herald: disconnected");
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        connected = false;
        main.removeCallbacks(lookAgain);
        main.removeCallbacks(arrived);
        cutNow();
        stopListening();
        main.removeCallbacks(letRoute);
        RouteHold.let(this);
        worker.shutdownNow();
        maker.shutdownNow();
        super.onDestroy();
    }

    private void stopListening() {
        if (listening) {
            audio.unregisterAudioDeviceCallback(devices);
            unregisterReceiver(noisy);
            unregisterReceiver(battery);
            unregisterReceiver(hush);
            unregisterReceiver(sample);
            unregisterReceiver(morningSample);
            unregisterReceiver(earsChanged);
            listening = false;
            // Unseen while not listening: what is found on return counts as headphones put on again,
            // unless the gap was only a moment.
            if (earsIn) {
                earsIn = false;
                earsOutAt = android.os.SystemClock.elapsedRealtime();
            }
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankings) {
        String pkg = sbn.getPackageName();
        if (pkg.equals(getPackageName())) {
            return;
        }
        // A navigator's hint: its turns are whispered when asked to. A one-off alert of the same
        // kind ("arriving") is still read as before; the ongoing turn-by-turn one is skipped below.
        boolean turns = Prefs.navWhisperShared(this);
        boolean eta = Prefs.navEtaShared(this);
        if (NavWhisper.isRouteHint(sbn) && (sbn.getNotification().flags & Notification.FLAG_ONGOING_EVENT) != 0
                && (turns || eta)) {
            try {
                // The time left is offered only when nothing waits and no notification is being read.
                NavWhisper.Said s = NavWhisper.seen(this, sbn, label(pkg), turns, eta,
                        route.get() == null && !newsSpeaking);
                // While a navigator leads the way, the voice is kept from being ended for memory.
                main.removeCallbacks(letRoute);
                RouteHold.hold(this);
                if (s != null) {
                    routeSoon(s);
                }
                for (String ahead : NavWhisper.takeToMake()) {
                    makeAhead(ahead);
                }
            } catch (RuntimeException | OutOfMemoryError e) {
                // A hint that cannot be read is not worth the book and the reader in this process.
                Diag.mark(this, "nav: a hint not read, " + e.getClass().getSimpleName());
            }
        }
        if (!Prefs.heraldHearsShared(this, pkg)) {
            return;
        }
        Notification n = sbn.getNotification();
        int skip = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE
                | Notification.FLAG_GROUP_SUMMARY;
        if ((n.flags & skip) != 0) {
            return;
        }
        Ranking r = new Ranking();
        boolean heldBack = false;
        if (rankings != null && rankings.getRanking(sbn.getKey(), r)) {
            if (r.getImportance() < NotificationManager.IMPORTANCE_DEFAULT || r.isAmbient()) {
                return;
            }
            heldBack = !r.matchesInterruptionFilter();
        }
        String app = label(pkg);
        String said = gist(n, app);
        if (said == null) {
            // Nothing worth saying: a reply of one's own, an empty post. Not news, not missed either.
            return;
        }
        if (!fresh(sbn, n, pkg, said)) {
            return;
        }
        if (heldBack) {
            // Kept back by do not disturb: still news once the phone may disturb again.
            miss(sbn.getKey(), app, sender(n));
            return;
        }
        if (quietNow()) {
            Diag.mark(this, "herald: quiet hours, " + app);
            miss(sbn.getKey(), app, sender(n));
            return;
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
        final String from = sender(n);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    read(a, s, key, from);
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
        if (NavWhisper.isRouteHint(sbn)) {
            NavWhisper.gone(this, sbn);
            if (!NavWhisper.anyRoute()) {
                // A navigator that redraws its card takes it away for a moment: let go only if none comes back.
                main.removeCallbacks(letRoute);
                main.postDelayed(letRoute, LET_ROUTE_MS);
            }
        }
        String key = sbn.getKey();
        gone.add(key);
        Missed m = missed;
        if (m != null) {
            m.gone(key);
        }
        if (key.equals(readingKey)) {
            cutNow("notification gone");
        }
    }

    /**
     * Whether a post is news: not an update that asks not to alert again, not the same words
     * from the same app said lately. Remembers it either way.
     */
    private boolean fresh(StatusBarNotification sbn, Notification n, String pkg, String said) {
        long now = System.currentTimeMillis();
        synchronized (seen) {
            forgetOld(now);
            boolean update = seen.containsKey(sbn.getKey());
            seen.put(sbn.getKey(), now);
            if (update && (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0) {
                return false;
            }
            String same = pkg + '\n' + said;
            if (seen.containsKey(same)) {
                return false;
            }
            seen.put(same, now);
            return true;
        }
    }

    /**
     * Main thread. One more notification the reader stays silent about, kept only when it is to
     * be told at all; who wrote is kept only when it is to be told in full. Starts looking for
     * the moment it may be told.
     */
    private void miss(String key, String app, String sender) {
        miss(key, app, sender, true);
    }

    private void miss(String key, String app, String sender, boolean look) {
        Missed m = missed;
        if (destroyed || m == null || key == null || !Prefs.missedSayShared(this)) {
            return;
        }
        m.add(key, app, Prefs.missedFullShared(this) ? sender : null);
        if (look) {
            main.post(lookAgain);
        }
    }

    /** Who wrote a message, from the messaging style or a message's title; null when not a message. */
    private static String sender(Notification n) {
        Bundle x = n.extras;
        if (x == null) {
            return null;
        }
        Parcelable[] messages = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null && messages.length > 0 && messages[messages.length - 1] instanceof Bundle) {
            Bundle last = (Bundle) messages[messages.length - 1];
            String who = str(last.getCharSequence("sender"));
            if (who == null && android.os.Build.VERSION.SDK_INT >= 28) {
                Object p = last.getParcelable("sender_person");
                if (p instanceof Person) {
                    who = str(((Person) p).getName());
                }
            }
            return who;
        }
        if (Notification.CATEGORY_MESSAGE.equals(n.category)) {
            String title = str(x.getCharSequence(Notification.EXTRA_TITLE));
            // A title with a number in it is a count ("3 new messages"), not a name.
            return title == null || title.matches("(?s).*\\d.*") ? null : title;
        }
        return null;
    }

    /** Whether Hora may speak now: no quiet hours, no call, headphones in, nothing set not to disturb. */
    private boolean mayTell() {
        return !quietNow() && mayStart() && disturbAllowed();
    }

    /** The phone is not set not to disturb; read only while the listener is bound. */
    private boolean disturbAllowed() {
        try {
            return getCurrentInterruptionFilter() == INTERRUPTION_FILTER_ALL;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Main thread. Puts telling about new notifications in line, once. */
    private void queueTell() {
        if (!toldQueued.compareAndSet(false, true)) {
            return;
        }
        waiting.incrementAndGet();
        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        tell();
                    } catch (Throwable t) {
                        Diag.mark(Herald.this, "herald: new notifications not told, " + t.getClass().getSimpleName());
                    } finally {
                        toldQueued.set(false);
                        waiting.decrementAndGet();
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // The service is going away.
            toldQueued.set(false);
            waiting.decrementAndGet();
        }
    }

    /**
     * Worker thread. What to say about new notifications, or null: nothing new, the shade not
     * seen right now, the phone set not to disturb, or the word turned off. Only notifications
     * still in the shade count; nothing of what they say is told or written down.
     */
    private Missed.Telling prepareTelling() {
        Missed m = missed;
        if (!connected || m == null || m.isEmpty() || !Prefs.missedSayShared(this)) {
            return null;
        }
        java.util.Set<String> keys = new java.util.HashSet<String>();
        if (!disturbAllowed() || !Ears.privateNow(this, audio)) {
            // Set not to disturb, or not one's own headphones: names and counts wait for the ear.
            return null;
        }
        try {
            StatusBarNotification[] shown = getActiveNotifications();
            if (shown == null) {
                return null;
            }
            for (StatusBarNotification sbn : shown) {
                keys.add(sbn.getKey());
            }
        } catch (RuntimeException e) {
            return null;
        }
        return m.prepare(SpeechLanguage.resources(this), Prefs.missedFullShared(this), keys);
    }

    /** Worker thread. Forgets what was told once it was heard: whole, or stopped by the person. */
    private void told(Missed.Telling t, int how) {
        Missed m = missed;
        if (t == null || m == null || (how != WHOLE && how != CUT_BY_USER)) {
            return;
        }
        tellTries.set(0);
        m.told(t.keys);
        Diag.mark(this, "herald: new notifications told, " + t.total + " in " + t.apps + " apps, "
                + (t.full ? "in full" : "briefly"));
    }

    /** The day the morning line was last said, as year and day of the year; kept in this process's own file. */
    private int morningDay() {
        return getSharedPreferences("morning", MODE_PRIVATE).getInt("day", 0);
    }

    /** Whether the headphones came longer ago than a morning line may wait. Any thread. */
    private boolean morningStale() {
        return android.os.SystemClock.elapsedRealtime() - arrivalAt > MORNING_STALE_MS;
    }

    /** The morning line is not said today; the reason goes to the journal. Any thread. */
    private void dropMorning(String why) {
        keepMorningDay(today(java.util.Calendar.getInstance()));
        morningTries.set(0);
        Diag.mark(this, "herald: morning line dropped, " + why);
    }

    private void keepMorningDay(int day) {
        getSharedPreferences("morning", MODE_PRIVATE).edit().putInt("day", day).commit();
    }

    /** Main thread. What keeps Hora silent now, in a few words for the journal. */
    private String heldBy() {
        if (quietNow()) {
            return "quiet hours";
        }
        if (!disturbAllowed()) {
            return "do not disturb";
        }
        if (audio.getMode() != AudioManager.MODE_NORMAL) {
            return "a call";
        }
        if (!Earpiece.present(audio)) {
            return "no headphones";
        }
        return "nothing now";
    }

    private static int today(java.util.Calendar now) {
        return now.get(java.util.Calendar.YEAR) * 1000 + now.get(java.util.Calendar.DAY_OF_YEAR);
    }

    /** Main thread. The morning line is on, not yet said today, and the headphones are in. */
    private boolean morningOwed() {
        return !destroyed && connected && audio != null && Prefs.morningShared(this)
                && today(java.util.Calendar.getInstance()) != morningDay() && Earpiece.present(audio);
    }

    /** Main thread. Whether this is the first moment of the day the morning line may be said. */
    private boolean morningDue() {
        if (destroyed || !connected || audio == null || !Prefs.morningShared(this)) {
            return false;
        }
        java.util.Calendar now = java.util.Calendar.getInstance();
        // Only for headphones put on this morning: a pair worn through the night waits for the next wearing.
        return now.get(java.util.Calendar.HOUR_OF_DAY) >= MORNING_FROM_HOUR && today(now) != morningDay()
                && arrivalDay == today(now) && mayTell();
    }

    /**
     * Main thread. Puts the morning line in line; an example is said whatever the day, and changes
     * nothing. The weather is fetched first on a thread of its own: the network may be slow, and the
     * reader's line must stay free for a turn that will not wait.
     */
    private void queueMorning(final boolean example) {
        if (!morningQueued.compareAndSet(false, true)) {
            return;
        }
        final boolean weather = Prefs.morningWeatherShared(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String fetched = null;
                try {
                    fetched = weather ? Weather.morning(Herald.this) : null;
                } catch (Throwable t) {
                    // Without the weather the line is still worth saying; this thread must never take the process down.
                    Diag.mark(Herald.this, "herald: morning weather failed, " + t.getClass().getSimpleName());
                }
                final String w = fetched;
                waiting.incrementAndGet();
                try {
                    worker.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                morning(example, w);
                            } catch (Throwable t) {
                                Diag.mark(Herald.this, "herald: morning line failed, " + t.getClass().getSimpleName());
                            } finally {
                                morningQueued.set(false);
                                waiting.decrementAndGet();
                            }
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    morningQueued.set(false);
                    waiting.decrementAndGet();
                }
            }
        }, "morning-weather").start();
    }

    /**
     * Worker thread. The morning line: a greeting and the time; the weather and the headphones'
     * charge when asked for and known; new notifications when they are to be told at all. A part
     * that cannot be had is left out without a word. Said, it is not said again today.
     */
    private void morning(boolean example, String weather) throws Exception {
        java.util.Calendar now = java.util.Calendar.getInstance();
        // Checked again here: the weather may have taken a while, and much can change meanwhile.
        if (!example && today(now) == morningDay()) {
            return;
        }
        if (!example && morningStale()) {
            // Too late to be a morning (the weather may have been slow, or the tries went on): not today.
            dropMorning("too late to say");
            main.post(lookAgain);
            return;
        }
        if (!example && !mayTell()) {
            // Something came up while the weather was fetched: look again later, as if just arrived.
            main.postDelayed(arrived, 5000);
            return;
        }
        android.content.res.Resources r = SpeechLanguage.resources(this);
        int h = now.get(java.util.Calendar.HOUR_OF_DAY);
        int min = now.get(java.util.Calendar.MINUTE);
        StringBuilder s = new StringBuilder(r.getString(h < 12 ? R.string.morning_hello
                : h < 18 ? R.string.morning_hello_day : R.string.morning_hello_evening));
        String time = TimePhrase.bare(r, h, min);
        s.append(' ').append(Character.toUpperCase(time.charAt(0))).append(time.substring(1)).append('.');
        StringBuilder parts = new StringBuilder("time");
        if (weather != null) {
            s.append(' ').append(weather);
            parts.append(", weather");
        }
        Missed.Telling t = example ? null : prepareTelling();
        if (t != null) {
            s.append(' ').append(t.text);
            parts.append(", new notifications");
        }
        if (Prefs.morningBatteryShared(this) && Ears.privateNow(this, audio)) {
            int b = headphoneCharge();
            if (b >= 0) {
                s.append(' ').append(r.getQuantityString(R.plurals.morning_battery, b, b));
                parts.append(", charge");
            }
        }
        int how = read(example ? "morning example" : "morning", s.toString(), null, null);
        told(t, how);
        if (example) {
            return;
        }
        if (how == WHOLE || how == CUT_BY_USER) {
            morningTries.set(0);
            keepMorningDay(today(now));
            Diag.mark(this, "herald: morning line, " + parts);
        } else if (morningTries.incrementAndGet() <= 3) {
            // Not heard (the sound was taken, or something cut it): again in a few seconds,
            // and after that at the next headphones.
            main.postDelayed(arrived, 5000);
        } else {
            morningTries.set(0);
            main.post(new Runnable() {
                @Override
                public void run() {
                    morningGaveUp = true;
                }
            });
        }
    }

    /**
     * The charge of the headphones the sound goes to, 0 to 100, or -1 when unknown: not wireless,
     * no permission, or the headphones do not tell. The system keeps it in a method it does not
     * publish; it is asked by name and may be gone on some phones.
     */
    private int headphoneCharge() {
        if (android.os.Build.VERSION.SDK_INT < 28 || audio == null) {
            return -1;
        }
        if (android.os.Build.VERSION.SDK_INT >= 31 && checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                != PackageManager.PERMISSION_GRANTED) {
            return -1;
        }
        AudioDeviceInfo d = Earpiece.find(audio);
        String address = d == null ? "" : d.getAddress();
        if (address == null || address.isEmpty()) {
            return -1;
        }
        try {
            android.bluetooth.BluetoothManager bm =
                    (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothDevice dev = bm.getAdapter().getRemoteDevice(address);
            int v = -1;
            try {
                Object level = android.bluetooth.BluetoothDevice.class.getMethod("getBatteryLevel").invoke(dev);
                v = level instanceof Integer ? (Integer) level : -1;
            } catch (Exception | LinkageError e) {
                v = -1;
            }
            if (v < 0 || v > 100) {
                // The second way some phones keep it: the device's metadata, key 18, as text.
                Object meta = android.bluetooth.BluetoothDevice.class.getMethod("getMetadata", int.class)
                        .invoke(dev, 18);
                if (meta instanceof byte[]) {
                    v = Integer.parseInt(new String((byte[]) meta, "UTF-8").trim());
                }
            }
            return v >= 0 && v <= 100 ? v : -1;
        } catch (Exception | LinkageError e) {
            return -1;
        }
    }

    /**
     * Worker thread. Says there are new notifications, on its own. When the sound is still
     * taken (as for a moment after a call) it tries again a few times, a few seconds apart.
     */
    private void tell() throws Exception {
        Missed.Telling t = prepareTelling();
        if (t == null) {
            return;
        }
        int how = read("new notifications", t.text, null, null);
        told(t, how);
        if (how == SILENT) {
            if (tellTries.incrementAndGet() <= 3) {
                main.postDelayed(lookAgain, 5000);
            } else {
                tellTries.set(0);
            }
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

    /**
     * Puts a route hint ahead of everything waiting. A notification being read is cut short:
     * a turn will not wait, a message will.
     */
    private void routeSoon(NavWhisper.Said s) {
        NavWhisper.Said before = route.getAndSet(s);
        if (before != null && !before.sample) {
            NavWhisper.unsay(before);
            Diag.mark(this, "nav: replaced by a newer hint, " + (before.time >= 0
                    ? "time, " + NavWhisper.TIME_NAMES[before.time] : NavArrow.NAMES[before.kind]));
        }
        if (newsSpeaking && s.time < 0) {
            // Only a turn cuts a notification short; the time left can wait for it.
            cutNow("route hint");
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                sayRouteSafely();
            }
        });
    }

    /**
     * On the worker thread: says the waiting route hint, if any. The book and other apps' sound
     * stop for the length of the phrase, and the book goes on from its place after it.
     */
    private void sayRoute() throws Exception {
        NavWhisper.Said s = route.getAndSet(null);
        if (s == null) {
            return;
        }
        String what = s.time >= 0 ? "time, " + NavWhisper.TIME_NAMES[s.time]
                : (s.early ? "early, " : "now, ") + NavArrow.NAMES[s.kind] + (s.sample ? ", example" : "");
        if (!s.sample && (android.os.SystemClock.elapsedRealtime() - s.at > ROUTE_STALE_MS
                || !NavWhisper.current(s))) {
            NavWhisper.unsay(s);
            Diag.mark(this, "nav: dropped stale, " + what);
            return;
        }
        String silent = !s.sample && quietNow() ? "nav: quiet hours, "
                : audio.getMode() != AudioManager.MODE_NORMAL ? "nav: quiet, a call, " : null;
        boolean ears = Earpiece.present(audio);
        if (silent == null && !ears && !Prefs.navSpeakerShared(this)) {
            silent = "nav: quiet, no headphones, ";
        }
        if (silent != null) {
            if (s.time < 0) {
                Diag.mark(this, silent + what);
                return;
            }
            // The start of the way is said once per route: kept for when it can be heard. The time
            // is offered again at every change of the card, so it is written down only once.
            if (s.time == NavWhisper.TIME_START) {
                NavWhisper.unsay(s);
            }
            Diag.markOnce(this, "nav-time-silent", silent + what);
            return;
        }
        // Without headphones, and only when asked: the route, never a notification, goes to the speaker.
        openAir = !ears;
        talkingAs = Cast.ROUTE;
        try {
            sayRouteNow(s, what);
        } finally {
            openAir = false;
            talkingAs = Cast.HERALD;
        }
    }

    /** Lets the voice go when no navigator's card has come back meanwhile. */
    private final Runnable letRoute = new Runnable() {
        @Override
        public void run() {
            if (!NavWhisper.anyRoute()) {
                RouteHold.let(Herald.this);
            }
        }
    };
    private static final long LET_ROUTE_MS = 30_000L;

    /** A turn, not the time left, waits to be said. */
    private boolean turnWaiting() {
        NavWhisper.Said w = route.get();
        return w != null && w.time < 0;
    }

    /** Whose voice speaks now: the notifications', or the route's while a route hint is said. */
    private volatile int talkingAs = Cast.HERALD;

    /** Set while a route hint is said aloud without headphones: the speaker is then allowed. */
    private volatile boolean openAir;

    private void sayRouteNow(NavWhisper.Said s, String what) throws Exception {
        // Made ahead, the hint is heard at once; made now, the book steps aside for it. With the
        // phone's voice nothing is made here: the phone's engine says it at once.
        boolean phone = SpareVoice.wanted(this);
        if (phone) {
            // A route hint comes first: a reply of the bot being said in the phone's voice gives way.
            SpareVoice.stopNow();
        }
        int reader = Prefs.roleShared(this, Cast.ROUTE);
        String key = madeKey(s.text, reader);
        Made pcm = phone ? null : made.get(key);
        boolean ahead = pcm != null;
        if (pcm == null && !phone) {
            Voice.urgent(true);
            try {
                pcm = make(s.text, reader);
            } finally {
                Voice.urgent(false);
            }
            if (pcm != null && s.time < 0) {
                // The time left is never the same twice: it does not push turns made ahead out of the store.
                made.put(key, pcm);
            }
        }
        long age = android.os.SystemClock.elapsedRealtime() - s.at;
        if (!s.sample && (age > ROUTE_STALE_MS || !NavWhisper.current(s))) {
            // Silence is better than a turn told after it is passed.
            NavWhisper.unsay(s);
            Diag.mark(this, String.format(java.util.Locale.ROOT, "nav: dropped late, %s, %.1f s", what, age / 1000f));
            return;
        }
        // The route comes first: the book stops and other apps' sound pauses, rather than both going
        // on quieter under a voice that may be the same.
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes())
                .setOnAudioFocusChangeListener(yield, main)
                .build();
        Awake.hold(this);
        boolean held = false;
        try {
            // The book stops for the hint, on the place it was, and goes on after it.
            held = onMain(true, "route hint");
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                NavWhisper.unsay(s);
                Diag.mark(this, "nav: quiet, no focus, " + what);
                return;
            }
            why = "headphones gone";
            final NavWhisper.Said hint = s;
            boolean whole = pcm != null ? playMade(pcm.pcm, pcm.rate) : speak(s.text, new Fresh() {
                @Override
                public boolean still() {
                    // A turn told after it is passed is worse than silence.
                    return hint.sample || (android.os.SystemClock.elapsedRealtime() - hint.at <= ROUTE_STALE_MS
                            && NavWhisper.current(hint));
                }
            });
            Diag.mark(this, String.format(java.util.Locale.ROOT, "%s%s, %s, %.1f s after",
                    whole ? "nav: said " : "nav: cut, " + why + ", ", what, ahead ? "made ahead" : phone ? "phone voice" : "made now",
                    age / 1000f));
        } finally {
            audio.abandonAudioFocusRequest(focus);
            Awake.let(this);
            if (held) {
                onMain(false, null);
            }
        }
    }

    /** The key a made phrase is kept under: the same words with another voice or pace are another sound. */
    private String madeKey(String text, int reader) {
        return reader + "|" + Prefs.speedShared(this) + "|" + Prefs.ownTempoFactorShared(this, reader) + "|"
                + Prefs.grimPitchShared(this, reader) + "|" + Prefs.grimTimbreShared(this, reader) + "|"
                + Prefs.grimAirShared(this, reader) + "|"
                + Prefs.pauseMsShared(this) + "|" + Prefs.fullDecoderShared(this) + "|" + text;
    }

    /** Makes a route phrase into sound in the background, unless it is made already. */
    private void makeAhead(final String text) {
        maker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    int reader = Prefs.roleShared(Herald.this, Cast.ROUTE);
                    String key = madeKey(text, reader);
                    if (made.containsKey(key) || !NavWhisper.wanted(text) || SpareVoice.wanted(Herald.this)) {
                        return;
                    }
                    // A phrase wanted now goes first; this one can wait a little.
                    for (int i = 0; i < 400 && Voice.urgentNow(); i++) {
                        Thread.sleep(20);
                    }
                    // The urgent one may have been this very phrase, or it is no longer wanted.
                    if (made.containsKey(key) || !NavWhisper.wanted(text)) {
                        return;
                    }
                    Made m = make(text, reader);
                    if (m != null) {
                        made.put(key, m);
                    }
                } catch (Throwable t) {
                    Diag.log(Herald.this, "nav: a phrase not made ahead, " + t.getClass().getSimpleName());
                }
            }
        });
    }

    /** A short phrase in the reader's voice, whole; null when there is no voice yet or no sound came. */
    private Made make(String said, int reader) throws Exception {
        Voice voice = Voice.get(this);
        try {
            voice.prepare();
        } catch (Voice.Missing m) {
            return null;
        }
        String spoken = voice.normalize(Lexicon.get(this).applyRules(TextPrep.clean(said)));
        int rate = voice.sampleRate();
        float speed = Prefs.speedShared(this);
        int pauseMs = Prefs.pauseMsShared(this);
        java.util.List<float[]> pieces = new java.util.ArrayList<float[]>();
        int total = 0;
        boolean[] inside = {false};
        for (String piece : Voice.chunks(spoken, SpeechLanguage.locale())) {
            float[] samples = voice.synthesizeVoice(Foreign.seal(piece, inside), speed, reader);
            pieces.add(samples);
            total += samples.length;
            if (pauseMs > 0) {
                float[] gap = new float[rate * pauseMs / 1000];
                pieces.add(gap);
                total += gap.length;
            }
        }
        if (total == 0) {
            return null;
        }
        float[] out = new float[total];
        int at = 0;
        for (float[] p : pieces) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return new Made(out, rate);
    }

    /** Plays a made phrase into the headphones; false when cut short. */
    private boolean playMade(float[] pcm, int rate) throws InterruptedException {
        AudioDeviceInfo ear = Earpiece.find(audio);
        if ((ear == null && !openAir) || rate <= 0) {
            return false;
        }
        AudioTrack out = openTrack(rate);
        if (ear != null) {
            out.setPreferredDevice(ear);
        }
        cut = false;
        current = out;
        try {
            out.play();
            write(out, pcm);
            if (stopped(out)) {
                return false;
            }
            long deadline = System.currentTimeMillis() + written * 1000L / rate + 1000;
            while (out.getPlaybackHeadPosition() < written && System.currentTimeMillis() < deadline) {
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

    /** A route hint's failure is its own: it never takes a notification down with it. */
    private void sayRouteSafely() {
        try {
            sayRoute();
        } catch (Throwable t) {
            Diag.mark(this, "nav: failed, " + t.getClass().getSimpleName());
        }
    }

    /**
     * On the worker thread: pauses the book, speaks, gives the book back. Returns how it went:
     * SILENT, CUT, CUT_BY_USER or WHOLE. A notification it stays silent about (one with a key)
     * is kept to be told later; before one it reads, it says in the same breath what was missed.
     */
    private int read(String app, String said, String key, String sender) throws Exception {
        // The notification's own words, before anything missed is put in front of them.
        final String own = said;
        // A route hint that came in meanwhile goes first.
        sayRouteSafely();
        if (key != null && gone.remove(key)) {
            Diag.mark(this, "herald: gone before its turn, " + app);
            return SILENT;
        }
        if (quietNow()) {
            Diag.mark(this, "herald: quiet hours, " + app);
            missOnMain(key, app, sender);
            return SILENT;
        }
        if (!mayStart()) {
            Diag.mark(this, (audio.getMode() != AudioManager.MODE_NORMAL
                    ? "herald: quiet, in a call, " : "herald: quiet, no headphones, ") + app);
            missOnMain(key, app, sender);
            return SILENT;
        }
        if (key != null && !Ears.privateNow(this, audio)) {
            // Not one's own headphones, or not answered yet: a short purr instead of words. The
            // notification is kept, to be told when the sound is in one's own ears again.
            missOnMain(key, app, sender, false);
            Diag.mark(this, "herald: purr, not own headphones, " + app);
            purr();
            return SILENT;
        }
        // Hora may speak again: what was missed is said first, in the same breath and the same hold of the book.
        Missed.Telling first = key != null ? prepareTelling() : null;
        if (first != null) {
            said = first.text + ' ' + said;
        }
        boolean held = false;
        // A notification comes after the route but before everything else: other apps' sound pauses
        // for it, as the book does, rather than going on quieter underneath.
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes())
                .setOnAudioFocusChangeListener(yield, main)
                .build();
        Awake.hold(this);
        try {
            // The book stops once for all that follows: a hint waiting now, then the reading.
            held = onMain(true, "notification");
            // From here on a route hint coming in cuts this reading short, at whatever stage it is.
            newsSpeaking = true;
            if (route.get() != null) {
                // A hint came in just now: it goes first, and the reading follows.
                newsSpeaking = false;
                sayRouteSafely();
                newsSpeaking = true;
            }
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Diag.mark(this, "herald: quiet, no focus, " + app);
                missOnMain(key, app, sender);
                return SILENT;
            }
            why = "headphones gone";
            readingKey = key;
            Hush.begin(this, new Runnable() {
                @Override
                public void run() {
                    cutNow("headphone button");
                }
            });
            AudioDeviceInfo startedOn = Earpiece.find(audio);
            readingOn = startedOn == null ? 0 : startedOn.getId();
            boolean whole;
            try {
                whole = speak(said);
            } finally {
                readingOn = 0;
            }
            Diag.mark(this, (whole ? "herald: read, " : "herald: cut, " + why + ", ") + app);
            int how = whole ? WHOLE
                    : "headphone button".equals(why) || "quiet tile".equals(why) ? CUT_BY_USER : CUT;
            told(first, how);
            if (key != null && (how == WHOLE || how == CUT_BY_USER)) {
                // Heard in one's own headphones: kept for the Day until midnight, when switched on.
                // The app's name, said first for some, is already in the row's title.
                String words = own.startsWith(app + ". ") ? own.substring(app.length() + 2) : own;
                try {
                    Heard.add(this, app, words);
                } catch (RuntimeException e) {
                    Diag.mark(this, "heard: not kept, " + e.getClass().getSimpleName());
                }
            }
            if (how == CUT && !"notification gone".equals(why)) {
                // Cut by the world, not by the person: it was not heard, so it is still news, told at the
                // next chance. Not at once: right now another sound or a turn has the floor, and the
                // next notification, the headphones or a call's end will bring it up.
                missOnMain(key, app, sender, false);
            }
            return how;
        } finally {
            newsSpeaking = false;
            readingKey = null;
            Hush.end(this);
            // A hint that cut this reading is said while the book is still held and other sound still
            // paused, so neither bursts back only to step aside again a moment later.
            sayRouteSafely();
            audio.abandonAudioFocusRequest(focus);
            Awake.let(this);
            if (held) {
                onMain(false, null);
            }
        }
    }

    /**
     * Worker thread. The purr, over the book (which steps aside, not stops), at most once in a
     * few seconds: a burst of notifications is one purr.
     */
    private void purr() throws InterruptedException {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastPurr < PURR_APART_MS) {
            return;
        }
        lastPurr = now;
        int rate = 24000;
        float[] pcm = Purr.make(rate, 0.45f);
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes())
                .setOnAudioFocusChangeListener(yield, main)
                .build();
        Awake.hold(this);
        BookPlayer.duck(true);
        try {
            if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                // The book takes a third of a second to step aside; the purr waits for it.
                Thread.sleep(300);
                playMade(pcm, rate);
            }
        } finally {
            audio.abandonAudioFocusRequest(focus);
            BookPlayer.duck(false);
            Awake.let(this);
        }
    }

    /**
     * Main thread. A wireless device not yet decided is asked about, in a notification: own
     * headphones, or a speaker or a car. Until then it is taken as the latter. Asked again on a
     * later connection while still undecided (a question swiped away is not an answer), at most
     * twice a day. Where notifications cannot show, Hora says so once into the device itself:
     * nothing private, only where to answer.
     */
    private void askIfNew() {
        if (audio == null || destroyed) {
            return;
        }
        AudioDeviceInfo d = Ears.wireless(audio);
        if (d == null) {
            return;
        }
        String key = Ears.key(d);
        String name = Ears.name(d);
        Ears.seen(this, key, name);
        if (Ears.state(this, key) != Ears.UNKNOWN || !Ears.mayAsk(this, key)) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            if (nm.getNotificationChannel(EARS_CHANNEL) == null) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(EARS_CHANNEL,
                        getString(R.string.ears_channel), NotificationManager.IMPORTANCE_HIGH);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
            android.app.NotificationChannel ch = nm.getNotificationChannel(EARS_CHANNEL);
            boolean shows = nm.areNotificationsEnabled() && ch != null
                    && ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
            Ears.asked(this, key);
            if (!shows) {
                Diag.mark(this, "herald: a new wireless device, notifications off, said where to answer");
                final String said = SpeechLanguage.resources(this).getString(R.string.ears_q_spoken);
                waiting.incrementAndGet();
                try {
                    worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            read("device question", said, null, null);
                        } catch (Throwable t) {
                            Diag.mark(Herald.this, "herald: device question failed, " + t.getClass().getSimpleName());
                        } finally {
                            waiting.decrementAndGet();
                        }
                    }
                    });
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    waiting.decrementAndGet();
                }
                return;
            }
            Diag.mark(this, "herald: a new wireless device, asked whose it is");
            int id = Ears.noteId(key);
            Notification n = new Notification.Builder(this, EARS_CHANNEL)
                    .setSmallIcon(R.drawable.ic_note)
                    .setContentTitle(getString(R.string.ears_q_title))
                    .setStyle(new Notification.BigTextStyle().bigText(getString(R.string.ears_q_text, name)))
                    .setContentText(getString(R.string.ears_q_text, name))
                    .setContentIntent(android.app.PendingIntent.getActivity(this, id,
                            new Intent(this, HeraldActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE))
                    .setAutoCancel(true)
                    .addAction(new Notification.Action.Builder(null, getString(R.string.ears_q_yes),
                            answer(key, Ears.MINE, id)).build())
                    .addAction(new Notification.Action.Builder(null, getString(R.string.ears_q_no),
                            answer(key, Ears.OPEN, id)).build())
                    .build();
            nm.notify(Ears.NOTE_TAG, id, n);
        } catch (RuntimeException e) {
            Diag.mark(this, "herald: could not ask about the device, " + e.getClass().getSimpleName());
        }
    }

    private android.app.PendingIntent answer(String key, int state, int note) {
        Intent i = new Intent(this, EarsAnswer.class)
                .putExtra(EarsAnswer.EXTRA_KEY, key)
                .putExtra(EarsAnswer.EXTRA_STATE, state)
                .putExtra(EarsAnswer.EXTRA_NOTE, note);
        return android.app.PendingIntent.getBroadcast(this, key.hashCode() * 4 + state, i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
    }

    /** From the worker: keeps a notification it stayed silent about; the list is touched on the main thread. */
    private void missOnMain(String key, String app, String sender) {
        missOnMain(key, app, sender, true);
    }

    private void missOnMain(final String key, final String app, final String sender, final boolean look) {
        if (key == null) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                miss(key, app, sender, look);
            }
        });
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
    private boolean onMain(final boolean hold, final String why) throws InterruptedException {
        final boolean[] result = new boolean[1];
        final CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                if (hold) {
                    result[0] = BookPlayer.holdForNews(why);
                } else {
                    BookPlayer.releaseAfterNews();
                }
                done.countDown();
            }
        });
        done.await(2, TimeUnit.SECONDS);
        return result[0];
    }

    /** Whether a phrase is still worth saying after a delay. */
    private interface Fresh {
        boolean still();
    }

    /** Returns true when everything was said, false when cut short. */
    private boolean speak(String said) throws Exception {
        return speak(said, null);
    }

    /** The same, given up rather than said late when fresh says the moment has passed. */
    private boolean speak(String said, Fresh fresh) throws Exception {
        boolean phoneTried = false;
        if (SpareVoice.wanted(this)) {
            phoneTried = true;
            SpareVoice.clearFailure();
            // Asked for: the phone's own voice says it, quickly, still only into the headphones.
            if (speakByPhone(said)) {
                return true;
            }
            if (cut || !SpareVoice.failedOnItsOwn() || SpareVoice.partlyHeard()) {
                // Stopped for a reason of its own (headphones gone, another sound), or perhaps heard
                // in part before the engine gave up: saying it all again would be worse.
                return false;
            }
            if (fresh != null && !fresh.still()) {
                Diag.mark(this, "spare voice: " + SpareVoice.failure() + ", too late for Hora's voice");
                return false;
            }
            // The phone's engine let it down: Hora's own voice says it rather than nobody.
            Diag.mark(this, "spare voice: " + SpareVoice.failure() + ", Hora's voice instead");
        }
        Voice voice = Voice.get(this);
        try {
            voice.prepare();
        } catch (Voice.Missing m) {
            // No voice pack yet: the phone's own voice reads it, still only into the headphones,
            // unless it has just failed at that very phrase.
            return !phoneTried && speakByPhone(said);
        }
        if (phoneTried && (cut || (fresh != null && !fresh.still()))) {
            // Cut, or overtaken, while the voice was being made ready.
            return false;
        }
        return speakOwn(voice, said, !phoneTried);
    }

    /** The phone's own engine says it, only while the headphones are on. */
    private boolean speakByPhone(String said) {
        if (Earpiece.find(audio) == null && !openAir) {
            why = "headphones gone";
            return false;
        }
        cut = false;
        phoneSpeaking = true;
        try {
            return SpareVoice.say(this, TextPrep.clean(said), new Voice.Cancel() {
                @Override
                public boolean cancelled() {
                    if (newsSpeaking && turnWaiting()) {
                        why = "route hint";
                        return true;
                    }
                    if (deviceMoved()) {
                        why = "device changed";
                        return true;
                    }
                    return cut || (!openAir && !Earpiece.present(audio));
                }
            });
        } finally {
            phoneSpeaking = false;
            if (!cut && SpareVoice.failedOnItsOwn()) {
                why = "phone voice: " + SpareVoice.failure();
            }
        }
    }

    /** Whether the phone's voice is saying this service's words now. */
    private volatile boolean phoneSpeaking;

    /** Hora's own voice says it; resetCut is false when it takes over a phrase, so a cut meanwhile stands. */
    private boolean speakOwn(Voice voice, String said, boolean resetCut) throws Exception {
        String spoken = voice.normalize(Lexicon.get(this).applyRules(TextPrep.clean(said)));
        int rate = voice.sampleRate();
        float speed = Prefs.speedShared(this);
        int pauseMs = Prefs.pauseMsShared(this);
        int reader = Prefs.roleShared(this, talkingAs);
        AudioDeviceInfo ear = Earpiece.find(audio);
        if (ear == null && !openAir) {
            return false;
        }
        AudioTrack out = openTrack(rate);
        if (ear != null) {
            out.setPreferredDevice(ear);
        }
        if (resetCut) {
            cut = false;
        } else if (cut) {
            out.release();
            return false;
        }
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

    /**
     * Stop when told to, when the headphones are gone, or when the sound went elsewhere. A
     * notification also stops for a route hint waiting behind it: a turn will not wait.
     */
    private boolean stopped(AudioTrack out) {
        if (cut) {
            return true;
        }
        if (openAir) {
            // A route hint said aloud on purpose: wherever the sound goes is fine.
            return false;
        }
        if (!Earpiece.present(audio)) {
            return true;
        }
        if (deviceMoved()) {
            why = "device changed";
            return true;
        }
        if (newsSpeaking && turnWaiting()) {
            why = "route hint";
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
        // The phone's voice, if it is saying this service's words, stops at once too.
        if (phoneSpeaking) {
            SpareVoice.stopNow();
        }
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
