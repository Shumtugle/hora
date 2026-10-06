package com.shumtugle.hora;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How a reader breathes: where the text is cut into breath groups and how
 * long the pause after each one is. The model reads every group afresh, so
 * each starts a little higher and settles towards its end, the arc a live
 * reader draws on one breath. The pauses follow measured readers: a calm
 * reading stops at most clause marks, an announcer at every mark and between
 * word groups, a stage reading every few words and longer.
 */
final class Breath {
    static final int NONE = 0;
    static final int READING = 1;
    static final int ANNOUNCER = 2;
    static final int STAGE = 3;

    /**
     * Pause after a group inside a sentence, then after a sentence, in ms, by manner.
     * Reading is measured against good readers of the same passage: a comma takes about
     * half of what a full stop takes (0.3 s against 0.65 s heard). The voice adds about a
     * tenth of a second of its own at each join, so the comma here is set below what is heard.
     */
    private static final int[][] PAUSE = {{0, 0}, {220, 600}, {650, 900}, {830, 1100}};
    /**
     * A reading group gathers clauses until it has at least this many
     * syllables. Shorter pieces lose the context the model needs for natural
     * reduction and stress, and come out over-careful, like a learner's.
     */
    private static final int READING_GROUP = 14;
    /** Word-group sizes for announcer and stage, in syllables. */
    private static final int ANNOUNCER_GROUP = 12;
    private static final int STAGE_GROUP = 9;
    /** Beyond the group size by this much, a group ends at the next word whatever follows. */
    private static final int OVERRUN = 5;
    /** A tail this short joins the group before it. */
    private static final int SHORT_TAIL = 5;

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?\u2026])\\s+");
    private static final Pattern CLAUSE = Pattern.compile("(?<=[,;:])\\s+|\\s+(?=[\u2014\u2013]\\s)");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    /** One breath group and the silence after it. */
    static final class Group {
        final String text;
        final int pauseMs;

        Group(String text, int pauseMs) {
            this.text = text;
            this.pauseMs = pauseMs;
        }
    }

    private final String vowels;
    private final Set<String> joiners;

    /**
     * vowels: the letters that make syllables; joiners: short words that open
     * a new word group (prepositions, conjunctions), lower case.
     */
    Breath(String vowels, String[] joiners) {
        this.vowels = vowels.toLowerCase(Locale.ROOT) + vowels.toUpperCase(Locale.ROOT);
        this.joiners = new HashSet<String>();
        for (String j : joiners) {
            this.joiners.add(j.trim());
        }
    }

    static boolean known(int manner) {
        return manner >= NONE && manner <= STAGE;
    }

    /** The text as groups with their pauses; with NONE, one group and no pause. */
    List<Group> split(String text, int manner) {
        List<Group> out = new ArrayList<Group>();
        String t = text.trim();
        if (t.isEmpty()) {
            return out;
        }
        if (!known(manner) || manner == NONE) {
            out.add(new Group(t, 0));
            return out;
        }
        String[] sentences = SENTENCE_END.split(t);
        for (String s : sentences) {
            List<String> groups = manner == READING ? byClauses(s) : byWords(s,
                    manner == STAGE ? STAGE_GROUP : ANNOUNCER_GROUP);
            for (int i = 0; i < groups.size(); i++) {
                boolean last = i == groups.size() - 1;
                out.add(new Group(groups.get(i), PAUSE[manner][last ? 1 : 0]));
            }
        }
        return out;
    }

    private List<String> byClauses(String sentence) {
        List<String> out = new ArrayList<String>();
        String cur = "";
        for (String clause : CLAUSE.split(sentence.trim())) {
            cur = cur.isEmpty() ? clause : cur + " " + clause;
            if (syllables(cur) >= READING_GROUP) {
                out.add(cur);
                cur = "";
            }
        }
        return closeTail(out, cur, READING_GROUP / 2);
    }

    private List<String> byWords(String sentence, int size) {
        List<String> out = new ArrayList<String>();
        String[] words = SPACE.split(sentence.trim());
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (cur.length() > 0) {
                cur.append(' ');
            }
            cur.append(words[i]);
            String w = words[i];
            int n = syllables(cur.toString());
            boolean mark = !w.isEmpty() && ",;:".indexOf(w.charAt(w.length() - 1)) >= 0;
            String next = i + 1 < words.length ? strip(words[i + 1]) : "";
            if ((mark && n >= SHORT_TAIL) || (n >= size && joiners.contains(next)) || n >= size + OVERRUN) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        return closeTail(out, cur.toString(), SHORT_TAIL);
    }

    private List<String> closeTail(List<String> out, String tail, int shortest) {
        if (!tail.isEmpty()) {
            if (!out.isEmpty() && syllables(tail) < shortest) {
                out.set(out.size() - 1, out.get(out.size() - 1) + " " + tail);
            } else {
                out.add(tail);
            }
        }
        return out;
    }

    private static String strip(String w) {
        int a = 0;
        int b = w.length();
        while (a < b && !Character.isLetter(w.charAt(a))) {
            a++;
        }
        while (b > a && !Character.isLetter(w.charAt(b - 1))) {
            b--;
        }
        return w.substring(a, b).toLowerCase(Locale.ROOT);
    }

    int syllables(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (vowels.indexOf(s.charAt(i)) >= 0) {
                n++;
            }
        }
        return n;
    }
}
