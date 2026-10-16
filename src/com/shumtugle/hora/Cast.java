package com.shumtugle.hora;

import android.content.Context;

import java.io.File;

/**
 * The voices. Each one is a short bundled sample in a numbered slot, with a
 * portrait, which is fictional, and a short description.
 */
final class Cast {
    static final int COUNT = 5;
    /**
     * Voices made after the first five: faces, scenes and names like the cast's, and the same
     * roles. The eighth number is empty: its voice left the troupe, and the numbers after it keep
     * their places, so samples, portraits and saved choices stay where they were.
     */
    static final int BETA_F = COUNT + 1;
    static final int BETA_M = COUNT + 2;
    static final int BETA_F2 = COUNT + 4;
    /** The highest voice number. */
    static final int LAST = BETA_F2;
    /** Roles: who reads outside dialogue, and the two sides of a dialogue. */
    static final int NARRATOR = 0;
    static final int SPEAKER_A = 1;
    static final int SPEAKER_B = 2;
    /** Reads notifications into headphones; a voice of its own, apart from the book. */
    static final int HERALD = 3;
    /** Hosts the talk and the Voice screen: the one who answers. */
    static final int TALK = 4;

    private Cast() {
    }

    /** File name of the sample for a voice numbered from 1. */
    static String slot(int voice) {
        return "voice" + voice + ".wav";
    }

    /** The name on screen: the person's own name for the voice, or the one it came with. */
    static String name(Context c, int voice) {
        String own = Prefs.voiceName(c, voice);
        if (!own.isEmpty()) {
            return own;
        }
        return givenName(c, voice);
    }

    /** The name as the voice says it: the person's own name for it, or the one in the speech language. */
    static String spokenName(Context c, int voice) {
        String own = Prefs.voiceName(c, voice);
        if (!own.isEmpty()) {
            return own;
        }
        return say(c, R.array.voice_names, voice);
    }

    /** The name a voice came with, before anyone renamed it. */
    static String givenName(Context c, int voice) {
        String[] names = c.getResources().getStringArray(R.array.voice_names);
        int v = origin(voice);
        return v >= 1 && v <= names.length ? names[v - 1] : String.valueOf(voice);
    }

    /** The voice whose face, scenes, lines and manner a voice has: every voice its own now. */
    static int origin(int voice) {
        return voice;
    }

    /** The voice that came with a plain label for a name; it asks for a name when first chosen. */
    static final int RENAMABLE = 3;
    /** The first voice, named after the app; the person may give her another name too, unasked. */
    static final int HOST = 1;

    /** Every voice may be given a name of the person's own. */
    static boolean renamable(int voice) {
        return exists(voice);
    }

    /**
     * The order the troupe stands in on screen, by voice number: the main
     * female voice, the main male one, then the rest. Numbers stay what they
     * were, so samples, portraits and saved choices keep their places.
     */
    private static final int[] ORDER = {1, 5, 2, 4, 3, BETA_F, BETA_F2, BETA_M};

    /** The voice standing at a place in the row, counted from 0. */
    static int at(int place) {
        return ORDER[Math.max(0, Math.min(ORDER.length - 1, place))];
    }

    /** How many voices stand in the row: all of them, always. A place in the row is not a voice number. */
    static int size(Context c) {
        return ORDER.length;
    }

    /** Whether a voice number belongs to a voice of the troupe (one that left has none). */
    static boolean exists(int voice) {
        for (int v : ORDER) {
            if (v == voice) {
                return true;
            }
        }
        return false;
    }

    /** The voices that speak of themselves as women; the rest as men. */
    private static final int[] FEMALE = {1, 2, BETA_F, BETA_F2};

    static boolean female(int voice) {
        int v = origin(voice);
        for (int f : FEMALE) {
            if (f == v) {
                return true;
            }
        }
        return false;
    }

    /** Whether a voice can be chosen now: it stands in the row. */
    static boolean available(Context c, int voice) {
        return exists(voice);
    }

