package com.shumtugle.hora;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;

import java.text.DateFormat;
import java.util.Date;

/**
 * Bringing Hora back from another phone: says what the archive holds and
 * restores it on one touch. The books are found by name in Hora's folder, so
 * the folder is asked for first when it is not known yet. After restoring,
 * Hora starts afresh so every part of it reads the settings anew.
 */
public final class RestoreActivity extends Activity {
    static final String EXTRA_URI = "uri";
    static final String EXTRA_CHECK = "check";

    /** Opens the after-restore check while something it lists is still waiting. */
    static void checkIfPending(Activity a) {
        if (Transfer.pendingCheck(a) != null) {
            a.startActivity(new Intent(a, RestoreActivity.class).putExtra(EXTRA_CHECK, true));
        }
    }

    private int built;
    private LinearLayout box;
    private Uri source;
    private Transfer.Summary summary;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Opens the offer when this install is new and the folder holds an archive from before. */
    static void offerIfFresh(Activity a) {
        if (Transfer.fresh(a) && Transfer.inFolder(a) != null) {
            a.startActivity(new Intent(a, RestoreActivity.class));
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        String u = getIntent().getStringExtra(EXTRA_URI);
        source = u != null ? Uri.parse(u) : Transfer.inFolder(this);
        LinearLayout list = Kit.room(this, getString(R.string.transfer_title));
        list.addView(Kit.lead(this, getString(R.string.transfer_lead)));
        box = Ui.column(this);
        list.addView(box, Kit.wide());
        if (!getIntent().getBooleanExtra(EXTRA_CHECK, false)) {
            load();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        if (getIntent().getBooleanExtra(EXTRA_CHECK, false)) {
            check();
        }
    }

    /**
     * After a restore, the rights and parts that do not travel: each still missing on this
     * phone gets a row that leads where it is given; rows disappear as they are done.
     */
    private void check() {
        box.removeAllViews();
        org.json.JSONObject had = Transfer.pendingCheck(this);
        if (had == null) {
            finish();
            return;
        }
        LinearLayout plate = Kit.plate(this);
        plate.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        int rows = 0;
        if (!Prefs.heraldApps(this).isEmpty() && !Herald.granted(this)) {
            plate.addView(Kit.rowEntry(this, R.drawable.ic_bell, getString(R.string.check_herald),
                    getString(R.string.check_herald_sub), go(new Intent(this, HeraldActivity.class))));
            rows++;
        }
        if (had.optBoolean("engine") && !Transfer.defaultEngine(this)) {
            plate.addView(Kit.rowEntry(this, R.drawable.ic_talk, getString(R.string.check_engine),
                    getString(R.string.check_engine_sub), go(new Intent("com.android.settings.TTS_SETTINGS"))));
            rows++;
        }
        if (had.optBoolean("model") && !Brain.get(this).hasModel()) {
            plate.addView(Kit.rowEntry(this, R.drawable.ic_sliders, getString(R.string.brain_title),
                    getString(R.string.check_model_sub), go(new Intent(this, BrainActivity.class))));
            rows++;
        }
        if (had.optBoolean("english") && !LanguagePack.installed(this, LanguagePack.ENGLISH)) {
            plate.addView(Kit.rowEntry(this, R.drawable.ic_book, getString(R.string.lang_english),
                    getString(R.string.check_english_sub), go(new Intent(this, LanguagesActivity.class))));
            rows++;
        }
        if (rows == 0) {
            Transfer.checkSeen(this);
            show(getString(R.string.check_all_done), null);
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            }, 1500);
            return;
        }
        android.widget.TextView t = Ui.text(this, getString(R.string.check_lead), 15, Palette.INK);
        t.setLineSpacing(0, 1.3f);
        t.setPadding(0, 0, 0, Ui.dp(this, 12));
        box.addView(t);
        box.addView(plate, Kit.wide());
        box.addView(Kit.link(this, getString(R.string.check_later), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Transfer.checkSeen(RestoreActivity.this);
                finish();
            }
        }), Kit.below(this, 12));
    }

    @Override
    public void onBackPressed() {
        // Leaving the check is the same as "later": it does not come back on its own.
        if (getIntent().getBooleanExtra(EXTRA_CHECK, false)) {
            Transfer.checkSeen(this);
        }
        super.onBackPressed();
    }

    private View.OnClickListener go(final Intent where) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(where);
                } catch (android.content.ActivityNotFoundException e) {
                    Diag.log(RestoreActivity.this, "transfer: that setting cannot be opened here", e);
                }
            }
        };
    }

    private void load() {
        box.removeAllViews();
        if (source == null) {
            show(getString(R.string.transfer_none), null);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                Transfer.Summary s = null;
                try {
                    s = Transfer.read(RestoreActivity.this, source);
                } catch (Exception e) {
                    Diag.log(RestoreActivity.this, "transfer: archive not read", e);
                }
                final Transfer.Summary got = s;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        summary = got;
                        if (got == null) {
                            show(getString(R.string.transfer_unreadable), null);
                            return;
                        }
                        String when = got.made <= 0 ? "" : DateFormat.getDateTimeInstance(DateFormat.LONG,
                                DateFormat.SHORT).format(new Date(got.made));
                        show(getString(R.string.transfer_found, when,
                                getResources().getQuantityString(R.plurals.transfer_books, got.books, got.books)),
                                summary);
                    }
                });
            }
        }, "transfer-read").start();
    }

    private void show(String what, final Transfer.Summary s) {
        box.removeAllViews();
        LinearLayout plate = Kit.plate(this);
        android.widget.TextView t = Ui.text(this, what, 15, Palette.INK);
        t.setLineSpacing(0, 1.3f);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        plate.addView(t);
        box.addView(plate, Kit.wide());
        if (s == null) {
            return;
        }
        boolean folder = HoraFolder.tree(this) != null;
        box.addView(Kit.primary(this, getString(folder ? R.string.transfer_restore : R.string.transfer_folder_first),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (HoraFolder.tree(RestoreActivity.this) == null) {
                            HoraFolder.ask(RestoreActivity.this);
                        } else {
                            restore();
                        }
                    }
                }), Kit.below(this, 16));
        box.addView(Kit.link(this, getString(R.string.transfer_later), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }), Kit.below(this, 8));
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == HoraFolder.PICK && result == RESULT_OK && HoraFolder.accept(this, data)) {
            restore();
        }
    }

    private void restore() {
        box.removeAllViews();
        show(getString(R.string.transfer_working), null);
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String done;
                try {
                    Transfer.restore(app, summary);
                    done = getString(R.string.transfer_done);
                } catch (Exception e) {
                    Diag.log(app, "transfer: not restored", e);
                    done = getString(R.string.transfer_failed);
                }
                final String said = done;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        show(said, null);
                        main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                restart();
                            }
                        }, 1500);
                    }
                });
            }
        }, "transfer-restore").start();
    }

    /** Starts Hora afresh: every process goes, so each reads the restored settings anew. */
    private void restart() {
        Intent again = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, again,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
        ((AlarmManager) getSystemService(ALARM_SERVICE)).set(AlarmManager.RTC, System.currentTimeMillis() + 400, pi);
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        int me = android.os.Process.myPid();
        for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
            if (p.pid != me) {
                android.os.Process.killProcess(p.pid);
            }
        }
        android.os.Process.killProcess(me);
    }
}
