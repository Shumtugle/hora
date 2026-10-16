package com.shumtugle.hora;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.AudioAttributes;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The phone's own speech engine, used while the voice pack is not on the phone,
 * and when the person asks for it: short things (the time, the weather, a reply,
 * a notification, a route hint) are said in the phone's voice, quickly. This
 * app's own engine is never chosen here. Blocking: never on the main thread.
 */
final class SpareVoice {
    // AudioAttributes.USAGE_ASSISTANT; not available at the compile-time API level.
    private static final int USAGE_ASSISTANT = 16;
    private static final String SERVICE = "android.intent.action.TTS_SERVICE";
    private static boolean told;
    /**
     * Why the last phrase was not said, in a few words for the journal; empty when it was. "called
     * off" means the caller stopped it: everything else is the engine's own trouble.
     */
    private static final ThreadLocal<String> failure = new ThreadLocal<String>() {
        @Override
        protected String initialValue() {
            return "";
        }
    };
    /** Said once per process: the engine has no voice for the language. */
    private static boolean langTold;
    /** The engine and language last found without a voice: later phrases skip straight past it. */
    private static volatile String voiceless = "";
    static final String CALLED_OFF = "called off";
    /** The engine's error code for a phrase, kept until its turn is over. */
    private static final java.util.Map<String, Integer> errors =
            new java.util.concurrent.ConcurrentHashMap<String, Integer>();

    private SpareVoice() {
    }

