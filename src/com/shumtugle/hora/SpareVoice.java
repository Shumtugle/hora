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
 * The phone's own speech engine, used while the voice pack is not on the phone:
 * short things (the time, the weather, a reply, a notification) are still said,
 * in another voice. This app's engine is never chosen here, since it has nothing
 * to speak with yet. Blocking: never on the main thread.
 */
final class SpareVoice {
    // AudioAttributes.USAGE_ASSISTANT; not available at the compile-time API level.
    private static final int USAGE_ASSISTANT = 16;
    private static final String SERVICE = "android.intent.action.TTS_SERVICE";
    private static boolean told;

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

    /** Says the text and waits until it is said; false when there is no other engine or it failed. */
    static boolean say(Context c, String text, final Voice.Cancel cancel) {
        String said = text == null ? "" : text.trim();
        if (said.isEmpty()) {
            return true;
        }
        String engine = engine(c.getApplicationContext());
        if (engine == null) {
            Diag.mark(c, "spare voice: the phone has no other speech engine");
            return false;
        }
        final CountDownLatch ready = new CountDownLatch(1);
        final int[] status = {TextToSpeech.ERROR};
        final TextToSpeech tts = new TextToSpeech(c.getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int s) {
                status[0] = s;
                ready.countDown();
            }
        }, engine);
        try {
            if (!ready.await(8, TimeUnit.SECONDS) || status[0] != TextToSpeech.SUCCESS) {
                Diag.mark(c, "spare voice: the other engine did not start");
                return false;
            }
            tts.setLanguage(SpeechLanguage.locale());
            tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            final CountDownLatch done = new CountDownLatch(1);
            final boolean[] fine = {false};
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                }

                @Override
                public void onDone(String id) {
                    fine[0] = true;
                    done.countDown();
                }

                @Override
                public void onError(String id) {
                    done.countDown();
                }
            });
            if (tts.speak(said, TextToSpeech.QUEUE_FLUSH, null, "spare") != TextToSpeech.SUCCESS) {
                return false;
            }
            if (!told) {
                told = true;
                Diag.mark(c, "spare voice: the phone's own voice speaks until the voice pack is fetched");
            }
            // Long enough for slow speech; cut short when the caller says so.
            long limit = System.currentTimeMillis() + 3000 + 150L * said.length();
            while (!done.await(200, TimeUnit.MILLISECONDS)) {
                if ((cancel != null && cancel.cancelled()) || System.currentTimeMillis() > limit) {
                    tts.stop();
                    return false;
                }
            }
            return fine[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            tts.shutdown();
        }
    }
}
