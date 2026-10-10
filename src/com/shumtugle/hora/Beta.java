package com.shumtugle.hora;

import android.content.Context;

/**
 * The developer room and the trial voices in it. The room opens with seven
 * touches on the build number, like the phone's own developer options. Inside,
 * each of three places (books, notifications, talk) can be given a trial voice.
 * Only the sound changes: roles, faces and names stay as they are, and
 * audiobooks are always made with the cast's own voices.
 */
final class Beta {
    private static final String PREFS = "beta";
    private static final String OPEN = "open";
    private static final String FOR = "for_";

    private Beta() {
    }

    @SuppressWarnings("deprecation")
    private static android.content.SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS);
    }

    static boolean open(Context c) {
        return sp(c).getBoolean(OPEN, false);
    }

    static void setOpen(Context c, boolean open) {
        sp(c).edit().putBoolean(OPEN, open).commit();
    }

    /** The trial voice chosen for a role, or 0 for the cast's own. */
    static int chosen(Context c, int role) {
        return sp(c).getInt(FOR + role, 0);
    }

    static void choose(Context c, int role, int voice) {
        sp(c).edit().putInt(FOR + role, voice).commit();
    }

    /** The voice that sounds for a role: a trial voice when the room is open and its sample is here, else the usual. */
    static int voice(Context c, int role, int usual) {
        if (!open(c)) {
            return usual;
        }
        int b = chosen(c, role);
        return b > Cast.COUNT && Demo.sample(c, b).isFile() ? b : usual;
    }
}
