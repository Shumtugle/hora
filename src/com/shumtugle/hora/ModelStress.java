package com.shumtugle.hora;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Last stop for words no dictionary or context rule has marked. First the
 * stress the voice model already knows. Its vocabulary holds frequent words
 * whole, with the stress mark, in the form it met most often while learning.
 * A word the dictionaries left bare gets that form: it then reaches the model
 * as a piece it knows, instead of loose parts it has to guess from. What is
 * still bare then goes to the character network, which guesses from spelling.
 */
final class ModelStress {
    private static final char ACUTE = '\u0301';
    private static final char WORD_START = '\u2581';
    private static final Pattern WORD = Pattern.compile("[\\p{IsCyrillic}\\u0301]+");
    private static final Pattern VOWEL = Pattern.compile("[\u0430\u0435\u0438\u043e\u0443\u044b\u044d\u044e\u044f\u0451]");
    private static final char YO = '\u0451';

    /** Bare lower-case word to its stressed form; words with two known stresses are left out. */
    private final Map<String, String> stressed = new HashMap<String, String>();
    /** Guesses from spelling; null when unavailable. */
    private final StressNet net;
    /** Particles that stay unstressed after a hyphen. */
    private final java.util.Set<String> clitics = new java.util.HashSet<String>();

    ModelStress(Iterable<String> pieces) {
        this(pieces, null, new String[0]);
    }

    ModelStress(Iterable<String> pieces, StressNet net, String[] clitics) {
        this.net = net;
        java.util.Collections.addAll(this.clitics, clitics);
        Map<String, String> seen = new HashMap<String, String>();
        for (String p : pieces) {
            if (p.length() < 3 || p.charAt(0) != WORD_START || p.indexOf(ACUTE) < 0) {
                continue;
            }
            String word = p.substring(1).toLowerCase(Locale.ROOT);
            if (!WORD.matcher(word).matches()) {
                continue;
            }
            String bare = word.replace(String.valueOf(ACUTE), "");
            String before = seen.put(bare, word);
            if (before != null && !before.equals(word)) {
                seen.put(bare, "");
            }
        }
        for (Map.Entry<String, String> e : seen.entrySet()) {
            if (!e.getValue().isEmpty()) {
                stressed.put(e.getKey(), e.getValue());
            }
        }
    }

    /** Reads the vocabulary from the bundle; works in any process. */
    static ModelStress fromAssets(Context c) {
        try {
            InputStream in = c.getAssets().open("voice/vocab.json");
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 14];
                int n;
                while ((n = in.read(buf)) > 0) {
                    b.write(buf, 0, n);
                }
                return new ModelStress(Voice.keys(new String(b.toByteArray(), "UTF-8")),
                        StressNet.get(c), clitics(c));
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return new ModelStress(new java.util.ArrayList<String>(), StressNet.get(c), clitics(c));
        }
    }

    static String[] clitics(Context c) {
        return SpeechLanguage.resources(c).getStringArray(R.array.stress_clitics);
    }

    /** Marks stress in words that have none yet. */
    String apply(String text) {
        Matcher m = WORD.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 16);
        int last = 0;
        while (m.find()) {
            String w = m.group();
            String lower = w.toLowerCase(Locale.ROOT);
            String hit = null;
            if (w.indexOf(ACUTE) < 0 && lower.indexOf(YO) < 0 && vowels(lower) > 1) {
                hit = stressed.get(lower);
                boolean clitic = m.start() > 0 && text.charAt(m.start() - 1) == '-' && clitics.contains(lower);
                if (hit == null && net != null && !clitic) {
                    int i = net.stressIndex(lower);
                    if (i >= 0) {
                        hit = lower.substring(0, i + 1) + ACUTE + lower.substring(i + 1);
                    }
                }
            }
            out.append(text, last, m.start()).append(hit == null ? w : Lexicon.matchCase(w, hit));
            last = m.end();
        }
        return out.append(text, last, text.length()).toString();
    }

    int size() {
        return stressed.size();
    }

    private static int vowels(String w) {
        int n = 0;
        Matcher v = VOWEL.matcher(w);
        while (v.find()) {
            n++;
        }
        return n;
    }
}
