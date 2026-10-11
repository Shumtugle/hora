package com.shumtugle.hora;

import android.content.Context;

/**
 * The developer room. It opens with seven touches on the build number, like
 * the phone's own developer options, and stays open until closed from inside.
 */
final class Beta {
    private static final String PREFS = "beta";
    private static final String OPEN = "open";

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
}
