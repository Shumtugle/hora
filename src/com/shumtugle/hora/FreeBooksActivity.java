package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Finding a book in the free library: a word, the works and authors that match, or a walk
 * through its catalogue (authors, genres, chosen works), and one work put on the shelf. Opened
 * with a page, the screen shows that page as a list: an author's works, a category's parts.
 * Putting a long work together takes a while, so it runs under the waiting heart with one bar
 * and a way to stop; the finished book opens on its own screen, ready to play.
 */
public final class FreeBooksActivity extends Activity {
    /** A page to show as a list, its language, and whether it is a category. */
    static final String EXTRA_PAGE = "page";
    static final String EXTRA_LANG = "lang";
    static final String EXTRA_CATEGORY = "category";
    private static final String PREF_LANG = "library_lang";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String page;
    private String lang;
    private boolean category;
    private String more;
    private LinearLayout lists;
    private LinearLayout doors;
    private TextView moreLink;
    /** A category's plates by kind, so its next batch joins them instead of repeating the headings. */
    private final java.util.Map<Integer, LinearLayout> plates = new java.util.HashMap<Integer, LinearLayout>();
    private long lastTap;
    /** Every library screen open now: when a book is shelved, all of them close behind it. */
    private static final java.util.List<java.lang.ref.WeakReference<FreeBooksActivity>> OPEN =
            new java.util.ArrayList<java.lang.ref.WeakReference<FreeBooksActivity>>();
    private int built;
    private EditText field;
    private TextView state;
    private TextView note;
    /** Only the last search answers; an older one coming late is dropped. */
    private int asked;
    private String shown = "";

    private FrameLayout overlay;
    private View fill;
    private View rest;
    private TextView stage;
    /** The putting together now running; a stopped one is told by a newer number. */
    private volatile int gathering;
    private boolean working;
    private boolean resumed;
    private float shownPart;
    /** A book finished while Hora was away: opened when it is back. */
    private String[] pending;

