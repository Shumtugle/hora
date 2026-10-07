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
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * The lab: everything that shapes a voice on one screen, next to a text that
 * stays until it is changed. Pick a face, move a slider, listen, without
 * leaving. The voice's own makeup (timbre, pitch, air) is per voice; tempo,
 * pauses, character and the text rules are for all voices; the engine trial
 * is at the bottom.
 */
public final class LabActivity extends Activity {
    static final String EXTRA_VOICE = "voice";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int built;
    private int voice;
    private final ImageView[] faces = new ImageView[Cast.COUNT];
    private final TextView[] names = new TextView[Cast.COUNT];
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
        voice = getIntent().getIntExtra(EXTRA_VOICE, Prefs.role(this, Cast.NARRATOR));
        if (voice < 1 || voice > Cast.COUNT) {
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

        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.BOTTOM);
        for (int i = 0; i < Cast.COUNT; i++) {
            final int v = Cast.at(i);
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
            LinearLayout.LayoutParams np = Ui.lp(Ui.WRAP, Ui.WRAP);
            np.topMargin = dp(6);
            cell.addView(names[i], np);
            row.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        list.addView(row, Kit.below(this, 20));

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
        actions.addView(listen, new LinearLayout.LayoutParams(0, dp(48), 1f));
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
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
        sp.leftMargin = dp(10);
        actions.addView(save, sp);
        TextView clear = Kit.link(this, getString(R.string.lab_clear), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                text.setText("");
            }
        });
        LinearLayout.LayoutParams cp = Ui.lp(Ui.WRAP, Ui.WRAP);
        cp.leftMargin = dp(12);
        actions.addView(clear, cp);
        plate.addView(actions, Kit.below(this, 12));
        info = Ui.text(this, "", 12, Palette.HINT);
        plate.addView(info, Kit.below(this, 10));
        list.addView(plate, Kit.below(this, 18));

        // This voice's own makeup.
        ownTitle = Kit.section(this, "");
        list.addView(ownTitle);
        own = Kit.plate(this);
        list.addView(own);

        // For every voice.
        list.addView(Kit.section(this, getString(R.string.lab_all)));
        LinearLayout all = Kit.plate(this);
        final float exMin = Prefs.EXPRESSION_MIN;
        all.addView(slider(getString(R.string.lab_character), getString(R.string.ws_calm), getString(R.string.ws_lively),
                Math.round((Prefs.EXPRESSION_MAX - exMin) * 20), Math.round((Prefs.expression(this) - exMin) * 20),
                new Value() {
                    @Override
                    public String show(int p) {
                        return String.format(Locale.getDefault(), "%.2f", exMin + p / 20f);
                    }

                    @Override
                    public void set(int p) {
                        Prefs.setExpression(LabActivity.this, exMin + p / 20f);
                    }
                }));
        all.addView(line());
        final float spMin = 0.6f;
        all.addView(slider(getString(R.string.lab_tempo), getString(R.string.ws_slow), getString(R.string.ws_fast),
                Math.round((1.3f - spMin) * 20), Math.round((Prefs.speed(this) - spMin) * 20), new Value() {
                    @Override
                    public String show(int p) {
                        return String.format(Locale.getDefault(), "%.2f\u00d7", spMin + p / 20f);
                    }

                    @Override
                    public void set(int p) {
                        Prefs.setSpeed(LabActivity.this, Math.round((spMin + p / 20f) * 100f) / 100f);
                    }
                }));
        all.addView(line());
        all.addView(slider(getString(R.string.lab_pause), getString(R.string.lab_shorter), getString(R.string.lab_longer),
                40, Prefs.pauseMs(this) / 25, new Value() {
                    @Override
                    public String show(int p) {
                        return getString(R.string.lab_ms, p * 25);
                    }

                    @Override
                    public void set(int p) {
                        Prefs.setPauseMs(LabActivity.this, p * 25);
                    }
                }));
        all.addView(line());
        all.addView(Kit.rowToggle(this, getString(R.string.respell), null, Prefs.respell(this), new Kit.Flip() {
            @Override
            public void flipped(boolean on) {
                Prefs.setRespell(LabActivity.this, on);
            }
        }));
        all.addView(line());
        all.addView(Kit.rowToggle(this, getString(R.string.even_tempo), null, Prefs.evenTempo(this), new Kit.Flip() {
            @Override
            public void flipped(boolean on) {
                Prefs.setEvenTempo(LabActivity.this, on);
            }
        }));
        all.addView(line());
        all.addView(Kit.rowToggle(this, getString(R.string.slow_by_pauses), null, Prefs.slowByPauses(this),
                new Kit.Flip() {
                    @Override
                    public void flipped(boolean on) {
                        Prefs.setSlowByPauses(LabActivity.this, on);
                    }
                }));
        list.addView(all);

        // The engine trial.
        list.addView(Kit.section(this, getString(R.string.lab_engine)));
        LinearLayout engine = Kit.plate(this);
        LinearLayout segRow = Ui.column(this);
        segRow.setPadding(dp(16), dp(12), dp(16), dp(8));
        segRow.addView(Ui.text(this, getString(R.string.lab_decoder), 16, Palette.INK));
        LinearLayout.LayoutParams segP = Ui.lp(Ui.MATCH, Ui.WRAP);
        segP.topMargin = dp(10);
        segRow.addView(Kit.segments(this, new String[] {getString(R.string.lab_decoder_small),
                getString(R.string.lab_decoder_full)}, Prefs.fullDecoder(this) ? 1 : 0, new Kit.Chosen() {
                    @Override
                    public void chose(int which) {
                        Prefs.setFullDecoder(LabActivity.this, which == 1);
                    }
                }), segP);
        engine.addView(segRow);
        TextView note = Ui.text(this, getString(R.string.lab_decoder_note), 12, Palette.HINT);
        note.setLineSpacing(0, 1.3f);
        note.setPadding(dp(16), 0, dp(16), dp(14));
        engine.addView(note);
        list.addView(engine);

        // The brain has its own room now.
        list.addView(Kit.section(this, getString(R.string.lab_brain)));
        LinearLayout brain = Kit.plate(this);
        brain.addView(Kit.rowNav(this, getString(R.string.brain_title), "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(LabActivity.this, BrainActivity.class));
            }
        }));
        list.addView(brain);

        choose(voice);
    }

    private static final int PICK_MODEL = 51;
    private View modelRow;
    private TextView brainOut;

    private String modelLabel() {
        Brain b = Brain.get(this);
        return b.hasModel() ? String.format(Locale.getDefault(), "%.2f GB", b.modelFile().length() / 1e9)
                : getString(R.string.lab_brain_none);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_MODEL || result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final android.net.Uri uri = data.getData();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Brain.get(LabActivity.this).importModel(getContentResolver().openInputStream(uri),
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
                                            Kit.setValue(modelRow, getString(R.string.lab_brain_copying,
                                                    (int) (bytes / 1_000_000L)));
                                        }
                                    });
                                }
                            });
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Kit.setValue(modelRow, modelLabel());
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Kit.setValue(modelRow, modelLabel());
                            brainOut.setText(getString(R.string.lab_brain_failed, String.valueOf(e.getMessage())));
                        }
                    });
                }
            }
        }, "model-import").start();
    }

    /** The instruction a voice's mind starts from. */
    static String persona(Context c, int voice) {
        return Brain.persona(c, voice);
    }

    private void tryBrain() {
        brainOut.setText(R.string.lab_brain_waking);
        final int v = voice;
        final String question = Cast.say(this, R.array.brain_ready_question, v);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Brain b = Brain.get(LabActivity.this);
                    java.util.List<String[]> turns = new java.util.ArrayList<String[]>();
                    turns.add(new String[] {"user", question});
                    final Brain.Reply reply = b.ask(persona(LabActivity.this, v), turns, 80);
                    final double woke = b.wokeIn();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            brainOut.setText(getString(R.string.lab_brain_result, reply.text, woke, reply.seconds,
                                    reply.tokensPerSecond));
                            VoiceService.speak(LabActivity.this, reply.text, v);
                        }
                    });
                } catch (final Exception e) {
                    Diag.log(LabActivity.this, "brain: trial failed", e);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            brainOut.setText(getString(R.string.lab_brain_failed, String.valueOf(e.getMessage())));
                        }
                    });
                }
            }
        }, "brain-trial").start();
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

    /** How a slider's position reads and what it sets. */
    private interface Value {
        String show(int position);

        void set(int position);
    }

    /** A titled slider with its value on the right and the two ends named under it. */
    private View slider(String title, String left, String right, int max, int at, final Value value) {
        LinearLayout box = Ui.column(this);
        box.setPadding(dp(16), dp(12), dp(16), dp(10));
        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, title, 16, Palette.INK),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView shown = Ui.text(this, value.show(at), 14, Palette.ACCENT_TEXT);
        shown.setTypeface(Palette.bodyStrong(this));
        head.addView(shown);
        box.addView(head);
        SeekBar bar = Ui.slider(this, max, at);
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

    private void choose(final int v) {
        voice = v;
        for (int i = 0; i < Cast.COUNT; i++) {
            boolean on = Cast.at(i) == v;
            int size = dp(on ? 52 : 44);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(size, size);
            faces[i].setLayoutParams(p);
            faces[i].setPadding(on ? dp(3) : 0, on ? dp(3) : 0, on ? dp(3) : 0, on ? dp(3) : 0);
            faces[i].setBackground(on ? Ui.oval(Palette.BG, Palette.voice(Cast.at(i)), 2, this) : null);
            faces[i].setAlpha(on ? 1f : 0.7f);
            names[i].setTextColor(on ? Palette.INK : Palette.MUTED);
            names[i].setTypeface(on ? Palette.bodyStrong(this) : Palette.body(this));
        }
        ownTitle.setText(Cast.name(this, v));
        own.removeAllViews();
        own.addView(slider(getString(R.string.ws_timbre), getString(R.string.lab_warmer), getString(R.string.lab_brighter),
                Grim.TIMBRE_MAX - Grim.TIMBRE_MIN, Prefs.grimTimbre(this, v) - Grim.TIMBRE_MIN, new Value() {
                    @Override
                    public String show(int p) {
                        return signed(p + Grim.TIMBRE_MIN);
                    }

                    @Override
                    public void set(int p) {
                        Prefs.setGrim(LabActivity.this, v, p + Grim.TIMBRE_MIN, Prefs.grimPitch(LabActivity.this, v));
                    }
                }));
        own.addView(line());
        own.addView(slider(getString(R.string.ws_pitch), getString(R.string.lab_lower), getString(R.string.lab_higher),
                Grim.PITCH_MAX - Grim.PITCH_MIN, Prefs.grimPitch(this, v) - Grim.PITCH_MIN, new Value() {
                    @Override
                    public String show(int p) {
                        return signed(p + Grim.PITCH_MIN);
                    }

                    @Override
                    public void set(int p) {
                        Prefs.setGrim(LabActivity.this, v, Prefs.grimTimbre(LabActivity.this, v), p + Grim.PITCH_MIN);
                    }
                }));
        own.addView(line());
        own.addView(slider(getString(R.string.lab_air), getString(R.string.lab_none), getString(R.string.lab_much),
                Grim.AIR_MAX, Prefs.grimAir(this, v), new Value() {
                    @Override
                    public String show(int p) {
                        return String.valueOf(p);
                    }

                    @Override
                    public void set(int p) {
                        Prefs.setGrimAir(LabActivity.this, v, p);
                    }
                }));
        own.addView(line());
        own.addView(Kit.rowNav(this, getString(R.string.lab_sample), "", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                WorkshopActivity.openCabinet(LabActivity.this, v);
            }
        }));
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
