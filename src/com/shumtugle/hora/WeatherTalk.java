package com.shumtugle.hora;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Tells the weather the way a voice would, not the way a chart does: a few
 * facts that matter today, picked with some chance, said with lines that
 * change from time to time and carry the manner of whoever is speaking. All
 * words come from a template file; this class only decides what to say.
 */
final class WeatherTalk {
    /** What is known about the sky. Temperatures in degrees, wind in metres a second. */
    static final class Facts {
        int temp;
        int feels;
        int code;
        int wind;
        int high;
        int low;
        int tomorrowHigh;
        int tomorrowCode = -1;
        /** Hour of the day now, 0..23. */
        int hour;
        /** Minutes after midnight at the place. */
        int minutes;
        /** Whether today's sunset is behind us. */
        boolean duskPassed;
        /** Weather codes for the coming hours, the first being the next hour. */
        int[] next = new int[0];
        String dusk = "";
        String dawn = "";
        /** Whether the sun is up; night lines replace day lines when it is not. */
        boolean isDay = true;
        /** Hours until rain or snow starts or stops, found while choosing what to say. */
        int hourAhead;
    }

    /** Remembers the last line used for each key, so a line does not come twice in a row. */
    interface Memory {
        int last(String key);

        void used(String key, int index);
    }

    private final Map<String, List<String>> lines = new HashMap<String, List<String>>();