    private final Runnable look = new Runnable() {
        @Override
        public void run() {
            search(field.getText().toString());
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        built = Ui.prepare(this);
        // The keyboard comes only when the field is touched.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        OPEN.add(new java.lang.ref.WeakReference<FreeBooksActivity>(this));
        page = getIntent().getStringExtra(EXTRA_PAGE);
        lang = getIntent().getStringExtra(EXTRA_LANG);
        category = getIntent().getBooleanExtra(EXTRA_CATEGORY, false);
        if (lang == null) {
            lang = getSharedPreferences("hora", MODE_PRIVATE).getString(PREF_LANG, "ru");
        }
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Palette.BG);
        root.setPadding(dp(Kit.PAD), dp(32), dp(Kit.PAD), 0);
        root.addView(Ui.header(this, page == null ? getString(R.string.library_title) : FreeBooks.bare(page), false),
                Ui.lp(Ui.MATCH, dp(48)));

        LinearLayout box = Ui.row(this);
        box.setBackground(Ui.round(Palette.SURFACE, 28, this));
        box.setPadding(dp(20), 0, dp(12), 0);
        ImageView lens = new ImageView(this);
        lens.setImageResource(R.drawable.ic_search);
        lens.setImageTintList(ColorStateList.valueOf(Palette.MUTED));
        lens.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(lens, Ui.lp(dp(20), dp(20)));
        field = new EditText(this);
        field.setHint(R.string.library_hint);
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setTextColor(Palette.INK);
        field.setHintTextColor(Palette.HINT);
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17);
        field.setTypeface(Palette.body(this));
        field.setBackground(null);
        field.setPadding(dp(10), 0, 0, 0);
        Ui.field(field);
        box.addView(field, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams bp = Ui.lp(Ui.MATCH, dp(56));
        bp.topMargin = dp(20);
        if (page == null) {
            root.addView(box, bp);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout list = Ui.column(this);
        list.setPadding(0, dp(12), 0, dp(40));
        state = Ui.text(this, "", 13, Palette.MUTED);
        list.addView(state);
        lists = Ui.column(this);
        list.addView(lists);
        moreLink = Kit.link(this, getString(R.string.library_more), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadList(more);
            }
        });
        moreLink.setVisibility(View.GONE);
        list.addView(moreLink, Kit.below(this, 8));
        if (page == null) {
            doors = doors();
            list.addView(doors, Kit.below(this, 4));
        }
        note = Ui.text(this, getString(R.string.library_note), 12, Palette.HINT);
        note.setLineSpacing(0, 1.3f);
        note.setPadding(dp(6), dp(12), dp(6), 0);
        list.addView(note);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        setContentView(root);
        buildOverlay();
        if (page != null) {
            note.setVisibility(View.GONE);
            loadList(null);
            return;
        }

        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                handler.removeCallbacks(look);
                // An empty field gives the doors back.
                boolean blank = e.toString().trim().isEmpty();
                doors.setVisibility(blank ? View.VISIBLE : View.GONE);
                if (blank) {
                    lists.removeAllViews();
                    state.setText("");
                    shown = "";
                    asked++;
                }
                // Each search is a request to the library: wait until the typing pauses.
                handler.postDelayed(look, 700);
            }
        });
        field.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int id, android.view.KeyEvent e) {
                handler.removeCallbacks(look);
                look.run();
                return false;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (pending != null) {
            String[] p = pending;
            pending = null;
            openBook(p[0], p[1]);
            return;
        }
        // A changed look rebuilds the screen, but not under a book being put together.
        if (!working) {
            Ui.stale(this, built);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(look);
        for (java.util.Iterator<java.lang.ref.WeakReference<FreeBooksActivity>> i = OPEN.iterator(); i.hasNext();) {
            FreeBooksActivity a = i.next().get();
            if (a == null || a == this) {
                i.remove();
            }
        }
        // Leaving the screen stops the work; a book half gathered is not put on the shelf.
        gathering++;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (working) {
            stopGathering();
            return;
        }
        super.onBackPressed();
    }

    /** Stops at once for the person; the work notices between two pages and its result is dropped. */
    private void stopGathering() {
        gathering++;
        working = false;
        overlay.setVisibility(View.GONE);
        state.setText(R.string.library_stopped);
        Diag.mark(this, "library: stopped by the person");
    }

    private boolean gone() {
        return isFinishing() || isDestroyed();
    }

    private void openBook(String uri, String name) {
        BookActivity.open(this, uri, name, false);
        startActivity(new Intent(this, BookActivity.class));
        // Back from the book goes to the shelf, not down every list that led to it.
        for (java.lang.ref.WeakReference<FreeBooksActivity> r : new java.util.ArrayList<
                java.lang.ref.WeakReference<FreeBooksActivity>>(OPEN)) {
            FreeBooksActivity a = r.get();
            if (a != null && a != this && !a.isFinishing()) {
                a.finish();
            }
        }
        OPEN.clear();
        finish();
    }

    /** A second touch right after the first opens nothing twice. */
    private boolean tapTooSoon() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTap < 800) {
            return true;
        }
        lastTap = now;
        return false;
    }

    private void search(String text) {
        final String q = text.trim();
        if (q.length() < 2 || q.equals(shown)) {
            return;
        }
        shown = q;
        final int mine = ++asked;
        state.setText(R.string.library_searching);
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<FreeBooks.Found> found = null;
                try {
                    found = FreeBooks.search(FreeBooksActivity.this, q);
                } catch (IOException | RuntimeException e) {
                    Diag.log(FreeBooksActivity.this, "library: search failed", e);
                }
                final List<FreeBooks.Found> got = found;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine == asked && !gone()) {
                            show(got);
                        }
                    }
                });
            }
        }, "library-search").start();
    }

    private void show(List<FreeBooks.Found> found) {
        lists.removeAllViews();
        if (found == null) {
            // A failed search may be asked again with the same words.
            shown = "";
            state.setText(R.string.library_offline);
            return;
        }
        if (found.isEmpty()) {
            state.setText(R.string.library_none);
            return;
        }
        state.setText("");
        // Authors under their own heading above the works, as two short lists.
        FreeBooks.Listing split = new FreeBooks.Listing();
        FreeBooks.Section people = new FreeBooks.Section();
        people.name = getString(R.string.library_authors);
        FreeBooks.Section works = new FreeBooks.Section();
        works.name = getString(R.string.library_works);
        for (FreeBooks.Found f : found) {
            (f.kind == FreeBooks.AUTHOR ? people : works).items.add(f);
        }
        for (FreeBooks.Section s : new FreeBooks.Section[] {people, works}) {
            if (!s.items.isEmpty()) {
                split.sections.add(s);
            }
        }
        lists.removeAllViews();
        showList(split, false);
    }

    /** The three ways into the catalogue, with the language they lead into. */
    private LinearLayout doors() {
        LinearLayout box = Ui.column(this);
        box.addView(Kit.segments(this, new String[] {getString(R.string.library_lang_ru),
                getString(R.string.library_lang_en)}, "en".equals(lang) ? 1 : 0, new Kit.Chosen() {
                    @Override
                    public void chose(int which) {
                        lang = which == 1 ? "en" : "ru";
                        getSharedPreferences("hora", MODE_PRIVATE).edit().putString(PREF_LANG, lang).apply();
                    }
                }), Kit.wide());
        box.addView(Kit.section(this, getString(R.string.library_doors)), Kit.below(this, 12));
        LinearLayout plate = Kit.plate(this);
        String[] keys = {"authors", "genres", "featured"};
        int[] icons = {R.drawable.ic_voices, R.drawable.ic_contents, R.drawable.ic_book};
        int[][] words = {{R.string.library_door_authors, R.string.library_door_authors_sub},
            {R.string.library_door_genres, R.string.library_door_genres_sub},
            {R.string.library_door_featured, R.string.library_door_featured_sub}};
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            if (i > 0) {
                plate.addView(Kit.divider(this));
            }
            plate.addView(Kit.rowEntry(this, icons[i], getString(words[i][0]), getString(words[i][1]),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openDoor(key);
                        }
                    }));
        }
        box.addView(plate, Kit.wide());
        return box;
    }

    private void openDoor(final String key) {
        if (tapTooSoon()) {
            return;
        }
        final String in = lang;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String target = null;
                boolean cat = false;
                try {
                    target = FreeBooks.door(FreeBooksActivity.this, in, key);
                    cat = target != null && FreeBooks.isCategory(FreeBooksActivity.this, in, target);
                } catch (IOException | RuntimeException e) {
                    Diag.log(FreeBooksActivity.this, "library: a door could not be opened", e);
                }
                final String t = target;
                final boolean isCat = cat;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gone()) {
                            return;
                        }
                        if (t == null) {
                            state.setText(R.string.library_empty);
                            return;
                        }
                        listOf(t, in, isCat);
                    }
                });
            }
        }, "library-door").start();
    }

    /** Opens a page of the catalogue as its own list, one step deeper. */
    private void listOf(String target, String in, boolean cat) {
        startActivity(new Intent(this, FreeBooksActivity.class).putExtra(EXTRA_PAGE, target)
                .putExtra(EXTRA_LANG, in).putExtra(EXTRA_CATEGORY, cat));
    }

    /** Reads this screen's page (or the next part of its category) and lays its sections out. */
    private void loadList(final String from) {
        state.setText(R.string.library_opening);
        moreLink.setVisibility(View.GONE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                FreeBooks.Listing got = null;
                try {
                    got = category ? FreeBooks.category(FreeBooksActivity.this, lang, page, from)
                            : FreeBooks.page(FreeBooksActivity.this, lang, page);
                } catch (IOException | RuntimeException e) {
                    Diag.log(FreeBooksActivity.this, "library: a list could not be read", e);
                }
                final FreeBooks.Listing listing = got;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!gone()) {
                            showList(listing, from != null);
                        }
                    }
                });
            }
        }, "library-list").start();
    }

    private void showList(FreeBooks.Listing listing, boolean adding) {
        if (listing == null) {
            state.setText(R.string.library_offline);
            if (adding) {
                moreLink.setVisibility(View.VISIBLE);
            }
            return;
        }
        if (!adding && listing.sections.isEmpty()) {
            state.setText(R.string.library_empty);
            return;
        }
        state.setText("");
        for (FreeBooks.Section s : listing.sections) {
            int kind = s.items.isEmpty() ? FreeBooks.WORK : s.items.get(0).kind;
            LinearLayout joined = category ? plates.get(kind) : null;
            if (joined != null) {
                // The next batch of a category joins its plate of the same kind.
                for (FreeBooks.Found f : s.items) {
                    joined.addView(Kit.divider(this));
                    joined.addView(row(f));
                }
                continue;
            }
            String name = s.name;
            if (category && (name == null || name.isEmpty()) && !s.items.isEmpty()) {
                name = getString(kind == FreeBooks.CATEGORY ? R.string.library_sections
                        : kind == FreeBooks.AUTHOR ? R.string.library_authors : R.string.library_works);
            }
            if (name != null && !name.isEmpty()) {
                lists.addView(Kit.section(this, name), Kit.below(this, 12));
            }
            LinearLayout plate = Kit.plate(this);
            for (int i = 0; i < s.items.size(); i++) {
                if (i > 0) {
                    plate.addView(Kit.divider(this));
                }
                plate.addView(row(s.items.get(i)));
            }
            lists.addView(plate, Kit.below(this, 8));
            if (category) {
                plates.put(kind, plate);
            }
        }
        more = listing.more;
        moreLink.setVisibility(more == null ? View.GONE : View.VISIBLE);
    }

    private View row(final FreeBooks.Found f) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(64));
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout words = Ui.column(this);
        TextView t = Ui.text(this, f.title, 17, Palette.INK);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(t);
        String quiet = f.kind == FreeBooks.AUTHOR ? getString(R.string.library_author_line) : f.by;
        final TextView s = Ui.text(this, quiet, 13, Palette.MUTED);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        s.setVisibility(quiet.isEmpty() ? View.GONE : View.VISIBLE);
        words.addView(s);
        r.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams cp = Ui.lp(Ui.WRAP, Ui.WRAP);
        cp.leftMargin = dp(12);
        r.addView(Ui.text(this, "›", 20, Palette.HINT), cp);
        r.setBackground(Ui.pressable(new android.graphics.drawable.ColorDrawable(0)));
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tapTooSoon()) {
                    return;
                }
                if (f.kind == FreeBooks.WORK) {
                    open(f, s);
                } else {
                    listOf(f.page, f.lang, f.kind == FreeBooks.CATEGORY);
                }
            }
        });
        return r;
    }

    /** Reads the work's front page for its author and year, then offers it for the shelf. */
    private void open(final FreeBooks.Found f, final TextView sub) {
        if (working) {
            return;
        }
        final CharSequence was = sub.getText();
        final int wasShown = sub.getVisibility();
        sub.setText(R.string.library_opening);
        sub.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                FreeBooks.Work w = null;
                try {
                    w = FreeBooks.about(FreeBooksActivity.this, f);
                } catch (IOException | RuntimeException e) {
                    Diag.log(FreeBooksActivity.this, "library: a work could not be read", e);
                }
                final FreeBooks.Work work = w;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gone()) {
                            return;
                        }
                        sub.setText(was);
                        sub.setVisibility(wasShown);
                        if (work == null) {
                            state.setText(R.string.library_offline);
                            return;
                        }
                        sheet(work);
                    }
                });
            }
        }, "library-about").start();
    }

    private void sheet(final FreeBooks.Work w) {
        LinearLayout box = Ui.row(this);
        Cover cover = new Cover(this);
        cover.set(w.title, w.author);
        box.addView(cover, Ui.lp(dp(104), dp(148)));
        LinearLayout words = Ui.column(this);
        words.addView(Ui.title(this, w.title, 24));
        if (!w.author.isEmpty()) {
            words.addView(Ui.text(this, w.author, 16, Palette.MUTED), Kit.below(this, 8));
        }
        if (!w.year.isEmpty()) {
            words.addView(Ui.text(this, w.year, 14, Palette.HINT), Kit.below(this, 4));
        }
        TextView source = Ui.text(this, getString(R.string.library_source), 12, Palette.HINT);
        source.setLineSpacing(0, 1.3f);
        words.addView(source, Kit.below(this, 16));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        wp.leftMargin = dp(16);
        box.addView(words, wp);
        Kit.sheet(this, null, box, getString(R.string.library_shelve), new Runnable() {
            @Override
            public void run() {
                gather(w);
            }
        });
    }

    /** Puts the work together under the waiting heart, then shelves it and opens its screen. */
    private void gather(final FreeBooks.Work w) {
        if (working || gone()) {
            return;
        }
        working = true;
        final int mine = ++gathering;
        shownPart = 0f;
        step(0, 0);
        overlay.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final android.content.Context app = getApplicationContext();
                Uri kept = null;
                String name = null;
                boolean cut = false;
                int missing = 0;
                try {
                    FreeBooks.Made made = FreeBooks.assemble(app, w, new FreeBooks.Progress() {
                        @Override
                        public void step(final int done, final int total) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (mine == gathering && !gone()) {
                                        FreeBooksActivity.this.step(done, total);
                                    }
                                }
                            });
                        }

                        @Override
                        public boolean stopped() {
                            return mine != gathering;
                        }
                    });
                    File f = made.file;
                    if (mine != gathering) {
                        f.delete();
                        throw new FreeBooks.Stopped();
                    }
                    cut = made.cut;
                    missing = made.missing;
                    name = f.getName();
                    kept = Books.keep(app, Uri.fromFile(f), name, false);
                    if (!f.delete()) {
                        f.deleteOnExit();
                    }
                } catch (FreeBooks.Stopped e) {
                    return;
                } catch (IOException | RuntimeException e) {
                    Diag.log(app, "library: a book could not be put together", e);
                }
                final Uri book = kept;
                final String file = name;
                final boolean shortened = cut;
                final int lost = missing;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine != gathering || gone()) {
                            return;
                        }
                        working = false;
                        overlay.setVisibility(View.GONE);
                        if (book == null) {
                            state.setText(R.string.library_failed);
                            return;
                        }
                        Diag.mark(FreeBooksActivity.this, "shelf: a book from the library kept");
                        if (shortened || lost > 0) {
                            android.widget.Toast.makeText(FreeBooksActivity.this, shortened
                                    ? getString(R.string.library_cut) : getString(R.string.library_missing, lost),
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                        // A foreground service may not start while Hora is away: the book waits for its return.
                        if (resumed) {
                            openBook(book.toString(), file);
                        } else {
                            pending = new String[] {book.toString(), file};
                        }
                    }
                });
            }
        }, "library-book").start();
    }

    private void step(int done, int total) {
        // Contents found on the way add parts; the bar never steps back for them.
        float part = Math.max(shownPart, total > 0 ? Math.min(1f, done / (float) total) : 0f);
        shownPart = part;
        ((LinearLayout.LayoutParams) fill.getLayoutParams()).weight = part;
        ((LinearLayout.LayoutParams) rest.getLayoutParams()).weight = 1f - part;
        fill.requestLayout();
        stage.setText(total > 0 ? getResources().getQuantityString(R.plurals.library_parts, total, done, total)
                : getString(R.string.library_starting));
    }

    private void buildOverlay() {
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor((Palette.BG & 0x00FFFFFF) | 0xF2000000);
        overlay.setClickable(true);
        LinearLayout col = Ui.column(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(32), 0, dp(32), 0);
        overlay.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        col.addView(new HeartGears(this), Ui.lp(dp(150), dp(150)));
        TextView title = Ui.title(this, getString(R.string.library_gathering), 26);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = Ui.lp(Ui.WRAP, Ui.WRAP);
        tp.topMargin = dp(20);
        col.addView(title, tp);
        TextView ask = Ui.text(this, getString(R.string.library_wait), 15, Palette.MUTED);
        ask.setGravity(Gravity.CENTER);
        ask.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams ap = Ui.lp(Ui.WRAP, Ui.WRAP);
        ap.topMargin = dp(10);
        col.addView(ask, ap);
        LinearLayout bar = Ui.row(this);
        bar.setBackground(Ui.round(Palette.LINE, 3, this));
        fill = new View(this);
        fill.setBackground(Ui.round(Palette.ACCENT, 3, this));
        rest = new View(this);
        bar.addView(fill, new LinearLayout.LayoutParams(0, -1, 0f));
        bar.addView(rest, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams barp = Ui.lp(Ui.MATCH, dp(6));
        barp.topMargin = dp(28);
        col.addView(bar, barp);
        stage = Ui.text(this, "", 14, Palette.INK);
        stage.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = Ui.lp(Ui.WRAP, Ui.WRAP);
        sp.topMargin = dp(12);
        col.addView(stage, sp);
        TextView cancel = Kit.link(this, getString(R.string.lang_cancel), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopGathering();
            }
        });
        LinearLayout.LayoutParams cp = Ui.lp(Ui.WRAP, Ui.WRAP);
        cp.topMargin = dp(24);
        col.addView(cancel, cp);
        overlay.setVisibility(View.GONE);
        ((android.view.ViewGroup) getWindow().getDecorView()).addView(overlay,
                new android.view.ViewGroup.LayoutParams(-1, -1));
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
