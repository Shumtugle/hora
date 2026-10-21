package com.shumtugle.hora;

import android.app.Notification;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turn hints whispered into the headphones while a navigator leads the way.
 *
 * A navigator marks its turn-by-turn notification as navigation. Its lines
 * carry the distance and the street, but the turn itself is only in the
 * picture beside them, an arrow, which {@link NavArrow} reads by its shape.
 * When to speak is {@link NavPlan}'s: twice per manoeuvre, early and at the
 * turn, and never twice the same while the card flicks between arrows.
 * Straight on, and hints without an arrow, stay silent. A shape the reader
 * does not know is said as just "a turn", and its picture is kept for later,
 * if it is plain (a colourful picture might be a piece of map and is never
 * kept).
 *
 * Nothing of the route is written down: the journal gets the kind of turn and
 * whether it was said, never a street or a distance.
 */
final class NavWhisper {
    private static final int KINDS_KEPT = 64;

    /** A phrase to be said, and what it belongs to, so a late one can be dropped. */
    static final class Said {
        final String text;
        final String key;
        /** The manoeuvre it belongs to, as the route's plan numbers them. */
        final int generation;
        final boolean early;
        final int kind;
        final long at;
        final boolean sample;
        /** The time left on the way rather than a turn: one of TIME_START, TIME_LEFT, TIME_NEAR; else -1. */
        final int time;

        Said(String text, String key, int generation, boolean early, int kind, boolean sample) {
            this(text, key, generation, early, kind, sample, -1);
        }

        private Said(String text, String key, int generation, boolean early, int kind, boolean sample, int time) {
            this.text = text;
            this.key = key;
            this.generation = generation;
            this.early = early;
            this.kind = kind;
            this.at = SystemClock.elapsedRealtime();
            this.sample = sample;
            this.time = time;
        }
    }

    static final int TIME_START = 0;
    static final int TIME_LEFT = 1;
    static final int TIME_NEAR = 2;
    static final String[] TIME_NAMES = {"start", "left", "near"};
    /** How often the time left is said along the way. */
    private static final long TIME_EVERY_MS = 15 * 60_000L;
    /** An unheard start of the way is offered again no sooner than this. */
    private static final long START_AGAIN_MS = 30_000L;
    /** The time left counts as "near the end" from this many minutes. */
    private static final int NEAR_MINUTES = 5;

    /** One route hint, by its notification's key. */
    private static final class Route {
        long firstAt;
        final NavPlan plan = new NavPlan();
        boolean toldNoDistance;
        /** The shape of the time line has been written down once for this route. */
        boolean timeLogged;
        boolean startSaid;
        boolean nearSaid;
        /** The time left the route began with, in minutes, or -1. */
        int startMinutes = -1;
        /** When the time left was last said, elapsed time, and the moment before that, for a phrase taken back. */
        long timeAt;
        long timeAtBefore;
    }

