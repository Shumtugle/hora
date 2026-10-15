package com.shumtugle.hora;

import android.content.res.Resources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes English out the way it is said, for the English model. Left raw, the model reads digits
 * one by one and stumbles on signs: "3rd of 12" comes out as "third of one two", "7:35 AM" as
 * "735M", and a dotted "a.m." cuts the whole phrase short. Here numbers become words: cardinals,
 * decimals, ordinals, years and decades, clock times, money, percent, degrees, a unit after a
 * number, long numbers and phone numbers digit by digit; titles and other abbreviations are
 * spelled out, a roman number after a chapter word is read as a number. A slash between numbers
 * is read as written: a date like 12/10 means different days in different countries, so it is
 * not guessed.
 *
 * Every word comes from resources of the English language; the rules here only say where a
 * thing stands. Without the English module the class is never asked.
 */
final class English {
    private static final String NUM = "(\\d{1,3}(?:,\\d{3})+|\\d+)";
    private static final String DEC = "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)";
    /** Signs written as escapes, so the source stays plain ASCII. */
    private static final String EURO = "\u20ac";
    private static final String POUND = "\u00a3";
    private static final String DEGREE = "\u00b0";
    private static final String MINUS_SIGNS = "\\-\u2212\u2013";

    /** A word that ends a sentence when the text goes on with a capital or stops. */
    private static final Pattern SENTENCE_GOES_ON = Pattern.compile("\\s+[\"'\u2019\u201c\u201d(]*[A-Z]");

    private static final Pattern ISO_DATE = Pattern.compile("(?<![\\d.,])(1[1-9]\\d\\d|20\\d\\d)-(\\d\\d)-(\\d\\d)(?![\\d])");
    /** A date written with dots or dashes, day or month first: read as written, a pause between. */
    private static final Pattern DATE_DOTTED = Pattern.compile(
            "(?<![\\d.,A-Za-z])([0-3]?\\d)([.\\-])([0-3]?\\d)\\2(1[1-9]\\d\\d|20\\d\\d)(?![\\d]|[.,]\\d)");
    private static final Pattern PHONE_PLUS = Pattern.compile("\\+(\\d{1,4}(?:[ \\-]\\(?\\d{1,8}\\)?){1,6})(?![\\d])");
    /** Thousands set apart by spaces, as some write them: 10 000. */
    private static final Pattern SPACED_THOUSANDS = Pattern.compile(
            "(?<![\\d.,]|\\d )[1-9]\\d{0,2}(?: \\d{3})+(?![\\d]| \\d)");
    private static final Pattern PHONE_GROUPS = Pattern.compile(
            "(?<![\\d.,+(])(?:\\(\\d{2,4}\\)\\s?|\\d{1,4}[ \\-])\\d{2,4}(?:[ \\-]\\d{2,4}){1,4}(?![\\d])");
    /** A local phone number: three digits, a dash, four. */
    private static final Pattern PHONE_LOCAL = Pattern.compile("(?<![\\d.,\\-])\\d{3}-\\d{4}(?![\\d]|-\\d)");
    /** A round hundred before a dash starts a span (800-1200 words), not a phone. */
    private static final Pattern ROUND_HUNDRED = Pattern.compile("\\d00-.*");
    /** A span between two numbers: a dash without spaces, or an en dash. */
    private static final Pattern RANGE = Pattern.compile(
            "(?<![\\d.,:/])([1-9]\\d{0,3}|0)(?:-|\\s?\u2013\\s?)([1-9]\\d{0,3}|0)(?![\\d.,:]\\d|\\d)");
    private static final Pattern MINUS = Pattern.compile("(^|[\\s(\\[=:])[" + MINUS_SIGNS + "](?=\\d)");
    private static final Pattern TIME = Pattern.compile(
            "(?<![\\d.,:])(\\d{1,2}):([0-5]\\d)(?::([0-5]\\d))?(?![\\d])(\\s?(?:[AaPp]\\.\\s?[Mm]\\.?|[AaPp][Mm])(?![A-Za-z]))?");
    private static final Pattern HOUR_ONLY = Pattern.compile(
            "(?<![\\d.,:])(\\d{1,2})\\s?([AaPp]\\.\\s?[Mm]\\.?|[AaPp][Mm])(?![A-Za-z])");
    private static final Pattern MONEY_BEFORE = Pattern.compile(
            "([\\$" + EURO + POUND + "])\\s?" + NUM + "(?:\\.(\\d+))?(?![\\d])(?:\\s?(k|m|bn|thousand|million|billion)(?![A-Za-z]))?");
    private static final Pattern MONEY_AFTER = Pattern.compile(
            "(?<![\\d.,])" + NUM + "(?:[.,](\\d{1,2}))?\\s?([" + EURO + POUND + "])");
    private static final Pattern PERCENT = Pattern.compile("(?<![\\d.,])" + DEC + "\\s?%");
    private static final Pattern DEGREES = Pattern.compile(
            "(?<![\\d.,])" + DEC + "\\s?" + DEGREE + "\\s?([CF](?![A-Za-z]))?");
    private static final Pattern ORDINAL = Pattern.compile("(?<![\\d.,])" + NUM + "(st|nd|rd|th)(?![A-Za-z])");
    private static final Pattern DECADE = Pattern.compile("(?<![\\d.,])(1[1-9]|20)(\\d)0s(?![A-Za-z])");
    /** The '60s, the sixties; a bare 60s is left to the units, where it is sixty seconds. */
    private static final Pattern SHORT_DECADE = Pattern.compile(
            "(?:['\u2019]|(?<=\\b(?i:the|their|his|her|its|our|my|early|late|mid)[ \\-]))([2-9])0s(?![A-Za-z])");
    private static final Pattern SLASH_NUMBERS = Pattern.compile("(?<![\\d.,])\\d+(?:\\s?/\\s?\\d+)+(?![\\d])");
    private static final Pattern SLASH = Pattern.compile("\\s?/\\s?");
    private static final Pattern SLASH_WORDS = Pattern.compile("(?<=[A-Za-z])/(?=[A-Za-z])");
    private static final Pattern ROMAN = Pattern.compile("[IVXLCDM]+");
    /**
     * A roman number numbering a chapter rather than the pronoun or a letter: heading a line
     * before a colon and a capital ("I: Into the Primitive", "V: The Toil"), standing alone on a
     * line ("II", "IV."), heading a sentence before a colon and a capital ("Recalled to Life. I:
     * The Period"), or ending a line after a title with no stop of its own ("The Pilgrim. I"). A
     * sentence end counts only after a word of three small letters, so the stop of a title
     * before a name ("Mr. X: ...") is no sentence end.
     */
    private static final Pattern LEADING_ROMAN = Pattern.compile(
            "^([IVXLC]{1,10})(?=:\\s+[\\p{Lu}\u201c\"])"
            + "|^([IVXLC]{2,10})(?=[.:]?\\s*$)"
            + "|(?<=[a-z]{3}[.!?]\\s)([IVXLC]{1,10})(?=:\\s+[\\p{Lu}\u201c\"])"
            + "|(?<=[a-z]{3}\\.\\s)([IVXLC]{1,10})(?=\\s*$)");

