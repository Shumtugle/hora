package com.shumtugle.hora;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/**
 * Everything Hora fetches, as one download: the voice, English, the full
 * decoder, the AI model and its part for photos. The accelerator is left out:
 * it is fetched only by hand. The voice goes first, so Hora speaks in its own
 * voice long before the model arrives. Each file is still its own fetch with
 * its own checks; only the progress is shown as one.
 */
final class Everything {
    private static final String PREFS = "everything";
    private static final String TOTAL = "total";
    private static final String ON = "on";
    private static final String SIZE = "size_";

    /** The packs in the set, by id, in the order they start. */
    private static final String[] PACKS = {LanguagePack.NATIVE, LanguagePack.ENGLISH, LanguagePack.DECODER};
    private static final String[] PACKS_PLAIN = {LanguagePack.NATIVE, LanguagePack.ENGLISH};

    /**
     * The packs in the set now. The full decoder only while the developer room is open: its
     * switch lives there, and outside it the pack would only take room without being used.
     */
    private static String[] packs(Context c) {
        return Beta.open(c) ? PACKS : PACKS_PLAIN;
    }
    /** The model places in the set: the model itself and the part for photos. */
    private static final int[] SLOTS = {0, 1};
    /** Kept free beyond what is fetched, so the phone is never filled to the brim. */
    private static final long SPARE = 300L * 1024 * 1024;

    /** Where the set stands. */
    static final class State {
        long done;
        long total;
        boolean running;
        boolean waitingWifi;
        boolean voiceReady;
        /** What is coming now: a pack id, a model place, or empty. */
        String now = "";
        /** Items that are neither here nor coming. */
        int missing;
    }

    /** Not enough room: how many bytes are needed and how many are free. */
    static final class NoRoom extends IOException {
        final long need;
        final long free;

        NoRoom(long need, long free) {
            super("not enough room");
            this.need = need;
            this.free = free;
        }
    }

    private Everything() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether the set was asked for and is not over yet. */
    static boolean on(Context c) {
        return sp(c).getBoolean(ON, false);
    }

