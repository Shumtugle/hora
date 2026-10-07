package com.shumtugle.hora;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * System text-to-speech engine. Other apps (readers, navigators) reach the
 * built-in voice through it. Runs in the voice process and shares its engine.
 */
public final class HoraTtsService extends TextToSpeechService {
    /**
     * Voice names are relative: "main" is whatever the settings call the main
     * voice, "alt" is the other one. Readers usually pin the voice they got at
     * start-up, so absolute names would make the settings switch look broken.
     */
    static final String VOICE_MAIN = "ru-ru-x-hora-main";
    static final String VOICE_ALT = "ru-ru-x-hora-alt";
    // Names used by earlier builds; readers may still have them saved.
    private static final String LEGACY_FIRST = "ru-ru-x-hora-female";
    private static final String LEGACY_SECOND = "ru-ru-x-hora-male";
    /** Prefix of voice names that pin one numbered voice as the narrator. */
    static final String NUMBERED = "ru-ru-x-hora-";

    private final AtomicInteger generation = new AtomicInteger();
    /** One piece at a time is made ahead; the voice is never asked for two at once. */
    private final ExecutorService maker = Executors.newSingleThreadExecutor();

    @Override
    public java.util.List<android.speech.tts.Voice> onGetVoices() {
        java.util.List<android.speech.tts.Voice> list = new java.util.ArrayList<android.speech.tts.Voice>();
        java.util.Locale loc = new java.util.Locale(SpeechLanguage.CODE, SpeechLanguage.COUNTRY);
        java.util.List<String> names = new java.util.ArrayList<String>();
        names.add(VOICE_MAIN);
        names.add(VOICE_ALT);
        for (int v = 1; v <= Cast.COUNT; v++) {
            names.add(NUMBERED + v);
        }
        for (String name : names) {
            list.add(new android.speech.tts.Voice(name, loc,
                    android.speech.tts.Voice.QUALITY_HIGH, android.speech.tts.Voice.LATENCY_NORMAL,
                    false, new java.util.HashSet<String>()));
        }
        return list;
    }

    @Override
    public int onIsValidVoiceName(String name) {
        return VOICE_MAIN.equals(name) || VOICE_ALT.equals(name)
                || LEGACY_FIRST.equals(name) || LEGACY_SECOND.equals(name)
                || numbered(name) > 0 ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }

