package com.shumtugle.hora;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/**
 * The AI model's files, fetched by the list like everything else and put in
 * the model's places once whole and checked.
 */
final class ModelDownload {
    /** How each place is called in the list. */
    private static final String[] SLOTS = {"model", "vision", "draft"};

    private ModelDownload() {
    }

    static String key(int slot) {
        return "model:" + SLOTS[slot];
    }

    /** The slot a fetch key belongs to, or -1. */
    static int slotOf(String key) {
        for (int s = 0; s < SLOTS.length; s++) {
            if (key(s).equals(key)) {
                return s;
            }
        }
        return -1;
    }

    /** Starts fetching the given slots, read from one copy of the list. Network: not on the main thread. */
    static void start(Context c, int[] slots, boolean anyNetwork) throws IOException {
        org.json.JSONObject root = Fetch.index(c);
        for (int s : slots) {
            Fetch.start(c, key(s), Fetch.entry(root, "models", "slot", SLOTS[s]),
                    new File(Brain.get(c).outerFolder(), SLOTS[s] + ".fetched"), anyNetwork);
        }
    }

    static boolean active(Context c, int slot) {
        return Fetch.active(c, key(slot));
    }

    static boolean running(Context c, int slot) {
        return Fetch.running(c, key(slot));
    }

    static Fetch.Progress progress(Context c, int slot) {
        return Fetch.progress(c, key(slot));
    }

    static void cancel(Context c, int slot) {
        Fetch.cancel(c, key(slot));
    }

    /**
     * Moves a fetch on and puts the file in its place once whole and checked.
     * Returns Fetch.DONE, WORKING, FAILED or NONE. Slow: not on the main thread.
     */
    static synchronized int finish(Context c, int slot) {
        String name = Fetch.name(c, key(slot));
        Fetch.Outcome o = Fetch.step(c, key(slot));
        if (o.state != Fetch.DONE) {
            return o.state;
        }
        try {
            Brain.get(c).adopt(slot, o.file, name);
            return Fetch.DONE;
        } catch (IOException e) {
            Diag.log(c, "model: not put in place", e);
            o.file.delete();
            return Fetch.FAILED;
        }
    }
}
