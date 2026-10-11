package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Settings, one thing in one place. Voices: roles, a voice's own settings,
 * notifications. Reading: what holds for every voice, the rarer part folded.
 * Then the look, everything else, and about the app; the journal and the
 * dictionary folded at the very bottom.
 */
public final class SettingsActivity extends Activity {
    /** The look this screen was built with. */
    private int built;

    private LinearLayout list;
    private LinearLayout beta;
    private View allRow;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        list = Kit.room(this, getString(R.string.settings));

        // The voices themselves, one card at a time: the way to meet them and choose who answers.
        LinearLayout meet = Kit.plate(this);
        LinearLayout meetRow = Ui.row(this);
        meetRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        meetRow.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout stack = Ui.column(this);
        LinearLayout faces = Ui.row(this);
        for (int i = 0; i < Cast.COUNT; i++) {
            android.widget.ImageView f = Ui.portrait(this, Cast.at(i));
            f.setPadding(dp(2), dp(2), dp(2), dp(2));
            f.setBackground(Ui.oval(Palette.SURFACE, 0, 0, this));
            LinearLayout.LayoutParams fp0 = new LinearLayout.LayoutParams(dp(38), dp(38));
            if (i > 0) {
                fp0.leftMargin = -dp(10);
            }
            faces.addView(f, fp0);
        }
        stack.addView(faces);
        TextView meetTitle = Ui.text(this, getString(R.string.voices_title), 17, Palette.INK);
        LinearLayout.LayoutParams mtp = Ui.lp(Ui.WRAP, Ui.WRAP);
        mtp.topMargin = dp(10);
        stack.addView(meetTitle, mtp);
        stack.addView(Ui.text(this, getString(R.string.voices_sub), 13, Palette.MUTED));
        meetRow.addView(stack, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        meetRow.addView(Ui.text(this, "\u203a", 20, Palette.MUTED));
        meetRow.setBackground(Ui.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)));
        meetRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VoicesActivity.open(SettingsActivity.this, Prefs.role(SettingsActivity.this, Cast.TALK));
            }
        });
        meet.addView(meetRow);
        LinearLayout.LayoutParams mp = Kit.wide();
        mp.topMargin = dp(20);
        list.addView(meet, mp);

        // Voices.
        list.addView(Kit.section(this, getString(R.string.voices_section)));
        LinearLayout voices = Kit.plate(this);
        voices.addView(Kit.rowNav(this, getString(R.string.settings_voices_roles), null, open(RolesActivity.class)));
        voices.addView(Kit.divider(this));
        voices.addView(Kit.rowNav(this, getString(R.string.lab_title), null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LabActivity.open(SettingsActivity.this, Prefs.role(SettingsActivity.this, Cast.TALK));
            }
        }));
        voices.addView(Kit.divider(this));
        voices.addView(Kit.rowNav(this, getString(R.string.intro_herald), null, open(HeraldActivity.class)));
        list.addView(voices);

        // Reading, for every voice: pace and character in sight, the rest folded.
        list.addView(Kit.section(this, getString(R.string.reading_all)));
        LinearLayout reading = Kit.plate(this);
        final float spMin = 0.6f;
        reading.addView(Kit.slider(this, getString(R.string.lab_tempo), getString(R.string.ws_slow),
                getString(R.string.ws_fast), Math.round((1.3f - spMin) * 20), Math.round((Prefs.speed(this) - spMin) * 20),
                new Kit.Value() {
                    @Override
                    public String show(int p) {
                        return String.format(Locale.getDefault(), "%.2f\u00d7", spMin + p / 20f);
                    }

                    @Override
                    public void set(int p) {
                        Prefs.setSpeed(SettingsActivity.this, Math.round((spMin + p / 20f) * 100f) / 100f);
                    }
                }));
        reading.addView(Kit.divider(this));
        final float exMin = Prefs.EXPRESSION_MIN;
        reading.addView(Kit.slider(this, getString(R.string.lab_character), getString(R.string.ws_calm),
                getString(R.string.ws_lively), Math.round((Prefs.EXPRESSION_MAX - exMin) * 20),
                Math.round((Prefs.expression(this) - exMin) * 20), new Kit.Value() {
                    @Override
                    public String show(int p) {
                        return String.format(Locale.getDefault(), "%.2f", exMin + p / 20f);
                    }

                    @Override
                    public void set(int p) {
                        Prefs.setExpression(SettingsActivity.this, exMin + p / 20f);
                    }
                }));
        reading.addView(Kit.divider(this));
        LinearLayout fine = Ui.column(this);
        fine.addView(Kit.slider(this, getString(R.string.lab_pause), getString(R.string.lab_shorter),
                getString(R.string.lab_longer), Prefs.PAUSE_MAX_MS / 25, Prefs.pauseMs(this) / 25, new Kit.Value() {
                    @Override
                    public String show(int p) {
                        return getString(R.string.lab_ms, p * 25);
                    }

                    @Override
                    public void set(int p) {
                        Prefs.setPauseMs(SettingsActivity.this, p * 25);
                    }
                }));
        fine.addView(Kit.rowToggle(this, getString(R.string.even_tempo), null, Prefs.evenTempo(this), new Kit.Flip() {
            @Override
            public void flipped(boolean on) {
                Prefs.setEvenTempo(SettingsActivity.this, on);
            }
        }));
        fine.addView(Kit.rowToggle(this, getString(R.string.slow_by_pauses), null, Prefs.slowByPauses(this),
                new Kit.Flip() {
                    @Override
                    public void flipped(boolean on) {
                        Prefs.setSlowByPauses(SettingsActivity.this, on);
                    }
                }));
        fine.addView(Kit.rowToggle(this, getString(R.string.respell), null, Prefs.respell(this), new Kit.Flip() {
            @Override
            public void flipped(boolean on) {
                Prefs.setRespell(SettingsActivity.this, on);
            }
        }));
        // The sound decoder: the compact one by default, the full one once its pack is here.
        LinearLayout dec = Ui.column(this);
        dec.setPadding(dp(16), dp(12), dp(16), dp(8));
        dec.addView(Ui.text(this, getString(R.string.lab_decoder), 16, Palette.INK));
        LinearLayout.LayoutParams segP = Ui.lp(Ui.MATCH, Ui.WRAP);
        segP.topMargin = dp(10);
        dec.addView(Kit.segments(this, new String[] {getString(R.string.lab_decoder_small),
                getString(R.string.lab_decoder_full)}, Prefs.fullDecoder(this) ? 1 : 0, new Kit.Chosen() {
                    @Override
                    public void chose(int which) {
                        Prefs.setFullDecoder(SettingsActivity.this, which == 1);
                    }
                }), segP);
        TextView decNote = Ui.text(this, getString(R.string.lab_decoder_note), 12, Palette.HINT);
        decNote.setLineSpacing(0, 1.3f);
        decNote.setPadding(0, dp(8), 0, dp(6));
        dec.addView(decNote);
        // The full decoder is a pack of its own, fetched where the other packs are.
        if (!LanguagePack.installed(this, LanguagePack.DECODER)) {
            TextView fetch = Kit.link(this, getString(R.string.decoder_where), open(LanguagesActivity.class));
            fetch.setPadding(0, dp(2), 0, dp(8));
            dec.addView(fetch);
        }
        fine.addView(dec);
        reading.addView(Kit.fold(this, "reading_fine", getString(R.string.fine_tuning), fine));
        list.addView(reading);

        // The look.
        list.addView(Kit.section(this, getString(R.string.look)));
        LinearLayout looks = Ui.column(this);
        lookChooser(looks);
        list.addView(looks, Kit.wide());
        LinearLayout.LayoutParams sgp = Kit.wide();
        sgp.topMargin = dp(14);
        TextView shelfLabel = Ui.text(this, getString(R.string.settings_shelf_view), 15, Palette.MUTED);
        list.addView(shelfLabel, sgp);
        LinearLayout.LayoutParams segShelf = Kit.wide();
        segShelf.topMargin = dp(8);
        list.addView(Kit.segments(this, new String[] {getString(R.string.settings_shelf_covers),
                getString(R.string.settings_shelf_list)}, Prefs.shelfList(this) ? 1 : 0, new Kit.Chosen() {
                    @Override
                    public void chose(int which) {
                        Prefs.setShelfList(SettingsActivity.this, which == 1);
                    }
                }), segShelf);

        // Everything else.
        list.addView(Kit.section(this, getString(R.string.settings_rest)));
        LinearLayout rest = Kit.plate(this);
        rest.addView(Kit.rowNav(this, getString(R.string.brain_title), null, open(BrainActivity.class)));
        rest.addView(Kit.divider(this));
        rest.addView(Kit.rowNav(this, getString(R.string.lang_title), LanguagePack.installed(this,
                LanguagePack.ENGLISH) ? getString(R.string.lang_english) : null, open(LanguagesActivity.class)));
        rest.addView(Kit.divider(this));
        // Everything Hora fetches, in one touch; the accelerator stays on the model's own screen.
        allRow = Kit.rowNav(this, getString(R.string.all_title), null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startEverything();
            }
        });
        rest.addView(allRow);
        rest.addView(Kit.divider(this));
        String where = Weather.placeName(this);
        rest.addView(Kit.rowNav(this, getString(R.string.place_title), where.isEmpty()
                ? (Weather.hasPlace(this) ? getString(R.string.place_here_short) : null) : where,
                open(PlaceActivity.class)));
        rest.addView(Kit.divider(this));
        int queued = Export.queue(this).length();
        rest.addView(Kit.rowNav(this, getString(R.string.export_title), queued == 0 ? null : String.valueOf(queued),
                open(ExportActivity.class)));
        rest.addView(Kit.divider(this));
        long saved = Transfer.savedAt(this);
        rest.addView(Kit.rowNav(this, getString(R.string.transfer_row), HoraFolder.tree(this) == null
                ? getString(R.string.transfer_no_folder)
                : saved <= 0 ? getString(R.string.transfer_not_yet)
                : java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(new java.util.Date(saved)), new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                transferSheet();
                            }
                        }));
        list.addView(rest);

        // About the app.
        list.addView(Kit.section(this, getString(R.string.about)));
        LinearLayout about = Kit.plate(this);
        about.addView(Kit.rowNav(this, getString(R.string.intro_open), null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                IntroActivity.open(SettingsActivity.this);
            }
        }));
        about.addView(Kit.divider(this));
        // The demo book, read by the narrator; it never sits on the shelf.
        about.addView(Kit.rowNav(this, getString(R.string.demo_title), "\u25b7", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BetaActivity.read(SettingsActivity.this, 0);
            }
        }));
        about.addView(Kit.divider(this));
        about.addView(Kit.rowNav(this, getString(R.string.about_title), null, open(AboutActivity.class)));
        about.addView(Kit.divider(this));
        // Seven touches on the build number open the developer room, as on the phone itself.
        about.addView(Kit.rowNav(this, getString(R.string.build), buildLabel(), new View.OnClickListener() {
            private int taps;
            private long last;

            @Override
            public void onClick(View v) {
                long now = System.currentTimeMillis();
                taps = now - last < 3000 ? taps + 1 : 1;
                last = now;
                if (Beta.open(SettingsActivity.this)) {
                    if (taps >= 3) {
                        Toast.makeText(SettingsActivity.this, R.string.dev_already, Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                if (taps >= 7) {
                    Beta.setOpen(SettingsActivity.this, true);
                    Diag.mark(SettingsActivity.this, "settings: developer room opened");
                    Toast.makeText(SettingsActivity.this, R.string.dev_opened, Toast.LENGTH_SHORT).show();
                    showDeveloper();
                } else if (taps >= 4) {
                    Toast.makeText(SettingsActivity.this, getString(R.string.dev_steps, 7 - taps),
                            Toast.LENGTH_SHORT).show();
                }
            }
        }));
        list.addView(about);

        // The developer room: shown once opened, until closed from inside.
        beta = Ui.column(this);
        list.addView(beta, Kit.wide());

        // For those who look under the hood: the journal and the dictionary.
        LinearLayout tools = Kit.plate(this);
        tools.addView(Kit.rowNav(this, getString(R.string.journal), null, open(JournalActivity.class)));
        tools.addView(Kit.divider(this));
        tools.addView(Kit.rowNav(this, getString(R.string.lexicon), null, open(LexiconActivity.class)));
        LinearLayout.LayoutParams fp = Kit.wide();
        fp.topMargin = dp(18);
        list.addView(Kit.fold(this, "settings_tools", getString(R.string.settings_more), tools), fp);
    }

    /** A click that opens another screen. */
    private View.OnClickListener open(final Class<? extends Activity> screen) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, screen));
            }
        };
    }

    /** The looks as tiles, two to a row, each drawn in its own colors so it can be judged before choosing. */
    private void lookChooser(LinearLayout into) {
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
                into.addView(row, lp);
            }
            LinearLayout.LayoutParams p = Ui.weight(1);
            if (i % 2 == 1) {
                p.leftMargin = dp(12);
            }
            row.addView(tile, p);
        }
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

    private String buildLabel() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private final Runnable allTick = new Runnable() {
        @Override
        public void run() {
            showEverything();
        }
    };

    /** The set's row says what is left, how far it came, or that all is here. */
    private void showEverything() {
        allRow.removeCallbacks(allTick);
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean whole = Everything.complete(app);
                final Everything.State st = Everything.state(app);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String value = whole ? getString(R.string.all_here)
                                : st.running ? (st.total <= 0 ? "\u2026"
                                        : (int) (100L * st.done / st.total) + "\u00a0%")
                                : getString(R.string.all_size_total);
                        Kit.setValue(allRow, value);
                        if (st.running && !isFinishing()) {
                            allRow.postDelayed(allTick, 1500);
                        }
                    }
                });
            }
        }, "settings-all").start();
    }

    private void startEverything() {
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (Everything.complete(app) || Everything.state(app).running) {
                    return;
                }
                String trouble = null;
                try {
                    Everything.start(app);
                } catch (Everything.NoRoom e) {
                    trouble = getString(R.string.all_no_room, Everything.gb(e.need - e.free));
                } catch (Exception e) {
                    Diag.log(app, "everything: could not start", e);
                    trouble = getString(R.string.lang_no_list);
                }
                final String said = trouble;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (said != null) {
                            Toast.makeText(SettingsActivity.this, said, Toast.LENGTH_LONG).show();
                            return;
                        }
                        EverythingWatch.begin(SettingsActivity.this);
                        showEverything();
                    }
                });
            }
        }, "settings-start-all").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        showDeveloper();
        showEverything();
    }

    private void showDeveloper() {
        if (beta == null) {
            return;
        }
        beta.removeAllViews();
        if (!Beta.open(this)) {
            return;
        }
        beta.addView(Kit.section(this, getString(R.string.dev_section)));
        LinearLayout room = Kit.plate(this);
        room.addView(Kit.rowNav(this, getString(R.string.beta_title), null, open(BetaActivity.class)));
        room.addView(Kit.divider(this));
        // A short spoken sample of every voice, written to a file, for comparing builds by ear.
        room.addView(Kit.rowNav(this, getString(R.string.probe), null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VoiceService.probe(SettingsActivity.this);
            }
        }));
        beta.addView(room);
    }

    private int dp(float v) {
        return Kit.dp(this, v);
    }
}
