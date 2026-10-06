package com.shumtugle.hora;

import android.content.res.Resources;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns arbitrary text into text the voice can read: digits become words,
 * Latin letters are transliterated, and characters the voice has no symbols
 * for are replaced by spaces. All language data comes from resources.
 */
final class TextPrep {
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern LATIN = Pattern.compile("[A-Za-z]+");
    private static final Pattern SPACES = Pattern.compile("[ \\t]{2,}");
    private static final int MAX_NUMBER_DIGITS = 12;

    private final Resources res;
    private final String[] unitsM;
    private final String[] unitsF;
    private final String[] teens;
    private final String[] tens;
    private final String[] hundreds;
    private final int[] scaleFeminine;
    private final boolean dropSingleOne;
    private final int[] scalePlurals = {0, R.plurals.num_scale_1, R.plurals.num_scale_2,
            R.plurals.num_scale_3};
    private final String[] keys;
    private final String[] values;
    private final Set<Integer> allowed;
    private final Normalizer normalizer;
    private final java.util.Map<Character, Character> lookalikes = new java.util.HashMap<Character, Character>();
    private final java.util.Set<String> oneLetterWords = new java.util.HashSet<String>();
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{M}]+");

    /** @param allowed code points the voice can read, or null to skip filtering */
    TextPrep(Resources res, Set<Integer> allowed) {
        this.res = res;
        this.allowed = allowed;
        unitsM = res.getStringArray(R.array.num_units_m);
        unitsF = res.getStringArray(R.array.num_units_f);
        teens = res.getStringArray(R.array.num_teens);
        tens = res.getStringArray(R.array.num_tens);
        hundreds = res.getStringArray(R.array.num_hundreds);
        scaleFeminine = res.getIntArray(R.array.num_scale_feminine);
        dropSingleOne = res.getBoolean(R.bool.num_drop_single_one);

        for (String pair : res.getStringArray(R.array.latin_lookalikes)) {
            if (pair.length() == 3 && pair.charAt(1) == '=') {
                lookalikes.put(pair.charAt(0), pair.charAt(2));
            }
        }
        for (String w : res.getStringArray(R.array.one_letter_words)) {
            oneLetterWords.add(w);
        }
        normalizer = new Normalizer(new AndroidLang(res), new Normalizer.Cardinal() {
            @Override
            public String number(long n, boolean feminine) {
                return TextPrep.this.number(n, feminine);
            }
        });

        String[] pairs = res.getStringArray(R.array.translit);
        Arrays.sort(pairs, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return b.indexOf('=') - a.indexOf('=');
            }
        });
        keys = new String[pairs.length];
        values = new String[pairs.length];
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            keys[i] = pairs[i].substring(0, eq);
            values[i] = pairs[i].substring(eq + 1);
        }
    }

    // Characters that readers insert for layout and that are invisible on screen:
    // soft hyphens for justified text, zero-width spaces and joiners, byte-order marks.
    private static final Pattern INVISIBLE = Pattern.compile("[\\u00ad\\u200b\\u200c\\u200d\\u2060\\ufeff]");
    private static final Pattern ODD_SPACES = Pattern.compile("[\\u00a0\\u2007\\u2009\\u202f]");

    /**
     * Removes layout-only characters. A soft hyphen inside a name splits it into
     * pieces that no dictionary or rule recognizes, so this runs before anything else.
     */
    static String clean(String text) {
        String t = INVISIBLE.matcher(text).replaceAll("");
        return ODD_SPACES.matcher(t).replaceAll(" ");
    }

    /** The text with invisible and unusual characters made visible, for the journal. */
    static String reveal(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean plain = c == ' ' || c == '\n' || (c >= 0x20 && c < 0x7f)
                    || (Character.isLetterOrDigit(c) && c != '\u00ad')
                    || ".,:;!?()\u00ab\u00bb\u2014\u2013-".indexOf(c) >= 0;
            if (plain) {
                sb.append(c);
            } else {
                sb.append(String.format(java.util.Locale.ROOT, "<%04x>", (int) c));
            }
        }
        return sb.toString();
    }

    /**
     * Scanned books mix look-alike Latin letters into native words ("B" for the
     * native one-letter preposition, a Latin "o" inside a word). A word that
     * already has native letters gets its Latin twins replaced; a lone Latin
     * letter becomes native when its twin is a one-letter word of the language.
     * Words made only of Latin letters, roman numerals included, stay as they are.
     */
    String fixScripts(String text) {
        if (lookalikes.isEmpty()) {
            return text;
        }
        Matcher m = TOKEN.matcher(text);
        StringBuffer sb = new StringBuffer(text.length());
        while (m.find()) {
            String w = m.group();
            boolean nativeLetter = false;
            boolean latin = false;
            for (int i = 0; i < w.length(); i++) {
                char c = w.charAt(i);
                if (c < 128 && Character.isLetter(c)) {
                    latin = true;
                } else if (Character.isLetter(c)) {
                    nativeLetter = true;
                }
            }
            String out = w;
            if (latin && nativeLetter) {
                out = swap(w);
            } else if (latin && w.length() == 1) {
                String twin = swap(w);
                if (!twin.equals(w) && oneLetterWords.contains(twin.toLowerCase(Locale.ROOT))) {
                    out = twin;
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(out));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String swap(String w) {
        StringBuilder sb = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            Character twin = lookalikes.get(w.charAt(i));
            sb.append(twin == null ? w.charAt(i) : twin);
        }
        return sb.toString();
    }

    String apply(String text) {
        String out = replaceAll(DIGITS, normalizer.apply(fixScripts(text)), new Fn() {
            @Override
            public String on(String digits) {
                return spellDigits(digits);
            }
        });
        if (keys.length > 0) {
            out = replaceAll(LATIN, out, new Fn() {
                @Override
                public String on(String word) {
                    return transliterate(word.toLowerCase(Locale.ROOT));
                }
            });
        }
        return SPACES.matcher(filter(out)).replaceAll(" ");
    }

    private String spellDigits(String digits) {
        String d = digits.replaceFirst("^0+(?=\\d)", "");
        if (d.length() > MAX_NUMBER_DIGITS) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digits.length(); i++) {
                sb.append(i == 0 ? "" : " ").append(unitsM[digits.charAt(i) - '0']);
            }
            return sb.toString();
        }
        return number(Long.parseLong(d));
    }

    /** Cardinal number in words, nominative, masculine. */
    String number(long n) {
        return number(n, false);
    }

    /** Cardinal number in words, nominative; feminine affects only the last group. */
    String number(long n, boolean feminine) {
        if (n == 0) {
            return unitsM[0];
        }
        StringBuilder sb = new StringBuilder();
        long scale = 1_000_000_000L;
        for (int s = 3; s >= 0; s--, scale /= 1000) {
            int group = (int) (n / scale % 1000);
            if (group == 0) {
                continue;
            }
            if (!(s > 0 && group == 1 && dropSingleOne)) {
                append(sb, triad(group, s == 0 ? feminine : scaleFeminine[s] != 0));
            }
            if (s > 0) {
                append(sb, res.getQuantityString(scalePlurals[s], group));
            }
        }
        return sb.toString();
    }

    private String triad(int n, boolean feminine) {
        StringBuilder sb = new StringBuilder();
        append(sb, hundreds[n / 100]);
        int rest = n % 100;
        if (rest >= 10 && rest < 20) {
            append(sb, teens[rest - 10]);
        } else {
            append(sb, tens[rest / 10]);
            if (rest % 10 != 0) {
                append(sb, (feminine ? unitsF : unitsM)[rest % 10]);
            }
        }
        return sb.toString();
    }

    private String transliterate(String w) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        outer:
        while (i < w.length()) {
            for (int k = 0; k < keys.length; k++) {
                if (w.startsWith(keys[k], i)) {
                    sb.append(values[k]);
                    i += keys[k].length();
                    continue outer;
                }
            }
            sb.append(w.charAt(i++));
        }
        return sb.toString();
    }

    private String filter(String s) {
        if (allowed == null) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || allowed.contains(cp)) {
                sb.appendCodePoint(cp);
            } else {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    /**
     * Characters present in the voice's token vocabulary. ASCII letters and
     * digits are left out on purpose: their partial coverage breaks tokenization,
     * and by the time filtering runs they have been rewritten anyway.
     */
    static Set<Integer> charsOf(Iterable<String> pieces) {
        Set<Integer> set = new HashSet<Integer>();
        for (String p : pieces) {
            if (p.startsWith("<") && p.endsWith(">")) {
                continue;
            }
            for (int i = 0; i < p.length(); ) {
                int cp = p.codePointAt(i);
                i += Character.charCount(cp);
                boolean ascii = cp < 128 && Character.isLetterOrDigit(cp);
                if (!ascii && cp != 0x2581) {
                    set.add(cp);
                }
            }
        }
        return set;
    }

    private static void append(StringBuilder sb, String w) {
        if (w == null || w.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(w);
    }

    private interface Fn {
        String on(String match);
    }

    private static String replaceAll(Pattern p, String text, Fn fn) {
        Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(fn.on(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
