package com.shumtugle.hora;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The journal as a list, newest first: time on the left, where it came from
 * and what happened on the right. Events stand out, details stay quiet,
 * trouble is in the accent color, and a line marks each new process (an
 * update or a restart). Above it: copy, save, test the engine, clear.
 */
public final class JournalActivity extends Activity {
    private static final long REFRESH_MS = 3000;
    private static final long CLEAR_ARM_MS = 3000;
    private static final String[] TROUBLE = {"silent", "failed", "cut", "error", "refused", "cannot", "dropped"};

    /** The look this screen was built with. */
    private int built;
    private TextToSpeech client;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Entry> all = new ArrayList<Entry>();
    private final List<Entry> shown = new ArrayList<Entry>();
    private boolean eventsOnly;
    private boolean clearArmed;
    private ListView listView;
    private TextView empty;
    private TextView allTab;
    private TextView eventsTab;
    private TextView clear;
    private final Adapter adapter = new Adapter();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            // Only while the newest lines are in view, so reading older ones is not disturbed.
            if (listView.getFirstVisiblePosition() == 0) {
                refresh();
            }
            main.postDelayed(this, REFRESH_MS);
        }
    };

    /** One journal line, or a mark where a new process begins. */
    private static final class Entry {
        String raw;
        String day;
        String time;
        int pid;
        String source;
        String text;
        boolean event;
        boolean trouble;
        boolean processMark;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.BG);
        root.setPadding(dp(24), dp(32), dp(24), 0);
        root.addView(Ui.header(this, getString(R.string.journal), false),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        TextView note = text(getString(R.string.journal_note), 14, Palette.MUTED, Palette.body(this));
        LinearLayout.LayoutParams np = wrap();
        np.topMargin = dp(12);
        root.addView(note, np);

        LinearLayout row1 = new LinearLayout(this);
        LinearLayout row2 = new LinearLayout(this);
        row1.addView(action(R.string.journal_copy, true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy(Diag.read(JournalActivity.this));
            }
        }), half(false));
        row1.addView(action(R.string.journal_save, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        }), half(true));
        row2.addView(action(R.string.journal_test, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testSystemPath();
            }
        }), half(false));
        clear = action(R.string.journal_clear, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearTapped();
            }
        });
        row2.addView(clear, half(true));
        LinearLayout.LayoutParams r1 = full();
        r1.topMargin = dp(24);
        root.addView(row1, r1);
        LinearLayout.LayoutParams r2 = full();
        r2.topMargin = dp(10);
        root.addView(row2, r2);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(round(Palette.SURFACE, Palette.LINE, dp(24)));
        allTab = tab(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                eventsOnly = false;
                show();
            }
        });
        eventsTab = tab(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                eventsOnly = true;
                show();
            }
        });
        tabs.addView(allTab, new LinearLayout.LayoutParams(0, dp(40), 1f));
        tabs.addView(eventsTab, new LinearLayout.LayoutParams(0, dp(40), 1f));
        LinearLayout.LayoutParams tp = full();
        tp.topMargin = dp(28);
        tp.bottomMargin = dp(8);
        root.addView(tabs, tp);

        listView = new ListView(this);
        listView.setDivider(null);
        listView.setSelector(android.R.color.transparent);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, dp(32));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter);
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Entry e = shown.get(position);
                if (e.processMark) {
                    return false;
                }
                copy(e.raw);
                return true;
            }
        });
        empty = text(getString(R.string.journal_empty), 15, Palette.HINT, Palette.body(this));
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(48), 0, 0);
        listView.setEmptyView(empty);
        root.addView(empty, wrapFull());
        root.addView(listView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        refresh();
        main.postDelayed(tick, REFRESH_MS);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (client != null) {
            client.shutdown();
        }
        super.onDestroy();
    }

    private void refresh() {
        parse(Diag.read(this));
        show();
    }

    private void show() {
        shown.clear();
        int events = 0;
        int lines = 0;
        for (Entry e : all) {
            if (e.processMark) {
                continue;
            }
            lines++;
            if (e.event) {
                events++;
            }
        }
        for (Entry e : all) {
            if (!eventsOnly || e.event || e.processMark) {
                shown.add(e);
            }
        }
        allTab.setText(getString(R.string.journal_all, lines));
        eventsTab.setText(getString(R.string.journal_events, events));
        paintTab(allTab, !eventsOnly);
        paintTab(eventsTab, eventsOnly);
        adapter.notifyDataSetChanged();
    }

    /** Turns the file into entries, newest first, with a mark wherever the process changes. */
    private void parse(String text) {
        all.clear();
        List<Entry> lines = new ArrayList<Entry>();
        Entry last = null;
        for (String line : text.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int close = line.indexOf("] ");
            int open = line.indexOf(" [");
            if (line.length() < 20 || !Character.isDigit(line.charAt(0)) || open < 0 || close < open) {
                // The rest of a trace belongs to the line above.
                if (last != null) {
                    last.text += "\n" + line.trim();
                    last.raw += "\n" + line;
                }
                continue;
            }
            Entry e = new Entry();
            e.raw = line;
            e.day = line.substring(0, 5);
            e.time = line.substring(6, Math.min(14, open));
            try {
                e.pid = Integer.parseInt(line.substring(open + 2, close));
            } catch (NumberFormatException x) {
                e.pid = 0;
            }
            String msg = line.substring(close + 2);
            e.event = msg.startsWith("* ");
            if (e.event) {
                msg = msg.substring(2);
            }
            int colon = msg.indexOf(": ");
            if (colon > 0 && colon < 12) {
                e.source = msg.substring(0, colon);
                msg = msg.substring(colon + 2);
            } else {
                e.source = "";
            }
            e.text = msg;
            for (String t : TROUBLE) {
                if (msg.startsWith(t) || msg.contains(" " + t)) {
                    e.trouble = true;
                    break;
                }
            }
            lines.add(e);
            last = e;
        }
        for (int i = 0; i < lines.size(); i++) {
            Entry e = lines.get(i);
            if (i > 0 && e.pid != lines.get(i - 1).pid) {
                Entry m = new Entry();
                m.processMark = true;
                m.pid = e.pid;
                all.add(m);
            }
            all.add(e);
        }
        Collections.reverse(all);
    }

    private String sourceName(String s) {
        switch (s) {
            case "book": return getString(R.string.journal_src_book);
            case "herald": return getString(R.string.journal_src_herald);
            case "engine": return getString(R.string.journal_src_engine);
            case "voice": return getString(R.string.journal_src_voice);
            case "test": return getString(R.string.journal_src_test);
            case "weather": return getString(R.string.journal_src_weather);
            case "place": return getString(R.string.journal_src_place);
            case "check": return getString(R.string.journal_src_check);
            default: return s;
        }
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int i) {
            return shown.get(i).processMark ? 1 : 0;
        }

        @Override
        public View getView(int i, View old, ViewGroup parent) {
            Entry e = shown.get(i);
            return e.processMark ? markView(e) : entryView(e);
        }
    }

    private View markView(Entry e) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(14));
        View l = new View(this);
        l.setBackgroundColor(Palette.LINE);
        View r = new View(this);
        r.setBackgroundColor(Palette.LINE);
        TextView t = text(getString(R.string.journal_process, e.pid), 12, Palette.HINT, Palette.body(this));
        t.setPadding(dp(12), 0, dp(12), 0);
        row.addView(l, new LinearLayout.LayoutParams(0, dp(1), 1f));
        row.addView(t, wrap());
        row.addView(r, new LinearLayout.LayoutParams(0, dp(1), 1f));
        return row;
    }

    private View entryView(Entry e) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(e.event ? 12 : 8), 0, dp(e.event ? 12 : 8));

        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        TextView time = text(e.time, 13, e.event ? Palette.INK : Palette.HINT, Typeface.MONOSPACE);
        when.addView(time);
        String today = new SimpleDateFormat("MM-dd", Locale.ROOT).format(new Date());
        if (!today.equals(e.day)) {
            when.addView(text(e.day.substring(3) + "." + e.day.substring(0, 2), 11, Palette.HINT,
                    Typeface.MONOSPACE));
        }
        row.addView(when, new LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT));

        View bar = new View(this);
        bar.setBackground(round(e.trouble ? Palette.ACCENT : e.event ? Palette.RULE : 0, 0, dp(2)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT);
        bp.rightMargin = dp(14);
        row.addView(bar, bp);

        LinearLayout what = new LinearLayout(this);
        what.setOrientation(LinearLayout.VERTICAL);
        if (!e.source.isEmpty()) {
            TextView src = text(sourceName(e.source).toUpperCase(Locale.getDefault()), 11,
                    e.event ? Palette.ACCENT_TEXT : Palette.HINT, Palette.bodyStrong(this));
            src.setLetterSpacing(0.1f);
            what.addView(src);
        }
        TextView msg = text(e.text, e.event ? 15 : 13,
                e.trouble ? Palette.ACCENT_TEXT : e.event ? Palette.INK : Palette.MUTED, Palette.body(this));
        msg.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams mp = wrapFull();
        mp.topMargin = dp(2);
        what.addView(msg, mp);
        row.addView(what, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.journal), text));
        // Newer systems confirm a copy themselves.
        if (android.os.Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, R.string.journal_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void save() {
        try {
            String name = WavOut.saveBytesToDownloads(this, "hora-journal.txt", Diag.read(this).getBytes("UTF-8"));
            Toast.makeText(this, getString(R.string.reference_exported, name), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /** Clearing takes two taps: the first asks, the second clears. */
    private void clearTapped() {
        if (!clearArmed) {
            clearArmed = true;
            clear.setText(R.string.journal_clear_sure);
            clear.setTextColor(Palette.ACCENT_TEXT);
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    disarm();
                }
            }, CLEAR_ARM_MS);
            return;
        }
        disarm();
        Diag.clear(this);
        refresh();
    }

    private void disarm() {
        clearArmed = false;
        clear.setText(R.string.journal_clear);
        clear.setTextColor(Palette.INK);
    }

    /** Speaks through the system service bound to this app's own engine. */
    private void testSystemPath() {
        if (client != null) {
            client.shutdown();
        }
        Diag.mark(this, "test: binding the system speech service");
        client = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                Diag.mark(JournalActivity.this, "test: init status " + status);
                if (status != TextToSpeech.SUCCESS) {
                    refreshLater();
                    return;
                }
                int lang = client.setLanguage(SpeechLanguage.locale());
                Diag.mark(JournalActivity.this, "test: setLanguage -> " + lang + ", voice "
                        + (client.getVoice() == null ? "none" : client.getVoice().getName()));
                client.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                        Diag.mark(JournalActivity.this, "test: started");
                        refreshLater();
                    }

                    @Override
                    public void onDone(String id) {
                        Diag.mark(JournalActivity.this, "test: done");
                        refreshLater();
                    }

                    @Override
                    public void onError(String id) {
                        Diag.mark(JournalActivity.this, "test: error");
                        refreshLater();
                    }

                    @Override
                    public void onError(String id, int code) {
                        Diag.mark(JournalActivity.this, "test: error code " + code);
                        refreshLater();
                    }
                });
                int r = client.speak(SpeechLanguage.resources(JournalActivity.this)
                        .getString(R.string.tts_sample), TextToSpeech.QUEUE_FLUSH, null, "test");
                Diag.mark(JournalActivity.this, "test: speak -> " + r);
                refreshLater();
            }
        }, getPackageName());
    }

    private void refreshLater() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        });
    }

    private TextView action(int label, boolean primary, View.OnClickListener l) {
        TextView b = Ui.pill(this, getString(label), primary ? Palette.ACCENT : Palette.SURFACE,
                primary ? 0 : Palette.LINE, primary ? Palette.ON_ACCENT : Palette.INK, primary);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setOnClickListener(l);
        return b;
    }

    private TextView tab(View.OnClickListener l) {
        TextView t = text("", 14, Palette.INK, Palette.body(this));
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(l);
        return t;
    }

    private void paintTab(TextView t, boolean on) {
        t.setBackground(on ? round(Palette.RAISED, 0, dp(20)) : null);
        t.setTextColor(on ? Palette.INK : Palette.MUTED);
        t.setTypeface(on ? Palette.bodyStrong(this) : Palette.body(this));
    }

    private GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (stroke != 0) {
            g.setStroke(dp(1), stroke);
        }
        return g;
    }

    private TextView text(String s, int sp, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        return t;
    }

    private LinearLayout.LayoutParams half(boolean right) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), 1f);
        if (right) {
            p.leftMargin = dp(10);
        }
        return p;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrapFull() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }
}
