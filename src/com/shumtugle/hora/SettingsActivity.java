package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Reading settings: pauses, pronunciation, the dictionary, and "About". Voices live in the workshop, roles in the book screen. */
public final class SettingsActivity extends Activity {
    /** The look this screen was built with. */
    private int built;
    private static final int PAUSE_STEP_MS = 50;

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(24), dp(32), dp(24), dp(24));

        list.addView(Ui.header(this, getString(R.string.settings), false),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        section(R.string.voices_section);
        View workshop = row(getString(R.string.lab_title), "\u203a");
        workshop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LabActivity.open(SettingsActivity.this, Prefs.role(SettingsActivity.this, Cast.NARRATOR));
            }
        });
        list.addView(workshop);
        list.addView(rule());
        View brain = row(getString(R.string.brain_title), "\u203a");
        brain.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, BrainActivity.class));
            }
        });
        list.addView(brain);
        list.addView(rule());
        View langs = row(getString(R.string.lang_title), LanguagePack.installed(this, LanguagePack.ENGLISH)
                ? getString(R.string.lang_english) : "\u203a");
        langs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, LanguagesActivity.class));
            }
        });
        list.addView(langs);
        list.addView(rule());
        String where = Weather.placeName(this);
        View place = row(getString(R.string.place_title), where.isEmpty()
                ? (Weather.hasPlace(this) ? getString(R.string.place_here_short) : "\u203a") : where);
        place.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, PlaceActivity.class));
            }
        });
        list.addView(place);
        section(R.string.look);
        lookChooser();
        TextView shelfLabel = text(getString(R.string.settings_shelf_view), 17, 1f);
        LinearLayout.LayoutParams slp = wrap();
        slp.topMargin = dp(20);
        list.addView(shelfLabel, slp);
        LinearLayout.LayoutParams sgp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sgp.topMargin = dp(10);
        list.addView(Kit.segments(this, new String[] {getString(R.string.settings_shelf_covers),
                getString(R.string.settings_shelf_list)}, Prefs.shelfList(this) ? 1 : 0, new Kit.Chosen() {
                    @Override
                    public void chose(int which) {
                        Prefs.setShelfList(SettingsActivity.this, which == 1);
                    }
                }), sgp);
        section(R.string.reading);
        android.widget.Switch slow = new android.widget.Switch(this);
        slow.setText(R.string.slow_by_pauses);
        slow.setTextColor(Palette.INK);
        slow.setAlpha(0.8f);
        slow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        slow.setChecked(Prefs.slowByPauses(this));
        slow.setThumbTintList(ColorStateList.valueOf(Palette.ACCENT));
        slow.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                Prefs.setSlowByPauses(SettingsActivity.this, on);
            }
        });
        slow.setMinHeight(dp(48));
        list.addView(slow);
        android.widget.Switch even = new android.widget.Switch(this);
        even.setText(R.string.even_tempo);
        even.setTextColor(Palette.INK);
        even.setAlpha(0.8f);
        even.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        even.setChecked(Prefs.evenTempo(this));
        even.setThumbTintList(ColorStateList.valueOf(Palette.ACCENT));
        even.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                Prefs.setEvenTempo(SettingsActivity.this, on);
            }
        });
        even.setMinHeight(dp(48));
        list.addView(even);
        list.addView(rule());
        pauseSlider();
        list.addView(rule());
        bedRows();
        list.addView(rule());
        android.widget.Switch respell = new android.widget.Switch(this);
        respell.setText(R.string.respell);
        respell.setTextColor(Palette.INK);
        respell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        respell.setChecked(Prefs.respell(this));
        respell.setThumbTintList(ColorStateList.valueOf(Palette.ACCENT));
        respell.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                Prefs.setRespell(SettingsActivity.this, on);
            }
        });
        respell.setMinHeight(dp(56));
        list.addView(respell);

        section(R.string.herald_section);
        View herald = row(getString(R.string.herald_row), "\u203a");
        herald.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, HeraldActivity.class));
            }
        });
        list.addView(herald);

        section(R.string.tools);
        View journal = row(getString(R.string.journal), "›");
        journal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, JournalActivity.class));
            }
        });
        list.addView(journal);
        list.addView(rule());
        View check = row(getString(R.string.text_check), "\u203a");
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, TextCheckActivity.class));
            }
        });
        list.addView(check);
        list.addView(rule());
        View lex = row(getString(R.string.lexicon), "›");
        lex.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, LexiconActivity.class));
            }
        });
        list.addView(lex);
        list.addView(rule());
        View probe = row(getString(R.string.probe), getString(R.string.probe_hint));
        probe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VoiceService.probe(SettingsActivity.this);
            }
        });
        list.addView(probe);

        View books = row(getString(R.string.export_title), Export.queue(this).length() == 0 ? "\u203a"
                : String.valueOf(Export.queue(this).length()));
        books.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, ExportActivity.class));
            }
        });
        list.addView(books);
        list.addView(rule());

        section(R.string.transfer_section);
        long saved = Transfer.savedAt(this);
        View move = row(getString(R.string.transfer_row), HoraFolder.tree(this) == null
                ? getString(R.string.transfer_no_folder)
                : saved <= 0 ? getString(R.string.transfer_not_yet)
                : java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(new java.util.Date(saved)));
        move.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                transferSheet();
            }
        });
        list.addView(move);
        list.addView(rule());

        section(R.string.about);
        View intro = row(getString(R.string.intro_open), "\u203a");
        intro.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                IntroActivity.open(SettingsActivity.this);
            }
        });
        list.addView(intro);
        list.addView(rule());
        list.addView(row(getString(R.string.build), buildLabel()));
        list.addView(rule());
        list.addView(row(getString(R.string.license), getString(R.string.license_name)));
        list.addView(rule());
        list.addView(row(getString(R.string.yo_dictionary), getString(R.string.yo_dictionary_license)));
        list.addView(rule());
        list.addView(row(getString(R.string.stress_dictionary), getString(R.string.stress_dictionary_license)));
        list.addView(rule());
        list.addView(row(getString(R.string.voices), getString(R.string.voices_license)));
        list.addView(rule());
        list.addView(row(getString(R.string.portraits), getString(R.string.portraits_note)));
        list.addView(rule());
        list.addView(row(getString(R.string.fonts), getString(R.string.fonts_license)));
        list.addView(rule());
        list.addView(row(getString(R.string.weather_data), getString(R.string.weather_license)));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Palette.BG);
        scroll.addView(list);
        setContentView(scroll);
    }

    /** The background under the book: what it is, and how loud, always below the voice. */
    private void bedRows() {
        final String[] kinds = getResources().getStringArray(R.array.bed_kinds);
        final LinearLayout which = row(getString(R.string.bed), kinds[Prefs.bed(this)]);
        which.setClickable(true);
        which.setBackground(Ui.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)));
        which.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LinearLayout choices = new LinearLayout(SettingsActivity.this);
                choices.setOrientation(LinearLayout.VERTICAL);
                final android.app.Dialog[] d = new android.app.Dialog[1];
                for (int k = 0; k < kinds.length; k++) {
                    final int kind = k;
                    LinearLayout r = row(kinds[k], k == Prefs.bed(SettingsActivity.this) ? "\u2713" : "");
                    r.setClickable(true);
                    r.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            Prefs.setBed(SettingsActivity.this, kind);
                            ((TextView) which.getChildAt(1)).setText(kinds[kind]);
                            BedSheet.tell(SettingsActivity.this);
                            d[0].dismiss();
                        }
                    });
                    choices.addView(r);
                }
                d[0] = Kit.sheet(SettingsActivity.this, getString(R.string.bed), choices, null, null);
                d[0].show();
            }
        });
        list.addView(which);
        int cur = Prefs.bedVolume(this);
        final TextView value = text(getString(R.string.bed_volume_value, cur), 17, 0.6f);
        final SeekBar bar = slider(100, cur);
        bar.setOnSeekBarChangeListener(new Listener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                value.setText(getString(R.string.bed_volume_value, p));
                if (user) {
                    Prefs.setBedVolume(SettingsActivity.this, p);
                }
            }
        });
        list.addView(labelled(getString(R.string.bed_volume), value, bar));
    }

    private void pauseSlider() {
        int cur = Prefs.pauseMs(this);
        final TextView value = text(getString(R.string.pause_value, cur), 17, 0.6f);
        final SeekBar bar = slider(Prefs.PAUSE_MAX_MS / PAUSE_STEP_MS, cur / PAUSE_STEP_MS);
        bar.setOnSeekBarChangeListener(new Listener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                int v = p * PAUSE_STEP_MS;
                value.setText(getString(R.string.pause_value, v));
                if (user) {
                    Prefs.setPauseMs(SettingsActivity.this, v);
                }
            }
        });
        list.addView(labelled(getString(R.string.pause), value, bar));
    }

    private android.widget.Button flatButton(String label, View.OnClickListener l) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Palette.ACCENT_TEXT);
        b.setBackground(null);
        b.setOnClickListener(l);
        return b;
    }

    /** Bordeaux or contrast, side by side; the chosen one stands out. */
    /** The looks as tiles, two to a row, each drawn in its own colors so it can be judged before choosing. */
    private void lookChooser() {
        int current = Prefs.look(this);
        int[] names = new int[4];
        names[Palette.BORDEAUX] = R.string.look_bordeaux;
        names[Palette.LAMP] = R.string.look_lamp;
        names[Palette.INK_LOOK] = R.string.look_ink;
        names[Palette.CONTRAST] = R.string.look_contrast;
        LinearLayout row = null;
        for (int i = 0; i < Palette.LOOKS.length; i++) {
            final int which = Palette.LOOKS[i];
            int[] c = Palette.preview(which);
            boolean on = which == current;
            LinearLayout tile = Ui.column(this);
            tile.setPadding(dp(16), dp(14), dp(16), dp(14));
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(c[0]);
            g.setCornerRadius(dp(18));
            g.setStroke(dp(on ? 2 : 1), on ? Palette.ACCENT : Palette.LINE);
            tile.setBackground(g);
            LinearLayout top = Ui.row(this);
            top.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView sample = Ui.text(this, getString(R.string.look_sample), 26, c[2]);
            sample.setTypeface(Palette.display(this));
            top.addView(sample, Ui.weight(1));
            View dot = new View(this);
            dot.setBackground(Ui.oval(c[3], 0, 0, this));
            top.addView(dot, new LinearLayout.LayoutParams(dp(18), dp(18)));
            tile.addView(top);
            TextView name = Ui.text(this, getString(names[which]), 14, c[2]);
            name.setTypeface(on ? Palette.bodyStrong(this) : Palette.body(this));
            tile.addView(name);
            tile.setContentDescription(getString(names[which]));
            tile.setSelected(on);
            tile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (which == Prefs.look(SettingsActivity.this)) {
                        return;
                    }
                    Prefs.setLook(SettingsActivity.this, which);
                    Palette.apply(SettingsActivity.this);
                    Widgets.refresh(SettingsActivity.this);
                    recreate();
                }
            });
            if (i % 2 == 0) {
                row = Ui.row(this);
                LinearLayout.LayoutParams lp = Ui.lp(Ui.MATCH, Ui.WRAP);
                lp.topMargin = dp(i == 0 ? 8 : 12);
                list.addView(row, lp);
            }
            LinearLayout.LayoutParams p = Ui.weight(1);
            if (i % 2 == 1) {
                p.leftMargin = dp(12);
            }
            row.addView(tile, p);
        }
    }

    private SeekBar slider(int max, int progress) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(Math.max(0, Math.min(max, progress)));
        ColorStateList accent = ColorStateList.valueOf(Palette.ACCENT);
        bar.setProgressTintList(accent);
        bar.setThumbTintList(accent);
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Palette.INK));
        return bar;
    }

    private View labelled(String label, TextView value, SeekBar bar) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, dp(8));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.addView(text(label, 17, 1f), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(value, wrap());
        box.addView(head);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(8);
        box.addView(bar, bp);
        return box;
    }

    private static final int PICK_ARCHIVE = 81;

    /** Save the archive now, or bring one back from a file. */
    private void transferSheet() {
        android.widget.TextView fromFile = Kit.link(this, getString(R.string.transfer_from_file), null);
        LinearLayout lesser = Ui.column(this);
        lesser.addView(fromFile);
        android.widget.TextView about = Ui.text(this, getString(R.string.transfer_about, Transfer.fileName(this)),
                14, Palette.MUTED);
        about.setLineSpacing(0, 1.3f);
        final android.app.Dialog d = Kit.sheet(this, getString(R.string.transfer_row), about,
                getString(R.string.transfer_save_now), new Runnable() {
                    @Override
                    public void run() {
                        if (HoraFolder.tree(SettingsActivity.this) == null) {
                            HoraFolder.ask(SettingsActivity.this);
                            return;
                        }
                        final android.content.Context app = getApplicationContext();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Transfer.save(app);
                                } catch (Exception e) {
                                    Diag.log(app, "transfer: not saved", e);
                                }
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        recreate();
                                    }
                                });
                            }
                        }, "transfer-now").start();
                    }
                }, lesser);
        fromFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*"), PICK_ARCHIVE);
            }
        });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_ARCHIVE && result == RESULT_OK && data != null && data.getData() != null) {
            startActivity(new Intent(this, RestoreActivity.class)
                    .putExtra(RestoreActivity.EXTRA_URI, data.getData().toString()));
        } else if (request == HoraFolder.PICK && result == RESULT_OK && HoraFolder.accept(this, data)) {
            Transfer.soon(this);
            RestoreActivity.offerIfFresh(this);
            recreate();
        }
    }

    private void section(int res) {
        TextView section = text(getString(res), 13, 1f);
        section.setTextColor(Palette.MUTED);
        section.setTypeface(Palette.bodyStrong(this));
        LinearLayout.LayoutParams sp = wrap();
        sp.topMargin = dp(36);
        sp.bottomMargin = dp(8);
        list.addView(section, sp);
    }

    private String buildLabel() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private LinearLayout row(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.addView(text(label, 17, 1f), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(value, 17, 0.6f);
        v.setGravity(Gravity.END);
        row.addView(v, wrap());
        return row;
    }

    private View rule() {
        View v = new View(this);
        v.setBackgroundColor(Palette.RULE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        return v;
    }

    private TextView text(String s, int sp, float alpha) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Palette.INK);
        t.setAlpha(alpha);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Palette.body(this));
        return t;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private abstract static class Listener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar s) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar s) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.stale(this, built);
    }
}
