package com.shumtugle.hora;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Words spelled alike but stressed differently, resolved by the words around
 * them. Each rule gives a word, its stressed form, and the exact text that must
 * stand right before and right after it. When no rule fits, the word is left
 * unmarked: a wrong mark would be worse than the model's own guess.
 */
final class Homographs {
    private static final String ASSET = "lexicons/homographs.tsv";
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}]+(?:-[\\p{L}\\p{M}]+)*");
    private static Homographs instance;

    private final Map<String, List<String[]>> rules = new HashMap<String, List<String[]>>();
    private final char plainE;
    private final char dottedE;

    private Homographs(char plainE, char dottedE) {
        this.plainE = plainE;
        this.dottedE = dottedE;
    }

    static synchronized Homographs get(Context c) {
        if (instance == null) {
            String pair = SpeechLanguage.resources(c).getString(R.string.letter_folding);
            char from = pair.length() == 3 ? pair.charAt(0) : 0;
            char to = pair.length() == 3 ? pair.charAt(2) : 0;
            instance = new Homographs(to, from);
            instance.load(c);
        }
        return instance;
    }

    private void load(Context c) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open(ASSET), "UTF-8"), 1 << 16);
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] f = line.split("\t", -1);
                    if (f.length == 4) {
                        List<String[]> list = rules.get(f[0]);
                        if (list == null) {
                            list = new ArrayList<String[]>();
                            rules.put(f[0], list);
                        }
                        list.add(new String[] {f[1], f[2], f[3]});
                    }
                }
            } finally {
                r.close();
            }
        } catch (IOException e) {
            // Without rules homographs simply stay unmarked.
        }
    }

    /**
     * Folds text for comparison (lower case, stress marks removed, one letter
     * folded into its plain twin, runs of spaces made single) and remembers
     * where each folded character came from.
     */
    private String fold(String text, int[] origin) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == Lexicon.ACUTE) {
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (space) {
                    continue;
                }
                space = true;
                c = ' ';
            } else {
                space = false;
                c = Character.toLowerCase(c);
                if (c == dottedE) {
                    c = plainE;
                }
            }
            origin[sb.length()] = i;
            sb.append(c);
        }
        return sb.toString();
    }

    String apply(String text) {
        return apply(text, null);
    }

    /** As above, but a word the user has in their own lists is theirs to stress, not the rules'. */
    String apply(String text, Lexicon own) {
        if (rules.isEmpty()) {
            return text;
        }
        int[] origin = new int[text.length() + 1];
        String t = fold(text, origin);
        Matcher m = WORD.matcher(t);
        List<int[]> spans = new ArrayList<int[]>();
        List<String> forms = new ArrayList<String>();
        while (m.find()) {
            List<String[]> candidates = rules.get(m.group());
            if (candidates == null || (own != null && own.hasOwn(m.group()))) {
                continue;
            }
            String best = null;
            int bestLength = -1;
            boolean tie = false;
            for (String[] rule : candidates) {
                String pre = rule[1];
                String suf = rule[2];
                int from = m.start() - pre.length();
                int to = m.end() + suf.length();
                if (from < 0 || to > t.length()) {
                    continue;
                }
                if (!t.startsWith(pre, from) || !t.startsWith(suf, m.end())) {
                    continue;
                }
                if (!pre.isEmpty() && from > 0 && Character.isLetter(t.charAt(from - 1))) {
                    continue;
                }
                if (!suf.isEmpty() && to < t.length() && Character.isLetter(t.charAt(to))) {
                    continue;
                }
                int length = pre.length() + suf.length();
                if (length > bestLength) {
                    best = rule[0];
                    bestLength = length;
                    tie = false;
                } else if (length == bestLength && !rule[0].equals(best)) {
                    tie = true;
                }
            }
            if (best != null && !tie) {
                spans.add(new int[] {origin[m.start()], origin[m.end() - 1] + 1});
                forms.add(best);
            }
        }
        if (spans.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + spans.size());
        int last = 0;
        for (int i = 0; i < spans.size(); i++) {
            int[] s = spans.get(i);
            String original = text.substring(s[0], s[1]);
            out.append(text, last, s[0]).append(matchCase(original, forms.get(i)));
            last = s[1];
        }
        out.append(text.substring(last));
        return out.toString();
    }

    private static String matchCase(String original, String form) {
        if (!original.isEmpty() && Character.isUpperCase(original.charAt(0))) {
            return form.substring(0, 1).toUpperCase(Locale.ROOT) + form.substring(1);
        }
        return form;
    }
}
