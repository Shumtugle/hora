package com.shumtugle.hora;

import android.content.Context;

import java.io.File;

/**
 * The voices. Each one is a short sample in a numbered slot; the bundled
 * sample can be replaced by the user's own, per slot. Each also has a
 * portrait, which is fictional, and a short description.
 */
final class Cast {
    static final int COUNT = 5;
    /** Roles: who reads outside dialogue, and the two sides of a dialogue. */
    static final int NARRATOR = 0;
    static final int SPEAKER_A = 1;
    static final int SPEAKER_B = 2;
    /** Reads notifications into headphones; a voice of its own, apart from the book. */
    static final int HERALD = 3;
    /** Hosts the talk and the main screen: the one who answers. */
    static final int TALK = 4;

    private Cast() {
    }

    /** File name of the sample for a voice numbered from 1. */
    static String slot(int voice) {
        return "voice" + voice + ".wav";
    }

    static String name(Context c, int voice) {
        String own = Prefs.voiceName(c, voice);
        if (!own.isEmpty()) {
            return own;
        }
        String[] names = c.getResources().getStringArray(R.array.voice_names);
        return voice >= 1 && voice <= names.length ? names[voice - 1] : String.valueOf(voice);
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
        return voice >= 1 && voice <= names.length ? names[voice - 1] : String.valueOf(voice);
    }

    /** The voice that came with a plain label for a name; it asks for a name when first chosen. */
    static final int RENAMABLE = 3;
    /** The first voice, named after the app; the person may give her another name too, unasked. */
    static final int HOST = 1;

    static boolean renamable(int voice) {
        return voice == RENAMABLE || voice == HOST;
    }

    /**
     * The order the troupe stands in on screen, by voice number: the main
     * female voice, the main male one, then the rest. Numbers stay what they
     * were, so samples, portraits and saved choices keep their places.
     */
    private static final int[] ORDER = {1, 5, 2, 4, 3};

    /** The voice standing at a place in the row, counted from 0. */
    static int at(int place) {
        return ORDER[Math.max(0, Math.min(COUNT - 1, place))];
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
        R.drawable.portrait_4, R.drawable.portrait_5,
    };

    static int portrait(int voice) {
        return PORTRAITS[Math.max(1, Math.min(COUNT, voice)) - 1];
    }

    /** One-word character of a voice, shown under its name. */
    static String epithet(Context c, int voice) {
        return item(c, R.array.voice_epithets, voice);
    }

    /** What a voice is, as a noun: the word that follows its name when it introduces itself. */
    static String title(Context c, int voice) {
        return say(c, R.array.voice_titles, voice);
    }

    /** What kind of voice it is: female or male, main or not. */
    static String kind(Context c, int voice) {
        return item(c, R.array.voice_kinds, voice);
    }

    /** A voice's own line from a list of five, in the language it speaks. */
    static String say(Context c, int array, int voice) {
        String[] all = SpeechLanguage.resources(c).getStringArray(array);
        return voice >= 1 && voice <= all.length ? all[voice - 1] : "";
    }

    private static String item(Context c, int array, int voice) {
        String[] all = c.getResources().getStringArray(array);
        return voice >= 1 && voice <= all.length ? all[voice - 1] : "";
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
        String dir = "cards/" + voice;
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
            java.io.InputStream in = c.getAssets().open("cards/" + voice + "/1.jpg");
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
