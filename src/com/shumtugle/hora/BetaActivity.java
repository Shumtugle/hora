package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

/**
 * Trial voices, in the developer room. No faces, names or descriptions: what the
 * samples folder holds, where each trial voice sounds, and the demo read by it.
 * The plain demo, read by the narrator, starts the same way from the settings.
 */
public final class BetaActivity extends Activity {
    private static final int[] PLACES = {Cast.NARRATOR, Cast.HERALD, Cast.TALK};
    private static final int[] PLACE_NAMES = {R.string.beta_for_books, R.string.beta_for_herald, R.string.beta_for_talk};
    private static final int[] CHOICES = {0, Cast.BETA_F, Cast.BETA_M};

    private int built;
    private LinearLayout body;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        LinearLayout list = Kit.room(this, getString(R.string.beta_title));
        body = Ui.column(this);
        list.addView(body, Kit.wide());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int found = Demo.take(app);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing()) {
                            show(found);
                        }
                    }
                });
            }
        }, "beta-look").start();
    }

    private void show(int found) {
        body.removeAllViews();
        body.addView(Kit.section(this, getString(R.string.beta_samples)));
        LinearLayout state = Kit.plate(this);
        int words = found == Demo.SAMPLES_READY ? R.string.beta_samples_ready
                : found == Demo.SAMPLES_WRONG ? R.string.beta_samples_wrong
                : found == Demo.SAMPLES_HALF ? R.string.beta_samples_half : R.string.beta_samples_none;
        state.addView(Kit.status(this, getString(words), found == Demo.SAMPLES_READY ? Kit.Mood.FINE
                : found == Demo.SAMPLES_WRONG ? Kit.Mood.TROUBLE : Kit.Mood.UNKNOWN));
        body.addView(state, Kit.wide());
        if (found == Demo.SAMPLES_READY) {
            body.addView(Kit.section(this, getString(R.string.beta_where)));
            LinearLayout where = Kit.plate(this);
            String[] labels = {getString(R.string.beta_own), getString(R.string.beta_female_short),
                getString(R.string.beta_male_short)};
            for (int i = 0; i < PLACES.length; i++) {
                final int role = PLACES[i];
                LinearLayout row = Ui.column(this);
                row.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
                row.addView(Ui.text(this, getString(PLACE_NAMES[i]), 16, Palette.INK));
                int now = 0;
                for (int k = 0; k < CHOICES.length; k++) {
                    if (CHOICES[k] == Beta.chosen(this, role)) {
                        now = k;
                    }
                }
                LinearLayout.LayoutParams sp = Ui.lp(Ui.MATCH, Ui.WRAP);
                sp.topMargin = Ui.dp(this, 10);
                row.addView(Kit.segments(this, labels, now, new Kit.Chosen() {
                    @Override
                    public void chose(int which) {
                        Beta.choose(BetaActivity.this, role, CHOICES[which]);
                    }
                }), sp);
                where.addView(row);
            }
            TextView note = Ui.text(this, getString(R.string.beta_where_note), 12, Palette.HINT);
            note.setLineSpacing(0, 1.3f);
            note.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 14));
            where.addView(note);
            body.addView(where, Kit.wide());

            body.addView(Kit.section(this, getString(R.string.demo_title)));
            LinearLayout demo = Kit.plate(this);
            demo.addView(Kit.rowNav(this, getString(R.string.beta_female), "▷", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    read(BetaActivity.this, Cast.BETA_F);
                }
            }));
            demo.addView(Kit.rowNav(this, getString(R.string.beta_male), "▷", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    read(BetaActivity.this, Cast.BETA_M);
                }
            }));
            body.addView(demo, Kit.wide());

            // The demo as an audiobook, made overnight on the charger like any other.
            body.addView(Kit.section(this, getString(R.string.beta_audiobook)));
            LinearLayout night = Kit.plate(this);
            night.addView(Kit.rowNav(this, getString(R.string.beta_female), "\u203a", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    audiobook(Cast.BETA_F);
                }
            }));
            night.addView(Kit.rowNav(this, getString(R.string.beta_male), "\u203a", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    audiobook(Cast.BETA_M);
                }
            }));
            body.addView(night, Kit.wide());
        }
        body.addView(Kit.link(this, getString(R.string.dev_close), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Beta.setOpen(BetaActivity.this, false);
                finish();
            }
        }), Kit.below(this, 16));
    }

    /** Offers the demo book to the audiobook queue, read by a trial voice, in a folder of its own. */
    private void audiobook(final int voice) {
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String title;
                try {
                    Demo.book(app);
                    Book b = Export.load(app, Demo.uri(app), "book.txt");
                    title = (b.title == null || b.title.isEmpty() ? getString(R.string.demo_title) : b.title)
                            + " \u00b7 " + Cast.name(app, voice);
                } catch (Exception e) {
                    Diag.log(app, "demo: the book could not be prepared", e);
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        startActivity(new Intent(BetaActivity.this, ExportActivity.class)
                                .putExtra(ExportActivity.EXTRA_URI, Demo.uri(app))
                                .putExtra(ExportActivity.EXTRA_NAME, "book.txt")
                                .putExtra(ExportActivity.EXTRA_TITLE, title)
                                .putExtra(ExportActivity.EXTRA_READER, voice));
                    }
                });
            }
        }, "demo-audiobook").start();
    }

    /** Reads the demo with a trial voice, or with the narrator when the voice is 0, and shows the book. */
    static void read(Activity a, final int voice) {
        if (!Voice.ready(a)) {
            Toast.makeText(a, R.string.voice_pack_needed, Toast.LENGTH_LONG).show();
            return;
        }
        final android.content.Context app = a.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Demo.book(app);
                } catch (IOException e) {
                    Diag.log(app, "demo: the book could not be prepared", e);
                    return;
                }
                Demo.setVoice(app, voice);
                Diag.mark(app, voice == 0 ? "demo: read by the narrator"
                        : "demo: read by trial voice " + (voice - Cast.COUNT));
                BookActivity.open(app, Demo.uri(app), "book.txt", true);
            }
        }, "demo").start();
        a.startActivity(new Intent(a, BookActivity.class));
    }
}