    /** Whether every item of the set is on the phone. */
    static boolean complete(Context c) {
        for (String p : packs(c)) {
            if (!LanguagePack.installed(c, p)) {
                return false;
            }
        }
        for (int s : SLOTS) {
            if (!Brain.get(c).has(s)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Starts whatever of the set is not yet here, by Wi-Fi. Reads the list,
     * checks the room first. Network: not on the main thread.
     */
    static void start(Context c) throws IOException {
        JSONObject index = Fetch.index(c);
        long need = 0;
        long biggest = 0;
        java.util.List<Runnable> starts = new java.util.ArrayList<Runnable>();
        long total = 0;
        SharedPreferences.Editor sizes = sp(c).edit();
        for (final String p : packs(c)) {
            final Fetch.Entry e = Fetch.entry(index, "packs", "id", p);
            total += e.bytes;
            sizes.putLong(SIZE + p, e.bytes);
            if (LanguagePack.installed(c, p) || Fetch.active(c, LanguagePack.key(p))) {
                continue;
            }
            // A pack arrives as a zip and is unpacked beside it: twice its size for a moment.
            need += 2 * e.bytes;
            final Context app = c.getApplicationContext();
            starts.add(new Runnable() {
                @Override
                public void run() {
                    LanguagePack.start(app, p, null, e);
                }
            });
        }
        for (final int s : SLOTS) {
            final Fetch.Entry e = Fetch.entry(index, "models", "slot", ModelDownload.slotName(s));
            total += e.bytes;
            sizes.putLong(SIZE + ModelDownload.slotName(s), e.bytes);
            if (Brain.get(c).has(s) || ModelDownload.active(c, s)) {
                continue;
            }
            need += e.bytes;
            biggest = Math.max(biggest, partOf(e));
            final Context app = c.getApplicationContext();
            starts.add(new Runnable() {
                @Override
                public void run() {
                    ModelDownload.start(app, s, e);
                }
            });
        }
        // A file that comes in parts holds one part beside the joined file for a moment.
        need += biggest + SPARE;
        long free = free(c);
        if (!starts.isEmpty() && free < need) {
            throw new NoRoom(need, free);
        }
        sizes.putLong(TOTAL, total).putBoolean(ON, true).commit();
        for (Runnable r : starts) {
            r.run();
        }
        Diag.mark(c, "everything: started, " + starts.size() + " files, " + total / 1000000 + " MB in all");
    }

    /** The largest part of an item fetched in parts, or the whole item. */
    private static long partOf(Fetch.Entry e) {
        try {
            org.json.JSONArray parts = e.sources.getJSONObject(0).optJSONArray("parts");
            return parts == null ? e.bytes : (e.bytes + parts.length() - 1) / parts.length();
        } catch (org.json.JSONException x) {
            return e.bytes;
        }
    }

    /** Free room where the files land (outside storage when there is one, else inside). */
    static long free(Context c) {
        File where = c.getExternalFilesDir(null);
        if (where == null) {
            where = c.getFilesDir();
        }
        return new StatFs(where.getPath()).getAvailableBytes();
    }

    /** How the set stands now. Cheap enough for a screen that looks once a second. */
    static State state(Context c) {
        State st = new State();
        st.total = sp(c).getLong(TOTAL, 0);
        long known = 0;
        for (String p : packs(c)) {
            if (LanguagePack.installed(c, p)) {
                // Counted as fetched, not as unpacked, so the bar never runs past its end.
                long n = sp(c).getLong(SIZE + p, LanguagePack.size(c, p));
                st.done += n;
                known += n;
                continue;
            }
            Fetch.Progress pr = Fetch.progress(c, LanguagePack.key(p));
            if (pr == null) {
                st.missing++;
                continue;
            }
            take(st, pr, p);
        }
        for (int s : SLOTS) {
            if (Brain.get(c).has(s)) {
                long n = Brain.get(c).slotFile(s).length();
                st.done += n;
                known += n;
                continue;
            }
            Fetch.Progress pr = ModelDownload.progress(c, s);
            if (pr == null) {
                st.missing++;
                continue;
            }
            take(st, pr, ModelDownload.slotName(s));
        }
        st.voiceReady = Voice.ready(c);
        if (st.total <= 0) {
            st.total = Math.max(known, st.done);
        }
        st.done = Math.min(st.done, st.total);
        if (!st.running && on(c)) {
            // Nothing is coming any more: the set is over, whole or not.
            sp(c).edit().putBoolean(ON, false).apply();
        }
        return st;
    }

    private static void take(State st, Fetch.Progress pr, String what) {
        st.done += Math.max(0, pr.done);
        st.running = true;
        if (st.now.isEmpty()) {
            st.now = what;
        }
        if (pr.status == DownloadManager.STATUS_PAUSED && pr.reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) {
            st.waitingWifi = true;
        }
    }

    /** The words for what is coming now, for the screens and the notification. */
    static String nowWords(Context c, String now) {
        switch (now) {
            case LanguagePack.NATIVE: return c.getString(R.string.all_now_voice);
            case LanguagePack.ENGLISH: return c.getString(R.string.all_now_english);
            case LanguagePack.DECODER: return c.getString(R.string.all_now_decoder);
            case "model": return c.getString(R.string.all_now_model);
            case "vision": return c.getString(R.string.all_now_vision);
            default: return "";
        }
    }

    /** "1,2 of 4,3 GB · 28 %" in the person's language. */
    static String amount(Context c, State st) {
        int pct = st.total <= 0 ? 0 : (int) (100L * st.done / st.total);
        return c.getString(R.string.all_amount, gb(st.done), gb(st.total), pct);
    }

    static String gb(long bytes) {
        return String.format(java.util.Locale.getDefault(), "%.1f", bytes / 1e9);
    }
}