    /** A name and a roman number of two letters or more: a monarch, a pope, a ship. */
    private static final Pattern REGNAL = Pattern.compile("(?<![A-Za-z])([A-Z][a-z]+)\\s+([IVX]{2,6})(?![A-Za-z'\u2019])");
    private static final Pattern YEAR = Pattern.compile("(?<![\\d.,])(1[1-9]\\d\\d|20\\d\\d)(?![\\d]|[.,]\\d)");
    /** The word before a number, to tell a year from a count. */
    private static final Pattern WORD_BEFORE = Pattern.compile("([A-Za-z]+)[\\s,]*$");
    private static final Pattern WORD_AFTER = Pattern.compile("\\s+([A-Za-z]+)");
    private static final Pattern GROUPED = Pattern.compile("(?<![\\d.,])(\\d{1,3}(?:,\\d{3})+)(?:\\.(\\d+))?(?![\\d])");
    /** A version or an address of numbers joined by dots: two point zero point one. */
    private static final Pattern DOTTED = Pattern.compile("(?<![\\d.,])\\d{1,12}(?:\\.\\d{1,12}){2,8}(?![\\d])");
    private static final Pattern DECIMAL = Pattern.compile("(?<![\\d.,])(\\d+)\\.(\\d+)(?![\\d])");
    private static final Pattern INTEGER = Pattern.compile("\\d+");
    private static final Pattern NUMBER_SIGN = Pattern.compile("#\\s?(?=\\d)");
    private static final Pattern PLUS = Pattern.compile("\\s?\\+\\s?(?=\\d)");
    private static final Pattern AMPERSAND = Pattern.compile("\\s?&\\s?");
    private static final Pattern SPACES = Pattern.compile("[ \\t]{2,}");
    /** A number glued after a letter (a version, a model): the two are read apart. */
    private static final Pattern LETTER_DIGIT = Pattern.compile("(?<=[A-Za-z])(?=\\d)");
    private static final Pattern SPACE_BEFORE_MARK = Pattern.compile(" +([,.;:!?])");

