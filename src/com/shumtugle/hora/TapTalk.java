package com.shumtugle.hora;

import android.content.Context;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What a voice says when its line on the main screen is touched. The line on
 * screen is its card, not a subtitle: the voice speaks about it in its own
 * words, put together from four cells (greeting, name, who it is, invitation),
 * and never repeats a cell's last pick. The words are plain: a joke that comes
 * back is no longer a joke. Touches in a row cool it down: the fourth drops
 * the greeting, and from the fifth on it is the same flat line every time.
 * A pause ends the game.
 */
final class TapTalk {
    private static final String ASSET = "talk/tap.txt";
    /** Touches further apart than this start the game over. */
    private static final long GAME_GAP_MS = 25000;
    private static final int DROP_GREETING = 4;
    private static final int FLAT = 5;
    /** The flat line is spoken a little slower than the voice's own pace. */
    static final float FLAT_PACE = 0.9f;

    private static Map<String, List<String>> lines;
    private static final Map<String, Integer> lastPick = new HashMap<String, Integer>();
    private static final Random random = new Random();
    private static int count;
    private static int countVoice;
    private static long lastTap;

    /** One spoken reply: the words and whether it is the flat one. */
    static final class Reply {
        final String text;
        final boolean flat;

        Reply(String text, boolean flat) {
            this.text = text;
            this.flat = flat;
        }
    }

    private TapTalk() {
    }

    /** The reply to the next touch on a voice's line, or null when there are no lines. */
    static synchronized Reply next(Context c, int voice) {
        long now = SystemClock.elapsedRealtime();
        if (voice != countVoice || now - lastTap > GAME_GAP_MS) {
            count = 0;
        }
        countVoice = voice;
        lastTap = now;
        count++;
        String lang = SpeechLanguage.CODE;
        if (count >= FLAT) {
            String flat = pick(c, lang, "flat", voice);
            return flat == null ? null : new Reply(flat, true);
        }
        StringBuilder out = new StringBuilder();
        if (count < DROP_GREETING) {
            append(out, pick(c, lang, "greet", voice));
        }
        append(out, pick(c, lang, "name", voice));
        append(out, pick(c, lang, "self", voice));
        append(out, pick(c, lang, "invite", voice));
        return out.length() == 0 ? null : new Reply(out.toString(), false);
    }

    /** Forgets the game, as when another voice steps forward. */
    static synchronized void reset() {
        count = 0;
    }

    private static void append(StringBuilder out, String part) {
        if (part == null || part.isEmpty()) {
            return;
        }
        if (out.length() > 0) {
            out.append(' ');
        }
        out.append(part);
    }

    private static String pick(Context c, String lang, String cell, int voice) {
        String key = lang + "." + cell + "." + voice;
        List<String> own = load(c).get(key);
        if (own == null || own.isEmpty()) {
            return null;
        }
        Integer last = lastPick.get(key);
        int i = random.nextInt(own.size());
        if (own.size() > 1 && last != null && i == last) {
            i = (i + 1 + random.nextInt(own.size() - 1)) % own.size();
        }
        lastPick.put(key, i);
        return own.get(i).replace("{name}", Cast.spokenName(c, voice));
    }

    private static Map<String, List<String>> load(Context c) {
        if (lines != null) {
            return lines;
        }
        Map<String, List<String>> map = new HashMap<String, List<String>>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getAssets().open(ASSET), "UTF-8"))) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                int bar = line.indexOf('|');
                if (line.isEmpty() || line.startsWith("#") || bar <= 0) {
                    continue;
                }
                String key = line.substring(0, bar).trim();
                List<String> list = map.get(key);
                if (list == null) {
                    list = new ArrayList<String>();
                    map.put(key, list);
                }
                list.add(line.substring(bar + 1).trim());
            }
        } catch (IOException e) {
            Diag.log(c, "tap: lines missing", e);
        }
        lines = map;
        return lines;
    }
}
