package com.shumtugle.hora;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.channels.FileLock;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * A small on-device journal, readable from settings, so problems can be
 * diagnosed without a computer. Two kinds of lines: details, written freely
 * and gone after a quarter of an hour, and events (marked with a star),
 * kept for half a day: a tool for what is going on now, not an archive. Both processes write to the same file under a shared lock.
 */
final class Diag {
    private static final String FILE = "journal.txt";
    private static final String LOCK = "journal.lock";
    private static final String TAG = "hora";
    private static final String EVENT = "* ";
    private static final long DETAIL_LIFE_MS = 15 * 60 * 1000L;
    private static final long EVENT_LIFE_MS = 12 * 60 * 60 * 1000L;
    private static final long PRUNE_EVERY_MS = 60 * 1000L;
    private static final int LIMIT = 128 * 1024;
    /** The fewest lines a copy of the journal's end carries. */
    private static final int FEWEST = 40;
    private static final String STAMP = "MM-dd HH:mm:ss.SSS";
    private static long pruned;

    private Diag() {
    }

    static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    /** A detail: kept for fifteen minutes. */
    static void log(Context c, String msg) {
        write(c, msg, false, null);
    }

    /** An event: kept for half a day. */
    static void mark(Context c, String msg) {
        write(c, msg, true, null);
    }

    /**
     * An event written only if no line in the journal carries the key yet. Both
     * processes report the same past events when they start; the check runs
     * under the shared lock, so one of them writes and the other stays silent.
     */
    static void markOnce(Context c, String key, String msg) {
        // Once per process for certain, whether or not the key shows in the line itself.
        if (!ONCE.add(key)) {
            return;
        }
        write(c, msg, true, key);
    }

    private static final java.util.Set<String> ONCE =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** A failure is always an event, with the start of its trace. */
    static void log(Context c, String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();
        if (trace.length() > 2000) {
            trace = trace.substring(0, 2000);
        }
        mark(c, msg + ": " + trace);
    }

    private static synchronized void write(Context c, String msg, boolean event, String once) {
        long now = System.currentTimeMillis();
        String line = new SimpleDateFormat(STAMP, Locale.ROOT).format(new Date(now))
                + " [" + android.os.Process.myPid() + "] " + (event ? EVENT : "") + msg + "\n";
        Log.i(TAG, msg);
        Context app = c.getApplicationContext();
        File f = file(app);
        try {
            RandomAccessFile lockFile = new RandomAccessFile(new File(app.getFilesDir(), LOCK), "rw");
            try {
                FileLock held = lockFile.getChannel().lock();
                try {
                    if (now - pruned > PRUNE_EVERY_MS || f.length() > LIMIT) {
                        prune(f, now);
                        pruned = now;
                    }
                    if (once != null && f.exists() && Lexicon.readText(f).contains(once)) {
                        return;
                    }
                    FileOutputStream out = new FileOutputStream(f, true);
                    try {
                        out.write(line.getBytes("UTF-8"));
                    } finally {
                        out.close();
                    }
                } finally {
                    held.release();
                }
            } finally {
                lockFile.close();
            }
        } catch (IOException e) {
            // The journal is best effort.
        }
    }

    static String read(Context c) {
        return Lexicon.readText(file(c));
    }

