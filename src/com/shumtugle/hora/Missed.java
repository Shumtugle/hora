package com.shumtugle.hora;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Notifications the reader had to stay silent about: in a call, in quiet hours, with the
 * headphones off. Kept per notification until it leaves the shade or Hora says there are
 * new ones. Only the app, how many and who writes are kept; never what was written. Kept
 * on disk, as the voice process may be ended while it waits.
 */
final class Missed {
    private static final String FILE = "missed";
    private static final String LIST = "list";
    /** More notifications than this are not worth counting one by one. */
    private static final int KEPT = 64;
    /** Apps named in the full word; the rest are "more in other apps". */
    private static final int APPS_NAMED = 3;
    /** Names kept per notification: two are said, a third makes it "and others". */
    private static final int SENDERS_KEPT = 3;
    /** Older than this, a notification still in the shade is not new any more. */
    private static final long NEW_FOR_MS = 12 * 60 * 60 * 1000L;

    private static final class Entry {
        final String app;
        int count;
        /** When it was last missed, wall clock. */
        long since;
        final LinkedHashSet<String> senders = new LinkedHashSet<String>();

        Entry(String app) {
            this.app = app;
        }
    }

    /** What to say, and which notifications it covers, so only those are forgotten once it is heard. */
    static final class Telling {
        final String text;
        final Set<String> keys;
        final int total;
        final int apps;
        final boolean full;

        Telling(String text, Set<String> keys, int total, int apps, boolean full) {
            this.text = text;
            this.keys = keys;
            this.total = total;
            this.apps = apps;
            this.full = full;
        }
    }

    /** By notification key, oldest first. */
    private final LinkedHashMap<String, Entry> byKey = new LinkedHashMap<String, Entry>();
    private final Context app;

    Missed(Context c) {
        app = c.getApplicationContext();
        load();
    }

    /** One more post the reader stayed silent about; the sender may be null. */
    synchronized void add(String key, String appName, String sender) {
        Entry e = byKey.get(key);
        if (e == null) {
            if (byKey.size() >= KEPT) {
                Iterator<String> it = byKey.keySet().iterator();
                it.next();
                it.remove();
            }
            e = new Entry(appName);
            byKey.put(key, e);
        }
        // Aged by the latest post: a chat left in the shade all day is new again with a new message.
        e.since = System.currentTimeMillis();
        e.count++;
        if (sender != null && e.senders.size() < SENDERS_KEPT) {
            e.senders.add(sender);
        }
        save();
    }

    /** The notification left the shade: read elsewhere or swept away, no longer news. */
    synchronized void gone(String key) {
        if (byKey.remove(key) != null) {
            save();
        }
    }

    /**
     * What to say now, or null when nothing is new: whatever left the shade (also while this
     * process was gone) or waited too long is dropped first.
     */
    synchronized Telling prepare(Resources r, boolean full, Set<String> shown) {
        boolean changed = byKey.keySet().retainAll(shown);
        long oldest = System.currentTimeMillis() - NEW_FOR_MS;
        Iterator<Entry> it = byKey.values().iterator();
        while (it.hasNext()) {
            if (it.next().since < oldest) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            save();
        }
        if (byKey.isEmpty()) {
            return null;
        }
        return new Telling(phrase(r, full), new java.util.HashSet<String>(byKey.keySet()), total(), apps(), full);
    }

    /** These were heard: forget them, and only them; anything missed meanwhile stays. */
    synchronized void told(Set<String> keys) {
        if (byKey.keySet().removeAll(keys)) {
            save();
        }
    }

    synchronized boolean isEmpty() {
        return byKey.isEmpty();
    }

    /** How many posts are waiting, for the journal. */
    synchronized int total() {
        int n = 0;
        for (Entry e : byKey.values()) {
            n += e.count;
        }
        return n;
    }

    /** How many apps they came from, for the journal. */
    synchronized int apps() {
        return grouped().size();
    }

    /**
     * What to say: one short line, or, when asked for in full, the apps with how many and who
     * writes, the first few apps by name and the rest together.
     */
    synchronized String phrase(Resources r, boolean full) {
        if (!full) {
            return r.getString(R.string.missed_short);
        }
        List<Entry> apps = grouped();
        StringBuilder parts = new StringBuilder();
        for (int i = 0; i < apps.size() && i < APPS_NAMED; i++) {
            Entry e = apps.get(i);
            if (parts.length() > 0) {
                parts.append(r.getString(R.string.missed_join));
            }
            parts.append(part(r, e));
        }
        String out = r.getString(R.string.missed_full, parts.toString());
        if (apps.size() > APPS_NAMED) {
            out = out + ' ' + r.getString(R.string.missed_more);
        }
        return out;
    }

    private static String part(Resources r, Entry e) {
        String n = count(r, e.count);
        List<String> who = new ArrayList<String>(e.senders);
        if (who.isEmpty()) {
            return r.getString(R.string.missed_app, e.app, n);
        }
        if (who.size() == 1) {
            return r.getString(R.string.missed_app_one, e.app, n, who.get(0));
        }
        if (who.size() == 2) {
            return r.getString(R.string.missed_app_two, e.app, n, who.get(0), who.get(1));
        }
        return r.getString(R.string.missed_app_many, e.app, n, who.get(0), who.get(1));
    }

    /** Small numbers as words, so the voice never guesses their gender; larger ones as digits. */
    private static String count(Resources r, int n) {
        String[] words = r.getStringArray(R.array.missed_counts);
        return n >= 1 && n <= words.length ? words[n - 1] : String.valueOf(n);
    }

    /** The same app's notifications together, in the order the apps were first missed. */
    private List<Entry> grouped() {
        Map<String, Entry> byApp = new LinkedHashMap<String, Entry>();
        for (Entry e : byKey.values()) {
            Entry g = byApp.get(e.app);
            if (g == null) {
                g = new Entry(e.app);
                byApp.put(e.app, g);
            }
            g.count += e.count;
            g.senders.addAll(e.senders);
        }
        return new ArrayList<Entry>(byApp.values());
    }

    private void load() {
        String s = prefs().getString(LIST, null);
        if (s == null) {
            return;
        }
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry(o.getString("app"));
                e.count = o.optInt("count", 1);
                e.since = o.optLong("since", System.currentTimeMillis());
                JSONArray w = o.optJSONArray("senders");
                if (w != null) {
                    for (int j = 0; j < w.length(); j++) {
                        e.senders.add(w.getString(j));
                    }
                }
                byKey.put(o.getString("key"), e);
            }
        } catch (JSONException e) {
            // A list that cannot be read is no worse than an empty one.
            byKey.clear();
        }
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Map.Entry<String, Entry> m : byKey.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("key", m.getKey());
                o.put("app", m.getValue().app);
                o.put("count", m.getValue().count);
                o.put("since", m.getValue().since);
                o.put("senders", new JSONArray(m.getValue().senders));
                a.put(o);
            }
        } catch (JSONException e) {
            return;
        }
        prefs().edit().putString(LIST, a.toString()).apply();
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
