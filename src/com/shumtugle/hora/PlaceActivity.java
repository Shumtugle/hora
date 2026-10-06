package com.shumtugle.hora;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Finding the place whose sky to tell: a name typed, the places of that name
 * listed with their region and country, one chosen. Namesakes stand apart,
 * so a town abroad is not mistaken for a capital.
 */
public final class PlaceActivity extends Activity {
    private int built;
    private EditText query;
    private LinearLayout results;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Palette.BG);
        root.setPadding(dp(20), dp(20), dp(20), dp(16));
        root.addView(Ui.header(this, getString(R.string.place_title), false), Ui.lp(Ui.MATCH, dp(48)));

        LinearLayout line = Ui.row(this);
        line.setBackground(Ui.round(Palette.SURFACE, 26, this));
        line.setPadding(dp(18), dp(6), dp(6), dp(6));
        query = new EditText(this);
        Ui.field(query);
        query.setHint(R.string.place_hint);
        query.setHintTextColor(Palette.HINT);
        query.setTextColor(Palette.INK);
        query.setTextSize(16);
        query.setTypeface(Palette.body(this));
        query.setBackground(null);
        query.setSingleLine(true);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int action, KeyEvent e) {
                search();
                return true;
            }
        });
        line.addView(query, new LinearLayout.LayoutParams(0, dp(44), 1f));
        ImageView go = Ui.iconButton(this, R.drawable.ic_next, Palette.ACCENT, 44, getString(R.string.place_find));
        go.setImageTintList(android.content.res.ColorStateList.valueOf(Palette.ON_ACCENT));
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                search();
            }
        });
        line.addView(go);
        LinearLayout.LayoutParams lp = Ui.lp(Ui.MATCH, Ui.WRAP);
        lp.topMargin = dp(16);
        root.addView(line, lp);

        status = Ui.text(this, "", 13, Palette.MUTED);
        status.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams sp = Ui.lp(Ui.MATCH, Ui.WRAP);
        sp.topMargin = dp(12);
        root.addView(status, sp);

        ScrollView scroll = new ScrollView(this);
        results = Ui.column(this);
        scroll.addView(results);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f);
        rp.topMargin = dp(8);
        root.addView(scroll, rp);
        setContentView(root);
        query.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.stale(this, built);
    }

    private void search() {
        final String name = query.getText().toString().trim();
        if (name.isEmpty()) {
            return;
        }
        status.setText(R.string.place_searching);
        results.removeAllViews();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Weather.Place> found = null;
                try {
                    found = Weather.search(PlaceActivity.this, name);
                } catch (Exception e) {
                    Diag.log(PlaceActivity.this, "place: search failed", e);
                }
                final List<Weather.Place> shown = found;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        show(shown);
                    }
                });
            }
        }, "place-search").start();
    }

    private void show(List<Weather.Place> found) {
        results.removeAllViews();
        if (found == null) {
            status.setText(Weather.line(this, "fail"));
            return;
        }
        if (found.isEmpty()) {
            status.setText(Weather.line(this, "nowhere"));
            return;
        }
        status.setText("");
        for (final Weather.Place p : found) {
            LinearLayout row = Ui.column(this);
            row.setBackground(Ui.pressable(Ui.round(Palette.SURFACE, 18, this)));
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setClickable(true);
            row.addView(Ui.title(this, p.name, 20));
            if (!p.region.isEmpty()) {
                TextView r = Ui.text(this, p.region, 13, Palette.MUTED);
                LinearLayout.LayoutParams rp = Ui.lp(Ui.MATCH, Ui.WRAP);
                rp.topMargin = dp(4);
                row.addView(r, rp);
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Weather.setPlace(PlaceActivity.this, p.name, p.lat, p.lon);
                    setResult(RESULT_OK);
                    finish();
                }
            });
            LinearLayout.LayoutParams lp = Ui.lp(Ui.MATCH, Ui.WRAP);
            lp.topMargin = dp(8);
            results.addView(row, lp);
        }
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
