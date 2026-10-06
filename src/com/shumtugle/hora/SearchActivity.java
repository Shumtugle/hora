package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Search in the open book, as a room: the field stays at the top, the places
 * found run below it, grouped by chapter, each with a little of the text
 * around the word. Touching a place goes back to the book there and opens
 * that paragraph's sheet.
 *
 * The search is soft: a word finds its other forms (its stem is compared),
 * or any word it begins; several words find the paragraphs holding all of
 * them in any order; words in quotes are looked for exactly as written.
 * One word lists every place it stands; several words list paragraphs.
 */
public final class SearchActivity extends Activity {
    static final String EXTRA_INDEX = "index";
    private static final int MIN_QUERY = 2;
    private static final int SHOWN = 300;
    private static final int BEFORE = 45;
    private static final int AFTER = 75;
    /** The last words looked for, kept while the app lives, so coming back finds them again. */
    private static String lastQuery = "";

    /** Sets the words to look for when the room opens next, as when asked aloud. */
    static void prime(String query) {
        lastQuery = query == null ? "" : query.trim();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int built;
    private EditText field;
    private TextView count;
    private LinearLayout results;
    private ScrollView scroll;
    private int generation;

    /** One place found: the paragraph, the span the snippet centres on, and every span to light up. */
    private static final class Hit {
        final int paragraph;
        final int at;
        final int end;
        final int[] marks;

        Hit(int paragraph, int at, int end, int[] marks) {
            this.paragraph = paragraph;
            this.at = at;
            this.end = end;
            this.marks = marks;
        }
    }

    /** What is looked for: a word (by its stem and as a beginning) or a phrase in quotes. */
    private static final class Term {
        final boolean phrase;
        final String fold;
        final String stem;

        Term(boolean phrase, String fold) {
            this.phrase = phrase;
            this.fold = fold;
            this.stem = phrase ? fold : Stem.of(fold);
        }
    }

    /** The book cut into words once, kept while the same text is open. */
    private static final class Words {
        final String[] fold;
        final int[][] starts;
        final int[][] ends;
        final String[][] stems;

        Words(List<String> text) {
            int n = text.size();
            fold = new String[n];
            starts = new int[n][];
            ends = new int[n][];
            stems = new String[n][];
            for (int i = 0; i < n; i++) {
                String f = fold(text.get(i));
                fold[i] = f;
                List<int[]> spans = new ArrayList<int[]>();
                int j = 0;
                while (j < f.length()) {
                    while (j < f.length() && !Character.isLetterOrDigit(f.charAt(j))) {
                        j++;
                    }
                    int a = j;
                    while (j < f.length() && Character.isLetterOrDigit(f.charAt(j))) {
                        j++;
                    }
                    if (j > a) {
                        spans.add(new int[] {a, j});
                    }
                }
                starts[i] = new int[spans.size()];
                ends[i] = new int[spans.size()];
                stems[i] = new String[spans.size()];
                for (int k = 0; k < spans.size(); k++) {
                    starts[i][k] = spans.get(k)[0];
                    ends[i][k] = spans.get(k)[1];
                    stems[i][k] = Stem.of(f.substring(spans.get(k)[0], spans.get(k)[1]));
                }
            }
        }
    }

    private static List<String> wordsOf;
    private static Words words;

    private static synchronized Words wordsFor(List<String> text) {
        if (wordsOf != text || words == null) {
            words = new Words(text);
            wordsOf = text;
        }
        return words;
    }

    /** Words and quoted phrases of a query, already folded. */
    private static List<Term> terms(String raw) {
        List<Term> out = new ArrayList<Term>();
        String q = fold(raw);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[\"\u00ab\u201c\u201e]([^\"\u00bb\u201d\u201c]+)[\"\u00bb\u201d\u201c]?").matcher(q);
        StringBuilder rest = new StringBuilder();
        int last = 0;
        while (m.find()) {
            rest.append(q, last, m.start()).append(' ');
            String phrase = m.group(1).trim().replaceAll("\\s+", " ");
            if (phrase.length() >= MIN_QUERY) {
                out.add(new Term(true, phrase));
            }
            last = m.end();
        }
        rest.append(q.substring(last));
        for (String w : rest.toString().split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= MIN_QUERY) {
                out.add(new Term(false, w));
            }
        }
        return out;
    }

