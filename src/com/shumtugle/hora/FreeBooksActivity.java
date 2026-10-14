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
 * Finding a book in the free library: a word, the works that match, and one of them put on the
 * shelf. Putting a long work together takes a while, so it runs under the waiting heart with
 * one bar and a way to stop; the finished book opens on its own screen, ready to play.
 */
public final class FreeBooksActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int built;
    private EditText field;
    private TextView state;
    private LinearLayout results;
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
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Palette.BG);
        root.setPadding(dp(Kit.PAD), dp(32), dp(Kit.PAD), 0);
        root.addView(Ui.header(this, getString(R.string.library_title), false), Ui.lp(Ui.MATCH, dp(48)));

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
        root.addView(box, bp);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout list = Ui.column(this);
        list.setPadding(0, dp(12), 0, dp(40));
        state = Ui.text(this, "", 13, Palette.MUTED);
        list.addView(state);
        results = Kit.plate(this);
        results.setVisibility(View.GONE);
        list.addView(results, Kit.below(this, 8));
        note = Ui.text(this, getString(R.string.library_note), 12, Palette.HINT);
        note.setLineSpacing(0, 1.3f);
        note.setPadding(dp(6), dp(12), dp(6), 0);
        list.addView(note);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        setContentView(root);
        buildOverlay();

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
        finish();
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
        results.removeAllViews();
        if (found == null) {
            // A failed search may be asked again with the same words.
            shown = "";
            state.setText(R.string.library_offline);
            results.setVisibility(View.GONE);
            return;
        }
        if (found.isEmpty()) {
            state.setText(R.string.library_none);
            results.setVisibility(View.GONE);
            return;
        }
        state.setText(R.string.library_found);
        for (int i = 0; i < found.size(); i++) {
            if (i > 0) {
                results.addView(Kit.divider(this));
            }
            results.addView(row(found.get(i)));
        }
        results.setVisibility(View.VISIBLE);
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
        final TextView s = Ui.text(this, f.by, 13, Palette.MUTED);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        s.setVisibility(f.by.isEmpty() ? View.GONE : View.VISIBLE);
        words.addView(s);
        r.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams cp = Ui.lp(Ui.WRAP, Ui.WRAP);
        cp.leftMargin = dp(12);
        r.addView(Ui.text(this, "›", 20, Palette.HINT), cp);
        r.setBackground(Ui.pressable(new android.graphics.drawable.ColorDrawable(0)));
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(f, s);
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