    @Override
    public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        return SpeechLanguage.isLanguage(lang) ? VOICE_MAIN : null;
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        if (!matches(lang)) {
            return TextToSpeech.LANG_NOT_SUPPORTED;
        }
        return SpeechLanguage.isCountry(country)
                ? TextToSpeech.LANG_COUNTRY_AVAILABLE
                : TextToSpeech.LANG_AVAILABLE;
    }

    @Override
    protected String[] onGetLanguage() {
        return new String[] {SpeechLanguage.ISO3, SpeechLanguage.COUNTRY3, ""};
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        return onIsLanguageAvailable(lang, country, variant);
    }

    @Override
    public void onDestroy() {
        maker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        generation.incrementAndGet();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Diag.mark(this, "engine: service created");
    }

    @Override
    public int onLoadVoice(String name) {
        int r = onIsValidVoiceName(name);
        Diag.log(this, "engine: load voice " + name + " -> " + r);
        return r;
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        Awake.hold(this);
        try {
            synthesize(request, callback);
        } catch (Throwable t) {
            Diag.log(this, "engine: synthesis failed", t);
            callback.error();
        } finally {
            Awake.let(this);
        }
    }

    private void synthesize(SynthesisRequest request, SynthesisCallback callback) {
        final int gen = generation.get();
        Voice voice = Voice.get(this);
        CharSequence rawText = request.getCharSequenceText();
        Diag.log(this, "engine: request lang=" + request.getLanguage() + "-" + request.getCountry()
                + " voice=" + request.getVoiceName() + " rate=" + request.getSpeechRate()
                + " chars=" + (rawText == null ? -1 : rawText.length()));
        // Open the stream before the slow part. Readers learn the engine has taken
        // the request from this call; a cold start (unpacking, loading the model)
        // can otherwise look like silence and make them give up.
        int rate = voice.isReady() ? voice.sampleRate() : Voice.MODEL_RATE;
        if (callback.start(rate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
            Diag.mark(this, "engine: output refused the stream");
            return;
        }
        try {
            voice.prepare();
        } catch (Throwable t) {
            Diag.log(this, "engine: voice failed to load", t);
            callback.error();
            return;
        }
        if (voice.sampleRate() != rate) {
            Diag.mark(this, "engine: model rate " + voice.sampleRate() + " differs from announced " + rate);
        }
        CharSequence raw = request.getCharSequenceText();
        String given = raw == null ? "" : raw.toString();
        Diag.log(this, "engine: raw " + TextPrep.reveal(given.length() > 240 ? given.substring(0, 240) + "..." : given));
        String text = voice.normalize(Lexicon.get(this).applyRules(TextPrep.clean(given)));
        Diag.log(this, "engine: text " + (text.length() > 240 ? text.substring(0, 240) + "..." : text));
        // Settings decide; "alt" lends the narrator part to the first dialogue voice,
        // and a numbered name pins that voice as the narrator.
        String asked = request.getVoiceName();
        int narrator = numbered(asked);
        if (VOICE_ALT.equals(asked) || LEGACY_SECOND.equals(asked)) {
            narrator = Prefs.roleShared(this, Cast.SPEAKER_A);
        }
        // The system rate slider scales the tempo chosen in this app's settings.
        float speed = Math.max(Prefs.SPEED_MIN, Math.min(Prefs.SPEED_MAX,
                request.getSpeechRate() / 100f * Prefs.speedShared(this)));
        int pauseMs = Prefs.pauseMsShared(this);
        int produced = 0;
        // The platform lets the engine run only a little ahead of the speaker, so a piece
        // made after the last one is handed over leaves a gap as long as its own synthesis.
        // The next piece is made while the current one is handed over; the first is short.
        java.util.List<Voice.Part> parts = new java.util.ArrayList<Voice.Part>();
        for (Voice.Part p : Voice.quickParts(text, SpeechLanguage.locale())) {
            if (!p.text.isEmpty()) {
                parts.add(p);
            }
        }
        long started = android.os.SystemClock.elapsedRealtime();
        Future<float[]> next = parts.isEmpty() ? null : ahead(voice, parts.get(0), speed, narrator);
        for (int i = 0; i < parts.size(); i++) {
            float[] samples;
            try {
                samples = next.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                Diag.log(this, "engine: synthesis failed", e.getCause());
                callback.error();
                return;
            }
            if (i == 0) {
                Diag.log(this, "engine: first sound after " + (android.os.SystemClock.elapsedRealtime() - started) + " ms");
            }
            if (gen != generation.get()) {
                return;
            }
            next = i + 1 < parts.size() ? ahead(voice, parts.get(i + 1), speed, narrator) : null;
            if (i > 0 && pauseMs > 0) {
                send(callback, new byte[rate * pauseMs / 1000 * 2]);
            }
            produced += samples.length;
            if (!send(callback, toPcm16(samples))) {
                Diag.log(this, "engine: output closed early");
                if (next != null) {
                    next.cancel(false);
                }
                return;
            }
        }
        callback.done();
        Diag.log(this, "engine: done, " + produced / (float) rate + " s of speech in "
                + (android.os.SystemClock.elapsedRealtime() - started) + " ms");
    }

    /** Makes one piece on the side thread, so the next is ready while this one is heard. */
    private Future<float[]> ahead(final Voice voice, final Voice.Part part, final float speed, final int narrator) {
        return maker.submit(new Callable<float[]>() {
            @Override
            public float[] call() {
                return voice.synthesizeRole(part.text, speed, part.role, narrator);
            }
        });
    }

    /** Voice number from a numbered voice name, or 0. */
    private static int numbered(String name) {
        if (name == null || !name.startsWith(NUMBERED)) {
            return 0;
        }
        try {
            int v = Integer.parseInt(name.substring(NUMBERED.length()));
            return v >= 1 && v <= Cast.COUNT ? v : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean matches(String lang) {
        return SpeechLanguage.isLanguage(lang);
    }

    private static boolean send(SynthesisCallback cb, byte[] pcm) {
        int max = cb.getMaxBufferSize();
        for (int off = 0; off < pcm.length; off += max) {
            int n = Math.min(max, pcm.length - off);
            if (cb.audioAvailable(pcm, off, n) != TextToSpeech.SUCCESS) {
                return false;
            }
        }
        return true;
    }

    private static byte[] toPcm16(float[] s) {
        byte[] out = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            float v = Math.max(-1f, Math.min(1f, s[i]));
            int x = Math.round(v * 32767f);
            out[2 * i] = (byte) x;
            out[2 * i + 1] = (byte) (x >> 8);
        }
        return out;
    }
}
