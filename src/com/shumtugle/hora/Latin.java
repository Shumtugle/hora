package com.shumtugle.hora;

import android.content.res.Resources;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Single words in Latin letters, read in the voice's own letters. Which language a word is in
 * is guessed in layers: words known by heart (everyday names of apps and services), the letters
 * and endings of a language, and else the language of the country the phone is in, where street
 * signs, names and app labels are in that language. Finnish and Swedish are read by their own
 * spelling rules, stress on the first syllable; anything else is left to the plain table.
 *
 * The rules here only say where a letter stands (at the start, after a consonant, closing a
 * syllable); every sound comes from resources of the voice language. Without them, nothing is
 * read here.
 */
final class Latin {
    private static final char ACUTE = '\u0301';
    private static final String FI_VOWELS = "aeiouy\u00e4\u00f6";

    /**
     * Words this class wrote in the text being read now, per thread, so the phonetic rewriting
     * that follows leaves them as they are. Cleared for every new text: a native word spelled
     * like a foreign one read earlier keeps its own rewriting.
     */
    private static final ThreadLocal<Set<String>> MADE = new ThreadLocal<Set<String>>() {
        @Override
        protected Set<String> initialValue() {
            return new java.util.HashSet<String>();
        }
    };

    /** A new text begins: forget the foreign words of the last one. */
    static void begin() {
        MADE.get().clear();
    }

    private final Map<String, String> known = new HashMap<String, String>();
    private final Map<String, String> fi = new HashMap<String, String>();
    private final Map<String, String> sv = new HashMap<String, String>();
    private final Map<String, String> svAfter = new LinkedHashMap<String, String>();
    private final String[] svEndings;
    private final String vowelsOut;
    private final boolean finnishHere;
    private final boolean swedishHere;
    private int svLongest;

    /** @param country the two-letter country the phone is in, lower case; empty when unknown */
    Latin(Resources r, String country) {
        pairs(r.getStringArray(R.array.latin_known), known);
        pairs(r.getStringArray(R.array.latin_fi), fi);
        pairs(r.getStringArray(R.array.latin_sv), sv);
        pairs(r.getStringArray(R.array.latin_sv_after), svAfter);
        for (String k : sv.keySet()) {
            if (k.indexOf('.') < 0) {
                svLongest = Math.max(svLongest, k.length());
            }
        }
        svEndings = r.getStringArray(R.array.latin_sv_endings);
        vowelsOut = r.getString(R.string.stress_vowels);
        finnishHere = listed(r.getStringArray(R.array.latin_fi_countries), country);
        swedishHere = listed(r.getStringArray(R.array.latin_sv_countries), country);
    }

    /** Whether there are rules to read by at all: the voice language has them. */
    boolean isEmpty() {
        return fi.isEmpty() && known.isEmpty();
    }

    /** Whether the phonetic rewriting should leave a word as it is: it was read here. */
    static boolean made(String plainLower) {
        return MADE.get().contains(plainLower);
    }

    /** The word in the voice's letters with its stress marked, or null to leave it to the plain table. */
    String read(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        String out = known.get(w);
        if (out == null && !acronym(word)) {
            if (swedish(w)) {
                out = swedishWord(w);
            } else if (!englishLooking(w)) {
                if (finnishHere || !swedishHere && finnish(w)) {
                    out = finnishWord(w);
                } else if (swedishHere) {
                    out = swedishWord(w);
                }
            }
        }
        if (out != null && !out.isEmpty()) {
            for (String part : out.replace(String.valueOf(ACUTE), "").split(" ")) {
                MADE.get().add(part);
            }
            return out;
        }
        return null;
    }

