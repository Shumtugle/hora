package com.shumtugle.hora;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The built-in demo book. It stands on the shelf like any other, with its own
 * cover, marked as an example from the app, until it is taken off; it comes back
 * from "Add a book". Any voice may read it in place of the narrator.
 */
final class Demo {
    private static final String ASSET = "demo/book.txt";
    private static final String PREFS = "demo";
    private static final String VOICE = "voice";

    private Demo() {
    }

    /** The book, copied once out of the app so it can be opened like a file. */
    static File book(Context c) throws IOException {
        File f = new File(new File(c.getFilesDir(), "demo"), "book.txt");
        if (!f.isFile() || f.length() != size(c)) {
            f.getParentFile().mkdirs();
            try (InputStream in = c.getAssets().open(ASSET); OutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                }
            }
        }
        return f;
    }

    private static long size(Context c) {
        try (InputStream in = c.getAssets().open(ASSET)) {
            long n = 0;
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) {
                n += r;
            }
            return n;
        } catch (IOException e) {
            return -1;
        }
    }

    private static final String COVER = "demo/cover.jpg";
    /** The demo's author, as the person who made the app named it. */
    static final String AUTHOR = "Hora";

    /** The demo's cover as it ships, or null if it cannot be read. */
    static byte[] cover(Context c) {
        try (InputStream in = c.getAssets().open(COVER)) {
            return LanguagePack.readAll(in);
        } catch (IOException e) {
            return null;
        }
    }

    /** The cover as a picture, scaled down to about the given width; null if unreadable. A few sizes are kept. */
    private static final java.util.Map<Integer, android.graphics.Bitmap> shown =
            new java.util.LinkedHashMap<Integer, android.graphics.Bitmap>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Integer, android.graphics.Bitmap> e) {
                    return size() > 3;
                }
            };

    static synchronized android.graphics.Bitmap coverBitmap(Context c, int width) {
        // Sizes close to each other share one picture: the decoder only halves anyway.
        int key = Math.max(1, 800 / Math.max(1, width));
        android.graphics.Bitmap b = shown.get(key);
        if (b == null) {
            b = decode(c, width);
            if (b != null) {
                shown.put(key, b);
            }
        }
        return b;
    }

    private static android.graphics.Bitmap decode(Context c, int width) {
        byte[] b = cover(c);
        if (b == null) {
            return null;
        }
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inSampleSize = Math.max(1, 800 / Math.max(1, width));
        return android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length, o);
    }

    private static String title;

    /** The demo's title, its first line without the closing full stop. */
    static synchronized String title(Context c) {
        if (title == null) {
            title = "";
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    c.getAssets().open(ASSET), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        title = line.endsWith(".") ? line.substring(0, line.length() - 1) : line;
                        break;
                    }
                }
            } catch (IOException e) {
                title = "";
            }
        }
        return title;
    }

    static String uri(Context c) {
        return Uri.fromFile(new File(new File(c.getFilesDir(), "demo"), "book.txt")).toString();
    }

    /** Whether a book address is the demo's. */
    static boolean is(Context c, String uri) {
        return uri != null && uri.equals(uri(c));
    }

    /** The voice the demo is read with, in place of the narrator; 0 when none was chosen. */
    @SuppressWarnings("deprecation")
    static int voice(Context c) {
        int v = c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS).getInt(VOICE, 0);
        // A voice that left the row (the ninth, when the developer section closes) gives the book back.
        return v > 0 && Cast.available(c, v) ? v : 0;
    }

    @SuppressWarnings("deprecation")
    static void setVoice(Context c, int voice) {
        c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS).edit().putInt(VOICE, voice).commit();
        BookPlayer.revoice(c);
    }

    private static final String SHELVED = "shelved";

    /** Whether the demo stands on the shelf; it does until it is taken off. */
    @SuppressWarnings("deprecation")
    static boolean shelved(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS).getBoolean(SHELVED, true);
    }

    @SuppressWarnings("deprecation")
    static void setShelved(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS).edit().putBoolean(SHELVED, on).commit();
        Diag.mark(c, on ? "demo: back on the shelf" : "demo: taken off the shelf");
    }

    /** Whether a book's place and reading are kept in the library: every book's, and the demo's while shelved. */
    static boolean kept(Context c, String uri) {
        return !is(c, uri) || shelved(c);
    }

    /** Puts the demo on the shelf if it should stand there and does not yet. Slow: not on the main thread. */
    static void ensureOnShelf(Context c) {
        if (!shelved(c) || Library.get(c, uri(c)) != null) {
            return;
        }
        try {
            book(c);
            Book b = Export.load(c, uri(c), "book.txt");
            Library.found(c, uri(c), "book.txt", b);
            Diag.mark(c, "demo: on the shelf");
        } catch (Exception e) {
            Diag.log(c, "demo: not put on the shelf", e);
        }
    }

    /** Where earlier builds kept trial samples taken from Hora's folder. */
    private static final String OLD_SAMPLES = "beta";

    /**
     * The trial voices now ship with the app. Samples that earlier builds took
     * from the folder are removed once, and the journal says so.
     */
    static void forgetOldSamples(Context c) {
        File dir = new File(c.getFilesDir(), OLD_SAMPLES);
        File[] all = dir.listFiles();
        if (all == null) {
            return;
        }
        int gone = 0;
        for (File f : all) {
            if (f.delete()) {
                gone++;
            }
        }
        dir.delete();
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("samples").commit();
        Diag.mark(c, "demo: old trial samples removed, " + gone);
    }
}