    private final String[] units;
    private final String[] teens;
    private final String[] tens;
    private final String[] scales;
    private final Map<String, String> words = new HashMap<String, String>();
    private final Map<String, String> ordinals = new HashMap<String, String>();
    private final Map<String, String> plurals = new HashMap<String, String>();
    private final List<String[]> unitWords = new ArrayList<String[]>();
    private final Pattern unitPattern;
    private final List<Abbrev> abbrevs = new ArrayList<Abbrev>();
    private final Pattern romanAfter;
    private final Pattern dayAfterMonth;
    private final Pattern dayBeforeMonth;
    private final java.util.Set<String> yearBefore = new java.util.HashSet<String>();
    private final java.util.Set<String> notNames = new java.util.HashSet<String>();
    private final java.util.Set<String> counted = new java.util.HashSet<String>();
    private final java.util.Set<String> notCounted = new java.util.HashSet<String>();
    private final java.util.Set<String> openers = new java.util.HashSet<String>();

    /** An abbreviation and where it may stand. */
    private static final class Abbrev {
        final Pattern pattern;
        final String said;
        /** It may end a sentence: its dot then stays as the sentence's end. */
        final boolean mayEnd;

        Abbrev(Pattern pattern, String said, boolean mayEnd) {
            this.pattern = pattern;
            this.said = said;
            this.mayEnd = mayEnd;
        }
    }

