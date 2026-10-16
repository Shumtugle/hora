package com.shumtugle.hora;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The built-in demo books. Each stands on the shelf like any other, with its own
 * cover, marked as an example from the app, until it is taken off; it comes back
 * from "Add a book". One of them is the chosen one, read when a voice is listened
 * to; any voice may read the demos in place of the narrator.
 */
final class Demo {
    /** The demos as they ship: text and cover, by number. */
    private static final String[] TEXTS = {"demo/book.txt"};
    private static final String[] COVERS = {"demo/cover.jpg"};
    /** The file each is copied to, and the name it goes by on the shelf. */
    private static final String[] FILES = {"book.txt"};
    static final int COUNT = TEXTS.length;
    private static final String PREFS = "demo";
    private static final String VOICE = "voice";
    private static final String CHOSEN = "chosen";
    /** Whether the first demo stands on the shelf; later ones have keys of their own. */
    private static final String SHELVED = "shelved";

    private Demo() {
    }

    /** The chosen demo's number. */
    @SuppressWarnings("deprecation")
    static int chosen(Context c) {
        int i = c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS).getInt(CHOSEN, 0);
        return i >= 0 && i < COUNT ? i : 0;
    }

    @SuppressWarnings("deprecation")
    static void setChosen(Context c, int i) {
        c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS).edit()
                .putInt(CHOSEN, Math.max(0, Math.min(COUNT - 1, i))).commit();
        Diag.mark(c, "demo: chosen " + i);
    }

    /** The chosen demo, copied once out of the app so it can be opened like a file. */
    static File book(Context c) throws IOException {
        return book(c, chosen(c));
    }

    static File book(Context c, int i) throws IOException {
        File f = file(c, i);
        if (!f.isFile() || f.length() != size(c, i)) {
            f.getParentFile().mkdirs();
            try (InputStream in = c.getAssets().open(TEXTS[i]); OutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                }
            }
        }
        return f;
    }

    private static File file(Context c, int i) {
        return new File(new File(c.getFilesDir(), "demo"), FILES[i]);
    }

    /** The name a demo goes by on the shelf. */
    static String name(int i) {
        return FILES[i];
    }

    private static long size(Context c, int i) {
        try (InputStream in = c.getAssets().open(TEXTS[i])) {
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

    /** The demos' author: the app, by its name in the language of the screen. */
    static String author(Context c) {
        return c.getString(R.string.app_name);
    }

    /** A demo's cover as it ships, or null if it cannot be read or the address is no demo's. */
    static byte[] cover(Context c, String uri) {
        int i = which(c, uri);
        if (i < 0) {
            return null;
        }
        try (InputStream in = c.getAssets().open(COVERS[i])) {
            return LanguagePack.readAll(in);
        } catch (IOException e) {
            return null;
        }
    }

    /** The cover as a picture, scaled down to about the given width; null if unreadable. A few are kept. */
    private static final java.util.Map<String, android.graphics.Bitmap> shown =
            new java.util.LinkedHashMap<String, android.graphics.Bitmap>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, android.graphics.Bitmap> e) {
                    return size() > 4;
                }
            };

    static synchronized android.graphics.Bitmap coverBitmap(Context c, String uri, int width) {
        int i = which(c, uri);
        if (i < 0) {
            return null;
        }
        // Sizes close to each other share one picture: the decoder only halves anyway.
        int sample = Math.max(1, 800 / Math.max(1, width));
        String key = i + "/" + sample;
        android.graphics.Bitmap b = shown.get(key);
        if (b == null) {
            byte[] bytes = cover(c, uri);
            if (bytes != null) {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = sample;
                b = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            }
            if (b != null) {
                shown.put(key, b);
            }
        }
        return b;
    }

    private static final String[] titles = new String[COUNT];

    /** The chosen demo's title. */
    static String title(Context c) {
        return title(c, chosen(c));
    }

    /** A demo's title, its first line without the closing full stop. */
    static synchronized String title(Context c, int i) {
        if (titles[i] == null) {
            titles[i] = "";
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    c.getAssets().open(TEXTS[i]), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        titles[i] = line.endsWith(".") ? line.substring(0, line.length() - 1) : line;
                        break;
                    }
                }
            } catch (IOException e) {
                titles[i] = "";
            }
        }
        return titles[i];
    }

    /**
     * Gives a book read from a demo's file the demo's title: a story without chapters would
     * otherwise go by its file's name. Other books are left as they are.
     */
    static void titled(Context c, String uri, Book b) {
        int i = which(c, uri);
        if (i >= 0 && b != null && !title(c, i).isEmpty()) {
            b.title = title(c, i);
        }
    }

    /** The chosen demo's address. */
    static String uri(Context c) {
        return uri(c, chosen(c));
    }

    static String uri(Context c, int i) {
        return Uri.fromFile(file(c, i)).toString();
    }

    /** Which demo a book address is: its number, or -1 for a book that is no demo. */
    static int which(Context c, String uri) {
        if (uri == null) {
            return -1;
        }
        for (int i = 0; i < COUNT; i++) {
            if (uri.equals(uri(c, i))) {
                return i;
            }
        }
        return -1;
    }

    /** Whether a book address is a demo's. */
    static boolean is(Context c, String uri) {
        return which(c, uri) >= 0;
    }

    /** The voice the demos are read with, in place of the narrator; 0 when none was chosen. */
    @SuppressWarnings("deprecation")
    static int voice(Context c) {
        int v = c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS).getInt(VOICE, 0);
        // A voice that left the troupe gives the book back.
        return v > 0 && Cast.available(c, v) ? v : 0;
    }

    @SuppressWarnings("deprecation")
    static void setVoice(Context c, int voice) {
        android.content.SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS);
        if (sp.getInt(VOICE, 0) == voice) {
            // The same reader again: what was made ahead is still right, and the place after a pause stands.
            return;
        }
        sp.edit().putInt(VOICE, voice).commit();
        BookPlayer.revoice(c);
    }

    private static String shelvedKey(int i) {
        return i == 0 ? SHELVED : SHELVED + "_" + i;
    }

    /** Whether a demo stands on the shelf; each does until it is taken off. */
    @SuppressWarnings("deprecation")
    static boolean shelved(Context c, int i) {
        return c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS).getBoolean(shelvedKey(i), true);
    }

    /** Whether the demo at this address stands on the shelf; false for a book that is no demo. */
    static boolean shelved(Context c, String uri) {
        int i = which(c, uri);
        return i >= 0 && shelved(c, i);
    }

    /** Whether every demo stands on the shelf. */
    static boolean allShelved(Context c) {
        for (int i = 0; i < COUNT; i++) {
            if (!shelved(c, i)) {
                return false;
            }
        }
        return true;
    }

    /** Whether some demo stands on the shelf. */
    static boolean anyShelved(Context c) {
        for (int i = 0; i < COUNT; i++) {
            if (shelved(c, i)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    static void setShelved(Context c, int i, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS).edit().putBoolean(shelvedKey(i), on).commit();
        Diag.mark(c, on ? "demo " + i + ": back on the shelf" : "demo " + i + ": taken off the shelf");
    }

    /** Whether a book's place and reading are kept in the library: every book's, and a demo's while shelved. */
    static boolean kept(Context c, String uri) {
        int i = which(c, uri);
        return i < 0 || shelved(c, i);
    }

    /** Puts every demo on the shelf that should stand there and does not yet. Slow: not on the main thread. */
    static void ensureOnShelf(Context c) {
        for (int i = 0; i < COUNT; i++) {
            if (!shelved(c, i)) {
                continue;
            }
            if (Library.get(c, uri(c, i)) != null) {
                // An earlier build may have shelved it under its file's name.
                if (!title(c, i).isEmpty()) {
                    Library.retitle(c, uri(c, i), title(c, i));
                }
                continue;
            }
            try {
                book(c, i);
                Book b = Export.load(c, uri(c, i), FILES[i]);
                Library.found(c, uri(c, i), FILES[i], b);
                Diag.mark(c, "demo " + i + ": on the shelf");
            } catch (Exception e) {
                Diag.log(c, "demo " + i + ": not put on the shelf", e);
            }
        }
    }

    /**
     * Demos earlier builds shipped and this one does not: the file each was copied to and the key
     * that said it stood on the shelf. Only ever appended to; a step that would touch the file or
     * key of a demo that ships now is skipped.
     */
    private static final String[][] RETIRED = {{"book2.txt", SHELVED + "_1"}};
    private static final String RETIRED_DONE = "retired";

    /**
     * A demo that no longer ships leaves once, before any book is opened: its shelf entry, its
     * copy, its place, its key and any audiobook queued from it. If it was the current book,
     * nothing is current until a book is chosen;
     * the book screen opens the demo itself while only demos stand on the shelf.
     */
    @SuppressWarnings("deprecation")
    static void forgetRetired(final Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS);
        if (sp.getInt(RETIRED_DONE, 0) >= RETIRED.length) {
            return;
        }
        SharedPreferences.Editor ed = sp.edit();
        boolean cleared = forgetRetiredCurrent(c);
        for (String[] r : RETIRED) {
            if (!liveKey(r[1])) {
                ed.remove(r[1]);
            }
            if (liveFile(r[0])) {
                continue;
            }
            File f = retiredFile(c, r[0]);
            String uri = Uri.fromFile(f).toString();
            boolean shelf = Library.get(c, uri) != null;
            if (shelf) {
                Library.remove(c, uri);
            }
            Export.drop(c, uri);
            Reading.forgetPlace(c, uri);
            boolean gone = f.delete();
            if (shelf || gone) {
                Diag.markOnce(c, "demo-retired-" + r[0], "demo: " + r[0] + " no longer shipped, taken away");
            }
        }
        if (sp.getInt(CHOSEN, 0) >= COUNT) {
            ed.remove(CHOSEN);
        }
        ed.putInt(RETIRED_DONE, RETIRED.length).commit();
        if (cleared) {
            // Drawing the widget is not work for the start of a process.
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Widgets.refresh(c);
                    } catch (RuntimeException e) {
                        Diag.log(c, "demo: widget not redrawn", e);
                    }
                }
            }, "demo-retired").start();
        }
    }

    /** Whether a retired demo's file is one a demo that ships now is copied to. */
    private static boolean liveFile(String file) {
        for (int i = 0; i < COUNT; i++) {
            if (FILES[i].equals(file)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a retired demo's shelf key is one a demo that ships now uses. */
    private static boolean liveKey(String key) {
        for (int i = 0; i < COUNT; i++) {
            if (shelvedKey(i).equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static File retiredFile(Context c, String name) {
        return new File(new File(c.getFilesDir(), "demo"), name);
    }

    /**
     * Nothing is current if the current book is a demo that no longer ships: also after settings
     * come back from an archive an earlier build made. True when it was; the caller redraws the widget.
     */
    static boolean forgetRetiredCurrent(Context c) {
        String now = Reading.uri(c);
        for (String[] r : RETIRED) {
            if (!liveFile(r[0]) && now.equals(Uri.fromFile(retiredFile(c, r[0])).toString())) {
                Reading.clear(c);
                Diag.markOnce(c, "demo-retired-current-" + r[0],
                        "demo: " + r[0] + " no longer shipped, was the current book");
                return true;
            }
        }
        return false;
    }

    /** Where earlier builds kept trial samples taken from Hora's folder. */
    private static final String OLD_SAMPLES = "beta";

    /**
     * Every voice ships with the app. Trial samples that earlier builds took
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
