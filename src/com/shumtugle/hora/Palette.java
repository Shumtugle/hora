package com.shumtugle.hora;

import android.content.Context;
import android.graphics.Typeface;

/**
 * Shared colors and type for all screens. Looks: bordeaux, near-black red
 * with paper-colored text and a cinnabar accent, the colors of the app's
 * sign; lamp, the green of a reading-room lamp with a brass accent; ink,
 * night-blue with cinnabar; and a contrast look for eyes that need it and
 * for grey-scale screens: pure black, white text, yellow accent, lines
 * around every card and a solid face for titles. Every look keeps text at
 * least 4.5 times brighter or darker than what it sits on. The fields are set by apply(); screens read
 * them while building, and rebuild when the look has changed since.
 */
final class Palette {
    static final int BORDEAUX = 0;
    static final int CONTRAST = 1;
    static final int LAMP = 2;
    static final int INK_LOOK = 3;
    /** The looks in the order the settings offer them. */
    static final int[] LOOKS = {BORDEAUX, LAMP, INK_LOOK, CONTRAST};
    /** Each look's colors: bg, surface, raised, line, ink, muted, hint, accent, on accent, accent text, rule. */
    private static final int[][] COLORS = {
        {0xFF140B0D, 0xFF28141A, 0xFF3A1C22, 0xFF4E262D, 0xFFF3E7CF, 0xFFCDB5A6, 0xFF9A7F74,
            0xFFB83A2B, 0xFFF3E7CF, 0xFFE8826C, 0x22F3E7CF},
        {0xFF000000, 0xFF141414, 0xFF333333, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFE6E6E6, 0xFFBDBDBD,
            0xFFFFD600, 0xFF000000, 0xFFFFD600, 0x66FFFFFF},
        {0xFF0E1411, 0xFF18231D, 0xFF223129, 0xFF33473A, 0xFFEFE6D2, 0xFFC3BBA4, 0xFF8E8C78,
            0xFFA9802E, 0xFF15110A, 0xFFD9B160, 0x22EFE6D2},
        {0xFF0C0E17, 0xFF171B2B, 0xFF232943, 0xFF323A5C, 0xFFECE7DC, 0xFFBBB7C4, 0xFF8487A2,
            0xFFB5432F, 0xFFF6EEE2, 0xFFEE8C74, 0x22ECE7DC},
    };

    static int BG;
    static int SURFACE;
    static int RAISED;
    static int LINE;
    static int INK;
    static int MUTED;
    static int HINT;
    static int ACCENT;
    /** The accent as a text color on the background: lighter where the accent itself is too dark to read. */
    static int ACCENT_TEXT;
    /** Text and marks drawn on the accent color. */
    static int ON_ACCENT;
    static int RULE;
    /** Whether cards and buttons carry a visible edge. */
    static boolean OUTLINED;

    private static int look = -1;
    /** Grows with every change of look; screens compare it to the one they were built with. */
    private static int generation;

    /** One color per voice, in voice order. */
    private static final int[] VOICE = {0xFFE0A84B, 0xFFD98A6E, 0xFF7FB0C4, 0xFFA8C08F, 0xFFB3A5E0};

    private static Typeface display;
    private static Typeface body;
    private static Typeface bodyStrong;

    static {
        set(BORDEAUX);
    }

    private Palette() {
    }

    /** The sheet theme of the current look, so system parts drawn inside a sheet wear its colors. */
    static int sheetTheme() {
        switch (look) {
            case CONTRAST:
                return R.style.Sheet_Contrast;
            case LAMP:
                return R.style.Sheet_Lamp;
            case INK_LOOK:
                return R.style.Sheet_Ink;
            default:
                return R.style.Sheet;
        }
    }

    /** Takes the chosen look from the settings. */
    static synchronized int apply(Context c) {
        int wanted = Prefs.look(c);
        if (wanted != look) {
            set(wanted);
            generation++;
        }
        return generation;
    }

    static synchronized int generation() {
        return generation;
    }

    static boolean contrast() {
        return look == CONTRAST;
    }

    /** A valid look: unknown values fall back to bordeaux. */
    static int known(int which) {
        return which >= 0 && which < COLORS.length ? which : BORDEAUX;
    }

    /** A look's background, surface, text and accent, for showing it before it is chosen. */
    static int[] preview(int which) {
        int[] c = COLORS[known(which)];
        return new int[] {c[0], c[1], c[4], c[7]};
    }

    private static void set(int which) {
        look = known(which);
        int[] c = COLORS[look];
        BG = c[0];
        SURFACE = c[1];
        RAISED = c[2];
        LINE = c[3];
        INK = c[4];
        MUTED = c[5];
        HINT = c[6];
        ACCENT = c[7];
        ON_ACCENT = c[8];
        ACCENT_TEXT = c[9];
        RULE = c[10];
        OUTLINED = look == CONTRAST;
    }

    static int voice(int v) {
        int c = v >= 1 && v <= VOICE.length ? VOICE[v - 1] : ACCENT;
        return look == CONTRAST ? lighten(c, 0.55f) : c;
    }

    /** The voice color at a given opacity (0..255). */
    static int voice(int v, int alpha) {
        return (voice(v) & 0x00FFFFFF) | (alpha << 24);
    }

    /** Mixes a color toward white, keeping its hue readable against black. */
    private static int lighten(int c, float k) {
        int r = (c >> 16) & 0xff;
        int g = (c >> 8) & 0xff;
        int b = c & 0xff;
        r = Math.round(r + (255 - r) * k);
        g = Math.round(g + (255 - g) * k);
        b = Math.round(b + (255 - b) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Names and titles: the serif in the bordeaux look, the solid sans in the contrast one. */
    static synchronized Typeface display(Context c) {
        if (look == CONTRAST) {
            return bodyStrong(c);
        }
        if (display == null) {
            display = load(c, "fonts/display.ttf", Typeface.SERIF);
        }
        return display;
    }

    /** Everything else. */
    static synchronized Typeface body(Context c) {
        if (body == null) {
            body = load(c, "fonts/text.ttf", Typeface.SANS_SERIF);
        }
        return body;
    }

    static synchronized Typeface bodyStrong(Context c) {
        if (bodyStrong == null) {
            bodyStrong = load(c, "fonts/text-strong.ttf", Typeface.DEFAULT_BOLD);
        }
        return bodyStrong;
    }

    private static Typeface load(Context c, String path, Typeface fallback) {
        try {
            return Typeface.createFromAsset(c.getApplicationContext().getAssets(), path);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
