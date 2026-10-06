package com.shumtugle.hora;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Fetches the large files Hora does not carry: language packs and the AI
 * model. What exists and where is told by a small list published next to the
 * app's releases; its address lives in the bundle, not in the code.
 *
 * Each item in the list names the finished file's size and sum and one or
 * more sources, tried in order. A source is either one address for the whole
 * file, or a row of parts, each with its own sum, for hosts that cap the size
 * of a single file. Parts are joined back into the very file the sum names,
 * so the result is checked the same way whichever source it came from.
 *
 * The system does the downloading: it resumes after a drop and goes on with
 * the app closed. Every step after a download (checking a part, joining it,
 * starting the next one, falling back to the next source) happens in step(),
 * called both when the system reports a download over and when a screen
 * looks, so nothing depends on the screen being open.
 */
final class Fetch {
    private static final String STATE = "fetch";
    private static final String KEYS = "keys";
    private static final String INDEX_ASSET = "talk/packs-url.txt";

    /** What step() found. */
    static final int WORKING = 0;
    static final int DONE = 1;
    static final int FAILED = 2;
    static final int NONE = 3;

    /** One item of the list. */
    static final class Entry {
        String name;
        long bytes;
        String sha256;
        JSONArray sources;
    }

    /** Where a fetch stands, over the whole file, parts included. */
    static final class Progress {
        int status;
        int reason;
        long done;
        long total;
    }

    static final class Outcome {
        final int state;
        final File file;

        Outcome(int state, File file) {
            this.state = state;
            this.file = file;
        }
    }

    private Fetch() {
    }

    // The list.

