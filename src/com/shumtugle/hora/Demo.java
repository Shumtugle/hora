package com.shumtugle.hora;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The built-in demo book and the two trial voices that read it. The book is
 * read like any other, but it is not kept on the shelf. The trial voices are
 * not part of the app: their samples are put into Hora's folder by hand, and
 * only this book is ever read with them.
 */
final class Demo {
    private static final String ASSET = "demo/book.txt";
    private static final String PREFS = "demo";
    private static final String VOICE = "voice";
    /** Sample names in the samples folder of Hora's folder. */
    private static final String[] NAMES = {"beta-f.wav", "beta-m.wav"};

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
    }

    /** Where a trial voice's sample lives inside the app once taken from the folder. */
    static File sample(Context c, int voice) {
        return new File(new File(c.getFilesDir(), "beta"), NAMES[voice == Cast.BETA_M ? 1 : 0]);
    }

    /** Whether both trial samples are on the phone. */
    static boolean ready(Context c) {
        return sample(c, Cast.BETA_F).isFile() && sample(c, Cast.BETA_M).isFile();
    }

    /**
     * Takes the trial samples from the samples folder of Hora's folder, when they
     * are there and differ from the copies already taken. Slow: not on the main thread.
     */
    static void take(Context c) {
        Uri dir = HoraFolder.findSub(c, c.getString(R.string.folder_samples));
        if (dir == null) {
            return;
        }
        for (int i = 0; i < NAMES.length; i++) {
            Uri u = HoraFolder.find(c, dir, NAMES[i]);
            if (u == null) {
                continue;
            }
            File to = sample(c, i == 0 ? Cast.BETA_F : Cast.BETA_M);
            try (InputStream in = c.getContentResolver().openInputStream(u)) {
                if (in == null) {
                    continue;
                }
                to.getParentFile().mkdirs();
                File part = new File(to.getPath() + ".part");
                try (OutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                    }
                }
                if (part.length() != to.length() && part.renameTo(to)) {
                    Diag.mark(c, "demo: trial sample " + (i + 1) + " taken");
                }
                part.delete();
            } catch (IOException | SecurityException e) {
                Diag.log(c, "demo: trial sample not taken", e);
            }
        }
    }

    /** Clears what the app keeps of the trial voices. */
    static void forget(Context c) {
        sample(c, Cast.BETA_F).delete();
        sample(c, Cast.BETA_M).delete();
        setVoice(c, 0);
    }
}
