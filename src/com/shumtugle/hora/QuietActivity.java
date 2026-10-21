package com.shumtugle.hora;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import java.util.Locale;

/**
 * Hora's quiet hours, one set for everything that speaks by itself: the notifications, the morning
 * line and the route. The phone's own do not disturb is a separate thing and never stops the route.
 */
public final class QuietActivity extends Activity {
    private int built;
    private View fromRow;
    private View toRow;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        built = Ui.prepare(this);
        LinearLayout list = Kit.room(this, getString(R.string.quiet_title));
        list.addView(Kit.lead(this, getString(R.string.quiet_lead)));
        LinearLayout quiet = Kit.plate(this);
        quiet.addView(Kit.rowToggle(this, getString(R.string.herald_quiet_switch), null, Prefs.quiet(this),
                new Kit.Flip() {
                    @Override
                    public void flipped(boolean on) {
                        Prefs.setQuiet(QuietActivity.this, on);
                        showQuiet();
                    }
                }));
        fromRow = Kit.rowNav(this, getString(R.string.herald_quiet_from), "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Kit.timeSheet(QuietActivity.this, getString(R.string.herald_quiet_from_title),
                        Prefs.quietFrom(QuietActivity.this), new Kit.TimeSet() {
                            @Override
                            public void set(int minutes) {
                                Prefs.setQuietFrom(QuietActivity.this, minutes);
                                showQuiet();
                            }
                        });
            }
        });
        toRow = Kit.rowNav(this, getString(R.string.herald_quiet_to), "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Kit.timeSheet(QuietActivity.this, getString(R.string.herald_quiet_to_title),
                        Prefs.quietTo(QuietActivity.this), new Kit.TimeSet() {
                            @Override
                            public void set(int minutes) {
                                Prefs.setQuietTo(QuietActivity.this, minutes);
                                showQuiet();
                            }
                        });
            }
        });
        quiet.addView(fromRow);
        quiet.addView(toRow);
        list.addView(quiet, Kit.wide());
        showQuiet();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        showQuiet();
    }

    private void showQuiet() {
        Kit.setValue(fromRow, clock(Prefs.quietFrom(this)));
        Kit.setValue(toRow, clock(Prefs.quietTo(this)));
        boolean on = Prefs.quiet(this);
        for (View v : new View[] {fromRow, toRow}) {
            v.setAlpha(on ? 1f : 0.4f);
            v.setEnabled(on);
        }
    }

    static String clock(int minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** What the settings row shows: the span, or that there are none. */
    static String value(Context c) {
        return Prefs.quiet(c) ? clock(Prefs.quietFrom(c)) + "\u2013" + clock(Prefs.quietTo(c))
                : c.getString(R.string.quiet_value_off);
    }
}
