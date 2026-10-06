package com.shumtugle.hora;

import android.content.res.Resources;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phonetic spelling. The voice reads letters and guesses their sound, so the
 * text is rewritten the way it is pronounced: consonant clusters simplified,
 * voiced consonants devoiced where speech devoices them, and, in words whose
 * stress is known, unstressed vowels written as they sound. All rules come
 * from resources of the voice language; with no rules this is a no-op.
 */
final class Respell {
    private static final char ACUTE = Lexicon.ACUTE;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}]+");

    private final Map<String, String> words = new HashMap<String, String>();
    private final Set<String> keep = new HashSet<String>();
    /** Words left exactly as written: rewritten, the model no longer recognises them. */
    private final Set<String> untouched = new HashSet<String>();
    private final List<Pattern> patterns = new ArrayList<Pattern>();
    private final List<String> replacements = new ArrayList<String>();
    private final String vowels;
    private final String alwaysStressed;
    private final String softeners;
    private final String hardening;
    private final String hardenedE;
    private final String voicelessUnpaired;
    /**
     * Unstressed vowel mapping, written "from=to" plus optional flags:
     * "^" only after a consonant (keeps the glide of soft vowels elsewhere),
     * "<" only before the stressed syllable.
     */
    private final List<char[]> reduce = new ArrayList<char[]>();
    private final List<Boolean> pretonicOnly = new ArrayList<Boolean>();
    private final List<Boolean> afterConsonantOnly = new ArrayList<Boolean>();
    /** Final devoicing pairs, and the words that take voicing from the next one. */
    private final Map<Character, Character> devoice = new HashMap<Character, Character>();
    private final Set<String> proclitics = new HashSet<String>();

    Respell(Resources r) {
        for (String w : r.getStringArray(R.array.respell_untouched)) {
            untouched.add(w);
        }
        vowels = r.getString(R.string.stress_vowels);
        alwaysStressed = r.getString(R.string.always_stressed);
        softeners = r.getString(R.string.respell_signs);
        hardening = r.getString(R.string.respell_hardening);
        hardenedE = r.getString(R.string.respell_hardened_e);
        voicelessUnpaired = r.getString(R.string.respell_voiceless_unpaired);
        for (String pair : r.getStringArray(R.array.respell_words)) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                words.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        for (String w : r.getStringArray(R.array.respell_keep)) {
            keep.add(w);
        }
        for (String rule : r.getStringArray(R.array.respell_rules)) {
            int arrow = rule.indexOf("=>");
            if (arrow > 0) {
                patterns.add(Pattern.compile(rule.substring(0, arrow)));
                replacements.add(rule.substring(arrow + 2));
            }
        }
        for (String pair : r.getStringArray(R.array.respell_final)) {
            if (pair.length() == 3 && pair.charAt(1) == '=') {
                devoice.put(pair.charAt(0), pair.charAt(2));
            }
        }
        for (String w : r.getStringArray(R.array.respell_proclitics)) {
            proclitics.add(w);
        }
        for (String rule : r.getStringArray(R.array.respell_reduce)) {
            if (rule.length() >= 3 && rule.charAt(1) == '=') {
                String flags = rule.substring(3);
                reduce.add(new char[] {rule.charAt(0), rule.charAt(2)});
                pretonicOnly.add(flags.indexOf('<') >= 0);
                afterConsonantOnly.add(flags.indexOf('^') >= 0);
            }
        }
    }

    boolean isEmpty() {
        return words.isEmpty() && patterns.isEmpty() && reduce.isEmpty() && devoice.isEmpty();
    }

    String apply(String text) {
        if (isEmpty()) {
            return text;
        }
        // Collect words first: final devoicing depends on the word that follows.
        Matcher m = WORD.matcher(text);
        List<int[]> spans = new ArrayList<int[]>();
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        int last = 0;
        for (int i = 0; i < spans.size(); i++) {
            int[] w = spans.get(i);
            char next = 0;
            if (i + 1 < spans.size()) {
                int[] n = spans.get(i + 1);
                // Only whitespace between the words means they are spoken together.
                if (text.substring(w[1], n[0]).trim().isEmpty()) {
                    next = Character.toLowerCase(text.charAt(n[0]));
                }
            }
            sb.append(text, last, w[0]).append(word(text.substring(w[0], w[1]), next));
            last = w[1];
        }
        sb.append(text.substring(last));
        return sb.toString();
    }

    private String word(String token, char next) {
        String lower = token.toLowerCase(Locale.ROOT);
        String plain = lower.replace(String.valueOf(ACUTE), "");
        if (untouched.contains(plain)) {
            return token;
        }
        String out;
        String listed = words.get(plain);
        if (listed != null) {
            out = listed;
        } else {
            out = lower;
            if (!keep.contains(plain) && plain.length() > 1) {
                for (int i = 0; i < patterns.size(); i++) {
                    out = patterns.get(i).matcher(out).replaceAll(replacements.get(i));
                }
            }
            out = finalDevoicing(out, plain, next);
            out = reduceVowels(out);
        }
        return matchCase(token, out);
    }

    /**
     * A voiced consonant at the end of a word is devoiced before a pause or a
     * voiceless sound, but keeps its voice before a voiced one; short words
     * that lean on the next word keep it before vowels and sonorants too.
     */
    private String finalDevoicing(String w, String plain, char next) {
        if (devoice.isEmpty() || w.isEmpty()) {
            return w;
        }
        int end = w.length() - 1;
        boolean sign = softeners.indexOf(w.charAt(end)) >= 0 && end > 0;
        int at = sign ? end - 1 : end;
        Character to = devoice.get(w.charAt(at));
        if (to == null) {
            return w;
        }
        if (next != 0) {
            if (devoice.containsKey(next)) {
                return w;
            }
            boolean nextVoiceless = devoice.containsValue(next) || isVoicelessOther(next);
            if (proclitics.contains(plain) && !nextVoiceless) {
                return w;
            }
        }
        return w.substring(0, at) + to + w.substring(at + 1);
    }

    /** Voiceless consonants that have no voiced pair in the table. */
    private boolean isVoicelessOther(char c) {
        return voicelessUnpaired.indexOf(c) >= 0;
    }

    /** Rewrites unstressed vowels, only when the stressed one is known. */
    private String reduceVowels(String w) {
        int stressed = -1;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            boolean marked = i + 1 < w.length() && w.charAt(i + 1) == ACUTE;
            if (vowels.indexOf(c) >= 0 && (marked || alwaysStressed.indexOf(c) >= 0)) {
                stressed = i;
                break;
            }
        }
        if (stressed < 0 || reduce.isEmpty()) {
            return w;
        }
        StringBuilder sb = new StringBuilder(w);
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (i == stressed || vowels.indexOf(c) < 0) {
                continue;
            }
            char prev = previousLetter(sb, i);
            boolean afterConsonant = prev != 0 && vowels.indexOf(prev) < 0 && softeners.indexOf(prev) < 0;
            for (int k = 0; k < reduce.size(); k++) {
                char[] pair = reduce.get(k);
                if (c != pair[0] || (pretonicOnly.get(k) && i > stressed)) {
                    continue;
                }
                if (!afterConsonantOnly.get(k)) {
                    sb.setCharAt(i, pair[1]);
                } else if (afterConsonant) {
                    boolean hard = hardening.indexOf(prev) >= 0 && !hardenedE.isEmpty();
                    sb.setCharAt(i, hard ? hardenedE.charAt(0) : pair[1]);
                }
                break;
            }
        }
        return sb.toString();
    }

    private static char previousLetter(CharSequence s, int i) {
        for (int j = i - 1; j >= 0; j--) {
            char p = s.charAt(j);
            if (p != ACUTE) {
                return p;
            }
        }
        return 0;
    }

    private static String matchCase(String original, String out) {
        if (out.isEmpty()) {
            return out;
        }
        String letters = original.replace(String.valueOf(ACUTE), "");
        if (letters.length() > 1 && letters.equals(letters.toUpperCase(Locale.ROOT))) {
            return out.toUpperCase(Locale.ROOT);
        }
        if (Character.isUpperCase(original.charAt(0))) {
            return out.substring(0, 1).toUpperCase(Locale.ROOT) + out.substring(1);
        }
        return out;
    }
}
