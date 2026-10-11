package com.shumtugle.hora;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * A voice's own settings on one screen, next to a text that stays until it is
 * changed. Pick a face, move a slider, listen, without leaving. Only what
 * belongs to the voice lives here (pitch, timbre, air); what holds for every
 * voice is in Settings, under Reading.
 */
public final class LabActivity extends Activity {
    static final String EXTRA_VOICE = "voice";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int built;
    /** How many voices stood in the cast when the screen was built. */
    private int cast;
    private int voice;
    private final ImageView[] faces = new ImageView[Cast.LAST];
    private final TextView[] names = new TextView[Cast.LAST];
    private EditText text;
    private TextView info;
    private TextView ownTitle;
    private LinearLayout own;

    static void open(Activity a, int voice) {
        a.startActivity(new Intent(a, LabActivity.class).putExtra(EXTRA_VOICE, voice));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        cast = Cast.size(this);
        voice = getIntent().getIntExtra(EXTRA_VOICE, Prefs.role(this, Cast.NARRATOR));
        if (!Cast.available(this, voice)) {
            voice = 1;
        }
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        if (cast != Cast.size(this)) {
            // The trial voices came or went while the screen was away: it is laid out anew.
            recreate();
            return;
        }
        showRatio();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private int dp(float v) {
        return Kit.dp(this, v);
    }

    private void build() {
        LinearLayout list = Kit.room(this, getString(R.string.lab_title));

        // The cast in one row; the trial voices, when shown, in a second row of the same cells.
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.BOTTOM);
        LinearLayout faceRows = Ui.column(this);
        faceRows.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP));
        for (int i = 0; i < Cast.size(this); i++) {
            final int v = Cast.at(i);
            if (i == Cast.COUNT) {
                row = Ui.row(this);
                row.setGravity(Gravity.TOP);
                LinearLayout.LayoutParams second = Ui.lp(Ui.MATCH, Ui.WRAP);
                second.topMargin = dp(14);
                faceRows.addView(row, second);
            }
            LinearLayout cell = Ui.column(this);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            faces[i] = Ui.portrait(this, v);
            faces[i].setClickable(true);
            faces[i].setContentDescription(Cast.name(this, v));
            faces[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    choose(v);
                }
            });
            cell.addView(faces[i]);
            names[i] = Ui.text(this, Cast.name(this, v), 12, Palette.MUTED);
            // Long trial names break onto a second line, centred under the face.
            names[i].setGravity(Gravity.CENTER_HORIZONTAL);
            names[i].setMaxLines(2);
            LinearLayout.LayoutParams np = Ui.lp(Ui.WRAP, Ui.WRAP);
            np.topMargin = dp(6);
            cell.addView(names[i], np);
            row.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        // An unfinished second row keeps the cells as wide as the first one's.
        for (int i = Cast.size(this); i > Cast.COUNT && i % Cast.COUNT != 0; i++) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        list.addView(faceRows, Kit.below(this, 20));

        // The text plate: what is pasted here stays until it is changed or cleared.
        LinearLayout plate = Kit.plate(this);
        plate.setPadding(dp(16), dp(14), dp(16), dp(14));
        text = new EditText(this);
        Ui.field(text);
        text.setTypeface(Palette.display(this));
        text.setTextSize(18);
        text.setTextColor(Palette.INK);
        text.setHintTextColor(Palette.HINT);
        text.setHint(R.string.lab_hint);
        text.setBackground(null);
        text.setPadding(0, 0, 0, 0);
        text.setGravity(Gravity.TOP | Gravity.START);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        text.setMinLines(4);
        text.setMaxLines(8);
        text.setVerticalScrollBarEnabled(true);
        text.setText(Prefs.labText(this));
        text.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                Prefs.setLabText(LabActivity.this, e.toString());
            }
        });
        plate.addView(text, Ui.lp(Ui.MATCH, Ui.WRAP));
        LinearLayout actions = Ui.row(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView listen = Kit.primary(this, getString(R.string.lab_listen), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = text.getText().toString().trim();
                if (!t.isEmpty()) {
                    VoiceService.speak(LabActivity.this, t, voice);
                    pollRatio();
                }
            }
        });
        // The one main action across the whole plate; writing and clearing on the row below.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(14);
        plate.addView(listen, lp);
        TextView save = Kit.secondary(this, getString(R.string.lab_save), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = text.getText().toString().trim();
                if (!t.isEmpty()) {
                    VoiceService.record(LabActivity.this, t, voice);
                    info.setText(R.string.lab_saving);
                    pollRatio();
                }
            }
        });
        actions.addView(save, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)));
        actions.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView clear = Kit.link(this, getString(R.string.lab_clear), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                text.setText("");
            }
        });
        actions.addView(clear, Ui.lp(Ui.WRAP, Ui.WRAP));
        plate.addView(actions, Kit.below(this, 10));
        info = Ui.text(this, "", 12, Palette.HINT);
        plate.addView(info, Kit.below(this, 10));
        list.addView(plate, Kit.below(this, 18));

        // This voice's own makeup.
        ownTitle = Kit.section(this, "");
        list.addView(ownTitle);
        own = Kit.plate(this);
        list.addView(own);

        choose(voice);
    }

    /** The instruction a voice's mind starts from. */
    static String persona(Context c, int voice) {
        return Brain.persona(c, voice);
    }

    private void choose(final int v) {
        voice = v;
        for (int i = 0; i < faces.length && faces[i] != null; i++) {
            boolean on = Cast.at(i) == v;
            // Every face keeps its size; the ring marks the chosen one.
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(52), dp(52));
            faces[i].setLayoutParams(p);
            faces[i].setPadding(dp(3), dp(3), dp(3), dp(3));
            faces[i].setBackground(on ? Ui.oval(Palette.BG, Palette.voice(Cast.at(i)), 2, this) : null);
            faces[i].setAlpha(on ? 1f : 0.7f);
            names[i].setTextColor(on ? Palette.INK : Palette.MUTED);
            names[i].setTypeface(on ? Palette.bodyStrong(this) : Palette.body(this));
        }
        ownTitle.setText(Cast.name(this, v));
        own.removeAllViews();
        own.addView(Kit.slider(this, getString(R.string.ws_pitch), getString(R.string.lab_lower),
                getString(R.string.lab_higher), Grim.PITCH_MAX - Grim.PITCH_MIN, Prefs.grimPitch(this, v) - Grim.PITCH_MIN,
                new Kit.Value() {
                    @Override
                    public String show(int p) {
                        return signed(p + Grim.PITCH_MIN);
                    }

                    @Override
                    public void set(int p) {
                        Prefs.setGrim(LabActivity.this, v, Prefs.grimTimbre(LabActivity.this, v), p + Grim.PITCH_MIN);
                    }
                }));
        own.addView(Kit.divider(this));
        own.addView(Kit.slider(this, getString(R.string.ws_timbre), getString(R.string.lab_warmer),
                getString(R.string.lab_brighter), Grim.TIMBRE_MAX - Grim.TIMBRE_MIN,
                Prefs.grimTimbre(this, v) - Grim.TIMBRE_MIN, new Kit.Value() {
                    @Override
                    public String show(int p) {
                        return signed(p + Grim.TIMBRE_MIN);
                    }

                    @Override
                    public void set(int p) {
                        Prefs.setGrim(LabActivity.this, v, p + Grim.TIMBRE_MIN, Prefs.grimPitch(LabActivity.this, v));
                    }
                }));
        own.addView(Kit.divider(this));
        // The rarer makeup: air, and the way back to how the voice came.
        LinearLayout fine = Ui.column(this);
        fine.addView(Kit.slider(this, getString(R.string.lab_air), getString(R.string.lab_none),
                getString(R.string.lab_much), Grim.AIR_MAX, Prefs.grimAir(this, v), new Kit.Value() {
                    @Override
                    public String show(int p) {
                        return String.valueOf(p);
                    }

                    @Override
                    public void set(int p) {
                        Prefs.setGrimAir(LabActivity.this, v, p);
                    }
                }));
        TextView reset = Kit.link(this, getString(R.string.lab_reset), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Prefs.setGrim(LabActivity.this, v, Prefs.defaultTimbre(v), Prefs.defaultPitch(v));
                Prefs.setGrimAir(LabActivity.this, v, Prefs.defaultAir(v));
                choose(v);
            }
        });
        reset.setPadding(dp(16), dp(6), dp(16), dp(14));
        fine.addView(reset);
        own.addView(Kit.fold(this, "voice_fine", getString(R.string.fine_tuning), fine));
    }

    private static String signed(int n) {
        return n > 0 ? "+" + n : String.valueOf(n);
    }

    /** Reads how fast the voice made the last speech, a few times after a request. */
    private void pollRatio() {
        handler.removeCallbacksAndMessages(null);
        for (int i = 1; i <= 12; i++) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    showRatio();
                }
            }, i * 2500L);
        }
    }

    private void showRatio() {
        float r = Prefs.lastRatio(this);
        info.setText(r > 0 ? getString(R.string.lab_ratio, String.format(Locale.getDefault(), "%.1f", r))
                : getString(R.string.lab_kept));
    }
}
