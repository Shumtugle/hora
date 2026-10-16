package com.shumtugle.hora;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a sentence asks for. Each intent is written as phrase templates in the
 * language data: words, alternatives "(read|go on)", optional words "[me]",
 * and slots "{n}" (a number, in digits or words) or "{q}" (any words). The
 * whole sentence must match a template, so a line from a book that merely
 * contains a command word is not taken for a command. Nothing is guessed:
 * a sentence that fits no template is no command.
 */
final class Intents {

    /** A recognised request: the intent and its slots as written. */
    static final class Hit {
        final String intent;
        final Map<String, String> slots;
        /** The name the request called a voice by, folded; empty when it named none. */
        String called = "";

        Hit(String intent, Map<String, String> slots) {
            this.intent = intent;
            this.slots = slots;
        }

        String slot(String name) {
            String v = slots.get(name);
            return v == null ? "" : v;
        }
    }

    private static final Pattern SLOT = Pattern.compile("\\{([a-w]+)\\}");
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}\\- ]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final List<String> names = new ArrayList<String>();
    private final List<Pattern> patterns = new ArrayList<Pattern>();
    private final List<List<String>> slotNames = new ArrayList<List<String>>();
    private final List<String> fillers = new ArrayList<String>();
    private final List<String> callnames = new ArrayList<String>();
    /** Names the person gave the voices, which a request may open with as well. */
    private volatile List<String> ownNames = new ArrayList<String>();
    /** Number words by stem, longest stems first: "fifteen" before "five". */
    private final List<String[]> numberStems = new ArrayList<String[]>();

    /**
     * @param rows "intent=template" lines
     * @param fillers words dropped at either end of a request (please, well)
     * @param callnames names a voice may be called by: dropped at the start, or anywhere when the
     *        sentence fits no template with it ("read, Nix", "read Nix, you yourself")
     * @param numbers "value=stem|stem" lines for numbers said in words
     */
    Intents(String[] rows, String[] fillers, String[] callnames, String[] numbers) {
        for (String n : callnames) {
            this.callnames.add(fold(n));
        }
        for (String row : rows) {
            int eq = row.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            List<String> slots = new ArrayList<String>();
            Pattern p = compile(row.substring(eq + 1).trim(), slots);
            if (p != null) {
                names.add(row.substring(0, eq).trim());
                patterns.add(p);
                slotNames.add(slots);
            }
        }
        for (String f : fillers) {
            this.fillers.add(fold(f));
        }
        for (String row : numbers) {
            int eq = row.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            for (String stem : row.substring(eq + 1).split("\\|")) {
                if (!stem.isEmpty()) {
                    numberStems.add(new String[] {fold(stem), row.substring(0, eq)});
                }
            }
        }
        java.util.Collections.sort(numberStems, new java.util.Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return b[0].length() - a[0].length();
            }
        });
    }

    static String fold(String s) {
        return s.toLowerCase(Locale.ROOT).replace('\u0451', '\u0435');
    }

    /** A template into a whole-sentence pattern; spaces may be absent around optional parts. */
    private static Pattern compile(String template, List<String> slots) {
        StringBuilder re = new StringBuilder("^");
        String t = fold(template);
        int i = 0;
        while (i < t.length()) {
            char c = t.charAt(i);
            if (c == '{') {
                Matcher m = SLOT.matcher(t);
                if (!m.find(i) || m.start() != i) {
                    return null;
                }
                // A slot may stand in several alternatives; each gets its own group, read back by its base name.
                String name = m.group(1);
                String group = name + "x" + slots.size();
                slots.add(group);
                re.append("(?<").append(group).append(">.+?)");
                i = m.end();
                continue;
            }
            if (c == '[') {
                re.append("(?:");
            } else if (c == ']') {
                re.append(")?");
            } else if (c == '(') {
                re.append("(?:");
            } else if (c == ')') {
                re.append(")");
            } else if (c == '|') {
                re.append("|");
            } else if (c == ' ') {
                re.append("\\s*");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
            i++;
        }
        re.append("$");
        try {
            return Pattern.compile(re.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Takes the names the person gave the voices, so "Name, what time is it" is understood. */
    void ownNames(List<String> names) {
        List<String> folded = new ArrayList<String>();
        for (String n : names) {
            String f = SPACES.matcher(NOT_WORD.matcher(fold(n)).replaceAll(" ")).replaceAll(" ").trim();
            if (!f.isEmpty()) {
                folded.add(f);
            }
        }
        ownNames = folded;
    }

    /** The sentence as templates see it: lower case, yo as ye, no punctuation, no fillers at the ends. */
    String clean(String text) {
        return clean(text, false)[0];
    }

    /**
     * The sentence cleaned, and the name it called a voice by. A name in front is always dropped,
     * and is only an address ("Hora, go on"), never a choice of voice. With anywhere set, the name
     * that comes first among the words is dropped wherever it stands, and that one is the choice.
     */
    private String[] clean(String text, boolean anywhere) {
        String t = fold(text);
        t = NOT_WORD.matcher(t).replaceAll(" ");
        t = SPACES.matcher(t).replaceAll(" ").trim();
        List<String> names = new ArrayList<String>(ownNames);
        names.addAll(callnames);
        String called = "";
        // Fillers may stand before the address ("well, Hora, go on"): both come off the front, in turns.
        boolean front = true;
        while (front && !t.isEmpty()) {
            front = false;
            for (String f : fillers) {
                if (t.startsWith(f + " ")) {
                    t = t.substring(f.length() + 1).trim();
                    front = true;
                }
            }
            for (String n : names) {
                if (t.startsWith(n + " ")) {
                    t = t.substring(n.length() + 1).trim();
                    front = true;
                    break;
                }
            }
        }
        if (anywhere) {
            String padded = " " + t + " ";
            int first = -1;
            for (String n : names) {
                int at = padded.indexOf(" " + n + " ");
                // The earliest name wins; at the same place, the longer one ("Tea Rose" over "Tea").
                if (at >= 0 && (first < 0 || at < first || (at == first && n.length() > called.length()))) {
                    first = at;
                    called = n;
                }
            }
            if (first >= 0) {
                t = (padded.substring(0, first) + " " + padded.substring(first + called.length() + 2)).trim();
                t = SPACES.matcher(t).replaceAll(" ");
            }
        }
        boolean changed = true;
        while (changed && !t.isEmpty()) {
            changed = false;
            for (String f : fillers) {
                if (t.equals(f)) {
                    return new String[] {"", called};
                }
                if (t.startsWith(f + " ")) {
                    t = t.substring(f.length() + 1).trim();
                    changed = true;
                }
                if (t.endsWith(" " + f)) {
                    t = t.substring(0, t.length() - f.length() - 1).trim();
                    changed = true;
                }
            }
        }
        return new String[] {t, called};
    }

    /** The sentence with any voice's name taken out of it, for telling a command from talk. */
    String bare(String text) {
        return clean(text, true)[0];
    }

    /**
     * The first intent whose template fits the whole sentence, or null. Number slots must read as
     * numbers. The sentence is tried as said first, so "let Nix read" keeps its name in the slot;
     * only then without a voice's name standing anywhere in it.
     */
    Hit match(String text) {
        String[] plain = clean(text, false);
        Hit hit = fit(plain[0]);
        if (hit != null) {
            return hit;
        }
        String[] loose = clean(text, true);
        if (loose[0].equals(plain[0])) {
            return null;
        }
        hit = fit(loose[0]);
        if (hit != null) {
            hit.called = loose[1];
        }
        return hit;
    }

    private Hit fit(String t) {
        if (t.isEmpty()) {
            return null;
        }
        for (int k = 0; k < patterns.size(); k++) {
            Matcher m = patterns.get(k).matcher(t);
            if (!m.matches()) {
                continue;
            }
            Map<String, String> slots = new HashMap<String, String>();
            boolean ok = true;
            for (String g : slotNames.get(k)) {
                String raw = m.group(g);
                if (raw == null) {
                    continue;
                }
                String base = g.substring(0, g.lastIndexOf('x'));
                String v = raw.trim();
                if (base.equals("n") && number(v) < 0) {
                    ok = false;
                    break;
                }
                slots.put(base, v);
            }
            if (ok) {
                return new Hit(names.get(k), slots);
            }
        }
        return null;
    }

    /** A number said in digits or words, ordinals and compounds too; -1 if it is not one. */
    long number(String words) {
        String t = fold(words).trim();
        if (t.matches("\\d{1,6}")) {
            return Long.parseLong(t);
        }
        long sum = 0;
        boolean any = false;
        for (String w : t.split(" ")) {
            if (w.isEmpty()) {
                continue;
            }
            long v = -1;
            if (w.matches("\\d{1,6}")) {
                v = Long.parseLong(w);
            } else {
                for (String[] s : numberStems) {
                    if (w.startsWith(s[0])) {
                        v = Long.parseLong(s[1]);
                        break;
                    }
                }
            }
            if (v < 0) {
                return -1;
            }
            sum += v;
            any = true;
        }
        return any ? sum : -1;
    }
}
