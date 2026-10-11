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
 * Trial voices, in the developer room: while it is open they stand with the cast
 * and take roles; here the demo is read by them, or made into an audiobook.
 * The plain demo, read by the narrator, starts the same way from the settings.
 */
public final class BetaActivity extends Activity {

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
        show();
    }

    /** Names of the trial voice rows, in voice order from the first trial voice. */
    private static final int[] LABELS = {R.string.beta_female, R.string.beta_male,
        R.string.beta_male2, R.string.beta_neutral};

    private void show() {
        body.removeAllViews();
        // The trial voices stand in the row of faces and take roles like the cast while this room is open.
        TextView note = Ui.text(this, getString(R.string.beta_in_cast), 13, Palette.MUTED);
        note.setLineSpacing(0, 1.3f);
        note.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), 0);
        body.addView(note);

        body.addView(Kit.section(this, getString(R.string.demo_title)));
        LinearLayout demo = Kit.plate(this);
        for (int i = 0; i < LABELS.length; i++) {
            final int voice = Cast.BETA_F + i;
            demo.addView(Kit.rowNav(this, getString(LABELS[i]), "\u25b7", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    read(BetaActivity.this, voice);
                }
            }));
        }
        body.addView(demo, Kit.wide());

        // The demo as an audiobook, made overnight on the charger like any other.
        body.addView(Kit.section(this, getString(R.string.beta_audiobook)));
        LinearLayout night = Kit.plate(this);
        for (int i = 0; i < LABELS.length; i++) {
            final int voice = Cast.BETA_F + i;
            night.addView(Kit.rowNav(this, getString(LABELS[i]), "\u203a", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    audiobook(voice);
                }
            }));
        }
        body.addView(night, Kit.wide());

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
