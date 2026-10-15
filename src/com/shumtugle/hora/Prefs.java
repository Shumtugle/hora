package com.shumtugle.hora;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User settings. Requests from this app carry the values with them; the
 * system engine, which has no such request, reads them through pauseMsShared().
 */
final class Prefs {
    static final float SPEED_MIN = 0.5f;
    static final float SPEED_MAX = 2.0f;
    static final float SPEED_DEFAULT = 0.85f;
    static final int PAUSE_MAX_MS = 2000;
    static final int PAUSE_DEFAULT_MS = 250;

    private static final String FILE = "settings";
    private static final String SPEED = "speed";
    private static final String HERALD_APPS = "herald_apps";
    private static final String QUIET = "herald_quiet";
    private static final String QUIET_FROM = "herald_quiet_from";
    private static final String QUIET_TO = "herald_quiet_to";
    private static final int QUIET_FROM_DEFAULT = 23 * 60;
    private static final int QUIET_TO_DEFAULT = 7 * 60;
    private static final String PAUSE = "pause_ms";
    private static final String EXPRESSION = "expression";
    private static final String DIALOGUE = "dialogue_voice";
    private static final String MAIN_SECOND = "main_is_second";
    static final float EXPRESSION_MIN = 0.1f;
    static final float EXPRESSION_MAX = 1.0f;
    /** Heard as right for the narrator through other apps; higher made the voice uneven. */
    static final float EXPRESSION_DEFAULT = 0.20f;

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static float speed(Context c) {
        return sp(c).getFloat(SPEED, SPEED_DEFAULT);
    }

    static int pauseMs(Context c) {
        return sp(c).getInt(PAUSE, PAUSE_DEFAULT_MS);
    }

    /**
     * For the voice process: re-reads the file on each call, since the value
     * is changed by another process.
     */
    @SuppressWarnings("deprecation")
    static int pauseMsShared(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getInt(PAUSE, PAUSE_DEFAULT_MS);
    }

    @SuppressWarnings("deprecation")
    static float speedShared(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getFloat(SPEED, SPEED_DEFAULT);
    }

    static float expression(Context c) {
        return sp(c).getFloat(EXPRESSION, EXPRESSION_DEFAULT);
    }