    /** @param r resources of the English language */
    English(Resources r) {
        units = r.getStringArray(R.array.num_units_m);
        teens = r.getStringArray(R.array.num_teens);
        tens = r.getStringArray(R.array.num_tens);
        scales = new String[] {"",
                r.getQuantityString(R.plurals.num_scale_1, 2),
                r.getQuantityString(R.plurals.num_scale_2, 2),
                r.getQuantityString(R.plurals.num_scale_3, 2)};
        pairs(r.getStringArray(R.array.en_words), words);
        pairs(r.getStringArray(R.array.en_ordinals), ordinals);
        pairs(r.getStringArray(R.array.en_plurals), plurals);
        for (String line : r.getStringArray(R.array.en_units)) {
            String[] p = line.split("\\|");
            if (p.length == 3) {
                unitWords.add(p);
            }
        }
        // Longer signs first, so km/h is not taken for km.
        List<String[]> sorted = new ArrayList<String[]>(unitWords);
        Collections.sort(sorted, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return b[0].length() - a[0].length();
            }
        });
        StringBuilder alt = new StringBuilder();
        for (String[] u : sorted) {
            alt.append(alt.length() == 0 ? "" : "|").append(Pattern.quote(u[0]));
        }
        unitPattern = alt.length() == 0 ? null
                : Pattern.compile("(?<![\\d.,])" + DEC + "\\s?(" + alt + ")(?![A-Za-z/])");
        for (String line : r.getStringArray(R.array.en_abbrev)) {
            String[] p = line.split("\\|");
            if (p.length >= 2) {
                abbrevs.add(abbrev(p[0], p[1], p.length > 2 ? p[2] : ""));
            }
        }
        StringBuilder after = new StringBuilder();
        for (String w : r.getStringArray(R.array.en_roman_after)) {
            after.append(after.length() == 0 ? "" : "|").append(Pattern.quote(w));
        }
        StringBuilder months = new StringBuilder();
        for (String w : r.getStringArray(R.array.en_months)) {
            months.append(months.length() == 0 ? "" : "|").append(Pattern.quote(w));
        }
        dayAfterMonth = Pattern.compile("(?<![A-Za-z])(" + months + ")\\s+([1-9]|[12]\\d|3[01])(?![\\d:.,]\\d|\\d)");
        dayBeforeMonth = Pattern.compile("(?<![\\d.,])([1-9]|[12]\\d|3[01])\\s+(" + months + ")(?![A-Za-z])");
        // Only the word ignores case; the number is in capitals and no apostrophe follows it (I'm).
        romanAfter = Pattern.compile("(?<![A-Za-z])((?i:" + after + "))\\s+([IVXLCDM]+)(?![A-Za-z'\u2019])");
        for (String w : r.getStringArray(R.array.en_year_before)) {
            yearBefore.add(w);
        }
        for (String w : r.getStringArray(R.array.en_not_names)) {
            notNames.add(w);
        }
        java.util.Collections.addAll(counted, r.getStringArray(R.array.en_counted));
        java.util.Collections.addAll(notCounted, r.getStringArray(R.array.en_not_counted));
        java.util.Collections.addAll(openers, r.getStringArray(R.array.en_openers));
    }

    /**
     * An abbreviation's pattern by its kind: "t" a title before a name (the dot may be left
     * out, a capital follows), "e" one that may end a sentence, "d" one that stands before a
     * number, empty for one that stands anywhere.
     */
    private static Abbrev abbrev(String written, String said, String kind) {
        String bare = written.endsWith(".") ? written.substring(0, written.length() - 1) : written;
        String body;
        if ("t".equals(kind)) {
            body = Pattern.quote(bare) + "(?:\\.|(?=\\s+[A-Z]))";
        } else if ("d".equals(kind)) {
            body = Pattern.quote(written) + "(?=\\s?\\d)";
        } else {
            body = Pattern.quote(written) + (written.endsWith(".") ? "" : "(?![A-Za-z])");
        }
        return new Abbrev(Pattern.compile("(?<![A-Za-z.])" + body), said, "e".equals(kind));
    }

    private static void pairs(String[] lines, Map<String, String> into) {
        for (String line : lines) {
            int bar = line.indexOf('|');
            if (bar > 0) {
                into.put(line.substring(0, bar), line.substring(bar + 1));
            }
        }
    }

    private String word(String key) {
        String w = words.get(key);
        return w == null ? key : w;
    }

    /** The text as it is said. */
    String say(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String t = LETTER_DIGIT.matcher(text).replaceAll(" ");
        t = isoDates(t);
        t = dottedDates(t);
        t = phones(t);
        t = RANGE.matcher(t).replaceAll("$1 " + Matcher.quoteReplacement(word("to")) + " $2");
        t = MINUS.matcher(t).replaceAll("$1" + Matcher.quoteReplacement(word("minus")) + " ");
        t = times(t);
        t = money(t);
        t = percent(t);
        t = degrees(t);
        t = ordinals(t);
        t = decades(t);
        t = unitsAfterNumbers(t);
        t = slashes(t);
        t = SLASH_WORDS.matcher(t).replaceAll(" ");
        t = romans(t);
        t = abbreviations(t);
        t = saintOrStreet(t);
        t = NUMBER_SIGN.matcher(t).replaceAll(Matcher.quoteReplacement(word("number")) + " ");
        t = PLUS.matcher(t).replaceAll(" " + Matcher.quoteReplacement(word("plus")) + " ");
        t = AMPERSAND.matcher(t).replaceAll(" " + Matcher.quoteReplacement(word("and")) + " ");
        t = dates(t);
        t = years(t);
        t = numbers(t);
        t = SPACES.matcher(t).replaceAll(" ");
        t = SPACE_BEFORE_MARK.matcher(t).replaceAll("$1");
        return t.trim();
    }

    // ---- numbers ----

    /** A whole number in words; a number too long for words is read digit by digit. */
    String cardinal(long n) {
        if (n < 0) {
            return word("minus") + " " + cardinal(-n);
        }
        if (n == 0) {
            return units[0];
        }
        if (n > 999999999999L) {
            return digits(String.valueOf(n));
        }
        StringBuilder out = new StringBuilder();
        long scale = 1000000000L;
        for (int s = 3; s >= 0; s--) {
            int group = (int) (n / scale % 1000);
            if (group > 0) {
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(below1000(group));
                if (s > 0) {
                    out.append(' ').append(scales[s]);
                }
            }
            scale /= 1000;
        }
        return out.toString();
    }

    private String below1000(int n) {
        int h = n / 100;
        int r = n % 100;
        StringBuilder out = new StringBuilder();
        if (h > 0) {
            out.append(units[h]).append(' ').append(word("hundred"));
        }
        if (r > 0) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(below100(r));
        }
        return out.toString();
    }

    private String below100(int n) {
        if (n < 10) {
            return units[n];
        }
        if (n < 20) {
            return teens[n - 10];
        }
        return n % 10 == 0 ? tens[n / 10] : tens[n / 10] + "-" + units[n % 10];
    }

    /** Digits one by one, as a phone number or a code is read. */
    String digits(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(units[c - '0']);
            }
        }
        return out.toString();
    }

    /** First, second, twenty-third: the last word of the cardinal turned. */
    String ordinal(long n) {
        String c = cardinal(n);
        int cut = Math.max(c.lastIndexOf(' '), c.lastIndexOf('-')) + 1;
        String last = c.substring(cut);
        String turned = ordinals.get(last);
        return c.substring(0, cut) + (turned != null ? turned : last + word("th"));
    }

    /** A year: nineteen ninety, nineteen oh five, two thousand five, twenty twenty-six. */
    String year(int y) {
        if (y >= 2000 && y < 2010) {
            return cardinal(y);
        }
        int hi = y / 100;
        int lo = y % 100;
        if (lo == 0) {
            return below100(hi) + " " + word("hundred");
        }
        if (lo < 10) {
            return below100(hi) + " " + word("oh") + " " + units[lo];
        }
        return below100(hi) + " " + below100(lo);
    }

    private String plural(String phrase) {
        int cut = Math.max(phrase.lastIndexOf(' '), phrase.lastIndexOf('-')) + 1;
        String last = phrase.substring(cut);
        String p = plurals.get(last);
        return phrase.substring(0, cut) + (p != null ? p : last + "s");
    }

    private static long value(String grouped) {
        return Long.parseLong(grouped.replace(",", ""));
    }

    /** A number as written, with its fraction read digit by digit after "point". */
    private String decimal(String whole, String fraction) {
        String w = whole.replace(",", "").length() > 12 ? digits(whole) : cardinal(value(whole));
        return fraction == null || fraction.isEmpty() ? w : w + " " + word("point") + " " + digits(fraction);
    }

    private String decimal(String written) {
        int dot = written.indexOf('.');
        return dot < 0 ? decimal(written, null) : decimal(written.substring(0, dot), written.substring(dot + 1));
    }

    private static boolean isOne(String written) {
        return "1".equals(written);
    }

    // ---- the passes, in the order they run ----

    private String isoDates(String t) {
        Matcher m = ISO_DATE.matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String said = year(Integer.parseInt(m.group(1))) + ", " + cardinal(Integer.parseInt(m.group(2)))
                    + ", " + cardinal(Integer.parseInt(m.group(3)));
            m.appendReplacement(out, Matcher.quoteReplacement(said));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String dottedDates(String t) {
        Matcher m = DATE_DOTTED.matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String said = cardinal(Integer.parseInt(m.group(1))) + ", " + cardinal(Integer.parseInt(m.group(3)))
                    + ", " + year(Integer.parseInt(m.group(4)));
            m.appendReplacement(out, Matcher.quoteReplacement(said));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Numbers with slashes between them, read as written: twelve slash ten. */
    private String slashes(String t) {
        Matcher m = SLASH_NUMBERS.matcher(t);
        StringBuffer out = new StringBuffer();
        String said = " " + word("slash") + " ";
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(SLASH.matcher(m.group()).replaceAll(said)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Phone numbers: digit by digit, a short pause between the groups as written. */
    private String phones(String t) {
        Matcher m = PHONE_PLUS.matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(word("plus") + " " + groups(m.group(1))));
        }
        m.appendTail(out);
        // Groups of three after a short head are a number written with spaces, not a phone.
        m = SPACED_THOUSANDS.matcher(out.toString());
        out = new StringBuffer();
        while (m.find()) {
            // Only five digits or more: 1 800 is a code, not one thousand eight hundred.
            String g = m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(g.length() - g.replace(" ", "").length() > 0
                    && g.replace(" ", "").length() >= 5 ? g.replace(' ', ',') : g));
        }
        m.appendTail(out);
        m = PHONE_GROUPS.matcher(out.toString());
        out = new StringBuffer();
        while (m.find()) {
            String g = m.group();
            int count = g.replaceAll("\\D", "").length();
            // A phone has a group of three digits or more; 10 20 30 40 is a list of numbers.
            boolean longGroup = g.startsWith("(") || g.matches(".*\\d{3}.*");
            m.appendReplacement(out, Matcher.quoteReplacement(count >= 7 && longGroup ? groups(g) : g));
        }
        m.appendTail(out);
        m = PHONE_LOCAL.matcher(out.toString());
        out = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(ROUND_HUNDRED.matcher(m.group()).matches()
                    ? m.group() : groups(m.group())));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String groups(String g) {
        StringBuilder out = new StringBuilder();
        for (String part : g.split("[ \\-()]+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(digits(part));
        }
        return out.toString();
    }

    private String times(String t) {
        Matcher m = TIME.matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            String suffix = m.group(4);
            if (h > 24) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            StringBuilder said = new StringBuilder(cardinal(h));
            if (min == 0) {
                if (suffix == null) {
                    said.append(' ').append(word("oclock"));
                }
            } else if (min < 10) {
                said.append(' ').append(word("oh")).append(' ').append(units[min]);
            } else {
                said.append(' ').append(below100(min));
            }
            if (m.group(3) != null && Integer.parseInt(m.group(3)) > 0) {
                said.append(' ').append(below100(Integer.parseInt(m.group(3))));
            }
            if (suffix != null) {
                said.append(' ').append(meridiem(suffix));
                said.append(endDot(t, m.end(), suffix));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(said.toString()));
        }
        m.appendTail(out);
        m = HOUR_ONLY.matcher(out.toString());
        String in = out.toString();
        out = new StringBuffer();
        while (m.find()) {
            int h = Integer.parseInt(m.group(1));
            String said = h < 1 || h > 12 ? m.group()
                    : cardinal(h) + " " + meridiem(m.group(2)) + endDot(in, m.end(), m.group(2));
            m.appendReplacement(out, Matcher.quoteReplacement(said));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String meridiem(String suffix) {
        char c = Character.toLowerCase(suffix.trim().charAt(0));
        return word(c == 'a' ? "am" : "pm");
    }

    /** A dotted abbreviation at a sentence's end carried that end: give the dot back. */
    private static String endDot(String text, int end, String written) {
        if (!written.endsWith(".")) {
            return "";
        }
        return end >= text.length() || SENTENCE_GOES_ON.matcher(text).region(end, text.length()).lookingAt()
                ? "." : "";
    }

    /** Whether the word after a position is one that usually opens a sentence (Then, He, The). */
    private boolean openerAfter(String text, int end) {
        Matcher w = WORD_AFTER.matcher(text).region(end, text.length());
        return w.lookingAt() && openers.contains(w.group(1));
    }

    private String money(String t) {
        Matcher m = MONEY_BEFORE.matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(sum(m.group(1), m.group(2), m.group(3), m.group(4))));
        }
        m.appendTail(out);
        m = MONEY_AFTER.matcher(out.toString());
        out = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(sum(m.group(3), m.group(1), m.group(2), null)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Five dollars ninety-nine, fifty cents, one euro, two point five million pounds. */
    private String sum(String sign, String whole, String fraction, String scale) {
        String one;
        String many;
        String small;
        String smalls;
        if (EURO.equals(sign)) {
            one = word("euro");
            many = word("euros");
            small = word("cent");
            smalls = word("cents");
        } else if (POUND.equals(sign)) {
            one = word("pound");
            many = word("pounds");
            small = word("penny");
            smalls = word("pence");
        } else {
            one = word("dollar");
            many = word("dollars");
            small = word("cent");
            smalls = word("cents");
        }
        if (scale != null) {
            String s = scale.toLowerCase(java.util.Locale.ROOT);
            String big = s.startsWith("k") || s.startsWith("t") ? scales[1] : s.startsWith("m") ? scales[2] : scales[3];
            return decimal(whole, fraction) + " " + big + " " + many;
        }
        // A sum too long for words, or with more than two places, is read as a number.
        if (whole.replace(",", "").length() > 12 || fraction != null && fraction.length() > 2) {
            return decimal(whole, fraction) + " " + many;
        }
        long w = value(whole);
        int cents = 0;
        if (fraction != null) {
            cents = Integer.parseInt(fraction.length() == 1 ? fraction + "0" : fraction);
        }
        if (w == 0 && cents > 0) {
            return below100(cents) + " " + (cents == 1 ? small : smalls);
        }
        String out = cardinal(w) + " " + (w == 1 ? one : many);
        return cents > 0 ? out + " " + below100(cents) : out;
    }

    private String percent(String t) {
        Matcher m = PERCENT.matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(decimal(m.group(1)) + " " + word("percent")));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String degrees(String t) {
        Matcher m = DEGREES.matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String said = decimal(m.group(1)) + " " + word(isOne(m.group(1)) ? "degree" : "degrees");
            if (m.group(2) != null) {
                said += " " + word("C".equals(m.group(2)) ? "celsius" : "fahrenheit");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(said));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String ordinals(String t) {
        Matcher m = ORDINAL.matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String n = m.group(1);
            m.appendReplacement(out, Matcher.quoteReplacement(n.length() > 12 ? m.group() : ordinal(value(n))));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String decades(String t) {
        Matcher m = DECADE.matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            int y = Integer.parseInt(m.group(1) + m.group(2) + "0");
            String said = y % 100 == 0 ? (y == 2000 ? plural(cardinal(2000)) : plural(year(y))) : plural(year(y));
            m.appendReplacement(out, Matcher.quoteReplacement(said));
        }
        m.appendTail(out);
        m = SHORT_DECADE.matcher(out.toString());
        out = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(plural(tens[Integer.parseInt(m.group(1))])));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String unitsAfterNumbers(String t) {
        if (unitPattern == null) {
            return t;
        }
        Matcher m = unitPattern.matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String[] u = null;
            for (String[] x : unitWords) {
                if (x[0].equals(m.group(2))) {
                    u = x;
                    break;
                }
            }
            String said = u == null ? m.group() : decimal(m.group(1)) + " " + (isOne(m.group(1)) ? u[1] : u[2]);
            m.appendReplacement(out, Matcher.quoteReplacement(said));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String romans(String t) {
        Matcher lead = LEADING_ROMAN.matcher(t);
        StringBuffer led = new StringBuffer();
        while (lead.find()) {
            int g = lead.group(1) != null ? 1 : lead.group(2) != null ? 2 : lead.group(3) != null ? 3 : 4;
            String numeral = lead.group(g);
            int n = roman(numeral);
            // Only what a chapter number looks like: one of I, V, X in it (CC, CL are letters), not
            // too large, and a number standing alone smaller still (a lone CV or XL is a letter pair).
            boolean likely = numeral.matches(".*[IVX].*") && n > 0 && n < (g == 2 ? 100 : 200);
            lead.appendReplacement(led, Matcher.quoteReplacement(likely ? cardinal(n) : lead.group()));
        }
        lead.appendTail(led);
        t = led.toString();
        Matcher m = romanAfter.matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String numeral = m.group(2);
            int n = roman(numeral);
            // Chapters and parts stay small: a large value is a word in capitals, not a number.
            n = n < 400 ? n : 0;
            // A single C, D, L or M is a letter (Type C); a single I only after a capitalized
            // word (Part I), never after a lower-case one, where it is the pronoun.
            if (numeral.length() == 1 && ("CDLM".indexOf(numeral.charAt(0)) >= 0
                    || numeral.equals("I") && !Character.isUpperCase(m.group(1).charAt(0)))) {
                n = 0;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(n > 0 ? m.group(1) + " " + cardinal(n) : m.group()));
        }
        m.appendTail(out);
        // What is left after a name is an order: a monarch the eighth, a pope the twelfth.
        m = REGNAL.matcher(out.toString());
        out = new StringBuffer();
        while (m.find()) {
            int n = roman(m.group(2));
            boolean name = !notNames.contains(m.group(1).toLowerCase(java.util.Locale.ROOT));
            m.appendReplacement(out, Matcher.quoteReplacement(name && n >= 2 && n < 40
                    ? m.group(1) + " " + word("the") + " " + ordinal(n) : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The value of a well-formed roman number, 0 for anything else. */
    static int roman(String s) {
        if (!ROMAN.matcher(s).matches()) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = romanDigit(s.charAt(i));
            int next = i + 1 < s.length() ? romanDigit(s.charAt(i + 1)) : 0;
            total += v < next ? -v : v;
        }
        // Only the canonical spelling counts, so a word like "MIX" or "DIVIL" is not a number.
        return total > 0 && total < 4000 && toRoman(total).equals(s) ? total : 0;
    }

    private static int romanDigit(char c) {
        switch (c) {
            case 'I': return 1;
            case 'V': return 5;
            case 'X': return 10;
            case 'L': return 50;
            case 'C': return 100;
            case 'D': return 500;
            default: return 1000;
        }
    }

    private static String toRoman(int n) {
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            while (n >= v[i]) {
                out.append(s[i]);
                n -= v[i];
            }
        }
        return out.toString();
    }

    private String abbreviations(String t) {
        String in = t;
        for (Abbrev a : abbrevs) {
            Matcher m = a.pattern.matcher(in);
            if (!m.find()) {
                continue;
            }
            StringBuffer out = new StringBuffer();
            do {
                boolean ends = a.mayEnd || m.group().endsWith(".") && openerAfter(in, m.end());
                String said = a.said + (ends ? endDot(in, m.end(), m.group()) : "");
                m.appendReplacement(out, Matcher.quoteReplacement(said));
            } while (m.find());
            m.appendTail(out);
            in = out.toString();
        }
        return in;
    }

    private static final Pattern ST = Pattern.compile("(?<![A-Za-z.])(St|Dr)(\\.|(?=\\s+[A-Z]))");
    private static final Pattern NAME_BEFORE = Pattern.compile("(?:[A-Z][a-z]+|\\d+[A-Za-z]?)\\s+$");

    /**
     * St and Dr: before a capitalized word a saint and a doctor; after a name, with nothing
     * capitalized following, a street and a drive.
     */
    private String saintOrStreet(String t) {
        Matcher m = ST.matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            boolean saint = m.group(1).equals("St");
            boolean nameAfter = SENTENCE_GOES_ON.matcher(t).region(m.end(), t.length()).lookingAt();
            boolean nameBefore = NAME_BEFORE.matcher(t.substring(Math.max(0, m.start() - 40), m.start())).find();
            // A name before and a sentence opener after (Main St. Then...) is a street ending a sentence.
            boolean place = nameBefore && (!nameAfter || m.group(2).equals(".") && openerAfter(t, m.end()));
            String said;
            if (place) {
                said = word(saint ? "street" : "drive") + endDot(t, m.end(), m.group());
            } else {
                said = word(saint ? "saint" : "doctor");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(said));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** October 14 is October fourteenth; 14 October is the fourteenth of October. */
    private String dates(String t) {
        Matcher m = dayAfterMonth.matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + " " + ordinal(Integer.parseInt(m.group(2)))));
        }
        m.appendTail(out);
        m = dayBeforeMonth.matcher(out.toString());
        out = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(word("the") + " " + ordinal(Integer.parseInt(m.group(1)))
                    + " " + word("of") + " " + m.group(2)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String years(String t) {
        Matcher m = YEAR.matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(isYear(t, m.start(), m.end())
                    ? year(Integer.parseInt(m.group(1))) : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * A four-digit number is a year after a word that leads to one (in, since, the, a month,
     * a day as an ordinal), or when a mark or the end follows it; before a plural noun it is
     * a count (2048 bytes); anywhere else, a year.
     */
    private boolean isYear(String t, int start, int end) {
        if (end >= t.length() || ",.;:!?)\u2013\u2014\"'\u2019\u201d".indexOf(t.charAt(end)) >= 0
                || t.startsWith("s", end)) {
            return true;
        }
        Matcher w = WORD_BEFORE.matcher(t.substring(Math.max(0, start - 30), start));
        if (w.find()) {
            String before = w.group(1).toLowerCase(java.util.Locale.ROOT);
            // After a day said as an ordinal (October fourteenth, 2026) a year follows too.
            if (yearBefore.contains(before) || ordinals.containsValue(before)) {
                return true;
            }
        }
        Matcher a = WORD_AFTER.matcher(t).region(end, t.length());
        if (!a.lookingAt()) {
            return true;
        }
        String after = a.group(1);
        boolean plural = after.length() > 2 && after.endsWith("s") && Character.isLowerCase(after.charAt(0))
                && !notCounted.contains(after);
        return !(plural || counted.contains(after));
    }

    /** What is left: grouped and decimal numbers, then plain ones; a leading zero means a code. */
    private String numbers(String t) {
        Matcher m = GROUPED.matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(decimal(m.group(1), m.group(2))));
        }
        m.appendTail(out);
        m = DOTTED.matcher(out.toString());
        out = new StringBuffer();
        while (m.find()) {
            StringBuilder said = new StringBuilder();
            for (String part : m.group().split("\\.")) {
                if (said.length() > 0) {
                    said.append(' ').append(word("point")).append(' ');
                }
                said.append(part.length() > 1 && part.charAt(0) == '0' || part.length() > 12
                        ? digits(part) : cardinal(Long.parseLong(part)));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(said.toString()));
        }
        m.appendTail(out);
        m = DECIMAL.matcher(out.toString());
        out = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(decimal(m.group(1), m.group(2))));
        }
        m.appendTail(out);
        String in = out.toString();
        m = INTEGER.matcher(in);
        out = new StringBuffer();
        while (m.find()) {
            String n = m.group();
            String said = n.length() > 1 && n.charAt(0) == '0' || n.length() > 12 ? digits(n) : cardinal(Long.parseLong(n));
            // A letter glued to a number, as in a house number, is read after it, apart.
            if (m.end() < in.length() && Character.isLetter(in.charAt(m.end()))) {
                said += " ";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(said));
        }
        m.appendTail(out);
        return out.toString();
    }
}
