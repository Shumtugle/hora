package com.shumtugle.hora;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

/**
 * The day, said aloud from the launcher icon's menu: nothing is shown and nothing opens. The words are
 * put together off the main thread and handed to the voice while this still counts as a visible screen,
 * so the voice may start; then it goes. The sky is said only if a recent one is kept: no network here.
 */
public final class SummaryActivity extends Activity {
    /** If the words take longer than this, the touch is dropped rather than holding the screen. */
    private static final long LIMIT_MS = 2500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean done;

    private final Runnable giveUp = new Runnable() {
        @Override
        public void run() {
            if (!done) {
                done = true;
                Diag.mark(SummaryActivity.this, "shortcut: summary took too long, not said");
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Touches pass through to whatever is underneath while the words are being found.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        if (!Prefs.introSeen(this)) {
            // Nothing to say before the first start is through: open the app, which begins with it.
            startActivity(new Intent(this, DayActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        if (state != null) {
            // Recreated after the words were asked for once: do not say the day twice.
            finish();
            return;
        }
        final Context app = getApplicationContext();
        final int teller = Prefs.role(this, Cast.TALK);
        handler.postDelayed(giveUp, LIMIT_MS);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String said = null;
                try {
                    said = DaySummary.build(app);
                } catch (RuntimeException e) {
                    Diag.mark(app, "shortcut: summary failed, " + e.getClass().getSimpleName());
                }
                final String told = said;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (done) {
                            return;
                        }
                        done = true;
                        handler.removeCallbacks(giveUp);
                        if (told != null && !told.isEmpty()) {
                            try {
                                VoiceService.answerMarked(app, told, teller);
                            } catch (IllegalStateException e) {
                                Diag.mark(app, "shortcut: summary not said, " + e.getClass().getSimpleName());
                            }
                        }
                        finish();
                    }
                });
            }
        }, "shortcut-summary").start();
    }

    @Override
    protected void onDestroy() {
        // A gone instance stays silent: a second touch, or leaving early, must not say the day twice.
        done = true;
        handler.removeCallbacks(giveUp);
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        // Leaves without a closing animation: it was never seen arriving either.
        overridePendingTransition(0, 0);
    }
}
