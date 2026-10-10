package com.shumtugle.hora;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * The built-in voice, one per process. Bundled files live in the "voice" asset
 * folder and are unpacked once into private storage, because the synthesizer
 * reads real paths. The voice timbre comes from a short reference recording.
 * Synthesis is serialized: the native engine is not thread-safe.
 */
final class Voice {
    interface Cancel {
        boolean cancelled();
    }

    /** The model loses volume on long inputs; text is fed in pieces no longer than this. */
    static final int MAX_CHUNK = 180;
    /** The first piece heard after a silence is kept short, so the voice starts sooner. */
    static final int START_CHUNK = 90;
    /** Short sentences are read together up to this length, so the voice carries its intonation across them. */
    static final int MERGE_CHUNK = 140;
    /** Silence kept before and after the sound of a chunk; the rest of the model's own silence goes. */
    private static final float LEAD_S = 0.03f;
    private static final float TAIL_S = 0.12f;

    private static final String SLOT = "voice";
    private static final String MARKER = ".unpacked";
    private static final int THREADS = 4;
    private boolean fullDecoder;
    /** Seconds of speech made and seconds spent making it, since the last reading of the ratio. */
    private double madeSeconds;
    private double spentSeconds;
    // AudioAttributes.USAGE_ASSISTANT; not available at the compile-time API level.
    private static final int USAGE_ASSISTANT = 16;
    // Other apps (screen recorders) may not capture what this voice plays.
    private static final int CAPTURE_NONE = android.media.AudioAttributes.ALLOW_CAPTURE_BY_NONE;

    /** Output rate of the bundled model, known before it is loaded. */
    static final int MODEL_RATE = 24000;

    private static Voice instance;

    private final Context context;
    private OfflineTts engine;
    /** The English model, made when English is first heard; null while there is none. */
    private OfflineTts english;
    private boolean englishTried;
    /**
     * Tempo for English: the same person, clearly slower than in the native language,
     * chosen by ear over the pack's own suggestion, which installed packs still carry.
     */
    private static final float ENGLISH_TEMPO = 0.75f;
    private float englishTempo = ENGLISH_TEMPO;
    /** Said before an English chunk, so the model does not swallow its first word. */
    private String englishLead = "\u2014 ";
    private final float[] levelEn = new float[Cast.COUNT + 1];
    /** Silence between a native and an English piece of one chunk. */
    private static final float LANGUAGE_GAP_S = 0.12f;
    private Breath breath;
    private final Sample[] samples = new Sample[2 * (Cast.COUNT + 1)];
    private File root;

    /** A voice sample and the file state it was read from. */
    private static final class Sample {
        float[] audio;
        int rate;
        String stamp = "";
    }

    /** A piece of text and the role that reads it. */
    static final class Part {
        final String text;
        final int role;

        Part(String text, int role) {
            this.text = text;
            this.role = role;
        }
    }

    /**
     * Dialogue turn state, kept across calls because readers send text one
     * paragraph at a time: consecutive dialogue paragraphs alternate between
     * the two speakers, and a narrator paragraph starts a new exchange.
     */
    private static int lastSpeaker = 0;

    // Dashes that open direct speech and separate it from the author's remarks.
    private static final java.util.regex.Pattern QUOTES =
            java.util.regex.Pattern.compile("[\u00ab\u00bb\u201e\u201c\u201d\"]");
    private static final java.util.regex.Pattern REMARK_SPLIT =
            java.util.regex.Pattern.compile("\\s[\u2014\u2013]\\s|\\s-\\s?(?=\\p{L})");
    private AudioTrack track;
    private TextPrep prep;
    private Respell respell;
    private ModelStress modelStress;

    static synchronized Voice get(Context context) {
        if (instance == null) {
            instance = new Voice(context.getApplicationContext());
        }
        return instance;
    }

    private Voice(Context context) {
        this.context = context;
    }

    synchronized void prepare() throws IOException {
        boolean full = Prefs.fullDecoderShared(context);
        if (engine != null && full == fullDecoder) {
            return;
        }
        if (engine != null) {
            // The decoder was switched in the lab: the engine is built again around the other one.
            engine.release();
            engine = null;
            releaseEnglish();
            Diag.mark(context, "voice: decoder switched, full " + full);
        }
        fullDecoder = full;
        File root = new File(context.getFilesDir(), SLOT);
        File marker = new File(root, MARKER);
        // Unpacked again only when the voice itself changed, not with every new build of the app.
        String stamp = BuildConfigLite.assetStamp(context, SLOT + "/stamp.txt");
        if (!marker.isFile() || !stamp.equals(readSmall(marker))) {
            deleteTree(root);
            copyAssetTree(context.getAssets(), SLOT, root);
            writeSmall(marker, stamp);
        }

        this.root = root;
        Cast.forgetOwnSamples(context);
        sample(1);
        List<String> pieces = vocabulary(new File(root, "vocab.json"));
        respell = new Respell(SpeechLanguage.resources(context));
        modelStress = new ModelStress(pieces, StressNet.get(context), ModelStress.clitics(context));
        prep = new TextPrep(SpeechLanguage.resources(context), TextPrep.charsOf(pieces));

        OfflineTtsConfig config = new OfflineTtsConfig();
        config.model.numThreads = THREADS;
        config.model.pocket.lmFlow = path(root, "lm_flow.onnx");
        config.model.pocket.lmMain = path(root, "lm_main.onnx");
        config.model.pocket.encoder = path(root, "encoder.onnx");
        File whole = new File(root, "decoder_full.onnx");
        config.model.pocket.decoder = path(root, fullDecoder && whole.isFile() ? "decoder_full.onnx" : "decoder.onnx");
        config.model.pocket.textConditioner = path(root, "text_conditioner.onnx");
        config.model.pocket.vocabJson = path(root, "vocab.json");
        config.model.pocket.tokenScoresJson = path(root, "token_scores.json");
        config.model.pocket.voiceEmbeddingCacheCapacity = 4;
        engine = new OfflineTts(config);
    }