    /** Reads the list of what can be fetched; network, so never on the main thread. */
    static JSONObject index(Context c) throws IOException {
        String address;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open(INDEX_ASSET), "UTF-8"))) {
            address = r.readLine().trim();
        }
        HttpURLConnection h = (HttpURLConnection) new URL(address).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(15000);
        h.setInstanceFollowRedirects(true);
        try {
            if (h.getResponseCode() != 200) {
                throw new IOException("list: http " + h.getResponseCode());
            }
            return new JSONObject(new String(LanguagePack.readAll(h.getInputStream()), "UTF-8"));
        } catch (JSONException ex) {
            throw new IOException("list: " + ex.getMessage());
        } finally {
            h.disconnect();
        }
    }

    /** In the given group, the item whose key field has the given value. */
    static Entry entry(JSONObject root, String group, String key, String value) throws IOException {
        try {
            JSONArray all = root.getJSONArray(group);
            for (int i = 0; i < all.length(); i++) {
                JSONObject p = all.getJSONObject(i);
                if (!value.equals(p.optString(key))) {
                    continue;
                }
                Entry e = new Entry();
                e.name = p.optString("name");
                e.bytes = p.optLong("bytes");
                e.sha256 = p.optString("sha256").toLowerCase(java.util.Locale.ROOT);
                e.sources = p.getJSONArray("sources");
                if (e.sources.length() == 0 || e.sha256.length() != 64) {
                    throw new IOException("list: incomplete entry for " + value);
                }
                return e;
            }
            throw new IOException("list: nothing for " + value);
        } catch (JSONException ex) {
            throw new IOException("list: " + ex.getMessage());
        }
    }

    // Starting and looking.

    /** Starts fetching an item into the given file; whatever was fetched under this key before is dropped. */
    static synchronized void start(Context c, String key, Entry e, File target, boolean anyNetwork) {
        cancel(c, key);
        try {
            JSONObject st = new JSONObject();
            st.put("name", e.name == null ? "" : e.name);
            st.put("sum", e.sha256);
            st.put("bytes", e.bytes);
            st.put("sources", e.sources);
            st.put("src", 0);
            st.put("part", 0);
            st.put("done", 0L);
            st.put("target", target.getPath());
            st.put("any", anyNetwork);
            enqueue(c, key, st);
        } catch (JSONException ex) {
            Diag.log(c, "fetch: cannot start " + key, ex);
        }
    }

    /** The fetch under this key, or null when there is none. */
    static Progress progress(Context c, String key) {
        JSONObject st = load(c, key);
        if (st == null) {
            return null;
        }
        Progress p = new Progress();
        long id = st.optLong("id", -1);
        Cursor q = dm(c).query(new DownloadManager.Query().setFilterById(id));
        long now = 0;
        long size = 0;
        try {
            if (q != null && q.moveToFirst()) {
                p.status = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                p.reason = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                now = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                size = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            } else {
                // The system forgot it (cleared by the user, say): treat as failed so step() moves on.
                p.status = DownloadManager.STATUS_FAILED;
            }
        } finally {
            if (q != null) {
                q.close();
            }
        }
        long whole = st.optLong("bytes");
        p.done = st.optLong("done") + Math.max(0, now);
        p.total = whole > 0 ? whole : size;
        return p;
    }

    /**
     * Whether a fetch exists at all: from start until the file is in place or every source
     * failed, including the moments between parts and while a finished file is checked.
     */
    static boolean active(Context c, String key) {
        return load(c, key) != null;
    }

    /** Whether a fetch is under way and not yet reported over. */
    static boolean running(Context c, String key) {
        Progress p = progress(c, key);
        return p != null && p.status != DownloadManager.STATUS_SUCCESSFUL && p.status != DownloadManager.STATUS_FAILED;
    }

    /** The name the item has in the list, for the screen. */
    static String name(Context c, String key) {
        JSONObject st = load(c, key);
        return st == null ? "" : st.optString("name");
    }

    /** The key of the fetch the system download with this id belongs to, or null. */
    static String keyOf(Context c, long id) {
        for (String k : sp(c).getStringSet(KEYS, new java.util.HashSet<String>())) {
            JSONObject st = load(c, k);
            if (st != null && st.optLong("id", -1) == id) {
                return k;
            }
        }
        return null;
    }

    static synchronized void cancel(Context c, String key) {
        JSONObject st = load(c, key);
        if (st == null) {
            return;
        }
        long id = st.optLong("id", -1);
        if (id >= 0) {
            dm(c).remove(id);
        }
        litter(st);
        forget(c, key);
    }

    // After a download.

    /**
     * Moves a fetch on after the system reports its current download over:
     * checks it, joins a part, starts the next part or the next source.
     * Slow (sums read whole files): never on the main thread.
     */
    static synchronized Outcome step(Context c, String key) {
        JSONObject st = load(c, key);
        if (st == null) {
            return new Outcome(NONE, null);
        }
        Progress p = progress(c, key);
        if (p.status != DownloadManager.STATUS_SUCCESSFUL && p.status != DownloadManager.STATUS_FAILED) {
            return new Outcome(WORKING, null);
        }
        try {
            if (p.status == DownloadManager.STATUS_FAILED) {
                Diag.mark(c, "fetch: " + key + " download failed, reason " + p.reason);
                return next(c, key, st);
            }
            File target = new File(st.getString("target"));
            JSONObject source = st.getJSONArray("sources").getJSONObject(st.getInt("src"));
            JSONArray parts = source.optJSONArray("parts");
            if (parts == null) {
                File got = whole(st);
                if (!st.getString("sum").equals(LanguagePack.sha256(got))) {
                    Diag.mark(c, "fetch: " + key + " sum differs");
                    return next(c, key, st);
                }
                return done(c, key, got, target);
            }
            int k = st.getInt("part");
            File got = part(st, k);
            String want = parts.getJSONObject(k).optString("sha256").toLowerCase(java.util.Locale.ROOT);
            if (!want.equals(LanguagePack.sha256(got))) {
                Diag.mark(c, "fetch: " + key + " part " + (k + 1) + " sum differs");
                return next(c, key, st);
            }
            File join = joined(st);
            if (k == 0) {
                join.delete();
            }
            long size = got.length();
            append(got, join);
            got.delete();
            st.put("done", st.optLong("done") + size);
            if (k + 1 < parts.length()) {
                st.put("part", k + 1);
                enqueue(c, key, st);
                return new Outcome(WORKING, null);
            }
            if (!st.getString("sum").equals(LanguagePack.sha256(join))) {
                Diag.mark(c, "fetch: " + key + " joined file sum differs");
                return next(c, key, st);
            }
            return done(c, key, join, target);
        } catch (JSONException | IOException e) {
            Diag.log(c, "fetch: " + key + " step failed", e);
            try {
                return next(c, key, st);
            } catch (JSONException ex) {
                forget(c, key);
                return new Outcome(FAILED, null);
            }
        }
    }

    private static Outcome done(Context c, String key, File got, File target) throws IOException {
        target.delete();
        if (!got.renameTo(target)) {
            throw new IOException("cannot move into place");
        }
        forget(c, key);
        return new Outcome(DONE, target);
    }

    /** Drops what the current source left and tries the next one, if any. */
    private static Outcome next(Context c, String key, JSONObject st) throws JSONException {
        litter(st);
        int src = st.getInt("src") + 1;
        if (src >= st.getJSONArray("sources").length()) {
            forget(c, key);
            return new Outcome(FAILED, null);
        }
        st.put("src", src);
        st.put("part", 0);
        st.put("done", 0L);
        enqueue(c, key, st);
        return new Outcome(WORKING, null);
    }

    // Plumbing.

    private static void enqueue(Context c, String key, JSONObject st) throws JSONException {
        JSONObject source = st.getJSONArray("sources").getJSONObject(st.getInt("src"));
        JSONArray parts = source.optJSONArray("parts");
        String url;
        File into;
        if (parts == null) {
            url = source.getString("url");
            into = whole(st);
        } else {
            int k = st.getInt("part");
            url = parts.getJSONObject(k).getString("url");
            into = part(st, k);
        }
        into.getParentFile().mkdirs();
        into.delete();
        String title = st.optString("name");
        if (parts != null) {
            title = title + " \u00b7 " + (st.getInt("part") + 1) + "/" + parts.length();
        }
        long id = dm(c).enqueue(new DownloadManager.Request(Uri.parse(url))
                .setTitle(title)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(st.optBoolean("any"))
                .setAllowedOverRoaming(false)
                .setDestinationUri(Uri.fromFile(into)));
        st.put("id", id);
        save(c, key, st);
    }

    private static File whole(JSONObject st) {
        return new File(st.optString("target") + ".download");
    }

    private static File part(JSONObject st, int k) {
        return new File(st.optString("target") + ".part" + k);
    }

    private static File joined(JSONObject st) {
        return new File(st.optString("target") + ".join");
    }

    /** Removes every leftover file of the current source. */
    private static void litter(JSONObject st) {
        whole(st).delete();
        joined(st).delete();
        for (int k = 0; k < 64; k++) {
            part(st, k).delete();
        }
    }

    private static void append(File from, File to) throws IOException {
        byte[] buf = new byte[1 << 20];
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to, true)) {
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
        }
    }

    private static DownloadManager dm(Context c) {
        return (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(STATE, Context.MODE_PRIVATE);
    }

    private static JSONObject load(Context c, String key) {
        String s = sp(c).getString(key, null);
        if (s == null) {
            return null;
        }
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
    }

    private static void save(Context c, String key, JSONObject st) {
        java.util.Set<String> keys = new java.util.HashSet<String>(sp(c).getStringSet(KEYS, new java.util.HashSet<String>()));
        keys.add(key);
        sp(c).edit().putString(key, st.toString()).putStringSet(KEYS, keys).commit();
    }

    private static void forget(Context c, String key) {
        java.util.Set<String> keys = new java.util.HashSet<String>(sp(c).getStringSet(KEYS, new java.util.HashSet<String>()));
        keys.remove(key);
        sp(c).edit().remove(key).putStringSet(KEYS, keys).commit();
    }
}
