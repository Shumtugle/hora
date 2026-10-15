package com.shumtugle.hora;

import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;

import java.io.File;

/**
 * Words out of a stream of sound while it is still being said. One utterance at a time, all calls
 * from one thread. Knows nothing of microphones or screens, so it can be tried on a desk with
 * recorded sound.
 */
final class Hearing {
    static final int RATE = 16000;
    /** The model files, as they lie in the app and once unpacked. */
    static final String[] FILES = {"encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt", "bpe.vocab"};

    private final OnlineRecognizer recognizer;
    private OnlineStream stream;
    private String words = "";

    Hearing(File dir, int threads) {
        OnlineRecognizerConfig c = new OnlineRecognizerConfig();
        c.modelConfig.transducer.encoder = new File(dir, FILES[0]).getPath();
        c.modelConfig.transducer.decoder = new File(dir, FILES[1]).getPath();
        c.modelConfig.transducer.joiner = new File(dir, FILES[2]).getPath();
        c.modelConfig.tokens = new File(dir, FILES[3]).getPath();
        c.modelConfig.numThreads = threads;
        // A search that keeps a few guesses open, so names it was told to expect can win over
        // look-alike words. The names come with each utterance.
        c.decodingMethod = "modified_beam_search";
        c.maxActivePaths = 4;
        c.hotwordsScore = 2f;
        c.modelConfig.modelingUnit = "bpe";
        c.modelConfig.bpeVocab = new File(dir, FILES[4]).getPath();
        c.featConfig.sampleRate = RATE;
        c.featConfig.featureDim = 80;
        // Nothing said for five seconds: done, with nothing. Something said, then a second of
        // silence: done, with it. Twenty seconds of talk: done whatever comes next.
        c.endpointConfig.rule1.minTrailingSilence = 5f;
        c.endpointConfig.rule2.minTrailingSilence = 1f;
        c.endpointConfig.rule3.minUtteranceLength = 20f;
        recognizer = new OnlineRecognizer(c);
    }

    /** Starts a new utterance, forgetting the last one; names are what it should expect, as from expect(). */
    void begin(String names) {
        drop();
        stream = recognizer.createStream(names);
        words = "";
    }

    /** Takes the next piece of sound; true when the words heard so far changed. */
    boolean feed(float[] samples) {
        if (stream == null) {
            return false;
        }
        stream.acceptWaveform(samples, RATE);
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream);
        }
        return refresh();
    }

    /** What has been heard so far, as the recognizer writes it: lower case, no punctuation. */
    String words() {
        return words;
    }

    /** Whether the speaker has finished: a pause after words, or long silence, or long talk. */
    boolean finished() {
        return stream != null && recognizer.isEndpoint(stream);
    }

    /** Closes the utterance and gives its words; empty when nothing was understood. */
    String end() {
        if (stream == null) {
            return words;
        }
        // A second of silence lets the last word out of the model before the input closes:
        // with less, a stop pressed right after speaking loses that word.
        stream.acceptWaveform(new float[RATE], RATE);
        stream.inputFinished();
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream);
        }
        refresh();
        drop();
        return words;
    }

    /** Frees the model; nothing may be called afterwards. */
    void release() {
        drop();
        recognizer.release();
    }

    private boolean refresh() {
        OnlineRecognizerResult r = recognizer.getResult(stream);
        String now = r == null || r.text == null ? "" : r.text.trim();
        if (now.equals(words)) {
            return false;
        }
        words = now;
        return true;
    }

    private void drop() {
        if (stream != null) {
            stream.release();
            stream = null;
        }
    }

    /**
     * Names to expect, as the recognizer takes them: lower case, one per item, separated by
     * slashes. Only what the model can spell is kept: letters of its own alphabet and spaces.
     */
    static String expect(Iterable<String> names) {
        java.util.LinkedHashSet<String> kept = new java.util.LinkedHashSet<String>();
        for (String n : names) {
            if (n == null) {
                continue;
            }
            String w = n.toLowerCase(java.util.Locale.ROOT).replace('-', ' ').trim().replaceAll("\\s+", " ");
            if (!w.isEmpty() && w.matches("[\\u0430-\\u044f\\u0451 ]+")) {
                kept.add(w);
            }
        }
        StringBuilder b = new StringBuilder();
        for (String w : kept) {
            if (b.length() > 0) {
                b.append('/');
            }
            b.append(w);
        }
        return b.toString();
    }

    /** The heard words as a line to send: first letter up, nothing else touched. */
    static String asLine(String heard) {
        String s = heard == null ? "" : heard.trim();
        if (s.isEmpty()) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT)
                + s.substring(s.offsetByCodePoints(0, 1));
    }
}
