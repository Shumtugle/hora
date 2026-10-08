package com.shumtugle.hora;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * The background under the book, at hand on the book screen: on or off,
 * which one, how loud beside the voice, and a voice that falls silent while
 * the background plays on. Every change is heard at once.
 */
final class BedSheet {
    /** The two rows of choices: noises, then the rest. Numbers are the kinds of background. */
    private static final int[][] GROUPS = {
        {Bed.BROWN, Bed.PINK, Bed.WHITE},
        {Bed.RAIN, Bed.SURF, Bed.MUSIC},
    };

    private BedSheet() {
    }

    /** Tells the reading process of a change, when it has something playing to change. */
    static void tell(Context c) {
        if (Reading.playing(c) || Prefs.bedOnly(c)) {
            c.startForegroundService(BookPlayer.intent(c, BookPlayer.ACTION_BED));
        }
    }

    /** Turns the background on with the last one heard, or off. */
    static void flip(Context c, boolean on) {
        Prefs.setBed(c, on ? Prefs.bedLast(c) : Bed.OFF);
        tell(c);
    }

    /** "Rain · 35 %", "Rain · the voice is silent", or "off". */
    static String state(Context c) {
        int kind = Prefs.bed(c);
        if (kind == Bed.OFF) {
            return c.getString(R.string.bed_row_off);
        }
        String name = c.getResources().getStringArray(R.array.bed_kinds)[kind];
        return Prefs.bedOnly(c) ? c.getString(R.string.bed_row_voice_quiet, name)
                : c.getString(R.string.bed_row_on, name, Prefs.bedVolume(c));
    }

    static Dialog show(final Activity a, final Runnable changed) {
        final LinearLayout body = Ui.column(a);
        build(a, body, changed);
        Dialog d = Kit.sheet(a, a.getString(R.string.bed), body, a.getString(R.string.kit_done), null);
        d.show();
        return d;
    }

    private static void build(final Activity a, final LinearLayout body, final Runnable changed) {
        body.removeAllViews();
        final int kind = Prefs.bed(a);
        final boolean on = kind != Bed.OFF;
        body.addView(Kit.rowToggle(a, a.getString(R.string.bed_switch), null, on, new Kit.Flip() {
            @Override
            public void flipped(boolean now) {
                flip(a, now);
                again(a, body, changed);
            }
        }), Kit.wide());

        String[] names = a.getResources().getStringArray(R.array.bed_kinds_short);
        for (int g = 0; g < GROUPS.length; g++) {
            final int[] group = GROUPS[g];
            String[] labels = new String[group.length];
            int chosen = -1;
            for (int i = 0; i < group.length; i++) {
                labels[i] = names[group[i]];
                if (group[i] == kind) {
                    chosen = i;
                }
            }
            LinearLayout seg = Kit.segments(a, labels, chosen, new Kit.Chosen() {
                @Override
                public void chose(int which) {
                    Prefs.setBed(a, group[which]);
                    tell(a);
                    again(a, body, changed);
                }
            });
            if (!on) {
                seg.setAlpha(0.55f);
            }
            body.addView(seg, Kit.below(a, g == 0 ? 8 : 10));
        }

        LinearLayout head = Ui.row(a);
        TextView label = Ui.text(a, a.getString(R.string.bed_volume_near_voice), 16, Palette.INK);
        head.addView(label, Ui.weight(1));
        final TextView value = Ui.text(a, a.getString(R.string.bed_volume_value, Prefs.bedVolume(a)), 16,
                Palette.MUTED);
        head.addView(value);
        body.addView(head, Kit.below(a, 20));
        SeekBar bar = Ui.slider(a, 100, Prefs.bedVolume(a));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                value.setText(a.getString(R.string.bed_volume_value, p));
                if (user) {
                    // The noise reads the setting on every block; the music hears of it on release.
                    Prefs.setBedVolume(a, p);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                tell(a);
                if (changed != null) {
                    changed.run();
                }
            }
        });
        body.addView(bar, Kit.below(a, 2));
        LinearLayout ends = Ui.row(a);
        ends.addView(Ui.text(a, a.getString(R.string.bed_volume_low), 13, Palette.MUTED), Ui.weight(1));
        ends.addView(Ui.text(a, a.getString(R.string.bed_volume_high), 13, Palette.MUTED));
        body.addView(ends, Kit.wide());

        View quiet = Kit.rowToggle(a, a.getString(R.string.bed_voice_quiet),
                a.getString(R.string.bed_voice_quiet_sub), Prefs.bedOnly(a), new Kit.Flip() {
                    @Override
                    public void flipped(boolean now) {
                        a.startForegroundService(BookPlayer.intent(a, BookPlayer.ACTION_VOICE_QUIET)
                                .putExtra(BookPlayer.EXTRA_ON, now));
                        if (changed != null) {
                            changed.run();
                        }
                    }
                });
        quiet.setEnabled(on);
        quiet.setAlpha(on ? 1f : 0.45f);
        body.addView(quiet, Kit.below(a, 12));
    }

    private static void again(final Activity a, final LinearLayout body, final Runnable changed) {
        body.post(new Runnable() {
            @Override
            public void run() {
                build(a, body, changed);
                if (changed != null) {
                    changed.run();
                }
            }
        });
    }
}
