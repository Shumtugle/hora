package com.shumtugle.hora;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The built-in demo book. It is read like any other, but it is not kept on the
 * shelf; a trial voice may read it.
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

    /** The cover as a picture, scaled down to about the given width; null if unreadable. */
    private static android.graphics.Bitmap shown;
    private static int shownWidth;

    static synchronized android.graphics.Bitmap coverBitmap(Context c, int width) {
        if (shown != null && shownWidth == width) {
            return shown;
        }
        shownWidth = width;
        shown = decode(c, width);
        return shown;
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

    static String uri(Context c) {
        return Uri.fromFile(new File(new File(c.getFilesDir(), "demo"), "book.txt")).toString();
    }

    /** Whether a book address is the demo's. */
    static boolean is(Context c, String uri) {
        return uri != null && uri.equals(uri(c));
    }

    /** The trial voice the demo is read with; 0 when none was chosen. */
    @SuppressWarnings("deprecation")
    static int voice(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS).getInt(VOICE, 0);
    }

    @SuppressWarnings("deprecation")
    static void setVoice(Context c, int voice) {
        c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS).edit().putInt(VOICE, voice).commit();
        BookPlayer.revoice(c);
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
