package com.shumtugle.hora;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;

/**
 * One pack on a screen: what is there and what can be done next. Fetched by
 * Wi-Fi unless asked otherwise, or taken from a file the person already has.
 * The screen that holds the panel passes on its resume, pause and results.
 */
final class PackPanel {
    private final Activity a;
    private final String id;
    private final int request;
    private final int megabytes;
    private final int badFile;
    private final int note;
    private final boolean removable;
    private final Runnable changed;
    private final LinearLayout box;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Set while something slow runs (reading the list, unpacking); the panel shows it instead of the state. */
    private int busy;
    /** The last trouble in words, shown until the next action. */
    private String trouble;
    private boolean shown;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            show();
        }
    };

    /**
     * @param request  the request code for choosing a file, unique on the screen
     * @param megabytes the size said on the download button
     * @param badFile  what to say when a chosen file is not this pack
     * @param note     a line under the installed state, or 0
     * @param changed  run on the main thread when the pack came or went, or null
     */
    PackPanel(Activity a, String id, int request, int megabytes, int badFile, int note, boolean removable,
              Runnable changed) {
        this.a = a;
        this.id = id;
        this.request = request;
        this.megabytes = megabytes;
        this.badFile = badFile;
        this.note = note;
        this.removable = removable;
        this.changed = changed;
        box = Ui.column(a);
    }

    View view() {
        return box;
    }

    void resume() {
        shown = true;
        // A download that ended while the screen was closed is finished here too.
        settle();
        show();
    }

    void pause() {
        shown = false;
        main.removeCallbacks(tick);
    }

    private void settle() {
        Fetch.Progress p = Fetch.progress(a, LanguagePack.key(id));
        if (p != null && (p.status == DownloadManager.STATUS_SUCCESSFUL || p.status == DownloadManager.STATUS_FAILED)) {
            work(R.string.lang_installing, new Job() {
                @Override
                public String run() {
                    return LanguagePack.finish(a, id) == Fetch.FAILED ? a.getString(R.string.lang_failed) : null;
                }
            });
        }
    }

    private void show() {
        main.removeCallbacks(tick);
        box.removeAllViews();
        LinearLayout plate = Kit.plate(a);
        box.addView(plate, Kit.wide());
        if (busy != 0) {
            plate.addView(Kit.status(a, a.getString(busy), Kit.Mood.UNKNOWN));
            return;
        }
        Fetch.Progress p = Fetch.progress(a, LanguagePack.key(id));
        if (p != null && p.status != DownloadManager.STATUS_SUCCESSFUL && p.status != DownloadManager.STATUS_FAILED) {
            plate.addView(Kit.status(a, downloading(p), Kit.Mood.UNKNOWN));
            box.addView(Kit.link(a, a.getString(R.string.lang_cancel), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Fetch.cancel(a, LanguagePack.key(id));
                    show();
                }
            }), Kit.below(a, 8));
            if (shown) {
                main.postDelayed(tick, 1000);
            }
            return;
        }
        if (p != null) {
            // Done or failed, but not settled yet: settled now.
            settle();
            return;
        }
        boolean pack = LanguagePack.installed(a, id);
        // The voice may still live where an older build put it: it is here all the same.
        if (pack || (LanguagePack.NATIVE.equals(id) && Voice.ready(a))) {
            plate.addView(Kit.status(a, pack ? a.getString(R.string.lang_installed,
                    Math.round(LanguagePack.size(a, id) / 1e6)) : a.getString(R.string.lang_builtin), Kit.Mood.FINE));
            if (note != 0) {
                TextView line = Ui.text(a, a.getString(note), 14, Palette.MUTED);
                line.setPadding(Ui.dp(a, Kit.PAD), 0, Ui.dp(a, Kit.PAD), Ui.dp(a, 16));
                plate.addView(line);
            }
            if (removable && pack) {
                box.addView(Kit.link(a, a.getString(R.string.lang_remove), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        LanguagePack.remove(a, id);
                        show();
                        if (changed != null) {
                            changed.run();
                        }
                    }
                }), Kit.below(a, 8));
            }
            return;
        }
        plate.addView(Kit.status(a, trouble != null ? trouble : a.getString(R.string.lang_absent),
                trouble != null ? Kit.Mood.TROUBLE : Kit.Mood.UNKNOWN));
        box.addView(Kit.primary(a, a.getString(R.string.pack_get_wifi, megabytes), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fetch(false);
            }
        }), Kit.below(a, 16));
        box.addView(Kit.link(a, a.getString(R.string.lang_get_now), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fetch(true);
            }
        }), Kit.below(a, 8));
        box.addView(Kit.link(a, a.getString(R.string.lang_from_file), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), request);
            }
        }));
    }

    private String downloading(Fetch.Progress p) {
        if (p.status == DownloadManager.STATUS_PAUSED
                && p.reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) {
            return a.getString(R.string.lang_waiting_wifi);
        }
        if (p.status == DownloadManager.STATUS_PAUSED) {
            return a.getString(R.string.lang_waiting_net);
        }
        if (p.total <= 0) {
            return a.getString(R.string.lang_starting);
        }
        return a.getString(R.string.lang_downloading, (int) (p.done * 100 / p.total));
    }

    private void fetch(final boolean anyNetwork) {
        trouble = null;
        work(R.string.lang_asking, new Job() {
            @Override
            public String run() {
                try {
                    LanguagePack.start(a, id, anyNetwork);
                    return null;
                } catch (Exception e) {
                    Diag.log(a, "pack: list unavailable for " + id, e);
                    return a.getString(R.string.lang_no_list);
                }
            }
        });
    }

    /** A file chosen for this panel; false when the result is not its own. */
    boolean result(int code, int result, Intent data) {
        if (code != request) {
            return false;
        }
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }
        final Uri uri = data.getData();
        trouble = null;
        work(R.string.lang_installing, new Job() {
            @Override
            public String run() {
                try (InputStream in = a.getContentResolver().openInputStream(uri)) {
                    LanguagePack.unpack(a, id, in);
                    Diag.mark(a, "pack: " + id + " taken from a file");
                    return null;
                } catch (Exception e) {
                    Diag.log(a, "pack: file refused for " + id, e);
                    return a.getString(badFile);
                }
            }
        });
        return true;
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
                        if (!a.isFinishing()) {
                            show();
                        }
                        if (t == null && changed != null) {
                            changed.run();
                        }
                    }
                });
            }
        }, "pack-" + id).start();
    }
}
