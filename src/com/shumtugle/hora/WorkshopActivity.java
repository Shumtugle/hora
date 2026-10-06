package com.shumtugle.hora;

import android.animation.AnimatorSet;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * The voice workshop: pick a voice and change how it sounds. Timbre and
 * pitch are makeup on the voice's own sample, applied once when the sample
 * is read; tempo and character apply to all speech.
 */
public final class WorkshopActivity extends Activity {
    /** The look this screen was built with. */
    private int built;
    private static final int TAB_VOICE = 0;
    private static final int TAB_TIMBRE = 1;
    private static final int TAB_PITCH = 2;
    private static final int TAB_TEMPO = 3;
    private static final int TAB_MOOD = 4;
    private static final int[] TAB_LABELS = {
        R.string.ws_troupe, R.string.ws_timbre, R.string.ws_pitch, R.string.ws_tempo, R.string.ws_mood,
    };
    private static final String STATE_TAB = "tab";

    private int voice;
    private int tab;
    private View ring;
    private ImageView photo;
    private TextView name;
    private TextView summary;
    private FrameLayout page;
    private final TextView[] tabs = new TextView[TAB_LABELS.length];
    private final View[] cards = new View[Cast.COUNT + 1];
    private AnimatorSet breath;
    private TextView listen;
    private boolean speaking;
    private final android.content.BroadcastReceiver idle = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, Intent i) {
            showSpeaking(false);
        }
    };

    /** Opened with a voice, the workshop becomes that voice's own room: its name on top, no troupe, a way back. */
    static final String EXTRA_VOICE = "voice";
    private boolean cabinet;

    /** Opens the room of one voice, from wherever its portrait was touched. */
    static void openCabinet(Activity a, int voice) {
        a.startActivity(new Intent(a, WorkshopActivity.class).putExtra(EXTRA_VOICE, voice));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        int asked = getIntent().getIntExtra(EXTRA_VOICE, 0);
        cabinet = asked >= 1 && asked <= Cast.COUNT;
        voice = cabinet ? asked : Prefs.homeVoice(this);
        int first = cabinet ? TAB_TIMBRE : TAB_VOICE;
        tab = state == null ? first : state.getInt(STATE_TAB, first);
        setContentView(build());
        showVoice();
        showTab(tab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        breath.start();
        android.content.IntentFilter f = new android.content.IntentFilter(VoiceService.ACTION_IDLE);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(idle, f, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(idle, f);
        }
    }

    @Override
    protected void onPause() {
        breath.cancel();
        unregisterReceiver(idle);
        super.onPause();
    }

    /** The listen button turns into a stop button while the voice reads. */
    private void showSpeaking(boolean on) {
        speaking = on;
        listen.setText(on ? R.string.ws_stop : R.string.ws_listen);
        listen.setCompoundDrawablesRelativeWithIntrinsicBounds(
                on ? R.drawable.ic_stop_small : R.drawable.ic_play_small, 0, 0, 0);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_TAB, tab);
    }

    private View build() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Palette.BG);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));

        root.addView(Ui.header(this, cabinet ? Cast.name(this, voice) : getString(R.string.workshop), false),
                Ui.lp(Ui.MATCH, dp(48)));

        LinearLayout hero = Ui.column(this);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(0, dp(10), 0, dp(12));
        FrameLayout face = new FrameLayout(this);
        face.setClipChildren(false);
        ring = new View(this);
        face.addView(ring, new FrameLayout.LayoutParams(dp(156), dp(156), Gravity.CENTER));
        photo = Ui.portrait(this, voice);
        face.addView(photo, new FrameLayout.LayoutParams(dp(144), dp(144), Gravity.CENTER));
        breath = Ui.breathe(ring, 0.95f, 1.04f, 0.6f, 1f);
        final android.graphics.Bitmap scene = cabinet ? Cast.card(this, voice, new java.util.Random()) : null;
        if (scene != null) {
            // In a voice's own room a scene of it at work stands where the portrait would; a touch shows another.
            final ImageView card = new ImageView(this);
            card.setImageBitmap(scene);
            card.setAdjustViewBounds(true);
            card.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.setContentDescription(Cast.name(this, voice));
            card.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(24));
                }
            });
            card.setClipToOutline(true);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    android.graphics.Bitmap next = Cast.card(WorkshopActivity.this, voice, new java.util.Random());
                    if (next != null) {
                        card.setImageBitmap(next);
                    }
                }
            });
            hero.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP));
        } else {
            // The ring breathes past its own size; the frame leaves room so it is never cut.
            hero.addView(face, Ui.lp(dp(180), dp(180)));
        }

        LinearLayout caption = Ui.row(this);
        caption.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        name = Ui.title(this, "", 26);
        caption.addView(name);
        summary = Ui.text(this, "", 13, Palette.MUTED);
        LinearLayout.LayoutParams sp = Ui.lp(Ui.WRAP, Ui.WRAP);
        sp.leftMargin = dp(10);
        sp.bottomMargin = dp(3);
        caption.addView(summary, sp);
        LinearLayout.LayoutParams cp = Ui.lp(Ui.WRAP, Ui.WRAP);
        cp.topMargin = dp(10);
        hero.addView(caption, cp);

        listen = Ui.pill(this, getString(R.string.ws_listen), Palette.ACCENT, 0, Palette.ON_ACCENT, true);
        listen.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Palette.ON_ACCENT));
        listen.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_play_small, 0, 0, 0);
        listen.setCompoundDrawablePadding(dp(8));
        listen.setPadding(dp(22), 0, dp(22), 0);
        listen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (speaking) {
                    VoiceService.stop(WorkshopActivity.this);
                    showSpeaking(false);
                } else {
                    VoiceService.speak(WorkshopActivity.this, SpeechLanguage.resources(WorkshopActivity.this)
                            .getString(R.string.ws_listen_text), voice);
                    showSpeaking(true);
                }
            }
        });
        LinearLayout.LayoutParams lp = Ui.lp(Ui.WRAP, dp(44));
        lp.topMargin = dp(10);
        hero.addView(listen, lp);
        root.addView(hero, Ui.lp(Ui.MATCH, Ui.WRAP));

        LinearLayout panel = Ui.column(this);
        panel.setBackground(Ui.round(Palette.SURFACE, 28, this));
        panel.setPadding(dp(16), dp(18), dp(16), dp(14));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page = new FrameLayout(this);
        scroll.addView(page);
        panel.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        // All tabs share the width; the longest labels give up a little of theirs.
        LinearLayout strip = Ui.row(this);
        strip.setBackground(Ui.round(Palette.BG, 24, this));
        strip.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int which = i;
            tabs[i] = Ui.pill(this, getString(TAB_LABELS[i]), Palette.BG, 0, Palette.MUTED, false);
            tabs[i].setTextSize(13);
            tabs[i].setPadding(dp(2), 0, dp(2), 0);
            Ui.oneLine(tabs[i], 10, 14);
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTab(which);
                }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1f);
            if (i > 0) {
                p.leftMargin = dp(2);
            }
            strip.addView(tabs[i], p);
        }
        LinearLayout.LayoutParams stp = Ui.lp(Ui.MATCH, Ui.WRAP);
        stp.topMargin = dp(12);
        panel.addView(strip, stp);
        root.addView(panel, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        if (cabinet) {
            // One voice's room has no troupe to choose from.
            tabs[TAB_VOICE].setVisibility(View.GONE);
        }
        return root;
    }

    private void pickVoice(int v) {
        voice = v;
        Prefs.setHomeVoice(this, v);
        showVoice();
    }

    private void showVoice() {
        Ui.setFace(photo, voice);
        photo.setContentDescription(Cast.name(this, voice));
        name.setText(Cast.name(this, voice));
        ring.setBackground(Ui.oval(Color.TRANSPARENT, Palette.voice(voice), 2, this));
        for (int v = 1; v <= Cast.COUNT; v++) {
            if (cards[v] != null) {
                paintCard(v);
            }
        }
        showSummary();
        if (tab == TAB_TIMBRE || tab == TAB_PITCH) {
            // Makeup belongs to one voice: its sliders follow the pick.
            showTab(tab);
        }
    }

    /** One line under the name: what has been done to this voice. */
    private void showSummary() {
        StringBuilder s = new StringBuilder();
        int pitch = Prefs.grimPitch(this, voice);
        int timbre = Prefs.grimTimbre(this, voice);
        if (pitch != 0) {
            s.append(getString(pitch < 0 ? R.string.ws_deeper : R.string.ws_higher)).append(" \u00b7 ");
        }
        if (timbre != 0) {
            s.append(getString(timbre < 0 ? R.string.ws_warmer : R.string.ws_brighter)).append(" \u00b7 ");
        }
        s.append(tempoLabel(Prefs.speed(this)));
        summary.setText(s);
    }

    private void showTab(int which) {
        tab = which;
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == which;
            tabs[i].setBackground(Ui.pressable(Ui.round(on ? Palette.RAISED : Palette.BG, 22, this)));
            tabs[i].setTextColor(on ? Palette.INK : Palette.MUTED);
            tabs[i].setTypeface(on ? Palette.bodyStrong(this) : Palette.body(this));
        }
        page.removeAllViews();
        View content;
        switch (which) {
            case TAB_TIMBRE:
                content = timbrePage();
                break;
            case TAB_PITCH:
                content = pitchPage();
                break;
            case TAB_TEMPO:
                content = tempoPage();
                break;
            case TAB_MOOD:
                content = moodPage();
                break;
            default:
                content = voicesPage();
                break;
        }
        page.addView(content, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
    }

    private View voicesPage() {
        LinearLayout grid = Ui.column(this);
        LinearLayout line = null;
        for (int v = 1; v <= Cast.COUNT; v++) {
            if ((v - 1) % 2 == 0) {
                line = Ui.row(this);
                line.setGravity(Gravity.TOP);
                LinearLayout.LayoutParams p = Ui.lp(Ui.MATCH, Ui.WRAP);
                if (v > 1) {
                    p.topMargin = dp(10);
                }
                grid.addView(line, p);
            }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
            if ((v - 1) % 2 == 1) {
                cp.leftMargin = dp(10);
            }
            line.addView(card(v), cp);
        }
        if (Cast.COUNT % 2 == 1) {
            // Keeps the last card half wide, like the others.
            line.addView(new View(this), leftGap());
        }
        return grid;
    }

    private LinearLayout.LayoutParams leftGap() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
        p.leftMargin = dp(10);
        return p;
    }

    private View card(final int v) {
        LinearLayout c = Ui.row(this);
        c.setMinimumHeight(dp(64));
        c.setPadding(dp(10), dp(8), dp(10), dp(8));
        ImageView face = Ui.portrait(this, v);
        c.addView(face, Ui.lp(dp(44), dp(44)));
        LinearLayout words = Ui.column(this);
        words.addView(Ui.title(this, Cast.name(this, v), 20));
        TextView kind = Ui.text(this, Cast.kind(this, v), 11, Palette.MUTED);
        Ui.oneLine(kind, 8, 11);
        LinearLayout.LayoutParams kp = Ui.lp(Ui.MATCH, Ui.WRAP);
        kp.topMargin = dp(2);
        words.addView(kind, kp);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        wp.leftMargin = dp(10);
        c.addView(words, wp);
        c.setClickable(true);
        c.setContentDescription(Cast.name(this, v));
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pickVoice(v);
            }
        });
        cards[v] = c;
        paintCard(v);
        return c;
    }

    private void paintCard(int v) {
        int edge = v == voice ? Palette.voice(v) : Palette.RAISED;
        cards[v].setBackground(Ui.pressable(Ui.outlined(Palette.BG, edge, 2, 18, this)));
    }

    private View timbrePage() {
        final TextView head = sliderTitle();
        final int t0 = Prefs.grimTimbre(this, voice);
        head.setText(getString(R.string.ws_timbre_value, timbreLabel(t0)));
        SeekBar bar = Ui.slider(this, Grim.TIMBRE_MAX - Grim.TIMBRE_MIN, t0 - Grim.TIMBRE_MIN);
        bar.setOnSeekBarChangeListener(new Slide() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                head.setText(getString(R.string.ws_timbre_value, timbreLabel(p + Grim.TIMBRE_MIN)));
                if (user) {
                    Prefs.setGrim(WorkshopActivity.this, voice, s.getProgress() + Grim.TIMBRE_MIN,
                            Prefs.grimPitch(WorkshopActivity.this, voice));
                    showSummary();
                }
            }
        });
        return sliderPage(head, bar, R.string.ws_warmer, R.string.ws_brighter, R.string.ws_timbre_note);
    }

    private View pitchPage() {
        final TextView head = sliderTitle();
        final int p0 = Prefs.grimPitch(this, voice);
        head.setText(getString(R.string.ws_pitch_value, pitchLabel(p0)));
        SeekBar bar = Ui.slider(this, Grim.PITCH_MAX - Grim.PITCH_MIN, p0 - Grim.PITCH_MIN);
        bar.setOnSeekBarChangeListener(new Slide() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                head.setText(getString(R.string.ws_pitch_value, pitchLabel(p + Grim.PITCH_MIN)));
                if (user) {
                    Prefs.setGrim(WorkshopActivity.this, voice,
                            Prefs.grimTimbre(WorkshopActivity.this, voice), s.getProgress() + Grim.PITCH_MIN);
                    showSummary();
                }
            }
        });
        return sliderPage(head, bar, R.string.ws_deeper, R.string.ws_higher, R.string.ws_pitch_note);
    }

    private View tempoPage() {
        final TextView head = sliderTitle();
        float cur = Prefs.speed(this);
        head.setText(getString(R.string.ws_tempo_value, tempoLabel(cur)));
        int steps = Math.round((Prefs.SPEED_MAX - Prefs.SPEED_MIN) * 20);
        SeekBar bar = Ui.slider(this, steps, Math.round((cur - Prefs.SPEED_MIN) * 20));
        bar.setOnSeekBarChangeListener(new Slide() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                float v = Prefs.SPEED_MIN + p / 20f;
                head.setText(getString(R.string.ws_tempo_value, tempoLabel(v)));
                if (user) {
                    Prefs.setSpeed(WorkshopActivity.this, v);
                    showSummary();
                }
            }
        });
        return sliderPage(head, bar, R.string.ws_slow, R.string.ws_fast, R.string.ws_tempo_note);
    }

    private View moodPage() {
        final TextView head = sliderTitle();
        float cur = Prefs.expression(this);
        head.setText(getString(R.string.ws_mood_value, number(cur)));
        int steps = Math.round((Prefs.EXPRESSION_MAX - Prefs.EXPRESSION_MIN) * 20);
        SeekBar bar = Ui.slider(this, steps, Math.round((cur - Prefs.EXPRESSION_MIN) * 20));
        bar.setOnSeekBarChangeListener(new Slide() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                float v = Prefs.EXPRESSION_MIN + p / 20f;
                head.setText(getString(R.string.ws_mood_value, number(v)));
                if (user) {
                    Prefs.setExpression(WorkshopActivity.this, v);
                }
            }
        });
        return sliderPage(head, bar, R.string.ws_calm, R.string.ws_lively, R.string.ws_mood_note);
    }

    private TextView sliderTitle() {
        TextView t = Ui.text(this, "", 15, Palette.INK);
        t.setTypeface(Palette.bodyStrong(this));
        return t;
    }

    private View sliderPage(TextView head, SeekBar bar, int left, int right, int note) {
        LinearLayout box = Ui.column(this);
        box.setPadding(dp(4), dp(8), dp(4), dp(8));
        box.addView(head);
        LinearLayout.LayoutParams bp = Ui.lp(Ui.MATCH, Ui.WRAP);
        bp.topMargin = dp(10);
        box.addView(bar, bp);
        LinearLayout ends = Ui.row(this);
        TextView l = Ui.text(this, getString(left), 13, Palette.MUTED);
        ends.addView(l, Ui.weight(1));
        ends.addView(Ui.text(this, getString(right), 13, Palette.MUTED));
        LinearLayout.LayoutParams ep = Ui.lp(Ui.MATCH, Ui.WRAP);
        ep.topMargin = dp(6);
        box.addView(ends, ep);
        TextView n = Ui.text(this, getString(note), 13, Palette.MUTED);
        n.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams np = Ui.lp(Ui.MATCH, Ui.WRAP);
        np.topMargin = dp(14);
        box.addView(n, np);
        return box;
    }

    private String timbreLabel(int t) {
        if (t == 0) {
            return getString(R.string.ws_as_sample);
        }
        return getString(t < 0 ? R.string.ws_warmer : R.string.ws_brighter) + " " + Math.abs(t);
    }

    private String pitchLabel(int p) {
        if (p == 0) {
            return getString(R.string.ws_as_sample);
        }
        return getString(R.string.ws_semitones, (p > 0 ? "+" : "\u2212") + Math.abs(p));
    }

    private static String tempoLabel(float v) {
        return number(v) + "\u00d7";
    }

    private static String number(float v) {
        return String.format(Locale.getDefault(), "%.2f", v);
    }

    private View.OnClickListener close() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        };
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private abstract static class Slide implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar s) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar s) {
        }
    }
}