    /**
     * The end of the journal: every line of the last span of time, and never fewer than a few
     * dozen lines, so a quiet hour still shows what came before it. Small enough to paste anywhere.
     */
    static String recent(Context c, long spanMs) {
        String all = read(c);
        if (all.isEmpty()) {
            return "";
        }
        String[] lines = all.split("\n");
        long now = System.currentTimeMillis();
        int from = lines.length;
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i];
            // Lines of a trace carry no stamp and go with the line above them.
            if (line.length() <= STAMP.length() || !Character.isDigit(line.charAt(0))) {
                continue;
            }
            long at = stampTime(line, now);
            if (at < 0) {
                continue;
            }
            if (now - at > spanMs) {
                break;
            }
            from = i;
        }
        from = Math.min(from, Math.max(0, lines.length - FEWEST));
        StringBuilder b = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            b.append(lines[i]).append('\n');
        }
        return b.toString();
    }

    static void clear(Context c) {
        file(c).delete();
    }

    /**
     * When a line was written, or -1 if it carries no stamp. The stamp has no year: this year is
     * put in front before reading it, so that the 29th of February is read as a real day, and a
     * time ahead of now belongs to the year before.
     */
    private static long stampTime(String line, long now) {
        if (line.length() <= STAMP.length() || !Character.isDigit(line.charAt(0))) {
            return -1;
        }
        SimpleDateFormat parse = new SimpleDateFormat("yyyy-" + STAMP, Locale.ROOT);
        parse.setLenient(false);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int year = cal.get(Calendar.YEAR);
        String stamp = line.substring(0, STAMP.length());
        try {
            long at = parse.parse(year + "-" + stamp).getTime();
            if (at > now + EVENT_LIFE_MS) {
                at = parse.parse((year - 1) + "-" + stamp).getTime();
            }
            return at;
        } catch (ParseException e) {
            // The 29th of February of last year, read in a year that has none, or a broken line.
            try {
                return parse.parse((year - 1) + "-" + stamp).getTime();
            } catch (ParseException again) {
                return -1;
            }
        }
    }

    /** Drops details older than their life and events older than theirs; then the oldest half if still too big. */
    private static void prune(File f, long now) throws IOException {
        if (!f.exists()) {
            return;
        }
        String all = Lexicon.readText(f);
        StringBuilder kept = new StringBuilder(all.length());
        long age = 0;
        boolean event = true;
        for (String line : all.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            // Lines of a trace carry no stamp and follow the fate of the line above.
            if (line.length() > STAMP.length() && Character.isDigit(line.charAt(0))) {
                long at = stampTime(line, now);
                if (at >= 0) {
                    age = now - at;
                    int close = line.indexOf("] ");
                    event = close > 0 && line.startsWith(EVENT, close + 2);
                } else {
                    age = 0;
                    event = true;
                }
            }
            if (age <= (event ? EVENT_LIFE_MS : DETAIL_LIFE_MS)) {
                kept.append(line).append('\n');
            }
        }
        String out = kept.toString();
        if (out.length() > LIMIT) {
            out = out.substring(out.length() / 2);
            int nl = out.indexOf('\n');
            out = nl >= 0 ? out.substring(nl + 1) : out;
        }
        if (out.length() != all.length()) {
            Lexicon.writeFile(f, out);
        }
    }

    /** What the phone is doing to the processor now: screen, saver, heat, battery. */
    static String state(Context c) {
        StringBuilder s = new StringBuilder();
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            s.append(pm.isInteractive() ? "screen on" : "screen off");
            s.append(pm.isPowerSaveMode() ? ", saver on" : ", saver off");
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                s.append(", heat ").append(pm.getCurrentThermalStatus());
            }
        }
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (t != Integer.MIN_VALUE) {
                s.append(String.format(Locale.ROOT, ", %.1f C", t / 10f));
            }
            int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            s.append(plugged != 0 ? ", charging" : ", on battery");
        }
        String group = cpuGroup();
        if (!group.isEmpty()) {
            s.append(", cores ").append(group);
        }
        return s.toString();
    }

    /**
     * The group of processor cores the system lets this thread use now, as it names it (for
     * instance the one for the app in front, or the one for work in the background): with the
     * screen off a process may lose the fastest cores. Empty when it cannot be read.
     */
    static String cpuGroup() {
        java.io.File own = new java.io.File("/proc/thread-self/cpuset");
        String path = own.exists() ? own.getPath() : "/proc/self/cpuset";
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            String line = r.readLine();
            if (line == null) {
                return "";
            }
            line = line.trim();
            int slash = line.lastIndexOf('/');
            String name = slash >= 0 ? line.substring(slash + 1) : line;
            return name.isEmpty() ? "all" : name;
        } catch (java.io.IOException | RuntimeException e) {
            return "";
        }
    }
}