    private static final Map<String, Route> routes = new HashMap<String, Route>();
    /** What each arrow was read as, by its fingerprint: a picture is read once. */
    private static final Map<String, Integer> kinds = new LinkedHashMap<String, Integer>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> e) {
            return size() > KINDS_KEPT;
        }
    };
    /** Unknown arrows already kept in the folder. */
    private static final Set<String> kept = new HashSet<String>();
    /**
     * Phrases are made into sound ahead, as soon as a turn shows on the card, so that at the
     * turn the hint is heard at once and does not wait for the voice to finish a piece of the
     * book. Waiting here to be made; and, by manoeuvre, the distance of the early phrase made
     * ahead and the phrase made for the turn itself.
     */
    private static final List<String> toMake = new ArrayList<String>();
    private static final Map<Integer, Integer> earlyMade = lru();
    private static final Map<Integer, String> earlyText = lru();
    private static final Map<Integer, String> nowMade = lru();
    private static List<Object[]> units;
    private static final Pattern DISTANCE = Pattern.compile("(\\d+(?:[.,]\\d+)?)[\\s\\u00a0\\u202f]*(\\p{L}+)");
    private static final Pattern AMOUNT = Pattern.compile("(?<![\\d.,:])(\\d+)[\\s\\u00a0\\u202f]*(\\p{L}+)");
    private static final Pattern CLOCK = Pattern.compile("(?<![\\d:])(\\d{1,2}):(\\d{2})(?![\\d:])(?:[\\s\\u00a0\\u202f]*([AaPp])\\.?[Mm]\\.?)?");
    private static List<Object[]> timeUnits;

    private NavWhisper() {
    }

    static boolean isRouteHint(StatusBarNotification sbn) {
        return Notification.CATEGORY_NAVIGATION.equals(sbn.getNotification().category);
    }

    /**
     * Looks at a new state of a route hint. Returns what to say now, or null. Called on the
     * listener's thread, one notification at a time. Turns are looked for when asked; the time
     * left only when asked and when nothing else waits to be said (free): a turn always comes first.
     */
    static synchronized Said seen(Context c, StatusBarNotification sbn, String app, boolean turns,
            boolean eta, boolean free) {
        long now = SystemClock.elapsedRealtime();
        Notification n = sbn.getNotification();
        Bundle x = n.extras;
        Route r = routes.get(sbn.getKey());
        if (r == null) {
            r = new Route();
            r.firstAt = now;
            routes.put(sbn.getKey(), r);
            Diag.mark(c, "nav: a route hint appears from " + app);
        }
        String picture = "-";
        int kind = NavArrow.NONE;
        Bitmap b = picture(c, n);
        if (b != null) {
            int w = b.getWidth();
            int h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            boolean[] mask = colourful(px) ? null : NavArrow.mask(px, w, h);
            if (mask == null) {
                Diag.markOnce(c, "nav-colourful", "nav: a colourful picture, silent");
            }
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            if (mask == null) {
                // A colourful picture is not an arrow (it may be a piece of map): silence.
                crc.update(px.length);
            } else {
                for (boolean m : mask) {
                    crc.update(m ? 1 : 0);
                }
            }
            picture = String.format(Locale.ROOT, "%08x", crc.getValue());
            Integer known = mask == null ? Integer.valueOf(NavArrow.NONE) : kinds.get(picture);
            if (known == null) {
                kind = NavArrow.read(mask);
                kinds.put(picture, kind);
                Diag.mark(c, "nav: arrow " + picture + " read as " + NavArrow.NAMES[kind]);
                if (kind == NavArrow.UNKNOWN) {
                    keep(c, picture, b, px);
                }
            } else {
                kind = known;
            }
        }
        String line = x == null ? null : str(x.getCharSequence(Notification.EXTRA_TEXT));
        float distance = x == null ? -1 : distance(c, x.getCharSequence(Notification.EXTRA_TITLE));
        if (distance < 0) {
            distance = distance(c, line);
        }
        // The street, if it is ever said, is taken from the line without its distance.
        String words = withoutDistance(c, line);
        int say = r.plan.update(now, picture, kind, distance);
        if (r.plan.fresh) {
            Diag.log(c, "nav: new manoeuvre, " + NavArrow.NAMES[kind]);
        }
        if (say == NavPlan.NO_DISTANCE && !r.toldNoDistance) {
            r.toldNoDistance = true;
            Diag.mark(c, "nav: no distance in the hint");
        }
        Resources res = SpeechLanguage.resources(c);
        Said turn = turns ? turn(c, sbn, r, res, kind, distance, words, say) : null;
        if (turn != null || !eta || !free) {
            return turn;
        }
        return time(c, sbn.getKey(), r, res, x, kind, distance);
    }

    /** What to say of the turn ahead, if anything; queues the phrases to make ahead. */
    private static Said turn(Context c, StatusBarNotification sbn, Route r, Resources res, int kind,
            float distance, String words, int say) {
        int id = r.plan.id;
        String nowText = NavArrow.spoken(kind) ? nowPhrase(c, res, kind, words) : null;
        if (nowText != null && say != NavPlan.NOW && !nowText.equals(nowMade.get(id))) {
            // Made again only if the words change (the street, when it is named).
            nowMade.put(id, nowText);
            toMake.add(nowText);
        }
        if (NavArrow.spoken(kind) && distance >= 0) {
            // The early phrase says the distance where it is due; that moves with the speed.
            int ahead = NavPlan.round(NavPlan.earlyAt());
            Integer made = earlyMade.get(id);
            if (say != NavPlan.EARLY && !r.plan.saidEarly() && distance > NavPlan.earlyAt()
                    && (made == null || made != ahead)) {
                String text = earlyPhrase(res, kind, ahead);
                earlyMade.put(id, ahead);
                earlyText.put(id, text);
                toMake.add(text);
            }
        }
        if (say == NavPlan.NOW) {
            return new Said(nowText, sbn.getKey(), id, false, kind, false);
        }
        if (say == NavPlan.EARLY) {
            // The phrase made ahead is taken when its distance is near enough the true one.
            int metres = r.plan.metres;
            Integer made = earlyMade.get(id);
            if (made != null && Math.abs(made - metres) <= Math.max(10, metres / 5)) {
                metres = made;
            }
            return new Said(earlyPhrase(res, kind, metres), sbn.getKey(), id, true, kind, false);
        }
        return null;
    }

    /**
     * The time left on the way, from the navigator's small line ("33 min · 35 km · 9:49"): at the
     * start, then every quarter of an hour, and once near the end. Never close to a turn, so the
     * turn is not kept waiting behind it.
     */
    private static Said time(Context c, String key, Route r, Resources res, Bundle x, int kind, float distance) {
        String line = x == null ? null : str(x.getCharSequence(Notification.EXTRA_SUB_TEXT));
        if (!r.timeLogged) {
            r.timeLogged = true;
            Diag.mark(c, line == null ? "nav: no time line" : "nav: time line " + shape(line));
        }
        int minutes = minutesLeft(c, line);
        if (minutes < 0) {
            return null;
        }
        if (NavArrow.spoken(kind) && distance >= 0 && distance <= NavPlan.earlyAt() * 1.5f) {
            return null;
        }
        long now = SystemClock.elapsedRealtime();
        int step;
        String text;
        if (!r.startSaid) {
            if (minutes <= 0 || (r.timeAt != 0 && now - r.timeAt < START_AGAIN_MS)) {
                // An unheard start is offered again, but not at every change of the card.
                return null;
            }
            step = TIME_START;
            String clock = clock(res, line, minutes);
            text = clock == null ? res.getString(R.string.nav_eta_start_no_clock, duration(res, minutes))
                    : res.getString(R.string.nav_eta_start, duration(res, minutes), clock);
            r.startSaid = true;
            r.startMinutes = minutes;
        } else if (!r.nearSaid && minutes > 0 && minutes <= NEAR_MINUTES && r.startMinutes > NEAR_MINUTES + 3) {
            step = TIME_NEAR;
            text = res.getString(R.string.nav_eta_near, capital(duration(res, minutes)));
            r.nearSaid = true;
        } else if (!r.nearSaid && minutes > NEAR_MINUTES + 2 && now - r.timeAt >= TIME_EVERY_MS) {
            step = TIME_LEFT;
            text = res.getString(R.string.nav_eta_left, duration(res, minutes));
        } else {
            return null;
        }
        r.timeAtBefore = r.timeAt;
        r.timeAt = now;
        return new Said(text, key, 0, false, NavArrow.NONE, false, step);
    }

    /** Minutes left in a time line ("1 h 5 min", "33 min."), or -1 when it shows none. */
    static int minutesLeft(Context c, String line) {
        if (line == null) {
            return -1;
        }
        Matcher m = AMOUNT.matcher(line);
        int total = 0;
        boolean found = false;
        while (m.find()) {
            Integer per = timePer(c, m.group(2));
            if (per != null) {
                try {
                    total += Integer.parseInt(m.group(1)) * per;
                    found = true;
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return found ? total : -1;
    }

    /** The arrival in words ("nine forty"), from the line's clock or, when it has none, from now. */
    private static String clock(Resources res, String line, int minutes) {
        int h;
        int m;
        Matcher k = line == null ? null : CLOCK.matcher(line);
        if (k != null && k.find()) {
            h = Integer.parseInt(k.group(1));
            m = Integer.parseInt(k.group(2));
            String half = k.group(3);
            if (half != null && (half.equals("p") || half.equals("P")) && h < 12) {
                h += 12;
            } else if (half != null && h == 12 && (half.equals("a") || half.equals("A"))) {
                h = 0;
            }
            if (h > 23 || m > 59) {
                return null;
            }
        } else {
            java.util.Calendar at = java.util.Calendar.getInstance();
            at.add(java.util.Calendar.MINUTE, minutes);
            h = at.get(java.util.Calendar.HOUR_OF_DAY);
            m = at.get(java.util.Calendar.MINUTE);
        }
        String[] masculine = res.getStringArray(R.array.numbers_masculine);
        String[] feminine = res.getStringArray(R.array.numbers_feminine);
        String mm = m == 0 ? res.getString(R.string.minutes_zero)
                : m < 10 ? res.getString(R.string.nav_clock_zero) + " " + feminine[m] : feminine[m];
        return masculine[h] + " " + mm;
    }

    /** "Twenty-five minutes", "one hour ten minutes", in words. */
    private static String duration(Resources res, int minutes) {
        String[] masculine = res.getStringArray(R.array.numbers_masculine);
        String[] feminine = res.getStringArray(R.array.numbers_feminine);
        int h = Math.min(minutes / 60, masculine.length - 1);
        int m = minutes % 60;
        String hours = h == 0 ? "" : res.getQuantityString(R.plurals.hours, h, masculine[h]);
        String mins = m == 0 ? "" : res.getQuantityString(R.plurals.minutes, m, feminine[m]);
        return (hours + " " + mins).trim();
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** A line's shape for the journal: words as w, numbers as n, the rest as it is. */
    private static String shape(String line) {
        return line.replaceAll("\\p{L}+", "w").replaceAll("\\d+", "n");
    }

    /** Minutes in one of a time word, by how the word begins; null when it is not one. */
    private static Integer timePer(Context c, String word) {
        String w = word.toLowerCase(Locale.ROOT);
        for (Object[] known : timeUnits(c)) {
            String k = (String) known[0];
            // A one-letter word ("h") must be the whole word; longer ones may begin it ("min" in "minutes").
            if (k.length() == 1 ? w.equals(k) : w.startsWith(k)) {
                return (Integer) known[1];
            }
        }
        return null;
    }

    private static synchronized List<Object[]> timeUnits(Context c) {
        if (timeUnits != null) {
            return timeUnits;
        }
        List<Object[]> out = new ArrayList<Object[]>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                c.getAssets().open("talk/nav-time.txt"), "UTF-8"))) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\t");
                if (p.length == 2) {
                    out.add(new Object[] {p[0].trim().toLowerCase(Locale.ROOT), Integer.parseInt(p[1].trim())});
                }
            }
        } catch (Exception e) {
            Diag.log(c, "nav: time words not read", e);
        }
        timeUnits = out;
        return timeUnits;
    }

    /** Whether a phrase queued for making ahead is still the one a turn on the way will need. */
    static synchronized boolean wanted(String text) {
        return nowMade.containsValue(text) || earlyText.containsValue(text);
    }

    /** The phrases to make into sound ahead, taken once. */
    static synchronized List<String> takeToMake() {
        List<String> out = new ArrayList<String>(toMake);
        toMake.clear();
        return out;
    }

    private static <V> Map<Integer, V> lru() {
        return new LinkedHashMap<Integer, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, V> e) {
                return size() > 32;
            }
        };
    }

    /** Forgets every route: its cards may have gone unseen. */
    static synchronized void forgetAll() {
        routes.clear();
    }

    /** Whether a navigator's card is still on show. */
    static synchronized boolean anyRoute() {
        return !routes.isEmpty();
    }

    static synchronized void gone(Context c, StatusBarNotification sbn) {
        Route r = routes.remove(sbn.getKey());
        if (r != null) {
            Diag.mark(c, String.format(Locale.ROOT, "nav: the route hint is gone after %.1f min",
                    (SystemClock.elapsedRealtime() - r.firstAt) / 60000f));
        }
    }

    /** Whether a phrase still belongs to the manoeuvre ahead. */
    static synchronized boolean current(Said s) {
        if (s.sample) {
            return true;
        }
        Route r = routes.get(s.key);
        return r != null && (s.time >= 0 || r.plan.current(s.generation));
    }

    /** A phrase dropped as late may be said when its arrow comes back. */
    static synchronized void unsay(Said s) {
        Route r = s.sample ? null : routes.get(s.key);
        if (r == null) {
            return;
        }
        if (s.time == TIME_START) {
            r.startSaid = false;
        } else if (s.time == TIME_NEAR) {
            r.nearSaid = false;
        } else if (s.time == TIME_LEFT) {
            r.timeAt = r.timeAtBefore;
        } else {
            r.plan.unsay(s.generation, s.early);
        }
    }

    /** The phrase "Hear an example" plays: an early hint for a right turn. */
    static Said sample(Context c) {
        return new Said(earlyPhrase(SpeechLanguage.resources(c), NavArrow.RIGHT, 100), "", 0, true,
                NavArrow.RIGHT, true);
    }

    private static String earlyPhrase(Resources res, int kind, int metres) {
        String[] soon = res.getStringArray(R.array.nav_soon);
        return res.getQuantityString(R.plurals.nav_in_metres, metres, metres) + " " + soon[kind];
    }

    private static String nowPhrase(Context c, Resources res, int kind, String words) {
        String said = res.getStringArray(R.array.nav_now)[kind];
        String street = kind == NavArrow.ARRIVE || !Prefs.navStreetShared(c) ? null : street(words);
        return street == null ? said : res.getString(R.string.nav_onto, said, street);
    }

    /** The street of a text line, if the line looks like one: the first of its names, a few words, no numbers. */
    static String street(String words) {
        if (words == null) {
            return null;
        }
        int slash = words.indexOf(" / ");
        String s = (slash > 0 ? words.substring(0, slash) : words)
                .replaceAll("^[\\s\\p{Punct}\\u00b7\\u2013\\u2014]+|[\\s\\p{Punct}\\u00b7\\u2013\\u2014]+$", "").trim();
        if (s.isEmpty() || s.length() > 40 || s.split("\\s+").length > 4) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                return null;
            }
        }
        return s;
    }

    /** The distance written in a line ("170 m", "1,2 km", "1,000 ft"), in metres; -1 when there is none. */
    static float distance(Context c, CharSequence line) {
        if (line == null) {
            return -1;
        }
        Matcher m = DISTANCE.matcher(line);
        while (m.find()) {
            Float per = per(c, m.group(2));
            if (per != null) {
                String number = m.group(1);
                // "1,000 ft": a comma before exactly three digits of a small unit groups thousands.
                if (per <= 1f && number.matches("\\d{1,3},\\d{3}")) {
                    number = number.replace(",", "");
                }
                try {
                    return Float.parseFloat(number.replace(',', '.')) * per;
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** The line with every distance in it taken out. */
    private static String withoutDistance(Context c, String line) {
        if (line == null) {
            return null;
        }
        Matcher m = DISTANCE.matcher(line);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(out, per(c, m.group(2)) != null ? "" : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(out);
        return out.toString().trim();
    }

    /** Metres in one of a unit, or null when the word is not a unit. */
    private static Float per(Context c, String unit) {
        String u = unit.toLowerCase(Locale.ROOT);
        for (Object[] known : units(c)) {
            if (known[0].equals(u)) {
                return (Float) known[1];
            }
        }
        return null;
    }

    private static synchronized List<Object[]> units(Context c) {
        if (units != null) {
            return units;
        }
        List<Object[]> out = new ArrayList<Object[]>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                c.getAssets().open("talk/nav-units.txt"), "UTF-8"))) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\t");
                if (p.length == 2) {
                    out.add(new Object[] {p[0].trim().toLowerCase(Locale.ROOT), Float.parseFloat(p[1].trim())});
                }
            }
        } catch (Exception e) {
            Diag.log(c, "nav: units not read", e);
        }
        units = out;
        return units;
    }

    /** The large picture of the hint, whichever way the notification carries it. */
    private static Bitmap picture(Context c, Notification n) {
        android.graphics.drawable.Icon large = n.getLargeIcon();
        Object extra = n.extras == null ? null : n.extras.get(Notification.EXTRA_LARGE_ICON);
        if (large == null && extra instanceof android.graphics.drawable.Icon) {
            large = (android.graphics.drawable.Icon) extra;
        }
        if (large == null && extra instanceof Bitmap) {
            Bitmap b = (Bitmap) extra;
            if (b.isRecycled()) {
                return null;
            }
            if (b.getWidth() * b.getHeight() > 256 * 256 || b.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap plain = b.copy(Bitmap.Config.ARGB_8888, false);
                return plain == null ? null : Bitmap.createScaledBitmap(plain, 96, 96, true);
            }
            return b;
        }
        if (large == null) {
            return null;
        }
        try {
            android.graphics.drawable.Drawable d = large.loadDrawable(c);
            if (d == null) {
                return null;
            }
            Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas k = new android.graphics.Canvas(b);
            d.setBounds(0, 0, 96, 96);
            d.draw(k);
            return b;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether a picture is colourful (a fifth of what shows has colour), as a map is and an arrow is not. */
    private static boolean colourful(int[] px) {
        int seen = 0;
        int coloured = 0;
        for (int p : px) {
            if (p >>> 24 > 96) {
                seen++;
                int r = p >> 16 & 255;
                int g = p >> 8 & 255;
                int b = p & 255;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                // Saturation above 0.35 and brightness above 0.2, as the colour wheel counts them.
                if (max > 51 && (max - min) * 100 > 35 * max) {
                    coloured++;
                }
            }
        }
        return seen > 0 && coloured * 5 >= seen;
    }

    /**
     * Keeps an arrow the reader did not know, once (only plain pictures come here). It goes to
     * Hora's folder for later study and is not announced to the media index, so it never shows
     * up among the person's pictures.
     */
    private static void keep(final Context c, final String id, Bitmap b, int[] px) {
        if (!kept.add(id)) {
            return;
        }
        final Bitmap copy = b.copy(Bitmap.Config.ARGB_8888, false);
        if (copy == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    copy.compress(Bitmap.CompressFormat.PNG, 100, out);
                    HoraFolder.writeBytes(c, c.getString(R.string.folder_scout), "nav-unknown-" + id + ".png",
                            "image/png", out.toByteArray(), false);
                    Diag.mark(c, "nav: unknown arrow kept");
                } catch (Exception e) {
                    Diag.log(c, "nav: an unknown arrow not kept", e);
                }
            }
        }, "nav-picture").start();
    }

    private static String str(CharSequence cs) {
        if (cs == null) {
            return null;
        }
        String s = cs.toString().replace('\n', ' ').trim();
        return s.isEmpty() ? null : s;
    }
}
