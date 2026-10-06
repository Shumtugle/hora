package com.shumtugle.hora;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Hora moving to another phone. Everything that is the user's own and small
 * (settings of the voices, the bot, the model and the look; the shelf with
 * every book's place; the stress dictionary) is kept in one archive in
 * Hora's folder, rewritten a little after any change.
 * Books and models are large and live in their own folders beside it.
 *
 * On a new phone, once shown the folder (or handed the archive), Hora offers
 * to bring it all back. Books are matched by file name, since a file's
 * address differs from phone to phone while its name does not. Nothing of
 * other people goes into the archive: no notifications, no talks.
 */
final class Transfer {
    static final int VERSION = 1;
    static final String ENDING = ".hora";
    /** Settings stores that travel; the folder grant and the model's file names do not. */
    private static final String[] STORES = {"settings", "hora", "reading", "weather"};
    private static final Set<String> LOCAL = new HashSet<String>();

    static {
        LOCAL.add("hora_folder");
        LOCAL.add("intro_seen");
    }

    private static final long SOON_MS = 60_000L;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Runnable pending;

    private Transfer() {
    }

    static String fileName(Context c) {
        return c.getString(R.string.folder_root) + ENDING;
    }

    /** Asks for the archive to be rewritten a minute from now; later asks within that minute join it. */
    static synchronized void soon(final Context c) {
        if (pending != null) {
            return;
        }
        final Context app = c.getApplicationContext();
        pending = new Runnable() {
            @Override
            public void run() {
                synchronized (Transfer.class) {
                    pending = null;
                }
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            save(app);
                        } catch (IOException | RuntimeException e) {
                            Diag.log(app, "transfer: not saved", e);
                        }
                    }
                }, "transfer-save").start();
            }
        };
        main.postDelayed(pending, SOON_MS);
    }

    // Saving.

    /** Writes the archive into Hora's folder now. Does nothing until the folder is known. */
    static void save(Context c) throws IOException {
        if (HoraFolder.tree(c) == null) {
            return;
        }
        // One writer at a time across Hora's processes.
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(c.getFilesDir(), "transfer.lock"), "rw");
             FileLock held = lockFile.getChannel().lock()) {
            Uri file = HoraFolder.rootFile(c, fileName(c));
            try (OutputStream out = c.getContentResolver().openOutputStream(file, "wt")) {
                if (out == null) {
                    throw new IOException("cannot write");
                }
                write(c, out);
            }
            c.getSharedPreferences("hora", Context.MODE_PRIVATE).edit()
                    .putLong("transfer_saved", System.currentTimeMillis()).apply();
        }
    }

    static long savedAt(Context c) {
        return c.getSharedPreferences("hora", Context.MODE_PRIVATE).getLong("transfer_saved", 0);
    }

    private static void write(Context c, OutputStream raw) throws IOException {
        JSONObject root = new JSONObject();
        try {
            root.put("version", VERSION);
            root.put("made", System.currentTimeMillis());
            JSONObject stores = new JSONObject();
            for (String name : STORES) {
                stores.put(name, store(c.getSharedPreferences(name, Context.MODE_MULTI_PROCESS)));
            }
            root.put("stores", stores);
            // Books by name: the address is this phone's own.
            JSONArray shelf = new JSONArray();
            for (Library.Entry e : Library.all(c)) {
                JSONObject b = new JSONObject();
                b.put("name", e.name);
                b.put("index", e.index);
                b.put("reader", e.reader);
                b.put("opened", e.opened);
                b.put("temporary", Books.isTemporary(c, e.uri));
                b.put("current", e.uri.equals(Reading.uri(c)));
                shelf.put(b);
            }
            root.put("shelf", shelf);
            // What this phone had given or held that a new phone must be asked for again.
            JSONObject had = new JSONObject();
            had.put("engine", defaultEngine(c));
            had.put("model", Brain.get(c).hasModel());
            had.put("english", LanguagePack.installed(c, LanguagePack.ENGLISH));
            root.put("had", had);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        try (ZipOutputStream z = new ZipOutputStream(raw)) {
            z.putNextEntry(new ZipEntry("settings.json"));
            z.write(root.toString(1).getBytes("UTF-8"));
            z.closeEntry();
            put(z, Lexicon.file(c), "lexicon.txt");
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    private static void put(ZipOutputStream z, File f, String name) throws IOException {
        if (!f.isFile()) {
            return;
        }
        z.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(f)) {
            copy(in, z);
        }
        z.closeEntry();
    }

    /** A settings store as JSON, each value with its type, so it comes back as the same kind. */
    private static JSONObject store(SharedPreferences sp) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (LOCAL.contains(e.getKey()) || e.getKey().startsWith("brain_name_") || e.getKey().startsWith("at_")
                    || e.getKey().startsWith("transfer_")) {
                continue;
            }
            Object v = e.getValue();
            JSONObject t = new JSONObject();
            if (v instanceof Boolean) {
                t.put("b", v);
            } else if (v instanceof Integer) {
                t.put("i", v);
            } else if (v instanceof Long) {
                t.put("l", v);
            } else if (v instanceof Float) {
                t.put("f", ((Float) v).doubleValue());
            } else if (v instanceof String) {
                t.put("s", v);
            } else if (v instanceof Set) {
                t.put("ss", new JSONArray((Set<?>) v));
            } else {
                continue;
            }
            o.put(e.getKey(), t);
        }
        return o;
    }

    // Reading back.

    /** What an archive holds, for the question before restoring. */
    static final class Summary {
        long made;
        int books;
        boolean lexicon;
        JSONObject settings;
        final Map<String, byte[]> files = new HashMap<String, byte[]>();
    }

    static Summary read(Context c, Uri from) throws IOException {
        Summary s = new Summary();
        try (InputStream raw = c.getContentResolver().openInputStream(from);
             ZipInputStream z = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                if (n.contains("..")) {
                    continue;
                }
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                copy(z, b);
                if (n.equals("settings.json")) {
                    s.settings = new JSONObject(new String(b.toByteArray(), "UTF-8"));
                } else if (n.equals("lexicon.txt")) {
                    s.lexicon = true;
                    s.files.put(n, b.toByteArray());
                }
            }
        } catch (JSONException ex) {
            throw new IOException("not a Hora archive: " + ex.getMessage());
        }
        if (s.settings == null) {
            throw new IOException("not a Hora archive");
        }
        s.made = s.settings.optLong("made");
        JSONArray shelf = s.settings.optJSONArray("shelf");
        s.books = shelf == null ? 0 : shelf.length();
        return s;
    }

    /**
     * Brings an archive back: settings, dictionary, and the shelf matched by file name
     * against the books now in Hora's folder. Slow: not on the main thread.
     */
    static void restore(Context c, Summary s) throws IOException {
        try {
            JSONObject stores = s.settings.getJSONObject("stores");
            for (String name : STORES) {
                JSONObject o = stores.optJSONObject(name);
                if (o != null) {
                    putStore(c.getSharedPreferences(name, Context.MODE_MULTI_PROCESS), o);
                }
            }
            for (Map.Entry<String, byte[]> f : s.files.entrySet()) {
                if (!f.getKey().equals("lexicon.txt")) {
                    continue;
                }
                File to = Lexicon.file(c);
                to.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(to)) {
                    out.write(f.getValue());
                }
            }
            // The shelf: each book is looked for by name in the books, then in the temporary ones.
            Books.moveOut(c);
            Books.tidy(c);
            Map<String, String> here = Books.byName(c);
            JSONArray shelf = s.settings.optJSONArray("shelf");
            int found = 0;
            for (int i = 0; shelf != null && i < shelf.length(); i++) {
                JSONObject b = shelf.getJSONObject(i);
                String uri = here.get(b.optString("name"));
                if (uri == null) {
                    continue;
                }
                found++;
                Library.restored(c, uri, b.optInt("index"), b.optInt("reader"), b.optLong("opened"));
                Reading.setSavedIndex(c, uri, b.optInt("index"));
                if (b.optBoolean("current")) {
                    Reading.restoreCurrent(c, uri, b.optString("name"));
                }
            }
            JSONObject had = s.settings.optJSONObject("had");
            c.getSharedPreferences("hora", Context.MODE_PRIVATE).edit()
                    .putString(CHECK, had == null ? "{}" : had.toString()).commit();
            Diag.mark(c, "transfer: restored, " + found + " of " + (shelf == null ? 0 : shelf.length()) + " books found");
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    private static void putStore(SharedPreferences sp, JSONObject o) throws JSONException {
        SharedPreferences.Editor ed = sp.edit();
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (LOCAL.contains(k)) {
                continue;
            }
            JSONObject t = o.getJSONObject(k);
            if (t.has("b")) {
                ed.putBoolean(k, t.getBoolean("b"));
            } else if (t.has("i")) {
                ed.putInt(k, t.getInt("i"));
            } else if (t.has("l")) {
                ed.putLong(k, t.getLong("l"));
            } else if (t.has("f")) {
                ed.putFloat(k, (float) t.getDouble("f"));
            } else if (t.has("s")) {
                ed.putString(k, t.getString("s"));
            } else if (t.has("ss")) {
                JSONArray a = t.getJSONArray("ss");
                Set<String> set = new HashSet<String>();
                for (int i = 0; i < a.length(); i++) {
                    set.add(a.getString(i));
                }
                ed.putStringSet(k, set);
            }
        }
        ed.commit();
    }

    /** Set after a restore: what the old phone had that this one still has to give, until seen. */
    static final String CHECK = "transfer_check";

    /** Whether Hora is the phone's speech engine for other apps. */
    static boolean defaultEngine(Context c) {
        String engine = android.provider.Settings.Secure.getString(c.getContentResolver(), "tts_default_synth");
        return c.getPackageName().equals(engine);
    }

    /** What still waits on this phone after a restore, or null when nothing does. */
    static JSONObject pendingCheck(Context c) {
        String s = c.getSharedPreferences("hora", Context.MODE_PRIVATE).getString(CHECK, null);
        if (s == null) {
            return null;
        }
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
    }

    static void checkSeen(Context c) {
        c.getSharedPreferences("hora", Context.MODE_PRIVATE).edit().remove(CHECK).apply();
    }

    /** The archive in Hora's folder, if there is one. */
    static Uri inFolder(Context c) {
        return HoraFolder.tree(c) == null ? null : HoraFolder.findRoot(c, fileName(c));
    }

    /** Whether this install is new enough that bringing settings back cannot overwrite anything of value. */
    static boolean fresh(Context c) {
        return Library.all(c).isEmpty() && savedAt(c) == 0;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int r;
        while ((r = in.read(buf)) > 0) {
            out.write(buf, 0, r);
        }
    }

    static boolean isArchive(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(ENDING);
    }
}
