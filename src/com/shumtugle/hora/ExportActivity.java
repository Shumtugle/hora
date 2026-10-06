package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Making a book into an audiobook: says plainly what it takes (hours, on the
 * charger, best overnight), where the files will go, and keeps the queue in
 * sight with each book's progress. Opened with a book, it offers that book;
 * opened without, it shows the queue.
 */
public final class ExportActivity extends Activity {
    static final String EXTRA_URI = "uri";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_READER = "reader";
    private static final int PICK_FOLDER = 91;

    private int built;
    private LinearLayout offer;
    private LinearLayout queueBox;
    private TextView whereRow;
    private Book book;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            showQueue();
            main.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        LinearLayout list = Kit.room(this, getString(R.string.export_title));
        list.addView(Kit.lead(this, getString(R.string.export_lead)));
        offer = Ui.column(this);
        list.addView(offer, Kit.wide());
        list.addView(Kit.section(this, getString(R.string.export_where)));
        LinearLayout where = Kit.plate(this);
        whereRow = Ui.text(this, "", 15, Palette.INK);
        whereRow.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        where.addView(whereRow);
        list.addView(where, Kit.wide());
        list.addView(Kit.link(this, getString(R.string.export_other_folder), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), PICK_FOLDER);
            }
        }), Kit.below(this, 8));
        list.addView(Kit.section(this, getString(R.string.export_queue)));
        queueBox = Ui.column(this);
        list.addView(queueBox, Kit.wide());
        loadBook();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        showWhere();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(tick);
        super.onPause();
    }

    private void showWhere() {
        String to;
        if (Export.ownTree(this) != null) {
            Uri t = Export.ownTree(this);
            String id = android.provider.DocumentsContract.getTreeDocumentId(t);
            to = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        } else if (HoraFolder.tree(this) != null) {
            to = getString(R.string.folder_root) + "/" + getString(R.string.folder_audiobooks);
        } else {
            to = getString(R.string.export_no_folder);
        }
        whereRow.setText(to);
    }

    /** The book offered, if one came with the screen: read in the background, then described. */
    private void loadBook() {
        final String uri = getIntent().getStringExtra(EXTRA_URI);
        if (uri == null) {
            return;
        }
        final String name = getIntent().getStringExtra(EXTRA_NAME);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Book b = null;
                try {
                    b = Export.load(ExportActivity.this, uri, name);
                } catch (Exception e) {
                    Diag.log(ExportActivity.this, "export: book not read", e);
                }
                final Book got = b;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        book = got;
                        showOffer(uri, name);
                    }
                });
            }
        }, "export-book").start();
    }

    private void showOffer(final String uri, final String name) {
        offer.removeAllViews();
        if (book == null || Export.queued(this, uri)) {
            return;
        }
        final String title = getIntent().getStringExtra(EXTRA_TITLE);
        final int reader = getIntent().getIntExtra(EXTRA_READER, Prefs.role(this, Cast.NARRATOR));
        final int chapters = Export.parts(book).size();
        int[] est = Export.estimate(book);
        LinearLayout plate = Kit.plate(this);
        TextView t = Ui.text(this, getString(R.string.export_about, title,
                getResources().getQuantityString(R.plurals.book_chapters, chapters, chapters),
                Cast.name(this, reader), hours(est[0]), hours(est[1])), 15, Palette.INK);
        t.setLineSpacing(0, 1.3f);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        plate.addView(t);
        offer.addView(plate, Kit.wide());
        offer.addView(Kit.primary(this, getString(R.string.export_start), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Export.ownTree(ExportActivity.this) == null && HoraFolder.tree(ExportActivity.this) == null) {
                    HoraFolder.ask(ExportActivity.this);
                    return;
                }
                Export.add(ExportActivity.this, uri, name, title, reader, chapters);
                offer.removeAllViews();
                showQueue();
            }
        }), Kit.below(this, 16));
    }

    /** Minutes in plain words, or hours once there are sixty of them. */
    private String hours(int minutes) {
        if (minutes < 60) {
            return getResources().getQuantityString(R.plurals.export_minutes, minutes, minutes);
        }
        int h = Math.round(minutes / 60f);
        return getResources().getQuantityString(R.plurals.export_hours, h, h);
    }

    /** The chapter at work and how far into it, from the place the work keeps after every paragraph. */
    private String working(JSONObject j) {
        int all = j.optInt("chapters");
        int ch = Math.min(j.optInt("chapter") + 1, all);
        int from = j.optInt("from", -1);
        int to = j.optInt("to", -1);
        int p = j.optInt("paragraph");
        if (from < 0 || to <= from || p < from) {
            return getString(R.string.export_working, ch, all);
        }
        int pct = Math.max(0, Math.min(99, (p - from) * 100 / (to - from)));
        return getString(R.string.export_working_pct, ch, all, pct);
    }

    private void showQueue() {
        queueBox.removeAllViews();
        JSONArray q = Export.queue(this);
        LinearLayout plate = Kit.plate(this);
        plate.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        if (q.length() == 0) {
            plate.addView(Kit.status(this, getString(R.string.export_queue_empty), Kit.Mood.UNKNOWN));
        }
        boolean charging = ((android.os.BatteryManager) getSystemService(BATTERY_SERVICE)).isCharging();
        boolean first = true;
        for (int i = 0; i < q.length(); i++) {
            final JSONObject j = q.optJSONObject(i);
            final boolean failed = j.optBoolean("failed");
            String state;
            if (failed) {
                state = getString(R.string.export_failed);
            } else if (!first) {
                state = getString(R.string.export_waiting_turn);
            } else if (!charging) {
                state = getString(R.string.export_waiting_charger);
            } else if (Reading.playing(this)) {
                state = getString(R.string.export_yielding);
            } else if (System.currentTimeMillis() - j.optLong("at") < Export.FRESH_MS) {
                state = working(j);
            } else {
                state = getString(R.string.export_waiting_phone);
            }
            if (!failed) {
                first = false;
            }
            View row = Kit.rowNav(this, j.optString("title"), state, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final String uri = j.optString("uri");
                    if (!failed) {
                        Kit.sheet(ExportActivity.this, j.optString("title"), null, getString(R.string.export_cancel),
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        Export.cancel(ExportActivity.this, uri);
                                        showQueue();
                                    }
                                });
                        return;
                    }
                    final android.app.Dialog[] sheet = new android.app.Dialog[1];
                    View drop = Kit.link(ExportActivity.this, getString(R.string.export_cancel), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Export.cancel(ExportActivity.this, uri);
                            if (sheet[0] != null) {
                                sheet[0].dismiss();
                            }
                            showQueue();
                        }
                    });
                    sheet[0] = Kit.sheet(ExportActivity.this, j.optString("title"),
                            Ui.text(ExportActivity.this, getString(R.string.export_failed_about), 15, Palette.MUTED),
                            getString(R.string.export_retry), new Runnable() {
                                @Override
                                public void run() {
                                    Export.retry(ExportActivity.this, uri);
                                    showQueue();
                                }
                            }, drop);
                }
            });
            plate.addView(row);
        }
        queueBox.addView(plate, Kit.wide());
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_FOLDER && result == RESULT_OK) {
            Export.acceptFolder(this, data);
        } else if (request == HoraFolder.PICK && result == RESULT_OK) {
            HoraFolder.accept(this, data);
        }
        showWhere();
    }
}
