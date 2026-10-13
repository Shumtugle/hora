package com.shumtugle.hora;

import android.media.session.MediaSession;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;

/** Media keys as plain words for the log: which key, down or up, how many repeats, from what. */
final class Keys {
    private Keys() {
    }

    /** "key MEDIA_NEXT (87) down, repeat 0, long, from <device>"; never the text of anything. */
    static String describe(KeyEvent k) {
        if (k == null) {
            return "key none in the intent";
        }
        StringBuilder s = new StringBuilder("key ").append(name(k.getKeyCode()))
                .append(" (").append(k.getKeyCode()).append(") ")
                .append(k.getAction() == KeyEvent.ACTION_DOWN ? "down"
                        : k.getAction() == KeyEvent.ACTION_UP ? "up" : "action " + k.getAction())
                .append(", repeat ").append(k.getRepeatCount());
        if (k.isLongPress()) {
            s.append(", long");
        }
        if ((k.getFlags() & KeyEvent.FLAG_CANCELED) != 0) {
            s.append(", canceled");
        }
        s.append(", from ").append(device(k.getDeviceId()));
        return s.toString();
    }

    /** Where a step came from: the key being handled, or none (screen, system panel, another app). */
    static String source(int code) {
        return code == 0 ? "no key" : "key " + name(code);
    }

    /**
     * Who asked the session for the step, as ", by <package>"; only inside a session callback
     * and only from Android 9, empty otherwise. Tells a key from a command sent straight by
     * the system's wireless audio or by another app.
     */
    static String caller(MediaSession s) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return "";
        }
        try {
            android.media.session.MediaSessionManager.RemoteUserInfo who = s == null ? null : s.getCurrentControllerInfo();
            return who == null ? "" : ", by " + who.getPackageName();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String name(int code) {
        String n = KeyEvent.keyCodeToString(code);
        return n.startsWith("KEYCODE_") ? n.substring(8) : n;
    }

    private static String device(int id) {
        try {
            InputDevice d = InputDevice.getDevice(id);
            return d == null ? "device " + id : d.getName() + " (" + id + ")";
        } catch (RuntimeException e) {
            return "device " + id;
        }
    }
}
