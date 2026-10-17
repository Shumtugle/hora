package com.shumtugle.hora;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * What was read into one's own headphones today, for the Day: the app, the time and the words.
 * Written by the reading process, read by the screens. Kept in the app's own folder on this phone
 * only, never moved to another phone, and only for today: after midnight it is no longer shown, and
 * the file goes at the next look or the next reading. A switch turns it off, and switching it off
 * wipes what was kept.
 */
final class Heard {
    private static final String FILE = "heard-today.tsv";
    /** At most this many are kept for a day; the oldest go first. */
    private static final int KEPT = 50;
    /** One headline of a notification is not a letter: it is cut to this. */
    private static final int MAX_TEXT = 600;

    /** One thing heard: when, which app, and what was said. */
    static final class Entry {
        final long at;
        final String app;
        final String text;

        Entry(long at, String app, String text) {
            this.at = at;
            this.app = app;
            this.text = text;
        }
    }

    private Heard() {
    }

    /**
     * The switch is a file: present means off. Making and deleting a file is seen at once by the other
     * process, which a shared setting is not always.
     */
    private static File offMark(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "heard.off");
    }

    static boolean on(Context c) {
        return !offMark(c).exists();
    }

    static void setOn(Context c, boolean on) {
        File mark = offMark(c);
        try {
            if (on) {
                if (mark.exists() && !mark.delete()) {
                    Diag.mark(c, "heard: switch not changed");
                }
            } else if (!mark.exists() && !mark.createNewFile()) {
                Diag.mark(c, "heard: switch not changed");
            }
        } catch (IOException e) {
            Diag.mark(c, "heard: switch not changed, " + e.getClass().getSimpleName());
        }
        if (!on) {
            clear(c);
        }
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), FILE);
    }

    private static File temp(Context c) {
        return new File(file(c).getPath() + ".tmp");
    }

    /** When the list last changed, for a screen to tell whether to draw it anew; empty when there is none. */
    static String changed(Context c) {
        File f = file(c);
        return f.exists() ? f.lastModified() + ":" + f.length() : "";
    }

    /** Something done to the list while both processes are kept out of it. */
    private interface Locked {
        void run() throws IOException;
    }

    /**
     * Runs under a lock both processes respect: the reading process writing and a screen wiping never
     * cross. Within one process the class lock keeps threads from asking for the file lock twice.
     */
    private static void locked(Context c, Locked work) {
        // Never called from inside a Locked: the same process asking twice would be refused.
        synchronized (Heard.class) {
            File lockFile = new File(c.getApplicationContext().getFilesDir(), "heard.lock");
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(lockFile, "rw");
                 java.nio.channels.FileLock lock = raf.getChannel().lock()) {
                work.run();
            } catch (IOException e) {
                Diag.mark(c, "heard: " + e.getClass().getSimpleName());
            }
        }
    }

    /** Wipes what was kept, the half-written file with it. */
    static void clear(final Context c) {
        locked(c, new Locked() {
            @Override
            public void run() {
                wipe(c);
            }
        });
    }

    private static void wipe(Context c) {
        File f = file(c);
        File t = temp(c);
        if ((f.exists() && !f.delete()) | (t.exists() && !t.delete())) {
            Diag.mark(c, "heard: could not be wiped");
        }
    }

    /** Keeps one thing read aloud. Called by the reading process; does nothing when switched off. */
    static void add(final Context c, String app, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        final Entry fresh = new Entry(System.currentTimeMillis(), one(app), one(text));
        locked(c, new Locked() {
            @Override
            public void run() throws IOException {
                // Asked under the lock: a switch turned off a moment ago is seen here, not after.
                if (!on(c)) {
                    wipe(c);
                    return;
                }
                List<Entry> all = read(c);
                if (all == null) {
                    // What is there did not read: it is not overwritten with this one line.
                    throw new IOException("unread");
                }
                Collections.reverse(all);
                all.add(fresh);
                while (all.size() > KEPT) {
                    all.remove(0);
                }
                StringBuilder b = new StringBuilder();
                for (Entry e : all) {
                    b.append(e.at).append('\t').append(e.app).append('\t').append(e.text).append('\n');
                }
                File t = temp(c);
                try (FileOutputStream out = new FileOutputStream(t)) {
                    out.write(b.toString().getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    t.delete();
                    throw e;
                }
                // Whole or not at all: a screen reading meanwhile sees the old list or the new one.
                if (!t.renameTo(file(c))) {
                    t.delete();
                    throw new IOException("rename");
                }
            }
        });
    }

    /**
     * What was heard today, newest first. A file holding nothing of today is wiped: yesterday's words
     * do not stay on the phone, even when nothing new was read since.
     */
    static List<Entry> today(final Context c) {
        List<Entry> out = read(c);
        if (out == null) {
            // The file did not read just now: nothing is shown, and nothing is wiped for it.
            return new ArrayList<Entry>();
        }
        if (out.isEmpty() && file(c).exists()) {
            locked(c, new Locked() {
                @Override
                public void run() {
                    List<Entry> again = read(c);
                    if (again != null && again.isEmpty()) {
                        wipe(c);
                    }
                }
            });
        }
        return out;
    }

    /** Today's lines in the file, newest first; yesterday's are passed by. Null when the file did not read. */
    private static List<Entry> read(Context c) {
        List<Entry> out = new ArrayList<Entry>();
        File f = file(c);
        if (!f.exists()) {
            return out;
        }
        String all;
        try {
            all = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        long start = startOfToday();
        Calendar next = Calendar.getInstance();
        next.setTimeInMillis(start);
        next.add(Calendar.DAY_OF_MONTH, 1);
        long end = next.getTimeInMillis();
        for (String line : all.split("\n")) {
            String[] p = line.split("\t", 3);
            if (p.length < 3) {
                continue;
            }
            try {
                long at = Long.parseLong(p[0]);
                if (at >= start && at < end) {
                    out.add(new Entry(at, p[1], p[2]));
                }
            } catch (NumberFormatException e) {
                // A broken line is left out.
            }
        }
        Collections.reverse(out);
        return out;
    }

    /** Midnight of today on the phone's clock. */
    static long startOfToday() {
        Calendar day = Calendar.getInstance();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        return day.getTimeInMillis();
    }

    /** One line, without the marks the file is built from, and not too long. */
    private static String one(String s) {
        String t = s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return t.codePointCount(0, t.length()) > MAX_TEXT
                ? t.substring(0, t.offsetByCodePoints(0, MAX_TEXT)) + "\u2026" : t;
    }
}
