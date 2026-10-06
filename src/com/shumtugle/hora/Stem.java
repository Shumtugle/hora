package com.shumtugle.hora;

/**
 * Cuts Russian words down to their stems, so a search for one form of a
 * word finds the others: the Snowball rules for Russian, written out here.
 * Words are expected in lower case with yo already folded into ye.
 */
final class Stem {
    private Stem() {
    }

    private static final String VOWELS = "\u0430\u0435\u0438\u043e\u0443\u044b\u044d\u044e\u044f";

    private static final String[] GERUND_1 = words("\u0432 \u0432\u0448\u0438 \u0432\u0448\u0438\u0441\u044c");
    private static final String[] GERUND_2 = words("\u0438\u0432 \u0438\u0432\u0448\u0438 \u0438\u0432\u0448\u0438\u0441\u044c"
            + " \u044b\u0432 \u044b\u0432\u0448\u0438 \u044b\u0432\u0448\u0438\u0441\u044c");
    private static final String[] ADJECTIVE = words("\u0435\u0435 \u0438\u0435 \u044b\u0435 \u043e\u0435 \u0438\u043c\u0438"
            + " \u044b\u043c\u0438 \u0435\u0439 \u0438\u0439 \u044b\u0439 \u043e\u0439 \u0435\u043c \u0438\u043c \u044b\u043c"
            + " \u043e\u043c \u0435\u0433\u043e \u043e\u0433\u043e \u0435\u043c\u0443 \u043e\u043c\u0443 \u0438\u0445 \u044b\u0445"
            + " \u0443\u044e \u044e\u044e \u0430\u044f \u044f\u044f \u043e\u044e \u0435\u044e");
    private static final String[] PARTICIPLE_1 = words("\u0435\u043c \u043d\u043d \u0432\u0448 \u044e\u0449 \u0449");
    private static final String[] PARTICIPLE_2 = words("\u0438\u0432\u0448 \u044b\u0432\u0448 \u0443\u044e\u0449");
    private static final String[] REFLEXIVE = words("\u0441\u044f \u0441\u044c");
    private static final String[] VERB_1 = words("\u043b\u0430 \u043d\u0430 \u0435\u0442\u0435 \u0439\u0442\u0435 \u043b\u0438"
            + " \u0439 \u043b \u0435\u043c \u043d \u043b\u043e \u043d\u043e \u0435\u0442 \u044e\u0442 \u043d\u044b \u0442\u044c"
            + " \u0435\u0448\u044c \u043d\u043d\u043e");
    private static final String[] VERB_2 = words("\u0438\u043b\u0430 \u044b\u043b\u0430 \u0435\u043d\u0430 \u0435\u0439\u0442\u0435"
            + " \u0443\u0439\u0442\u0435 \u0438\u0442\u0435 \u0438\u043b\u0438 \u044b\u043b\u0438 \u0435\u0439 \u0443\u0439"
            + " \u0438\u043b \u044b\u043b \u0438\u043c \u044b\u043c \u0435\u043d \u0438\u043b\u043e \u044b\u043b\u043e \u0435\u043d\u043e"
            + " \u044f\u0442 \u0443\u0435\u0442 \u0443\u044e\u0442 \u0438\u0442 \u044b\u0442 \u0435\u043d\u044b \u0438\u0442\u044c"
            + " \u044b\u0442\u044c \u0438\u0448\u044c \u0443\u044e \u044e");
    private static final String[] NOUN = words("\u0430 \u0435\u0432 \u043e\u0432 \u0438\u0435 \u044c\u0435 \u0435 \u0438\u044f\u043c\u0438"
            + " \u044f\u043c\u0438 \u0430\u043c\u0438 \u0435\u0438 \u0438\u0438 \u0438 \u0438\u0435\u0439 \u0435\u0439 \u043e\u0439"
            + " \u0438\u0439 \u0439 \u0438\u044f\u043c \u044f\u043c \u0438\u0435\u043c \u0435\u043c \u0430\u043c \u043e\u043c \u043e"
            + " \u0443 \u0430\u0445 \u0438\u044f\u0445 \u044f\u0445 \u044b \u044c \u0438\u044e \u044c\u044e \u044e \u0438\u044f"
            + " \u044c\u044f \u044f");
    private static final String[] SUPERLATIVE = words("\u0435\u0439\u0448\u0435 \u0435\u0439\u0448");
    private static final String[] DERIVATIONAL = words("\u043e\u0441\u0442\u044c \u043e\u0441\u0442");

    private static String[] words(String s) {
        String[] w = s.split(" ");
        // Longest first, so the longest ending that fits is the one cut.
        java.util.Arrays.sort(w, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return b.length() - a.length();
            }
        });
        return w;
    }

    private static boolean vowel(char c) {
        return VOWELS.indexOf(c) >= 0;
    }

    /** The stem of one word in lower case. Words without a vowel come back as they are. */
    static String of(String word) {
        int n = word.length();
        int rv = -1;
        for (int i = 0; i < n; i++) {
            if (vowel(word.charAt(i))) {
                rv = i + 1;
                break;
            }
        }
        if (rv < 0 || rv >= n) {
            return word;
        }
        // R1: after the first non-vowel that follows a vowel; R2: the same again inside R1.
        int r1 = region(word, 0);
        int r2 = region(word, r1);
        StringBuilder w = new StringBuilder(word);

        if (!cut(w, rv, GERUND_2, false) && !cut(w, rv, GERUND_1, true)) {
            cut(w, rv, REFLEXIVE, false);
            if (cutAdjectival(w, rv)) {
                // done
            } else if (!cut(w, rv, VERB_2, false) && !cut(w, rv, VERB_1, true)) {
                cut(w, rv, NOUN, false);
            }
        }
        if (w.length() > rv && w.charAt(w.length() - 1) == '\u0438') {
            w.setLength(w.length() - 1);
        }
        cut(w, Math.max(rv, r2), DERIVATIONAL, false);
        if (endsWith(w, rv, "\u043d\u043d")) {
            w.setLength(w.length() - 1);
        } else if (cut(w, rv, SUPERLATIVE, false)) {
            if (endsWith(w, rv, "\u043d\u043d")) {
                w.setLength(w.length() - 1);
            }
        } else if (w.length() > rv && w.charAt(w.length() - 1) == '\u044c') {
            w.setLength(w.length() - 1);
        }
        return w.toString();
    }

    private static int region(String word, int from) {
        for (int i = from + 1; i < word.length(); i++) {
            if (!vowel(word.charAt(i)) && vowel(word.charAt(i - 1))) {
                return i + 1;
            }
        }
        return word.length();
    }

    private static boolean endsWith(StringBuilder w, int from, String end) {
        int start = w.length() - end.length();
        return start >= from && w.indexOf(end, start) == start;
    }

    /** Cuts the longest ending of the list inside the region; the first group needs a or ya before it. */
    private static boolean cut(StringBuilder w, int from, String[] ends, boolean afterAorYa) {
        for (String e : ends) {
            if (!endsWith(w, from, e)) {
                continue;
            }
            int start = w.length() - e.length();
            if (afterAorYa) {
                if (start - 1 < from) {
                    continue;
                }
                char before = w.charAt(start - 1);
                if (before != '\u0430' && before != '\u044f') {
                    continue;
                }
            }
            w.setLength(start);
            return true;
        }
        return false;
    }

    private static boolean cutAdjectival(StringBuilder w, int from) {
        if (!cut(w, from, ADJECTIVE, false)) {
            return false;
        }
        if (!cut(w, from, PARTICIPLE_2, false)) {
            cut(w, from, PARTICIPLE_1, true);
        }
        return true;
    }
}
