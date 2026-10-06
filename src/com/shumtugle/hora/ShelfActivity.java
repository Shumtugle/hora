package com.shumtugle.hora;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The shelf, the fourth scene: the book being heard on top with the one
 * button that matters here, then every other book opened in Hora as a cover
 * two to a row, newest first. A touch continues a book; holding it offers to
 * take it off the shelf or tells about it. In settings the shelf can turn
 * into a list instead: every book as a row, recent, by title or read through.
 */
public final class ShelfActivity extends Activity {
    private static final int PICK_BOOK = 310;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int built;
    private ImageView backdrop;
    private int backdropVoice = -1;
    private LinearLayout list;
    private ImageView play;
    /** What the shelf was last drawn from, so it is only redrawn when something changed. */
    private String drawn = "";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            show();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        FrameLayout whole = new FrameLayout(this);
        whole.setBackgroundColor(Palette.BG);
        backdrop = new ImageView(this);
        backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        whole.addView(backdrop, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        View dim = new View(this);
        dim.setBackgroundColor((Palette.BG & 0x00FFFFFF) | 0xC7000000);
        whole.addView(dim, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout root = Ui.column(this);
        whole.addView(root, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.title(this, getString(R.string.shelf_title), 40),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ImageView gear = Ui.iconButton(this, R.drawable.ic_settings, Palette.SURFACE, 48, getString(R.string.settings));
        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ShelfActivity.this, SettingsActivity.class));
            }
        });
        head.addView(gear, Ui.lp(dp(48), dp(48)));
        root.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(24));
        list = Ui.column(this);
        list.setPadding(0, 0, 0, dp(24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        root.addView(Ui.navBar(this, Ui.NAV_SHELF), Ui.lp(Ui.MATCH, Ui.WRAP));
        Kit.edgeToEdge(this, root, dp(24), dp(20), dp(24), dp(16));
        setContentView(whole);
    }

    @Override
    protected void onResume() {
        super.onResume();
        BookActivity.warm(this);
        if (Ui.stale(this, built)) {
            return;
        }
        drawn = "";
        handler.post(tick);
        tidy(false);
    }

    private static boolean tidying;

    /** Brings the shelf in line with the folders in the background, then redraws; first moves books out when asked. */
    private void tidy(final boolean moveOut) {
        if (tidying) {
            return;
        }
        tidying = true;
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (moveOut) {
                        Books.moveOut(app);
                    }
                    Books.tidy(app);
                } finally {
                    tidying = false;
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        drawn = "";
                        show();
                    }
                });
            }
        }, "shelf-tidy").start();
    }

    /** Until Hora knows its folder, a quiet line under the shelf offers to show it. */
    private View folderHint() {
        if (HoraFolder.tree(this) != null) {
            return null;
        }
        TextView t = Ui.text(this, getString(R.string.shelf_folder_hint), 14, Palette.ACCENT_TEXT);
        t.setLineSpacing(0, 1.25f);
        t.setPadding(0, dp(18), 0, dp(4));
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HoraFolder.ask(ShelfActivity.this);
            }
        });
        return t;
    }

    /** The state line, with the temporary mark in the accent colour before it. */
    private CharSequence marked(Library.Entry e, String before) {
        if (!Books.isTemporary(this, e.uri)) {
            return before + state(e);
        }
        String mark = getString(R.string.shelf_temporary);
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(mark)
                .append(" \u00b7 ").append(before).append(state(e));
        b.setSpan(new android.text.style.ForegroundColorSpan(Palette.ACCENT_TEXT), 0, mark.length(), 0);
        return b;
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private int dp(float v) {
        return Kit.dp(this, v);
    }

    /** Redraws the shelf when a book, its place, its reader or the play state changed. */
    private void show() {
        String current = Reading.uri(this);
        List<Library.Entry> all = Library.all(this);
        boolean playing = Reading.playing(this);
        boolean asList = Prefs.shelfList(this);
        int order = Prefs.shelfOrder(this);
        StringBuilder key = new StringBuilder(current).append(playing).append(asList).append(order);
        for (Library.Entry e : all) {
            key.append('|').append(e.uri).append(e.index).append(e.reader);
        }
        int narrator = Prefs.role(this, Cast.NARRATOR);
        if (narrator != backdropVoice) {
            backdropVoice = narrator;
            Kit.backdrop(this, backdrop, narrator);
        }
        if (key.toString().equals(drawn)) {
            return;
        }
        drawn = key.toString();
        list.removeAllViews();
        if (asList) {
            showList(all, current, order, narrator);
            return;
        }

        Library.Entry now = null;
        List<Library.Entry> rest = new ArrayList<Library.Entry>();
        for (Library.Entry e : all) {
            if (now == null && e.uri.equals(current)) {
                now = e;
            } else {
                rest.add(e);
            }
        }
        if (now != null) {
            LinearLayout.LayoutParams np = Ui.lp(Ui.MATCH, Ui.WRAP);
            np.topMargin = dp(24);
            list.addView(nowCard(now, playing, narrator), np);
        }

        LinearLayout bar = Ui.row(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView on = Kit.section(this, rest.isEmpty() ? getString(R.string.shelf_on_none)
                : getString(R.string.shelf_on, rest.size()));
        on.setPadding(0, 0, 0, 0);
        bar.addView(on, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = addLink();
        bar.addView(add);
        LinearLayout.LayoutParams bp = Ui.lp(Ui.MATCH, Ui.WRAP);
        bp.topMargin = dp(now != null ? 26 : 24);
        list.addView(bar, bp);
        View hint = folderHint();
        if (hint != null) {
            list.addView(hint);
        }

        if (rest.isEmpty()) {
            TextView none = Ui.text(this, getString(now == null ? R.string.shelf_empty : R.string.shelf_only_one),
                    15, Palette.MUTED);
            none.setLineSpacing(0, 1.25f);
            LinearLayout.LayoutParams ep = Ui.lp(Ui.MATCH, Ui.WRAP);
            ep.topMargin = dp(12);
            list.addView(none, ep);
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % 2 == 0) {
                row = Ui.row(this);
                row.setGravity(Gravity.TOP);
                LinearLayout.LayoutParams rp = Ui.lp(Ui.MATCH, Ui.WRAP);
                rp.topMargin = dp(i == 0 ? 12 : 22);
                list.addView(row, rp);
            }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 2 == 1) {
                cp.leftMargin = dp(18);
            }
            row.addView(card(rest.get(i)), cp);
        }
        if (rest.size() % 2 == 1) {
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 1, 1f);
            sp.leftMargin = dp(18);
            row.addView(new View(this), sp);
        }
    }

    private TextView addLink() {
        TextView add = Ui.text(this, getString(R.string.shelf_add), 15, Palette.ACCENT_TEXT);
        add.setTypeface(Palette.bodyStrong(this));
        add.setPadding(dp(8), dp(10), 0, dp(10));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, PICK_BOOK);
            }
        });
        return add;
    }

    private boolean finished(Library.Entry e) {
        return e.total > 0 && e.index >= e.total - 1;
    }

    /** The shelf as a list: segments for the order, then one plate of rows. */
    private void showList(List<Library.Entry> all, String current, int order, int narrator) {
        LinearLayout.LayoutParams gp = Ui.lp(Ui.MATCH, Ui.WRAP);
        gp.topMargin = dp(16);
        list.addView(Kit.segments(this, new String[] {getString(R.string.shelf_recent),
                getString(R.string.shelf_by_title), getString(R.string.shelf_read_ones)}, order, new Kit.Chosen() {
                    @Override
                    public void chose(int which) {
                        Prefs.setShelfOrder(ShelfActivity.this, which);
                        show();
                    }
                }), gp);
        List<Library.Entry> shown = new ArrayList<Library.Entry>();
        for (Library.Entry e : all) {
            if (order != Prefs.SHELF_READ || finished(e)) {
                shown.add(e);
            }
        }
        if (order == Prefs.SHELF_BY_TITLE) {
            final java.text.Collator by = java.text.Collator.getInstance(Locale.getDefault());
            java.util.Collections.sort(shown, new java.util.Comparator<Library.Entry>() {
                @Override
                public int compare(Library.Entry a, Library.Entry b) {
                    return by.compare(titleOf(a), titleOf(b));
                }
            });
        }
        if (shown.isEmpty()) {
            TextView none = Ui.text(this, getString(order == Prefs.SHELF_READ ? R.string.shelf_none_read
                    : R.string.shelf_empty), 15, Palette.MUTED);
            none.setLineSpacing(0, 1.25f);
            LinearLayout.LayoutParams ep = Ui.lp(Ui.MATCH, Ui.WRAP);
            ep.topMargin = dp(20);
            list.addView(none, ep);
        } else {
            LinearLayout plate = Kit.plate(this);
            plate.setPadding(0, dp(4), 0, dp(4));
            for (Library.Entry e : shown) {
                plate.addView(listRow(e, e.uri.equals(current), narrator), Ui.lp(Ui.MATCH, Ui.WRAP));
            }
            LinearLayout.LayoutParams pp = Ui.lp(Ui.MATCH, Ui.WRAP);
            pp.topMargin = dp(20);
            list.addView(plate, pp);
        }
        LinearLayout tail = Ui.row(this);
        tail.setGravity(Gravity.END);
        tail.addView(addLink());
        View hint = folderHint();
        if (hint != null) {
            tail.addView(hint);
        }
        LinearLayout.LayoutParams tp = Ui.lp(Ui.MATCH, Ui.WRAP);
        tp.topMargin = dp(8);
        list.addView(tail, tp);
    }

    /** A book as a row: small cover, title, author and state, thread; the face of the reader on the one heard now. */
    private View listRow(final Library.Entry e, boolean now, int narrator) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(12), dp(16), dp(12));
        r.setBackground(Ui.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)));
        r.setClickable(true);
        r.setOnClickListener(touch(e, now));
        r.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                bookSheet(e);
                return true;
            }
        });
        Cover cover = new Cover(this);
        cover.set(titleOf(e), e.author);
        r.addView(cover, Ui.lp(dp(48), Ui.WRAP));
        LinearLayout words = Ui.column(this);
        TextView name = Ui.title(this, titleOf(e), 21);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(name);
        String who = e.author == null || e.author.isEmpty() ? "" : Book.shortName(e.author) + " \u00b7 ";
        TextView sub = Ui.text(this, marked(e, who), 13, Palette.MUTED);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sp = Ui.lp(Ui.MATCH, Ui.WRAP);
        sp.topMargin = dp(2);
        words.addView(sub, sp);
        Kit.Bar thread = new Kit.Bar(this);
        thread.set(e.total <= 0 ? 0f : Math.min(1f, (e.index + 1) / (float) e.total));
        LinearLayout.LayoutParams bp = Ui.lp(Ui.MATCH, dp(3));
        bp.topMargin = dp(8);
        words.addView(thread, bp);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        wp.leftMargin = dp(14);
        r.addView(words, wp);
        if (now) {
            ImageView face = Ui.portrait(this, narrator);
            face.setPadding(dp(3), dp(3), dp(3), dp(3));
            face.setBackground(Ui.oval(Palette.SURFACE, Palette.ACCENT_TEXT, 2, this));
            LinearLayout.LayoutParams fp = Ui.lp(dp(36), dp(36));
            fp.leftMargin = dp(14);
            r.addView(face, fp);
        }
        r.setContentDescription(titleOf(e) + ", " + state(e));
        return r;
    }

    /** A touch on a book: the one heard now goes to the book screen; another opens and reads. */
    private View.OnClickListener touch(final Library.Entry e, final boolean now) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!now) {
                    BookActivity.open(ShelfActivity.this, e.uri, e.name, true);
                }
                startActivity(new Intent(ShelfActivity.this, BookActivity.class));
            }
        };
    }

    private static String titleOf(Library.Entry e) {
        if (e.title != null && !e.title.isEmpty()) {
            return e.title;
        }
        String n = e.name == null ? "" : e.name;
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** "Chapter six · 24 %", or only the percent in a book without chapters. */
    private String where(Library.Entry e) {
        int c = e.chapterAt(e.index);
        String pct = e.percent() + "\u00a0%";
        return c < 0 ? pct : e.chapters.get(c).label + " \u00b7 " + pct;
    }

    /** "73 %", "new" or "read" under a cover. */
    private String state(Library.Entry e) {
        if (finished(e)) {
            return getString(R.string.shelf_read);
        }
        if (e.index <= 0) {
            return getString(R.string.shelf_new);
        }
        return e.percent() + "\u00a0%";
    }

    /** The book being heard: its cover, where it is, who reads it, and the one main button of the shelf. */
    private View nowCard(final Library.Entry e, boolean playing, int narrator) {
        LinearLayout card = Ui.row(this);
        card.setGravity(Gravity.TOP);
        card.setBackground(Ui.pressable(Ui.round(Palette.SURFACE, 24, this)));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ShelfActivity.this, BookActivity.class));
            }
        });
        Cover cover = new Cover(this);
        cover.set(titleOf(e), e.author);
        card.addView(cover, Ui.lp(dp(96), Ui.WRAP));

        LinearLayout side = Ui.column(this);
        TextView label = Ui.text(this, getString(R.string.shelf_now).toUpperCase(Locale.getDefault()), 11,
                Palette.ACCENT_TEXT);
        label.setTypeface(Palette.bodyStrong(this));
        label.setLetterSpacing(0.12f);
        side.addView(label);
        TextView name = Ui.title(this, titleOf(e), 24);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = Ui.lp(Ui.MATCH, Ui.WRAP);
        tp.topMargin = dp(6);
        side.addView(name, tp);
        TextView place = Ui.text(this, where(e), 13, Palette.MUTED);
        LinearLayout.LayoutParams pp = Ui.lp(Ui.MATCH, Ui.WRAP);
        pp.topMargin = dp(6);
        side.addView(place, pp);

        LinearLayout who = Ui.row(this);
        who.setGravity(Gravity.CENTER_VERTICAL);
        ImageView face = Ui.portrait(this, narrator);
        who.addView(face, Ui.lp(dp(32), dp(32)));
        TextView reader = Ui.text(this, Cast.name(this, narrator), 13, Palette.MUTED);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rp.leftMargin = dp(10);
        who.addView(reader, rp);
        play = Ui.iconButton(this, playing ? R.drawable.ic_pause : R.drawable.ic_play, Palette.ACCENT, 52,
                getString(playing ? R.string.shelf_pause : R.string.shelf_continue));
        play.setImageTintList(ColorStateList.valueOf(Palette.ON_ACCENT));
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startForegroundService(BookPlayer.intent(ShelfActivity.this, BookPlayer.ACTION_TOGGLE));
            }
        });
        who.addView(play, Ui.lp(dp(52), dp(52)));
        LinearLayout.LayoutParams wp = Ui.lp(Ui.MATCH, Ui.WRAP);
        wp.topMargin = dp(12);
        side.addView(who, wp);

        // The words decide the card's height, never the cover: the reader and the button are never cut.
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sp.leftMargin = dp(16);
        card.addView(side, sp);
        return card;
    }

    /** A book in the grid: cover, title, thread and percent. Touch continues it; holding offers the sheet. */
    private View card(final Library.Entry e) {
        LinearLayout c = Ui.column(this);
        c.setClickable(true);
        c.setBackground(Ui.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)));
        c.setOnClickListener(touch(e, false));
        c.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                bookSheet(e);
                return true;
            }
        });
        Cover cover = new Cover(this);
        cover.set(titleOf(e), e.author);
        c.addView(cover, Ui.lp(Ui.MATCH, Ui.WRAP));
        TextView name = Ui.title(this, titleOf(e), 19);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams np = Ui.lp(Ui.MATCH, Ui.WRAP);
        np.topMargin = dp(8);
        c.addView(name, np);
        LinearLayout line = Ui.row(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        Kit.Bar thread = new Kit.Bar(this);
        thread.set(e.total <= 0 ? 0f : Math.min(1f, (e.index + 1) / (float) e.total));
        line.addView(thread, new LinearLayout.LayoutParams(0, dp(3), 1f));
        TextView st = Ui.text(this, marked(e, ""), 12, Palette.MUTED);
        LinearLayout.LayoutParams sp = Ui.lp(Ui.WRAP, Ui.WRAP);
        sp.leftMargin = dp(8);
        line.addView(st, sp);
        LinearLayout.LayoutParams lp = Ui.lp(Ui.MATCH, Ui.WRAP);
        lp.topMargin = dp(8);
        c.addView(line, lp);
        c.setContentDescription(titleOf(e) + ", " + state(e));
        return c;
    }

    /** Holding a cover: take the book off the shelf, or read about it. */
    private void bookSheet(final Library.Entry e) {
        final Dialog[] sheet = new Dialog[1];
        LinearLayout more = Ui.column(this);
        if (Books.isTemporary(this, e.uri)) {
            // A temporary book can be kept for good: it moves to the books with its place.
            more.addView(Kit.secondary(this, getString(R.string.shelf_keep), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sheet[0].dismiss();
                    final android.content.Context app = getApplicationContext();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Books.keepForGood(app, e.uri);
                            } catch (java.io.IOException | RuntimeException ex) {
                                Diag.log(app, "shelf: a temporary book not kept", ex);
                            }
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    drawn = "";
                                    show();
                                }
                            });
                        }
                    }, "shelf-keep").start();
                }
            }));
        }
        more.addView(Kit.secondary(this, getString(R.string.export_make), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sheet[0].dismiss();
                startActivity(new Intent(ShelfActivity.this, ExportActivity.class)
                        .putExtra(ExportActivity.EXTRA_URI, e.uri).putExtra(ExportActivity.EXTRA_NAME, e.name)
                        .putExtra(ExportActivity.EXTRA_TITLE, titleOf(e))
                        .putExtra(ExportActivity.EXTRA_READER, e.reader > 0 ? e.reader : Prefs.role(ShelfActivity.this, Cast.NARRATOR)));
            }
        }));
        more.addView(Kit.secondary(this, getString(R.string.shelf_about), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sheet[0].dismiss();
                aboutSheet(e);
            }
        }));
        sheet[0] = Kit.sheet(this, titleOf(e), null, getString(R.string.shelf_remove), new Runnable() {
            @Override
            public void run() {
                // Hora's own copy goes with the book; a book that only points elsewhere just leaves the shelf.
                if (Books.owns(ShelfActivity.this, e.uri)) {
                    Books.delete(ShelfActivity.this, e.uri);
                }
                Library.remove(ShelfActivity.this, e.uri);
                drawn = "";
                show();
            }
        }, more);
    }

    private void aboutSheet(Library.Entry e) {
        LinearLayout lines = Ui.column(this);
        String opened = e.opened <= 0 ? "" : DateFormat.getDateInstance(DateFormat.LONG).format(new Date(e.opened));
        String[] rows = {
            getString(R.string.shelf_about_file, e.name),
            e.author == null || e.author.isEmpty() ? "" : getString(R.string.shelf_about_author, e.author),
            getString(R.string.shelf_about_size, getResources().getQuantityString(R.plurals.shelf_paragraphs, e.total, e.total),
                    getResources().getQuantityString(R.plurals.book_chapters, e.chapters.size(), e.chapters.size())),
            getString(R.string.shelf_about_place, where(e)),
            getString(R.string.shelf_about_reader, Cast.name(this, e.reader <= 0 ? 1 : e.reader)),
            opened.isEmpty() ? "" : getString(R.string.shelf_about_opened, opened),
        };
        for (String r : rows) {
            if (r.isEmpty()) {
                continue;
            }
            TextView t = Ui.text(this, r, 15, Palette.INK);
            t.setLineSpacing(0, 1.25f);
            t.setPadding(0, dp(4), 0, dp(4));
            lines.addView(t);
        }
        Kit.sheet(this, titleOf(e), lines, null, null);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == HoraFolder.PICK && result == RESULT_OK) {
            if (HoraFolder.accept(this, data)) {
                tidy(true);
                RestoreActivity.offerIfFresh(this);
            }
            return;
        }
        if (request != PICK_BOOK || result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        // Chosen here, it is a book whatever its format: copied into the books and read.
        startActivity(new Intent(Intent.ACTION_VIEW, data.getData()).setClass(this, ShelveActivity.class)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(ShelveActivity.EXTRA_PLAY, true).putExtra(ShelveActivity.EXTRA_KEEP, true));
    }
}
