package com.shumtugle.hora;

import android.content.Context;
import android.content.res.Resources;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * What a voice says when its line on the main screen is touched: its name and
 * one thing that is true right now (the time, the date, where the book is,
 * the charge, the weather fetched lately, what this voice reads, quiet hours).
 * The variety comes from the facts, which really change, and not from words
 * of mood, which would only pretend to. No feelings, no closeness, no talk
 * about its own manner; the character is in the length of the sentence.
 * A fact that is not known is not said, and the same kind never comes twice
 * in a row. Touches in a row cool it down: the fourth drops the last word,
 * and from the fifth on it is the same flat line every time. A pause ends the game.
 */
final class TapTalk {
    private static final String ASSET = "talk/tap.txt";
    /** Touches further apart than this start the game over. */
    private static final long GAME_GAP_MS = 25000;
    private static final int DROP_INVITE = 4;
    private static final int FLAT = 5;
    /** One touch in this many ends with the voice's short word. */
    private static final int INVITE_EVERY = 3;
    /** The flat line is spoken a little slower than the voice's own pace. */
    static final float FLAT_PACE = 0.9f;

    private static final String[] KINDS = {
        "time", "date", "book", "battery", "charging", "weather", "role_book", "role_news", "quiet"
    };

    private static Map<String, List<String>> lines;
    private static final Map<String, Integer> lastPick = new HashMap<String, Integer>();
    private static final Map<Integer, String> lastKind = new HashMap<Integer, String>();
    private static final Random random = new Random();
    private static int count;
    private static int countVoice;
    private static long lastTap;

    /** One spoken reply: the words and whether it is the flat one. */
    static final class Reply {
        final String text;
        final boolean flat;

        Reply(String text, boolean flat) {
            this.text = text;
            this.flat = flat;
        }
    }

    private TapTalk() {
    }

    /** The reply to the next touch on a voice's line, or null when there are no lines. */
    static synchronized Reply next(Context c, int voice) {
        long now = SystemClock.elapsedRealtime();
        if (voice != countVoice || now - lastTap > GAME_GAP_MS) {
            count = 0;
        }
        countVoice = voice;
        lastTap = now;
        count++;
        String lang = SpeechLanguage.CODE;
        if (count >= FLAT) {
            String flat = pick(c, lang, "flat", voice);
            return flat == null ? null : new Reply(name(flat, c, voice), true);
        }
        StringBuilder out = new StringBuilder(Cast.spokenName(c, voice)).append('.');
        append(out, fact(c, lang, voice));
        if (count < DROP_INVITE && random.nextInt(INVITE_EVERY) == 0) {
            append(out, pick(c, lang, "invite", voice));
        }
        return new Reply(out.toString(), false);
    }

    /** Forgets the game, as when another voice steps forward. */
    static synchronized void reset() {
        count = 0;
    }

    /** One true thing about now, in this voice's words; null if nothing is known. */
    private static String fact(Context c, String lang, int voice) {
        Map<String, String> known = known(c, voice);
        List<String> kinds = new ArrayList<String>();
        String last = lastKind.get(voice);
        for (String k : KINDS) {
            if (known.containsKey(k) && !k.equals(last)) {
                kinds.add(k);
            }
        }
        if (kinds.isEmpty()) {
            return null;
        }
        String kind = kinds.get(random.nextInt(kinds.size()));
        lastKind.put(voice, kind);
        String s = pick(c, lang, kind, voice);
        if (s == null) {
            return null;
        }
        for (Map.Entry<String, String> e : known.entrySet()) {
            if (!e.getKey().startsWith("{")) {
                continue;
            }
            s = s.replace(e.getKey(), e.getValue());
        }
        return capital(s);
    }

