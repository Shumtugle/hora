package com.shumtugle.hora;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Hora as a listener: a typed (later, spoken) request is matched to an intent
 * and carried out, and the answer is what the voice says about it. A command
 * is done, never only promised. What fits no intent is either a question it
 * cannot answer yet, said so plainly, or text to be spoken as it is.
 */
final class Bot {
    /** What the screen should do besides speaking the answer. */
    static final int SAY = 0;
    static final int TIME = 1;
    static final int WEATHER = 2;
    static final int SPEAK_TEXT = 3;
    static final int VOICE = 4;
    /** A question the bot has no skill for; its text is the plain "cannot yet" line. */
    static final int UNKNOWN = 5;
    /** Save the talk so far as a text file in Hora's folder. */
    static final int SAVE = 6;
    /** A question about the world, for the reference; the answer's text is the canned line for when it is out of reach. */
    static final int FACT = 7;

    static final class Answer {
        final int kind;
        final String text;
        final int voice;
        /** What was done, shown under the answer ("the book · sleep timer 20 min"); empty if nothing. */
        String trace = "";

        Answer(int kind, String text, int voice) {
            this.kind = kind;
            this.text = text;
            this.voice = voice;
        }

        Answer done(String what) {
            trace = what == null ? "" : what;
            return this;
        }
    }

    private static Intents intents;
    private static final Map<String, Integer> lastPick = new HashMap<String, Integer>();
    private static final Random random = new Random();
    private static final float SPEED_STEP = 0.05f;
    private static final float SPEED_LOW = 0.6f;
    private static final float SPEED_HIGH = 1.3f;
    private static final String DOT = " \u00b7 ";

    private Bot() {
    }

    private static synchronized Intents intents(Resources r) {
        if (intents == null) {
            intents = new Intents(r.getStringArray(R.array.bot_intents), r.getStringArray(R.array.bot_fillers),
                    r.getStringArray(R.array.bot_callnames), r.getStringArray(R.array.bot_numbers));
        }
        return intents;
    }

    /** One of the lines for a reply, not the same as last time, with its blanks filled. */
    /** The voice the current answer is in: its own lines, when it has them, come first. */
    private static int speaker;

