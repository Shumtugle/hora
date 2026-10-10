package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.IOException;

/**
 * Two trial voices, nothing more: no faces, names or descriptions. A touch reads
 * the demo book with that voice, from where the demo was left, on the book screen.
 */
public final class BetaActivity extends Activity {
    private int built;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        LinearLayout list = Kit.room(this, getString(R.string.beta_title));
        LinearLayout plate = Kit.plate(this);
        plate.addView(Kit.rowNav(this, getString(R.string.beta_female), "▷", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                read(Cast.BETA_F);
            }
        }));
        plate.addView(Kit.rowNav(this, getString(R.string.beta_male), "▷", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                read(Cast.BETA_M);
            }
        }));
        list.addView(plate, Kit.wide());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.stale(this, built);
    }

    private void read(final int voice) {
        if (!Voice.ready(this)) {
            Toast.makeText(this, R.string.voice_pack_needed, Toast.LENGTH_LONG).show();
            return;
        }
        final android.content.Context app = getApplicationContext();
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
                Diag.mark(app, "demo: read by trial voice " + (voice - Cast.COUNT));
                BookActivity.open(app, Demo.uri(app), "book.txt", true);
            }
        }, "demo").start();
        startActivity(new Intent(this, BookActivity.class));
    }
}
