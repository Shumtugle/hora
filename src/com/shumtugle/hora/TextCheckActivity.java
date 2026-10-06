package com.shumtugle.hora;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Shows what the voice will actually receive for a piece of text, and runs a
 * built-in self-test of the text rules on this device, with this device's data.
 */
public final class TextCheckActivity extends Activity {
    /** The look this screen was built with. */
    private int built;
    private EditText input;
    private ScrollView resultBox;
    private LinearLayout progressPlate;
    private TextView progressWords;
    private Kit.Bar progressBar;
    private TextView output;
    private TextPrep prep;
    private Respell respell;
    private ModelStress modelStress;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);

        // A room whose parts share the height: the text and the result scroll inside
        // themselves, so the actions stay in reach however long the text is.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.BG);
        root.setPadding(dp(Kit.PAD), dp(32), dp(Kit.PAD), dp(24));
        root.addView(Ui.header(this, getString(R.string.text_check), false),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        root.addView(Kit.lead(this, getString(R.string.text_check_lead)));

        input = new EditText(this);
        Ui.field(input);
        input.setTextColor(Palette.INK);
        input.setHint(R.string.text_check_hint);
        input.setHintTextColor(Palette.HINT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setBackground(Ui.round(Palette.SURFACE, Kit.PLATE_RADIUS, this));
        input.setTypeface(Palette.body(this));
        input.setPadding(dp(18), dp(16), dp(18), dp(16));
        input.setVerticalScrollBarEnabled(true);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        ip.topMargin = dp(16);
        root.addView(input, ip);

        output = new TextView(this);
        output.setTextColor(Palette.INK);
        output.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        output.setTypeface(Palette.body(this));
        output.setLineSpacing(0, 1.2f);
        output.setTextIsSelectable(true);
        output.setPadding(dp(18), dp(16), dp(18), dp(16));
        resultBox = new ScrollView(this);
        resultBox.setBackground(Ui.round(Palette.SURFACE, Kit.PLATE_RADIUS, this));
        resultBox.addView(output);
        resultBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        op.topMargin = dp(12);
        root.addView(resultBox, op);

        LinearLayout tools = new LinearLayout(this);
        tools.addView(Kit.secondary(this, getString(R.string.text_check_show), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run(false);
            }
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sp.leftMargin = dp(12);
        tools.addView(Kit.secondary(this, getString(R.string.text_check_selftest), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run(true);
            }
        }), sp);
        progressWords = Ui.text(this, "", 15, Palette.INK);
        progressBar = new Kit.Bar(this);
        progressPlate = Kit.progress(this, progressWords, progressBar);
        progressPlate.setVisibility(View.GONE);
        root.addView(progressPlate, Kit.below(this, 12));
        root.addView(tools, Kit.below(this, 16));
        TextView record = Kit.primary(this, getString(R.string.text_check_record), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = input.getText().toString().trim();
                if (!text.isEmpty()) {
                    VoiceService.record(TextCheckActivity.this, text);
                    progressWords.setText(R.string.text_check_starting);
                    progressBar.set(0f);
                    progressPlate.setVisibility(View.VISIBLE);
                }
            }
        });
        root.addView(record, Kit.below(this, 12));
        setContentView(root);
    }

    /** Follows a recording: paragraphs done, then where the file went. */
    private final android.content.BroadcastReceiver recording = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, android.content.Intent i) {
            progressPlate.setVisibility(View.VISIBLE);
            if (i.hasExtra(VoiceService.EXTRA_FILE)) {
                String file = i.getStringExtra(VoiceService.EXTRA_FILE);
                String error = i.getStringExtra(VoiceService.EXTRA_ERROR);
                float seconds = i.getFloatExtra(VoiceService.EXTRA_SECONDS, 0f);
                progressBar.set(error == null ? 1f : 0f);
                progressWords.setText(error != null ? getString(R.string.text_check_failed, error)
                        : file == null || file.isEmpty() ? getString(R.string.text_check_not_saved)
                        : getString(R.string.text_check_saved, file, Math.round(seconds / 60f * 10f) / 10f));
                return;
            }
            int done = i.getIntExtra(VoiceService.EXTRA_DONE, 0);
            int total = Math.max(1, i.getIntExtra(VoiceService.EXTRA_TOTAL, 1));
            progressBar.set(done / (float) total);
            progressWords.setText(getString(R.string.text_check_progress, done, total, done * 100 / total));
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        Ui.stale(this, built);
        android.content.IntentFilter f = new android.content.IntentFilter(VoiceService.ACTION_RECORD_STATE);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(recording, f, 4 /* RECEIVER_NOT_EXPORTED */);
        } else {
            registerReceiver(recording, f);
        }
    }

    @Override
    protected void onPause() {
        unregisterReceiver(recording);
        super.onPause();
    }

    /** Shows a result under the text; the two then share the height. */
    private void show(CharSequence s) {
        output.setText(s);
        resultBox.setVisibility(View.VISIBLE);
        resultBox.scrollTo(0, 0);
    }

    private void run(final boolean selfTest) {
        final String text = input.getText().toString();
        show(getString(R.string.text_check_working));
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    ensureTools();
                    result = selfTest ? selfTest() : spoken(text);
                } catch (Throwable t) {
                    result = String.valueOf(t);
                    Diag.log(TextCheckActivity.this, "check: failed", t);
                }
                final String shown = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        show(shown);
                    }
                });
            }
        }).start();
    }

    private synchronized void ensureTools() {
        if (prep == null) {
            Resources speech = SpeechLanguage.resources(this);
            prep = new TextPrep(speech, null);
            respell = new Respell(speech);
            modelStress = ModelStress.fromAssets(this);
        }
    }

    /** The whole path a reader's text takes before it reaches the voice. */
    private String spoken(String text) {
        Lexicon lex = Lexicon.get(this);
        String t = Homographs.get(this).apply(prep.apply(lex.applyRules(TextPrep.clean(text))), lex);
        t = Lexicon.bareYo(modelStress.apply(lex.applyWords(t)));
        return Prefs.respell(this) ? respell.apply(t) : t;
    }

    /** Built-in cases: written form and the words it must become. */
    private String selfTest() {
        String[] cases = SpeechLanguage.resources(this).getStringArray(R.array.selftest);
        StringBuilder sb = new StringBuilder();
        int passed = 0;
        for (String c : cases) {
            int arrow = c.indexOf("=>");
            if (arrow < 0) {
                continue;
            }
            String in = c.substring(0, arrow);
            String expected = c.substring(arrow + 2);
            String actual = prep.apply(TextPrep.clean(in)).trim();
            boolean ok = actual.equals(expected);
            if (ok) {
                passed++;
            }
            sb.append(ok ? "\u2713 " : "\u2717 ").append(actual);
            if (!ok) {
                sb.append("\n    \u2192 ").append(expected);
            }
            sb.append("\n\n");
        }
        String head = getString(R.string.text_check_summary, passed, cases.length);
        Diag.mark(this, "check: self-test " + passed + "/" + cases.length);
        return head + "\n\n" + sb;
    }

        private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

}
