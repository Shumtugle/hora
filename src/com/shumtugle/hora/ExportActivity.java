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
    private static final int PICK_COVER = 92;
    /** The book's own cover, small, or null when the file carries none. */
    private byte[] bookCover;
    /** What the chapters will carry: the book's cover, the person's own picture, or none. */
    private String coverMode;
    private byte[] chosenCover;
    /** The author written into the chapters; empty means none. */
    private String author;
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
                final byte[] own = BookCover.jpeg(ExportActivity.this, uri, name);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        book = got;
                        bookCover = own;
                        coverMode = own != null ? Export.COVER_BOOK : Export.COVER_NONE;
                        author = got == null || got.author == null ? "" : got.author.trim();
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

        // What the files will say about the book: only what the book itself says, or what the person adds.
        offer.addView(Kit.section(this, getString(R.string.export_files_section)));
        LinearLayout tags = Kit.plate(this);
        String coverValue = getString(Export.COVER_BOOK.equals(coverMode) ? R.string.export_cover_book
                : Export.COVER_NONE.equals(coverMode) ? R.string.export_cover_none : R.string.export_cover_own);
        tags.addView(Kit.rowNav(this, getString(R.string.export_cover), coverValue, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                coverSheet(uri, name);
            }
        }));
        tags.addView(Kit.rowNav(this, getString(R.string.export_author),
                author.isEmpty() ? getString(R.string.export_author_none) : author, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        authorSheet(uri, name);
                    }
                }));
        offer.addView(tags, Kit.wide());
        TextView note = Ui.text(this, getString(R.string.export_files_note), 13, Palette.MUTED);
        note.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), 0);
        offer.addView(note, Kit.wide());

        offer.addView(Kit.primary(this, getString(R.string.export_start), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Export.ownTree(ExportActivity.this) == null && HoraFolder.tree(ExportActivity.this) == null) {
                    HoraFolder.ask(ExportActivity.this);
                    return;
                }
                Export.add(ExportActivity.this, uri, name, title, reader, chapters, author, coverMode);
                offer.removeAllViews();
                showQueue();
            }
        }), Kit.below(this, 16));
    }

    /** The cover: what it is now, and the ways to change it. */
    private void coverSheet(final String uri, final String name) {
        LinearLayout box = Ui.column(this);
        byte[] now = Export.COVER_BOOK.equals(coverMode) ? bookCover
                : Export.COVER_NONE.equals(coverMode) ? null : chosenCover;
        if (now != null) {
            android.widget.ImageView pic = new android.widget.ImageView(this);
            pic.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            pic.setClipToOutline(true);
            pic.setBackground(Ui.round(Palette.RAISED, 10, this));
            pic.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(now, 0, now.length));
            box.addView(pic, Ui.lp(Ui.dp(this, 120), Ui.dp(this, 180)));
        }
        box.addView(Kit.lead(this, getString(bookCover != null ? R.string.export_cover_about_has
                : R.string.export_cover_about_none)), Kit.below(this, now != null ? 14 : 0));
        final android.app.Dialog[] d = new android.app.Dialog[1];
        LinearLayout lesser = Ui.column(this);
        if (bookCover != null && !Export.COVER_BOOK.equals(coverMode)) {
            lesser.addView(Kit.link(this, getString(R.string.export_cover_take_book), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    coverMode = Export.COVER_BOOK;
                    d[0].dismiss();
                    showOffer(uri, name);
                }
            }));
        }
        if (!Export.COVER_NONE.equals(coverMode)) {
            lesser.addView(Kit.link(this, getString(R.string.export_cover_drop), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    coverMode = Export.COVER_NONE;
                    d[0].dismiss();
                    showOffer(uri, name);
                }
            }));
        }
        d[0] = Kit.sheet(this, getString(R.string.export_cover), box, getString(R.string.export_cover_choose),
                new Runnable() {
                    @Override
                    public void run() {
                        Intent pick = android.os.Build.VERSION.SDK_INT >= 33
                                ? new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
                                : new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                        .setType("image/*");
                        startActivityForResult(pick, PICK_COVER);
                    }
                }, lesser);
        d[0].show();
    }

    /** The author: what the book says, which the person may correct or clear. */
    private void authorSheet(final String uri, final String name) {
        final android.widget.EditText field = new android.widget.EditText(this);
        Ui.field(field);
        field.setText(author);
        field.setSelection(author.length());
        field.setHint(R.string.export_author_hint);
        field.setHintTextColor(Palette.HINT);
        field.setTextColor(Palette.INK);
        field.setSingleLine(true);
        LinearLayout box = Ui.column(this);
        box.addView(Kit.lead(this, getString(R.string.export_author_about)));
        box.addView(field, Kit.below(this, 12));
        Kit.sheet(this, getString(R.string.export_author), box, getString(R.string.kit_done), new Runnable() {
            @Override
            public void run() {
                author = field.getText().toString().trim();
                showOffer(uri, name);
            }
        }).show();
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
        if (request == PICK_COVER) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                final Uri picture = data.getData();
                final String uri = getIntent().getStringExtra(EXTRA_URI);
                final String name = getIntent().getStringExtra(EXTRA_NAME);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final byte[] got = BookCover.chosen(ExportActivity.this, picture);
                        if (got != null && uri != null) {
                            try {
                                java.nio.file.Files.write(Export.chosenCover(ExportActivity.this, uri).toPath(), got);
                            } catch (java.io.IOException e) {
                                Diag.log(ExportActivity.this, "export: chosen cover not kept", e);
                                return;
                            }
                        }
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (got != null) {
                                    chosenCover = got;
                                    coverMode = "own";
                                    showOffer(uri, name);
                                }
                            }
                        });
                    }
                }, "export-cover").start();
            }
            return;
        }
        if (request == PICK_FOLDER && result == RESULT_OK) {
            Export.acceptFolder(this, data);
        } else if (request == HoraFolder.PICK && result == RESULT_OK) {
            HoraFolder.accept(this, data);
        }
        showWhere();
    }
}
