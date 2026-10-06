package com.shumtugle.hora;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A book as Hora reads it: its paragraphs, title, author and chapters.
 * Chapters come from the file's own markup where it has one (book sections,
 * page headings), and otherwise from lines that look like headings: a
 * chapter word of the reading language with a short tail, or a lone number.
 */
final class Book {
    /** Put in front of a line the markup declared a heading; never read aloud. */
    static final char HEADING = '\uE000';
    private static final int SHORT_LINE = 80;
    private static final int FIRST_WORDS = 6;
    private static final Pattern NUMBER = Pattern.compile("^(?:[IVXLCDM]{1,7}|\\d{1,3})\\.?$");

    /** A chapter: the paragraph it starts at, its heading, the first words after it. */
    static final class Chapter {
        final int at;
        final String label;
        final String first;

        Chapter(int at, String label, String first) {
            this.at = at;
            this.label = label;
            this.first = first;
        }
    }

    final List<String> paragraphs = new ArrayList<String>();
    final List<Chapter> chapters = new ArrayList<Chapter>();
    String title = "";
    String author = "";

    /**
     * Builds a book from extracted lines. chapterWords: the words that open a
     * chapter heading in the reading language, separated by '|'; ordinals:
     * how the word after it ends when it is a number in words ("fifth"
     * and its kin). A chapter word followed by anything else ("The book lay on
     * the table.") is ordinary text.
     */
    static Book of(List<String> lines, String fallbackTitle, String metaTitle, String metaAuthor,
            String chapterWords, String ordinals) {
        Book b = new Book();
        Pattern word = Pattern.compile("^(?:" + chapterWords + ")(?:\\s+(?:(?:\\d{1,3}|[IVXLCDM]{1,7})(?![\\p{L}\\p{N}]).*"
                + "|\\S*(?:" + ordinals + ")(?!\\p{L})[.:]?(?:\\s.*)?))?$",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        List<Integer> heads = new ArrayList<Integer>();
        for (String raw : lines) {
            boolean marked = raw.indexOf(HEADING) >= 0;
            String s = raw.replace(String.valueOf(HEADING), "").trim();
            if (s.isEmpty()) {
                continue;
            }
            boolean looks = s.length() <= SHORT_LINE
                    && (word.matcher(s).matches() || NUMBER.matcher(s).matches());
            if (marked || looks) {
                heads.add(b.paragraphs.size());
            }
            b.paragraphs.add(s);
        }
        b.title = metaTitle != null && !metaTitle.trim().isEmpty() ? metaTitle.trim() : guessTitle(b, heads);
        if (b.title.isEmpty()) {
            b.title = fallbackTitle == null ? "" : fallbackTitle;
        }
        b.author = metaAuthor == null ? "" : metaAuthor.trim();
        for (int i = 0; i < heads.size(); i++) {
            int at = heads.get(i);
            String label = trimDot(b.paragraphs.get(at));
            // The book's own title or its author standing at the head of the text is not a chapter.
            if (at <= 3 && (label.equalsIgnoreCase(b.title) || sameName(label, b.author))) {
                continue;
            }
            String first = "";
            for (int j = at + 1; j < b.paragraphs.size() && (i + 1 >= heads.size() || j < heads.get(i + 1)); j++) {
                first = firstWords(b.paragraphs.get(j));
                break;
            }
            b.chapters.add(new Chapter(at, label, first));
        }
        return b;
    }

    /** Whether a line is the author's name, maybe shorter: every word of the line is among the author's. */
    static boolean sameName(String line, String author) {
        if (author == null || author.trim().isEmpty()) {
            return false;
        }
        java.util.Set<String> words = new java.util.HashSet<String>();
        for (String w : author.toLowerCase(java.util.Locale.ROOT).split("[\\s.,]+")) {
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        String[] mine = line.toLowerCase(java.util.Locale.ROOT).split("[\\s.,]+");
        int n = 0;
        for (String w : mine) {
            if (w.isEmpty()) {
                continue;
            }
            if (!words.contains(w)) {
                return false;
            }
            n++;
        }
        return n >= 2;
    }

    /** An author's name as a cover shows it: a middle name (patronymic) left out. */
    static String shortName(String author) {
        if (author == null) {
            return "";
        }
        String[] w = author.trim().split("\\s+");
        if (w.length == 3 && w[1].toLowerCase(java.util.Locale.ROOT)
                .matches(".*(\u0432\u0438\u0447|\u0432\u043d\u0430|\u0438\u0447\u043d\u0430|\u044c\u0438\u0447)$")) {
            return w[0] + " " + w[2];
        }
        return author.trim();
    }

    /**
     * A short first line followed by a heading, or standing alone above the
     * text without sentences of its own, is the title.
     */
    private static String guessTitle(Book b, List<Integer> heads) {
        if (b.paragraphs.size() < 2) {
            return "";
        }
        String first = b.paragraphs.get(0);
        if (first.length() > SHORT_LINE || trimDot(first).contains(". ")) {
            return "";
        }
        boolean nextIsHeading = heads.contains(1);
        boolean firstIsHeading = heads.contains(0);
        if (nextIsHeading && !firstIsHeading) {
            return trimDot(first);
        }
        return "";
    }

    static String trimDot(String s) {
        String t = s.trim();
        while (t.endsWith(".")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        return t;
    }

    private static String firstWords(String s) {
        String[] w = s.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(FIRST_WORDS, w.length); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(w[i]);
        }
        return sb.toString().replaceAll("[,;:\u2014\u2013-]+$", "");
    }

    /** The chapter a paragraph belongs to, or -1 before the first one. */
    int chapterAt(int paragraph) {
        int found = -1;
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).at <= paragraph) {
                found = i;
            } else {
                break;
            }
        }
        return found;
    }

    /** A tag's inner markup from an XML string, untouched, or "". */
    static String raw(String xml, String tag) {
        Matcher m = Pattern.compile("(?is)<" + tag + "\\b[^>]*>(.*?)</" + tag + ">").matcher(xml);
        return m.find() ? m.group(1) : "";
    }

    /** A tag's inner text from an XML string, or "". */
    static String inner(String xml, String tag) {
        Matcher m = Pattern.compile("(?is)<" + tag + "\\b[^>]*>(.*?)</" + tag + ">").matcher(xml);
        return m.find() ? m.group(1).replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").trim() : "";
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
