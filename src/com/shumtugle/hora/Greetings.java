package com.shumtugle.hora;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Lines from a story, a handful for each voice. No screen shows them for
 * now; they wait for the scene on the lock screen. Each voice draws from its own bag, so a line comes back only
 * after all the others have been heard, and a new round never opens with the
 * line that closed the last one.
 */
final class Greetings {
    private static final String ASSET = "greetings.txt";
    private static final String FILE = "greetings";
    private static final String BAG = "bag_";
    private static final String LAST = "last_";
    private static List<List<String>> lines;

    private Greetings() {
    }

    /** The next line for a voice, or null when there are none. */
    static synchronized String next(Context c, int voice) {
        List<String> own = linesFor(c, voice);
        if (own.isEmpty()) {
            return null;
        }
        SharedPreferences sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        List<Integer> bag = parse(sp.getString(BAG + voice, ""), own.size());
        int last = sp.getInt(LAST + voice, -1);
        if (bag.isEmpty()) {
            for (int i = 0; i < own.size(); i++) {
                bag.add(i);
            }
            Collections.shuffle(bag, new Random());
            if (bag.size() > 1 && bag.get(0) == last) {
                Collections.swap(bag, 0, bag.size() - 1);
            }
        }
        int pick = bag.remove(0);
        StringBuilder rest = new StringBuilder();
        for (int i : bag) {
            if (rest.length() > 0) {
                rest.append(',');
            }
            rest.append(i);
        }
        sp.edit().putString(BAG + voice, rest.toString()).putInt(LAST + voice, pick).apply();
        return own.get(pick);
    }

    private static List<Integer> parse(String s, int size) {
        List<Integer> out = new ArrayList<Integer>();
        for (String p : s.split(",")) {
            try {
                int i = Integer.parseInt(p.trim());
                if (i >= 0 && i < size) {
                    out.add(i);
                }
            } catch (NumberFormatException ignored) {
                // An empty or stale entry is skipped.
            }
        }
        return out;
    }

    private static List<String> linesFor(Context c, int voice) {
        if (lines == null) {
            lines = new ArrayList<List<String>>();
            for (int v = 0; v <= Cast.COUNT; v++) {
                lines.add(new ArrayList<String>());
            }
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open(ASSET), "UTF-8"));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int bar = line.indexOf('|');
                        if (bar <= 0) {
                            continue;
                        }
                        try {
                            int v = Integer.parseInt(line.substring(0, bar).trim());
                            if (v >= 1 && v <= Cast.COUNT) {
                                lines.get(v).add(line.substring(bar + 1).trim());
                            }
                        } catch (NumberFormatException ignored) {
                            // Not a line of the list.
                        }
                    }
                } finally {
                    r.close();
                }
            } catch (IOException e) {
                // Without the list the plain greeting is used.
            }
        }
        return voice >= 1 && voice <= Cast.COUNT ? lines.get(voice) : new ArrayList<String>();
    }
}
