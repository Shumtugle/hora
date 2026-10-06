package com.shumtugle.hora;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * What is being read aloud, as the widget and the book screen see it: the
 * book, how far into it, and whether the voice is going. The player writes
 * it; everyone else only reads. Kept in its own small file so frequent writes
 * never touch the settings.
 */
final class Reading {
    private static final String FILE = "reading";
    private static final String TITLE = "title";
    private static final String PERMILLE = "permille";
    private static final String PLAYING = "playing";
    private static final String URI = "uri";
    private static final String INDEX = "index";
    private static final String TOTAL = "total";
    private static final String PARAGRAPH = "paragraph";
    private static final String AT = "at_";
    private static final String NAME = "name";

    private Reading() {
    }

    /** Written in the voice process, read in the app's: every read must see the file as it is now. */
    @SuppressWarnings("deprecation")
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS);
    }

    /** Title of the current book, or empty when none has been opened. */
    static String title(Context c) {
        return sp(c).getString(TITLE, "");
    }

    /** How far into the book, in tenths of a percent. */
    static int permille(Context c) {
        return sp(c).getInt(PERMILLE, 0);
    }

    static boolean playing(Context c) {
        return sp(c).getBoolean(PLAYING, false);
    }

    /** Records the state and redraws the widget. */
    static void set(Context c, String title, int permille, boolean playing) {
        sp(c).edit().putString(TITLE, title == null ? "" : title)
                .putInt(PERMILLE, Math.max(0, Math.min(1000, permille)))
                .putBoolean(PLAYING, playing).apply();
        Widgets.refresh(c);
    }

    /** Where the current book lives, or empty. */
    static String uri(Context c) {
        return sp(c).getString(URI, "");
    }

    /** The paragraph being read, counted from zero. */
    static int index(Context c) {
        return sp(c).getInt(INDEX, 0);
    }

    static int total(Context c) {
        return sp(c).getInt(TOTAL, 0);
    }

    /** The text of the paragraph being read, for the book screen. */
    static String paragraph(Context c) {
        return sp(c).getString(PARAGRAPH, "");
    }

    /** Records the whole state at once: one write, one redraw of the widget. */
    static void set(Context c, String uri, String title, int index, int total, String paragraph, boolean playing) {
        int pm = total <= 0 ? 0 : (int) (1000L * index / total);
        sp(c).edit().putString(URI, uri).putString(TITLE, title == null ? "" : title)
                .putInt(INDEX, index).putInt(TOTAL, total).putString(PARAGRAPH, paragraph == null ? "" : paragraph)
                .putInt(PERMILLE, Math.max(0, Math.min(1000, pm))).putBoolean(PLAYING, playing)
                .putInt(AT + uri.hashCode(), index).commit();
        Widgets.refresh(c);
    }

    /** The current book brought back from another phone: where it lives and its file name. */
    static void restoreCurrent(Context c, String uri, String name) {
        sp(c).edit().putString(URI, uri).putString(NAME, name == null ? "" : name).commit();
    }

    /** A book that moved: its kept place, and the current book's address if it is this one. */
    static void moved(Context c, String from, String to) {
        SharedPreferences.Editor e = sp(c).edit().putInt(AT + to.hashCode(), savedIndex(c, from));
        if (from.equals(uri(c))) {
            e.putString(URI, to);
        }
        e.commit();
    }

    /** Where a book was left, or zero if it was never opened. */
    static int savedIndex(Context c, String uri) {
        return sp(c).getInt(AT + uri.hashCode(), 0);
    }

    /** Moves the kept place of a book, for a player that has not loaded it yet. */
    static void setSavedIndex(Context c, String uri, int index) {
        sp(c).edit().putInt(AT + uri.hashCode(), Math.max(0, index)).commit();
    }

    /** File name of the current book: it tells the format when the book is opened again. */
    static String name(Context c) {
        return sp(c).getString(NAME, "");
    }

    static void setBook(Context c, String uri, String name) {
        sp(c).edit().putString(URI, uri).putString(NAME, name == null ? "" : name).commit();
    }
}
