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
    private LinearLayout apps;
    private EditText search;
    /** One app: package, name, and the name made unique when two apps share it. */
    private final List<String[]> rows = new ArrayList<String[]>();
    private final Map<String, Drawable> icons = new HashMap<String, Drawable>();

    private final AudioDeviceCallback devices = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            showState();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            showState();
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
        Kit.setValue(voiceRow, Cast.name(this, Prefs.role(this, Cast.HERALD)));
        audio.registerAudioDeviceCallback(devices, main);
    }

    @Override
    protected void onPause() {
        audio.unregisterAudioDeviceCallback(devices);
        super.onPause();
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
