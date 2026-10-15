package com.shumtugle.hora;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The covers books bring with them, for the shelf, the screen behind a book and the player's
 * notice. A book's own picture is taken out of its file once, made small and kept in the app's
 * files; a book read whole that carries none is noted, so its file is not read again. A book that
 * could not be read is not noted: it is tried again a quarter of an hour later. Nothing is drawn
 * or looked for beside the file: a book without a picture keeps the paper cover. Taking a picture
 * out reads the book, so it is done on a thread of its own, one book at a time, and only in the
 * app's own process: the voice's process takes only what is kept.
 */
final class Covers {
    private static final ExecutorService READER = Executors.newSingleThreadExecutor();
    /** Small pictures kept in memory, so a screen drawn again does not read them again. */
    private static final android.util.LruCache<String, Bitmap> SHOWN = new android.util.LruCache<String, Bitmap>(
            (int) Math.min(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    /** Books that could not be read, and when: not read again for a quarter of an hour. */
    private static final java.util.Map<String, Long> TRIED = new java.util.concurrent.ConcurrentHashMap<String, Long>();
    private static final long RETRY = 15L * 60 * 1000;
    /** Books known to carry no cover, so a shelf drawn again asks nothing for them. */
    private static final Set<String> NONE = Collections.synchronizedSet(new HashSet<String>());
    /** The kept files' names by book, worked out once. */
    private static final java.util.Map<String, String> KEYS = new java.util.concurrent.ConcurrentHashMap<String, String>();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static boolean triedLately(String uri) {
        Long at = TRIED.get(uri);
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at < RETRY) {
            return true;
        }
        TRIED.remove(uri);
        return false;
    }

    /** Failures to read a book in a row: after a few, it is a broken file, not a passing trouble. */
    private static final java.util.Map<String, Integer> FAILS = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
    private static final int GIVE_UP = 3;

    private static void tried(String uri) {
        TRIED.put(uri, System.currentTimeMillis());
    }

    /** Told when a cover is ready; on the thread that took it out, never the screen's. */
    interface Ready {
        void ready(Bitmap picture);
    }

    private Covers() {
    }

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "covers");
    }

    /** A name for the book's kept files that tells nothing of where the book lives. */
    private static String key(String uri) {
        String known = KEYS.get(uri);
        if (known != null) {
            return known;
        }
        String k;
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-1").digest(uri.getBytes("UTF-8"));
            char[] out = new char[24];
            for (int i = 0; i < 12; i++) {
                out[2 * i] = HEX[(h[i] >> 4) & 15];
                out[2 * i + 1] = HEX[h[i] & 15];
            }
            k = new String(out);
        } catch (java.security.NoSuchAlgorithmException | java.io.UnsupportedEncodingException e) {
            k = Integer.toHexString(uri.hashCode()) + "-" + Integer.toHexString(uri.length());
        }
        KEYS.put(uri, k);
        return k;
    }

    private static File picture(Context c, String uri) {
        return new File(dir(c), key(uri) + ".jpg");
    }

    private static File none(Context c, String uri) {
        return new File(dir(c), key(uri) + ".none");
    }

    /** The cover if it is in memory now; null otherwise. Never reads, so it is fit for the screen's thread. */
    static Bitmap shown(String uri) {
        return uri == null ? null : SHOWN.get(key(uri));
    }

    /** The cover as kept, without taking it out of the book; null when not kept (yet) or none. Reads a file. */
    static Bitmap kept(Context c, String uri) {
        Bitmap b = shown(uri);
        if (b != null) {
            return b;
        }
        File f = picture(c, uri);
        if (!f.isFile()) {
            return null;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        // A cover has nothing see-through: half the memory, no loss to see.
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) {
            // A kept picture that cannot be read is taken out of the book again next time.
            f.delete();
            return null;
        }
        SHOWN.put(key(uri), b);
        return b;
    }

    /**
     * Finds the book's cover: from memory, from what is kept, or taken out of the book. Told only
     * when there is a picture; a book without one, or one that cannot be read now, is told nothing.
     */
    static void find(Context context, String uri, String name, Ready then) {
        find(context, uri, name, true, then);
    }

    /** The same, from what is kept only: nothing is read out of the book (the voice's process). */
    static void findKept(Context context, String uri, Ready then) {
        find(context, uri, null, false, then);
    }

    private static void find(final Context context, final String uri, final String name, final boolean take,
            final Ready then) {
        if (uri == null || uri.isEmpty() || NONE.contains(uri) || triedLately(uri)) {
            return;
        }
        final Context c = context.getApplicationContext();
        READER.execute(new Runnable() {
            @Override
            public void run() {
                if (NONE.contains(uri) || triedLately(uri)) {
                    return;
                }
                try {
                    Bitmap b = kept(c, uri);
                    if (b == null && none(c, uri).isFile()) {
                        NONE.add(uri);
                        return;
                    }
                    if (b == null && take && !picture(c, uri).isFile()) {
                        try {
                            take(c, uri, name);
                        } catch (OutOfMemoryError e) {
                            // Too big to take out on this phone, today and tomorrow alike: the paper cover stays.
                            markNone(c, uri);
                            Diag.markOnce(c, "covers-memory", "covers: a cover too big for memory");
                            return;
                        }
                        b = kept(c, uri);
                    }
                    if (b != null) {
                        then.ready(b);
                    }
                } catch (RuntimeException e) {
                    tried(uri);
                    Diag.markOnce(c, "covers-fail", "covers: a cover not had: " + e.getClass().getSimpleName());
                } catch (OutOfMemoryError e) {
                    // Memory short for a moment while showing a kept picture: tried again later.
                    tried(uri);
                }
            }
        });
    }

    private static void markNone(Context c, String uri) {
        NONE.add(uri);
        try {
            new FileOutputStream(none(c, uri)).close();
        } catch (IOException ignored) {
            // Not noted on disk: it is noted in memory for this run.
        }
    }

    /** Takes the picture out of the book and keeps it small; or notes that the book has none. */
    private static void take(Context c, String uri, String name) {
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) {
            tried(uri);
            return;
        }
        if (name == null || name.isEmpty()) {
            // Without its file's name the kind of book is not known: not now, and not for good.
            tried(uri);
            return;
        }
        try {
            byte[] jpeg = BookCover.mayCarry(name) ? BookCover.own(c, uri, name) : null;
            if (jpeg == null) {
                markNone(c, uri);
                return;
            }
            // Only the app's own process takes covers out, one at a time: one draft name will do.
            File tmp = new File(picture(c, uri).getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(jpeg);
            }
            if (!tmp.renameTo(picture(c, uri))) {
                tmp.delete();
                if (!picture(c, uri).isFile()) {
                    tried(uri);
                }
            }
            FAILS.remove(uri);
        } catch (IOException e) {
            // Not readable now (no access, not on the phone this moment): tried again later; a book
            // that fails every time is a broken file, and keeps the paper cover.
            int n = FAILS.containsKey(uri) ? FAILS.get(uri) + 1 : 1;
            FAILS.put(uri, n);
            if (n >= GIVE_UP) {
                FAILS.remove(uri);
                markNone(c, uri);
            } else {
                tried(uri);
            }
            Diag.markOnce(c, "covers-read", "covers: a book not readable for its cover: " + e.getMessage());
        }
    }

    /**
     * Forgets a book's cover when the book leaves the shelf or moves. The files go after any
     * finding already waiting, so a finding cannot bring them back.
     */
    static void forget(Context c, final String uri) {
        if (uri == null) {
            return;
        }
        SHOWN.remove(key(uri));
        TRIED.remove(uri);
        NONE.remove(uri);
        FAILS.remove(uri);
        final Context app = c.getApplicationContext();
        READER.execute(new Runnable() {
            @Override
            public void run() {
                picture(app, uri).delete();
                none(app, uri).delete();
            }
        });
    }
}
