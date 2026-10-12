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
    }

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
        if (r.lastAt == 0 || wordsChanged || !sh.equals(r.shape)) {
            Diag.mark(c, String.format(java.util.Locale.ROOT, "nav: after %.1f s, %s, %s",
                    r.lastAt == 0 ? 0f : (now - r.lastAt) / 1000f, wordsChanged ? "new words" : "same words", sh));
        }
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