    @SuppressWarnings("deprecation")
    static float expressionShared(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS)
                .getFloat(EXPRESSION, EXPRESSION_DEFAULT);
    }

    static void setExpression(Context c, float v) {
        sp(c).edit().putFloat(EXPRESSION, v).apply();
    }

    static boolean dialogueVoice(Context c) {
        return sp(c).getBoolean(DIALOGUE, false);
    }

    @SuppressWarnings("deprecation")
    static boolean dialogueVoiceShared(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getBoolean(DIALOGUE, false);
    }

    static void setDialogueVoice(Context c, boolean on) {
        sp(c).edit().putBoolean(DIALOGUE, on).commit();
        BookPlayer.revoice(c);
    }

    /** Whether the second sample reads everything outside dialogue. */
    static boolean mainIsSecond(Context c) {
        return sp(c).getBoolean(MAIN_SECOND, false);
    }

    @SuppressWarnings("deprecation")
    static boolean mainIsSecondShared(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getBoolean(MAIN_SECOND, false);
    }

    static void setMainIsSecond(Context c, boolean v) {
        sp(c).edit().putBoolean(MAIN_SECOND, v).apply();
    }

    private static final String SLOW_BY_PAUSES = "slow_by_pauses";
    private static final String HEARD_TO_FIELD = "heard_to_field";
    private static final String MIC_ASKED = "mic_asked";
    private static final String RESPELL = "respell";
    private static final String EVEN_TEMPO = "even_tempo";
    private static final String ROLE_PREFIX = "role_";
    private static final String NAME_PREFIX = "voice_name_";
    private static final String BED = "bed";
    private static final String BED_VOLUME = "bed_volume";
    private static final String BED_LAST = "bed_last";
    private static final String BED_ONLY = "bed_only";

    /** The background under a book read aloud; off unless chosen. */
    @SuppressWarnings("deprecation")
    static int bed(Context c) {
        int v = c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getInt(BED, Bed.OFF);
        return v >= 0 && v < Bed.KINDS ? v : Bed.OFF;
    }

    /** Written at once: the reading process reads it as soon as it is told of the change. */
    static void setBed(Context c, int kind) {
        SharedPreferences.Editor e = sp(c).edit().putInt(BED, kind);
        if (kind != Bed.OFF) {
            e.putInt(BED_LAST, kind);
        }
        e.commit();
    }

    /** The last background that was not silence, to bring back with one touch. */
    @SuppressWarnings("deprecation")
    static int bedLast(Context c) {
        int v = c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getInt(BED_LAST, Bed.BROWN);
        return v > Bed.OFF && v < Bed.KINDS ? v : Bed.BROWN;
    }

    /** Whether the voice is silent on purpose while the background plays; kept by the reading process. */
    @SuppressWarnings("deprecation")
    static boolean bedOnly(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getBoolean(BED_ONLY, false);
    }

    static void setBedOnly(Context c, boolean on) {
        sp(c).edit().putBoolean(BED_ONLY, on).commit();
    }

    /**
     * 0..100, kept for each background on its own: noises are loud by nature
     * and sit low, the music is soft and sits higher. Each starts where it
     * sounded right under the voice.
     */
    private static final int[] BED_VOLUME_DEFAULT = {0, 8, 7, 6, 7, 11, 53, 9, 8, 10};

    static int bedVolume(Context c) {
        return bedVolume(c, bed(c));
    }

    @SuppressWarnings("deprecation")
    static int bedVolume(Context c, int kind) {
        int k = kind >= 0 && kind < BED_VOLUME_DEFAULT.length ? kind : 0;
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getInt(BED_VOLUME + "_" + k,
                BED_VOLUME_DEFAULT[k]);
    }

    /** Sets the volume of the background chosen now. */
    static void setBedVolume(Context c, int v) {
        sp(c).edit().putInt(BED_VOLUME + "_" + bed(c), Math.max(0, Math.min(100, v))).apply();
    }
    private static final String NAME_ASKED_PREFIX = "voice_name_asked_";
    /** Longest name a person may give a voice. */
    static final int NAME_MAX = 20;

    /** The name the person gave a voice, or "" when it keeps its own. */
    @SuppressWarnings("deprecation")
    static String voiceName(Context c, int voice) {
        String s = c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getString(NAME_PREFIX + voice, "");
        return s == null ? "" : s.trim();
    }

    static void setVoiceName(Context c, int voice, String name) {
        String s = name == null ? "" : name.trim();
        if (s.length() > NAME_MAX) {
            s = s.substring(0, NAME_MAX).trim();
        }
        sp(c).edit().putString(NAME_PREFIX + voice, s).apply();
    }

    static boolean nameAsked(Context c, int voice) {
        return sp(c).getBoolean(NAME_ASKED_PREFIX + voice, false);
    }

    static void setNameAsked(Context c, int voice) {
        sp(c).edit().putBoolean(NAME_ASKED_PREFIX + voice, true).apply();
    }
    /** Book, dialogue A (male), dialogue B (female), notifications, talk: all to the main voice. */
    private static final int[] ROLE_DEFAULT = {1, 5, 2, 1, 1};

    /** Voice number for a role: the book, the two sides of a dialogue, notifications, the talk. */
    static int role(Context c, int role) {
        return readRole(c, sp(c), role);
    }

    @SuppressWarnings("deprecation")
    static int roleShared(Context c, int role) {
        return readRole(c, c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS), role);
    }

    private static int readRole(Context c, SharedPreferences p, int role) {
        int def = ROLE_DEFAULT[role];
        if (role == Cast.NARRATOR && p.getBoolean(MAIN_SECOND, false)) {
            def = 5;
        }
        int v = p.getInt(ROLE_PREFIX + role, def);
        // A voice that left the troupe gives the role back to its usual voice.
        return Cast.available(c, v) ? v : def;
    }

    static void setRole(Context c, int role, int voice) {
        // Written at once: the reading process reads it right after being told.
        sp(c).edit().putInt(ROLE_PREFIX + role, voice).commit();
        if (role == Cast.NARRATOR || role == Cast.SPEAKER_A || role == Cast.SPEAKER_B) {
            BookPlayer.revoice(c);
        }
    }

    /** Spoken words wait in the chat's field for a touch, instead of going out after a pause. */
    static boolean heardToField(Context c) {
        return sp(c).getBoolean(HEARD_TO_FIELD, false);
    }

    static void setHeardToField(Context c, boolean on) {
        sp(c).edit().putBoolean(HEARD_TO_FIELD, on).apply();
    }

    /** Whether the phone has already been asked for the microphone once. */
    static boolean micAsked(Context c) {
        return sp(c).getBoolean(MIC_ASKED, false);
    }

    static void setMicAsked(Context c) {
        sp(c).edit().putBoolean(MIC_ASKED, true).apply();
    }

    /** Slowing down lengthens the pauses instead of stretching the speech itself. */
    static boolean slowByPauses(Context c) {
        return sp(c).getBoolean(SLOW_BY_PAUSES, true);
    }

    @SuppressWarnings("deprecation")
    static boolean slowByPausesShared(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getBoolean(SLOW_BY_PAUSES, true);
    }

    static void setSlowByPauses(Context c, boolean on) {
        sp(c).edit().putBoolean(SLOW_BY_PAUSES, on).apply();
    }

    static boolean respell(Context c) {
        return sp(c).getBoolean(RESPELL, true);
    }

    @SuppressWarnings("deprecation")
    static boolean respellShared(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getBoolean(RESPELL, true);
    }

    static void setRespell(Context c, boolean on) {
        sp(c).edit().putBoolean(RESPELL, on).apply();
    }

    /** Evens out the pace between chunks, which the model varies on its own. */
    static boolean evenTempo(Context c) {
        return sp(c).getBoolean(EVEN_TEMPO, true);
    }

    @SuppressWarnings("deprecation")
    static boolean evenTempoShared(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getBoolean(EVEN_TEMPO, true);
    }

    static void setEvenTempo(Context c, boolean on) {
        sp(c).edit().putBoolean(EVEN_TEMPO, on).apply();
    }

    static void setSpeed(Context c, float v) {
        sp(c).edit().putFloat(SPEED, v).apply();
    }

    static void setPauseMs(Context c, int v) {
        sp(c).edit().putInt(PAUSE, v).apply();
    }

    private static final String HOME_VOICE = "home_voice";
    private static final String GRIM_TIMBRE = "grim_timbre_";
    private static final String GRIM_PITCH = "grim_pitch_";

    /** The voice on the main screen and in the workshop. */
    static int homeVoice(Context c) {
        int v = sp(c).getInt(HOME_VOICE, 1);
        return Cast.available(c, v) ? v : 1;
    }

    static void setHomeVoice(Context c, int v) {
        sp(c).edit().putInt(HOME_VOICE, v).apply();
    }

    /** Sample makeup of a voice: timbre tilt, warmer below zero. */
    static int grimTimbre(Context c, int voice) {
        return sp(c).getInt(GRIM_TIMBRE + voice, defaultTimbre(voice));
    }

    /** Sample makeup of a voice: pitch shift in semitones. */
    static int grimPitch(Context c, int voice) {
        return sp(c).getInt(GRIM_PITCH + voice, defaultPitch(voice));
    }

    @SuppressWarnings("deprecation")
    static int grimTimbreShared(Context c, int voice) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getInt(GRIM_TIMBRE + voice, defaultTimbre(voice));
    }

    @SuppressWarnings("deprecation")
    static int grimPitchShared(Context c, int voice) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getInt(GRIM_PITCH + voice, defaultPitch(voice));
    }

    /** The voice process picks it up with the next sample it reads. */
    // Starting makeup per voice, measured on the bench against good human readers (breathiness
    // and top band): the smooth, dark samples get a little brightness and air; the voices whose
    // samples are already breathy or rough are left as they are. The first voice's sample was
    // made anew and is bright by itself, so it needs none; so was the second voice's. By voice
    // number from 1; the eighth number has no voice.
    private static final int[] TIMBRE_DEFAULT = {0, 0, 0, 0, 5, 0, 0, 0, 0, 0};
    private static final int[] AIR_DEFAULT = {0, 0, 0, 0, 3, 0, 1, 0, 0, 0};
    /** Pitch makeup by voice: the later voices were set by ear on the phone. */
    private static final int[] PITCH_DEFAULT = {0, 0, 0, 0, 0, 0, 4, -1, 0, 1};

    static int defaultTimbre(int voice) {
        return voice >= 1 && voice < TIMBRE_DEFAULT.length ? TIMBRE_DEFAULT[voice] : 0;
    }

    static int defaultPitch(int voice) {
        return voice >= 1 && voice < PITCH_DEFAULT.length ? PITCH_DEFAULT[voice] : 0;
    }

    static int defaultAir(int voice) {
        return voice >= 1 && voice < AIR_DEFAULT.length ? AIR_DEFAULT[voice] : 0;
    }

    private static final String GRIM_AIR = "grim_air_";
    private static final String OWN_TEMPO = "own_tempo_";
    private static final String PHONE_VOICE = "phone_voice";

    /** Whether the bot's replies and the words in the headphones are said by the phone's own engine. */
    static boolean phoneVoice(Context c) {
        return sp(c).getBoolean(PHONE_VOICE, false);
    }

    @SuppressWarnings("deprecation")
    static boolean phoneVoiceShared(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getBoolean(PHONE_VOICE, false);
    }

    static void setPhoneVoice(Context c, boolean on) {
        sp(c).edit().putBoolean(PHONE_VOICE, on).commit();
    }
    /** A voice's own pace on top of the common tempo, in steps of five per cent. */
    static final int OWN_TEMPO_MIN = -6;
    static final int OWN_TEMPO_MAX = 6;
    private static final float OWN_TEMPO_STEP = 0.05f;
    /** By voice: every voice reads at the common tempo until the person sets its own. */
    private static final int[] OWN_TEMPO_DEFAULT = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    static int defaultOwnTempo(int voice) {
        return voice >= 1 && voice < OWN_TEMPO_DEFAULT.length ? OWN_TEMPO_DEFAULT[voice] : 0;
    }

    static int ownTempo(Context c, int voice) {
        return clampTempo(sp(c).getInt(OWN_TEMPO + voice, defaultOwnTempo(voice)));
    }

    /** For the voice process: the pace as a factor, 1 for the common tempo. */
    @SuppressWarnings("deprecation")
    static float ownTempoFactorShared(Context c, int voice) {
        int t = c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getInt(OWN_TEMPO + voice, defaultOwnTempo(voice));
        return 1f + OWN_TEMPO_STEP * clampTempo(t);
    }

    static void setOwnTempo(Context c, int voice, int steps) {
        sp(c).edit().putInt(OWN_TEMPO + voice, clampTempo(steps)).commit();
        BookPlayer.revoice(c);
    }

    private static int clampTempo(int t) {
        return Math.max(OWN_TEMPO_MIN, Math.min(OWN_TEMPO_MAX, t));
    }
    private static final String FULL_DECODER = "full_decoder";
    private static final String LAB_TEXT = "lab_text";
    private static final String LAST_RATIO = "last_ratio";

    /** Sample makeup of a voice: breath noise, 0 to Grim.AIR_MAX. */
    static int grimAir(Context c, int voice) {
        return sp(c).getInt(GRIM_AIR + voice, defaultAir(voice));
    }

    @SuppressWarnings("deprecation")
    static int grimAirShared(Context c, int voice) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getInt(GRIM_AIR + voice, defaultAir(voice));
    }

    static void setGrimAir(Context c, int voice, int air) {
        sp(c).edit().putInt(GRIM_AIR + voice, air).commit();
        BookPlayer.revoice(c);
    }

    /** The trial: the sound decoder kept whole instead of the compressed one. */
    static boolean fullDecoder(Context c) {
        return sp(c).getBoolean(FULL_DECODER, false);
    }

    @SuppressWarnings("deprecation")
    static boolean fullDecoderShared(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getBoolean(FULL_DECODER, false);
    }

    static void setFullDecoder(Context c, boolean on) {
        sp(c).edit().putBoolean(FULL_DECODER, on).commit();
    }

    private static final String USER_FEMALE = "user_female";

    /** How the voices address the user in free talk: as a woman, or (by default) as a man. */
    static boolean userFemale(Context c) {
        return sp(c).getBoolean(USER_FEMALE, false);
    }

    static void setUserFemale(Context c, boolean on) {
        sp(c).edit().putBoolean(USER_FEMALE, on).apply();
    }

    /** The text in the lab, kept until it is changed or cleared. */
    static String labText(Context c) {
        return sp(c).getString(LAB_TEXT, "");
    }

    static void setLabText(Context c, String text) {
        sp(c).edit().putString(LAB_TEXT, text).apply();
    }

    /** How many times faster than speech the voice made the last thing it said; 0 if unknown. */
    @SuppressWarnings("deprecation")
    static float lastRatio(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getFloat(LAST_RATIO, 0f);
    }

    @SuppressWarnings("deprecation")
    static void setLastRatio(Context c, float ratio) {
        c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).edit().putFloat(LAST_RATIO, ratio).commit();
    }

    static void setGrim(Context c, int voice, int timbre, int pitch) {
        sp(c).edit().putInt(GRIM_TIMBRE + voice, timbre).putInt(GRIM_PITCH + voice, pitch).commit();
        BookPlayer.revoice(c);
    }

    private static final String LOOK = "look";

    /** The app's look; see Palette.LOOKS. */
    static int look(Context c) {
        return Palette.known(sp(c).getInt(LOOK, Palette.BORDEAUX));
    }

    static void setLook(Context c, int look) {
        sp(c).edit().putInt(LOOK, look).commit();
    }

    private static final String SHELF_LIST = "shelf_list";
    private static final String SHELF_ORDER = "shelf_order";
    static final int SHELF_RECENT = 0;
    static final int SHELF_BY_TITLE = 1;
    static final int SHELF_READ = 2;

    /** Whether the shelf shows books as a list instead of covers. */
    static boolean shelfList(Context c) {
        return sp(c).getBoolean(SHELF_LIST, false);
    }

    static void setShelfList(Context c, boolean list) {
        sp(c).edit().putBoolean(SHELF_LIST, list).apply();
    }

    /** The list's order: recent, by title, or only the books read through. */
    static int shelfOrder(Context c) {
        return Math.max(0, Math.min(2, sp(c).getInt(SHELF_ORDER, SHELF_RECENT)));
    }

    static void setShelfOrder(Context c, int order) {
        sp(c).edit().putInt(SHELF_ORDER, order).apply();
    }

    private static final String INTRO_SEEN = "intro_seen";
    private static final String BATTERY_WARN = "battery_warn";
    /** The key of the earlier route scout: whoever let it look at the navigator now hears the turns. */
    private static final String NAV_WHISPER = "nav_scout";
    private static final String NAV_STREET = "nav_street";

    /** Whether turn hints from a navigator are said into the headphones; read from the voice process. */
    static boolean navWhisperShared(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getBoolean(NAV_WHISPER, false);
    }

    static void setNavWhisper(Context c, boolean on) {
        sp(c).edit().putBoolean(NAV_WHISPER, on).commit();
    }

    /** Whether a turn said at the turn itself names the street too; read from the voice process. */
    static boolean navStreetShared(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getBoolean(NAV_STREET, false);
    }

    static void setNavStreet(Context c, boolean on) {
        sp(c).edit().putBoolean(NAV_STREET, on).commit();
    }

    private static final String MORNING = "morning";
    private static final String MORNING_WEATHER = "morning_weather";
    private static final String MORNING_BATTERY = "morning_battery";

    /** Whether the first headphones of the day bring a morning line; off until asked for. Read from the voice process. */
    static boolean morningShared(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getBoolean(MORNING, false);
    }

    static void setMorning(Context c, boolean on) {
        sp(c).edit().putBoolean(MORNING, on).commit();
    }

    /** Whether the morning line says the weather, going online for it once. */
    static boolean morningWeatherShared(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getBoolean(MORNING_WEATHER, true);
    }

    static void setMorningWeather(Context c, boolean on) {
        sp(c).edit().putBoolean(MORNING_WEATHER, on).commit();
    }

    /** Whether the morning line says the headphones' charge, when they tell it. */
    static boolean morningBatteryShared(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getBoolean(MORNING_BATTERY, false);
    }

    static void setMorningBattery(Context c, boolean on) {
        sp(c).edit().putBoolean(MORNING_BATTERY, on).commit();
    }

    private static final String MISSED_SAY = "missed_say";
    private static final String MISSED_FULL = "missed_full";

    /** Whether Hora says there are new notifications once it may speak again; read from the voice process. */
    static boolean missedSayShared(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getBoolean(MISSED_SAY, true);
    }

    static void setMissedSay(Context c, boolean on) {
        sp(c).edit().putBoolean(MISSED_SAY, on).commit();
    }

    /** Whether that word names the apps and who writes, never what; read from the voice process. */
    static boolean missedFullShared(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getBoolean(MISSED_FULL, false);
    }

    static void setMissedFull(Context c, boolean on) {
        sp(c).edit().putBoolean(MISSED_FULL, on).commit();
    }

    /** Whether the reader says so when the battery runs low; read from the voice process too. */
    static boolean batteryWarnShared(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getBoolean(BATTERY_WARN, true);
    }

    static void setBatteryWarn(Context c, boolean on) {
        sp(c).edit().putBoolean(BATTERY_WARN, on).apply();
    }

    /** Whether the title page has been shown once; until then the app opens on it. */
    static boolean introSeen(Context c) {
        return sp(c).getBoolean(INTRO_SEEN, false);
    }

    static void setIntroSeen(Context c) {
        sp(c).edit().putBoolean(INTRO_SEEN, true).apply();
    }

    private static final String TROUPE_HINT = "troupe_hint";
    /** How many voice changes the hint under the troupe stays for. */
    private static final int TROUPE_HINT_TIMES = 3;

    static int troupeHintsLeft(Context c) {
        return sp(c).getInt(TROUPE_HINT, TROUPE_HINT_TIMES);
    }

    static void troupeHintUsed(Context c) {
        int left = troupeHintsLeft(c);
        if (left > 0) {
            sp(c).edit().putInt(TROUPE_HINT, left - 1).apply();
        }
    }

    /** Apps whose notifications are read into headphones. None by default. */
    static java.util.Set<String> heraldApps(Context c) {
        return new java.util.HashSet<String>(sp(c).getStringSet(HERALD_APPS,
                java.util.Collections.<String>emptySet()));
    }

    static boolean heraldHearsShared(Context c, String pkg) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS)
                .getStringSet(HERALD_APPS, java.util.Collections.<String>emptySet()).contains(pkg);
    }

    static void setHeraldApp(Context c, String pkg, boolean on) {
        java.util.Set<String> apps = heraldApps(c);
        if (on) {
            apps.add(pkg);
        } else {
            apps.remove(pkg);
        }
        sp(c).edit().putStringSet(HERALD_APPS, apps).commit();
    }

    /** Quiet hours: when on, notifications stay unread between from and to, minutes after midnight. */
    static boolean quiet(Context c) {
        return sp(c).getBoolean(QUIET, false);
    }

    static int quietFrom(Context c) {
        return sp(c).getInt(QUIET_FROM, QUIET_FROM_DEFAULT);
    }

    static int quietTo(Context c) {
        return sp(c).getInt(QUIET_TO, QUIET_TO_DEFAULT);
    }

    static void setQuiet(Context c, boolean on) {
        sp(c).edit().putBoolean(QUIET, on).commit();
    }

    static void setQuietFrom(Context c, int minutes) {
        sp(c).edit().putInt(QUIET_FROM, minutes).commit();
    }

    static void setQuietTo(Context c, int minutes) {
        sp(c).edit().putInt(QUIET_TO, minutes).commit();
    }

    /** Whether the given minute of the day falls in quiet hours; the span may cross midnight. */
    static boolean quietAtShared(Context c, int minute) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS);
        if (!p.getBoolean(QUIET, false)) {
            return false;
        }
        int from = p.getInt(QUIET_FROM, QUIET_FROM_DEFAULT);
        int to = p.getInt(QUIET_TO, QUIET_TO_DEFAULT);
        if (from == to) {
            return false;
        }
        return from < to ? minute >= from && minute < to : minute >= from || minute < to;
    }

    private static final String BREATH = "breath_";
    /** Each voice's way of breathing by default, from measured readers; see Breath. */
    // Every voice reads whole sentences by default, as it does for other apps: cut into breath
    // groups, each piece lost the sentence's melody and ended as if the sentence had.
    private static final int[] BREATH_DEFAULT = {Breath.NONE, Breath.NONE, Breath.NONE,
        Breath.NONE, Breath.NONE, Breath.NONE, Breath.NONE, Breath.NONE, Breath.NONE, Breath.NONE};

    static int breathShared(Context c, int voice) {
        int v = Math.max(1, Math.min(Cast.LAST, voice));
        int fallback = v < BREATH_DEFAULT.length ? BREATH_DEFAULT[v] : Breath.NONE;
        int m = c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getInt(BREATH + v, fallback);
        return Breath.known(m) ? m : fallback;
    }

    static void setBreath(Context c, int voice, int manner) {
        sp(c).edit().putInt(BREATH + Math.max(1, Math.min(Cast.LAST, voice)), manner).commit();
    }
}