    /** The engine to use: the phone's default unless that is this app, then any other; null if none. */
    static String engine(Context c) {
        String own = c.getPackageName();
        String chosen = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.TTS_DEFAULT_SYNTH);
        if (chosen != null && !chosen.isEmpty() && !chosen.equals(own)) {
            return chosen;
        }
        List<ResolveInfo> all = c.getPackageManager().queryIntentServices(new Intent(SERVICE), 0);
        for (ResolveInfo r : all) {
            if (r.serviceInfo != null && !own.equals(r.serviceInfo.packageName)) {
                return r.serviceInfo.packageName;
            }
        }
        return null;
    }

    /**
     * Whether short speech should go to the phone's engine now: the person asked for it and the
     * phone has an engine other than this app.
     */
    static boolean wanted(Context c) {
        return Prefs.phoneVoiceShared(c) && engine(c.getApplicationContext()) != null;
    }

    /** One engine kept between phrases, so a phrase does not wait for the engine to start each time. */
    private static volatile TextToSpeech kept;
    private static String keptFor;
    private static volatile long lastUsed;
    private static final long KEEP_MS = 120_000;
    private static final java.util.Map<String, CountDownLatch> waiting =
            new java.util.concurrent.ConcurrentHashMap<String, CountDownLatch>();
    private static final java.util.Set<String> fine =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static int next;

    /** Off the main thread: letting go may wait for the engine lock while an engine starts. */
    private static android.os.Handler later;

    private static synchronized android.os.Handler later() {
        if (later == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("spare-voice");
            t.start();
            later = new android.os.Handler(t.getLooper());
        }
        return later;
    }

    /** Lets the kept engine go once it has been idle for a while, so it is not held for good. */
    private static void letGoLater() {
        later().removeCallbacksAndMessages(null);
        later().postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (ENGINE) {
                    if (kept != null && !busy && System.currentTimeMillis() - lastUsed >= KEEP_MS) {
                        kept.shutdown();
                        kept = null;
                    }
                }
            }
        }, KEEP_MS + 1000);
    }

    /** Guards the kept engine; held only briefly, never while a phrase is spoken. */
    private static final Object ENGINE = new Object();
    /** One phrase at a time: held while a phrase is spoken. */
    private static final Object TURN = new Object();
    private static volatile boolean busy;

    /** The kept engine for the given package, started if need be; null if it does not start. */
    private static TextToSpeech engineFor(Context c, String engine) throws InterruptedException {
        synchronized (ENGINE) {
            return engineLocked(c, engine);
        }
    }

    private static TextToSpeech engineLocked(Context c, String engine) throws InterruptedException {
        long now = System.currentTimeMillis();
        if (kept != null && (!engine.equals(keptFor) || now - lastUsed > KEEP_MS)) {
            kept.shutdown();
            kept = null;
        }
        if (kept != null) {
            return kept;
        }
        final CountDownLatch ready = new CountDownLatch(1);
        final int[] status = {TextToSpeech.ERROR};
        TextToSpeech tts = new TextToSpeech(c.getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int s) {
                status[0] = s;
                ready.countDown();
            }
        }, engine);
        if (!ready.await(8, TimeUnit.SECONDS) || status[0] != TextToSpeech.SUCCESS) {
            tts.shutdown();
            Diag.mark(c, "spare voice: the other engine did not start");
            return null;
        }
        tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
            }

            @Override
            public void onDone(String id) {
                fine.add(id);
                CountDownLatch l = waiting.remove(id);
                if (l != null) {
                    l.countDown();
                }
            }

            @Override
            public void onError(String id) {
                CountDownLatch l = waiting.remove(id);
                if (l != null) {
                    l.countDown();
                }
            }

            @Override
            public void onError(String id, int code) {
                // Kept only for a phrase still waiting: a late error after a timeout is dropped.
                if (waiting.containsKey(id)) {
                    errors.put(id, code);
                }
                onError(id);
            }

            @Override
            public void onStop(String id, boolean interrupted) {
                CountDownLatch l = waiting.remove(id);
                if (l != null) {
                    l.countDown();
                }
            }
        });
        kept = tts;
        keptFor = engine;
        lastUsed = now;
        return tts;
    }

    /** Says the text and waits until it is said; false when there is no other engine or it failed. */
    static boolean say(Context c, String text, final Voice.Cancel cancel) {
        return say(c, text, 1f, cancel);
    }

    /**
     * The same at a pace relative to the common one (1 for the tempo of the settings). One phrase
     * at a time: the bot and the headphones take turns rather than cut each other off.
     */
    static boolean say(Context c, String text, float pace, final Voice.Cancel cancel) {
        synchronized (TURN) {
            busy = true;
            failure.set("");
            try {
                return sayNow(c, text, pace, cancel);
            } finally {
                busy = false;
                letGoLater();
            }
        }
    }

    /** Why the last phrase failed, or "" when it was said. */
    static String failure() {
        return failure.get();
    }

    /** Forgets the last failure on this thread, before an attempt that may not reach the engine. */
    static void clearFailure() {
        failure.set("");
    }

    /** Whether the last phrase failed through the engine itself, not because the caller stopped it. */
    static boolean failedOnItsOwn() {
        String f = failure.get();
        return !f.isEmpty() && !CALLED_OFF.equals(f);
    }

    /** Whether the last phrase may have been heard in part before the engine gave up on it. */
    static boolean partlyHeard() {
        return SLOW.equals(failure.get());
    }

    private static final String SLOW = "engine too slow";

    /** A refused phrase may mean a dead connection: the kept engine is started afresh next time. */
    private static void dropKept() {
        synchronized (ENGINE) {
            if (kept != null) {
                kept.shutdown();
                kept = null;
            }
        }
    }

    /** Stops the phrase being said now, if any: a route hint comes first, a cut is at once. */
    static void stopNow() {
        TextToSpeech t = kept;
        if (t != null) {
            try {
                t.stop();
            } catch (RuntimeException ignored) {
                // Let go meanwhile.
            }
        }
    }

    private static boolean sayNow(Context c, String text, float pace, final Voice.Cancel cancel) {
        String said = text == null ? "" : text.trim();
        if (said.isEmpty()) {
            return true;
        }
        String engine = engine(c.getApplicationContext());
        if (engine == null) {
            Diag.mark(c, "spare voice: the phone has no other speech engine");
            failure.set("no engine");
            return false;
        }
        if (voiceless.equals(engine + "|" + SpeechLanguage.locale())) {
            // Found without a voice for this language already: no reason to wait for it again.
            failure.set("no voice for the language");
            return false;
        }
        try {
            TextToSpeech tts = engineFor(c, engine);
            if (tts == null) {
                failure.set("engine did not start");
                return false;
            }
            int lang = tts.setLanguage(SpeechLanguage.locale());
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                // The engine has no voice for the language on this phone: it would say nothing.
                if (!langTold) {
                    langTold = true;
                    Diag.mark(c, "spare voice: " + engine + " has no voice for the language, " + lang);
                }
                failure.set("no voice for the language");
                voiceless = engine + "|" + SpeechLanguage.locale();
                return false;
            }
            // The engine's own normal pace stands for the default tempo of the settings.
            tts.setSpeechRate(Math.max(0.5f, Math.min(2f, Prefs.speedShared(c) / Prefs.SPEED_DEFAULT * pace)));
            if (cancel != null && cancel.cancelled()) {
                // Called off while waiting for the turn or for the engine to start.
                failure.set(CALLED_OFF);
                return false;
            }
            String id = "spare-" + (++next);
            CountDownLatch done = new CountDownLatch(1);
            waiting.put(id, done);
            if (tts.speak(said, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                waiting.remove(id);
                failure.set("engine refused the phrase");
                dropKept();
                return false;
            }
            if (!told) {
                told = true;
                Diag.mark(c, "spare voice: the phone's own voice speaks, " + engine);
            }
            // Long enough for slow speech; cut short when the caller says so.
            long limit = System.currentTimeMillis() + 3000 + 150L * said.length();
            while (!done.await(200, TimeUnit.MILLISECONDS)) {
                boolean off = cancel != null && cancel.cancelled();
                if (off || System.currentTimeMillis() > limit) {
                    tts.stop();
                    waiting.remove(id);
                    errors.remove(id);
                    failure.set(off ? CALLED_OFF : SLOW);
                    return false;
                }
            }
            lastUsed = System.currentTimeMillis();
            if (fine.remove(id)) {
                return true;
            }
            Integer code = errors.remove(id);
            failure.set(code != null ? "engine error " + code : "engine stopped");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure.set(CALLED_OFF);
            return false;
        } catch (RuntimeException e) {
            Diag.log(c, "spare voice: failed", e);
            failure.set("engine failed");
            synchronized (ENGINE) {
                if (kept != null) {
                    kept.shutdown();
                    kept = null;
                }
            }
            return false;
        }
    }
}
