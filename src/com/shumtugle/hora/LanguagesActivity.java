package com.shumtugle.hora;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;

/**
 * Languages the voices read in. The built-in one is always there; another
 * is fetched separately, by Wi-Fi unless asked otherwise, or taken from a
 * file. The room only says what is there and what can be done next.
 */
public final class LanguagesActivity extends Activity {
    private static final int PICK = 71;
    private static final String EN = LanguagePack.ENGLISH;

    private int built;
    private LinearLayout english;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Set while something slow runs (reading the list, unpacking); the room shows it instead of the state. */
    private int busy;
    /** The last trouble in words, shown until the next action. */
    private String trouble;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            show();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        LinearLayout list = Kit.room(this, getString(R.string.lang_title));
        list.addView(Kit.lead(this, getString(R.string.lang_lead)));
        list.addView(Kit.section(this, getString(R.string.lang_russian)));
        LinearLayout ru = Kit.plate(this);
        ru.addView(Kit.status(this, getString(R.string.lang_builtin), Kit.Mood.FINE));
        list.addView(ru, Kit.wide());
        list.addView(Kit.section(this, getString(R.string.lang_english)));
        english = Ui.column(this);
        list.addView(english, Kit.wide());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        // A download that ended while the room was closed is finished here too.
        settle();
        show();
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(tick);
        super.onPause();
    }

    private void settle() {
        Fetch.Progress p = Fetch.progress(this, LanguagePack.key(EN));
        if (p != null && (p.status == DownloadManager.STATUS_SUCCESSFUL || p.status == DownloadManager.STATUS_FAILED)) {
            work(R.string.lang_installing, new Job() {
                @Override
                public String run() {
                    return LanguagePack.finish(LanguagesActivity.this, EN) == Fetch.FAILED
                            ? getString(R.string.lang_failed) : null;
                }
            });
        }
    }

    private void show() {
        main.removeCallbacks(tick);
        english.removeAllViews();
        LinearLayout plate = Kit.plate(this);
        english.addView(plate, Kit.wide());
        if (busy != 0) {
            plate.addView(Kit.status(this, getString(busy), Kit.Mood.UNKNOWN));
            return;
        }
        Fetch.Progress p = Fetch.progress(this, LanguagePack.key(EN));
        if (p != null && p.status != DownloadManager.STATUS_SUCCESSFUL && p.status != DownloadManager.STATUS_FAILED) {
            plate.addView(Kit.status(this, downloading(p), Kit.Mood.UNKNOWN));
            english.addView(Kit.link(this, getString(R.string.lang_cancel), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Fetch.cancel(LanguagesActivity.this, LanguagePack.key(EN));
                    show();
                }
            }), Kit.below(this, 8));
            main.postDelayed(tick, 1000);
            return;
        }
        if (p != null) {
            // Done or failed, but not settled yet: the next tick settles it.
            settle();
            return;
        }
        if (LanguagePack.installed(this, EN)) {
            plate.addView(Kit.status(this, getString(R.string.lang_installed,
                    Math.round(LanguagePack.size(this, EN) / 1e6)), Kit.Mood.FINE));
            TextView note = Ui.text(this, getString(R.string.lang_not_yet), 14, Palette.MUTED);
            note.setPadding(Ui.dp(this, Kit.PAD), 0, Ui.dp(this, Kit.PAD), Ui.dp(this, 16));
            plate.addView(note);
            english.addView(Kit.link(this, getString(R.string.lang_remove), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    LanguagePack.remove(LanguagesActivity.this, EN);
                    show();
                }
            }), Kit.below(this, 8));
            return;
        }
        plate.addView(Kit.status(this, trouble != null ? trouble : getString(R.string.lang_absent),
                trouble != null ? Kit.Mood.TROUBLE : Kit.Mood.UNKNOWN));
        english.addView(Kit.primary(this, getString(R.string.lang_get_wifi), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fetch(false);
            }
        }), Kit.below(this, 16));
        english.addView(Kit.link(this, getString(R.string.lang_get_now), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fetch(true);
            }
        }), Kit.below(this, 8));
        english.addView(Kit.link(this, getString(R.string.lang_from_file), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK);
            }
        }));
    }

    private String downloading(Fetch.Progress p) {
        if (p.status == DownloadManager.STATUS_PAUSED
                && p.reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) {
            return getString(R.string.lang_waiting_wifi);
        }
        if (p.status == DownloadManager.STATUS_PAUSED) {
            return getString(R.string.lang_waiting_net);
        }
        if (p.total <= 0) {
            return getString(R.string.lang_starting);
        }
        return getString(R.string.lang_downloading, (int) (p.done * 100 / p.total));
    }

    private void fetch(final boolean anyNetwork) {
        trouble = null;
        work(R.string.lang_asking, new Job() {
            @Override
            public String run() {
                try {
                    LanguagePack.start(LanguagesActivity.this, EN, anyNetwork);
                    return null;
                } catch (Exception e) {
                    Diag.log(LanguagesActivity.this, "pack: list unavailable", e);
                    return getString(R.string.lang_no_list);
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        trouble = null;
        work(R.string.lang_installing, new Job() {
            @Override
            public String run() {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    LanguagePack.unpack(LanguagesActivity.this, EN, in);
                    return null;
                } catch (Exception e) {
                    Diag.log(LanguagesActivity.this, "pack: file refused", e);
                    return getString(R.string.lang_bad_file);
                }
            }
        });
    }

    private interface Job {
        /** Does the slow part; returns trouble in words, or null when all went well. */
        String run();
    }

    private void work(int what, final Job job) {
        if (busy != 0) {
            return;
        }
        busy = what;
        show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String t = job.run();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = 0;
                        trouble = t;
                        if (!isFinishing()) {
                            show();
                        }
                    }
                });
            }
        }, "languages").start();
    }
}
