package com.shumtugle.hora;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Whose ears a wireless device is. The system cannot tell headphones from a speaker or a car:
 * all of them are the same kind of wireless audio. So each one is asked about once and the
 * answer is kept: one's own headphones hear everything, anything else (and anything not yet
 * answered) hears nothing private. Wired headphones are taken as one's own.
 * Kept in a file both processes read: the reader asks and obeys, the screen lists and changes.
 */
final class Ears {
    static final int UNKNOWN = 0;
    static final int MINE = 1;
    static final int OPEN = 2;

    private static final String FILE = "ears";
    private static final String STATE = "s|";
    private static final String NAME = "n|";
    private static final String SEEN = "t|";
    private static final String ASKED = "a|";
    /** Asked again while undecided, but not more often than this. */
    private static final long ASK_AGAIN_MS = 12 * 60 * 60 * 1000L;
    /** The tag the question is posted under, apart from every other notification of the app. */
    static final String NOTE_TAG = "ears";

    private Ears() {
    }

    @SuppressWarnings("deprecation")
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS);
    }

    /** Whether a device can only be one's own: by wire into headphones, or a hearing aid. */
    static boolean wired(AudioDeviceInfo d) {
        int t = d.getType();
        return t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == TYPE_HEARING_AID;
    }

    // AudioDeviceInfo.TYPE_HEARING_AID; not available at the minimum API level.
    private static final int TYPE_HEARING_AID = 23;

    /** The question's notification id for a device; posted under NOTE_TAG. */
    static int noteId(String key) {
        return key.hashCode();
    }

    /** Whether a still undecided device may be asked about now: never asked, or not lately. */
    static boolean mayAsk(Context c, String key) {
        return System.currentTimeMillis() - sp(c).getLong(ASKED + key, 0) > ASK_AGAIN_MS;
    }

    static void asked(Context c, String key) {
        sp(c).edit().putLong(ASKED + key, System.currentTimeMillis()).apply();
    }

    /** The name a device is kept under: its product name and, where the system gives one, its address. */
    static String key(AudioDeviceInfo d) {
        String address = android.os.Build.VERSION.SDK_INT >= 28 ? d.getAddress() : "";
        return name(d) + "|" + (address == null ? "" : address);
    }

    static String name(AudioDeviceInfo d) {
        CharSequence n = d.getProductName();
        return n == null || n.length() == 0 ? "?" : n.toString();
    }

    static int state(Context c, String key) {
        return sp(c).getInt(STATE + key, UNKNOWN);
    }

    static void set(Context c, String key, int state) {
        sp(c).edit().putInt(STATE + key, state).commit();
    }

    /** Remembers a device as just seen; true when it was never seen before. */
    static boolean seen(Context c, String key, String name) {
        SharedPreferences p = sp(c);
        boolean fresh = !p.contains(NAME + key);
        p.edit().putString(NAME + key, name).putLong(SEEN + key, System.currentTimeMillis()).apply();
        return fresh;
    }

    static void forget(Context c, String key) {
        sp(c).edit().remove(STATE + key).remove(NAME + key).remove(SEEN + key).remove(ASKED + key).commit();
    }

    /** The wireless output playing now, or null: none, or wired, which needs no answer. */
    static AudioDeviceInfo wireless(AudioManager audio) {
        AudioDeviceInfo d = Earpiece.find(audio);
        return d == null || wired(d) ? null : d;
    }

    /** Whether what plays now goes into one's own ears: wired headphones, or a device answered as one's own. */
    static boolean privateNow(Context c, AudioManager audio) {
        AudioDeviceInfo d = Earpiece.find(audio);
        if (d == null) {
            return false;
        }
        return wired(d) || state(c, key(d)) == MINE;
    }

    /** Every device met, the latest first: key, name, state. */
    static List<Object[]> all(Context c) {
        Map<String, ?> m = sp(c).getAll();
        List<Object[]> out = new ArrayList<Object[]>();
        for (Map.Entry<String, ?> e : m.entrySet()) {
            if (e.getKey().startsWith(NAME) && e.getValue() instanceof String) {
                String key = e.getKey().substring(NAME.length());
                Object at = m.get(SEEN + key);
                out.add(new Object[] {key, e.getValue(), state(c, key), at instanceof Long ? (Long) at : 0L});
            }
        }
        Collections.sort(out, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return Long.compare((Long) b[3], (Long) a[3]);
            }
        });
        return out;
    }
}
