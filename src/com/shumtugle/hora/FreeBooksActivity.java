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
 * Finding a free book: a word, the works and authors that match, or a walk through a catalogue,
 * and one book put on the shelf. Two libraries answer: the free library in both languages, and
 * for words in Latin letters the edited editions too, shown first, each with its cover. Opened
 * with a page, the screen shows that page as a list: an author's works, a category's parts;
 * opened for the edited editions, it shows their latest books, their authors, or one author's.
 * Putting a long work together takes a while, so it runs under the waiting heart with one bar
 * and a way to stop; the finished book opens on its own screen, ready to play.
 */
public final class FreeBooksActivity extends Activity {
    /** A page to show as a list, its language, and whether it is a category. */
    static final String EXTRA_PAGE = "page";
    static final String EXTRA_LANG = "lang";
    static final String EXTRA_CATEGORY = "category";
    /** Which list of the edited editions to show (the latest, the authors, one author's), and the author. */
    static final String EXTRA_EDITED = "edited";
    static final String EXTRA_AUTHOR = "author";
    private static final String EDITED_LATEST = "latest";
    private static final String EDITED_AUTHORS = "authors";
    private static final String EDITED_BY = "by";
    private static final String PREF_LANG = "library_lang";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String page;
    private String lang;
    private boolean category;
    private String edited;
    private String editedAuthor;
    /** Covers and descriptions are fetched here, two at a time, for the rows on screen. */
    private final java.util.concurrent.ExecutorService loader = java.util.concurrent.Executors.newFixedThreadPool(2);
    private LinearLayout doorsBody;
    /** Counts the lists laid out: work for rows of a list already gone is not started. */
    private volatile int rowsShown;
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
        edited = getIntent().getStringExtra(EXTRA_EDITED);
        editedAuthor = getIntent().getStringExtra(EXTRA_AUTHOR);
        if (lang == null) {
            lang = getSharedPreferences("hora", MODE_PRIVATE).getString(PREF_LANG, "ru");
        }
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Palette.BG);
        root.setPadding(dp(Kit.PAD), dp(32), dp(Kit.PAD), 0);
        root.addView(Ui.header(this, heading(), false), Ui.lp(Ui.MATCH, dp(48)));
        if (edited != null) {
            TextView lead = Ui.text(this, getString(EDITED_BY.equals(edited) || EDITED_AUTHORS.equals(edited)
                    ? R.string.edited_authors_line : R.string.edited_latest_line), 14, Palette.MUTED);
            LinearLayout.LayoutParams lp = Ui.lp(Ui.MATCH, Ui.WRAP);
            lp.topMargin = dp(12);
            root.addView(lead, lp);
        }

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
        if (searching()) {
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
        if (searching()) {
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
        if (edited != null) {
            note.setVisibility(View.GONE);
            loadEdited();
            return;
        }
        if (page != null) {
            note.setVisibility(View.GONE);
            loadList(null);
            return;
        }
        showNote();

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
                    rowsShown++;
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
        loader.shutdownNow();
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

    /** The screen's title: the free books, a page of the catalogue, or a list of the edited editions. */
    private String heading() {
        if (EDITED_LATEST.equals(edited)) {
            return getString(R.string.edited_latest);
        }
        if (EDITED_AUTHORS.equals(edited)) {
            return getString(R.string.edited_all);
        }
        if (EDITED_BY.equals(edited)) {
            return editedAuthor == null ? "" : editedAuthor;
        }
        return page == null ? getString(R.string.library_title) : FreeBooks.bare(page);
    }

    /** Whether this is the first screen, with the field and the doors. */
    private boolean searching() {
        return page == null && edited == null;
    }

    /** The quiet words at the foot: what is offered, and in English what the edited editions are. */
    private void showNote() {
        String t = getString(R.string.library_note);
        note.setText("en".equals(lang) ? t + "\n" + getString(R.string.edited_note) : t);
    }

    private void search(String text) {
        final String q = text.trim();
        if (q.length() < 2 || q.equals(shown)) {
            return;
        }
        shown = q;
        final int mine = ++asked;
        // Words in Latin letters ask the edited editions too; their list may have to come first.
        final boolean english = "en".equals(FreeBooks.edition(q));
        state.setText(english && !Edited.ready() ? R.string.edited_reading_list : R.string.library_searching);
        final android.content.Context app = getApplicationContext();
        // The two libraries are asked side by side; the results are laid out once, when both have answered.
        final Object[] answers = new Object[2];
        final java.util.concurrent.atomic.AtomicInteger left = new java.util.concurrent.atomic.AtomicInteger(english ? 2 : 1);
        final java.util.concurrent.atomic.AtomicBoolean libraryDone = new java.util.concurrent.atomic.AtomicBoolean();
        final boolean[] laid = new boolean[1];
        final Runnable done = new Runnable() {
            @Override
            public void run() {
                if (left.decrementAndGet() > 0) {
                    return;
                }
                handler.post(new Runnable() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public void run() {
                        if (mine == asked && !gone() && !laid[0]) {
                            laid[0] = true;
                            show((List<FreeBooks.Found>) answers[0], (List<EditedText.Book>) answers[1], english);
                        }
                    }
                });
            }
        };
        if (english && !Edited.ready()) {
            // The first list of the edited editions can take a while: the other library's answer is not
            // held for it longer than a few seconds, and the line says the catalogue is still being read.
            handler.postDelayed(new Runnable() {
                @Override
                @SuppressWarnings("unchecked")
                public void run() {
                    if (mine != asked || gone() || laid[0]) {
                        return;
                    }
                    if (!libraryDone.get()) {
                        handler.postDelayed(this, 1000);
                        return;
                    }
                    laid[0] = true;
                    show((List<FreeBooks.Found>) answers[0], new java.util.ArrayList<EditedText.Book>(), english);
                    // A free library that did not answer is said first; the catalogue's wait only when it did.
                    if (answers[0] != null) {
                        state.setText(R.string.edited_still_reading);
                    }
                    // The same words may be asked again once the catalogue is here.
                    shown = "";
                }
            }, 5000);
        }
        if (english) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        answers[1] = Edited.search(app, q);
                    } catch (IOException | RuntimeException e) {
                        Diag.log(app, "edited: search failed", e);
                    }
                    done.run();
                }
            }, "edited-search").start();
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    answers[0] = FreeBooks.search(app, q);
                } catch (IOException | RuntimeException e) {
                    Diag.log(app, "library: search failed", e);
                }
                libraryDone.set(true);
                done.run();
            }
        }, "library-search").start();
    }

    /**
     * Lays the results out. In English the edited editions come first under their own heading,
     * the free library's under its name; otherwise authors above works, as two short lists.
     */
    private void show(List<FreeBooks.Found> found, List<EditedText.Book> books, boolean english) {
        lists.removeAllViews();
        rowsShown++;
        boolean someEdited = books != null && !books.isEmpty();
        boolean someFound = found != null && !found.isEmpty();
        boolean failed = found == null || english && books == null;
        if (failed) {
            // A failed search may be asked again with the same words.
            shown = "";
        }
        if (!someEdited && !someFound) {
            state.setText(!failed ? R.string.library_none
                    : found == null ? R.string.library_offline : R.string.edited_offline);
            return;
        }
        // One library failing is said in a line, after the lists: laying them out clears the line.
        final String said = !failed ? "" : getString(found == null ? R.string.library_offline : R.string.edited_offline);
        state.setText(said);
        if (someEdited) {
            lists.addView(Kit.section(this, getString(R.string.edited_title)), Kit.below(this, 12));
            lists.addView(editedPlate(books, false), Kit.below(this, 8));
        }
        if (!someFound) {
            return;
        }
        showFound(found, english && someEdited);
        state.setText(said);
    }

    /** The free library's results: as one list under its name beside the edited ones, else authors above works. */
    private void showFound(List<FreeBooks.Found> found, boolean beside) {
        FreeBooks.Listing split = new FreeBooks.Listing();
        if (beside) {
            // Beside the edited editions, the free library's results stand as one list under its name.
            FreeBooks.Section all = new FreeBooks.Section();
            all.name = getString(R.string.about_wikisource);
            all.items.addAll(found);
            split.sections.add(all);
        } else {
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
        }
        showList(split, false);
    }

    /** The language switch and, under it, the ways into the catalogues for that language. */
    private LinearLayout doors() {
        LinearLayout box = Ui.column(this);
        box.addView(Kit.segments(this, new String[] {getString(R.string.library_lang_ru),
                getString(R.string.library_lang_en)}, "en".equals(lang) ? 1 : 0, new Kit.Chosen() {
                    @Override
                    public void chose(int which) {
                        lang = which == 1 ? "en" : "ru";
                        getSharedPreferences("hora", MODE_PRIVATE).edit().putString(PREF_LANG, lang).apply();
                        fillDoors();
                        showNote();
                    }
                }), Kit.wide());
        doorsBody = Ui.column(this);
        box.addView(doorsBody, Kit.wide());
        fillDoors();
        return box;
    }

    /**
     * In English the edited editions' two doors stand first, the free library's three under its
     * name; in Russian only the free library's, as before.
     */
    private void fillDoors() {
        doorsBody.removeAllViews();
        boolean english = "en".equals(lang);
        if (english) {
            doorsBody.addView(Kit.section(this, getString(R.string.edited_title)), Kit.below(this, 12));
            LinearLayout plate = Kit.plate(this);
            plate.addView(latestDoor());
            plate.addView(Kit.divider(this));
            final LinearLayout all = Kit.rowEntry(this, R.drawable.ic_voices, getString(R.string.edited_all),
                    allLine(), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openEdited(EDITED_AUTHORS, null);
                        }
                    });
            plate.addView(all);
            doorsBody.addView(plate, Kit.wide());
            // The list is read and, when old, asked for ahead, so the first search does not wait for it.
            Edited.warm(getApplicationContext(), new Runnable() {
                @Override
                public void run() {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!gone()) {
                                ((TextView[]) all.getTag())[1].setText(allLine());
                            }
                        }
                    });
                }
            });
        }
        doorsBody.addView(Kit.section(this, getString(english ? R.string.about_wikisource : R.string.library_doors)),
                Kit.below(this, 12));
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
        doorsBody.addView(plate, Kit.wide());
    }

    /** "About 1 500, by author" once the list is here, "by author" before. */
    private String allLine() {
        int n = Edited.count();
        if (n < 100) {
            return getString(R.string.edited_all_sub_plain);
        }
        long round = Math.round(n / 100.0) * 100;
        return getString(R.string.edited_all_sub, String.format(java.util.Locale.getDefault(), "%,d", round));
    }

    /** The door to the latest books: three of their covers fanned out in place of an icon. */
    private View latestDoor() {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(64));
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        final FrameLayout fan = new FrameLayout(this);
        fan.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.addView(fan, Ui.lp(dp(52), dp(50)));
        LinearLayout words = Ui.column(this);
        TextView t = Ui.text(this, getString(R.string.edited_latest), 17, Palette.INK);
        t.setSingleLine(true);
        words.addView(t);
        TextView sub = Ui.text(this, getString(R.string.edited_latest_sub), 13, Palette.MUTED);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(sub);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        // The words start where the other doors' do, past their round icon.
        wp.leftMargin = dp(2);
        r.addView(words, wp);
        r.addView(Ui.text(this, "›", 20, Palette.HINT));
        r.setBackground(Ui.pressable(new android.graphics.drawable.ColorDrawable(0)));
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEdited(EDITED_LATEST, null);
            }
        });
        final android.content.Context app = getApplicationContext();
        // Until the covers come, and if they never do, the door has the same round icon as the others.
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_book);
        icon.setImageTintList(ColorStateList.valueOf(Palette.INK));
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setBackground(Ui.oval(Palette.RAISED, 0, 0, this));
        fan.addView(icon, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER_VERTICAL));
        loader.execute(new Runnable() {
            @Override
            public void run() {
                List<EditedText.Book> latest = new java.util.ArrayList<EditedText.Book>();
                final android.graphics.Bitmap[] pics = new android.graphics.Bitmap[3];
                try {
                    latest = Edited.latestKept(app);
                    if (latest.isEmpty()) {
                        latest = Edited.latest(app);
                    }
                    for (int i = 0; i < Math.min(3, latest.size()); i++) {
                        pics[i] = Edited.thumb(app, latest.get(i), dp(30));
                    }
                } catch (IOException | RuntimeException e) {
                    Diag.log(app, "edited: the latest not had ahead", e);
                }
                final List<EditedText.Book> three = latest.size() > 3 ? latest.subList(0, 3) : latest;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gone() || three.isEmpty() || pics[0] == null) {
                            return;
                        }
                        fan.removeAllViews();
                        for (int i = 0; i < three.size(); i++) {
                            Cover c = new Cover(FreeBooksActivity.this);
                            c.set(three.get(i).title, three.get(i).author);
                            c.setPicture(pics[i]);
                            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(30),
                                    FrameLayout.LayoutParams.WRAP_CONTENT);
                            cp.leftMargin = dp(11 * i);
                            cp.topMargin = dp(2 * (2 - i));
                            fan.addView(c, cp);
                        }
                    }
                });
            }
        });
        return r;
    }

    /** Opens a list of the edited editions on its own screen. */
    private void openEdited(String which, String author) {
        if (tapTooSoon()) {
            return;
        }
        Intent i = new Intent(this, FreeBooksActivity.class).putExtra(EXTRA_EDITED, which);
        if (author != null) {
            i.putExtra(EXTRA_AUTHOR, author);
        }
        startActivity(i);
    }

    /** Reads this screen's list of the edited editions and lays it out. */
    private void loadEdited() {
        state.setText(Edited.ready() || EDITED_LATEST.equals(edited) ? R.string.library_opening
                : R.string.edited_reading_list);
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<EditedText.Book> books = null;
                List<String[]> authors = null;
                try {
                    if (EDITED_LATEST.equals(edited)) {
                        books = Edited.latest(app);
                    } else if (EDITED_AUTHORS.equals(edited)) {
                        authors = Edited.authors(app);
                    } else {
                        books = Edited.by(app, editedAuthor == null ? "" : editedAuthor);
                    }
                } catch (IOException | RuntimeException e) {
                    Diag.log(app, "edited: a list could not be read", e);
                }
                final List<EditedText.Book> b = books;
                final List<String[]> a = authors;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gone()) {
                            return;
                        }
                        if (b == null && a == null) {
                            state.setText(R.string.edited_offline);
                            return;
                        }
                        if (b != null && b.isEmpty() || a != null && a.isEmpty()) {
                            state.setText(R.string.library_empty);
                            return;
                        }
                        state.setText("");
                        rowsShown++;
                        if (b != null) {
                            lists.addView(editedPlate(b, EDITED_LATEST.equals(edited)), Kit.below(FreeBooksActivity.this, 8));
                        } else {
                            showAuthors(a);
                        }
                    }
                });
            }
        }, "edited-list").start();
    }

    /**
     * Every author under the first letter of the name they are sorted by, with the number of their
     * books. Laid out a few dozen at a time, so a long list does not hold the screen still.
     */
    private void showAuthors(final List<String[]> authors) {
        final int mine = rowsShown;
        final LinearLayout[] plate = new LinearLayout[1];
        final String[] letter = new String[1];
        final int[] at = new int[1];
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (gone() || mine != rowsShown) {
                    return;
                }
                int end = Math.min(authors.size(), at[0] + 60);
                for (; at[0] < end; at[0]++) {
                    final String[] a = authors.get(at[0]);
                    String key = EditedText.sortName(a[0]);
                    String l = key.isEmpty() ? "#" : key.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
                    if (!l.matches("[A-Z]")) {
                        l = "#";
                    }
                    if (!l.equals(letter[0])) {
                        letter[0] = l;
                        lists.addView(Kit.section(FreeBooksActivity.this, l), Kit.below(FreeBooksActivity.this, 12));
                        plate[0] = Kit.plate(FreeBooksActivity.this);
                        lists.addView(plate[0], Kit.below(FreeBooksActivity.this, 8));
                    } else {
                        plate[0].addView(Kit.divider(FreeBooksActivity.this));
                    }
                    int n = Integer.parseInt(a[1]);
                    plate[0].addView(textRow(a[0], getResources().getQuantityString(R.plurals.edited_books, n, n),
                            new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    openEdited(EDITED_BY, a[0]);
                                }
                            }));
                }
                if (at[0] < authors.size()) {
                    handler.post(this);
                }
            }
        });
    }

    /** A plain row: a name, a quieter line, a chevron. */
    private View textRow(String title, String quiet, View.OnClickListener l) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(64));
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout words = Ui.column(this);
        TextView t = Ui.text(this, title, 17, Palette.INK);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(t);
        TextView s = Ui.text(this, quiet, 13, Palette.MUTED);
        s.setSingleLine(true);
        words.addView(s);
        r.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams cp = Ui.lp(Ui.WRAP, Ui.WRAP);
        cp.leftMargin = dp(12);
        r.addView(Ui.text(this, "›", 20, Palette.HINT), cp);
        r.setBackground(Ui.pressable(new android.graphics.drawable.ColorDrawable(0)));
        r.setOnClickListener(l);
        return r;
    }

    /** One plate of edited books, each with its cover; the latest show their genres from the feed. */
    private LinearLayout editedPlate(List<EditedText.Book> books, boolean feed) {
        LinearLayout plate = Kit.plate(this);
        for (int i = 0; i < books.size(); i++) {
            if (i > 0) {
                plate.addView(Kit.divider(this));
            }
            plate.addView(editedRow(books.get(i), feed));
        }
        return plate;
    }

    /**
     * A book of the edited editions: its cover (the paper one until the picture comes), title,
     * author, and a third line with its time aloud and genres once its description is here.
     */
    private View editedRow(final EditedText.Book b, boolean feed) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(12), dp(16), dp(12));
        final Cover cover = new Cover(this);
        cover.set(b.title, b.author);
        r.addView(cover, Ui.lp(dp(46), Ui.WRAP));
        LinearLayout words = Ui.column(this);
        TextView t = Ui.text(this, b.title, 17, Palette.INK);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(t);
        TextView by = Ui.text(this, b.author, 13, Palette.MUTED);
        by.setSingleLine(true);
        by.setEllipsize(android.text.TextUtils.TruncateAt.END);
        by.setVisibility(b.author.isEmpty() ? View.GONE : View.VISIBLE);
        words.addView(by);
        final TextView third = Ui.text(this, "", 12, Palette.HINT);
        third.setSingleLine(true);
        third.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(third);
        final String feedGenres = feed ? Edited.genres(this, b.genres) : "";
        third.setText(feedGenres);
        // The line keeps its place while it is empty, so the row does not grow when it is filled.
        third.setVisibility(feedGenres.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        wp.leftMargin = dp(14);
        r.addView(words, wp);
        LinearLayout.LayoutParams cp = Ui.lp(Ui.WRAP, Ui.WRAP);
        cp.leftMargin = dp(12);
        r.addView(Ui.text(this, "›", 20, Palette.HINT), cp);
        r.setBackground(Ui.pressable(new android.graphics.drawable.ColorDrawable(0)));
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!tapTooSoon()) {
                    openBook(b, third, feedGenres);
                }
            }
        });
        final android.content.Context app = getApplicationContext();
        final int mine = rowsShown;
        loader.execute(new Runnable() {
            @Override
            public void run() {
                // A row of a list already replaced is not worth a trip to the network.
                if (mine != rowsShown) {
                    return;
                }
                android.graphics.Bitmap got = null;
                EditedText.Package known = null;
                try {
                    known = Edited.aboutKept(app, b);
                    got = Edited.thumb(app, b, dp(46));
                    if (known == null && mine == rowsShown) {
                        known = Edited.about(app, b);
                    }
                } catch (IOException | RuntimeException e) {
                    Diag.markOnce(app, "edited-about", "edited: a description not had: " + e.getMessage());
                }
                final android.graphics.Bitmap pic = got;
                final EditedText.Package pack = known;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gone()) {
                            return;
                        }
                        if (pic != null) {
                            cover.setPicture(pic);
                        }
                        if (pack != null) {
                            third.setText(line(pack, feedGenres));
                            third.setVisibility(third.getText().length() == 0 ? View.INVISIBLE : View.VISIBLE);
                        }
                    }
                });
            }
        });
        return r;
    }

    /** "About 3 h 30 min aloud · adventure · fiction". */
    private String line(EditedText.Package p, String feedGenres) {
        String time = Edited.aloud(this, p.words);
        String genres = Edited.genres(this, p.genres);
        if (genres.isEmpty()) {
            genres = feedGenres;
        }
        return time.isEmpty() ? genres : genres.isEmpty() ? time : time + " · " + genres;
    }

    /** Reads the book's description and cover, then offers it for the shelf. */
    private void openBook(final EditedText.Book b, final TextView sub, final String feedGenres) {
        if (working) {
            return;
        }
        final CharSequence was = sub.getText();
        final int wasShown = sub.getVisibility();
        sub.setText(R.string.library_opening);
        sub.setVisibility(View.VISIBLE);
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                EditedText.Package p = null;
                try {
                    p = Edited.about(app, b);
                } catch (IOException | RuntimeException e) {
                    Diag.log(app, "edited: a book could not be read", e);
                }
                final android.graphics.Bitmap pic = p == null ? null : Edited.thumb(app, b, dp(104));
                final EditedText.Package pack = p;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gone()) {
                            return;
                        }
                        if (pack == null) {
                            sub.setText(was);
                            sub.setVisibility(wasShown);
                            state.setText(R.string.edited_offline);
                            return;
                        }
                        // The line says what is now known, not what it said before.
                        sub.setText(line(pack, feedGenres));
                        sub.setVisibility(sub.getText().length() == 0 ? View.INVISIBLE : View.VISIBLE);
                        editedSheet(b, pack, pic);
                    }
                });
            }
        }, "edited-about").start();
    }

    private void editedSheet(final EditedText.Book b, EditedText.Package p, android.graphics.Bitmap pic) {
        LinearLayout box = Ui.column(this);
        LinearLayout top = Ui.row(this);
        String title = p.title.isEmpty() ? b.title : p.title;
        String author = p.author().isEmpty() ? b.author : p.author();
        Cover cover = new Cover(this);
        cover.set(title, author);
        cover.setPicture(pic);
        top.addView(cover, Ui.lp(dp(104), Ui.WRAP));
        LinearLayout words = Ui.column(this);
        words.addView(Ui.title(this, title, 24));
        if (!author.isEmpty()) {
            words.addView(Ui.text(this, author, 16, Palette.MUTED), Kit.below(this, 8));
        }
        String time = Edited.aloud(this, p.words);
        if (!time.isEmpty()) {
            words.addView(Ui.text(this, time, 14, Palette.MUTED), Kit.below(this, 6));
        }
        String genres = Edited.genres(this, p.genres);
        if (!genres.isEmpty()) {
            words.addView(Ui.text(this, genres, 14, Palette.HINT), Kit.below(this, 4));
        }
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        wp.leftMargin = dp(16);
        top.addView(words, wp);
        box.addView(top, Kit.wide());
        String about = p.summary.isEmpty() ? b.summary : p.summary;
        if (!about.isEmpty()) {
            TextView s = Ui.text(this, about, 15, Palette.INK);
            s.setLineSpacing(0, 1.3f);
            box.addView(s, Kit.below(this, 16));
        }
        TextView source = Ui.text(this, getString(R.string.edited_source), 12, Palette.HINT);
        source.setLineSpacing(0, 1.3f);
        box.addView(source, Kit.below(this, 12));
        Kit.sheet(this, null, box, getString(R.string.library_shelve), new Runnable() {
            @Override
            public void run() {
                gather(new Maker() {
                    @Override
                    public FreeBooks.Made make(android.content.Context app, FreeBooks.Progress progress)
                            throws IOException {
                        return Edited.assemble(app, b, progress);
                    }
                }, R.string.edited_missing);
            }
        });
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
                gather(new Maker() {
                    @Override
                    public FreeBooks.Made make(android.content.Context app, FreeBooks.Progress progress)
                            throws IOException {
                        return FreeBooks.assemble(app, w, progress);
                    }
                }, R.string.library_missing);
            }
        });
    }

    /** Puts one book together, telling how far it has come; either library's way. */
    private interface Maker {
        FreeBooks.Made make(android.content.Context app, FreeBooks.Progress progress) throws IOException;
    }

    /**
     * Puts the book together under the waiting heart, then shelves it and opens its screen.
     *
     * @param missingText what is said when some parts did not come
     */
    private void gather(final Maker maker, final int missingText) {
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
                    FreeBooks.Made made = maker.make(app, new FreeBooks.Progress() {
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
                                    ? getString(R.string.library_cut) : getString(missingText, lost),
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