    /**
     * Makes arbitrary text readable for this voice. Call after prepare().
     * With the English module in place, English spans are marked and left as
     * they are, for the English model; the rest is rewritten for the native one.
     */
    synchronized String normalize(String text) {
        // Marks already in a source text are not ours and are dropped before marking.
        String bare = Foreign.plain(text);
        String marked = LanguagePack.installed(context, LanguagePack.ENGLISH) ? Foreign.mark(bare) : bare;
        if (!Foreign.has(marked)) {
            return nativeText(marked);
        }
        StringBuilder out = new StringBuilder();
        for (Foreign.Piece p : Foreign.pieces(marked)) {
            if (out.length() > 0) {
                out.append(' ');
            }
            if (p.english) {
                out.append(Foreign.OPEN).append(p.text).append(Foreign.CLOSE);
            } else {
                out.append(nativeText(p.text));
            }
        }
        return out.toString();
    }

    /** A text asked for in English, read whole by the English model. */
    String normalizeEnglish(String text) {
        return Foreign.whole(Foreign.plain(text).trim());
    }

    /** Whether English can be read at all: the module is installed. */
    boolean speaksEnglish() {
        return LanguagePack.installed(context, LanguagePack.ENGLISH);
    }

    /** The English model, built once from the module beside the native model's encoder and decoder. */
    private synchronized OfflineTts englishEngine() {
        if (english != null || englishTried || root == null) {
            return english;
        }
        englishTried = true;
        if (!LanguagePack.installed(context, LanguagePack.ENGLISH)) {
            return null;
        }
        try {
            File en = LanguagePack.dir(context, LanguagePack.ENGLISH);
            org.json.JSONObject set = LanguagePack.settings(context, LanguagePack.ENGLISH);
            englishLead = set.optString("lead", englishLead);
            OfflineTtsConfig config = new OfflineTtsConfig();
            config.model.numThreads = THREADS;
            config.model.pocket.lmFlow = path(en, "lm_flow.onnx");
            config.model.pocket.lmMain = path(en, "lm_main.onnx");
            config.model.pocket.textConditioner = path(en, "text_conditioner.onnx");
            config.model.pocket.vocabJson = path(en, "vocab.json");
            config.model.pocket.tokenScoresJson = path(en, "token_scores.json");
            // Both languages share the sound coder: the same encoder, the same decoder.
            config.model.pocket.encoder = path(root, "encoder.onnx");
            File whole = new File(root, "decoder_full.onnx");
            config.model.pocket.decoder = path(root, fullDecoder && whole.isFile() ? "decoder_full.onnx" : "decoder.onnx");
            config.model.pocket.voiceEmbeddingCacheCapacity = 4;
            english = new OfflineTts(config);
            Diag.mark(context, "voice: English model ready, tempo " + englishTempo);
        } catch (RuntimeException e) {
            Diag.log(context, "voice: English model failed", e);
            english = null;
        }
        return english;
    }

    private synchronized void releaseEnglish() {
        if (english != null) {
            english.release();
            english = null;
        }
        englishTried = false;
    }

    /** The native rewriting: numbers, stress, the letter yo, respelling. */
    private String nativeText(String text) {
        // Words produced by normalization (numbers, abbreviations) get stress marks too.
        Lexicon lex = Lexicon.get(context);
        String out = Homographs.get(context).apply(prep.apply(text), lex);
        out = Lexicon.bareYo(modelStress.apply(lex.applyWords(out)));
        return Prefs.respellShared(context) ? respell.apply(out) : out;
    }

    synchronized boolean isReady() {
        return engine != null;
    }

    synchronized int sampleRate() {
        return engine.sampleRate();
    }

    /** Synthesizes one chunk. Returns an empty array when nothing was produced. */
    synchronized float[] synthesize(String chunk, float speed) {
        return synthesizeRole(chunk, speed, Cast.NARRATOR, 0);
    }

    /**
     * Synthesizes one chunk for a role. A non-zero narratorOverride replaces
     * the narrator chosen in settings (an app asked for a specific voice).
     */
    synchronized float[] synthesizeRole(String chunk, float speed, int role, int narratorOverride) {
        return synthesizeVoice(chunk, speed, voiceFor(role, narratorOverride));
    }

    /** The voice a role is read with; a non-zero override replaces the chosen narrator. */
    private int voiceFor(int role, int narratorOverride) {
        if (role != Cast.NARRATOR && Prefs.dialogueVoiceShared(context)) {
            return Prefs.roleShared(context, role);
        }
        return narratorOverride > 0 ? narratorOverride : Prefs.roleShared(context, Cast.NARRATOR);
    }

