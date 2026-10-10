package com.shumtugle.hora;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The built-in demo book and the two trial voices. The book is read like any
 * other, but it is not kept on the shelf. The trial voices are not part of the
 * app: their samples are put into Hora's folder by hand, and only the two known
 * recordings are accepted.
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
    static final int SAMPLES_NONE = 0;
    static final int SAMPLES_READY = 1;
    static final int SAMPLES_WRONG = 2;
    static final int SAMPLES_HALF = 3;

    /**
     * Fingerprints of the only two samples accepted. Any other recording, whatever
     * its name, is refused: the trial voices are these two and cannot be swapped
     * for someone else's voice.
     */
    private static final String[] PRINTS = {
        "5f7310354d09e52f9670771dcf9f9413f60261052145ed91700732f3551d6e1c",
        "3cb1fc972b43550fe810bc403520ccced72a49b1f23be4227d81afb6903ebdb7"};

    /** What the last look into the folder found. */
    @SuppressWarnings("deprecation")
    static int samples(Context c) {
        if (ready(c)) {
            return SAMPLES_READY;
        }
        return c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS).getInt("samples", SAMPLES_NONE);
    }

    static int take(Context c) {
        if (HoraFolder.tree(c) == null) {
            return samples(c);
        }
        Uri dir = HoraFolder.findSub(c, c.getString(R.string.folder_samples));
        boolean wrong = false;
        for (int i = 0; i < NAMES.length; i++) {
            File to = sample(c, i == 0 ? Cast.BETA_F : Cast.BETA_M);
            if (to.isFile()) {
                // A copy kept by an earlier build that did not check: kept only if it is the known one.
                try {
                    if (PRINTS[i].equals(LanguagePack.sha256(to))) {
                        continue;
                    }
                } catch (IOException e) {
                    // Unreadable: taken again below.
                }
                to.delete();
            }
            // In the samples folder, or at the top of Hora's folder; a copy renamed by the phone counts too.
            Uri u = dir == null ? null : like(c, dir, NAMES[i]);
            if (u == null) {
                u = like(c, null, NAMES[i]);
            }
            if (u == null) {
                continue;
            }
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
                if (PRINTS[i].equals(LanguagePack.sha256(part)) && part.renameTo(to)) {
                    Diag.mark(c, "demo: trial sample " + (i + 1) + " taken");
                } else {
                    wrong = true;
                    Diag.mark(c, "demo: trial sample " + (i + 1) + " refused, not the known recording");
                }
                part.delete();
            } catch (IOException | SecurityException e) {
                Diag.log(c, "demo: trial sample not taken", e);
            }
        }
        int state = ready(c) ? SAMPLES_READY : wrong ? SAMPLES_WRONG
                : (sample(c, Cast.BETA_F).isFile() || sample(c, Cast.BETA_M).isFile()) ? SAMPLES_HALF : SAMPLES_NONE;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("samples", state).commit();
        return state;
    }

    /** A file whose name starts like the given one and ends the same, in a subfolder or at the top. */
    private static Uri like(Context c, Uri dir, String name) {
        Uri exact = dir == null ? HoraFolder.findRoot(c, name) : HoraFolder.find(c, dir, name);
        if (exact != null || dir == null) {
            return exact;
        }
        String stem = name.substring(0, name.lastIndexOf('.'));
        for (String[] f : HoraFolder.list(c, dir)) {
            if (f[1] != null && f[1].startsWith(stem) && f[1].endsWith(".wav")) {
                return Uri.parse(f[0]);
            }
        }
        return null;
    }

    /** Clears what the app keeps of the trial voices. */
    static void forget(Context c) {
        sample(c, Cast.BETA_F).delete();
        sample(c, Cast.BETA_M).delete();
        setVoice(c, 0);
    }
}
