package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
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
    /** The book's own cast: narrator, first and second speaker (zero: read by the narrator). */
    private final int[] cast = new int[3];
    private boolean castSet;
    /** The background mixed into the chapters; off by default. */
    private int bed = Bed.OFF;
    private LinearLayout when;
    private LinearLayout offerAction;
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
        // When the work is done: at once, or in a window of the night. One setting for the whole queue.
        when = Ui.column(this);
        list.addView(when, Kit.wide());
        offerAction = Ui.column(this);
        list.addView(offerAction, Kit.wide());
        showWhen();
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
                        author = Demo.is(ExportActivity.this, uri) ? Demo.author(ExportActivity.this)
                                : got == null || got.author == null ? "" : got.author.trim();
                        showOffer(uri, name);
                    }
                });
            }
        }, "export-book").start();
    }

    /** The "when" section: at once or on a schedule, and the window's two ends when scheduled. */
    private void showWhen() {
        when.removeAllViews();
        when.addView(Kit.section(this, getString(R.string.export_when)));
        LinearLayout plate = Kit.plate(this);
        final boolean scheduled = Export.scheduled(this);
        LinearLayout seg = Kit.segments(this, new String[] {getString(R.string.export_when_now),
                getString(R.string.export_when_scheduled)}, scheduled ? 1 : 0, new Kit.Chosen() {
                    @Override
                    public void chose(int which) {
                        Export.setSchedule(ExportActivity.this, which == 1, Export.startMinutes(ExportActivity.this),
                                Export.endMinutes(ExportActivity.this));
                        showWhen();
                        showQueue();
                    }
                });
        LinearLayout.LayoutParams sp = Ui.lp(Ui.MATCH, Ui.WRAP);
        sp.setMargins(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, scheduled ? 4 : 12));
        plate.addView(seg, sp);
        if (scheduled) {
            plate.addView(Kit.rowNav(this, getString(R.string.export_when_start),
                    Export.clock(Export.startMinutes(this)), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Kit.timeSheet(ExportActivity.this, getString(R.string.export_when_start),
                                    Export.startMinutes(ExportActivity.this), new Kit.TimeSet() {
                                        @Override
                                        public void set(int minutes) {
                                            Export.setSchedule(ExportActivity.this, true, minutes,
                                                    Export.endMinutes(ExportActivity.this));
                                            showWhen();
                                            showQueue();
                                        }
                                    });
                        }
                    }));
            plate.addView(Kit.divider(this));
            plate.addView(Kit.rowNav(this, getString(R.string.export_when_end),
                    Export.clock(Export.endMinutes(this)), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Kit.timeSheet(ExportActivity.this, getString(R.string.export_when_end),
                                    Export.endMinutes(ExportActivity.this), new Kit.TimeSet() {
                                        @Override
                                        public void set(int minutes) {
                                            Export.setSchedule(ExportActivity.this, true,
                                                    Export.startMinutes(ExportActivity.this), minutes);
                                            showWhen();
                                            showQueue();
                                        }
                                    });
                        }
                    }));
        }
        when.addView(plate, Kit.wide());
        TextView note = Ui.text(this, scheduled
                ? getString(R.string.export_when_note_night) + "\n" + getString(R.string.export_when_note_battery, Export.BATTERY_FLOOR)
                : getString(R.string.export_when_note_battery, Export.BATTERY_FLOOR), 13, Palette.MUTED);
        note.setLineSpacing(0, 1.3f);
        note.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), 0);
        when.addView(note, Kit.wide());
    }

    private void showOffer(final String uri, final String name) {
        offer.removeAllViews();
        offerAction.removeAllViews();
        if (book == null || Export.queued(this, uri)) {
            return;
        }
        final String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (!castSet) {
            // The cast starts as the roles are now: the dialogue voices only when they are on.
            castSet = true;
            cast[0] = getIntent().getIntExtra(EXTRA_READER, Prefs.role(this, Cast.NARRATOR));
            // A book last read by a voice that has since left the troupe goes to the narrator.
            if (!Cast.exists(cast[0])) {
                cast[0] = Prefs.role(this, Cast.NARRATOR);
            }
            boolean dialogue = Prefs.dialogueVoice(this);
            cast[1] = dialogue ? Prefs.role(this, Cast.SPEAKER_A) : 0;
            cast[2] = dialogue ? Prefs.role(this, Cast.SPEAKER_B) : 0;
        }
        final int reader = cast[0];
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

        // Who reads this book: its own cast, kept with it in the queue; the roles stay as they are.
        offer.addView(Kit.section(this, getString(R.string.export_voices)));
        LinearLayout voices = Kit.plate(this);
        for (int i = 0; i < 3; i++) {
            voices.addView(castRow(uri, name, i));
        }
        offer.addView(voices, Kit.wide());

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
        tags.addView(Kit.rowNav(this, getString(R.string.export_bed),
                bedName(bed), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        bedSheet(uri, name);
                    }
                }));
        offer.addView(tags, Kit.wide());
        TextView note = Ui.text(this, getString(R.string.export_files_note), 13, Palette.MUTED);
        note.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), 0);
        offer.addView(note, Kit.wide());

        offerAction.removeAllViews();
        offerAction.addView(Kit.primary(this, getString(R.string.export_start), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Export.ownTree(ExportActivity.this) == null && HoraFolder.tree(ExportActivity.this) == null) {
                    HoraFolder.ask(ExportActivity.this);
                    return;
                }
                Export.add(ExportActivity.this, uri, name, title, cast[0], cast[1], cast[2], chapters, author, coverMode,
                        bed, Bed.levelOf(ExportActivity.this, bed));
                Diag.mark(ExportActivity.this, "export: cast " + cast[0] + "/" + cast[1] + "/" + cast[2] + ", bed " + bed);
                offer.removeAllViews();
                offerAction.removeAllViews();
                showQueue();
            }
        }), Kit.below(this, 16));
    }

    /** One role of the book's cast: its name, and the face and name of who reads it. */
    private View castRow(final String uri, final String name, final int role) {
        int v = cast[role];
        String who = v > 0 ? Cast.name(this, v) : getString(R.string.export_by_narrator);
        LinearLayout row = Kit.rowNav(this, getString(title(role)), who, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                castSheet(uri, name, role, title(role));
            }
        });
        if (v > 0) {
            ImageView face = Ui.portrait(this, v);
            face.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28));
            fp.leftMargin = Ui.dp(this, 8);
            row.addView(face, 1, fp);
        }
        return row;
    }

    private int title(int role) {
        return role == 0 ? R.string.role_narrator : role == 1 ? R.string.role_speaker_a : R.string.role_speaker_b;
    }

    /** The troupe as faces for one role of this book; a speaker may also be left to the narrator. */
    private void castSheet(final String uri, final String name, final int role, int title) {
        final android.app.Dialog[] sheet = new android.app.Dialog[1];
        LinearLayout grid = Ui.column(this);
        grid.setGravity(Gravity.CENTER_HORIZONTAL);
        int count = Cast.size(this);
        int perRow = Ui.perRow(count);
        float width = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        int cellDp = Math.max(44, Math.min(84, (int) ((width - 24 - 2 * Kit.PAD) / perRow)));
        LinearLayout line = null;
        for (int i = 0; i < count; i++) {
            final int v = Cast.at(i);
            if (i % perRow == 0) {
                line = Ui.row(this);
                line.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams lp = Ui.lp(Ui.WRAP, Ui.WRAP);
                if (i > 0) {
                    lp.topMargin = Ui.dp(this, 10);
                }
                grid.addView(line, lp);
            }
            LinearLayout cell = Ui.column(this);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView face = Ui.portrait(this, v);
            boolean on = v == cast[role];
            face.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
            face.setBackground(on ? Ui.oval(Palette.SURFACE, Palette.voice(v), 2, this)
                    : Ui.oval(android.graphics.Color.TRANSPARENT, Palette.LINE, 1, this));
            face.setAlpha(on ? 1f : 0.75f);
            int side = Math.min(56, cellDp - 4);
            cell.addView(face, Ui.lp(Ui.dp(this, side), Ui.dp(this, side)));
            TextView label = Ui.text(this, Cast.name(this, v), 13, on ? Palette.INK : Palette.MUTED);
            if (on) {
                label.setTypeface(Palette.bodyStrong(this));
            }
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams np = Ui.lp(Ui.MATCH, Ui.WRAP);
            np.topMargin = Ui.dp(this, 4);
            cell.addView(label, np);
            cell.setContentDescription(Cast.name(this, v));
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    cast[role] = v;
                    sheet[0].dismiss();
                    showOffer(uri, name);
                }
            });
            line.addView(cell, Ui.lp(Ui.dp(this, cellDp), Ui.WRAP));
        }
        LinearLayout box = Ui.column(this);
        box.addView(Kit.lead(this, getString(R.string.export_cast_note)));
        box.addView(grid, Kit.below(this, 12));
        if (role > 0) {
            // Chosen now, it says so as a face would by its ring.
            String sameLabel = (cast[role] == 0 ? "\u2713  " : "") + getString(R.string.export_by_narrator_button);
            TextView same = Kit.secondary(this, sameLabel, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    cast[role] = 0;
                    sheet[0].dismiss();
                    showOffer(uri, name);
                }
            });
            box.addView(same, Kit.below(this, 18));
        }
        sheet[0] = Kit.liveSheet(this, getString(title), box);
    }

    private String bedName(int kind) {
        String[] names = getResources().getStringArray(R.array.bed_kinds);
        return kind >= 0 && kind < names.length ? names[kind] : "";
    }

    /** The background under the voice in the files: silence, or one of the kinds made here. */
    private void bedSheet(final String uri, final String name) {
        final android.app.Dialog[] sheet = new android.app.Dialog[1];
        LinearLayout box = Ui.column(this);
        box.addView(Kit.lead(this, getString(R.string.export_bed_note)));
        for (int k = 0; k < Bed.KINDS; k++) {
            if (k != Bed.OFF && !Bed.recordable(k)) {
                continue;
            }
            final int kind = k;
            boolean on = kind == bed;
            TextView item = Ui.text(this, (on ? "\u2713  " : "") + bedName(kind), 17, on ? Palette.INK : Palette.MUTED);
            if (on) {
                item.setTypeface(Palette.bodyStrong(this));
            }
            item.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
            item.setBackground(on ? Ui.round(Palette.RAISED, 22, this)
                    : Ui.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)));
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    bed = kind;
                    sheet[0].dismiss();
                    showOffer(uri, name);
                }
            });
            box.addView(item, Kit.below(this, 2));
        }
        sheet[0] = Kit.liveSheet(this, getString(R.string.export_bed_title), box);
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
        int gate = Export.gate(this);
        boolean first = true;
        for (int i = 0; i < q.length(); i++) {
            final JSONObject j = q.optJSONObject(i);
            final boolean failed = j.optBoolean("failed");
            String state;
            if (failed) {
                state = getString(R.string.export_failed);
            } else if (!first) {
                state = getString(R.string.export_waiting_turn);
            } else if (gate == Export.WAIT_TIME) {
                state = getString(R.string.export_waiting_time, Export.clock(Export.startMinutes(this)));
            } else if (gate == Export.WAIT_POWER) {
                state = getString(R.string.export_waiting_charger_at, Export.battery(this));
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