    private static String line(Resources r, String key, String... pairs) {
        String[] all = lines(r, "bot_say_" + key + "_v" + speaker);
        if (all.length == 0) {
            all = lines(r, "bot_say_" + key);
        }
        if (all.length == 0) {
            return "";
        }
        int pick = random.nextInt(all.length);
        Integer last = lastPick.get(key);
        if (all.length > 1 && last != null && last == pick) {
            pick = (pick + 1) % all.length;
        }
        lastPick.put(key, pick);
        String s = all[pick];
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            s = s.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }
        return s;
    }

    /** A list of lines by name; empty when this language has none under that name. */
    private static String[] lines(Resources r, String name) {
        int id = r.getIdentifier(name, "array", "com.shumtugle.hora");
        if (id == 0) {
            return new String[0];
        }
        try {
            return r.getStringArray(id);
        } catch (Resources.NotFoundException e) {
            return new String[0];
        }
    }

    private static boolean startsWithAny(Resources r, int array, String q) {
        String f = Intents.fold(q == null ? "" : q).trim() + " ";
        for (String w : r.getStringArray(array)) {
            if (f.startsWith(Intents.fold(w).trim() + " ")) {
                return true;
            }
        }
        return false;
    }

    private static void player(Context c, String action, int index) {
        Intent i = BookPlayer.intent(c, action);
        if (index >= 0) {
            i.putExtra(BookPlayer.EXTRA_INDEX, index);
        }
        c.startForegroundService(i);
    }

    static String bookTitle(Library.Entry e) {
        if (e == null) {
            return "";
        }
        if (e.title != null && !e.title.isEmpty()) {
            return e.title;
        }
        String n = e.name == null ? "" : e.name;
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** Understands a request and carries it out; voice is the one that answers. */
    static Answer handle(Context c, String text, int voice) {
        speaker = voice;
        Resources r = SpeechLanguage.resources(c);
        Intents.Hit hit = intents(r).match(text);
        // "Find something about X" asks the world, not the book; so does any search with no book open.
        if (hit != null && hit.intent.equals("search")
                && (Reading.uri(c).isEmpty() || startsWithAny(r, R.array.bot_search_world, hit.slot("q")))) {
            Diag.log(c, "bot: a search for the reference");
            return new Answer(FACT, line(r, "fact"), voice);
        }
        if (hit == null) {
            hit = byKeyword(r, text);
        }
        if (hit == null && mentions(r, R.array.bot_health_words, text)) {
            Diag.log(c, "bot: a health question");
            return new Answer(SAY, line(r, "health", "sos", emergencyNumber(c, r)), voice);
        }
        if (hit == null && isFact(r, text)) {
            Diag.log(c, "bot: a question for the reference");
            return new Answer(FACT, line(r, "fact"), voice);
        }
        if (hit == null) {
            Diag.log(c, "bot: no intent");
            return looksLikeQuestion(r, text) ? new Answer(UNKNOWN, line(r, "unknown"), voice)
                    : new Answer(SPEAK_TEXT, text, voice);
        }
        Diag.log(c, "bot: " + hit.intent + " " + hit.slots);
        chapterName(c, "");
        String uri = Reading.uri(c);
        Library.Entry book = uri.isEmpty() ? null : Library.get(c, uri);
        String title = book != null ? bookTitle(book) : Reading.title(c);
        boolean bookNeeded = hit.intent.equals("read") || hit.intent.equals("pause") || hit.intent.equals("next")
                || hit.intent.equals("prev") || hit.intent.equals("where") || hit.intent.startsWith("chapter")
                || hit.intent.endsWith("_chapter") || hit.intent.equals("search") || hit.intent.startsWith("sleep");
        if (bookNeeded && uri.isEmpty()) {
            return new Answer(SAY, line(r, "no_book"), voice);
        }
        switch (hit.intent) {
            case "battery": {
                android.os.BatteryManager bm = (android.os.BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
                int pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                return new Answer(SAY, line(r, bm.isCharging() ? "battery_charging" : "battery",
                        "pct", String.valueOf(pct)), voice);
            }
            case "time":
                return new Answer(TIME, "", voice);
            case "weather":
                return new Answer(WEATHER, "", voice);
            case "date": {
                Calendar now = Calendar.getInstance();
                String[] days = r.getStringArray(R.array.bot_days);
                String[] months = r.getStringArray(R.array.months_genitive);
                String[] week = r.getStringArray(R.array.bot_weekdays);
                int d = now.get(Calendar.DAY_OF_MONTH);
                int m = now.get(Calendar.MONTH);
                int w = now.get(Calendar.DAY_OF_WEEK) - 1;
                if (days.length < d || months.length <= m || week.length <= w) {
                    return new Answer(SAY, line(r, "unknown"), voice);
                }
                return new Answer(SAY, r.getString(R.string.bot_date, week[w], days[d - 1] + " " + months[m]), voice);
            }
            case "save_talk":
                return new Answer(SAVE, "", voice);
            case "help":
            case "hello":
            case "thanks":
                return new Answer(SAY, line(r, hit.intent), voice);
            case "read":
                player(c, BookPlayer.ACTION_PLAY, -1);
                return new Answer(SAY, line(r, "read", "book", title), voice).done(title);
            case "pause":
                player(c, BookPlayer.ACTION_PAUSE, -1);
                return new Answer(SAY, line(r, "pause"), voice).done(title + DOT + r.getString(R.string.bot_trace_pause));
            case "next":
                player(c, BookPlayer.ACTION_NEXT, -1);
                return new Answer(SAY, line(r, "next"), voice);
            case "prev":
                player(c, BookPlayer.ACTION_PREV, -1);
                return new Answer(SAY, line(r, "prev"), voice);
            case "where": {
                String where = book == null ? "" : place(book);
                return new Answer(SAY, line(r, "where", "book", title, "where", where), voice);
            }
            case "chapter":
            case "next_chapter":
            case "prev_chapter":
                return chapter(c, r, hit, book, voice);
            case "search": {
                String q = hit.slot("q");
                SearchActivity.prime(q);
                c.startActivity(new Intent(c, BookActivity.class).putExtra(BookActivity.EXTRA_SEARCH, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return new Answer(SAY, line(r, "search", "q", q), voice)
                        .done(r.getString(R.string.bot_trace_search, q));
            }
            case "sleep":
            case "sleep_half":
            case "sleep_hour": {
                int minutes = hit.intent.equals("sleep_half") ? 30 : hit.intent.equals("sleep_hour") ? 60
                        : (int) Math.min(600, intents(r).number(hit.slot("n")));
                if (minutes <= 0) {
                    return new Answer(SAY, line(r, "unknown"), voice);
                }
                c.startForegroundService(BookPlayer.intent(c, BookPlayer.ACTION_SLEEP)
                        .putExtra(BookPlayer.EXTRA_MINUTES, minutes));
                return new Answer(SAY, line(r, "sleep", "minutes",
                        r.getQuantityString(R.plurals.bot_minutes, minutes, minutes)), voice)
                        .done(title + DOT + r.getString(R.string.bot_trace_sleep, minutes));
            }
            case "sleep_cancel":
                c.startForegroundService(BookPlayer.intent(c, BookPlayer.ACTION_SLEEP)
                        .putExtra(BookPlayer.EXTRA_MINUTES, 0));
                return new Answer(SAY, line(r, "sleep_cancel"), voice)
                        .done(title + DOT + r.getString(R.string.bot_trace_sleep_off));
            case "faster":
            case "slower": {
                float now = Prefs.speed(c);
                float next = now + (hit.intent.equals("faster") ? SPEED_STEP : -SPEED_STEP);
                if (next < SPEED_LOW - 0.001f || next > SPEED_HIGH + 0.001f) {
                    return new Answer(SAY, line(r, "speed_edge"), voice);
                }
                float set = Math.round(next * 100f) / 100f;
                Prefs.setSpeed(c, set);
                return new Answer(SAY, line(r, hit.intent), voice)
                        .done(r.getString(R.string.bot_trace_speed, String.format(java.util.Locale.getDefault(), "%.2f", set)));
            }
            case "voice":
            case "other_voice": {
                int v = hit.intent.equals("other_voice") ? voice % Cast.COUNT + 1 : voiceByName(c, hit.slot("q"));
                if (v <= 0) {
                    return new Answer(SAY, line(r, "no_voice"), voice);
                }
                return new Answer(VOICE, line(r, "voice", "voice", Cast.name(c, v)), v);
            }
            default:
                return new Answer(SAY, line(r, "unknown"), voice);
        }
    }

    /** "Chapter six, 24 %", or only the percent in a book without chapters. */
    static String place(Library.Entry e) {
        int ch = e.chapterAt(e.index);
        String pct = e.percent() + "\u00a0%";
        return ch < 0 ? pct : chapterNameStatic(e.chapters.get(ch).label) + ", " + pct;
    }

    private static String chapterWord = "";

    /** A heading that is a bare number ("1", "IV") gets the word for chapter before it. */
    static String chapterName(Context c, String label) {
        if (chapterWord.isEmpty()) {
            chapterWord = SpeechLanguage.resources(c).getString(R.string.chapter_numbered);
        }
        return chapterNameStatic(label);
    }

    private static String chapterNameStatic(String label) {
        String l = label == null ? "" : label.trim();
        if (!chapterWord.isEmpty() && l.matches("\\d{1,4}\\.?|[IVXLCDM]{1,7}\\.?")) {
            return String.format(chapterWord, l.replace(".", ""));
        }
        return l;
    }

    private static Answer chapter(Context c, Resources r, Intents.Hit hit, Library.Entry book, int voice) {
        List<Book.Chapter> chapters = book == null ? null : book.chapters;
        if (chapters == null || chapters.isEmpty()) {
            return new Answer(SAY, line(r, "no_chapters"), voice);
        }
        int now = book.chapterAt(book.index);
        int want;
        if (hit.intent.equals("next_chapter")) {
            want = now + 1;
        } else if (hit.intent.equals("prev_chapter")) {
            want = now - 1;
        } else {
            want = (int) intents(r).number(hit.slot("n")) - 1;
        }
        if (want < 0 || want >= chapters.size()) {
            return new Answer(SAY, line(r, "no_chapter", "n", hit.slot("n").isEmpty() ? String.valueOf(want + 1)
                    : hit.slot("n"), "count", String.valueOf(chapters.size())), voice);
        }
        Book.Chapter ch = chapters.get(want);
        player(c, Reading.playing(c) ? BookPlayer.ACTION_FROM : BookPlayer.ACTION_SEEK, ch.at);
        String named = chapterName(c, ch.label);
        return new Answer(SAY, line(r, "chapter", "chapter", named), voice).done(bookTitle(book) + DOT + named);
    }

    private static int voiceByName(Context c, String said) {
        String s = Intents.fold(said).trim();
        if (s.length() < 3) {
            return 0;
        }
        for (int v = 1; v <= Cast.COUNT; v++) {
            String name = Intents.fold(Cast.name(c, v));
            int k = Math.min(4, Math.min(name.length(), s.length()));
            if (s.regionMatches(0, name, 0, k)) {
                return v;
            }
        }
        return 0;
    }

    /** Whether the request also names a given skill by one of its words ("the weather, and what time is it"). */
    static boolean alsoAsks(Context c, String text, String intent) {
        Resources r = SpeechLanguage.resources(c);
        String t = Intents.fold(text);
        for (String row : r.getStringArray(R.array.bot_keywords)) {
            int eq = row.indexOf('=');
            if (eq <= 0 || !row.substring(0, eq).equals(intent)) {
                continue;
            }
            for (String w : row.substring(eq + 1).split("\\|")) {
                if (!w.isEmpty() && t.contains(w)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A skill named by a word in the request when no template fits: the skill, not a guess. */
    private static Intents.Hit byKeyword(Resources r, String text) {
        String t = Intents.fold(text);
        for (String row : r.getStringArray(R.array.bot_keywords)) {
            int eq = row.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            for (String w : row.substring(eq + 1).split("\\|")) {
                if (!w.isEmpty() && t.contains(w)) {
                    return new Intents.Hit(row.substring(0, eq), new java.util.HashMap<String, String>());
                }
            }
        }
        return null;
    }

    /**
     * The number for medical help where the phone is now: by the network's
     * country, else the card's, else the language setting's; 112 when unknown,
     * which mobile networks in most countries also route to help.
     */
    static String emergencyNumber(Context c, Resources r) {
        String country = "";
        try {
            android.telephony.TelephonyManager tm =
                    (android.telephony.TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                country = tm.getNetworkCountryIso();
                if (country == null || country.isEmpty()) {
                    country = tm.getSimCountryIso();
                }
            }
        } catch (RuntimeException ignored) {
            // No telephony on this device.
        }
        if (country == null || country.isEmpty()) {
            country = java.util.Locale.getDefault().getCountry();
        }
        country = country == null ? "" : country.toLowerCase(java.util.Locale.ROOT);
        for (String row : r.getStringArray(R.array.emergency_numbers)) {
            int eq = row.indexOf('=');
            if (eq > 0 && row.substring(0, eq).equals(country)) {
                return row.substring(eq + 1);
            }
        }
        return "112";
    }

    private static boolean mentions(Resources r, int array, String text) {
        String t = " " + Intents.fold(text) + " ";
        for (String w : r.getStringArray(array)) {
            if (!w.isEmpty() && t.contains(Intents.fold(w))) {
                return true;
            }
        }
        return false;
    }

    /** A question about the world (who, when, where, how old...), for the reference and not the model. */
    private static boolean isFact(Resources r, String text) {
        String t = Intents.fold(text).trim();
        // About the voice itself: that is talk.
        String padded = " " + t.replaceAll("[^\\p{L}\\p{N}]+", " ") + " ";
        for (String w : r.getStringArray(R.array.bot_self_words)) {
            if (padded.contains(" " + Intents.fold(w) + " ")) {
                return false;
            }
        }
        for (String w : t.split("[^\\p{L}]+")) {
            for (String you : r.getString(R.string.bot_fact_not).split(" ")) {
                if (w.equals(you)) {
                    return false;
                }
            }
        }
        // Leading words that soften a question ("say, do you happen to know") are set aside.
        t = t.replaceAll("^\\p{L}+,\\s*", "");
        boolean cut = true;
        while (cut) {
            cut = false;
            for (String lead : r.getStringArray(R.array.bot_lead_words)) {
                String l = Intents.fold(lead);
                if (t.startsWith(l + " ") || t.startsWith(l + ", ")) {
                    t = t.substring(l.length()).replaceFirst("^,?\\s*", "");
                    cut = true;
                }
            }
        }
        for (String s : r.getStringArray(R.array.bot_fact_starts)) {
            if (t.startsWith(s) || t.contains(" " + s)) {
                return true;
            }
        }
        for (String w : r.getStringArray(R.array.bot_fact_words)) {
            if (t.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeQuestion(Resources r, String text) {
        String t = text.trim();
        if (t.endsWith("?")) {
            return true;
        }
        String first = Intents.fold(t.split("[\\s,]+")[0]);
        for (String w : r.getString(R.string.bot_question_words).split(" ")) {
            if (first.equals(w)) {
                return true;
            }
        }
        return false;
    }
}