    /** Where a voice stands in the row, counted from 0. */
    static int place(int voice) {
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i] == voice) {
                return i;
            }
        }
        return 0;
    }

    private static final int[] PORTRAITS = {
        R.drawable.portrait_1, R.drawable.portrait_2, R.drawable.portrait_3,
        R.drawable.portrait_4, R.drawable.portrait_5, R.drawable.portrait_6, R.drawable.portrait_7,
        // The eighth voice left the troupe: its place stays empty.
        0, R.drawable.portrait_9,
    };

    /** A voice's portrait; a number with no voice shows the first voice's face rather than none. */
    static int portrait(int voice) {
        return PORTRAITS[(exists(voice) ? voice : HOST) - 1];
    }

    /** What a voice is, as a noun: the word that follows its name when it introduces itself. */
    static String title(Context c, int voice) {
        return say(c, R.array.voice_titles, voice);
    }

    /** What kind of voice it is: female or male, main or not. */
    static String kind(Context c, int voice) {
        return item(c, R.array.voice_kinds, voice);
    }

    /** A voice's own line from a list by voice number, in the language it speaks. */
    static String say(Context c, int array, int voice) {
        String[] all = SpeechLanguage.resources(c).getStringArray(array);
        int v = origin(voice);
        return v >= 1 && v <= all.length ? all[v - 1] : "";
    }

    private static String item(Context c, int array, int voice) {
        String[] all = c.getResources().getStringArray(array);
        int v = origin(voice);
        return v >= 1 && v <= all.length ? all[v - 1] : "";
    }

    /** Where earlier builds kept samples brought in from outside. */
    private static final String OWN_DIR = "voice-user";

    /**
     * Hora speaks only with its own voices. Samples that earlier builds took from
     * outside are removed once, and the journal says so.
     */
    static void forgetOwnSamples(Context c) {
        File dir = new File(c.getFilesDir(), OWN_DIR);
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
        Diag.mark(c, "voices: samples from outside removed, " + gone);
    }

    /**
     * A scene of the voice at work, picked at random, cut wide around the
     * face and hands; null when none is bundled for it.
     */
    static android.graphics.Bitmap card(Context c, int voice, java.util.Random pick) {
        String dir = "cards/" + origin(voice);
        try {
            String[] all = c.getAssets().list(dir);
            if (all == null || all.length == 0) {
                return null;
            }
            java.io.InputStream in = c.getAssets().open(dir + "/" + all[pick.nextInt(all.length)]);
            android.graphics.Bitmap whole;
            try {
                whole = android.graphics.BitmapFactory.decodeStream(in);
            } finally {
                in.close();
            }
            if (whole == null) {
                return null;
            }
            int w = whole.getWidth();
            int h = Math.min(whole.getHeight(), Math.round(w / 1.5f));
            int top = Math.min(whole.getHeight() - h, Math.round(whole.getHeight() * 0.03f));
            return android.graphics.Bitmap.createBitmap(whole, 0, top, w, h);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    private static final android.util.SparseArray<android.graphics.Bitmap> TALL =
            new android.util.SparseArray<android.graphics.Bitmap>();

    /** The voice's first scene, cut tall around the face; kept once made. */
    static synchronized android.graphics.Bitmap tallCard(Context c, int voice) {
        android.graphics.Bitmap made = TALL.get(voice);
        if (made != null) {
            return made;
        }
        try {
            java.io.InputStream in = c.getAssets().open("cards/" + origin(voice) + "/1.jpg");
            android.graphics.Bitmap whole;
            try {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = 2;
                whole = android.graphics.BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
            if (whole == null) {
                return null;
            }
            int w = Math.round(whole.getWidth() * 0.66f);
            int h = Math.min(whole.getHeight(), Math.round(w * 1.5f));
            int left = (whole.getWidth() - w) / 2;
            made = android.graphics.Bitmap.createBitmap(whole, left, 0, w, h);
            TALL.put(voice, made);
            return made;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }
}
