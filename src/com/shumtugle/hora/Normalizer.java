package com.shumtugle.hora;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites written forms that a reader expands in speech: dates, years,
 * ordinals with endings, clock times, decimals, percents, version numbers,
 * roman numerals, the number sign, abbreviations, initials and web addresses.
 * Plain digits left over are spelled later by the caller.
 *
 * The code knows only shapes; every word, ending and case comes from the
 * language data. With empty data each step is skipped.
 */
final class Normalizer {

    /** Language data, supplied by the platform or by a test. */
    interface Lang {
        String str(String name);

        String[] arr(String name);

        /** The plural form of a counted word for the given count. */
        String plural(String name, long count);
    }

    /** Cardinal numbers in words. */
    interface Cardinal {
        String number(long n, boolean feminine);
    }

    // Grammatical slots of an ordinal, as listed in the data.
    static final int NOM_M = 0;
    static final int GEN = 1;
    static final int DAT = 2;
    static final int PREP = 3;
    static final int NOM_F = 4;
    static final int PLURAL_GEN = 5;
    static final int INS_M = 6;
    static final int OBLIQUE_F = 7;
    static final int ACC_F = 8;

    private static final String SP = "[ \\u00a0\\u202f]";
    private static final Pattern GROUPED = Pattern.compile("(?<![\\d,.])\\d{1,3}(?:" + SP + "\\d{3})+(?![\\d,.]*\\d)");
    private static final Pattern TIME = Pattern.compile("(?<![\\d.:,])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\d:])");
    private static final Pattern NUMERIC_DATE = Pattern.compile("(?<![\\d.])(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})(?![\\d.]*\\d)");
    private static final Pattern VERSION = Pattern.compile("(?<![\\d.])\\d+(?:\\.\\d+){2,}(?![\\d.]*\\d)");
    private static final Pattern DECIMAL = Pattern.compile("(?<![\\d,.])(\\d+),(\\d{1,3})(?![\\d,])");
    private static final Pattern PERCENT = Pattern.compile("(\\d+(?:,\\d+)?)" + SP + "?%");
    private static final Pattern SUFFIXED = Pattern.compile("(?<![\\d.,])(\\d+)-(\\p{L}{1,3})(?!\\p{L})");
    // Roman numerals, also typed with look-alike Cyrillic capitals (I, X, C, M twins).
    private static final Pattern ROMAN = Pattern.compile(
            "(?<![\\p{L}\\d])([IVXLCDM\\u0406\\u0425\\u0421\\u041c]{1,7})(?![\\p{L}\\d])");
    private static final Pattern WEB = Pattern.compile(
            "(?i)(?:https?://)?(?:www\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,6}(?:/(?:[\\w./-]*[\\w/])?)?"
                    + "|[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    private static final Pattern INITIAL = Pattern.compile("(?<![\\p{L}.])(\\p{Lu})\\." + SP + "?(?=\\p{Lu})");
    private static final Pattern CAPS = Pattern.compile("(?<![\\p{L}])(\\p{Lu}{2,5})(?![\\p{L}])");

    private final Lang lang;
    private final Cardinal cardinal;
    private final Map<Integer, String[]> ordinals = new HashMap<Integer, String[]>();
    private final Map<String, Integer> months = new HashMap<String, Integer>();
    private final Map<String, Integer> yearWords = new HashMap<String, Integer>();
    private final Map<String, Integer> centuryWords = new HashMap<String, Integer>();
    private final Map<String, Integer> titleWords = new HashMap<String, Integer>();
    private final Map<String, Integer> suffixes = new HashMap<String, Integer>();
    private final List<String[]> nameEndings = new ArrayList<String[]>();
    private final List<String[]> femaleEndings = new ArrayList<String[]>();
    private final List<String> femaleStems = new ArrayList<String>();
    private final List<String> dativePrepositions = new ArrayList<String>();
    private final List<String> locativePrepositions = new ArrayList<String>();
    private final List<Pattern> abbrevs = new ArrayList<Pattern>();
    private final List<String> abbrevTexts = new ArrayList<String>();
    private final Map<String, String> letterNames = new HashMap<String, String>();
    private final String vowels;
    private final String yearAbbrev;
    private final String[] yearSpoken;
    private final String[] denominators;
    private final String point;
    private final String slash;
    private final String at;
    private final String dash;
    private final String numberSign;
    private final Pattern dateBefore;
    private final Pattern yearBefore;

    // Counts: a number put in the case its noun or preposition asks for, with its unit.
    static final int C_NOM = 0;
    static final int C_ACC = 1;
    static final int C_GEN = 2;
    static final int C_DAT = 3;
    static final int C_INS = 4;
    static final int C_PREP = 5;
    private static final String[] CASE_NAMES = {"nom", "acc", "gen", "dat", "ins", "prep"};
    private final String[][][] caseUnits = new String[6][][];
    private final String[][] caseTeens = new String[6][];
    private final String[][] caseTens = new String[6][];
    private final String[][] caseHundreds = new String[6][];
    /** Scale rows by power (1 thousand, 2 million, 3 billion): gender, then forms. */
    private final String[][] scaleRows = new String[4][];
    private final Map<String, String[]> unitRows = new HashMap<String, String[]>();
    /** Every written form of a scale word, to its row. */
    private final Map<String, String[]> scaleWords = new HashMap<String, String[]>();
    private final Map<String, Integer> caseBefore = new HashMap<String, Integer>();
    private final List<String[]> caseEndings = new ArrayList<String[]>();
    private final List<String> skipBefore = new ArrayList<String>();
    private final Map<Integer, String[]> feminineEndings = new HashMap<Integer, String[]>();
    private final String seeAbbrev;
    private final String seeWord;
    private final Pattern count;
    private final Pattern decimalUnit;
    private final List<String[]> ordinalAbbrevs = new ArrayList<String[]>();
    private final List<String> nominativeBefore = new ArrayList<String>();

    Normalizer(Lang lang, Cardinal cardinal) {
        this.lang = lang;
        this.cardinal = cardinal;
        for (String row : lang.arr("ordinals")) {
            // "n=stem|slot0|slot1|..."
            int eq = row.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String[] parts = row.substring(eq + 1).split("\\|", -1);
            String[] forms = new String[parts.length - 1];
            for (int i = 1; i < parts.length; i++) {
                forms[i - 1] = parts[0] + parts[i];
            }
            ordinals.put(Integer.parseInt(row.substring(0, eq)), forms);
        }
        String[] monthList = lang.arr("months_genitive");
        StringBuilder monthAlt = new StringBuilder();
        for (int i = 0; i < monthList.length; i++) {
            months.put(monthList[i], i + 1);
            monthAlt.append(i == 0 ? "" : "|").append(Pattern.quote(monthList[i]));
        }
        readSlots(lang.arr("year_words"), yearWords);
        readSlots(lang.arr("century_words"), centuryWords);
        readSlots(lang.arr("title_words"), titleWords);
        readSlots(lang.arr("ordinal_suffixes"), suffixes);
        readOrdered(lang.arr("roman_name_endings"), nameEndings);
        readOrdered(lang.arr("roman_female_endings"), femaleEndings);
        for (String stem : lang.arr("female_name_stems")) {
            femaleStems.add(stem);
        }
        for (String p : lang.arr("dative_prepositions")) {
            dativePrepositions.add(p);
        }
        for (String p : lang.arr("locative_prepositions")) {
            locativePrepositions.add(p);
        }
        for (String row : lang.arr("abbreviations")) {
            int eq = row.lastIndexOf('=');
            if (eq <= 0) {
                continue;
            }
            // Spaces inside an abbreviation may be absent or doubled in real text.
            String written = Pattern.quote(row.substring(0, eq)).replace(" ", "\\E\\s*\\Q");
            abbrevs.add(Pattern.compile("(?<![\\p{L}])" + written + "(?![\\p{L}])"));
            abbrevTexts.add(row.substring(eq + 1));
        }
        for (String row : lang.arr("letter_names")) {
            int eq = row.indexOf('=');
            if (eq > 0) {
                letterNames.put(row.substring(0, eq), row.substring(eq + 1));
            }
        }
        vowels = lang.str("stress_vowels");
        yearAbbrev = lang.str("year_abbrev");
        yearSpoken = lang.arr("year_spoken");
        denominators = lang.arr("decimal_denominators");
        point = lang.str("word_point");
        slash = lang.str("word_slash");
        at = lang.str("word_at");
        dash = lang.str("word_dash");
        numberSign = lang.str("word_number_sign");

        for (int c = 1; c < 6; c++) {
            String[] u = lang.arr("count_units_" + CASE_NAMES[c]);
            if (u.length < 10) {
                continue;
            }
            caseUnits[c] = new String[10][];
            for (int i = 0; i < 10; i++) {
                caseUnits[c][i] = u[i].split("\\|", -1);
            }
            caseTeens[c] = lang.arr("count_teens_" + CASE_NAMES[c]);
            caseTens[c] = lang.arr("count_tens_" + CASE_NAMES[c]);
            caseHundreds[c] = lang.arr("count_hundreds_" + CASE_NAMES[c]);
        }
        for (String row : lang.arr("count_scales")) {
            int eq = row.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String[] forms = row.substring(eq + 1).split("\\|", -1);
            if (forms.length < 11) {
                continue;
            }
            long value = Long.parseLong(row.substring(0, eq));
            int power = value == 1000L ? 1 : value == 1000000L ? 2 : value == 1000000000L ? 3 : 0;
            if (power > 0) {
                scaleRows[power] = forms;
            }
            for (int i = 1; i < forms.length; i++) {
                scaleWords.put(forms[i], forms);
            }
        }
        StringBuilder unitAlt = new StringBuilder();
        List<String> unitKeys = new ArrayList<String>();
        for (String row : lang.arr("count_units")) {
            int eq = row.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String[] forms = row.substring(eq + 1).split("\\|", -1);
            if (forms.length < 11) {
                continue;
            }
            unitRows.put(row.substring(0, eq), forms);
            unitKeys.add(row.substring(0, eq));
        }
        // Longer abbreviations first, so "km" is not taken for "m".
        java.util.Collections.sort(unitKeys, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return b.length() - a.length();
            }
        });
        for (String k : unitKeys) {
            unitAlt.append(unitAlt.length() == 0 ? "" : "|").append(Pattern.quote(k));
        }
        for (String row : lang.arr("count_case_before")) {
            int eq = row.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            int c = caseIndex(row.substring(0, eq));
            for (String w : row.substring(eq + 1).split("\\|")) {
                caseBefore.put(w, c);
            }
        }
        for (String row : lang.arr("count_case_endings")) {
            int eq = row.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            for (String e : row.substring(eq + 1).split("\\|")) {
                caseEndings.add(new String[] {e, row.substring(0, eq)});
            }
        }
        java.util.Collections.sort(caseEndings, new java.util.Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return b[0].length() - a[0].length();
            }
        });
        for (String w : lang.arr("count_skip_before")) {
            skipBefore.add(w);
        }
        for (String row : lang.arr("count_feminine_endings")) {
            int eq = row.indexOf('=');
            if (eq > 0) {
                feminineEndings.put(Integer.parseInt(row.substring(0, eq)), row.substring(eq + 1).split("\\|"));
            }
        }
        for (String row : lang.arr("ordinal_abbrevs")) {
            String[] f = row.split("\\|", -1);
            if (f.length >= 12) {
                ordinalAbbrevs.add(f);
            }
        }
        for (String w : lang.arr("count_nominative_before")) {
            nominativeBefore.add(w);
        }
        decimalUnit = unitAlt.length() == 0 ? null : Pattern.compile(
                "(?<![\\d.,])(\\d+[.,]\\d+)(" + SP + "*)(" + unitAlt + ")\\.?(?![\\p{L}\\d])");
        seeAbbrev = lang.str("see_abbrev");
        seeWord = lang.str("see_word");
        StringBuilder scaleAlt = new StringBuilder();
        for (String w : scaleWords.keySet()) {
            scaleAlt.append(scaleAlt.length() == 0 ? "" : "|").append(Pattern.quote(w));
        }
        // A whole number (thousands may be grouped by spaces), then maybe a scale word, then maybe a unit.
        count = caseUnits[C_GEN] == null ? null : Pattern.compile(
                "(?<![\\d.,:/\\p{L}\\u2116])(?<!\\p{L}-)(\\d{1,3}(?:" + SP + "\\d{3})+|\\d+)(?![\\d]|[.,:]\\d|-\\p{L}|" + SP + "?%)"
                + (scaleAlt.length() == 0 ? "()" : "(?:" + SP + "+(" + scaleAlt + ")(?!\\p{L}))?")
                + (unitAlt.length() == 0 ? "()" : "(?:" + SP + "*(" + unitAlt + ")\\.?(?![\\p{L}\\d]))?"));

        dateBefore = monthList.length == 0 ? null : Pattern.compile(
                "(?<![\\d.,])(\\d{1,2})" + SP + "+(" + monthAlt + ")(?!\\p{L})",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        StringBuilder yw = new StringBuilder();
        for (String w : yearWords.keySet()) {
            yw.append(yw.length() == 0 ? "" : "|").append(Pattern.quote(w));
        }
        if (!yearAbbrev.isEmpty()) {
            yw.append(yw.length() == 0 ? "" : "|").append(Pattern.quote(yearAbbrev)).append("\\.");
        }
        yearBefore = yw.length() == 0 ? null : Pattern.compile(
                "(?<![\\d.,])(\\d{3,4})" + SP + "+(" + yw + ")(?!\\p{L})",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static void readOrdered(String[] rows, List<String[]> into) {
        for (String row : rows) {
            int eq = row.indexOf('=');
            if (eq > 0) {
                into.add(new String[] {row.substring(0, eq), row.substring(eq + 1)});
            }
        }
    }

    private static void readSlots(String[] rows, Map<String, Integer> into) {
        for (String row : rows) {
            int eq = row.indexOf('=');
            if (eq > 0) {
                into.put(row.substring(0, eq), Integer.parseInt(row.substring(eq + 1)));
            }
        }
    }

    String apply(String text) {
        String t = text;
        t = abbreviations(t);
        t = web(t);
        t = merged(t);
        t = numericDates(t);
        t = dates(t);
        t = titled(t);
        t = counts(t);
        t = years(t);
        t = suffixed(t);
        t = times(t);
        t = percents(t);
        t = versions(t);
        t = decimals(t);
        t = ordinalAbbreviations(t);
        t = romans(t);
        t = initials(t);
        t = capsAbbreviations(t);
        if (!numberSign.isEmpty()) {
            t = t.replace("\u2116", " " + numberSign + " ");
        }
        return t;
    }

    // ---- counts -----------------------------------------------------------

    private static int caseIndex(String name) {
        for (int i = 0; i < CASE_NAMES.length; i++) {
            if (CASE_NAMES[i].equals(name)) {
                return i;
            }
        }
        return C_NOM;
    }

    /** A cardinal number in a case; nominative and accusative of the inanimate keep the plain wording. */
    String cardinalCase(long n, int c, boolean feminine) {
        if (c == C_NOM || caseUnits[c] == null || n < 0) {
            return cardinal.number(n, feminine);
        }
        if (n == 0) {
            return caseUnits[c][0][0];
        }
        StringBuilder sb = new StringBuilder();
        long scale = 1_000_000_000L;
        for (int s = 3; s >= 0; s--, scale /= 1000) {
            int group = (int) (n / scale % 1000);
            if (group == 0) {
                continue;
            }
            String[] row = s > 0 ? scaleRows[s] : null;
            if (s > 0 && row == null) {
                return cardinal.number(n, feminine);
            }
            boolean fem = s == 0 ? feminine : "f".equals(row[0]);
            // A lone thousand is said without "one", as in the plain wording.
            if (!(s > 0 && group == 1 && cardinal.number(1000, false).indexOf(' ') < 0)) {
                appendWord(sb, triadCase(group, c, fem));
            }
            if (s > 0) {
                appendWord(sb, form(row, group, c, false));
            }
        }
        return sb.toString();
    }

    private String triadCase(int n, int c, boolean fem) {
        StringBuilder sb = new StringBuilder();
        appendWord(sb, caseHundreds[c][n / 100]);
        int rest = n % 100;
        if (rest >= 10 && rest < 20) {
            appendWord(sb, caseTeens[c][rest - 10]);
        } else {
            appendWord(sb, caseTens[c][rest / 10]);
            if (rest % 10 != 0) {
                String[] u = caseUnits[c][rest % 10];
                appendWord(sb, fem && u.length > 1 ? u[1] : u[0]);
            }
        }
        return sb.toString();
    }

    private static void appendWord(StringBuilder sb, String w) {
        if (w == null || w.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(w);
    }

    /**
     * The form of a counted word (forms: gender, then singular and plural of
     * nominative, accusative, genitive, dative, instrumental, prepositional)
     * after the count n in case c; after a scale word it is always plural.
     */
    private static String form(String[] f, long n, int c, boolean plural) {
        long d = n % 10;
        long h = n % 100;
        boolean one = !plural && d == 1 && h != 11;
        boolean few = !plural && d >= 2 && d <= 4 && (h < 12 || h > 14);
        switch (c) {
            case C_NOM:
                return one ? f[1] : few ? f[3] : f[4];
            case C_ACC:
                return one ? f[2] : few ? f[3] : f[4];
            case C_GEN:
                return one ? f[3] : f[4];
            case C_DAT:
                return one ? f[5] : f[6];
            case C_INS:
                return one ? f[7] : f[8];
            default:
                return one ? f[9] : f[10];
        }
    }

    private static long parseCount(String digits) {
        try {
            return Long.parseLong(digits.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static final Pattern TOKEN_AHEAD = Pattern.compile("[\\p{L}\\p{M}]+|\\d+");

    /** Whether a year word or the year abbreviation follows within a few tokens ("from 1920 to 1933 y."). */
    private boolean yearSoon(String t, int from) {
        Matcher m = TOKEN_AHEAD.matcher(t);
        m.region(from, t.length());
        for (int k = 0; k < 4 && m.find(); k++) {
            String lw = m.group().replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
            if (yearWords.containsKey(lw)
                    || (!yearAbbrev.isEmpty() && (lw.equals(yearAbbrev) || lw.equals(yearAbbrev + yearAbbrev)))) {
                return true;
            }
        }
        return false;
    }

    /** The case the next word shows by its ending, or -1. */
    private int caseAfter(String t, int from) {
        String w = nextWord(t, from).toLowerCase(Locale.ROOT);
        if (w.length() < 4) {
            return -1;
        }
        for (String[] e : caseEndings) {
            if (w.endsWith(e[0])) {
                return caseIndex(e[1]);
            }
        }
        return -1;
    }

    /** The case a preposition (or a governing word) right before asks for, or -1. */
    private int caseBefore(String t, int at) {
        int end = at;
        String w = previousWord(t, end).toLowerCase(Locale.ROOT);
        while (skipBefore.contains(w) && !w.isEmpty()) {
            end = t.lastIndexOf(w, end - 1);
            if (end < 0) {
                return -1;
            }
            w = previousWord(t, end).toLowerCase(Locale.ROOT);
        }
        int wAt = w.isEmpty() ? -1 : t.toLowerCase(Locale.ROOT).lastIndexOf(w, end - 1);
        // Half of a hyphenated pair ("more-or-less") governs nothing.
        if (wAt > 0 && t.charAt(wAt - 1) == '-') {
            return -1;
        }
        if (wAt > 0) {
            String w2 = previousWord(t, wAt).toLowerCase(Locale.ROOT);
            Integer two = caseBefore.get(w2 + " " + w);
            if (two != null) {
                return two;
            }
            int w2At = w2.isEmpty() ? -1 : t.toLowerCase(Locale.ROOT).lastIndexOf(w2, wAt - 1);
            if (w2At > 0) {
                Integer three = caseBefore.get(previousWord(t, w2At).toLowerCase(Locale.ROOT) + " " + w2 + " " + w);
                if (three != null) {
                    return three;
                }
            }
        }
        Integer one = caseBefore.get(w);
        return one == null ? -1 : one;
    }

    /** Whether the noun after a one or a two looks feminine by its ending. */
    private boolean feminineAfter(String t, int from, long n) {
        long d = n % 10;
        long h = n % 100;
        int key = d == 1 && h != 11 ? 1 : d == 2 && h != 12 ? 2 : 0;
        String[] ends = feminineEndings.get(key);
        if (ends == null) {
            return false;
        }
        String w = nextWord(t, from).toLowerCase(Locale.ROOT);
        if (w.length() < 3) {
            return false;
        }
        for (String e : ends) {
            if (w.endsWith(e)) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern JOIN = Pattern.compile("\\s*(?:\u0438|\u0438\u043b\u0438|-|\u2013|\u2014)\\s*");

    /**
     * Numbers counting something: put in the case that the word after them
     * (its plural ending) or the word before them (a preposition) asks for,
     * and units after them spelled in agreement. Numbers joined by "and",
     * "or" or a dash share one case. A number with nothing to agree with is
     * left as digits for the plain spelling later.
     */
    private String counts(String t) {
        if (count == null) {
            return t;
        }
        if (decimalUnit != null) {
            // A fraction counts in the singular genitive: "1.5 metres" reads "one and a half of a metre".
            t = replace(decimalUnit, t, new Fn() {
                @Override
                public String on(Matcher m) {
                    String[] unit = unitRows.get(m.group(3));
                    return unit == null ? m.group() : m.group(1) + " " + unit[3];
                }
            });
        }
        List<int[]> spans = new ArrayList<int[]>();
        List<String[]> parts = new ArrayList<String[]>();
        Matcher m = count.matcher(t);
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
            parts.add(new String[] {m.group(1), m.group(2), m.group(3)});
        }
        if (spans.isEmpty()) {
            return t;
        }
        StringBuilder out = new StringBuilder();
        int done = 0;
        int i = 0;
        while (i < spans.size()) {
            int j = i;
            while (j + 1 < spans.size() && parts.get(j)[2] == null && parts.get(j)[1] == null
                    && JOIN.matcher(t.substring(spans.get(j)[1], spans.get(j + 1)[0])).matches()) {
                j++;
            }
            int first = spans.get(i)[0];
            int last = spans.get(j)[1];
            String[] lastParts = parts.get(j);
            int c = -1;
            boolean hasWord = lastParts[1] != null || lastParts[2] != null;
            String nextW = nextWord(t, last).toLowerCase(Locale.ROOT);
            boolean allYears = true;
            for (int k = i; k <= j; k++) {
                long v = parseCount(parts.get(k)[0]);
                allYears &= v >= 1000 && v < 3000 && parts.get(k)[1] == null && parts.get(k)[2] == null;
            }
            Integer yearSlot = hasWord || !allYears ? null : yearWords.get(nextW);
            // A year with its word right after is the years' business.
            if (allYears && yearSlot != null && j == i) {
                i = j + 1;
                continue;
            }
            // A year whose word comes a little later ("from 1920 to 1933"): an ordinal in its preposition's case.
            if (allYears && yearSlot == null && yearSoon(t, last)) {
                int yc = caseFromWord(t, first);
                if (yc < 0) {
                    i = j + 1;
                    continue;
                }
                yearSlot = slotOf(yc);
            }
            if (!hasWord) {
                c = caseAfter(t, last);
            }
            if (c < 0) {
                c = caseBefore(t, first);
            }
            if (c < 0) {
                c = C_NOM;
            }
            out.append(t, done, first);
            for (int k = i; k <= j; k++) {
                String[] pp = parts.get(k);
                long n = parseCount(pp[0]);
                if (k > i) {
                    out.append(t, spans.get(k - 1)[1], spans.get(k)[0]);
                }
                if (n < 0) {
                    out.append(t, spans.get(k)[0], spans.get(k)[1]);
                    continue;
                }
                if (yearSlot != null) {
                    out.append(ordinal(n, yearWords.containsKey(nextW)
                            ? (c == C_INS ? INS_M : c == C_PREP ? PREP : c == C_DAT ? DAT : yearSlot) : yearSlot));
                    continue;
                }
                String[] scale = pp[1] == null ? null : scaleWords.get(pp[1]);
                String[] unit = pp[2] == null ? null : unitRows.get(pp[2]);
                boolean fem = scale != null ? "f".equals(scale[0]) : unit != null ? "f".equals(unit[0])
                        : feminineAfter(t, spans.get(k)[1], n);
                if (c == C_NOM && unit == null && scale == null && !fem) {
                    out.append(pp[0]);
                    continue;
                }
                out.append(cardinalCase(n, c, fem));
                if (scale != null) {
                    out.append(' ').append(form(scale, n, c, false));
                }
                if (unit != null) {
                    out.append(' ').append(form(unit, n, c, scale != null));
                }
            }
            done = last;
            i = j + 1;
        }
        out.append(t.substring(done));
        return out.toString();
    }

    private static final String ROMAN_PART = "[IVXLCDM\\u0406\\u0425\\u0421\\u041c]{1,7}";

    /** The ordinal slot for a case. */
    private static int slotOf(int c) {
        switch (c) {
            case C_GEN:
                return GEN;
            case C_DAT:
                return DAT;
            case C_INS:
                return INS_M;
            case C_PREP:
                return PREP;
            default:
                return NOM_M;
        }
    }

    /** The case the word right before a numeral asks for, as far as this numeral is concerned; -1 if none. */
    private int caseFromWord(String t, int at) {
        String w = previousWord(t, at).toLowerCase(Locale.ROOT);
        if (nominativeBefore.contains(w)) {
            return C_NOM;
        }
        if (locativePrepositions.contains(w)) {
            return C_PREP;
        }
        return caseBefore(t, at);
    }

    /**
     * Roman numerals before an abbreviated noun ("19th c.", "4th qr."), alone
     * or two joined by a dash, "and", "from-to": each becomes an ordinal in the
     * case its preposition asks for, and the abbreviation the word in the
     * same case, plural when two numerals share it.
     */
    private String ordinalAbbreviations(final String t) {
        if (ordinalAbbrevs.isEmpty() || ordinals.isEmpty()) {
            return t;
        }
        String result = t;
        for (final String[] f : ordinalAbbrevs) {
            Pattern p = Pattern.compile("(?<![\\p{L}\\d])(" + ROMAN_PART + ")(?:(" + SP
                    + "*(?:-|\u2013|\u2014)" + SP + "*|" + SP + "+\\p{L}{1,2}" + SP + "+)(" + ROMAN_PART + "))?" + SP
                    + "+(" + Pattern.quote(f[1]) + "|" + Pattern.quote(f[0]) + ")\\.?(?![\\p{L}\\d])");
            final String src = result;
            result = replace(p, src, new Fn() {
                @Override
                public String on(Matcher m) {
                    String l1 = toLatin(m.group(1));
                    int v1 = l1 == null ? -1 : roman(l1);
                    if (v1 <= 0) {
                        return m.group();
                    }
                    String sep = m.group(2);
                    int v2 = -1;
                    if (m.group(3) != null) {
                        String l2 = toLatin(m.group(3));
                        v2 = l2 == null ? -1 : roman(l2);
                        if (v2 <= 0) {
                            return m.group();
                        }
                    }
                    String sepWord = sep == null ? "" : sep.trim().toLowerCase(Locale.ROOT);
                    boolean joined = v2 > 0 && (sepWord.length() == 1 && "-\u2013\u2014".contains(sepWord)
                            || sepWord.equals(lang.str("word_and")));
                    if (v2 > 0 && !joined && !caseBefore.containsKey(sepWord) && !nominativeBefore.contains(sepWord)) {
                        return m.group();
                    }
                    int c1 = caseFromWord(src, m.start());
                    if (c1 < 0) {
                        c1 = C_NOM;
                    }
                    int c2 = c1;
                    if (v2 > 0 && !joined) {
                        Integer c = nominativeBefore.contains(sepWord) ? Integer.valueOf(C_NOM) : caseBefore.get(sepWord);
                        c2 = c == null ? C_NOM : c;
                    }
                    boolean plural = m.group(4).equals(f[1]) && !f[1].equals(f[0]) || joined;
                    int idx = c2 == C_GEN ? 1 : c2 == C_DAT ? 2 : c2 == C_PREP ? 3 : c2 == C_INS ? 4 : 0;
                    String word = f[2 + idx + (plural ? 5 : 0)];
                    StringBuilder sb = new StringBuilder(ordinal(v1, slotOf(c1)));
                    if (v2 > 0) {
                        sb.append(joined ? " \u2014 " : " " + sep.trim() + " ").append(ordinal(v2, slotOf(c2)));
                    }
                    return sb.append(' ').append(word).toString();
                }
            });
        }
        return result;
    }

    private static final Pattern TITLED_NUMBER = Pattern.compile("(?<![\\d.,])(\\d{1,4})(?![\\d.,]\\d|-\\p{L}|\\d)");

    /**
     * A number right after a word such as "chapter" or "volume" is an
     * ordinal in that word's form: "in chapter 10" reads "in the tenth".
     * Also "see." before a word is read in full.
     */
    private String titled(final String t) {
        String out = t;
        if (!seeAbbrev.isEmpty()) {
            // Not after a number: "5 cm." is a unit, "see." stands before a word.
            out = Pattern.compile("(?<![\\p{L}\\d])(?<!\\d" + SP + ")" + Pattern.quote(seeAbbrev)
                    + "(?=" + SP + "*\\p{L})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(out)
                    .replaceAll(Matcher.quoteReplacement(seeWord));
        }
        if (ordinals.isEmpty() || titleWords.isEmpty()) {
            return out;
        }
        final String src = out;
        return replace(TITLED_NUMBER, src, new Fn() {
            @Override
            public String on(Matcher m) {
                String before = previousWord(src, m.start()).toLowerCase(Locale.ROOT);
                Integer slot = titleWords.get(before);
                if (slot == null) {
                    return m.group();
                }
                int s = slot;
                // "of chapter 10": the genitive, written like the plural nominative.
                if (s == NOM_F && (before.endsWith("\u044b") || before.endsWith("\u0438"))) {
                    s = OBLIQUE_F;
                }
                return ordinal(Long.parseLong(m.group(1)), s);
            }
        });
    }

    // ---- ordinals ---------------------------------------------------------

    /** Ordinal in words: cardinal words for all but the last component. */
    String ordinal(long n, int slot) {
        if (ordinals.isEmpty() || n <= 0) {
            return cardinal.number(n, false);
        }
        String[] whole = ordinals.get((int) Math.min(n, Integer.MAX_VALUE));
        if (whole != null && n < 1_000_000) {
            return pick(whole, slot);
        }
        long last;
        int r100 = (int) (n % 100);
        if (r100 > 0 && r100 < 20) {
            last = r100;
        } else if (n % 10 != 0) {
            last = n % 10;
        } else if (r100 != 0) {
            last = r100;
        } else if (n % 1000 != 0) {
            last = n % 1000;
        } else {
            return cardinal.number(n, false);
        }
        String[] forms = ordinals.get((int) last);
        if (forms == null) {
            return cardinal.number(n, false);
        }
        String head = n - last > 0 ? cardinal.number(n - last, false) + " " : "";
        return head + pick(forms, slot);
    }

    private static String pick(String[] forms, int slot) {
        return forms[Math.min(slot, forms.length - 1)];
    }

    // ---- steps ------------------------------------------------------------

    private String abbreviations(String t) {
        for (int i = 0; i < abbrevs.size(); i++) {
            t = abbrevs.get(i).matcher(t).replaceAll(Matcher.quoteReplacement(abbrevTexts.get(i)));
        }
        return t;
    }

    /** Web and mail addresses: separators become words; letters are handled later. */
    private String web(String t) {
        if (point.isEmpty()) {
            return t;
        }
        return replace(WEB, t, new Fn() {
            @Override
            public String on(Matcher m) {
                String a = m.group().replaceFirst("(?i)^https?://", "");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < a.length(); i++) {
                    char c = a.charAt(i);
                    if (c == '.') {
                        sb.append(' ').append(point).append(' ');
                    } else if (c == '/') {
                        sb.append(' ').append(slash).append(' ');
                    } else if (c == '@') {
                        sb.append(' ').append(at).append(' ');
                    } else if (c == '-' || c == '_') {
                        sb.append(' ').append(dash).append(' ');
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString().trim();
            }
        });
    }

    /** "1 200 000" written with group spaces becomes one number. */
    private String merged(String t) {
        return replace(GROUPED, t, new Fn() {
            @Override
            public String on(Matcher m) {
                return m.group().replaceAll(SP, "");
            }
        });
    }

    private String dates(String t) {
        if (dateBefore == null) {
            return t;
        }
        return replace(dateBefore, t, new Fn() {
            @Override
            public String on(Matcher m) {
                int day = Integer.parseInt(m.group(1));
                if (day < 1 || day > 31) {
                    return m.group();
                }
                return ordinal(day, GEN) + " " + m.group(2);
            }
        });
    }

    /** "28.09.2026": day, month by name, year. */
    private String numericDates(String t) {
        final String[] names = lang.arr("months_genitive");
        if (names.length < 12 || yearSpoken.length <= GEN) {
            return t;
        }
        return replace(NUMERIC_DATE, t, new Fn() {
            @Override
            public String on(Matcher m) {
                int d = Integer.parseInt(m.group(1));
                int mo = Integer.parseInt(m.group(2));
                if (d < 1 || d > 31 || mo < 1 || mo > 12) {
                    return m.group();
                }
                return ordinal(d, GEN) + " " + names[mo - 1] + " "
                        + ordinal(Long.parseLong(m.group(3)), GEN) + " " + yearSpoken[GEN];
            }
        });
    }

    private String years(final String t) {
        if (yearBefore == null) {
            return t;
        }
        return replace(yearBefore, t, new Fn() {
            @Override
            public String on(Matcher m) {
                long year = Long.parseLong(m.group(1));
                String word = m.group(2).toLowerCase(Locale.ROOT);
                int slot;
                String spoken;
                boolean dat = precededBy(t, m.start(), dativePrepositions);
                if (word.endsWith(".")) {
                    boolean loc = precededBy(t, m.start(), locativePrepositions);
                    slot = loc ? PREP : dat ? DAT : GEN;
                    spoken = yearSpoken.length > slot ? yearSpoken[slot] : m.group(2);
                } else {
                    Integer s = yearWords.get(word);
                    slot = s == null ? GEN : s;
                    if (slot == PREP && dat) {
                        slot = DAT;
                    }
                    spoken = m.group(2);
                }
                return ordinal(year, slot) + " " + spoken;
            }
        });
    }

    /** A number with a hyphenated ending: the ending tells the grammatical form. */
    private String suffixed(String t) {
        if (suffixes.isEmpty()) {
            return t;
        }
        return replace(SUFFIXED, t, new Fn() {
            @Override
            public String on(Matcher m) {
                Integer slot = suffixes.get(m.group(2).toLowerCase(Locale.ROOT));
                if (slot == null) {
                    return m.group();
                }
                return ordinal(Long.parseLong(m.group(1)), slot);
            }
        });
    }

    /** "21:07" is said as hours, then minutes with a leading zero spoken. */
    private String times(String t) {
        return replace(TIME, t, new Fn() {
            @Override
            public String on(Matcher m) {
                int h = Integer.parseInt(m.group(1));
                int min = Integer.parseInt(m.group(2));
                String zero = cardinal.number(0, false);
                String mm = min == 0 ? zero + " " + zero
                        : min < 10 ? zero + " " + cardinal.number(min, false)
                        : cardinal.number(min, false);
                return cardinal.number(h, false) + " " + mm;
            }
        });
    }

    private String percents(String t) {
        if (lang.plural("percent", 1).isEmpty()) {
            return t;
        }
        return replace(PERCENT, t, new Fn() {
            @Override
            public String on(Matcher m) {
                String num = m.group(1);
                if (num.indexOf(',') >= 0) {
                    // After a fraction the counted word takes the form of "two".
                    return decimal(num) + " " + lang.plural("percent", 2);
                }
                long n = Long.parseLong(num);
                return cardinal.number(n, false) + " " + lang.plural("percent", n);
            }
        });
    }

    private String versions(String t) {
        if (point.isEmpty()) {
            return t;
        }
        return replace(VERSION, t, new Fn() {
            @Override
            public String on(Matcher m) {
                String[] parts = m.group().split("\\.");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) {
                        sb.append(' ').append(point).append(' ');
                    }
                    sb.append(cardinal.number(Long.parseLong(parts[i]), false));
                }
                return sb.toString();
            }
        });
    }

    private String decimals(String t) {
        if (denominators.length == 0) {
            return t;
        }
        return replace(DECIMAL, t, new Fn() {
            @Override
            public String on(Matcher m) {
                return decimal(m.group());
            }
        });
    }

    /** "37,4": whole part, then the fraction named by its number of digits. */
    private String decimal(String written) {
        int comma = written.indexOf(',');
        long whole = Long.parseLong(written.substring(0, comma));
        String frac = written.substring(comma + 1);
        if (denominators.length < frac.length()) {
            return written;
        }
        long f = Long.parseLong(frac);
        String[] denom = denominators[frac.length() - 1].split("\\|");
        String wholeWord = lang.plural("decimal_whole", whole);
        String fracWord = denom[f % 10 == 1 && f % 100 != 11 ? 0 : Math.min(1, denom.length - 1)];
        return cardinal.number(whole, true) + " " + wholeWord + " "
                + cardinal.number(f, true) + " " + fracWord;
    }

    /** Roman numerals only where they are clearly numbers: centuries, titles, headings. */
    private String romans(final String t) {
        if (ordinals.isEmpty()) {
            return t;
        }
        return replace(ROMAN, t, new Fn() {
            @Override
            public String on(Matcher m) {
                String latin = toLatin(m.group(1));
                if (latin == null) {
                    return m.group();
                }
                int value = roman(latin);
                if (value <= 0) {
                    return m.group();
                }
                String after = nextWord(t, m.end());
                Integer slot = centuryWords.get(after);
                if (slot != null) {
                    return ordinal(value, slot);
                }
                String before = previousWord(t, m.start());
                slot = titleWords.get(before);
                if (slot != null) {
                    return ordinal(value, slot);
                }
                // A regnal number: right after a capitalized name.
                String name = previousToken(t, m.start());
                if (!nameEndings.isEmpty() && name.length() > 1 && Character.isUpperCase(name.charAt(0))
                        && !isLatin(name)) {
                    return ordinal(value, regnalSlot(name.toLowerCase(Locale.ROOT)));
                }
                // A heading: the numeral opens a line and is followed by a dot.
                boolean lineStart = lineStart(t, m.start());
                boolean dot = m.end() < t.length() && t.charAt(m.end()) == '.';
                if (lineStart && dot) {
                    return cardinal.number(value, false);
                }
                return m.group();
            }
        });
    }

    /** Initials: a single capital letter before a dot is said by its name. */
    private String initials(String t) {
        if (letterNames.isEmpty()) {
            return t;
        }
        return replace(INITIAL, t, new Fn() {
            @Override
            public String on(Matcher m) {
                String name = letterNames.get(m.group(1).toLowerCase(Locale.ROOT));
                return name == null ? m.group() : name + " ";
            }
        });
    }

    /** Short capital words without vowels, or of one repeated vowel, are spelled out. */
    private String capsAbbreviations(String t) {
        if (letterNames.isEmpty()) {
            return t;
        }
        return replace(CAPS, t, new Fn() {
            @Override
            public String on(Matcher m) {
                String w = m.group(1).toLowerCase(Locale.ROOT);
                int vowelCount = 0;
                boolean sameVowel = true;
                for (int i = 0; i < w.length(); i++) {
                    if (vowels.indexOf(w.charAt(i)) >= 0) {
                        vowelCount++;
                    }
                    if (w.charAt(i) != w.charAt(0)) {
                        sameVowel = false;
                    }
                }
                boolean spell = vowelCount == 0 || (sameVowel && vowelCount == w.length());
                if (!spell) {
                    return m.group();
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < w.length(); i++) {
                    String name = letterNames.get(String.valueOf(w.charAt(i)));
                    if (name == null) {
                        return m.group();
                    }
                    sb.append(i == 0 ? "" : "-").append(name);
                }
                return sb.toString();
            }
        });
    }

    /** The grammatical form of a regnal number, read from the ending of the name. */
    private int regnalSlot(String name) {
        boolean female = false;
        for (String stem : femaleStems) {
            if (name.startsWith(stem)) {
                female = true;
                break;
            }
        }
        for (String[] e : female ? femaleEndings : nameEndings) {
            if (name.endsWith(e[0])) {
                return Integer.parseInt(e[1]);
            }
        }
        return female ? NOM_F : NOM_M;
    }

    private static boolean isLatin(String w) {
        for (int i = 0; i < w.length(); i++) {
            if (w.charAt(i) < 128 && Character.isLetter(w.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** The word right before a position, with its original letter case, or "". */
    private static String previousToken(String t, int to) {
        Matcher m = Pattern.compile("([\\p{L}\\p{M}]+)[\\s\\u00a0\\u2009\\u202f]+$").matcher(t.substring(0, to));
        return m.find() ? m.group(1).replaceAll("\\p{M}", "") : "";
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Maps Cyrillic look-alikes to Latin roman digits. A token made only of the
     * Cyrillic twins of C and M is an ordinary word, not a numeral: null.
     */
    static String toLatin(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean strong = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\u0406': sb.append('I'); strong = true; break;
                case '\u0425': sb.append('X'); strong = true; break;
                case '\u0421': sb.append('C'); break;
                case '\u041c': sb.append('M'); break;
                default:
                    sb.append(c);
                    if (c == 'I' || c == 'V' || c == 'X' || c == 'L' || c == 'D') {
                        strong = true;
                    }
            }
        }
        return strong ? sb.toString() : null;
    }

    static int roman(String s) {
        int total = 0;
        int prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v;
            switch (s.charAt(i)) {
                case 'I': v = 1; break;
                case 'V': v = 5; break;
                case 'X': v = 10; break;
                case 'L': v = 50; break;
                case 'C': v = 100; break;
                case 'D': v = 500; break;
                case 'M': v = 1000; break;
                default: return -1;
            }
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total > 0 && total < 4000 ? total : -1;
    }

    private static String nextWord(String t, int from) {
        Matcher m = Pattern.compile("^[\\s\\u00a0\\u2009\\u202f]+([\\p{L}\\p{M}]+)").matcher(t.substring(from));
        return m.find() ? m.group(1).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT) : "";
    }

    private static String previousWord(String t, int to) {
        Matcher m = Pattern.compile("([\\p{L}\\p{M}]+)[\\s\\u00a0\\u2009\\u202f]+$").matcher(t.substring(0, to));
        return m.find() ? m.group(1).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT) : "";
    }

    private static boolean precededBy(String t, int at, List<String> words) {
        return !words.isEmpty() && words.contains(previousWord(t, at));
    }

    private static boolean lineStart(String t, int at) {
        for (int i = at - 1; i >= 0; i--) {
            char c = t.charAt(i);
            if (c == '\n') {
                return true;
            }
            if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    private interface Fn {
        String on(Matcher m);
    }

    private static String replace(Pattern p, String text, Fn fn) {
        Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(fn.on(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