    /** Every span of one term in one paragraph, as start and end pairs. */
    private static List<int[]> find(Words w, int i, Term t) {
        List<int[]> out = new ArrayList<int[]>();
        String f = w.fold[i];
        if (t.phrase) {
            int from = 0;
            while (true) {
                int at = f.indexOf(t.fold, from);
                if (at < 0) {
                    break;
                }
                out.add(new int[] {at, at + t.fold.length()});
                from = at + t.fold.length();
            }
            return out;
        }
        int[] a = w.starts[i];
        int[] b = w.ends[i];
        String[] st = w.stems[i];
        for (int k = 0; k < a.length; k++) {
            boolean begins = b[k] - a[k] >= t.fold.length() && f.startsWith(t.fold, a[k]);
            if (begins || st[k].equals(t.stem)) {
                out.add(new int[] {a[k], b[k]});
            }
        }
        return out;
    }

    private final Runnable look = new Runnable() {
        @Override
        public void run() {
            search(field.getText().toString());
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        if (BookActivity.openText() == null) {
            finish();
            return;
        }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Palette.BG);
        root.setPadding(dp(Kit.PAD), dp(32), dp(Kit.PAD), 0);
        root.addView(Ui.header(this, getString(R.string.search_title), false), Ui.lp(Ui.MATCH, dp(48)));

        LinearLayout box = Ui.row(this);
        box.setBackground(Ui.round(Palette.SURFACE, 28, this));
        box.setPadding(dp(20), 0, dp(12), 0);
        ImageView lens = new ImageView(this);
        lens.setImageResource(R.drawable.ic_search);
        lens.setImageTintList(ColorStateList.valueOf(Palette.MUTED));
        lens.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(lens, Ui.lp(dp(20), dp(20)));
        field = new EditText(this);
        field.setHint(R.string.search_hint);
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

        count = Ui.text(this, "", 13, Palette.MUTED);
        LinearLayout.LayoutParams cp = Ui.lp(Ui.MATCH, Ui.WRAP);
        cp.topMargin = dp(12);
        root.addView(count, cp);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        results = Ui.column(this);
        results.setPadding(0, 0, 0, dp(40));
        scroll.addView(results);
        root.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        setContentView(root);

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
                handler.postDelayed(look, 250);
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
        if (!lastQuery.isEmpty()) {
            field.setText(lastQuery);
            field.setSelection(lastQuery.length());
        }
        field.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.stale(this, built);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(look);
        super.onDestroy();
    }

    /** Lowercase with yo folded into ye, keeping every index where it was. */
    private static String fold(String s) {
        char[] out = new char[s.length()];
        for (int i = 0; i < out.length; i++) {
            char c = Character.toLowerCase(s.charAt(i));
            out[i] = c == '\u0451' ? '\u0435' : c;
        }
        return new String(out);
    }

