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
 * Parts of the speech model fetched separately from the app. The native pack
 * is the voice itself: the speech model with its sound codec, without which
 * the voices are silent. A language pack holds the parts that differ for
 * another language and shares the native codec. The full decoder is an
 * optional, larger codec for the same voice. The voice samples stay in the
 * app and are shared by all.
 *
 * Where a pack comes from is told by the list Fetch reads; this class only
 * knows what a pack holds and how to put it in place.
 */
final class LanguagePack {
    static final String NATIVE = "ru";
    static final String ENGLISH = "en";
    static final String DECODER = "decoder";

    /** Every file a language pack must have; nothing else is unpacked. */
    static final List<String> FILES = Arrays.asList(
            "lm_main.onnx", "lm_flow.onnx", "text_conditioner.onnx", "vocab.json", "token_scores.json", "pack.json");
    /** The native pack: a language pack plus the sound codec. */
    static final List<String> NATIVE_FILES = Arrays.asList(
            "lm_main.onnx", "lm_flow.onnx", "text_conditioner.onnx", "vocab.json", "token_scores.json",
            "encoder.onnx", "decoder.onnx", "pack.json");
    static final List<String> DECODER_FILES = Arrays.asList("decoder_full.onnx", "pack.json");
    private static final Set<String> EXTRA = new HashSet<String>(Arrays.asList("LICENSE.txt"));
    /** A pack is far below this; anything larger is not one. */
    private static final long MAX_FILE = 400L * 1024 * 1024;

    private LanguagePack() {
    }

    static File dir(Context c, String lang) {
        return new File(new File(c.getFilesDir(), "lang"), lang);
    }

    static List<String> files(String id) {
        return NATIVE.equals(id) ? NATIVE_FILES : DECODER.equals(id) ? DECODER_FILES : FILES;
    }

    static boolean installed(Context c, String lang) {
        File d = dir(c, lang);
        for (String f : files(lang)) {
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
        clearLeftovers(c, lang);
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
        File fresh = work(c, lang);
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
                if (e.isDirectory() || !(files(lang).contains(name) || EXTRA.contains(name))) {
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
        for (String f : files(lang)) {
            if (!new File(fresh, f).isFile()) {
                delete(fresh);
                throw new IOException("missing " + f);
            }
        }
        String said;
        try {
            JSONObject j = new JSONObject(new String(read(new File(fresh, "pack.json")), "UTF-8"));
            said = j.optString("id", j.optString("language"));
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

    /**
     * Builds before the voice became a pack unpacked the model from the app into
     * the samples folder. Those files are moved into the packs as they are, so
     * an update keeps its voice without a download. Fast: renames only.
     */
    static synchronized void adopt(Context c, File old) {
        try {
            if (!installed(c, NATIVE) && new File(old, "lm_main.onnx").isFile()) {
                if (move(c, old, NATIVE)) {
                    Diag.mark(c, "voice: the model from the app kept as the voice pack");
                }
            }
            if (!installed(c, DECODER) && new File(old, "decoder_full.onnx").isFile()) {
                move(c, old, DECODER);
            }
        } catch (IOException e) {
            Diag.log(c, "voice: the model from the app could not be kept", e);
        }
    }

    private static boolean move(Context c, File old, String id) throws IOException {
        File fresh = work(c, id);
        delete(fresh);
        if (!fresh.mkdirs()) {
            throw new IOException("cannot make " + fresh);
        }
        // The description is written first: if even that fails, nothing has moved yet.
        try (OutputStream out = new FileOutputStream(new File(fresh, "pack.json"))) {
            out.write(("{\"id\": \"" + id + "\", \"language\": \"" + (DECODER.equals(id) ? "" : id)
                    + "\", \"version\": 1}").getBytes("UTF-8"));
        } catch (IOException e) {
            delete(fresh);
            throw e;
        }
        java.util.List<String> moved = new java.util.ArrayList<String>();
        boolean whole = true;
        for (String f : files(id)) {
            if ("pack.json".equals(f)) {
                continue;
            }
            if (!new File(old, f).renameTo(new File(fresh, f))) {
                whole = false;
                break;
            }
            moved.add(f);
        }
        if (whole) {
            delete(dir(c, id));
            whole = fresh.renameTo(dir(c, id));
        }
        if (!whole) {
            // Everything goes back where it was: the old copy stays whole and keeps working.
            for (String m : moved) {
                new File(fresh, m).renameTo(new File(old, m));
            }
            delete(fresh);
        }
        return whole;
    }

    /** A folder of this process for a pack being put together; replaces the pack only when complete. */
    private static File work(Context c, String id) {
        clearLeftovers(c, id);
        return new File(dir(c, id).getPath() + ".new" + android.os.Process.myPid());
    }

    /** Half-made folders of processes that are gone (killed mid-way) are removed; live ones are left alone. */
    private static void clearLeftovers(Context c, String id) {
        String stem = dir(c, id).getName() + ".new";
        File[] all = dir(c, id).getParentFile().listFiles();
        if (all == null) {
            return;
        }
        for (File f : all) {
            String n = f.getName();
            if (!n.startsWith(stem)) {
                continue;
            }
            String pid = n.substring(stem.length());
            if (pid.isEmpty() || !pid.matches("\\d+") || !new File("/proc/" + pid).exists()) {
                delete(f);
            }
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