    /**
     * What can be said right now. Kinds that may be spoken map to an empty
     * value; the words that go into the lines map from their {marks}.
     */
    private static Map<String, String> known(Context c, int voice) {
        Map<String, String> m = new HashMap<String, String>();
        Resources r = SpeechLanguage.resources(c);
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int minute = now.get(Calendar.MINUTE);
        try {
            m.put("{time}", TimePhrase.bare(r, hour, minute));
            m.put("time", "");
        } catch (RuntimeException e) {
            Diag.log(c, "tap: no words for the time", e);
        }
        String[] days = r.getStringArray(R.array.bot_days);
        String[] months = r.getStringArray(R.array.months_genitive);
        String[] week = r.getStringArray(R.array.bot_weekdays);
        int d = now.get(Calendar.DAY_OF_MONTH);
        int mo = now.get(Calendar.MONTH);
        int w = now.get(Calendar.DAY_OF_WEEK) - 1;
        if (days.length >= d && months.length > mo && week.length > w) {
            m.put("{weekday}", week[w]);
            m.put("{day}", days[d - 1]);
            m.put("{month}", months[mo]);
            m.put("date", "");
        }
        String uri = Reading.uri(c);
        Library.Entry book = uri.isEmpty() ? null : Library.get(c, uri);
        if (book != null) {
            Bot.chapterName(c, "");
            m.put("{book}", Bot.bookTitle(book));
            m.put("{place}", Bot.place(book));
            m.put("book", "");
        }
        android.os.BatteryManager bm = (android.os.BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            int pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (pct > 0 && pct <= 100) {
                m.put("{pct}", String.valueOf(pct));
                m.put(bm.isCharging() ? "charging" : "battery", "");
            }
        }
        String[] weather = Weather.brief(c);
        if (weather != null) {
            m.put("{weather}", weather[0]);
            m.put("{degrees}", weather[1]);
            m.put("weather", "");
        }
        if (Prefs.role(c, Cast.NARRATOR) == voice) {
            m.put("role_book", "");
        }
        boolean news = Herald.granted(c) && !Prefs.heraldApps(c).isEmpty();
        if (news && Prefs.role(c, Cast.HERALD) == voice) {
            m.put("role_news", "");
        }
        if (news && Prefs.quietAtShared(c, hour * 60 + minute)) {
            m.put("quiet", "");
        }
        return m;
    }

    private static String name(String line, Context c, int voice) {
        return line.replace("{name}", Cast.spokenName(c, voice));
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static void append(StringBuilder out, String part) {
        if (part == null || part.isEmpty()) {
            return;
        }
        if (out.length() > 0) {
            out.append(' ');
        }
        out.append(part);
    }

    /**
     * A line for a kind: this voice's own if it has one, otherwise the common
     * one; a different pick from last time when there is a choice.
     */
    private static String pick(Context c, String lang, String cell, int voice) {
        String key = lang + "." + cell + "." + voice;
        List<String> own = load(c).get(key);
        if (own == null || own.isEmpty()) {
            key = lang + "." + cell;
            own = load(c).get(key);
        }
        if (own == null || own.isEmpty()) {
            return null;
        }
        Integer last = lastPick.get(key);
        int i = random.nextInt(own.size());
        if (own.size() > 1 && last != null && i == last) {
            i = (i + 1 + random.nextInt(own.size() - 1)) % own.size();
        }
        lastPick.put(key, i);
        return own.get(i);
    }

    private static Map<String, List<String>> load(Context c) {
        if (lines != null) {
            return lines;
        }
        Map<String, List<String>> map = new HashMap<String, List<String>>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getAssets().open(ASSET), "UTF-8"))) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                int bar = line.indexOf('|');
                if (line.isEmpty() || line.startsWith("#") || bar <= 0) {
                    continue;
                }
                String key = line.substring(0, bar).trim();
                List<String> list = map.get(key);
                if (list == null) {
                    list = new ArrayList<String>();
                    map.put(key, list);
                }
                list.add(line.substring(bar + 1).trim());
            }
        } catch (IOException e) {
            Diag.log(c, "tap: lines missing", e);
        }
        lines = map;
        return lines;
    }
}
