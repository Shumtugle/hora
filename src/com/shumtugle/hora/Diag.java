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
 * kept for a day. Both processes write to the same file under a shared lock.
 */
final class Diag {
    private static final String FILE = "journal.txt";
    private static final String LOCK = "journal.lock";
    private static final String TAG = "hora";
    private static final String EVENT = "* ";
    private static final long DETAIL_LIFE_MS = 15 * 60 * 1000L;
    private static final long EVENT_LIFE_MS = 24 * 60 * 60 * 1000L;
    private static final long PRUNE_EVERY_MS = 60 * 1000L;
    private static final int LIMIT = 512 * 1024;
    private static final String STAMP = "MM-dd HH:mm:ss.SSS";
    private static long pruned;

    private Diag() {
    }

    static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    /** A detail: kept for fifteen minutes. */
    static void log(Context c, String msg) {
        write(c, msg, false);
    }

    /** An event: kept for a day. */
    static void mark(Context c, String msg) {
        write(c, msg, true);
    }

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

    private static synchronized void write(Context c, String msg, boolean event) {
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

    static void clear(Context c) {
        file(c).delete();
    }

    /** Drops details older than their life and events older than theirs; then the oldest half if still too big. */
    private static void prune(File f, long now) throws IOException {
        if (!f.exists()) {
            return;
        }
        String all = Lexicon.readText(f);
        StringBuilder kept = new StringBuilder(all.length());
        SimpleDateFormat parse = new SimpleDateFormat(STAMP, Locale.ROOT);
        Calendar when = Calendar.getInstance();
        int year = when.get(Calendar.YEAR);
        long age = 0;
        boolean event = true;
        for (String line : all.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            // Lines of a trace carry no stamp and follow the fate of the line above.
            if (line.length() > STAMP.length() && Character.isDigit(line.charAt(0))) {
                try {
                    when.setTime(parse.parse(line.substring(0, STAMP.length())));
                    when.set(Calendar.YEAR, year);
                    if (when.getTimeInMillis() > now + EVENT_LIFE_MS) {
                        when.set(Calendar.YEAR, year - 1);
                    }
                    age = now - when.getTimeInMillis();
                    int close = line.indexOf("] ");
                    event = close > 0 && line.startsWith(EVENT, close + 2);
                } catch (ParseException e) {
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
        return s.toString();
    }
}