    private boolean swedish(String w) {
        if (sv.isEmpty()) {
            return false;
        }
        if (w.indexOf('\u00e5') >= 0) {
            return true;
        }
        for (String e : svEndings) {
            if (w.length() > e.length() + 1 && w.endsWith(e)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Letters and pairs Finnish and Swedish names hardly use and English does: such a word is
     * left to the plain table, wherever the phone is.
     */
    private static boolean englishLooking(String w) {
        return w.matches(".*([cqwxz]|th|sh|ch|ph|zh|ck).*") || w.matches("y[aeiou].*");
    }

    /**
     * A short word in capitals only, like an abbreviation: not a word to read by a language's
     * rules. A long one, or one with a letter of the language, is a name written in capitals.
     */
    private static boolean acronym(String word) {
        return word.length() > 1 && word.length() <= 4 && word.equals(word.toUpperCase(Locale.ROOT))
                && !word.matches(".*[\u00c4\u00d6\u00c5].*");
    }

    /** Signs of Finnish, for a phone outside Finland: its letters, or long vowels English rarely has. */
    private static boolean finnish(String w) {
        return w.matches(".*[\u00e4\u00f6].*") || w.matches(".*(aa|ii|uu|yy).*");
    }

    private static boolean vowel(char c, String set) {
        return set.indexOf(c) >= 0;
    }

    private String finnishWord(String s) {
        if (fi.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        String lastPiece = "";
        int n = s.length();
        for (int i = 0; i < n; ) {
            char c = s.charAt(i);
            char next = i + 1 < n ? s.charAt(i + 1) : 0;
            char prev = i > 0 ? s.charAt(i - 1) : 0;
            boolean afterConsonant = i > 0 && !vowel(prev, FI_VOWELS);
            String piece = null;
            int take = 1;
            if (c == 'j' && next != 0 && vowel(next, FI_VOWELS)) {
                piece = fi.get("j" + next);
                take = 2;
                if (piece != null) {
                    // What a long vowel after it repeats is the vowel alone, not the j with it.
                    String alone = fi.containsKey(next + ".c") ? fi.get(next + ".c") : fi.get(String.valueOf(next));
                    out.append(piece);
                    lastPiece = alone == null ? piece : alone;
                    i += take;
                    continue;
                }
            } else if (c == 'i' && i > 0 && vowel(prev, FI_VOWELS) && prev != 'i'
                    && (next == 0 || !vowel(next, FI_VOWELS)) && !(i >= 2 && s.startsWith("ie", i - 2))) {
                // The second half of a diphthong: ai, ei, oi, ui, ai with umlaut.
                piece = fi.get("i.glide");
            } else if (vowel(c, FI_VOWELS) && c == prev && "y\u00e4\u00f6".indexOf(c) >= 0) {
                // A long vowel: the same sound again, not its word-initial form.
                piece = lastPiece;
            } else if (vowel(c, FI_VOWELS)) {
                piece = afterConsonant && fi.containsKey(c + ".c") ? fi.get(c + ".c") : fi.get(String.valueOf(c));
            } else if (c == 'l' && (next == 0 || !vowel(next, FI_VOWELS) && next != 'l')) {
                piece = fi.get("l.coda");
            } else {
                piece = fi.get(String.valueOf(c));
            }
            if (piece == null) {
                // A letter outside the rules: this is not a word to read by them.
                return null;
            }
            out.append(piece);
            lastPiece = piece;
            i += take;
        }
        return stressFirst(out.toString());
    }

    private String swedishWord(String s) {
        if (sv.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        int n = s.length();
        outer:
        for (int i = 0; i < n; ) {
            for (int len = Math.min(svLongest, n - i); len > 1; len--) {
                String piece = sv.get(s.substring(i, i + len));
                if (piece != null) {
                    out.append(piece);
                    i += len;
                    continue outer;
                }
            }
            char c = s.charAt(i);
            String piece = null;
            if (i == 0) {
                piece = sv.get(c + ".0");
            }
            if (piece == null && i == n - 1) {
                piece = sv.get(c + ".end");
            }
            if (piece == null) {
                piece = sv.get(String.valueOf(c));
            }
            if (piece == null) {
                return null;
            }
            out.append(piece);
            i++;
        }
        String t = out.toString();
        for (Map.Entry<String, String> e : svAfter.entrySet()) {
            t = t.replace(e.getKey(), e.getValue());
        }
        return stressFirst(t);
    }

    /** The stress mark after the first vowel: both languages stress the first syllable. */
    private String stressFirst(String w) {
        for (int i = 0; i < w.length(); i++) {
            if (vowelsOut.indexOf(w.charAt(i)) >= 0) {
                return w.substring(0, i + 1) + ACUTE + w.substring(i + 1);
            }
        }
        return w;
    }

    private static void pairs(String[] rows, Map<String, String> into) {
        for (String row : rows) {
            int eq = row.indexOf('=');
            if (eq > 0) {
                into.put(row.substring(0, eq), row.substring(eq + 1));
            }
        }
    }

    private static boolean listed(String[] rows, String country) {
        if (country == null || country.isEmpty()) {
            return false;
        }
        for (String c : rows) {
            if (c.equals(country)) {
                return true;
            }
        }
        return false;
    }
}
