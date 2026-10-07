package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Who reads a book: the narrator and the two alternating speakers of
 * dialogue. A short example shows each part in its reader's color and,
 * tapped, is read aloud with the voices as they stand.
 */
public final class RolesActivity extends Activity {
    /** The look this screen was built with. */
    private int built;
    private static final int[] ROLES = {Cast.NARRATOR, Cast.SPEAKER_A, Cast.SPEAKER_B, Cast.HERALD, Cast.TALK};
    private static final int[] TITLES = {R.string.role_narrator, R.string.role_speaker_a, R.string.role_speaker_b,
        R.string.role_herald, R.string.role_talk};
    private static final float DIMMED = 0.55f;

    private TextView sample;
    private final TextView[] chosen = new TextView[ROLES.length];
    private final ImageView[][] faces = new ImageView[ROLES.length][Cast.COUNT + 1];

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        setContentView(build());
        paint();
    }

    private View build() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Palette.BG);
        root.setPadding(dp(20), dp(20), dp(20), dp(16));

        LinearLayout head = Ui.header(this, getString(R.string.roles_title), true);
        root.addView(head, Ui.lp(Ui.MATCH, dp(48)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = Ui.column(this);
        body.setPadding(0, dp(16), 0, 0);

        sample = Ui.title(this, "", 19);
        sample.setLineSpacing(0, 1.3f);
        sample.setPadding(dp(18), dp(16), dp(18), dp(16));
        sample.setBackground(Ui.pressable(Ui.round(Palette.SURFACE, 24, this)));
        sample.setClickable(true);
        sample.setContentDescription(getString(R.string.roles_listen));
        sample.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VoiceService.speak(RolesActivity.this,
                        SpeechLanguage.resources(RolesActivity.this).getString(R.string.roles_sample_speech));
            }
        });
        body.addView(sample, Ui.lp(Ui.MATCH, Ui.WRAP));
        TextView hint = Ui.text(this, getString(R.string.roles_listen_hint), 12, Palette.HINT);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams hp = Ui.lp(Ui.MATCH, Ui.WRAP);
        hp.topMargin = dp(6);
        body.addView(hint, hp);

        for (int i = 0; i < ROLES.length; i++) {
            LinearLayout.LayoutParams rp = Ui.lp(Ui.MATCH, Ui.WRAP);
            rp.topMargin = dp(18);
            body.addView(roleRow(i), rp);
        }
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        LinearLayout toggle = Ui.row(this);
        toggle.setBackground(Ui.round(Palette.SURFACE, 26, this));
        toggle.setPadding(dp(16), 0, dp(12), 0);
        toggle.setMinimumHeight(dp(52));
        toggle.addView(Ui.text(this, getString(R.string.dialogue_switch), 15, Palette.INK), Ui.weight(1));
        Switch sw = new Switch(this);
        sw.setChecked(Prefs.dialogueVoice(this));
        sw.setThumbTintList(ColorStateList.valueOf(Palette.ACCENT));
        sw.setTrackTintList(ColorStateList.valueOf(Palette.LINE));
        sw.setContentDescription(getString(R.string.dialogue_switch));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Prefs.setDialogueVoice(RolesActivity.this, on);
                paint();
            }
        });
        toggle.addView(sw);
        LinearLayout.LayoutParams tgp = Ui.lp(Ui.MATCH, Ui.WRAP);
        tgp.topMargin = dp(12);
        root.addView(toggle, tgp);
        root.addView(Ui.navBar(this, Ui.NAV_ROLES), Ui.lp(Ui.MATCH, Ui.WRAP));
        return root;
    }

    /** A role: its title, the chosen voice by name, and a portrait per voice to pick from. */
    private View roleRow(final int i) {
        LinearLayout box = Ui.column(this);
        LinearLayout line = Ui.row(this);
        TextView t = Ui.text(this, getString(TITLES[i]), 15, Palette.INK);
        t.setTypeface(Palette.bodyStrong(this));
        line.addView(t, Ui.weight(1));
        chosen[i] = Ui.title(this, "", 20);
        // The chosen voice's name opens its own room, where it is tuned.
        chosen[i].setClickable(true);
        chosen[i].setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WorkshopActivity.openCabinet(RolesActivity.this, Prefs.role(RolesActivity.this, ROLES[i]));
            }
        });
        line.addView(chosen[i]);
        box.addView(line, Ui.lp(Ui.MATCH, Ui.WRAP));
        LinearLayout faceRow = Ui.row(this);
        for (int place = 0; place < Cast.COUNT; place++) {
            final int v = Cast.at(place);
            final int voice = v;
            ImageView f = Ui.portrait(this, v);
            f.setPadding(dp(3), dp(3), dp(3), dp(3));
            f.setClickable(true);
            f.setContentDescription(getString(TITLES[i]) + ": " + Cast.name(this, v));
            f.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Prefs.setRole(RolesActivity.this, ROLES[i], voice);
                    paint();
                }
            });
            faces[i][v] = f;
            LinearLayout.LayoutParams fp = Ui.lp(dp(54), dp(54));
            if (v > 1) {
                fp.leftMargin = dp(8);
            }
            faceRow.addView(f, fp);
        }
        LinearLayout.LayoutParams rp = Ui.lp(Ui.WRAP, Ui.WRAP);
        rp.topMargin = dp(8);
        box.addView(faceRow, rp);
        return box;
    }

    /** Colors the example by reader and marks each role's chosen voice. */
    private void paint() {
        boolean dialogue = Prefs.dialogueVoice(this);
        for (int i = 0; i < ROLES.length; i++) {
            int v = Prefs.role(this, ROLES[i]);
            chosen[i].setText(Cast.name(this, v));
            chosen[i].setTextColor(Palette.voice(v));
            // The dialogue switch governs only the two speakers.
            boolean active = ROLES[i] == Cast.NARRATOR || ROLES[i] == Cast.HERALD || ROLES[i] == Cast.TALK || dialogue;
            chosen[i].setAlpha(active ? 1f : 0.4f);
            for (int w = 1; w <= Cast.COUNT; w++) {
                ImageView f = faces[i][w];
                boolean on = w == v;
                f.setBackground(Ui.oval(Palette.BG, on ? Palette.voice(w) : Color.TRANSPARENT, 3, this));
                f.setAlpha(on ? (active ? 1f : 0.5f) : DIMMED * (active ? 1f : 0.6f));
            }
        }
        int narrator = Palette.voice(Prefs.role(this, Cast.NARRATOR));
        int a = dialogue ? Palette.voice(Prefs.role(this, Cast.SPEAKER_A)) : narrator;
        int b = dialogue ? Palette.voice(Prefs.role(this, Cast.SPEAKER_B)) : narrator;
        String[] parts = getResources().getStringArray(R.array.roles_sample);
        int[] who = getResources().getIntArray(R.array.roles_sample_readers);
        SpannableStringBuilder s = new SpannableStringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                s.append(' ');
            }
            int start = s.length();
            s.append(parts[i]);
            int reader = i < who.length ? who[i] : 0;
            int color = reader == 1 ? a : reader == 2 ? b : narrator;
            s.setSpan(new ForegroundColorSpan(color), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sample.setText(s);
        // The small stone on the home screen shows the narrator.
        Widgets.refresh(this);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.stale(this, built);
    }
}
