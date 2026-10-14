package com.shumtugle.hora;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Notifications read into headphones, built from the shared parts: what
 * stands in the way now (access, headphones), the voice, quiet hours, and
 * which apps, chosen ones on top, with a search over the rest.
 */
public final class HeraldActivity extends Activity {
    private int built;
    private AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout state;
    private View voiceRow;
    private View fromRow;
    private View toRow;
    private TextView appsTitle;
    private View streetRow;
    private View missedFullRow;
    private LinearLayout earsPlate;
    /** An answer given in the notification while this screen is open shows at once. */
    private final android.content.BroadcastReceiver earsChanged = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, Intent i) {
            showEars();
        }
    };
    /** A device just connected is written down by the reader a moment later; the list looks again then. */
    private final Runnable earsLater = new Runnable() {
        @Override
        public void run() {
            showEars();
        }
    };
    private View morningWeatherRow;
    private View morningBatteryRow;
    private View morningSampleRow;
    private static final int REQUEST_NEARBY = 7;
    private View sampleRow;
    private LinearLayout apps;
    private EditText search;
    /** One app: package, name, and the name made unique when two apps share it. */
    private final List<String[]> rows = new ArrayList<String[]>();
    private final Map<String, Drawable> icons = new HashMap<String, Drawable>();

    private final AudioDeviceCallback devices = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            showState();
            showEars();
            main.removeCallbacks(earsLater);
            main.postDelayed(earsLater, 2500);
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            showState();
            showEars();
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        built = Ui.prepare(this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        LinearLayout list = Kit.room(this, getString(R.string.herald_title));
        list.addView(Kit.lead(this, getString(R.string.herald_lead)));

        list.addView(Kit.section(this, getString(R.string.herald_state)));
        state = Ui.column(this);
        list.addView(state, Kit.wide());

        list.addView(Kit.section(this, getString(R.string.herald_voice_section)));
        LinearLayout voice = Kit.plate(this);
        voiceRow = Kit.rowNav(this, getString(R.string.herald_voice_row), "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HeraldActivity.this, RolesActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
        });
        voice.addView(voiceRow);
        list.addView(voice, Kit.wide());

        list.addView(Kit.section(this, getString(R.string.herald_quiet_section)));
        LinearLayout quiet = Kit.plate(this);
        quiet.addView(Kit.rowToggle(this, getString(R.string.herald_quiet_switch), null, Prefs.quiet(this),
                new Kit.Flip() {
                    @Override
                    public void flipped(boolean on) {
                        Prefs.setQuiet(HeraldActivity.this, on);
                        showQuiet();
                    }
                }));
        fromRow = Kit.rowNav(this, getString(R.string.herald_quiet_from), "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Kit.timeSheet(HeraldActivity.this, getString(R.string.herald_quiet_from_title),
                        Prefs.quietFrom(HeraldActivity.this), new Kit.TimeSet() {
                            @Override
                            public void set(int minutes) {
                                Prefs.setQuietFrom(HeraldActivity.this, minutes);
                                showQuiet();
                            }
                        });
            }
        });
        toRow = Kit.rowNav(this, getString(R.string.herald_quiet_to), "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Kit.timeSheet(HeraldActivity.this, getString(R.string.herald_quiet_to_title),
                        Prefs.quietTo(HeraldActivity.this), new Kit.TimeSet() {
                            @Override
                            public void set(int minutes) {
                                Prefs.setQuietTo(HeraldActivity.this, minutes);
                                showQuiet();
                            }
                        });
            }
        });
        quiet.addView(fromRow);
        quiet.addView(toRow);
        list.addView(quiet, Kit.wide());
        showQuiet();

        // Whose ears each wireless device is: asked once per device, every answer here to see and change.
        list.addView(Kit.section(this, getString(R.string.ears_section)));
        earsPlate = Kit.plate(this);
        list.addView(earsPlate, Kit.wide());
        TextView earsNote = Ui.text(this, getString(R.string.ears_note), 13, Palette.MUTED);
        earsNote.setLineSpacing(0, 1.25f);
        list.addView(earsNote, Kit.below(this, 12));

        // What came while Hora had to be silent: said once it may speak again, briefly or in full.
        LinearLayout missed = Kit.plate(this);
        missed.addView(Kit.rowToggle(this, getString(R.string.missed_switch), getString(R.string.missed_switch_sub),
                Prefs.missedSayShared(this), new Kit.Flip() {
                    @Override
                    public void flipped(boolean on) {
                        Prefs.setMissedSay(HeraldActivity.this, on);
                        showMissed();
                    }
                }));
        missed.addView(Kit.divider(this));
        missedFullRow = Kit.rowToggle(this, getString(R.string.missed_full_switch), getString(R.string.missed_full_sub),
                Prefs.missedFullShared(this), new Kit.Flip() {
                    @Override
                    public void flipped(boolean on) {
                        Prefs.setMissedFull(HeraldActivity.this, on);
                    }
                });
        missed.addView(missedFullRow);
        LinearLayout.LayoutParams mp = Kit.wide();
        mp.topMargin = Ui.dp(this, 12);
        list.addView(missed, mp);
        showMissed();

        // A word into the headphones when the battery runs low: at 15 percent, and again at 5.
        LinearLayout power = Kit.plate(this);
        power.addView(Kit.rowToggle(this, getString(R.string.battery_switch), getString(R.string.battery_switch_sub),
                Prefs.batteryWarnShared(this), new Kit.Flip() {
                    @Override
                    public void flipped(boolean on) {
                        Prefs.setBatteryWarn(HeraldActivity.this, on);
                    }
                }));
        LinearLayout.LayoutParams pp = Kit.wide();
        pp.topMargin = Ui.dp(this, 12);
        list.addView(power, pp);

        // The morning line: the first headphones of the day, off until asked for.
        list.addView(Kit.section(this, getString(R.string.morning_section)));
        LinearLayout morning = Kit.plate(this);
        morning.addView(Kit.rowToggle(this, getString(R.string.morning_switch), getString(R.string.morning_switch_sub),
                Prefs.morningShared(this), new Kit.Flip() {
                    @Override
                    public void flipped(boolean on) {
                        Prefs.setMorning(HeraldActivity.this, on);
                        Diag.mark(HeraldActivity.this, "herald: morning line " + (on ? "on" : "off"));
                        showMorning();
                    }
                }));
        morning.addView(Kit.divider(this));
        morningWeatherRow = Kit.rowToggle(this, getString(R.string.morning_weather), getString(R.string.morning_weather_sub),
                Prefs.morningWeatherShared(this), new Kit.Flip() {
                    @Override
                    public void flipped(boolean on) {
                        Prefs.setMorningWeather(HeraldActivity.this, on);
                    }
                });
        morning.addView(morningWeatherRow);
        morning.addView(Kit.divider(this));
        morningBatteryRow = Kit.rowToggle(this, getString(R.string.morning_battery_switch),
                getString(R.string.morning_battery_sub), Prefs.morningBatteryShared(this), new Kit.Flip() {
                    @Override
                    public void flipped(boolean on) {
                        Prefs.setMorningBattery(HeraldActivity.this, on);
                        if (on) {
                            askNearby();
                        }
                    }
                });
        morning.addView(morningBatteryRow);
        morning.addView(Kit.divider(this));
        morningSampleRow = Kit.rowNav(this, getString(R.string.nav_sample), "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                morningSample();
            }
        });
        morning.addView(morningSampleRow);
        list.addView(morning, Kit.wide());
        TextView morningNote = Ui.text(this, getString(R.string.morning_note), 13, Palette.MUTED);
        morningNote.setLineSpacing(0, 1.25f);
        list.addView(morningNote, Kit.below(this, 12));
        showMorning();

        // Turn hints from a navigator, whispered into the headphones.
        list.addView(Kit.section(this, getString(R.string.nav_section)));
        LinearLayout route = Kit.plate(this);
        route.addView(Kit.rowToggle(this, getString(R.string.nav_switch), getString(R.string.nav_switch_sub),
                Prefs.navWhisperShared(this), new Kit.Flip() {
                    @Override
                    public void flipped(boolean on) {
                        Prefs.setNavWhisper(HeraldActivity.this, on);
                        Diag.mark(HeraldActivity.this, "nav: turn hints " + (on ? "on" : "off"));
                        showRoute();
                    }
                }));
        route.addView(Kit.divider(this));
        streetRow = Kit.rowToggle(this, getString(R.string.nav_street), getString(R.string.nav_street_sub),
                Prefs.navStreetShared(this), new Kit.Flip() {
                    @Override
                    public void flipped(boolean on) {
                        Prefs.setNavStreet(HeraldActivity.this, on);
                    }
                });
        route.addView(streetRow);
        route.addView(Kit.divider(this));
        sampleRow = Kit.rowNav(this, getString(R.string.nav_sample), "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sample();
            }
        });
        route.addView(sampleRow);
        list.addView(route, Kit.wide());
        TextView note = Ui.text(this, getString(R.string.nav_note), 13, Palette.MUTED);
        note.setLineSpacing(0, 1.25f);
        list.addView(note, Kit.below(this, 12));
        showRoute();

        appsTitle = Kit.section(this, "");
        list.addView(appsTitle);
        search = new EditText(this);
        search.setHint(R.string.herald_apps_search);
        search.setSingleLine(true);
        search.setTextColor(Palette.INK);
        search.setHintTextColor(Palette.HINT);
        search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17);
        search.setTypeface(Palette.body(this));
        search.setBackground(Ui.round(Palette.SURFACE, 28, this));
        search.setPadding(Kit.dp(this, 20), 0, Kit.dp(this, 20), 0);
        search.setMinHeight(Kit.dp(this, Kit.ROW));
        Ui.field(search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                showApps();
            }
        });
        list.addView(search, Kit.wide());
        apps = Kit.plate(this);
        TextView loading = Ui.text(this, getString(R.string.herald_apps_loading), 15, Palette.MUTED);
        loading.setPadding(Kit.dp(this, 16), Kit.dp(this, 16), Kit.dp(this, 16), Kit.dp(this, 16));
        apps.addView(loading);
        list.addView(apps, Kit.below(this, 12));
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        showState();
        showEars();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(earsChanged, new android.content.IntentFilter(EarsAnswer.ACTION_CHANGED),
                    RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(earsChanged, new android.content.IntentFilter(EarsAnswer.ACTION_CHANGED), Herald.SELF, null);
        }
        Kit.setValue(voiceRow, Cast.name(this, Prefs.role(this, Cast.HERALD)));
        audio.registerAudioDeviceCallback(devices, main);
    }

    @Override
    protected void onPause() {
        audio.unregisterAudioDeviceCallback(devices);
        super.onPause();
        main.removeCallbacks(earsLater);
        try {
            unregisterReceiver(earsChanged);
        } catch (IllegalArgumentException ignored) {
            // Not registered: the screen was stale and closed before it registered.
        }
    }

    /** What stands between a notification and the ear, and the one way past it. */
    private void showState() {
        state.removeAllViews();
        boolean granted = granted();
        LinearLayout plate = Kit.plate(this);
        plate.addView(Kit.status(this, getString(granted ? R.string.herald_access_on : R.string.herald_access_off),
                granted ? Kit.Mood.FINE : Kit.Mood.TROUBLE));
        AudioDeviceInfo ear = Earpiece.find(audio);
        CharSequence name = ear == null ? null : ear.getProductName();
        plate.addView(Kit.status(this, ear == null ? getString(R.string.herald_ears_none)
                : getString(R.string.herald_ears_on, name == null || name.length() == 0 ? "\u2014" : name),
                ear == null ? Kit.Mood.UNKNOWN : Kit.Mood.FINE));
        state.addView(plate, Kit.wide());
        if (granted) {
            state.addView(Kit.link(this, getString(R.string.herald_access_settings), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openAccess();
                }
            }));
            return;
        }
        state.addView(Kit.primary(this, getString(R.string.herald_grant), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccess();
            }
        }), Kit.below(this, 16));
        TextView hint = Ui.text(this, getString(R.string.herald_restricted), 13, Palette.MUTED);
        hint.setLineSpacing(0, 1.25f);
        state.addView(hint, Kit.below(this, 16));
        state.addView(Kit.link(this, getString(R.string.herald_app_details), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            }
        }));
    }

    private void showQuiet() {
        Kit.setValue(fromRow, clock(Prefs.quietFrom(this)));
        Kit.setValue(toRow, clock(Prefs.quietTo(this)));
        boolean on = Prefs.quiet(this);
        for (View v : new View[] {fromRow, toRow}) {
            v.setAlpha(on ? 1f : 0.4f);
            v.setEnabled(on);
        }
    }

    /** Every wireless device met, latest first, with what it hears; wired headphones last, always one's own. */
    private void showEars() {
        earsPlate.removeAllViews();
        android.media.AudioDeviceInfo now = Ears.wireless(audio);
        String nowKey = now == null ? null : Ears.key(now);
        for (Object[] e : Ears.all(this)) {
            final String key = (String) e[0];
            final String name = (String) e[1];
            int state = (Integer) e[2];
            String sub = getString(state == Ears.MINE ? R.string.ears_mine
                    : state == Ears.OPEN ? R.string.ears_open : R.string.ears_unknown);
            earsPlate.addView(earsRow(name, sub, key.equals(nowKey), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    earsSheet(key, name);
                }
            }));
            earsPlate.addView(Kit.divider(this));
        }
        earsPlate.addView(earsRow(getString(R.string.ears_wired), getString(R.string.ears_wired_sub), false, null));
    }

    private View earsRow(CharSequence title, CharSequence sub, boolean current, View.OnClickListener l) {
        LinearLayout r = Ui.row(this);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Kit.dp(this, 64));
        r.setPadding(Kit.dp(this, 16), Kit.dp(this, 10), Kit.dp(this, 16), Kit.dp(this, 10));
        LinearLayout words = Ui.column(this);
        LinearLayout top = Ui.row(this);
        TextView t = Ui.text(this, title, 17, Palette.INK);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (current) {
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            np.leftMargin = Kit.dp(this, 8);
            top.addView(Ui.text(this, getString(R.string.ears_now), 13, Palette.ACCENT_TEXT), np);
        }
        words.addView(top);
        TextView s = Ui.text(this, sub, 13, Palette.MUTED);
        words.addView(s);
        r.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (l != null) {
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cp.leftMargin = Kit.dp(this, 12);
            r.addView(Ui.text(this, "\u203a", 20, Palette.HINT), cp);
            r.setBackground(Ui.pressable(new android.graphics.drawable.ColorDrawable(0)));
            r.setOnClickListener(l);
        }
        return r;
    }

    /** One device: own headphones, or a speaker or a car; chosen at once, and forgotten if asked. */
    private void earsSheet(final String key, String name) {
        LinearLayout box = Ui.column(this);
        final View[] cards = new View[2];
        final int[] states = {Ears.MINE, Ears.OPEN};
        int[][] words = {{R.string.ears_choice_mine, R.string.ears_choice_mine_sub},
            {R.string.ears_choice_open, R.string.ears_choice_open_sub}};
        for (int i = 0; i < 2; i++) {
            final int k = i;
            cards[i] = choiceCard(getString(words[i][0]), getString(words[i][1]));
            cards[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Ears.set(HeraldActivity.this, key, states[k]);
                    Diag.mark(HeraldActivity.this, "herald: device set, "
                            + (states[k] == Ears.MINE ? "own headphones" : "speaker or car"));
                    sendBroadcast(new Intent(EarsAnswer.ACTION_CHANGED).setPackage(getPackageName()));
                    markCards(cards, states, states[k]);
                    showEars();
                }
            });
            LinearLayout.LayoutParams lp = Kit.wide();
            lp.topMargin = i == 0 ? 0 : Kit.dp(this, 10);
            box.addView(cards[i], lp);
        }
        markCards(cards, states, Ears.state(this, key));
        final android.app.Dialog[] d = new android.app.Dialog[1];
        TextView forget = Kit.link(this, getString(R.string.ears_forget), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ears.forget(HeraldActivity.this, key);
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(Ears.NOTE_TAG, Ears.noteId(key));
                }
                sendBroadcast(new Intent(EarsAnswer.ACTION_CHANGED).setPackage(getPackageName()));
                showEars();
                if (d[0] != null) {
                    d[0].dismiss();
                }
            }
        });
        box.addView(forget, Kit.below(this, 16));
        d[0] = Kit.liveSheet(this, name, box);
        d[0].show();
    }

    private View choiceCard(String title, String sub) {
        LinearLayout r = Ui.row(this);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setPadding(Kit.dp(this, 16), Kit.dp(this, 14), Kit.dp(this, 16), Kit.dp(this, 14));
        View dot = new View(this);
        r.addView(dot, new LinearLayout.LayoutParams(Kit.dp(this, 18), Kit.dp(this, 18)));
        LinearLayout words = Ui.column(this);
        words.addView(Ui.text(this, title, 17, Palette.INK));
        TextView s = Ui.text(this, sub, 13, Palette.MUTED);
        s.setLineSpacing(0, 1.2f);
        words.addView(s);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        wp.leftMargin = Kit.dp(this, 14);
        r.addView(words, wp);
        r.setTag(dot);
        return r;
    }

    /** The chosen card is raised and its dot filled; the other is a quiet outline. */
    private void markCards(View[] cards, int[] states, int chosen) {
        for (int i = 0; i < cards.length; i++) {
            boolean on = states[i] == chosen;
            cards[i].setBackground(on ? Ui.round(Palette.RAISED, 18, this) : outline(18));
            ((View) cards[i].getTag()).setBackground(on ? Ui.oval(Palette.ACCENT, 0, 0, this)
                    : Ui.oval(android.graphics.Color.TRANSPARENT, Palette.HINT, 2, this));
        }
    }

    private android.graphics.drawable.Drawable outline(float radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(android.graphics.Color.TRANSPARENT);
        g.setCornerRadius(Kit.dp(this, radiusDp));
        g.setStroke(Math.max(1, Kit.dp(this, 1)), Palette.LINE);
        return g;
    }

    /** The parts of the morning line only matter while it is on; they dim rather than hide, so nothing jumps. */
    private void showMorning() {
        boolean on = Prefs.morningShared(this);
        for (View v : new View[] {morningWeatherRow, morningBatteryRow, morningSampleRow}) {
            v.setAlpha(on ? 1f : 0.4f);
            v.setEnabled(on);
        }
    }

    /** The headphones' charge needs the nearby devices permission from Android 12 on; asked only when switched on. */
    private void askNearby() {
        if (android.os.Build.VERSION.SDK_INT >= 31
                && checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {"android.permission.BLUETOOTH_CONNECT"}, REQUEST_NEARBY);
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] names, int[] results) {
        super.onRequestPermissionsResult(request, names, results);
        if (request != REQUEST_NEARBY) {
            return;
        }
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            // Refused: the switch goes back off, so it never promises what Hora cannot see.
            Prefs.setMorningBattery(this, false);
            Kit.Toggle t = toggleIn(morningBatteryRow);
            if (t != null) {
                t.set(false);
            }
            android.widget.Toast.makeText(this, R.string.morning_battery_denied, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private static Kit.Toggle toggleIn(View v) {
        if (v instanceof Kit.Toggle) {
            return (Kit.Toggle) v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Kit.Toggle t = toggleIn(g.getChildAt(i));
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    /** The morning line said now, as it would be: into the headphones, or a word here on why not. */
    private void morningSample() {
        if (!granted()) {
            Kit.setValue(morningSampleRow, getString(R.string.nav_sample_no_access));
            return;
        }
        if (Earpiece.find(audio) == null) {
            Kit.setValue(morningSampleRow, getString(R.string.nav_sample_no_ears));
            return;
        }
        Kit.setValue(morningSampleRow, "");
        sendBroadcast(new Intent(Herald.ACTION_MORNING_SAMPLE).setPackage(getPackageName()));
    }

    /** "In full" only matters while the word is said at all; it dims rather than hides, so nothing jumps. */
    private void showMissed() {
        boolean on = Prefs.missedSayShared(this);
        missedFullRow.setAlpha(on ? 1f : 0.4f);
        missedFullRow.setEnabled(on);
    }

    /** The street row only matters while the hints are on; it dims rather than hides, so nothing jumps. */
    private void showRoute() {
        boolean on = Prefs.navWhisperShared(this);
        streetRow.setAlpha(on ? 1f : 0.4f);
        streetRow.setEnabled(on);
    }

    /**
     * An early hint for a right turn, said by the notification reader into the headphones. When
     * it cannot be heard, the row says why instead of staying silent.
     */
    private void sample() {
        if (!granted()) {
            Kit.setValue(sampleRow, getString(R.string.nav_sample_no_access));
            return;
        }
        if (Earpiece.find(audio) == null) {
            Kit.setValue(sampleRow, getString(R.string.nav_sample_no_ears));
            return;
        }
        Kit.setValue(sampleRow, "");
        sendBroadcast(new Intent(Herald.ACTION_ROUTE_SAMPLE).setPackage(getPackageName()));
    }

    private static String clock(int minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private boolean granted() {
        return Herald.granted(this);
    }

    private void openAccess() {
        Herald.openAccess(this);
    }

    /** Apps with a launcher entry; chosen ones first, then by name; same names told apart by package. */
    private void loadApps() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final PackageManager pm = getPackageManager();
                Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                final List<String[]> found = new ArrayList<String[]>();
                final Map<String, Drawable> pics = new HashMap<String, Drawable>();
                Map<String, Integer> names = new HashMap<String, Integer>();
                List<String> taken = new ArrayList<String>();
                for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || taken.contains(pkg)) {
                        continue;
                    }
                    taken.add(pkg);
                    String label = ri.activityInfo.applicationInfo.loadLabel(pm).toString();
                    found.add(new String[] {pkg, label, null});
                    Integer n = names.get(label);
                    names.put(label, n == null ? 1 : n + 1);
                    try {
                        pics.put(pkg, pm.getApplicationIcon(pkg));
                    } catch (PackageManager.NameNotFoundException e) {
                        // No icon; the row shows the name alone.
                    }
                }
                for (String[] r : found) {
                    if (names.get(r[1]) > 1) {
                        r[2] = r[0];
                    }
                }
                final Set<String> on = Prefs.heraldApps(HeraldActivity.this);
                final Collator byName = Collator.getInstance();
                Collections.sort(found, new Comparator<String[]>() {
                    @Override
                    public int compare(String[] a, String[] b) {
                        boolean ai = on.contains(a[0]);
                        boolean bi = on.contains(b[0]);
                        if (ai != bi) {
                            return ai ? -1 : 1;
                        }
                        return byName.compare(a[1], b[1]);
                    }
                });
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        rows.clear();
                        rows.addAll(found);
                        icons.putAll(pics);
                        showApps();
                    }
                });
            }
        }, "herald-apps").start();
    }

    private void showApps() {
        Set<String> on = Prefs.heraldApps(this);
        appsTitle.setText(getString(R.string.herald_apps_section, on.size()));
        if (rows.isEmpty()) {
            return;
        }
        apps.removeAllViews();
        String q = search.getText().toString().trim().toLowerCase(Locale.getDefault());
        int shown = 0;
        for (String[] r : rows) {
            if (!q.isEmpty() && !r[1].toLowerCase(Locale.getDefault()).contains(q)) {
                continue;
            }
            final String pkg = r[0];
            LinearLayout row = Kit.rowToggle(this, r[1], r[2], on.contains(pkg), new Kit.Flip() {
                @Override
                public void flipped(boolean checked) {
                    Prefs.setHeraldApp(HeraldActivity.this, pkg, checked);
                    appsTitle.setText(getString(R.string.herald_apps_section,
                            Prefs.heraldApps(HeraldActivity.this).size()));
                }
            });
            Drawable d = icons.get(pkg);
            if (d != null) {
                ImageView icon = new ImageView(this);
                icon.setImageDrawable(d);
                icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Kit.dp(this, 32), Kit.dp(this, 32));
                ip.rightMargin = Kit.dp(this, 14);
                row.addView(icon, 0, ip);
            }
            apps.addView(row, Kit.wide());
            shown++;
        }
        if (shown == 0) {
            TextView none = Ui.text(this, getString(R.string.herald_apps_none), 15, Palette.MUTED);
            none.setPadding(Kit.dp(this, 16), Kit.dp(this, 16), Kit.dp(this, 16), Kit.dp(this, 16));
            apps.addView(none);
        }
    }
}
