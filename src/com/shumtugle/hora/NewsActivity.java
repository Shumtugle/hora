package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The paper's sources: the feeds switched on, and the catalogue by rubric. Opened
 * with a rubric's key, the same room lists that rubric's feeds. Only languages the
 * voices can read aloud are offered; English ones appear with the English voice.
 */
public final class NewsActivity extends Activity {
    static final String EXTRA_RUBRIC = "rubric";

    private int built;
    private LinearLayout list;
    /** The count over the switched-on feeds, changed in place as feeds are switched. */
    private TextView count;
    /** The rubric this room shows; null for the sources themselves. */
    private String rubric;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        rubric = getIntent().getStringExtra(EXTRA_RUBRIC);
        News.Rubric r = rubric == null ? null : News.rubric(this, rubric);
        list = Kit.room(this, r == null ? getString(R.string.news_title) : nameOf(r));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        show();
    }

    private int dp(float v) {
        return Kit.dp(this, v);
    }

    private String nameOf(News.Rubric r) {
        String lang = getResources().getConfiguration().getLocales().get(0).getLanguage();
        return "ru".equals(lang) ? r.nameRu : r.nameEn;
    }

    private String langWord(String lang) {
        return getString("en".equals(lang) ? R.string.news_lang_en : R.string.news_lang_ru);
    }

    /** Everything under the header, drawn anew after each change. */
    private void show() {
        while (list.getChildCount() > 1) {
            list.removeViewAt(1);
        }
        if (rubric != null) {
            showRubric();
            return;
        }
        list.addView(Kit.lead(this, getString(R.string.news_lead)));
        List<News.Feed> on = News.chosen(this);
        count = Kit.section(this, "");
        showCount();
        list.addView(count);
        // The first feeds always stand here, on or off; then whatever else was switched on.
        List<News.Feed> rows = new ArrayList<News.Feed>();
        for (News.Starter s : News.starters(this)) {
            rows.add(s.feed);
        }
        for (News.Feed f : on) {
            boolean there = false;
            for (News.Feed g : rows) {
                there |= g.url.equals(f.url);
            }
            if (!there) {
                rows.add(f);
            }
        }
        LinearLayout plate = Kit.plate(this);
        for (News.Feed f : rows) {
            boolean readable = News.readable(this, f.lang);
            boolean chosen = News.isChosen(this, f.url);
            if (!readable && !chosen) {
                continue;
            }
            // A feed switched on in a language that cannot be read now stays in sight, so it can be switched off.
            String sub = News.host(f.url) + " \u00b7 " + langWord(f.lang)
                    + (readable ? "" : " \u00b7 " + getString(R.string.news_needs_english));
            plate.addView(feedRow(f, sub), Kit.wide());
        }
        list.addView(plate, Kit.wide());

        list.addView(Kit.section(this, getString(R.string.news_catalog)));
        LinearLayout cats = Kit.plate(this);
        boolean hidden = false;
        for (final News.Rubric r : News.catalog(this)) {
            if (!News.readable(this, r.lang)) {
                hidden = true;
                continue;
            }
            String sub = getResources().getQuantityString(R.plurals.news_feeds, r.feeds.size(), r.feeds.size())
                    + " \u00b7 " + langWord(r.lang);
            cats.addView(navRow(nameOf(r), sub, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(NewsActivity.this, NewsActivity.class).putExtra(EXTRA_RUBRIC, r.key));
                }
            }), Kit.wide());
        }
        list.addView(cats, Kit.wide());
        if (hidden) {
            TextView note = Ui.text(this, getString(R.string.news_no_english), 13, Palette.MUTED);
            note.setLineSpacing(0, 1.3f);
            note.setPadding(dp(4), dp(12), dp(4), 0);
            list.addView(note, Kit.wide());
        }
    }

    private void showRubric() {
        News.Rubric r = News.rubric(this, rubric);
        if (r == null) {
            finish();
            return;
        }
        list.addView(Kit.lead(this, getString(R.string.news_rubric_lead)));
        LinearLayout plate = Kit.plate(this);
        for (String url : r.feeds) {
            String host = News.host(url);
            String sub = News.learned(this, url) != null ? host : getString(R.string.news_not_yet);
            plate.addView(feedRow(new News.Feed(url, r.lang), sub), Kit.wide());
        }
        LinearLayout.LayoutParams pp = Kit.wide();
        pp.topMargin = dp(16);
        list.addView(plate, pp);
    }

    private void showCount() {
        if (count != null) {
            // All switched on that can be read, even past the limit, so a count over it is seen as one.
            int on = 0;
            for (News.Feed f : News.chosen(this)) {
                if (News.readable(this, f.lang)) {
                    on++;
                }
            }
            count.setText(getString(R.string.news_on, on, News.MAX_CHOSEN));
        }
    }

    /**
     * A feed with its switch. A switch that took is left as it is, the count changing in place; one
     * refused for the limit is put back, with a word why.
     */
    private View feedRow(final News.Feed f, CharSequence sub) {
        return Kit.rowToggle(this, News.name(this, f.url), sub, News.isChosen(this, f.url), new Kit.Flip() {
            @Override
            public void flipped(boolean on) {
                if (!News.choose(NewsActivity.this, f, on)) {
                    Toast.makeText(NewsActivity.this, getString(R.string.news_limit, News.MAX_CHOSEN),
                            Toast.LENGTH_LONG).show();
                    show();
                    return;
                }
                Diag.mark(NewsActivity.this, "news: " + News.host(f.url) + (on ? " on" : " off"));
                showCount();
            }
        });
    }

    /** A row that leads on: a title, a line under it, and the chevron. */
    private View navRow(CharSequence title, CharSequence sub, View.OnClickListener l) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(60));
        r.setPadding(dp(20), dp(10), dp(20), dp(10));
        r.setBackground(Ui.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)));
        LinearLayout words = Ui.column(this);
        words.addView(Ui.text(this, title, 16, Palette.INK));
        words.addView(Ui.text(this, sub, 12, Palette.MUTED));
        r.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView mark = Ui.text(this, "\u203a", 20, Palette.MUTED);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.addView(mark);
        r.setContentDescription(title + ", " + sub);
        r.setOnClickListener(l);
        return r;
    }
}