    WeatherTalk(InputStream templates) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(templates, "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#") || line.indexOf('|') < 0) {
                    continue;
                }
                int bar = line.indexOf('|');
                String key = line.substring(0, bar).trim();
                List<String> list = lines.get(key);
                if (list == null) {
                    list = new ArrayList<String>();
                    lines.put(key, list);
                }
                list.add(line.substring(bar + 1).trim());
            }
        } finally {
            r.close();
        }
    }

    /** The whole report for one voice. */
    String say(Facts f, int voice, Random rnd, Memory memory) {
        String sky = sky(f.code);
        List<String> parts = new ArrayList<String>();
        if (rnd.nextFloat() < 0.45f) {
            add(parts, "open." + voice, f, rnd, memory);
        }
        add(parts, timed("now." + sky, f), f, rnd, memory);

        // What is worth saying today, each with a weight; one or two are told.
        List<String> keys = new ArrayList<String>();
        List<Float> weights = new ArrayList<Float>();
        if (f.feels - f.temp <= -4 || f.feels - f.temp >= 4) {
            keys.add("feels");
            weights.add((float) Math.abs(f.feels - f.temp));
        }
        if (f.wind >= 8) {
            keys.add("wind");
            weights.add(f.wind / 2f);
        }
        String change = change(f);
        if (change != null) {
            keys.add(change);
            weights.add(8f);
        }
        if (f.tomorrowCode >= 0) {
            String t = tomorrow(f);
            if (t != null) {
                keys.add(t);
                weights.add(t.endsWith("warmer") || t.endsWith("colder")
                        ? (float) Math.abs(f.tomorrowHigh - f.high) : 3f);
            }
        }
        if (f.hour < 14 && f.high - f.low >= 5) {
            keys.add("range");
            weights.add(2.5f);
        }
        if (f.hour >= 15 && !f.duskPassed && !f.dusk.isEmpty()) {
            keys.add("dusk");
            weights.add(2f);
        } else if ((f.duskPassed || f.hour < 6) && !f.dawn.isEmpty()) {
            keys.add("dawn");
            weights.add(1.5f);
        }
        int tell = keys.size() <= 1 || rnd.nextFloat() < 0.55f ? 1 : 2;
        List<String> chosen = new ArrayList<String>();
        for (int i = 0; i < tell && !keys.isEmpty(); i++) {
            int k = weighted(weights, rnd);
            chosen.add(keys.remove(k));
            weights.remove(k);
        }
        // What is now comes before what will be.
        java.util.Collections.sort(chosen, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return rank(a) - rank(b);
            }
        });
        for (String k : chosen) {
            add(parts, k, f, rnd, memory);
        }

        // Now and then, a line from the story, when today's sky is like the one there.
        String story = f.wind >= 8 && lines.containsKey("story.wind") ? "story.wind" : "story." + sky;
        if (lines.containsKey(story) && rnd.nextFloat() < 0.15f) {
            String quote = pick(story, rnd, memory);
            String end = quote.endsWith(".") ? "." : "";
            if (!end.isEmpty()) {
                quote = quote.substring(0, quote.length() - 1);
            }
            // The quoted line keeps its own capital; the full stop goes after the closing mark.
            parts.add(pick("story.intro", rnd, memory) + " \u00ab" + quote + "\u00bb" + end);
        }
        if (rnd.nextFloat() < 0.7f) {
            add(parts, timed("close." + voice + "." + mood(f, sky), f), f, rnd, memory);
        }
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(p);
        }
        return out.toString();
    }

    /**
     * The key said at this hour: a late-night variant after eleven, a night
     * variant while the sun is down (for closing words, from five in the
     * evening), or the plain one when neither exists.
     */
    private String timed(String key, Facts f) {
        boolean late = f.hour >= 23 || f.hour < 5;
        if (late && lines.containsKey(key + ".late")) {
            return key + ".late";
        }
        // Closing words turn to the evening a little before dark; the sky itself turns at sunset.
        boolean evening = f.hour >= 17 && key.startsWith("close.");
        if ((!f.isDay || evening) && lines.containsKey(key + ".night")) {
            return key + ".night";
        }
        return key;
    }

    private static int rank(String key) {
        String[] order = {"feels", "wind", "soon", "range", "dusk", "dawn", "tomorrow"};
        for (int i = 0; i < order.length; i++) {
            if (key.startsWith(order[i])) {
                return i;
            }
        }
        return order.length;
    }

    /** Any one line for a key, or empty. */
    String any(String key, Random rnd) {
        List<String> list = lines.get(key);
        return list == null || list.isEmpty() ? "" : list.get(rnd.nextInt(list.size()));
    }

    /** Kind of sky for a weather code of the common numeric scale. */
    static String sky(int code) {
        if (code == 0) {
            return "clear";
        }
        if (code == 1 || code == 2) {
            return "partly";
        }
        if (code == 3) {
            return "overcast";
        }
        if (code >= 45 && code <= 48) {
            return "fog";
        }
        if (code >= 51 && code <= 57) {
            return "drizzle";
        }
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
            return "snow";
        }
        if (code >= 95) {
            return "storm";
        }
        return "rain";
    }

    private static boolean wet(String sky) {
        return sky.equals("rain") || sky.equals("drizzle") || sky.equals("storm");
    }

    /** Whether rain or snow starts, or stops, in the coming hours. */
    private String change(Facts f) {
        boolean wetNow = wet(sky(f.code)) || sky(f.code).equals("snow");
        for (int h = 0; h < f.next.length && h < 10; h++) {
            String s = sky(f.next[h]);
            boolean wetThen = wet(s) || s.equals("snow");
            if (wetThen != wetNow) {
                f.hourAhead = h + 1;
                if (wetNow) {
                    return sky(f.code).equals("snow") ? "soon.snowend" : "soon.dry";
                }
                return s.equals("snow") ? "soon.snow" : "soon.rain";
            }
        }
        return null;
    }

    private static String tomorrow(Facts f) {
        int d = f.tomorrowHigh - f.high;
        if (d >= 4) {
            return "tomorrow.warmer";
        }
        if (d <= -4) {
            return "tomorrow.colder";
        }
        String s = sky(f.tomorrowCode);
        if (wet(s)) {
            return "tomorrow.rain";
        }
        if (s.equals("snow")) {
            return "tomorrow.snow";
        }
        if (s.equals("clear")) {
            return "tomorrow.clear";
        }
        return null;
    }

    private static String mood(Facts f, String sky) {
        if (wet(sky)) {
            return "wet";
        }
        if (f.feels <= -3) {
            return "frost";
        }
        if (f.feels <= 8) {
            return "cold";
        }
        if (f.temp >= 20) {
            return "warm";
        }
        return "any";
    }

    private void add(List<String> parts, String key, Facts f, Random rnd, Memory memory) {
        if (!lines.containsKey(key)) {
            return;
        }
        parts.add(fill(pick(key, rnd, memory), f, rnd, memory));
    }

    /** A line for the key, never the one used last time if there is a choice. */
    private String pick(String key, Random rnd, Memory memory) {
        List<String> list = lines.get(key);
        if (list == null || list.isEmpty()) {
            return "";
        }
        int last = memory.last(key);
        int i = rnd.nextInt(list.size());
        if (list.size() > 1 && i == last) {
            i = (i + 1 + rnd.nextInt(list.size() - 1)) % list.size();
        }
        memory.used(key, i);
        return list.get(i);
    }

    private String fill(String s, Facts f, Random rnd, Memory memory) {
        String when = when(f, rnd, memory);
        String t = degrees(f.temp);
        return s.replace("{t}", t).replace("{T}", upper(t))
                .replace("{feels}", degrees(f.feels))
                .replace("{high}", degrees(f.high)).replace("{low}", degrees(f.low))
                .replace("{tmax}", degrees(f.tomorrowHigh))
                .replace("{wind}", f.wind + " " + unit(f.wind))
                .replace("{when}", when).replace("{When}", upper(when))
                .replace("{dusk}", f.dusk).replace("{dawn}", f.dawn);
    }

    /** A time ahead said as people say it: soon, in a couple of hours, or by the part of the day. */
    private String when(Facts f, Random rnd, Memory memory) {
        int ahead = f.hourAhead <= 0 ? 1 : f.hourAhead;
        String key;
        if (ahead <= 1) {
            key = "when.hour";
        } else if (ahead <= 3) {
            key = "when.couple";
        } else {
            int at = (f.hour + ahead) % 24;
            key = at >= 5 && at < 11 ? "when.morning" : at < 14 ? "when.noon" : at < 18 ? "when.day"
                    : at < 23 ? "when.evening" : "when.night";
        }
        List<String> list = lines.get(key);
        return list == null || list.isEmpty() ? "" : list.get(0);
    }

    /** Degrees with a word for the sky, and degrees alone. */
    String[] brief(Facts f) {
        String t = degrees(f.temp);
        String sky = first("brief." + sky(f.code));
        return new String[] {sky.isEmpty() ? t : t + ", " + sky, t};
    }

    private String degrees(int d) {
        if (d == 0) {
            return first("sign.zero");
        }
        return first(d > 0 ? "sign.plus" : "sign.minus") + " " + Math.abs(d);
    }

    /** One, few or many: the form of a unit that goes with a number. */
    private String unit(int n) {
        String forms = first("unit.wind");
        String[] f = forms.split("\\|");
        if (f.length < 3) {
            return forms;
        }
        int a = Math.abs(n) % 100;
        int b = a % 10;
        return a >= 11 && a <= 14 ? f[2] : b == 1 ? f[0] : b >= 2 && b <= 4 ? f[1] : f[2];
    }

    private String first(String key) {
        List<String> list = lines.get(key);
        return list == null || list.isEmpty() ? "" : list.get(0);
    }

    private static String upper(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + s.substring(1);
    }

    private static int weighted(List<Float> weights, Random rnd) {
        float sum = 0f;
        for (float w : weights) {
            sum += w;
        }
        float x = rnd.nextFloat() * sum;
        for (int i = 0; i < weights.size(); i++) {
            x -= weights.get(i);
            if (x <= 0f) {
                return i;
            }
        }
        return weights.size() - 1;
    }
}
