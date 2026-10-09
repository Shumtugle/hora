package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Where everything in Hora comes from: the app's own licence and source, the
 * voice and its samples, the speech and model engines, the dictionaries, the
 * data it fetches, the fonts and pictures. Each row opens the full notice as
 * it ships in the app. With EXTRA_FILE the room shows that one notice.
 */
public final class AboutActivity extends Activity {
    static final String EXTRA_FILE = "file";
    static final String EXTRA_TITLE = "title";

    /** Section, then rows of title, licence in a few words, notice file. */
    private static final int[][] ROWS = {
        {R.string.about_app, R.string.about_hora, R.string.about_hora_license},
        {R.string.about_voice, R.string.about_voice_model, R.string.about_lic_cc_by},
        {R.string.about_voice, R.string.about_samples_123, R.string.about_lic_openrail},
        {R.string.about_voice, R.string.about_samples_45, R.string.about_lic_cc0},
        {R.string.about_voice, R.string.about_english, R.string.about_lic_cc_by},
        {R.string.about_voice, R.string.about_speech_engine, R.string.about_lic_apache_mit},
        {R.string.about_text, R.string.about_stress, R.string.about_lic_cc_by_sa_mit},
        {R.string.about_text, R.string.about_stress_extended, R.string.about_lic_apache},
        {R.string.about_text, R.string.about_stress_net, R.string.about_lic_mit},
        {R.string.about_text, R.string.about_ruaccent, R.string.about_lic_mit},
        {R.string.about_text, R.string.about_yo, R.string.about_lic_mit},
        {R.string.about_ai, R.string.about_gemma, R.string.about_lic_apache},
        {R.string.about_ai, R.string.about_model_engine, R.string.about_lic_mit},
        {R.string.about_data, R.string.about_weather, R.string.about_lic_cc_by},
        {R.string.about_data, R.string.about_wikipedia, R.string.about_lic_cc_by_sa},
        {R.string.about_look, R.string.about_fonts, R.string.about_lic_ofl},
        {R.string.about_look, R.string.about_portraits, R.string.portraits_note},
        {R.string.about_texts, R.string.about_apache_text, R.string.about_lic_apache},
    };
    private static final String[] FILES = {
        "hora.txt", "voice-model.txt", "voices-dialogs.txt", "voices-mozilla.txt", "english-voice.txt",
        "speech-engine.txt", "stress-dictionary.txt", "stress-extended.txt", "stress-network.txt",
        "ruaccent-dictionaries.txt", "yo-dictionary.txt", "ai-models.txt", "model-engine.txt",
        "weather-data.txt", "wikipedia.txt", "fonts-ofl.txt", "portraits.txt", "apache-2.0.txt",
    };

    private int built;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        String file = getIntent().getStringExtra(EXTRA_FILE);
        if (file != null) {
            notice(file, getIntent().getStringExtra(EXTRA_TITLE));
            return;
        }
        LinearLayout list = Kit.room(this, getString(R.string.about_title));
        list.addView(Kit.lead(this, getString(R.string.about_lead)));
        int section = 0;
        LinearLayout plate = null;
        for (int i = 0; i < ROWS.length; i++) {
            final int[] row = ROWS[i];
            if (row[0] != section) {
                section = row[0];
                list.addView(Kit.section(this, getString(section)));
                plate = Kit.plate(this);
                list.addView(plate, Kit.wide());
            }
            final String name = FILES[i];
            plate.addView(Kit.rowNav(this, getString(row[1]), getString(row[2]), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(AboutActivity.this, AboutActivity.class)
                            .putExtra(EXTRA_FILE, name).putExtra(EXTRA_TITLE, getString(row[1])));
                }
            }));
            if (i == 0) {
                plate.addView(Kit.rowNav(this, getString(R.string.about_source), getString(R.string.about_source_where),
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                openSource();
                            }
                        }));
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.stale(this, built);
    }

    /** One notice, whole, as it ships. */
    private void notice(String file, String title) {
        LinearLayout list = Kit.room(this, title == null ? getString(R.string.about_title) : title);
        TextView t = Ui.text(this, read("licenses/" + file), 14, Palette.MUTED);
        t.setLineSpacing(0, 1.25f);
        t.setPadding(0, Ui.dp(this, 16), 0, 0);
        list.addView(t, Kit.wide());
    }

    private String read(String path) {
        StringBuilder out = new StringBuilder();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(getAssets().open(path), "UTF-8"))) {
            String line;
            while ((line = in.readLine()) != null) {
                out.append(line).append('\n');
            }
        } catch (IOException e) {
            Diag.log(this, "about: notice missing, " + path, e);
        }
        return out.toString();
    }

    private void openSource() {
        String url = read("talk/source-url.txt").trim();
        if (url.isEmpty()) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            Diag.log(this, "about: no browser", e);
        }
    }
}