    /**
     * One part read the way its voice breathes: the breath groups of the
     * voice's manner, each followed by its pause. With no manner the part is
     * read whole and followed by pauseMs. Within a manner, slowing by pauses
     * adds no silence of its own (the manner's pauses are the slowing), and
     * slowing by stretching stretches as before.
     */
    synchronized float[] breathe(Part part, float speed, int narratorOverride, int pauseMs, Cancel cancel) {
        int voice = voiceFor(part.role, narratorOverride);
        int manner = Prefs.breathShared(context, voice);
        int rate = sampleRate();
        if (breath == null) {
            android.content.res.Resources res = SpeechLanguage.resources(context);
            breath = new Breath(res.getString(R.string.breath_vowels),
                    res.getString(R.string.breath_joiners).split(" "));
        }
        List<float[]> pieces = new ArrayList<float[]>();
        int total = 0;
        if (manner == Breath.NONE) {
            float[] s = synthesizeVoice(part.text, speed, voice, false);
            pieces.add(s);
            pieces.add(new float[rate * Math.max(0, pauseMs) / 1000]);
        } else {
            for (Breath.Group g : breath.split(part.text, manner)) {
                if (cancel != null && cancel.cancelled()) {
                    break;
                }
                pieces.add(synthesizeVoice(g.text, speed, voice, true));
                pieces.add(new float[rate * g.pauseMs / 1000]);
            }
        }
        for (float[] p : pieces) {
            total += p.length;
        }
        float[] out = new float[total];
        int at = 0;
        for (float[] p : pieces) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    synchronized float[] synthesizeVoice(String chunk, float speed, int voice) {
        return synthesizeVoice(chunk, speed, voice, false);
    }

    synchronized float[] synthesizeVoice(String chunk, float speed, int voice, boolean inManner) {
        long started = System.nanoTime();
        try {
            return synthesizeVoiceTimed(chunk, speed, voice, inManner);
        } finally {
            spentSeconds += (System.nanoTime() - started) / 1e9;
        }
    }

    /** Speed of making speech since the last call: seconds of speech per second of work; 0 if none. */
    synchronized float takeRatio() {
        float r = spentSeconds > 0 ? (float) (madeSeconds / spentSeconds) : 0f;
        madeSeconds = 0;
        spentSeconds = 0;
        return r;
    }

    private float[] synthesizeVoiceTimed(String chunk, float speed, int voice, boolean inManner) {
        if (Foreign.has(chunk)) {
            return mixed(chunk, speed, voice, inManner);
        }
        Sample use;
        try {
            use = sample(voice);
        } catch (IOException e) {
            Diag.log(context, "voice: sample " + voice + " unreadable", e);
            return new float[0];
        }
        String text = brackets(QUOTES.matcher(chunk).replaceAll(""));
        int letters = letterCount(text);
        try {
            float[] first = generate(text, use, -1);
            float verdict = judge(first, letters, voice);
            float[] best = first;
            if (verdict != 0) {
                // A chunk far longer than its text is babbling; far shorter has lost words.
                // One more draw with a different seed usually comes out clean.
                float[] second = generate(text, use, (int) (System.nanoTime() & 0x7fffffff));
                float verdict2 = judge(second, letters, voice);
                best = Math.abs(verdict2) < Math.abs(verdict) ? second : first;
                float kept = best == second ? verdict2 : verdict;
                Diag.log(context, "voice: " + (verdict > 0 ? "overlong" : "short") + " chunk, "
                        + (kept == 0 ? "redrawn clean" : "still off, trimmed") + ": " + text);
                if (kept > 0) {
                    best = trimTo(best, letters < JUDGED_LETTERS
                            ? shortLimit(letters) * MODEL_RATE / SHORT_LOOP
                            : expectedSamples(letters, voice) * TRIM_MARGIN);
                }
            }
            float before = rateOf(voice);
            boolean known = rateCount[Math.max(1, Math.min(Cast.COUNT, voice))] >= MIN_HISTORY;
            if (judge(best, letters, voice) == 0) {
                learnRate(best, letters, voice);
            }
            if (known && letters >= 12 && Prefs.evenTempoShared(context)) {
                best = even(best, letters, before);
            }
            // Uneven silence at the ends of each chunk made the joins stumble; keep a fixed sliver.
            best = trimEdges(best, MODEL_RATE);
            best = level(best, voice);
            // The model keeps the pace of its sample; tempo is applied afterwards.
            madeSeconds += best.length / (double) MODEL_RATE;
            return pace(best, MODEL_RATE, speed, inManner);
        } catch (RuntimeException e) {
            // One unreadable chunk must not end the whole reading, but it is recorded.
            Diag.log(context, "voice: chunk failed: " + chunk, e);
            return new float[0];
        }
    }

    private static final java.util.regex.Pattern OPEN_AT_START = java.util.regex.Pattern.compile("^[\\s]*[(\\[]\\s*");
    private static final java.util.regex.Pattern CLOSE_AT_END = java.util.regex.Pattern.compile("\\s*[)\\]]\\s*([.!?\u2026]*)\\s*$");
    private static final java.util.regex.Pattern CLOSE_BEFORE_MARK = java.util.regex.Pattern.compile("\\s*[)\\]](?=\\s*[,.;:!?\u2026])");
    private static final java.util.regex.Pattern OPEN_INSIDE = java.util.regex.Pattern.compile("\\s*[(\\[]\\s*");
    private static final java.util.regex.Pattern CLOSE_INSIDE = java.util.regex.Pattern.compile("\\s*[)\\]]\\s*");
    private static final java.util.regex.Pattern DOUBLE_COMMA = java.util.regex.Pattern.compile(",\\s*,");

    /**
     * Brackets, like quotation marks, make this model lose the end of a chunk
     * and run on. They become commas, which keep the pause of an aside, or
     * disappear where they open or close the whole chunk.
     */
    static String brackets(String s) {
        String t = OPEN_AT_START.matcher(s).replaceFirst("");
        t = CLOSE_AT_END.matcher(t).replaceFirst("$1");
        t = CLOSE_BEFORE_MARK.matcher(t).replaceAll("");
        t = OPEN_INSIDE.matcher(t).replaceAll(", ");
        t = CLOSE_INSIDE.matcher(t).replaceAll(", ");
        return DOUBLE_COMMA.matcher(t).replaceAll(",").trim();
    }

    /** A chunk holding English: each piece by its own model, in the same voice, joined with a short breath. */
    private float[] mixed(String chunk, float speed, int voice, boolean inManner) {
        List<float[]> parts = new ArrayList<float[]>();
        int gap = Math.round(LANGUAGE_GAP_S * MODEL_RATE);
        for (Foreign.Piece p : Foreign.pieces(chunk)) {
            float[] a;
            if (p.english && englishEngine() != null) {
                a = englishPiece(p.text, speed, voice);
            } else {
                // No English model after all: the piece is read the native way, spelled out.
                a = synthesizeVoiceTimed(p.english ? nativeText(p.text) : p.text, speed, voice, inManner);
            }
            if (a.length == 0) {
                continue;
            }
            if (!parts.isEmpty()) {
                parts.add(new float[gap]);
            }
            parts.add(a);
        }
        int n = 0;
        for (float[] a : parts) {
            n += a.length;
        }
        float[] out = new float[n];
        int at = 0;
        for (float[] a : parts) {
            System.arraycopy(a, 0, out, at, a.length);
            at += a.length;
        }
        return out;
    }

    /**
     * English read by the English model with this voice's own sample: a light
     * accent is expected and accepted. Slower than the native pace by the
     * English tempo, and brought to its own level, since this model speaks louder.
     */
    private float[] englishPiece(String text, float speed, int voice) {
        Sample use;
        try {
            use = sample(voice);
        } catch (IOException e) {
            Diag.log(context, "voice: sample " + voice + " unreadable", e);
            return new float[0];
        }
        String said = englishLead + brackets(QUOTES.matcher(text).replaceAll(""));
        try {
            float[] a = generateWith(english, said, use, -1);
            a = trimEdges(a, MODEL_RATE);
            a = levelIn(levelEn, a, voice);
            madeSeconds += a.length / (double) MODEL_RATE;
            float tempo = Math.max(0.6f, Math.min(1.3f, englishTempo * speed / Prefs.SPEED_DEFAULT));
            Diag.log(context, "voice: English piece, " + text.length() + " letters, tempo " + tempo);
            return Math.abs(tempo - 1f) < 0.02f ? a : TimeStretch.apply(a, MODEL_RATE, tempo);
        } catch (RuntimeException e) {
            Diag.log(context, "voice: English piece failed: " + text, e);
            return new float[0];
        }
    }

    private float[] generate(String text, Sample use, int seed) {
        return generateWith(engine, text, use, seed);
    }

    private float[] generateWith(OfflineTts with, String text, Sample use, int seed) {
        GenerationConfig g = new GenerationConfig();
        g.referenceAudio = use.audio;
        g.referenceSampleRate = use.rate;
        // One flow step: the model was distilled for it; more steps cost time without a clear gain.
        g.numSteps = 1;
        g.extra = new HashMap<String, String>();
        // Hard cap, about 1.6 frames per character: speech needs under one.
        g.extra.put("max_frames", String.valueOf(Math.min(500, 20 + (int) (1.6f * text.length()))));
        // Sampling temperature: low is calm and even, high is lively but less stable.
        g.extra.put("temperature", String.valueOf(Prefs.expressionShared(context)));
        if (seed >= 0) {
            g.extra.put("seed", String.valueOf(seed));
        }
        GeneratedAudio audio = with.generate(text, g);
        return audio == null || audio.samples == null ? new float[0] : audio.samples;
    }

    // Speaking-rate guard. The pace of each voice is learned from its clean
    // chunks; a chunk is suspicious when its length strays far from that pace.
    private static final float START_RATE = 12f;
    private static final float TOO_LONG = 1.7f;
    /** Below this many letters a chunk is judged by plain length, not by the voice's rate. */
    private static final int JUDGED_LETTERS = 8;
    /** How many single readings of a short chunk fit under its limit. */
    private static final float SHORT_LOOP = 2f;
    private static final float TOO_SHORT = 0.45f;
    private static final float TRIM_MARGIN = 1.25f;
    private static final float SLACK_S = 0.6f;
    // Recent speaking rates per voice; their median is the voice's natural pace.
    private static final int HISTORY = 21;
    private static final int MIN_HISTORY = 5;
    private final float[][] rateHistory = new float[Cast.COUNT + 1][HISTORY];
    private final int[] rateCount = new int[Cast.COUNT + 1];

    // Tempo evening: each chunk is nudged toward the natural pace, never by
    // more than these bounds, so the correction stays inaudible.
    private static final float EVEN_MIN = 0.87f;
    private static final float EVEN_MAX = 1.15f;
    private static final float VOICED = 0.01f;

    private static int letterCount(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    private float rateOf(int voice) {
        int v = voice >= 1 && voice <= Cast.COUNT ? voice : 1;
        int n = Math.min(rateCount[v], HISTORY);
        if (n == 0) {
            return START_RATE;
        }
        float[] sorted = java.util.Arrays.copyOf(rateHistory[v], n);
        java.util.Arrays.sort(sorted);
        return sorted[n / 2];
    }

    /**
     * The longest a short chunk may last, silence at its ends included: twice
     * the longest single reading measured across all voices (0.3 s plus 0.07 s
     * a letter, with some room).
     */
    private static float shortLimit(int letters) {
        return SHORT_LOOP * (0.3f + 0.07f * letters);
    }

    private int expectedSamples(int letters, int voice) {
        return Math.round(letters / rateOf(voice) * MODEL_RATE);
    }

    /** 0 when the length is plausible, &gt;0 when too long, &lt;0 when too short. */
    private float judge(float[] audio, int letters, int voice) {
        float seconds = audio.length / (float) MODEL_RATE;
        if (letters < JUDGED_LETTERS) {
            // A word or two: the rate is no guide here, but a short word said over
            // and over runs far past the length any single reading of it takes.
            if (letters == 0) {
                return 0;
            }
            float limit = shortLimit(letters);
            return seconds > limit ? seconds / limit * SHORT_LOOP : 0;
        }
        float expected = letters / rateOf(voice);
        if (seconds > expected * TOO_LONG + SLACK_S) {
            return seconds / expected;
        }
        if (seconds < expected * TOO_SHORT) {
            return -expected / Math.max(0.05f, seconds);
        }
        return 0;
    }

    private void learnRate(float[] audio, int letters, int voice) {
        float seconds = voicedSeconds(audio);
        if (letters < 12 || seconds <= 0) {
            return;
        }
        int v = voice >= 1 && voice <= Cast.COUNT ? voice : 1;
        rateHistory[v][rateCount[v] % HISTORY] = letters / seconds;
        rateCount[v]++;
    }

    /** Stretches a chunk slightly so its pace matches the voice's natural pace. */
    private static float[] even(float[] audio, int letters, float natural) {
        float seconds = voicedSeconds(audio);
        if (seconds <= 0) {
            return audio;
        }
        float actual = letters / seconds;
        float factor = Math.max(EVEN_MIN, Math.min(EVEN_MAX, natural / actual));
        if (Math.abs(factor - 1f) < 0.03f) {
            return audio;
        }
        return TimeStretch.apply(audio, MODEL_RATE, factor);
    }

    /** Duration from the first to the last sounding moment, ignoring silence at the ends. */
    private static float voicedSeconds(float[] audio) {
        int frame = MODEL_RATE / 50;
        int first = -1;
        int last = -1;
        for (int off = 0; off + frame <= audio.length; off += frame) {
            double sum = 0;
            for (int i = off; i < off + frame; i++) {
                sum += audio[i] * audio[i];
            }
            if (Math.sqrt(sum / frame) > VOICED) {
                if (first < 0) {
                    first = off;
                }
                last = off + frame;
            }
        }
        return first < 0 ? 0 : (last - first) / (float) MODEL_RATE;
    }

    /** Cuts to the given length with a short fade, so the cut does not click. */
    private static float[] trimTo(float[] audio, float samples) {
        int n = Math.min(audio.length, Math.max(0, Math.round(samples)));
        float[] out = new float[n];
        System.arraycopy(audio, 0, out, 0, n);
        int fade = Math.min(n, MODEL_RATE / 20);
        for (int i = 0; i < fade; i++) {
            out[n - 1 - i] *= i / (float) fade;
        }
        return out;
    }

    /** Plays text through the speaker, chunk by chunk, with a pause between chunks. */
    void speak(String text, Locale locale, float speed, int pauseMs, Cancel cancel) {
        speak(text, locale, speed, pauseMs, cancel, 0);
    }

    /** As above; a non-zero voice reads everything outside dialogue instead of the narrator. */
    void speak(String text, Locale locale, float speed, int pauseMs, Cancel cancel, int voice) {
        int rate = sampleRate();
        AudioTrack out = trackFor(rate);
        out.play();
        for (Part part : parts(text, locale)) {
            if (cancel.cancelled()) {
                break;
            }
            write(out, breathe(part, speed, voice, pauseMs, cancel), cancel);
        }
        if (cancel.cancelled()) {
            out.pause();
            out.flush();
        }
        // In streaming mode stop() lets the queued tail play out.
        out.stop();
    }

    /** Renders text to one buffer, chunk by chunk, with pauses between chunks. */
    float[] render(String text, float speed, int pauseMs) {
        List<float[]> parts = new ArrayList<float[]>();
        int total = 0;
        for (Part part : parts(text, SpeechLanguage.locale())) {
            float[] s = breathe(part, speed, 0, pauseMs, null);
            parts.add(s);
            total += s.length;
        }
        float[] out = new float[total];
        int off = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    /**
     * One paragraph made into sound without playing it: every part in its
     * role's voice, the pause after each. Consecutive calls keep dialogue
     * alternating between the two speakers, as reading does. Returns what
     * was made so far if cancelled.
     */
    float[] render(String spoken, Locale locale, float speed, int pauseMs, Cancel cancel) {
        return render(spoken, locale, speed, pauseMs, cancel, null);
    }

    /** Takes each piece of a paragraph as soon as it is made, so it can play while the rest is made. */
    interface Sink {
        void take(float[] piece);
    }

    /**
     * The same, handing every piece (a sentence or a turn of speech, with its
     * pause) to the sink the moment it is ready; then nothing is returned
     * but an empty array, as the pieces have already gone.
     */
    float[] render(String spoken, Locale locale, float speed, int pauseMs, Cancel cancel, Sink sink) {
        return render(spoken, locale, speed, pauseMs, cancel, sink, false);
    }

    /**
     * The same; with quick set the first piece, if long, is cut at its first
     * natural pause (a comma, a dash) so the listener waiting in silence hears
     * the voice after a short phrase, not a long sentence.
     */
    float[] render(String spoken, Locale locale, float speed, int pauseMs, Cancel cancel, Sink sink, boolean quick) {
        return render(spoken, locale, speed, pauseMs, cancel, sink, quick, 0);
    }

    /** As above; a non-zero voice reads everything outside dialogue instead of the narrator. */
    float[] render(String spoken, Locale locale, float speed, int pauseMs, Cancel cancel, Sink sink, boolean quick,
            int narratorOverride) {
        List<float[]> pieces = new ArrayList<float[]>();
        int total = 0;
        List<Part> all = quick ? quickParts(spoken, locale) : parts(spoken, locale);
        for (Part part : all) {
            if (part.text.isEmpty()) {
                continue;
            }
            if (cancel.cancelled()) {
                break;
            }
            float[] samples = breathe(part, speed, narratorOverride, pauseMs, cancel);
            if (sink != null) {
                if (samples.length > 0 && !cancel.cancelled()) {
                    sink.take(samples);
                }
                continue;
            }
            pieces.add(samples);
            total += samples.length;
        }
        float[] out = new float[total];
        int at = 0;
        for (float[] piece : pieces) {
            System.arraycopy(piece, 0, out, at, piece.length);
            at += piece.length;
        }
        return out;
    }

    void play(float[] samples, Cancel cancel) {
        AudioTrack out = trackFor(sampleRate());
        out.play();
        write(out, samples, cancel);
        out.stop();
    }

    /**
     * The pieces of a text, the first one cut at its first natural pause when
     * long, so a listener waiting in silence hears the voice after a short phrase.
     */
    static List<Part> quickParts(String spoken, Locale locale) {
        List<Part> all = new ArrayList<Part>(parts(spoken, locale));
        if (!all.isEmpty() && all.get(0).text.length() > START_CHUNK) {
            Part first = all.remove(0);
            int cut = lastBreak(first.text, START_CHUNK);
            all.add(0, new Part(first.text.substring(cut).trim(), first.role));
            all.add(0, new Part(first.text.substring(0, cut).trim(), first.role));
        }
        return sealed(all);
    }

    /** Every part whole on its own: an English span cut between parts is closed and reopened at the cut. */
    private static List<Part> sealed(List<Part> all) {
        boolean[] inside = {false};
        boolean any = false;
        for (Part p : all) {
            any |= Foreign.has(p.text);
        }
        if (!any) {
            return all;
        }
        List<Part> out = new ArrayList<Part>(all.size());
        for (Part p : all) {
            out.add(new Part(Foreign.seal(p.text, inside), p.role));
        }
        return out;
    }

    /**
     * Splits text into readable pieces and marks direct speech. A paragraph
     * that opens with a dash is dialogue; inside it, a spaced dash followed by
     * a lower-case word starts the author's remark, and the next spaced dash
     * returns to speech.
     */
    static synchronized List<Part> parts(String text, Locale locale) {
        List<Part> out = new ArrayList<Part>();
        for (String para : text.split("\n")) {
            String p = para.trim();
            if (p.isEmpty()) {
                continue;
            }
            boolean opensSpeech = p.charAt(0) == '\u2014' || p.charAt(0) == '\u2013'
                    || p.startsWith("- ");
            if (!opensSpeech) {
                lastSpeaker = 0;
                for (String c : chunks(p, locale)) {
                    out.add(new Part(c, Cast.NARRATOR));
                }
                continue;
            }
            int speaker = lastSpeaker == Cast.SPEAKER_A ? Cast.SPEAKER_B : Cast.SPEAKER_A;
            lastSpeaker = speaker;
            String[] segs = REMARK_SPLIT.split(p.substring(1).trim());
            boolean speech = true;
            for (int i = 0; i < segs.length; i++) {
                String seg = segs[i].trim();
                if (seg.isEmpty()) {
                    continue;
                }
                if (i > 0) {
                    speech = !(speech && Character.isLowerCase(seg.codePointAt(0)));
                }
                for (String c : chunks(seg, locale)) {
                    out.add(new Part(c, speech ? speaker : Cast.NARRATOR));
                }
            }
        }
        return sealed(out);
    }

    /** Speech level every voice is brought to, as the RMS of its sounding part. */
    private static final float LEVEL_TARGET = 0.07f;
    private static final float LEVEL_MAX_GAIN = 2.5f;
    private static final float LEVEL_MIN_GAIN = 0.4f;
    /** Below this a sample counts as silence when measuring level. */
    private static final float LEVEL_FLOOR = 0.01f;
    /** Each voice's speech level, learned slowly over its chunks. */
    private final float[] levelOf = new float[Cast.COUNT + 1];

    /**
     * Brings a voice to the common level, so voices taking turns in a book
     * sound equally near. The level is learned over many chunks, so the
     * rise and fall within a reading is kept.
     */
    private float[] level(float[] audio, int voice) {
        return levelIn(levelOf, audio, voice);
    }

    /** The same, against the level learned in the given table, one table per model. */
    private float[] levelIn(float[] table, float[] audio, int voice) {
        double sum = 0;
        int n = 0;
        float peak = 0f;
        for (float v : audio) {
            float a = Math.abs(v);
            peak = Math.max(peak, a);
            if (a > LEVEL_FLOOR) {
                sum += v * (double) v;
                n++;
            }
        }
        if (n < MODEL_RATE / 10) {
            return audio;
        }
        float rms = (float) Math.sqrt(sum / n);
        int v = Math.max(1, Math.min(Cast.COUNT, voice));
        table[v] = table[v] == 0f ? rms : table[v] * 0.85f + rms * 0.15f;
        float gain = Math.max(LEVEL_MIN_GAIN, Math.min(LEVEL_MAX_GAIN, LEVEL_TARGET / table[v]));
        if (peak * gain > 0.97f) {
            gain = 0.97f / peak;
        }
        float[] out = new float[audio.length];
        for (int i = 0; i < audio.length; i++) {
            out[i] = audio[i] * gain;
        }
        return out;
    }

    /**
     * Longest silence added after one chunk when slowing down by pauses. Silence added to slow a whole piece. With pieces of whole sentences it lands
     * on top of the pause between sentences, so it stays small: with 0.7 s the
     * gaps grew past a second, longer than any good reader's full stop (0.4-0.7 s).
     */
    private static final float MAX_ADDED_PAUSE_S = 0.2f;

    /**
     * Applies tempo. Speeding up always compresses the speech. Slowing down
     * either stretches it, or keeps it intact and adds the missing time as
     * silence after the chunk, which sounds like an unhurried reader rather
     * than a slowed recording.
     */
    private float[] pace(float[] audio, int rate, float tempo, boolean inManner) {
        if (tempo >= 1f || !Prefs.slowByPausesShared(context)) {
            return TimeStretch.apply(audio, rate, tempo);
        }
        if (inManner) {
            // Stretched speech sounds like careful foreign reading; the manner's pauses slow it instead.
            // (Tried stretching here in one build: heard as worse, taken back.)
            return audio;
        }
        float extra = Math.min(MAX_ADDED_PAUSE_S, audio.length / (float) rate * (1f / tempo - 1f));
        float[] out = new float[audio.length + Math.round(extra * rate)];
        System.arraycopy(audio, 0, out, 0, audio.length);
        return out;
    }

    /**
     * Keeps a fixed sliver of silence at both ends of a chunk and fades the
     * edges, so joins between chunks are even and never click.
     */
    static float[] trimEdges(float[] audio, int rate) {
        int n = audio.length;
        if (n == 0) {
            return audio;
        }
        float peak = 0f;
        for (float v : audio) {
            peak = Math.max(peak, Math.abs(v));
        }
        float floor = Math.max(0.004f, peak * 0.03f);
        // The end of a word can be a quiet hiss or a soft stop (as at the end of most number words):
        // the tail is measured against a lower floor than the start, or the last sound is cut off.
        float tailFloor = Math.max(0.0015f, peak * 0.008f);
        int window = Math.max(1, rate / 100);
        int first = -1;
        int last = -1;
        for (int i = 0; i + window <= n; i += window) {
            float m = 0f;
            for (int j = i; j < i + window; j++) {
                m = Math.max(m, Math.abs(audio[j]));
            }
            if (m > floor && first < 0) {
                first = i;
            }
            if (m > tailFloor && first >= 0) {
                last = i + window;
            }
        }
        if (first < 0) {
            return audio;
        }
        int from = Math.max(0, first - Math.round(LEAD_S * rate));
        int to = Math.min(n, last + Math.round(TAIL_S * rate));
        float[] out = new float[to - from];
        System.arraycopy(audio, from, out, 0, out.length);
        int fade = Math.min(out.length / 2, rate / 125);
        for (int i = 0; i < fade; i++) {
            float k = i / (float) fade;
            out[i] *= k;
            out[out.length - 1 - i] *= k;
        }
        return out;
    }

    /**
     * Sentences, with overlong ones cut at clause marks or, failing that, at
     * spaces; short neighbours are then joined so the voice reads them in one
     * breath, the way a reader does, instead of starting afresh at each.
     */
    static List<String> chunks(String text, Locale locale) {
        List<String> out = new ArrayList<String>();
        for (String s : sentences(text, locale)) {
            int last = out.size() - 1;
            if (last >= 0 && endsSentence(out.get(last))
                    && out.get(last).length() + 1 + s.length() <= MERGE_CHUNK) {
                out.set(last, out.get(last) + " " + s);
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static boolean endsSentence(String s) {
        char c = s.isEmpty() ? ' ' : s.charAt(s.length() - 1);
        return c == '.' || c == '!' || c == '?' || c == '\u2026';
    }

    private static List<String> sentences(String text, Locale locale) {
        List<String> out = new ArrayList<String>();
        BreakIterator it = BreakIterator.getSentenceInstance(locale);
        it.setText(text);
        int start = it.first();
        for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            String s = text.substring(start, end).trim();
            while (s.length() > MAX_CHUNK) {
                int cut = lastBreak(s, MAX_CHUNK);
                out.add(s.substring(0, cut).trim());
                s = s.substring(cut).trim();
            }
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static int lastBreak(String s, int limit) {
        for (int i = limit; i > limit / 2; i--) {
            char c = s.charAt(i - 1);
            if (c == ',' || c == ';' || c == ':' || c == '\u2014' || c == '\u2013') {
                return i;
            }
        }
        int space = s.lastIndexOf(' ', limit);
        return space > limit / 2 ? space : limit;
    }

    private synchronized AudioTrack trackFor(int sampleRate) {
        if (track != null && track.getSampleRate() == sampleRate) {
            return track;
        }
        if (track != null) {
            track.release();
        }
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        AudioAttributes.Builder ab = new AudioAttributes.Builder()
                .setUsage(USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            ab.setAllowedCapturePolicy(CAPTURE_NONE);
        }
        AudioAttributes attrs = ab.build();
        int min = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(min, sampleRate))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        return track;
    }

    private static void write(AudioTrack out, float[] data, Cancel cancel) {
        final int step = 4096;
        for (int off = 0; off < data.length && !cancel.cancelled(); off += step) {
            out.write(data, off, Math.min(step, data.length - off), AudioTrack.WRITE_BLOCKING);
        }
    }

    /** The bundled sample for a voice, with its makeup applied. */
    private Sample sample(int voice) throws IOException {
        int v = voice >= 1 && voice <= Cast.COUNT ? voice : 1;
        if (samples[v] == null) {
            samples[v] = new Sample();
        }
        load(samples[v], new File(root, Cast.slot(v)),
                Prefs.grimTimbreShared(context, v), Prefs.grimPitchShared(context, v), Prefs.grimAirShared(context, v));
        return samples[v];
    }

    /** Reads a sample when its file or its makeup changed since the last read. */
    private static void load(Sample s, File f, int timbre, int pitch, int air) throws IOException {
        String stamp = f.getPath() + ':' + f.lastModified() + ':' + f.length() + ':' + timbre + ':' + pitch + ':' + air;
        if (!stamp.equals(s.stamp)) {
            readReference(f, s);
            s.audio = Grim.apply(s.audio, s.rate, timbre, pitch, air);
            s.stamp = stamp;
        }
    }

    /** Reads a 16-bit PCM mono WAV file. */
    private static void readReference(File f, Sample out) throws IOException {
        byte[] b = readAll(f);
        if (b.length < 44 || b[0] != 'R' || b[8] != 'W') {
            throw new IOException("reference is not a WAV file");
        }
        out.rate = le32(b, 24);
        int pos = 12;
        while (pos + 8 <= b.length) {
            int size = le32(b, pos + 4);
            if (b[pos] == 'd' && b[pos + 1] == 'a' && b[pos + 2] == 't' && b[pos + 3] == 'a') {
                int n = Math.min(size, b.length - pos - 8) / 2;
                float[] audio = new float[n];
                for (int i = 0; i < n; i++) {
                    int lo = b[pos + 8 + 2 * i] & 0xff;
                    int hi = b[pos + 9 + 2 * i];
                    audio[i] = (short) ((hi << 8) | lo) / 32768f;
                }
                out.audio = audio;
                return;
            }
            pos += 8 + size + (size & 1);
        }
        throw new IOException("reference has no audio");
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

    private static List<String> vocabulary(File f) throws IOException {
        return keys(new String(readAll(f), "UTF-8"));
    }

    static List<String> keys(String json) throws IOException {
        List<String> out = new ArrayList<String>();
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                out.add(it.next());
            }
        } catch (org.json.JSONException e) {
            throw new IOException("bad vocabulary");
        }
        return out;
    }

    private static String path(File root, String name) {
        return new File(root, name).getAbsolutePath();
    }

    private static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            int n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                off += n;
            }
            return buf;
        } finally {
            in.close();
        }
    }

    private static void copyAssetTree(AssetManager assets, String path, File target)
            throws IOException {
        String[] children = assets.list(path);
        if (children == null || children.length == 0) {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create " + parent);
            }
            InputStream in = assets.open(path);
            try {
                OutputStream out = new FileOutputStream(target);
                try {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            return;
        }
        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("cannot create " + target);
        }
        for (String child : children) {
            copyAssetTree(assets, path + "/" + child, new File(target, child));
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    private static String readSmall(File f) {
        try {
            return new String(readAll(f), "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeSmall(File f, String s) throws IOException {
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(s.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }
}
