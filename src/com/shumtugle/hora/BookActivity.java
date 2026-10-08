package com.shumtugle.hora;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The book being read aloud: its title, how far in, the text as a ribbon
 * with the paragraph now heard on a raised plate, and the three controls.
 * The reading itself runs in the player; this screen only shows it and
 * sends it commands, so it can be closed at any time without the voice
 * stopping. A touched paragraph opens a sheet: read from here, copy, select.
 */
public final class BookActivity extends Activity {
    private static final int PICK_BOOK = 300;
    /** How often the screen looks at the reading state while it is visible. */
    private static final long TICK_MS = 500;

    private int built;
    private LinearLayout empty;
    private LinearLayout book;
    private TextView title;
    private TextView where;
    private ImageView readerFace;
    private TextView readerName;
    private TextView bedState;
    private ImageView bedIcon;
    private int shownReader = -1;
    private ListView ribbon;
    private ImageView backdrop;
    private ImageView contents;
    private ImageView finder;
    private static final int ASK_SEARCH = 41;
    /** Opened by a request to search: the search room opens as soon as the text is read. */
    static final String EXTRA_SEARCH = "search";
    private boolean searchWanted;
    /** A place found by search that could not be shown yet (the text was still being read), or -1. */
    private static int pendingJump = -1;
    /** The reader whose scene lies behind the screen, or -1 before the first look. */
    private int backdropVoice = -1;
    private final Ribbon rows = new Ribbon();
    private Kit.Bar bar;
    private ImageView toggle;
    private LinearLayout controls;
    /** The paragraph on the raised plate, or -1 before the first look. */
    private int current = -1;
    /** When the user last moved the ribbon by hand; the ribbon does not follow the voice for a while after. */
    private long touchedAt;
    private static final long HANDS_OFF_MS = 8000;
    /** Set once a finger has moved the ribbon, so the ribbon's own scrolling is not taken for the user's. */
    private boolean userScroll;
    /** The heard paragraph changed while the user's hands were on the text; the ribbon catches up after. */
    private boolean followOwed;
    /** The text of the book shown, read here once per book; null until it is read. */
    private static String loadedUri = "";
    private static List<String> text;
    private static List<Book.Chapter> chapters = new ArrayList<Book.Chapter>();
    private static boolean[] heading = new boolean[0];
    private static boolean loading;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            show();
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        setContentView(build());
        searchWanted = getIntent() != null && getIntent().getBooleanExtra(EXTRA_SEARCH, false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_SEARCH, false)) {
            searchWanted = true;
        }
    }

    /** Opens the search room once the text is read, if a request asked for it. */
    private void searchIfWanted() {
        if (searchWanted && text != null) {
            searchWanted = false;
            startActivityForResult(new Intent(this, SearchActivity.class), ASK_SEARCH);
        }
    }

    /** "Chapter six · paragraph 2 of 14 · 24 %" when the book has chapters, else by paragraph of the whole. */
    private String place(int total) {
        int i = Reading.index(this);
        int percent = Reading.permille(this) / 10;
        Library.Entry e = Library.get(this, Reading.uri(this));
        int ch = e == null ? -1 : e.chapterAt(i);
        if (ch < 0) {
            return getString(R.string.book_where_percent, i + 1, total, percent);
        }
        Book.Chapter c = e.chapters.get(ch);
        int inChapter = Math.max(1, i - c.at);
        int ofChapter = Math.max(1, e.chapterEnd(ch) - c.at - 1);
        return getString(R.string.book_where_chapter, c.label, inChapter, ofChapter, percent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        BookActivity.warm(this);
        if (Ui.stale(this, built)) {
            return;
        }
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private View build() {
        // The scene of the reader lies behind everything, blurred and dimmed, as on the other main screens.
        FrameLayout whole = new FrameLayout(this);
        whole.setBackgroundColor(Palette.BG);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
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

        // The book's own header: its title (two lines at most), where the voice is, and the contents.
        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.TOP);
        LinearLayout names = Ui.column(this);
        title = Ui.title(this, "", 30);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setLineSpacing(0, 0.95f);
        // Touching the title opens another book.
        title.setClickable(true);
        title.setBackground(Ui.pressable(new ColorDrawable(Color.TRANSPARENT)));
        title.setOnClickListener(pick());
        names.addView(title, Ui.lp(Ui.MATCH, Ui.WRAP));
        head.addView(names, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        contents = Ui.iconButton(this, R.drawable.ic_contents, Palette.SURFACE, 48, getString(R.string.book_contents));
        contents.setImageTintList(ColorStateList.valueOf(Palette.INK));
        contents.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                contentsSheet();
            }
        });
        contents.setVisibility(View.GONE);
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(dp(48), dp(48));
        cbp.leftMargin = dp(10);
        head.addView(contents, cbp);
        finder = Ui.iconButton(this, R.drawable.ic_search, Palette.SURFACE, 48, getString(R.string.search_title));
        finder.setImageTintList(ColorStateList.valueOf(Palette.INK));
        finder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(BookActivity.this, SearchActivity.class), ASK_SEARCH);
            }
        });
        finder.setVisibility(View.GONE);
        LinearLayout.LayoutParams fbp = new LinearLayout.LayoutParams(dp(48), dp(48));
        fbp.leftMargin = dp(10);
        head.addView(finder, fbp);
        root.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP));
        // The place runs the whole width under the title and its buttons, so it never breaks early.
        where = Ui.text(this, "", 13, Palette.MUTED);
        LinearLayout.LayoutParams wp = Ui.lp(Ui.MATCH, Ui.WRAP);
        wp.topMargin = dp(6);
        root.addView(where, wp);

        FrameLayout stage = new FrameLayout(this);

        empty = Ui.column(this);
        empty.setBackground(Ui.round(Palette.SURFACE, 24, this));
        empty.setPadding(dp(20), dp(20), dp(20), dp(20));
        empty.addView(Ui.title(this, getString(R.string.book_none), 24));
        TextView et = Ui.text(this, getString(R.string.book_none_hint), 14, Palette.MUTED);
        et.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams etp = Ui.lp(Ui.MATCH, Ui.WRAP);
        etp.topMargin = dp(8);
        empty.addView(et, etp);
        TextView openFirst = Ui.pill(this, getString(R.string.book_open), Palette.ACCENT, 0, Palette.ON_ACCENT, true);
        openFirst.setOnClickListener(pick());
        LinearLayout.LayoutParams ofp = Ui.lp(Ui.MATCH, dp(48));
        ofp.topMargin = dp(18);
        empty.addView(openFirst, ofp);
        FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        ep.topMargin = dp(18);
        stage.addView(empty, ep);

        book = Ui.column(this);
        // The whole text runs as one ribbon; the paragraph being heard stands on a raised plate.
        FrameLayout card = new FrameLayout(this);
        card.setBackground(Ui.round(Palette.SURFACE, 24, this));
        // Text scrolling past the plate's rounded corners is cut by them, not by a straight edge.
        card.setClipToOutline(true);
        ribbon = new ListView(this);
        ribbon.setDivider(null);
        ribbon.setDividerHeight(0);
        ribbon.setSelector(new ColorDrawable(Color.TRANSPARENT));
        ribbon.setCacheColorHint(Color.TRANSPARENT);
        ribbon.setVerticalScrollBarEnabled(false);
        ribbon.setOverScrollMode(View.OVER_SCROLL_NEVER);
        ribbon.setVerticalFadingEdgeEnabled(true);
        ribbon.setFadingEdgeLength(dp(56));
        ribbon.setPadding(0, dp(4), 0, dp(4));
        ribbon.setClipToPadding(false);
        ribbon.setAdapter(rows);
        ribbon.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                if (text != null) {
                    paragraphSheet(position);
                }
            }
        });
        ribbon.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView v, int state) {
                if (state != SCROLL_STATE_IDLE && userScroll) {
                    touchedAt = System.currentTimeMillis();
                }
            }

            @Override
            public void onScroll(AbsListView v, int first, int visible, int total) {
            }
        });
        ribbon.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
                    userScroll = true;
                    touchedAt = System.currentTimeMillis();
                }
                return false;
            }
        });
        card.addView(ribbon, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams cdp = new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f);
        cdp.topMargin = dp(14);
        book.addView(card, cdp);
        bar = new Kit.Bar(this);
        LinearLayout.LayoutParams bp = Ui.lp(Ui.MATCH, dp(3));
        bp.topMargin = dp(14);
        book.addView(bar, bp);
        stage.addView(book, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(stage, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        // One row under the thumb: who reads, the three controls, and what lies under the voice.
        controls = Ui.row(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        readerFace = Ui.portrait(this, 1);
        readerFace.setContentDescription(getString(R.string.book_change_reader));
        readerFace.setPadding(dp(3), dp(3), dp(3), dp(3));
        readerName = Ui.text(this, "", 11, Palette.MUTED);
        View reader = flank(readerFace, readerName);
        reader.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(BookActivity.this, RolesActivity.class));
            }
        });
        reader.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                WorkshopActivity.openCabinet(BookActivity.this, Prefs.role(BookActivity.this, Cast.NARRATOR));
                return true;
            }
        });
        controls.addView(reader, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ImageView prev = Ui.iconButton(this, R.drawable.ic_prev, Palette.SURFACE, 52, getString(R.string.book_prev));
        prev.setOnClickListener(command(BookPlayer.ACTION_PREV));
        toggle = Ui.iconButton(this, R.drawable.ic_play, Palette.ACCENT, 56, getString(R.string.book_play));
        toggle.setImageTintList(ColorStateList.valueOf(Palette.ON_ACCENT));
        toggle.setOnClickListener(command(BookPlayer.ACTION_TOGGLE));
        ImageView next = Ui.iconButton(this, R.drawable.ic_next, Palette.SURFACE, 52, getString(R.string.book_next));
        next.setOnClickListener(command(BookPlayer.ACTION_NEXT));
        controls.addView(prev);
        LinearLayout.LayoutParams tp = Ui.lp(dp(56), dp(56));
        tp.leftMargin = dp(14);
        tp.rightMargin = dp(14);
        controls.addView(toggle, tp);
        controls.addView(next);
        bedIcon = new ImageView(this);
        bedIcon.setImageResource(R.drawable.ic_waves);
        bedIcon.setScaleType(ImageView.ScaleType.CENTER);
        bedState = Ui.text(this, "", 11, Palette.MUTED);
        View bed = flank(bedIcon, bedState);
        bed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BedSheet.show(BookActivity.this, new Runnable() {
                    @Override
                    public void run() {
                        showBed();
                    }
                });
            }
        });
        controls.addView(bed, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams cp = Ui.lp(Ui.MATCH, Ui.WRAP);
        cp.topMargin = dp(14);
        root.addView(controls, cp);

        root.addView(Ui.navBar(this, Ui.NAV_BOOK), Ui.lp(Ui.MATCH, Ui.WRAP));
        Kit.edgeToEdge(this, root, dp(20), dp(20), dp(20), dp(16));
        return whole;
    }

    /** Brings the screen up to the reading state. */
    private void show() {
        boolean has = !Reading.uri(this).isEmpty();
        empty.setVisibility(has ? View.GONE : View.VISIBLE);
        book.setVisibility(has ? View.VISIBLE : View.GONE);
        controls.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
        where.setVisibility(has ? View.VISIBLE : View.GONE);
        contents.setVisibility(has && text != null && !chapters.isEmpty() ? View.VISIBLE : View.GONE);
        finder.setVisibility(has && text != null ? View.VISIBLE : View.GONE);
        searchIfWanted();
        if (!has) {
            title.setText(R.string.nav_book);
            setBackdrop(Prefs.role(this, Cast.NARRATOR));
            return;
        }
        String name = Reading.title(this);
        title.setText(name.isEmpty() ? getString(R.string.nav_book) : name);
        int total = Reading.total(this);
        where.setText(total <= 0 ? getString(R.string.book_opening) : place(total));
        int narrator = Prefs.role(this, Cast.NARRATOR);
        if (narrator != shownReader) {
            shownReader = narrator;
            Ui.setFace(readerFace, narrator);
            readerName.setText(Cast.name(this, narrator));
            readerName.setTextColor(Palette.voice(narrator));
            readerFace.setBackground(Ui.oval(Palette.BG, Palette.voice(narrator), 2, this));
        }
        setBackdrop(narrator);
        showBed();
        bar.set(Reading.permille(this) / 1000f);
        load();
        int i = Reading.index(this);
        if (text == null) {
            // Until the text is read here, the ribbon holds the heard paragraph alone.
            rows.notifyDataSetChanged();
        } else if (i != current) {
            boolean first = current < 0;
            current = i;
            rows.notifyDataSetChanged();
            follow(first);
        } else if (followOwed && System.currentTimeMillis() - touchedAt >= HANDS_OFF_MS) {
            follow(false);
        }
        boolean playing = Reading.playing(this);
        toggle.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        toggle.setContentDescription(getString(playing ? R.string.book_pause : R.string.book_play));
    }

    /** What lies under the voice: the waves glow while a background plays, its name below. */
    private void showBed() {
        if (bedState == null) {
            return;
        }
        int kind = Prefs.bed(this);
        boolean on = kind != Bed.OFF;
        String name = Prefs.bedOnly(this) ? getString(R.string.bed_caption_voice_quiet)
                : getResources().getStringArray(R.array.bed_kinds_short)[kind];
        if (!name.contentEquals(bedState.getText())) {
            bedState.setText(name);
        }
        int color = on ? Palette.ACCENT_TEXT : Palette.MUTED;
        bedState.setTextColor(color);
        bedIcon.setImageTintList(ColorStateList.valueOf(color));
        bedIcon.setBackground(Ui.oval(Palette.SURFACE, on ? Palette.ACCENT_TEXT : Palette.SURFACE, on ? 1.5f : 0, this));
        bedIcon.setContentDescription(getString(R.string.bed) + ": " + name);
    }

    /** A round picture with a short word under it, at the side of the controls. */
    private View flank(ImageView picture, TextView word) {
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClickable(true);
        box.setFocusable(true);
        box.setBackground(Ui.pressable(new ColorDrawable(Color.TRANSPARENT)));
        box.setPadding(0, dp(4), 0, dp(4));
        picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(picture, Ui.lp(dp(48), dp(48)));
        word.setTypeface(Palette.bodyStrong(this));
        word.setSingleLine(true);
        word.setEllipsize(android.text.TextUtils.TruncateAt.END);
        word.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams wp = Ui.lp(Ui.WRAP, Ui.WRAP);
        wp.topMargin = dp(4);
        box.addView(word, wp);
        return box;
    }

    /** The open book's text for the search room, or null when it is not read yet. */
    static List<String> openText() {
        return text;
    }

    static List<Book.Chapter> openChapters() {
        return chapters;
    }

    /** Back from the search room with a place: the ribbon goes there and its sheet opens. */
    private void found(Intent data) {
        if (data == null) {
            return;
        }
        int at = data.getIntExtra(SearchActivity.EXTRA_INDEX, -1);
        Diag.log(this, "book screen: back from search at paragraph " + at);
        if (text == null) {
            // The screen was rebuilt and its text is still being read: the jump waits for it.
            pendingJump = at;
            return;
        }
        jump(at);
    }

    /** Puts the ribbon on a paragraph and opens its sheet. */
    private void jump(final int at) {
        if (text == null || at < 0 || at >= text.size()) {
            return;
        }
        // The ribbon stays where the place was found, and its sheet opens over it.
        touchedAt = System.currentTimeMillis();
        handler.post(new Runnable() {
            @Override
            public void run() {
                ribbon.setSelectionFromTop(at, dp(48));
                paragraphSheet(at);
            }
        });
    }

    /** The reader's scene behind the screen, blurred; none in the contrast look. */
    private void setBackdrop(int voice) {
        if (voice == backdropVoice) {
            return;
        }
        backdropVoice = voice;
        Kit.backdrop(this, backdrop, voice);
    }

    /** Where a chosen place should take the voice: reading on from there if it reads, else only moving. */
    private void goTo(int index) {
        String action = Reading.playing(this) ? BookPlayer.ACTION_FROM : BookPlayer.ACTION_SEEK;
        startForegroundService(BookPlayer.intent(this, action).putExtra(BookPlayer.EXTRA_INDEX, index));
        touchedAt = 0;
    }

    /** The contents as a sheet: read chapters quiet, the current one raised with its own thread, the rest ahead. */
    private void contentsSheet() {
        if (text == null || chapters.isEmpty()) {
            return;
        }
        final Dialog[] sheet = new Dialog[1];
        int i = Reading.index(this);
        int now = -1;
        for (int n = 0; n < chapters.size(); n++) {
            if (chapters.get(n).at <= i) {
                now = n;
            }
        }
        LinearLayout content = Ui.column(this);
        LinearLayout top = Ui.row(this);
        top.setGravity(Gravity.BOTTOM);
        top.addView(Ui.title(this, getString(R.string.book_contents), 28),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        String count = getResources().getQuantityString(R.plurals.book_chapters, chapters.size(), chapters.size());
        TextView meta = Ui.text(this, getString(R.string.book_contents_meta, count, Reading.permille(this) / 10),
                13, Palette.MUTED);
        LinearLayout.LayoutParams mp = Ui.lp(Ui.WRAP, Ui.WRAP);
        mp.bottomMargin = dp(6);
        top.addView(meta, mp);
        // The title stands in by the rows' own margin, so chapter names line up under it
        // and the current chapter's plate keeps its rounded ends.
        top.setPadding(dp(14), 0, dp(14), 0);
        content.addView(top, Ui.lp(Ui.MATCH, Ui.WRAP));

        LinearLayout list = Ui.column(this);
        View here = null;
        for (int n = 0; n < chapters.size(); n++) {
            final Book.Chapter ch = chapters.get(n);
            int end = n + 1 < chapters.size() ? chapters.get(n + 1).at : text.size();
            boolean done = n < now;
            boolean current = n == now;
            LinearLayout row = Ui.row(this);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setBackground(Ui.pressable(current ? Ui.round(Palette.RAISED, 16, this)
                    : new ColorDrawable(Color.TRANSPARENT)));
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    goTo(ch.at);
                    sheet[0].dismiss();
                }
            });
            LinearLayout words = Ui.column(this);
            TextView name = Ui.title(this, ch.label, 20);
            if (current) {
                name.setTypeface(Palette.display(this), android.graphics.Typeface.BOLD);
            }
            name.setTextColor(done ? Palette.MUTED : Palette.INK);
            words.addView(name);
            String firstWords = ch.first == null ? "" : ch.first.trim();
            // Cut words trail off with an ellipsis, so they never read as a whole sentence.
            if (!firstWords.isEmpty() && !firstWords.matches(".*[.!?\u2026\u00bb\"]$")) {
                firstWords = firstWords + "\u2026";
            }
            if (!firstWords.isEmpty()) {
                TextView first = Ui.text(this, firstWords, 13, done ? Palette.HINT : Palette.MUTED);
                first.setSingleLine(true);
                first.setEllipsize(android.text.TextUtils.TruncateAt.END);
                words.addView(first);
            }
            String right = "";
            if (current) {
                float part = end - ch.at <= 1 ? 0f : (i - ch.at) / (float) (end - ch.at - 1);
                part = Math.max(0f, Math.min(1f, part));
                Kit.Bar thread = new Kit.Bar(this);
                thread.set(part);
                LinearLayout.LayoutParams tp = Ui.lp(Ui.MATCH, dp(3));
                tp.topMargin = dp(8);
                words.addView(thread, tp);
                right = Math.round(part * 100) + "\u00a0%";
                here = row;
            } else if (done) {
                right = getString(R.string.book_chapter_read);
            }
            row.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (!right.isEmpty()) {
                TextView r = Ui.text(this, right, 13, current ? Palette.MUTED : Palette.HINT);
                LinearLayout.LayoutParams rp = Ui.lp(Ui.WRAP, Ui.WRAP);
                rp.leftMargin = dp(14);
                row.addView(r, rp);
            }
            list.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP));
        }
        final Kit.Capped frame = new Kit.Capped(this, getResources().getDisplayMetrics().heightPixels * 62 / 100);
        frame.addView(list);
        LinearLayout.LayoutParams fp = Ui.lp(Ui.MATCH, Ui.WRAP);
        fp.topMargin = dp(14);
        content.addView(frame, fp);
        final View mark = here;
        if (mark != null) {
            // The sheet opens on the current chapter, with the one before it still in sight.
            frame.post(new Runnable() {
                @Override
                public void run() {
                    frame.scrollTo(0, Math.max(0, mark.getTop() - dp(72)));
                }
            });
        }
        sheet[0] = Kit.sheet(this, null, content, null, null);
    }

    /** Brings the heard paragraph into view, near the top, unless the user is looking elsewhere. */
    private void follow(boolean jump) {
        if (current < 0 || current >= rows.getCount()) {
            return;
        }
        if (jump) {
            ribbon.setSelectionFromTop(current, dp(48));
            return;
        }
        if (System.currentTimeMillis() - touchedAt < HANDS_OFF_MS) {
            followOwed = true;
            return;
        }
        followOwed = false;
        userScroll = false;
        int first = ribbon.getFirstVisiblePosition();
        int last = ribbon.getLastVisiblePosition();
        // A smooth scroll measures rows as it goes and stops short of a far one: far away, the ribbon jumps.
        if (current < first - 2 || current > last + 2) {
            Diag.log(this, "book screen: ribbon jumps to paragraph " + current + " from " + first + ".." + last);
            ribbon.setSelectionFromTop(current, dp(48));
            return;
        }
        ribbon.smoothScrollToPositionFromTop(current, dp(48), 420);
    }

    /** Reads the current book's text for the ribbon, once per book, away from the screen's thread. */
    private void load() {
        final String uri = Reading.uri(this);
        if (uri.isEmpty() || uri.equals(loadedUri) || loading) {
            return;
        }
        loading = true;
        // Another book: its own text replaces the old one, never the old one under the new title.
        text = null;
        chapters = new ArrayList<Book.Chapter>();
        heading = new boolean[0];
        current = -1;
        final String name = Reading.name(this);
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Book b = null;
                try {
                    InputStream in = app.getContentResolver().openInputStream(Uri.parse(uri));
                    try {
                        android.content.res.Resources res = SpeechLanguage.resources(app);
                        b = BookText.read(name, in, res.getString(R.string.chapter_words),
                                res.getString(R.string.chapter_ordinals));
                    } finally {
                        if (in != null) {
                            in.close();
                        }
                    }
                } catch (Exception e) {
                    Diag.log(app, "book screen: cannot read the text", e);
                }
                final Book got = b;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        loadedUri = uri;
                        if (got == null || got.paragraphs.isEmpty()) {
                            return;
                        }
                        text = got.paragraphs;
                        chapters = got.chapters;
                        heading = new boolean[text.size()];
                        for (Book.Chapter c : chapters) {
                            if (c.at >= 0 && c.at < heading.length) {
                                heading[c.at] = true;
                            }
                        }
                        current = -1;
                        show();
                        if (pendingJump >= 0) {
                            int at = pendingJump;
                            pendingJump = -1;
                            jump(at);
                        }
                    }
                });
            }
        }, "book-ribbon").start();
    }

    /** "Chapter six · paragraph 2", or "Paragraph 812" in a book without chapters. */
    private String label(int i) {
        Book.Chapter in = null;
        for (Book.Chapter c : chapters) {
            if (c.at <= i) {
                in = c;
            } else {
                break;
            }
        }
        if (in == null) {
            return getString(R.string.book_paragraph, i + 1);
        }
        if (in.at == i) {
            return in.label;
        }
        return getString(R.string.book_chapter_paragraph, in.label, i - in.at);
    }

    /** The sheet of a touched paragraph: read from here, copy it, or select a part of it. */
    private void paragraphSheet(final int i) {
        final String p = text.get(i);
        final Dialog[] sheet = new Dialog[1];
        LinearLayout content = Ui.column(this);
        content.addView(Ui.text(this, label(i), 13, Palette.MUTED));
        final TextView plain = Ui.title(this, p, 19);
        plain.setLineSpacing(0, 1.3f);
        final Kit.Selectable words = new Kit.Selectable(this);
        words.setText(p);
        words.setTextColor(Palette.INK);
        words.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 19);
        words.setTypeface(Palette.display(this));
        words.setIncludeFontPadding(false);
        words.setLineSpacing(0, 1.3f);
        words.setVisibility(View.GONE);
        LinearLayout both = Ui.column(this);
        both.addView(plain, Ui.lp(Ui.MATCH, Ui.WRAP));
        both.addView(words, Ui.lp(Ui.MATCH, Ui.WRAP));
        final Kit.Capped frame = new Kit.Capped(this, dp(150));
        frame.addView(both);
        LinearLayout.LayoutParams fp = Ui.lp(Ui.MATCH, Ui.WRAP);
        fp.topMargin = dp(10);
        content.addView(frame, fp);

        LinearLayout lesser = Ui.column(this);
        // Before selecting: copy the whole paragraph, or start selecting.
        final LinearLayout pair = Ui.row(this);
        // While selecting: the hint until a word is chosen, then the sheet's own pair.
        final TextView hint = Ui.text(this, getString(R.string.book_select_hint), 14, Palette.MUTED);
        hint.setLineSpacing(0, 1.25f);
        hint.setVisibility(View.GONE);
        final LinearLayout chosen = Ui.row(this);
        chosen.setVisibility(View.GONE);

        pair.addView(Kit.secondary(this, getString(R.string.book_copy), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy(label(i), p);
                sheet[0].dismiss();
            }
        }), Ui.weight(1));
        LinearLayout.LayoutParams sp = Ui.weight(1);
        sp.leftMargin = dp(12);
        pair.addView(Kit.secondary(this, getString(R.string.book_select), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // The words become selectable in place, with room to see all of them.
                plain.setVisibility(View.GONE);
                words.setVisibility(View.VISIBLE);
                frame.setCeiling(getResources().getDisplayMetrics().heightPixels / 2);
                pair.setVisibility(View.GONE);
                hint.setVisibility(View.VISIBLE);
            }
        }), sp);

        // While something is chosen: copy it, choose all, hand it to another app, or more apps that take text.
        final LinearLayout chosenTop = Ui.row(this);
        final LinearLayout chosenLow = Ui.row(this);
        chosen.setOrientation(LinearLayout.VERTICAL);
        chosen.addView(chosenTop, Ui.lp(Ui.MATCH, Ui.WRAP));
        LinearLayout.LayoutParams lowp = Ui.lp(Ui.MATCH, Ui.WRAP);
        lowp.topMargin = dp(12);
        chosen.addView(chosenLow, lowp);
        chosenTop.addView(Kit.secondary(this, getString(R.string.book_copy), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy(label(i), selected(words, p));
                sheet[0].dismiss();
            }
        }), Ui.weight(1));
        LinearLayout.LayoutParams ap = Ui.weight(1);
        ap.leftMargin = dp(12);
        chosenTop.addView(Kit.secondary(this, getString(R.string.book_select_all), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                words.chooseAll();
            }
        }), ap);
        chosenLow.addView(Kit.secondary(this, getString(R.string.book_share), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, selected(words, p));
                sheet[0].dismiss();
                try {
                    startActivity(Intent.createChooser(send, null));
                } catch (RuntimeException e) {
                    Diag.log(BookActivity.this, "book screen: cannot share", e);
                }
            }
        }), Ui.weight(1));
        final List<android.content.pm.ResolveInfo> takers = textTakers();
        if (!takers.isEmpty()) {
            LinearLayout.LayoutParams mp = Ui.weight(1);
            mp.leftMargin = dp(12);
            chosenLow.addView(Kit.secondary(this, getString(R.string.book_more), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    moreSheet(selected(words, p), takers, sheet[0]);
                }
            }), mp);
        }
        words.onSelection(new Kit.Selected() {
            @Override
            public void changed(boolean any) {
                hint.setVisibility(any ? View.GONE : View.VISIBLE);
                chosen.setVisibility(any ? View.VISIBLE : View.GONE);
            }
        });

        lesser.addView(pair, Ui.lp(Ui.MATCH, Ui.WRAP));
        LinearLayout.LayoutParams hp = Ui.lp(Ui.MATCH, Ui.WRAP);
        hp.leftMargin = dp(4);
        hp.rightMargin = dp(4);
        lesser.addView(hint, hp);
        lesser.addView(chosen, Ui.lp(Ui.MATCH, Ui.WRAP));

        sheet[0] = Kit.sheet(this, null, content, playLabel(getString(R.string.book_from_here)), new Runnable() {
            @Override
            public void run() {
                startForegroundService(BookPlayer.intent(BookActivity.this, BookPlayer.ACTION_FROM)
                        .putExtra(BookPlayer.EXTRA_INDEX, i));
                touchedAt = 0;
            }
        }, lesser);
    }

    private static String selected(Kit.Selectable words, String whole) {
        String part = words.chosen();
        return part.isEmpty() ? whole : part;
    }

    /**
     * The apps on the phone that take a piece of text: translators, search,
     * assistants, readers. Hora's own reading-aloud entry is left out here,
     * since this screen already reads; its dictionary entry stays.
     */
    private List<android.content.pm.ResolveInfo> textTakers() {
        List<android.content.pm.ResolveInfo> out = new ArrayList<android.content.pm.ResolveInfo>();
        try {
            Intent probe = new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain");
            for (android.content.pm.ResolveInfo r : getPackageManager().queryIntentActivities(probe, 0)) {
                if (r.activityInfo == null) {
                    continue;
                }
                if (getPackageName().equals(r.activityInfo.packageName)
                        && ReadActivity.class.getName().equals(r.activityInfo.name)) {
                    continue;
                }
                out.add(r);
            }
        } catch (RuntimeException e) {
            Diag.log(this, "book screen: cannot list text apps", e);
        }
        return out;
    }

    /** The second sheet: every app that takes text, one row each, as the phone names them. */
    private void moreSheet(final String part, List<android.content.pm.ResolveInfo> takers, final Dialog first) {
        final Dialog[] sheet = new Dialog[1];
        LinearLayout content = Ui.column(this);
        TextView quote = Ui.text(this, "\u00ab" + part + "\u00bb", 13, Palette.MUTED);
        quote.setMaxLines(2);
        quote.setEllipsize(android.text.TextUtils.TruncateAt.END);
        content.addView(quote);
        LinearLayout list = Kit.plate(this);
        list.setPadding(0, dp(4), 0, dp(4));
        for (final android.content.pm.ResolveInfo r : takers) {
            CharSequence name = r.loadLabel(getPackageManager());
            list.addView(Kit.rowChoice(this, name, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent take = new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                            .setClassName(r.activityInfo.packageName, r.activityInfo.name)
                            .putExtra(Intent.EXTRA_PROCESS_TEXT, part)
                            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
                    sheet[0].dismiss();
                    first.dismiss();
                    try {
                        startActivity(take);
                    } catch (RuntimeException e) {
                        Diag.log(BookActivity.this, "book screen: a text app would not open", e);
                    }
                }
            }), Ui.lp(Ui.MATCH, Ui.WRAP));
        }
        Kit.Capped frame = new Kit.Capped(this, getResources().getDisplayMetrics().heightPixels * 55 / 100);
        frame.addView(list);
        LinearLayout.LayoutParams fp = Ui.lp(Ui.MATCH, Ui.WRAP);
        fp.topMargin = dp(16);
        content.addView(frame, fp);
        sheet[0] = Kit.sheet(this, getString(R.string.book_what_to_do), content, null, null);
    }

    private void copy(String label, String s) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, s));
        }
    }

    /** A label with the play sign in front of it, for the one action that starts the voice. */
    private CharSequence playLabel(String label) {
        android.graphics.drawable.Drawable d = getDrawable(R.drawable.ic_play_small);
        if (d == null) {
            return label;
        }
        d = d.mutate();
        d.setTint(Palette.ON_ACCENT);
        int size = dp(20);
        d.setBounds(0, 0, size, size);
        android.text.SpannableString s = new android.text.SpannableString("\u2060  " + label);
        int align = android.os.Build.VERSION.SDK_INT >= 29
                ? android.text.style.DynamicDrawableSpan.ALIGN_CENTER
                : android.text.style.DynamicDrawableSpan.ALIGN_BASELINE;
        s.setSpan(new android.text.style.ImageSpan(d, align), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** The ribbon's rows: text before the heard paragraph is quieter, after it a little less so. */
    private final class Ribbon extends BaseAdapter {
        @Override
        public int getCount() {
            if (text != null) {
                return text.size();
            }
            return Reading.paragraph(BookActivity.this).isEmpty() ? 0 : 1;
        }

        @Override
        public Object getItem(int position) {
            return text != null ? text.get(position) : Reading.paragraph(BookActivity.this);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View reuse, ViewGroup parent) {
            TextView t = reuse instanceof TextView ? (TextView) reuse : row();
            boolean here = text == null || position == current;
            boolean head = text != null && position < heading.length && heading[position];
            t.setText((String) getItem(position));
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, head ? 24 : 20);
            if (here) {
                t.setTextColor(Palette.INK);
                t.setBackground(new InsetDrawable(Ui.round(Palette.RAISED, 16, BookActivity.this), dp(8), 0, dp(8), 0));
                t.setPadding(dp(22), dp(14), dp(22), dp(14));
            } else {
                t.setTextColor(position < current ? Palette.HINT : Palette.MUTED);
                t.setBackground(null);
                t.setPadding(dp(22), dp(head ? 22 : 12), dp(22), dp(12));
            }
            return t;
        }

        private TextView row() {
            TextView t = Ui.title(BookActivity.this, "", 20);
            t.setLineSpacing(0, 1.3f);
            t.setLayoutParams(new AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT,
                    AbsListView.LayoutParams.WRAP_CONTENT));
            return t;
        }
    }

    private View.OnClickListener command(final String action) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startForegroundService(BookPlayer.intent(BookActivity.this, action));
                // A press on the controls asks to see where the voice is.
                touchedAt = 0;
                followOwed = true;
            }
        };
    }

    private View.OnClickListener pick() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*");
                startActivityForResult(i, PICK_BOOK);
            }
        };
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == ASK_SEARCH) {
            if (result == RESULT_OK) {
                found(data);
            }
            return;
        }
        if (request != PICK_BOOK || result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        openPicked(this, data.getData());
    }

    /** Opens a book the system picker handed over, keeping the right to read it; false if it cannot be read. */
    static boolean openPicked(Activity a, Uri uri) {
        try {
            // Keeps the right to read the book after a restart.
            a.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some providers give one-time access only; the book still opens now.
        }
        String name = displayName(a, uri);
        if (!BookText.readable(name)) {
            Toast.makeText(a, a.getString(R.string.book_unreadable, name), Toast.LENGTH_LONG).show();
            return false;
        }
        open(a, uri.toString(), name, true);
        return true;
    }

    private static long warmedAt;

    /**
     * Asks the player to load the open book and its voice ahead of a touch,
     * at most every few minutes, so pressing play sounds at once.
     */
    static void warm(Context c) {
        if (Reading.uri(c).isEmpty() || Reading.playing(c)) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (warmedAt != 0 && now - warmedAt < 5 * 60 * 1000L) {
            return;
        }
        warmedAt = now;
        try {
            c.startForegroundService(BookPlayer.intent(c, BookPlayer.ACTION_WARM));
        } catch (RuntimeException e) {
            Diag.log(c, "book screen: cannot warm the voice", e);
        }
    }

    /** Asks the player to open a book at its kept place, reading at once or not. */
    static void open(Context c, String uri, String name, boolean play) {
        c.startForegroundService(BookPlayer.intent(c, BookPlayer.ACTION_OPEN)
                .putExtra(BookPlayer.EXTRA_URI, uri)
                .putExtra(BookPlayer.EXTRA_NAME, name)
                .putExtra(BookPlayer.EXTRA_PLAY, play));
    }

    static String displayName(Context a, Uri uri) {
        Cursor c = a.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    return c.getString(0);
                }
            } finally {
                c.close();
            }
        }
        return uri.getLastPathSegment();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
