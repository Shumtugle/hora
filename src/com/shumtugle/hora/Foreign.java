package com.shumtugle.hora;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds English inside a native text: a whole sentence, or a run of at least
 * four Latin words, as when a book quotes a line. Such a span is read by the
 * English model in the same voice; anything shorter (a name, a brand, a word)
 * stays with the native voice, spelled the native way, which sounds better
 * than a voice switching for one word, and a run needs two common English words, so a title like a newspaper name stays native too. German, French and the like are not
 * English and stay native too.
 *
 * Spans are marked in the text with two private characters, so the marks
 * survive the native rewriting around them and travel with the chunk.
 */
final class Foreign {
    /** Opens and closes an English span. */
    static final char OPEN = '';
    static final char CLOSE = '';

    private static final int MIN_WORDS = 4;

    /** Latin words, digits, spaces and the punctuation a quoted sentence carries; no native letters. */
    private static final Pattern RUN = Pattern.compile(
            "[A-Za-z][A-Za-z'’\\-]*(?:[\\s,.;:!?\"'’“”()\\-–—]+[A-Za-z0-9][A-Za-z0-9'’\\-]*)+");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z'’\\-]*");
    /** Letters English does not use: their presence means another language. */
    private static final Pattern NOT_ENGLISH = Pattern.compile("[À-ÿŒœ]");
    private static final Set<String> ENGLISH_WORDS = new HashSet<String>(Arrays.asList(
            "the", "a", "an", "and", "of", "to", "is", "in", "it", "that", "you", "i", "for", "with", "on",
            "be", "not", "are", "was", "this", "my", "your", "we", "he", "she", "they", "what", "all", "but",
            "have", "has", "do", "at", "as", "by", "from", "or", "if", "me", "so", "no", "will", "can"));
    private static final Set<String> OTHER_WORDS = new HashSet<String>(Arrays.asList(
            "der", "die", "das", "und", "ist", "nicht", "ich", "ein", "eine", "le", "la", "les", "et", "est",
            "une", "des", "du", "qui", "pas", "je", "vous", "il", "elle", "est", "sono", "che", "non", "el", "los"));

    private Foreign() {
    }

    /** The text with every English span wrapped in OPEN and CLOSE; unchanged when there is none. */
    static String mark(String text) {
        if (text == null || text.indexOf(OPEN) >= 0) {
            return text;
        }
        Matcher m = RUN.matcher(text);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            String run = trimEnd(m.group());
            if (!english(run)) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder();
            }
            int start = m.start();
            out.append(text, last, start).append(OPEN).append(run).append(CLOSE);
            last = start + run.length();
        }
        if (out == null) {
            return text;
        }
        out.append(text.substring(last));
        return out.toString();
    }

    /** The whole text as one English span, for a request made in English. */
    static String whole(String text) {
        return text == null || text.isEmpty() ? text : OPEN + text + CLOSE;
    }

    /** Whether a run of Latin words reads as English: long enough, English words in it, no other language's. */
    static boolean english(String run) {
        if (NOT_ENGLISH.matcher(run).find()) {
            return false;
        }
        Matcher w = WORD.matcher(run);
        int words = 0;
        int ours = 0;
        int theirs = 0;
        while (w.find()) {
            String x = w.group().toLowerCase(Locale.ROOT);
            words++;
            if (ENGLISH_WORDS.contains(x)) {
                ours++;
            }
            if (OTHER_WORDS.contains(x)) {
                theirs++;
            }
        }
        return words >= MIN_WORDS && ours >= 2 && ours > theirs;
    }

    /** A piece of a chunk and whether it is English. */
    static final class Piece {
        final String text;
        final boolean english;

        Piece(String text, boolean english) {
            this.text = text;
            this.english = english;
        }
    }

    /**
     * Cuts a chunk at the marks. A chunk may hold only one end of a span, when
     * a long quote was cut between chunks: a lone CLOSE means the chunk began
     * inside English, a lone OPEN that English runs to its end.
     */
    static List<Piece> pieces(String chunk) {
        List<Piece> out = new ArrayList<Piece>();
        int open = chunk.indexOf(OPEN);
        int close = chunk.indexOf(CLOSE);
        boolean english = close >= 0 && (open < 0 || close < open);
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (c == OPEN || c == CLOSE) {
                add(out, cur.toString(), english);
                cur.setLength(0);
                english = c == OPEN;
            } else {
                cur.append(c);
            }
        }
        add(out, cur.toString(), english);
        return out;
    }

    /**
     * Makes one piece of a cut text whole on its own: a piece that begins
     * inside an English span gets an opening mark, one that ends inside it a
     * closing mark. inside[0] carries the state from piece to piece.
     */
    static String seal(String piece, boolean[] inside) {
        boolean in = inside[0];
        for (int i = 0; i < piece.length(); i++) {
            char c = piece.charAt(i);
            if (c == OPEN) {
                in = true;
            } else if (c == CLOSE) {
                in = false;
            }
        }
        String out = piece;
        if (inside[0] && piece.indexOf(CLOSE) != 0) {
            out = OPEN + out;
        }
        if (in) {
            out = out + CLOSE;
        }
        inside[0] = in;
        return out;
    }

    static boolean has(String s) {
        return s != null && (s.indexOf(OPEN) >= 0 || s.indexOf(CLOSE) >= 0);
    }

    /** The text without marks, for anything that only shows or measures it. */
    static String plain(String s) {
        return s == null ? null : s.replace(String.valueOf(OPEN), "").replace(String.valueOf(CLOSE), "");
    }

    private static void add(List<Piece> out, String s, boolean english) {
        String t = s.trim();
        if (!t.isEmpty() && t.matches(".*[\\p{L}\\p{N}].*")) {
            out.add(new Piece(t, english));
        } else if (!t.isEmpty() && !out.isEmpty()) {
            // Bare punctuation between spans belongs to the piece before it.
            Piece p = out.remove(out.size() - 1);
            out.add(new Piece(p.text + t, p.english));
        }
    }

    private static String trimEnd(String run) {
        int end = run.length();
        while (end > 0 && " \t\n,;:-–—\"'’“”(".indexOf(run.charAt(end - 1)) >= 0) {
            end--;
        }
        return run.substring(0, end);
    }
}
