package com.shumtugle.hora;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Map;

/**
 * A scout for route hints, before Hora says any of them. A navigator marks its
 * turn-by-turn notification as navigation; while the person follows a route,
 * this notes how that notification lives: how often it changes, when its words
 * change (a new manoeuvre) and when only its numbers do (the distance running
 * down), what shape its lines have and what it carries besides words. It never
 * notes words or numbers: a route is where a person goes. Every word becomes
 * "w" and every number "n", so a line reads like "w n w w w w". Nothing is
 * spoken yet.
 *
 * The turn itself may be told only by the picture beside the line, an arrow.
 * Each new picture is described by a short fingerprint, and a plain one (few
 * colours, as arrows are) is kept in Hora's folder under "scout", so the arrows
 * can be learned; a colourful one, which might be a piece of map, is never kept.
 */
final class NavScout {
    /** Counts are told once a minute, not update by update. */
    private static final long SUMMARY_MS = 60_000;

    /** One route hint, by its notification's key: two at once do not mix. */
    private static final class Route {
        long firstAt;
        long lastAt;
        long summaryAt;
        String shape = "";
        int wordsHash;
        int numbersHash;
        int updates;
        int newWords;
        int newNumbers;
        String pictures = "";
    }

    /** Pictures already described or kept, by fingerprint. */
    private static final java.util.Set<String> knownPictures = new java.util.HashSet<String>();

    private static final Map<String, Route> routes = new HashMap<String, Route>();

    private NavScout() {
    }

    static boolean isRouteHint(StatusBarNotification sbn) {
        return Notification.CATEGORY_NAVIGATION.equals(sbn.getNotification().category);
    }