    private void search(String raw) {
        final String q = fold(raw.trim());
        lastQuery = raw.trim();
        final int mine = ++generation;
        if (q.length() < MIN_QUERY) {
            results.removeAllViews();
            count.setText("");
            return;
        }
        final List<String> text = BookActivity.openText();
        if (text == null) {
            return;
        }
        final List<Term> looked = terms(raw.trim());
        if (looked.isEmpty()) {
            results.removeAllViews();
            count.setText("");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                Words w = wordsFor(text);
                final List<Hit> hits = new ArrayList<Hit>();
                int total = 0;
                boolean single = looked.size() == 1;
                for (int i = 0; i < text.size() && mine == generation; i++) {
                    List<List<int[]>> per = new ArrayList<List<int[]>>();
                    boolean all = true;
                    for (Term t : looked) {
                        List<int[]> spans = find(w, i, t);
                        if (spans.isEmpty()) {
                            all = false;
                            break;
                        }
                        per.add(spans);
                    }
                    if (!all) {
                        continue;
                    }
                    if (single) {
                        for (int[] sp : per.get(0)) {
                            total++;
                            if (hits.size() < SHOWN) {
                                hits.add(new Hit(i, sp[0], sp[1], new int[] {sp[0], sp[1]}));
                            }
                        }
                    } else {
                        total++;
                        if (hits.size() < SHOWN) {
                            List<int[]> spans = new ArrayList<int[]>();
                            for (List<int[]> l : per) {
                                spans.addAll(l);
                            }
                            int[] first = spans.get(0);
                            int[] marks = new int[spans.size() * 2];
                            for (int k = 0; k < spans.size(); k++) {
                                if (spans.get(k)[0] < first[0]) {
                                    first = spans.get(k);
                                }
                                marks[2 * k] = spans.get(k)[0];
                                marks[2 * k + 1] = spans.get(k)[1];
                            }
                            hits.add(new Hit(i, first[0], first[1], marks));
                        }
                    }
                }
                final int found = total;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine == generation) {
                            show(hits, found);
                        }
                    }
                });
            }
        }, "book-search").start();
    }

    private void show(List<Hit> hits, int found) {
        results.removeAllViews();
        scroll.scrollTo(0, 0);
        List<Book.Chapter> chapters = BookActivity.openChapters();
        List<String> text = BookActivity.openText();
        if (found == 0 || text == null) {
            count.setText(R.string.search_none);
            return;
        }
        int groups = 0;
        int lastGroup = Integer.MIN_VALUE;
        for (Hit h : hits) {
            int g = chapterOf(chapters, h.paragraph);
            if (g != lastGroup) {
                groups++;
                lastGroup = g;
            }
        }
        String places = getResources().getQuantityString(R.plurals.search_places, found, found);
        count.setText(chapters.isEmpty() ? places
                : getString(R.string.search_count, places,
                        getResources().getQuantityString(R.plurals.search_in_chapters, groups, groups)));

        LinearLayout plate = null;
        lastGroup = Integer.MIN_VALUE;
        for (final Hit h : hits) {
            int g = chapterOf(chapters, h.paragraph);
            if (plate == null || g != lastGroup) {
                lastGroup = g;
                if (!chapters.isEmpty()) {
                    results.addView(Kit.section(this, g < 0 ? getString(R.string.search_before_chapters)
                            : chapters.get(g).label));
                } else if (plate == null) {
                    results.addView(new View(this), Ui.lp(Ui.MATCH, dp(20)));
                }
                plate = Kit.plate(this);
                results.addView(plate, Ui.lp(Ui.MATCH, Ui.WRAP));
            }
            int number = g < 0 ? h.paragraph + 1 : Math.max(1, h.paragraph - chapters.get(g).at);
            plate.addView(row(text.get(h.paragraph), h, number), Ui.lp(Ui.MATCH, Ui.WRAP));
        }
        if (found > hits.size()) {
            TextView more = Ui.text(this, getString(R.string.search_more, found - hits.size()), 13, Palette.HINT);
            more.setPadding(dp(4), dp(16), dp(4), 0);
            results.addView(more);
        }
    }

    private static int chapterOf(List<Book.Chapter> chapters, int paragraph) {
        int found = -1;
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).at <= paragraph) {
                found = i;
            } else {
                break;
            }
        }
        return found;
    }

    /** A found place: a little text around the words, the words in accent, the paragraph's number at the right. */
    private View row(String p, final Hit h, int number) {
        int start = Math.max(0, h.at - BEFORE);
        if (start > 0) {
            int space = p.indexOf(' ', start);
            if (space >= 0 && space < h.at) {
                start = space + 1;
            }
        }
        int end = Math.min(p.length(), h.end + AFTER);
        if (end < p.length()) {
            int space = p.lastIndexOf(' ', end);
            if (space > h.end) {
                end = space;
            }
        }
        SpannableStringBuilder s = new SpannableStringBuilder();
        if (start > 0) {
            s.append('\u2026');
        }
        int shift = s.length() - start;
        s.append(p, start, end);
        if (end < p.length()) {
            // A cut never ends on a comma or a dash before its ellipsis.
            while (s.length() > h.end + shift && ",;:\u2014\u2013- ".indexOf(s.charAt(s.length() - 1)) >= 0) {
                s.delete(s.length() - 1, s.length());
            }
            s.append('\u2026');
        }
        for (int k = 0; k + 1 < h.marks.length; k += 2) {
            int a = Math.max(h.marks[k], start) + shift;
            int b = Math.min(h.marks[k + 1], end) + shift;
            if (b <= a || b > s.length()) {
                continue;
            }
            s.setSpan(new ForegroundColorSpan(Palette.ACCENT_TEXT), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                s.setSpan(new android.text.style.TypefaceSpan(Palette.bodyStrong(this)), a, b,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                s.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.TOP);
        r.setPadding(dp(16), dp(14), dp(16), dp(14));
        r.setBackground(Ui.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)));
        r.setClickable(true);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Diag.log(SearchActivity.this, "search: chosen paragraph " + h.paragraph);
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_INDEX, h.paragraph));
                finish();
            }
        });
        TextView words = Ui.text(this, s, 15, Palette.INK);
        words.setLineSpacing(0, 1.3f);
        r.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView n = Ui.text(this, getString(R.string.search_paragraph, number), 12, Palette.HINT);
        n.setSingleLine(true);
        LinearLayout.LayoutParams np = Ui.lp(Ui.WRAP, Ui.WRAP);
        np.leftMargin = dp(12);
        np.topMargin = dp(2);
        r.addView(n, np);
        return r;
    }

    private int dp(float v) {
        return Kit.dp(this, v);
    }
}
