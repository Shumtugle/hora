package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * The mind of the voices, open to view: whether it is awake and how fast,
 * the three files it runs on, a few settings in plain words, the
 * instructions each voice starts from (editable, with a way back), and what
 * never reaches it at all.
 */
public final class BrainActivity extends Activity {
    private static final int PICK = 71;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int built;
    private int picking = -1;
    private TextView state;
    private TextView stateLine;
    private TextView sizeLine;
    private final View[] slotRows = new View[3];
    private LinearLayout prompts;
    private View draftToggle;
    private View draftLine;
    /** What is missing and how to get it; empty when nothing is missing. */
    private LinearLayout getBox;
    /** Set while the list is read or a finished file is checked. */
    private boolean busy;
    private String trouble;
    /**
     * The slots a download covers: the model, the part that lets it see pictures, and the
     * accelerator. Only the first two are asked for; the accelerator comes along when missing.
     */
    private static final int[] FETCHED = {Brain.SLOT_MODEL, Brain.SLOT_VISION, Brain.SLOT_DRAFT};
    /** Opened from a talk that asked for the missing parts: start fetching them at once. */
    static final String EXTRA_GET = "get";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            showGet();
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        built = Ui.prepare(this);
        build();
        buildOverlay();
        if (saved == null && getIntent().getBooleanExtra(EXTRA_GET, false) && missing().length > 0
                && !anyRunning()) {
            fetch(false);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    /** The missing parts worth asking for: the model and the part for photos. */
    private int[] missing() {
        Brain b = Brain.get(this);
        int[] asked = {Brain.SLOT_MODEL, Brain.SLOT_VISION};
        int n = 0;
        for (int s : asked) {
            if (!b.has(s)) {
                n++;
            }
        }
        int[] out = new int[n];
        int k = 0;
        for (int s : asked) {
            if (!b.has(s)) {
                out[k++] = s;
            }
        }
        return out;
    }

    private boolean anyRunning() {
        for (int s : FETCHED) {
            if (ModelDownload.active(this, s)) {
                return true;
            }
        }
        return false;
    }

    /** Asks the list where the missing parts live and hands them to the system to download. */
    private void fetch(final boolean anyNetwork) {
        fetch(missing(), anyNetwork);
    }

    /** Fetches the given parts; a part already in place is replaced once the new one is checked. */
    private void fetch(final int[] need, final boolean anyNetwork) {
        if (busy || need.length == 0) {
            return;
        }
        busy = true;
        trouble = null;
        showGet();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String t = null;
                try {
                    ModelDownload.start(BrainActivity.this, need, anyNetwork);
                } catch (Exception e) {
                    Diag.log(BrainActivity.this, "model: list unavailable", e);
                    t = getString(R.string.lang_no_list);
                }
                final String said = t;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        trouble = said;
                        showGet();
                    }
                });
            }
        }, "model-fetch").start();
    }

    /** Finishes downloads the system reports over: the sum is checked, the file put in place. */
    private void settle() {
        final java.util.List<Integer> done = new java.util.ArrayList<Integer>();
        for (int s : FETCHED) {
            Fetch.Progress p = ModelDownload.progress(this, s);
            if (p != null && !ModelDownload.running(this, s)) {
                done.add(s);
            }
        }
        if (done.isEmpty() || busy) {
            return;
        }
        busy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = true;
                for (int s : done) {
                    ok &= ModelDownload.finish(BrainActivity.this, s) != Fetch.FAILED;
                }
                final boolean fine = ok;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        trouble = fine ? null : getString(R.string.lang_failed);
                        if (fine && !anyRunning() && Brain.get(BrainActivity.this).hasModel()) {
                            waking = true;
                            trial();
                        }
                        refresh();
                    }
                });
            }
        }, "model-settle").start();
    }

    private void showGet() {
        handler.removeCallbacks(tick);
        getBox.removeAllViews();
        boolean working = anyRunning() || busy || waking;
        showOverlay(working);
        if (working) {
            getBox.setVisibility(View.GONE);
            handler.postDelayed(tick, 1000);
            return;
        }
        int[] need = missing();
        if (need.length == 0) {
            final Brain b = Brain.get(this);
            if (b.isDefaultModel()) {
                getBox.setVisibility(View.GONE);
                return;
            }
            // Another model is in place: say what that means for the extras, and offer the way back.
            getBox.setVisibility(View.VISIBLE);
            LinearLayout plate = Kit.plate(this);
            plate.setPadding(0, dp(4), 0, dp(4));
            TextView why = Ui.text(this, getString(b.visionRefused() ? R.string.brain_custom_no_photo
                    : R.string.brain_custom_model), 15, Palette.INK);
            why.setLineSpacing(0, 1.3f);
            why.setPadding(dp(16), dp(12), dp(16), dp(12));
            plate.addView(why);
            getBox.addView(plate, Kit.wide());
            getBox.addView(Kit.link(this, getString(R.string.brain_restore_default), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    restoreDefault();
                }
            }), Kit.below(this, 8));
            return;
        }
        getBox.setVisibility(View.VISIBLE);
        LinearLayout plate = Kit.plate(this);
        plate.setPadding(0, dp(4), 0, dp(4));
        getBox.addView(plate, Kit.wide());
        boolean model = !Brain.get(this).has(Brain.SLOT_MODEL);
        TextView why = Ui.text(this, getString(model ? R.string.brain_need_model : R.string.brain_need_vision),
                15, Palette.INK);
        why.setLineSpacing(0, 1.3f);
        why.setPadding(dp(16), dp(12), dp(16), dp(12));
        plate.addView(why);
        if (trouble != null) {
            plate.addView(Kit.status(this, trouble, Kit.Mood.TROUBLE));
        }
        getBox.addView(Kit.primary(this, getString(model ? R.string.brain_get_model : R.string.brain_get_vision),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        fetch(false);
                    }
                }), Kit.below(this, 12));
        getBox.addView(Kit.link(this, getString(R.string.lang_get_now), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fetch(true);
            }
        }), Kit.below(this, 4));
    }

    // The overlay shown while the model is being prepared.

    private View overlay;
    private TextView overlayStage;
    private View overlayFill;
    private View overlayRest;
    private TextView overlayCancel;
    /** Sizes of the files fetched in this visit, so finished ones still count in the total. */
    private final long[] sizes = new long[3];
    private final long[] got = new long[3];
    /** Set while the model is asked, once everything is in place, whether it is ready. */
    private boolean waking;
    private boolean overlayShown;

    private void buildOverlay() {
        android.widget.FrameLayout scrim = new android.widget.FrameLayout(this);
        scrim.setBackgroundColor((Palette.BG & 0x00FFFFFF) | 0xF2000000);
        scrim.setClickable(true);
        LinearLayout col = Ui.column(this);
        col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(32), 0, dp(32), 0);
        scrim.addView(col, new android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER));
        col.addView(new HeartGears(this), Ui.lp(dp(150), dp(150)));
        TextView title = Ui.title(this, getString(R.string.brain_prep_title), 26);
        title.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams tp = Ui.lp(Ui.WRAP, Ui.WRAP);
        tp.topMargin = dp(20);
        col.addView(title, tp);
        TextView ask = Ui.text(this, getString(R.string.brain_prep_ask), 15, Palette.MUTED);
        ask.setGravity(android.view.Gravity.CENTER);
        ask.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams ap = Ui.lp(Ui.WRAP, Ui.WRAP);
        ap.topMargin = dp(10);
        col.addView(ask, ap);
        LinearLayout bar = Ui.row(this);
        bar.setBackground(Ui.round(Palette.LINE, 3, this));
        overlayFill = new View(this);
        overlayFill.setBackground(Ui.round(Palette.ACCENT, 3, this));
        overlayRest = new View(this);
        bar.addView(overlayFill, new LinearLayout.LayoutParams(0, -1, 0f));
        bar.addView(overlayRest, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams bp = Ui.lp(Ui.MATCH, dp(6));
        bp.topMargin = dp(28);
        col.addView(bar, bp);
        overlayStage = Ui.text(this, "", 14, Palette.INK);
        overlayStage.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams sp = Ui.lp(Ui.WRAP, Ui.WRAP);
        sp.topMargin = dp(12);
        col.addView(overlayStage, sp);
        overlayCancel = Kit.link(this, getString(R.string.lang_cancel), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (int s : FETCHED) {
                    ModelDownload.cancel(BrainActivity.this, s);
                }
                java.util.Arrays.fill(sizes, 0);
                java.util.Arrays.fill(got, 0);
                refresh();
            }
        });
        LinearLayout.LayoutParams cp = Ui.lp(Ui.WRAP, Ui.WRAP);
        cp.topMargin = dp(24);
        col.addView(overlayCancel, cp);
        overlay = scrim;
        overlay.setVisibility(View.GONE);
        ((android.view.ViewGroup) getWindow().getDecorView()).addView(overlay,
                new android.view.ViewGroup.LayoutParams(-1, -1));
    }

    /** Shows or hides the overlay and, when shown, says where things stand. */
    private void showOverlay(boolean on) {
        if (!on) {
            if (overlayShown) {
                overlayShown = false;
                overlay.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        overlay.setVisibility(View.GONE);
                    }
                });
            }
            return;
        }
        if (!overlayShown) {
            overlayShown = true;
            overlay.setAlpha(0f);
            overlay.setVisibility(View.VISIBLE);
            overlay.animate().alpha(1f).setDuration(250);
        }
        boolean checking = false;
        boolean wifi = false;
        boolean net = false;
        boolean any = false;
        for (int k = 0; k < FETCHED.length; k++) {
            int s = FETCHED[k];
            if (!ModelDownload.active(this, s)) {
                // Finished in this visit: counts as whole.
                got[k] = sizes[k];
                continue;
            }
            any = true;
            Fetch.Progress p = ModelDownload.progress(this, s);
            if (p == null) {
                continue;
            }
            sizes[k] = Math.max(sizes[k], p.total);
            got[k] = Math.min(p.done, sizes[k]);
            if (p.status == android.app.DownloadManager.STATUS_SUCCESSFUL
                    || p.status == android.app.DownloadManager.STATUS_FAILED) {
                checking = true;
            } else if (p.status == android.app.DownloadManager.STATUS_PAUSED) {
                if (p.reason == android.app.DownloadManager.PAUSED_QUEUED_FOR_WIFI) {
                    wifi = true;
                } else {
                    net = true;
                }
            }
        }
        long all = 0;
        long done = 0;
        for (int k = 0; k < sizes.length; k++) {
            all += sizes[k];
            done += got[k];
        }
        float part = all > 0 ? Math.min(1f, done / (float) all) : 0f;
        ((LinearLayout.LayoutParams) overlayFill.getLayoutParams()).weight = part;
        ((LinearLayout.LayoutParams) overlayRest.getLayoutParams()).weight = 1f - part;
        overlayFill.requestLayout();
        overlayCancel.setVisibility(any && !waking ? View.VISIBLE : View.INVISIBLE);
        if (waking) {
            overlayStage.setText(R.string.brain_prep_waking);
        } else if (busy || checking) {
            overlayStage.setText(R.string.brain_prep_checking);
            settle();
        } else if (wifi) {
            overlayStage.setText(R.string.lang_waiting_wifi);
        } else if (net) {
            overlayStage.setText(R.string.lang_waiting_net);
        } else if (all <= 0) {
            overlayStage.setText(R.string.lang_starting);
        } else {
            overlayStage.setText(getString(R.string.brain_prep_progress, done / 1e9, all / 1e9, Math.round(part * 100)));
        }
    }

    private String downloading(Fetch.Progress p) {
        if (p.status == android.app.DownloadManager.STATUS_PAUSED
                && p.reason == android.app.DownloadManager.PAUSED_QUEUED_FOR_WIFI) {
            return getString(R.string.lang_waiting_wifi);
        }
        if (p.status == android.app.DownloadManager.STATUS_PAUSED) {
            return getString(R.string.lang_waiting_net);
        }
        if (p.total <= 0) {
            return getString(R.string.lang_starting);
        }
        return getString(R.string.lang_downloading, (int) (p.done * 100 / p.total));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        refresh();
    }

    private int dp(float v) {
        return Kit.dp(this, v);
    }

    private void build() {
        LinearLayout list = Kit.room(this, getString(R.string.brain_title));

        LinearLayout status = Kit.plate(this);
        status.setPadding(dp(16), dp(16), dp(16), dp(16));
        state = Ui.title(this, "", 22);
        status.addView(state);
        stateLine = Ui.text(this, "", 13, Palette.MUTED);
        stateLine.setLineSpacing(0, 1.3f);
        status.addView(stateLine, Kit.below(this, 6));
        sizeLine = Ui.text(this, "", 13, Palette.HINT);
        status.addView(sizeLine, Kit.below(this, 8));
        LinearLayout acts = Ui.row(this);
        acts.addView(Kit.secondary(this, getString(R.string.brain_sleep_now), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Brain.get(BrainActivity.this).stop();
                refresh();
            }
        }), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        tp.leftMargin = dp(10);
        acts.addView(Kit.secondary(this, getString(R.string.brain_try), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                trial();
            }
        }), tp);
        status.addView(acts, Kit.below(this, 12));
        list.addView(status, Kit.below(this, 16));
        getBox = Ui.column(this);
        list.addView(getBox, Kit.below(this, 16));

        list.addView(Kit.section(this, getString(R.string.brain_files)));
        LinearLayout files = Kit.plate(this);
        int[] titles = {R.string.brain_slot_model, R.string.brain_slot_vision, R.string.brain_slot_draft};
        for (int i = 0; i < 3; i++) {
            final int slot = i;
            if (i > 0) {
                files.addView(line());
            }
            slotRows[i] = fileRow(getString(titles[i]), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    slotSheet(slot);
                }
            });
            files.addView(slotRows[i]);
        }
        list.addView(files);
        TextView fileNote = Ui.text(this, getString(R.string.brain_files_note), 12, Palette.HINT);
        fileNote.setLineSpacing(0, 1.3f);
        fileNote.setPadding(dp(6), dp(8), dp(6), 0);
        list.addView(fileNote);

        list.addView(Kit.section(this, getString(R.string.brain_settings)));
        LinearLayout set = Kit.plate(this);
        // The accelerator can be switched off to compare speeds: the model restarts on the next question.
        draftToggle = Kit.rowToggle(this, getString(R.string.brain_draft_toggle), getString(R.string.brain_draft_toggle_sub),
                Brain.draftOn(this), new Kit.Flip() {
                    @Override
                    public void flipped(boolean on) {
                        Brain.setDraftOn(BrainActivity.this, on);
                        Brain.get(BrainActivity.this).stop();
                        refresh();
                    }
                });
        set.addView(draftToggle);
        draftLine = line();
        set.addView(draftLine);
        set.addView(slider(getString(R.string.brain_liveliness), getString(R.string.brain_even),
                getString(R.string.brain_livelier), 14, Math.round((Brain.temperature(this) - 0.2f) * 20),
                new Value() {
                    @Override
                    public String show(int p) {
                        return String.format(Locale.getDefault(), "%.2f", 0.2f + p / 20f);
                    }

                    @Override
                    public void set(int p) {
                        Brain.setTemperature(BrainActivity.this, 0.2f + p / 20f);
                    }
                }));
        set.addView(line());
        set.addView(slider(getString(R.string.brain_length), getString(R.string.lab_shorter),
                getString(R.string.lab_longer), 16, (Brain.replyTokens(this) - 40) / 10, new Value() {
                    @Override
                    public String show(int p) {
                        return getString(R.string.brain_tokens, 40 + p * 10);
                    }

                    @Override
                    public void set(int p) {
                        Brain.setReplyTokens(BrainActivity.this, 40 + p * 10);
                    }
                }));
        set.addView(line());
        set.addView(slider(getString(R.string.brain_memory), getString(R.string.brain_less),
                getString(R.string.brain_more), 10, Brain.remembered(this) - 2, new Value() {
                    @Override
                    public String show(int p) {
                        return String.valueOf(p + 2);
                    }

                    @Override
                    public void set(int p) {
                        Brain.setRemembered(BrainActivity.this, p + 2);
                    }
                }));
        set.addView(line());
        set.addView(slider(getString(R.string.brain_sleep_after), getString(R.string.brain_sooner),
                getString(R.string.brain_later), 14, Brain.sleepMinutes(this) - 1, new Value() {
                    @Override
                    public String show(int p) {
                        return getString(R.string.brain_minutes, p + 1);
                    }

                    @Override
                    public void set(int p) {
                        Brain.setSleepMinutes(BrainActivity.this, p + 1);
                    }
                }));
        set.addView(line());
        LinearLayout who = Ui.column(this);
        who.setPadding(dp(16), dp(12), dp(16), dp(12));
        who.addView(Ui.text(this, getString(R.string.lab_brain_user), 16, Palette.INK));
        who.addView(Kit.segments(this, new String[] {getString(R.string.lab_brain_user_he),
                getString(R.string.lab_brain_user_she)}, Prefs.userFemale(this) ? 1 : 0, new Kit.Chosen() {
                    @Override
                    public void chose(int which) {
                        Prefs.setUserFemale(BrainActivity.this, which == 1);
                    }
                }), Kit.below(this, 10));
        set.addView(who);
        list.addView(set);

        list.addView(Kit.section(this, getString(R.string.brain_prompts)));
        prompts = Kit.plate(this);
        list.addView(prompts);

        list.addView(Kit.section(this, getString(R.string.brain_guards)));
        LinearLayout guards = Kit.plate(this);
        guards.setPadding(0, dp(6), 0, dp(6));
        String[] gt = getResources().getStringArray(R.array.brain_guard_titles);
        String[] gs = getResources().getStringArray(R.array.brain_guard_notes);
        for (int i = 0; i < gt.length && i < gs.length; i++) {
            LinearLayout g = Ui.column(this);
            g.setPadding(dp(16), dp(10), dp(16), dp(10));
            g.addView(Ui.text(this, "\u25aa " + gt[i], 15, Palette.INK));
            TextView n = Ui.text(this, gs[i], 12, Palette.HINT);
            n.setLineSpacing(0, 1.3f);
            g.addView(n);
            guards.addView(g);
        }
        list.addView(guards);
    }

    private View line() {
        View v = new View(this);
        v.setBackgroundColor(Palette.RAISED);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        p.leftMargin = dp(16);
        p.rightMargin = dp(16);
        v.setLayoutParams(p);
        return v;
    }

    private interface Value {
        String show(int position);

        void set(int position);
    }

    private View slider(String title, String left, String right, int max, int at, final Value value) {
        LinearLayout box = Ui.column(this);
        box.setPadding(dp(16), dp(12), dp(16), dp(10));
        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, title, 16, Palette.INK),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        int start = Math.max(0, Math.min(max, at));
        final TextView shown = Ui.text(this, value.show(start), 14, Palette.ACCENT_TEXT);
        shown.setTypeface(Palette.bodyStrong(this));
        head.addView(shown);
        box.addView(head);
        SeekBar bar = Ui.slider(this, max, start);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                shown.setText(value.show(p));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                value.set(s.getProgress());
            }
        });
        box.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP));
        LinearLayout ends = Ui.row(this);
        ends.addView(Ui.text(this, left, 12, Palette.HINT),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ends.addView(Ui.text(this, right, 12, Palette.HINT));
        box.addView(ends);
        return box;
    }

    private void refresh() {
        Brain b = Brain.get(this);
        boolean awake = b.awake();
        state.setText(awake ? R.string.brain_awake : R.string.brain_asleep);
        String line = awake && b.lastSpeed() > 0
                ? getString(R.string.brain_awake_line, b.lastSpeed(), Brain.sleepMinutes(this))
                : getString(R.string.brain_asleep_line);
        if (b.draftRefused()) {
            line += " " + getString(R.string.brain_draft_refused);
        }
        stateLine.setText(line);
        int count = 0;
        for (int i = 0; i < 3; i++) {
            if (b.has(i)) {
                count++;
            }
        }
        sizeLine.setText(getString(R.string.brain_size, b.size() / 1e9, count));
        int[] roles = {R.string.brain_role_model, R.string.brain_role_vision, R.string.brain_role_draft};
        for (int i = 0; i < 3; i++) {
            TextView[] t = (TextView[]) slotRows[i].getTag();
            if (b.has(i)) {
                String name = b.slotName(i);
                t[1].setText(name.isEmpty() ? getString(R.string.brain_name_unknown) : name);
                t[1].setTextColor(Palette.MUTED);
                String size = String.format(Locale.getDefault(), "%.2f\u00a0GB", b.slotFile(i).length() / 1e9);
                String bits = bits(name);
                // A part the engine dropped says so instead of promising what it cannot do.
                boolean unfit = i == Brain.SLOT_VISION && b.visionRefused() || i == Brain.SLOT_DRAFT && b.draftRefused();
                t[2].setText(size + (bits.isEmpty() ? "" : " \u00b7 " + bits) + " \u00b7 "
                        + getString(unfit ? R.string.brain_part_unfit : roles[i]));
            } else {
                t[1].setText(R.string.lab_brain_none);
                t[1].setTextColor(Palette.HINT);
                t[2].setText(roles[i]);
            }
        }
        showGet();
        boolean accel = Brain.get(this).has(Brain.SLOT_DRAFT);
        draftToggle.setVisibility(accel ? View.VISIBLE : View.GONE);
        draftLine.setVisibility(accel ? View.VISIBLE : View.GONE);
        prompts.removeAllViews();
        prompts.addView(promptRow(getString(R.string.brain_rules_title), Brain.rules(this), 0));
        for (int place = 0; place < Cast.COUNT; place++) {
            int v = Cast.at(place);
            prompts.addView(line());
            prompts.addView(promptRow(Cast.name(this, v), Brain.manner(this, v), v));
        }
    }

    /** A file row: the place's name, the file's name (cut in the middle if long), size and what it does. */
    private View fileRow(String title, View.OnClickListener l) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout words = Ui.column(this);
        TextView t = Ui.text(this, title, 16, Palette.INK);
        words.addView(t);
        TextView name = Ui.text(this, "", 13, Palette.MUTED);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        words.addView(name, Kit.below(this, 2));
        TextView meta = Ui.text(this, "", 12, Palette.HINT);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(meta, Kit.below(this, 2));
        r.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams cp = Ui.lp(Ui.WRAP, Ui.WRAP);
        cp.leftMargin = dp(12);
        r.addView(Ui.text(this, "\u203a", 20, Palette.HINT), cp);
        r.setTag(new TextView[] {t, name, meta});
        r.setBackground(Ui.pressable(new android.graphics.drawable.ColorDrawable(0)));
        r.setOnClickListener(l);
        return r;
    }

    /** How finely the file is packed, read from its name: Q4 is 4 bits, F16 is 16. */
    private String bits(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(?:^|[-_.])(?:I?Q|F|BF)(\\d{1,2})(?:[_.-]|$)")
                .matcher(name);
        if (!m.find()) {
            return "";
        }
        int n = Integer.parseInt(m.group(1));
        return getResources().getQuantityString(R.plurals.brain_bits, n, n);
    }

    private String fileLabel(Brain b, int slot) {
        String name = b.slotName(slot);
        String size = String.format(Locale.getDefault(), "%.2f GB", b.slotFile(slot).length() / 1e9);
        return name.isEmpty() ? size : name + " \u00b7 " + size;
    }

    /** A prompt row: its title and the first two lines of the text; a touch opens it for editing. */
    private View promptRow(String title, String text, final int voice) {
        LinearLayout r = Ui.column(this);
        r.setPadding(dp(16), dp(12), dp(16), dp(12));
        r.addView(Ui.text(this, title, 16, Palette.INK));
        TextView t = Ui.text(this, text, 13, Palette.MUTED);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(t);
        r.setBackground(Ui.pressable(new android.graphics.drawable.ColorDrawable(0)));
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editPrompt(voice);
            }
        });
        return r;
    }

    private void editPrompt(final int voice) {
        final EditText edit = new EditText(this);
        Ui.field(edit);
        edit.setTextColor(Palette.INK);
        edit.setTextSize(15);
        edit.setTypeface(Palette.body(this));
        edit.setBackground(Ui.round(Palette.RAISED, 16, this));
        edit.setPadding(dp(14), dp(12), dp(14), dp(12));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setMinLines(5);
        edit.setMaxLines(12);
        edit.setText(voice == 0 ? Brain.rules(this) : Brain.manner(this, voice));
        TextView back = Kit.link(this, getString(R.string.brain_restore), null);
        LinearLayout lesser = Ui.column(this);
        lesser.addView(back);
        final android.app.Dialog d = Kit.sheet(this, voice == 0 ? getString(R.string.brain_rules_title)
                : Cast.name(this, voice), edit, getString(R.string.brain_save), new Runnable() {
                    @Override
                    public void run() {
                        String text = edit.getText().toString().trim();
                        if (voice == 0) {
                            Brain.setRules(BrainActivity.this, text.isEmpty() ? null : text);
                        } else {
                            Brain.setManner(BrainActivity.this, voice, text.isEmpty() ? null : text);
                        }
                        refresh();
                    }
                }, lesser);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (voice == 0) {
                    Brain.setRules(BrainActivity.this, null);
                } else {
                    Brain.setManner(BrainActivity.this, voice, null);
                }
                d.dismiss();
                refresh();
            }
        });
        d.show();
    }

    private void slotSheet(final int slot) {
        final Brain b = Brain.get(this);
        if (!b.has(slot) && slot == Brain.SLOT_DRAFT) {
            draftSheet();
            return;
        }
        if (!b.has(slot)) {
            pick(slot);
            return;
        }
        TextView remove = Kit.link(this, getString(R.string.brain_remove), null);
        LinearLayout lesser = Ui.column(this);
        lesser.addView(remove);
        if (slot == Brain.SLOT_MODEL && !b.isDefaultModel()) {
            TextView back = Kit.link(this, getString(R.string.brain_restore_default), null);
            lesser.addView(back);
            back.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    restoreDefault();
                }
            });
        }
        TextView about = Ui.text(this, fileLabel(b, slot), 14, Palette.MUTED);
        final android.app.Dialog d = Kit.sheet(this, getString(slot == 0 ? R.string.brain_slot_model
                : slot == 1 ? R.string.brain_slot_vision : R.string.brain_slot_draft), about,
                getString(R.string.brain_replace), new Runnable() {
                    @Override
                    public void run() {
                        pick(slot);
                    }
                }, lesser);
        remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                b.remove(slot);
                d.dismiss();
                refresh();
            }
        });
        d.show();
    }

    /** The accelerator is optional: offered for download, or picked from a file. */
    private void draftSheet() {
        TextView fromFile = Kit.link(this, getString(R.string.brain_pick_file), null);
        LinearLayout lesser = Ui.column(this);
        lesser.addView(fromFile);
        TextView about = Ui.text(this, getString(R.string.brain_draft_about), 14, Palette.MUTED);
        about.setLineSpacing(0, 1.3f);
        final android.app.Dialog d = Kit.sheet(this, getString(R.string.brain_slot_draft), about,
                getString(R.string.brain_get_draft), new Runnable() {
                    @Override
                    public void run() {
                        fetch(new int[] {Brain.SLOT_DRAFT}, false);
                    }
                }, lesser);
        fromFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                pick(Brain.SLOT_DRAFT);
            }
        });
        d.show();
    }

    /** Brings back the default model, and its photo part if that is missing or did not fit. */
    private void restoreDefault() {
        Brain b = Brain.get(this);
        boolean photo = !b.has(Brain.SLOT_VISION) || b.visionRefused();
        fetch(photo ? new int[] {Brain.SLOT_MODEL, Brain.SLOT_VISION} : new int[] {Brain.SLOT_MODEL}, false);
    }

    /**
     * A file brought in by hand is not on Hora's list, and nothing checks what
     * is inside it: the person hears that once, plainly, before choosing it.
     */
    private void pick(final int slot) {
        TextView why = Kit.lead(this, getString(R.string.brain_own_file_warning));
        Kit.sheet(this, getString(R.string.brain_own_file_title), why, getString(R.string.brain_own_file_go),
                new Runnable() {
                    @Override
                    public void run() {
                        picking = slot;
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK);
                    }
                }).show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null || data.getData() == null || picking < 0) {
            return;
        }
        final int slot = picking;
        final Uri uri = data.getData();
        final String name = displayName(uri);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Brain.get(BrainActivity.this).importFile(slot, getContentResolver().openInputStream(uri), name,
                            new Brain.Progress() {
                                long shown;

                                @Override
                                public void copied(final long bytes) {
                                    if (bytes - shown < 25_000_000L) {
                                        return;
                                    }
                                    shown = bytes;
                                    handler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            // The row keeps its title, name and details lines; progress goes in the details.
                                            ((TextView[]) slotRows[slot].getTag())[2].setText(getString(
                                                    R.string.lab_brain_copying, (int) (bytes / 1_000_000L)));
                                        }
                                    });
                                }
                            });
                } catch (final Exception e) {
                    Diag.log(BrainActivity.this, "brain: import failed", e);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            waking = false;
                            refresh();
                            stateLine.setText(getString(R.string.lab_brain_failed, String.valueOf(e.getMessage())));
                        }
                    });
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                    }
                });
            }
        }, "brain-import").start();
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (RuntimeException ignored) {
            // A name is only for the screen.
        }
        return "";
    }

    private void trial() {
        stateLine.setText(R.string.lab_brain_waking);
        final int v = Prefs.role(this, Cast.TALK);
        final String question = Cast.say(this, R.array.brain_ready_question, v);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Brain b = Brain.get(BrainActivity.this);
                    java.util.List<String[]> turns = new java.util.ArrayList<String[]>();
                    turns.add(new String[] {"user", question});
                    final Brain.Reply reply = b.ask(Brain.persona(BrainActivity.this, v), turns,
                            Brain.replyTokens(BrainActivity.this));
                    final double woke = b.wokeIn();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            waking = false;
                            refresh();
                            stateLine.setText(getString(R.string.lab_brain_result, reply.text, woke, reply.seconds,
                                    reply.tokensPerSecond));
                            VoiceService.speak(BrainActivity.this, reply.text, v);
                        }
                    });
                } catch (final Exception e) {
                    Diag.log(BrainActivity.this, "brain: trial failed", e);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            stateLine.setText(getString(R.string.lab_brain_failed, String.valueOf(e.getMessage())));
                        }
                    });
                }
            }
        }, "brain-trial").start();
    }
}
