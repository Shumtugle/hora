package com.shumtugle.hora;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A second language for the same voices, fetched separately. A pack holds
 * the parts of the speech model that differ between languages; the sound
 * codec and the voice samples are shared with the built-in language.
 *
 * Where a pack comes from is told by the list Fetch reads; this class only
 * knows what a pack holds and how to put it in place.
 */
final class LanguagePack {
    static final String ENGLISH = "en";

    /** Every file a pack must have; nothing else is unpacked. */
    static final List<String> FILES = Arrays.asList(
            "lm_main.onnx", "lm_flow.onnx", "text_conditioner.onnx", "vocab.json", "token_scores.json", "pack.json");
    private static final Set<String> EXTRA = new HashSet<String>(Arrays.asList("LICENSE.txt"));
    /** A pack is far below this; anything larger is not one. */
    private static final long MAX_FILE = 400L * 1024 * 1024;

    private LanguagePack() {
    }

    static File dir(Context c, String lang) {
        return new File(new File(c.getFilesDir(), "lang"), lang);
    }

    static boolean installed(Context c, String lang) {
        File d = dir(c, lang);
        for (String f : FILES) {
            if (!new File(d, f).isFile()) {
                return false;
            }
        }
        return true;
    }

    /** Bytes the pack takes on the phone; 0 when absent. */
    static long size(Context c, String lang) {
        long n = 0;
        File[] all = dir(c, lang).listFiles();
        if (all != null) {
            for (File f : all) {
                n += f.length();
            }
        }
        return n;
    }

    /** The pack's own settings, read from its pack.json; empty when absent or unreadable. */
    static JSONObject settings(Context c, String lang) {
        try {
            return new JSONObject(new String(read(new File(dir(c, lang), "pack.json")), "UTF-8"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static void remove(Context c, String lang) {
        delete(dir(c, lang));
    }

    static String key(String lang) {
        return "pack:" + lang;
    }

    /** Starts fetching a pack; the list says from where. Network: not on the main thread. */
    static void start(Context c, String lang, boolean anyNetwork) throws IOException {
        Fetch.start(c, key(lang), Fetch.entry(Fetch.index(c), "packs", "id", lang), zip(c, lang), anyNetwork);
    }

    /**
     * Moves a fetch on and unpacks it once the file is whole and checked.
     * Returns Fetch.DONE, WORKING, FAILED or NONE. Slow: not on the main thread.
     */
    static synchronized int finish(Context c, String lang) {
        Fetch.Outcome o = Fetch.step(c, key(lang));
        if (o.state != Fetch.DONE) {
            return o.state;
        }
        try (InputStream in = new FileInputStream(o.file)) {
            unpack(c, lang, in);
            return Fetch.DONE;
        } catch (IOException e) {
            Diag.log(c, "pack: unpack failed", e);
            return Fetch.FAILED;
        } finally {
            o.file.delete();
        }
    }

    /**
     * Unpacks a pack from a zip: only the known names, flat, into a fresh
     * folder that replaces the old one only when complete.
     */
    static void unpack(Context c, String lang, InputStream zipped) throws IOException {
        File fresh = new File(dir(c, lang).getPath() + ".new");
        delete(fresh);
        if (!fresh.mkdirs()) {
            throw new IOException("cannot make " + fresh);
        }
        try (ZipInputStream z = new ZipInputStream(zipped)) {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                int slash = name.lastIndexOf('/');
                name = slash < 0 ? name : name.substring(slash + 1);
                if (e.isDirectory() || !(FILES.contains(name) || EXTRA.contains(name))) {
                    continue;
                }
                long n = 0;
                try (OutputStream out = new FileOutputStream(new File(fresh, name))) {
                    int r;
                    while ((r = z.read(buf)) > 0) {
                        n += r;
                        if (n > MAX_FILE) {
                            throw new IOException("too large: " + name);
                        }
                        out.write(buf, 0, r);
                    }
                }
            }
        }
        for (String f : FILES) {
            if (!new File(fresh, f).isFile()) {
                delete(fresh);
                throw new IOException("missing " + f);
            }
        }
        String said;
        try {
            said = new JSONObject(new String(read(new File(fresh, "pack.json")), "UTF-8")).optString("language");
        } catch (org.json.JSONException ex) {
            said = "";
        }
        if (!lang.equals(said)) {
            delete(fresh);
            throw new IOException("not a pack for " + lang);
        }
        File old = dir(c, lang);
        delete(old);
        if (!fresh.renameTo(old)) {
            delete(fresh);
            throw new IOException("cannot move into place");
        }
    }

    private static File zip(Context c, String lang) {
        File d = c.getExternalFilesDir("packs");
        if (d == null) {
            d = new File(c.getCacheDir(), "packs");
        }
        d.mkdirs();
        return new File(d, lang + ".zip");
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) {
                md.update(buf, 0, r);
            }
            StringBuilder s = new StringBuilder();
            for (byte b : md.digest()) {
                s.append(String.format(java.util.Locale.ROOT, "%02x", b));
            }
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) {
            b.write(buf, 0, r);
        }
        return b.toByteArray();
    }

    private static void delete(File f) {
        File[] in = f.listFiles();
        if (in != null) {
            for (File x : in) {
                delete(x);
            }
        }
        f.delete();
    }
}
