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
 * Each manoeuvre is said twice: early, once the distance falls to about a
 * minute's way (never nearer than 100 m nor farther than 800 m), and again at
 * the turn itself (about eight seconds' way, 15 to 150 m). The speed is told by
 * how fast the distance melts. Straight on, and hints without an arrow, stay
 * silent. A shape the reader does not know is said as just "a turn", and its
 * picture is kept for later, if it is plain (a colourful picture might be a
 * piece of map and is never kept).
 *
 * Nothing of the route is written down: the journal gets the kind of turn and
 * whether it was said, never a street or a distance.
 */
final class NavWhisper {
    /** On foot, until the distance shows otherwise; metres a second. */
    private static final float WALK = 1.4f;
    private static final float SLOWEST = 0.5f;
    private static final float FASTEST = 40f;
    /**
     * A distance that grows by more than this, or by more than a quarter of itself (a rounded
     * "0.8 km" may read "0.9 km" next), is a new manoeuvre, even with the same arrow.
     */
    private static final float JUMP_M = 30f;
    private static final float JUMP_SHARE = 0.25f;
    /** The speed is judged over at most this much of the latest way, and no less than the shortest. */
    private static final long SPEED_WINDOW_MS = 30_000;
    private static final long SPEED_SHORTEST_MS = 4_000;
    private static final int SAMPLES = 32;
    private static final int KINDS_KEPT = 64;

    /** A phrase to be said, and what it belongs to, so a late one can be dropped. */
    static final class Said {
        final String text;
        final String key;
        final int generation;
        final boolean early;
        final int kind;
        final long at;
        final boolean sample;

        Said(String text, String key, int generation, boolean early, int kind, boolean sample) {
            this.text = text;
            this.key = key;
            this.generation = generation;
            this.early = early;
            this.kind = kind;
            this.at = SystemClock.elapsedRealtime();
            this.sample = sample;
        }
    }

    /** One route hint, by its notification's key. */
    private static final class Route {
        long firstAt;
        int generation;
        String picture = "";
        /** The text line without its distance, only to notice a new manoeuvre; never written anywhere. */
        String words;
        float lastDistance = -1;
        boolean saidEarly;
        boolean saidNow;
        boolean toldNoDistance;
        final long[] times = new long[SAMPLES];
        final float[] distances = new float[SAMPLES];
        int count;
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
    private static float speed = WALK;
    private static List<Object[]> units;
    private static final Pattern DISTANCE = Pattern.compile("(\\d+(?:[.,]\\d+)?)[\\s\\u00a0\\u202f]*(\\p{L}+)");

    private NavWhisper() {
    }

    static boolean isRouteHint(StatusBarNotification sbn) {
        return Notification.CATEGORY_NAVIGATION.equals(sbn.getNotification().category);
    }

    /**
     * Looks at a new state of a route hint. Returns what to say now, or null. Called on the
     * listener's thread, one notification at a time.
     */
    static synchronized Said seen(Context c, StatusBarNotification sbn, String app) {
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
        // The line is compared without its distance: a distance running down is the same manoeuvre.
        String words = withoutDistance(c, line);
        boolean fresh = !picture.equals(r.picture)
                || !same(words, r.words)
                || distance >= 0 && r.lastDistance >= 0
                && distance > r.lastDistance + Math.max(JUMP_M, JUMP_SHARE * r.lastDistance);
        if (fresh) {
            r.generation++;
            r.saidEarly = false;
            r.saidNow = false;
            r.count = 0;
            Diag.log(c, "nav: new manoeuvre, " + NavArrow.NAMES[kind]);
        }
        r.picture = picture;
        r.words = words;
        if (distance >= 0) {
            learnSpeed(r, now, distance);
            r.lastDistance = distance;
        }
        if (!NavArrow.spoken(kind)) {
            return null;
        }
        if (distance < 0) {
            if (!r.toldNoDistance) {
                r.toldNoDistance = true;
                Diag.mark(c, "nav: no distance in the hint");
            }
            return null;
        }
        float nowAt = clamp(speed * 8f, 15f, 150f);
        float earlyAt = clamp(speed * 60f, 100f, 800f);
        Resources res = SpeechLanguage.resources(c);
        if (!r.saidNow && distance <= nowAt) {
            r.saidNow = true;
            r.saidEarly = true;
            return new Said(nowPhrase(c, res, kind, words), sbn.getKey(), r.generation, false, kind, false);
        }
        if (!r.saidEarly && distance <= earlyAt) {
            r.saidEarly = true;
            return new Said(earlyPhrase(res, kind, round(distance)), sbn.getKey(), r.generation, true, kind, false);
        }
        return null;
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
        return r != null && r.generation == s.generation;
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

    /** Up to 100 m by tens, up to 300 m by fifties, then by hundreds. */
    static int round(float m) {
        if (m < 95) {
            return Math.max(10, Math.round(m / 10f) * 10);
        }
        if (m < 300) {
            return Math.max(100, Math.round(m / 50f) * 50);
        }
        return Math.round(m / 100f) * 100;
    }

    /** The speed, from how fast the distance to the turn melted over the last half minute. */
    private static void learnSpeed(Route r, long now, float distance) {
        int i = r.count % SAMPLES;
        r.times[i] = now;
        r.distances[i] = distance;
        r.count++;
        int n = Math.min(r.count, SAMPLES);
        long oldestAt = now;
        float oldest = distance;
        for (int k = 0; k < n; k++) {
            if (now - r.times[k] <= SPEED_WINDOW_MS && r.times[k] < oldestAt) {
                oldestAt = r.times[k];
                oldest = r.distances[k];
            }
        }
        long span = now - oldestAt;
        if (span < SPEED_SHORTEST_MS || oldest <= distance) {
            return;
        }
        float v = (oldest - distance) / (span / 1000f);
        speed = clamp(0.7f * speed + 0.3f * v, SLOWEST, FASTEST);
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

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String str(CharSequence cs) {
        if (cs == null) {
            return null;
        }
        String s = cs.toString().replace('\n', ' ').trim();
        return s.isEmpty() ? null : s;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