    static synchronized void seen(android.content.Context c, StatusBarNotification sbn, String app) {
        long now = System.currentTimeMillis();
        Notification n = sbn.getNotification();
        Bundle x = n.extras;
        CharSequence[] lines = {
            x == null ? null : x.getCharSequence(Notification.EXTRA_TITLE),
            x == null ? null : x.getCharSequence(Notification.EXTRA_TEXT),
            x == null ? null : x.getCharSequence(Notification.EXTRA_SUB_TEXT),
            x == null ? null : x.getCharSequence(Notification.EXTRA_BIG_TEXT),
            n.tickerText,
        };
        StringBuilder shape = new StringBuilder();
        StringBuilder words = new StringBuilder();
        StringBuilder numbers = new StringBuilder();
        for (CharSequence s : lines) {
            shape.append('[').append(shape(s)).append("] ");
            split(s, words, numbers);
        }
        Route r = routes.get(sbn.getKey());
        if (r == null) {
            r = new Route();
            r.firstAt = now;
            r.summaryAt = now;
            routes.put(sbn.getKey(), r);
            Diag.mark(c, "nav: a route hint appears from " + app + ", extras " + keys(x) + ", picture "
                    + (n.getLargeIcon() != null) + ", actions " + (n.actions == null ? 0 : n.actions.length)
                    + ", ongoing " + ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0));
        }
        r.updates++;
        int w = words.toString().hashCode();
        int num = numbers.toString().hashCode();
        String sh = shape.toString().trim();
        boolean wordsChanged = r.lastAt != 0 && w != r.wordsHash;
        if (wordsChanged) {
            r.newWords++;
        } else if (r.lastAt != 0 && num != r.numbersHash) {
            r.newNumbers++;
        }
        String pics = pictures(c, n);
        boolean newPicture = !pics.equals(r.pictures);
        if (r.lastAt == 0 || wordsChanged || newPicture || !sh.equals(r.shape)) {
            Diag.mark(c, String.format(java.util.Locale.ROOT, "nav: after %.1f s, %s, %s, pictures %s",
                    r.lastAt == 0 ? 0f : (now - r.lastAt) / 1000f, wordsChanged ? "new words" : "same words", sh, pics));
        }
        r.pictures = pics;
        r.shape = sh;
        r.wordsHash = w;
        r.numbersHash = num;
        r.lastAt = now;
        if (now - r.summaryAt >= SUMMARY_MS) {
            Diag.mark(c, "nav: " + r.updates + " updates in the last minute, words changed " + r.newWords
                    + ", only numbers changed " + r.newNumbers);
            r.updates = 0;
            r.newWords = 0;
            r.newNumbers = 0;
            r.summaryAt = now;
        }
    }

    static synchronized void gone(android.content.Context c, StatusBarNotification sbn) {
        Route r = routes.remove(sbn.getKey());
        if (r == null) {
            return;
        }
        Diag.mark(c, String.format(java.util.Locale.ROOT, "nav: the route hint is gone after %.1f min",
                (System.currentTimeMillis() - r.firstAt) / 60000f));
    }

    /** Fingerprints of the large and the small picture, "-" for none: "large ab12cd34 small 9f00e1aa". */
    private static String pictures(android.content.Context c, Notification n) {
        android.graphics.drawable.Icon large = n.getLargeIcon();
        Object extra = n.extras == null ? null : n.extras.get(Notification.EXTRA_LARGE_ICON);
        android.graphics.Bitmap big = null;
        if (large == null && extra instanceof android.graphics.drawable.Icon) {
            large = (android.graphics.drawable.Icon) extra;
        } else if (large == null && extra instanceof android.graphics.Bitmap) {
            big = (android.graphics.Bitmap) extra;
        }
        return "large " + print(c, "large", big != null ? big : render(c, large))
                + " small " + print(c, "small", render(c, n.getSmallIcon()));
    }

    private static android.graphics.Bitmap render(android.content.Context c, android.graphics.drawable.Icon icon) {
        if (icon == null) {
            return null;
        }
        try {
            android.graphics.drawable.Drawable d = icon.loadDrawable(c);
            if (d == null) {
                return null;
            }
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas k = new android.graphics.Canvas(b);
            d.setBounds(0, 0, 96, 96);
            d.draw(k);
            return b;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A short fingerprint of a picture's shape; the first time a plain picture is seen it is
     * kept in the folder. Colourful pictures are only fingerprinted.
     */
    private static String print(final android.content.Context c, final String kind, android.graphics.Bitmap b) {
        if (b == null) {
            return "-";
        }
        final android.graphics.Bitmap s = android.graphics.Bitmap.createScaledBitmap(b, 12, 12, true);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        int coloured = 0;
        int seen = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 12; x++) {
                int p = s.getPixel(x, y);
                int alpha = p >>> 24;
                int lum = ((p >> 16 & 255) + (p >> 8 & 255) + (p & 255)) / 3;
                crc.update(alpha > 96 && lum > 64 ? 1 : 0);
                if (alpha > 96) {
                    seen++;
                    android.graphics.Color.colorToHSV(p, hsv);
                    if (hsv[1] > 0.35f && hsv[2] > 0.2f) {
                        coloured++;
                    }
                }
            }
        }
        final String id = String.format(java.util.Locale.ROOT, "%08x", crc.getValue());
        boolean plain = seen == 0 || coloured * 5 < seen;
        if (knownPictures.add(kind + id) && plain) {
            final android.graphics.Bitmap keep = b.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        keep.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                        HoraFolder.writeBytes(c, c.getString(R.string.folder_scout), "nav-" + kind + "-" + id + ".png",
                                "image/png", out.toByteArray());
                    } catch (Exception e) {
                        Diag.log(c, "nav: a picture not kept", e);
                    }
                }
            }, "nav-picture").start();
        }
        return id + (plain ? "" : " colourful");
    }

    /** Every word "w", every number "n"; spaces and a few signs stay. Empty for none. */
    static String shape(CharSequence s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        char run = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            char kind;
            if (Character.isDigit(ch)) {
                kind = 'n';
            } else if (Character.isWhitespace(ch)) {
                kind = ' ';
            } else if (".,:;-–—/()%".indexOf(ch) >= 0) {
                kind = ch;
            } else {
                kind = 'w';
            }
            if ((kind == 'n' || kind == 'w') && kind == run) {
                continue;
            }
            out.append(kind);
            run = kind;
        }
        return out.toString().trim();
    }

    /** The words and the numbers of a line, apart, only to tell later which of them changed. */
    private static void split(CharSequence s, StringBuilder words, StringBuilder numbers) {
        if (s == null) {
            return;
        }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            (Character.isDigit(ch) ? numbers : words).append(ch);
        }
        words.append('|');
        numbers.append('|');
    }

    /** The names of what the notification carries, without their values. */
    private static String keys(Bundle x) {
        if (x == null) {
            return "[]";
        }
        java.util.List<String> k = new java.util.ArrayList<String>(x.keySet());
        java.util.Collections.sort(k);
        return k.toString();
    }
}
