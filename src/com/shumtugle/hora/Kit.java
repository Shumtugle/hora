package com.shumtugle.hora;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * The parts every screen is built from, one look per role: a room with its
 * header, groups of rows on plates, the toggle, the state line, buttons,
 * links and the sheet. A screen arranges these; it does not style its own.
 */
final class Kit {
    static final int PAD = 24;
    static final int GAP_GROUP = 36;
    static final int ROW = 56;
    static final int PLATE_RADIUS = 24;

    private Kit() {
    }

    /** A room: header with the way back, then a scrolling column. Returns the column. */
    static LinearLayout room(Activity a, CharSequence title) {
        LinearLayout list = Ui.column(a);
        list.setPadding(dp(a, PAD), dp(a, 32), dp(a, PAD), dp(a, 40));
        list.addView(Ui.header(a, title, false),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 48)));
        ScrollView scroll = new ScrollView(a);
        scroll.setBackgroundColor(Palette.BG);
        scroll.addView(list);
        a.setContentView(scroll);
        return list;
    }

    /** At most two lines under the header saying what the room is for. */
    static TextView lead(Context c, CharSequence s) {
        TextView t = Ui.text(c, s, 15, Palette.MUTED);
        t.setLineSpacing(0, 1.25f);
        t.setPadding(0, dp(c, 16), 0, 0);
        return t;
    }

    /** A group's title; the plate follows it. */
    static TextView section(Context c, CharSequence s) {
        TextView t = Ui.text(c, s, 13, Palette.MUTED);
        t.setTypeface(Palette.bodyStrong(c));
        t.setPadding(0, dp(c, GAP_GROUP), 0, dp(c, 8));
        return t;
    }

    /** The surface that holds a group of rows. */
    static LinearLayout plate(Context c) {
        LinearLayout p = Ui.column(c);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Palette.SURFACE);
        g.setCornerRadius(dp(c, PLATE_RADIUS));
        if (Palette.contrast()) {
            g.setStroke(dp(c, 1), Palette.LINE);
        }
        p.setBackground(g);
        p.setClipToOutline(true);
        return p;
    }

    private static LinearLayout bareRow(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, ROW));
        r.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        return r;
    }

    /** Title and an optional quieter line under it, taking the row's free width. */
    private static LinearLayout words(Context c, CharSequence title, CharSequence sub) {
        LinearLayout w = Ui.column(c);
        w.addView(Ui.text(c, title, 17, Palette.INK));
        if (sub != null && sub.length() > 0) {
            w.addView(Ui.text(c, sub, 13, Palette.MUTED));
        }
        return w;
    }

    /** A row that is itself a choice in a sheet's list: one line of words, the whole row touchable. */
    static TextView rowChoice(Context c, CharSequence title, View.OnClickListener l) {
        TextView t = Ui.text(c, title, 17, Palette.INK);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(dp(c, ROW));
        t.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        t.setBackground(Ui.pressable(new ColorDrawable(Color.TRANSPARENT)));
        t.setOnClickListener(l);
        return t;
    }

    static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    static LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    /** A row that opens something: title, value, chevron. The value view is returned through the row's tag. */
    static LinearLayout rowNav(Context c, CharSequence title, CharSequence value, View.OnClickListener l) {
        LinearLayout r = bareRow(c);
        r.addView(words(c, title, null), fill());
        TextView v = Ui.text(c, value == null ? "" : value, 17, Palette.MUTED);
        v.setPadding(dp(c, 12), 0, dp(c, 8), 0);
        r.addView(v);
        r.addView(Ui.text(c, "\u203a", 20, Palette.MUTED));
        r.setTag(v);
        r.setBackground(Ui.pressable(new ColorDrawable(Color.TRANSPARENT)));
        r.setOnClickListener(l);
        return r;
    }

    /**
     * A row that leads to another room: a round icon, a title with a quieter line
     * under it, and a chevron. The quiet line is returned through the row's tag.
     */
    static LinearLayout rowEntry(Context c, int icon, CharSequence title, CharSequence sub, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, 64));
        r.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        ImageView i = new ImageView(c);
        i.setImageResource(icon);
        i.setImageTintList(android.content.res.ColorStateList.valueOf(Palette.INK));
        i.setScaleType(ImageView.ScaleType.CENTER);
        i.setBackground(Ui.oval(Palette.RAISED, 0, 0, c));
        i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.addView(i, new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(c, title, 17, Palette.INK);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(t);
        TextView s = Ui.text(c, sub == null ? "" : sub, 13, Palette.MUTED);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(s);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        wp.leftMargin = dp(c, 14);
        r.addView(words, wp);
        r.addView(Ui.text(c, "\u203a", 20, Palette.HINT));
        r.setTag(new TextView[] {t, s});
        r.setBackground(Ui.pressable(new ColorDrawable(Color.TRANSPARENT)));
        r.setOnClickListener(l);
        return r;
    }

    /** Changes the title and the quiet line of a row made with rowEntry. */
    static void setEntry(View row, CharSequence title, CharSequence sub) {
        TextView[] t = (TextView[]) row.getTag();
        t[0].setText(title);
        t[1].setText(sub);
    }

    /** Sets the value shown by a row made with rowNav. */
    static void setValue(View row, CharSequence value) {
        ((TextView) row.getTag()).setText(value);
    }

    /** Changes as the user flips a toggle row. */
    interface Flip {
        void flipped(boolean on);
    }

    /** A row with Hora's own toggle; tapping anywhere on the row flips it. */
    static LinearLayout rowToggle(Context c, CharSequence title, CharSequence sub, boolean on, final Flip f) {
        final LinearLayout r = bareRow(c);
        r.addView(words(c, title, sub), fill());
        final Toggle t = new Toggle(c, on);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(c, 52), dp(c, 32));
        tp.leftMargin = dp(c, 12);
        r.addView(t, tp);
        r.setBackground(Ui.pressable(new ColorDrawable(Color.TRANSPARENT)));
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                t.set(!t.on);
                r.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
                if (f != null) {
                    f.flipped(t.on);
                }
            }
        });
        r.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Switch");
                info.setCheckable(true);
                info.setChecked(t.on);
            }
        });
        return r;
    }

    /** Hora's toggle: off is quiet, on is the accent. Drawn, not a system widget. */
    static final class Toggle extends View {
        boolean on;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        Toggle(Context c, boolean on) {
            super(c);
            this.on = on;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(boolean value) {
            on = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas k) {
            float w = getWidth();
            float h = getHeight();
            float r = h / 2f;
            float line = dp(getContext(), 1);
            box.set(0, 0, w, h);
            paint.setStyle(Paint.Style.FILL);
            if (on) {
                paint.setColor(Palette.ACCENT);
                k.drawRoundRect(box, r, r, paint);
                paint.setColor(Palette.ON_ACCENT);
                k.drawCircle(w - r, r, r - dp(getContext(), 4), paint);
            } else {
                paint.setColor(Palette.RAISED);
                k.drawRoundRect(box, r, r, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(line);
                paint.setColor(Palette.LINE);
                box.inset(line / 2f, line / 2f);
                k.drawRoundRect(box, r, r, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Palette.HINT);
                k.drawCircle(r, r, r - dp(getContext(), 6), paint);
            }
        }
    }

    /** What a state line says about itself. */
    enum Mood { FINE, TROUBLE, UNKNOWN }

    /** A state in words, with a dot: ink when fine, accent for trouble, hint when unknown. */
    static LinearLayout status(Context c, CharSequence s, Mood m) {
        LinearLayout r = bareRow(c);
        View dot = new View(c);
        dot.setBackground(Ui.oval(m == Mood.TROUBLE ? Palette.ACCENT : m == Mood.FINE ? Palette.INK : Palette.HINT,
                0, 0, c));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(dp(c, 10), dp(c, 10));
        dp.rightMargin = dp(c, 14);
        r.addView(dot, dp);
        r.addView(Ui.text(c, s, 17, Palette.INK), fill());
        return r;
    }

    /** The one main action of a room, full width. */
    static TextView primary(Context c, CharSequence label, View.OnClickListener l) {
        TextView b = Ui.pill(c, label, Palette.ACCENT, 0, Palette.ON_ACCENT, true);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setMinHeight(dp(c, ROW));
        b.setOnClickListener(l);
        return b;
    }

    static TextView secondary(Context c, CharSequence label, View.OnClickListener l) {
        TextView b = Ui.pill(c, label, Palette.SURFACE, Palette.LINE, Palette.INK, false);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setMinHeight(dp(c, ROW));
        b.setOnClickListener(l);
        return b;
    }

    /** Accent text without a frame, for the lesser ways out. */
    static TextView link(Context c, CharSequence label, View.OnClickListener l) {
        TextView t = Ui.text(c, label, 16, Palette.ACCENT_TEXT);
        t.setTypeface(Palette.bodyStrong(c));
        t.setPadding(0, dp(c, 12), 0, dp(c, 12));
        t.setOnClickListener(l);
        return t;
    }

    /** Lays parts out one under another with the given gap. */
    static LinearLayout.LayoutParams below(Context c, int gapDp) {
        LinearLayout.LayoutParams p = wide();
        p.topMargin = dp(c, gapDp);
        return p;
    }

    /** A sheet rising from the bottom over a dimmed screen: title, content, one main action. */
    static Dialog sheet(Activity a, CharSequence title, View content, CharSequence action, final Runnable onAction) {
        return sheet(a, title, content, action, onAction, null);
    }

    /**
     * The same sheet with lesser actions under the main one. The title may be
     * null when the content names itself. The lesser actions close the sheet
     * themselves when they are done.
     */
    static Dialog sheet(Activity a, CharSequence title, View content, CharSequence action, final Runnable onAction,
            View lesser) {
        final Dialog d = new Dialog(a, Palette.sheetTheme());
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = Ui.column(a);
        card.setBackground(Ui.round(Palette.SURFACE, PLATE_RADIUS, a));
        card.setPadding(dp(a, PAD), dp(a, PAD), dp(a, PAD), dp(a, 12));
        if (title != null) {
            card.addView(Ui.title(a, title, 28));
        }
        if (content != null) {
            card.addView(content, title == null ? wide() : below(a, 16));
        }
        if (action != null) {
            TextView go = primary(a, action, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    if (onAction != null) {
                        onAction.run();
                    }
                }
            });
            card.addView(go, below(a, 24));
        }
        if (lesser != null) {
            card.addView(lesser, below(a, 12));
        }
        TextView cancel = link(a, a.getString(R.string.kit_cancel), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        cancel.setTextColor(Palette.INK);
        cancel.setTypeface(Palette.body(a));
        cancel.setGravity(Gravity.CENTER);
        card.addView(cancel, below(a, 4));
        d.setContentView(card);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            int side = dp(a, 12);
            w.getDecorView().setPadding(side, 0, side, side);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.7f);
        }
        d.show();
        return d;
    }

    /** Receives a time picked in a sheet, as minutes after midnight. */
    interface TimeSet {
        void set(int minutes);
    }

    /** A sheet with hours and minutes stepped by plus and minus; minutes go by fifteen. */
    static void timeSheet(Activity a, CharSequence title, int minutes, final TimeSet done) {
        final int[] h = {minutes / 60};
        final int[] m = {(minutes % 60) / 15 * 15};
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER);
        final TextView hours = Ui.title(a, "", 56);
        final TextView mins = Ui.title(a, "", 56);
        final Runnable show = new Runnable() {
            @Override
            public void run() {
                hours.setText(String.format(Locale.ROOT, "%02d", h[0]));
                mins.setText(String.format(Locale.ROOT, "%02d", m[0]));
            }
        };
        show.run();
        row.addView(stepper(a, hours, new int[] {-1, 1}, new Step() {
            @Override
            public void step(int by) {
                h[0] = (h[0] + by + 24) % 24;
                show.run();
            }
        }));
        TextView colon = Ui.title(a, ":", 56);
        colon.setPadding(dp(a, 8), 0, dp(a, 8), 0);
        row.addView(colon);
        row.addView(stepper(a, mins, new int[] {-15, 15}, new Step() {
            @Override
            public void step(int by) {
                m[0] = (m[0] + by + 60) % 60;
                show.run();
            }
        }));
        sheet(a, title, row, a.getString(R.string.kit_done), new Runnable() {
            @Override
            public void run() {
                done.set(h[0] * 60 + m[0]);
            }
        });
    }

    private interface Step {
        void step(int by);
    }

    private static LinearLayout stepper(Context c, TextView number, final int[] by, final Step s) {
        LinearLayout col = Ui.column(c);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        String[] marks = {"\u2212", "+"};
        TextView[] buttons = new TextView[2];
        for (int i = 0; i < 2; i++) {
            final int amount = by[i];
            TextView b = Ui.pill(c, marks[i], Palette.RAISED, 0, Palette.INK, true);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            b.setContentDescription(marks[i] + " " + Math.abs(amount));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    s.step(amount);
                }
            });
            buttons[i] = b;
        }
        col.addView(buttons[1], new LinearLayout.LayoutParams(dp(c, 64), dp(c, 48)));
        col.addView(number);
        col.addView(buttons[0], new LinearLayout.LayoutParams(dp(c, 64), dp(c, 48)));
        return col;
    }

    /**
     * Lets a scene screen run under the status and navigation bars, as its
     * scene does: the window draws edge to edge, and the content keeps the
     * given padding plus whatever the bars take.
     */
    static void edgeToEdge(Activity a, final View content, final int left, final int top, final int right,
            final int bottom) {
        android.view.Window w = a.getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            w.setStatusBarContrastEnforced(false);
            w.setNavigationBarContrastEnforced(false);
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        content.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                int below = in.getSystemWindowInsetBottom();
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    // The keyboard counts as well, so a field at the bottom rises above it.
                    below = in.getInsets(android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.ime()).bottom;
                }
                v.setPadding(left, top + in.getSystemWindowInsetTop(), right, bottom + below);
                return in;
            }
        });
        content.requestApplyInsets();
    }

    /** The reader's scene laid behind a scene screen, blurred; none in the contrast look. */
    static void backdrop(Context c, ImageView v, int voice) {
        if (Palette.contrast()) {
            v.setImageDrawable(null);
            return;
        }
        boolean soft = android.os.Build.VERSION.SDK_INT >= 31;
        // Where the system can blur, a small picture is blurred by it; elsewhere a tiny one is simply stretched.
        v.setImageBitmap(Widgets.scene(c, voice, Widgets.sceneNumber(c, voice), soft ? 270 : 40));
        if (soft) {
            float r = dp(c, 18);
            v.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(r, r,
                    android.graphics.Shader.TileMode.CLAMP));
        }
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setScaleX(1.08f);
        v.setScaleY(1.08f);
    }

    /** Hears which segment was chosen. */
    interface Chosen {
        void chose(int which);
    }

    /**
     * Segments: a choice among two to four, made in place. The chosen one sits
     * on a raised plate in bold ink; the others are muted.
     */
    static LinearLayout segments(final Context c, String[] labels, int chosen, final Chosen l) {
        final LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = Ui.round(Palette.SURFACE, 28, c);
        bg.setStroke(dp(c, 1), Palette.LINE);
        box.setBackground(bg);
        box.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            boolean on = i == chosen;
            TextView t = Ui.text(c, labels[i], 15, on ? Palette.INK : Palette.MUTED);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setTypeface(on ? Palette.bodyStrong(c) : Palette.body(c));
            t.setBackground(on ? Ui.round(Palette.RAISED, 20, c)
                    : Ui.pressable(new ColorDrawable(Color.TRANSPARENT)));
            t.setSelected(on);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int k = 0; k < box.getChildCount(); k++) {
                        TextView o = (TextView) box.getChildAt(k);
                        boolean now = k == which;
                        o.setTextColor(now ? Palette.INK : Palette.MUTED);
                        o.setTypeface(now ? Palette.bodyStrong(c) : Palette.body(c));
                        o.setBackground(now ? Ui.round(Palette.RAISED, 20, c)
                                : Ui.pressable(new ColorDrawable(Color.TRANSPARENT)));
                        o.setSelected(now);
                    }
                    l.chose(which);
                }
            });
            box.addView(t, new LinearLayout.LayoutParams(0, dp(c, 40), 1f));
        }
        return box;
    }

    /** Hears whether any text is selected. */
    interface Selected {
        void changed(boolean any);
    }

    /**
     * Text the user may select inside a sheet. The system's floating bar over
     * a selection is kept silent: it cannot wear the look's colors, so the
     * sheet offers its own pair of buttons instead. Selection and handles are
     * in the accent text color.
     */
    static final class Selectable extends TextView {
        private Selected listener;

        Selectable(Context c) {
            super(c);
            setTextIsSelectable(true);
            Ui.field(this);
            setCustomSelectionActionModeCallback(new android.view.ActionMode.Callback() {
                @Override
                public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                    menu.clear();
                    return true;
                }

                @Override
                public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                    menu.clear();
                    return true;
                }

                @Override
                public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) {
                    return false;
                }

                @Override
                public void onDestroyActionMode(android.view.ActionMode mode) {
                }
            });
        }

        void onSelection(Selected l) {
            listener = l;
        }

        /** The selected words, or an empty string. */
        String chosen() {
            int a = Math.max(0, Math.min(getSelectionStart(), getSelectionEnd()));
            int b = Math.max(0, Math.max(getSelectionStart(), getSelectionEnd()));
            return b > a ? getText().subSequence(a, b).toString() : "";
        }

        void chooseAll() {
            CharSequence t = getText();
            if (t instanceof android.text.Spannable) {
                requestFocus();
                android.text.Selection.setSelection((android.text.Spannable) t, 0, t.length());
            }
        }

        @Override
        protected void onSelectionChanged(int start, int end) {
            super.onSelectionChanged(start, end);
            if (listener != null) {
                listener.changed(end != start);
            }
        }
    }

    /** A scrolling frame that grows with its content up to a ceiling, then scrolls. */
    static final class Capped extends android.widget.ScrollView {
        private int ceiling;

        Capped(Context c, int ceilingPx) {
            super(c);
            ceiling = ceilingPx;
            setVerticalScrollBarEnabled(false);
            setFadingEdgeLength(dp(c, 24));
            setVerticalFadingEdgeEnabled(true);
        }

        void setCeiling(int px) {
            ceiling = px;
            requestLayout();
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(ceiling, MeasureSpec.AT_MOST));
        }
    }

    /** A thin progress line: the done part in the accent, the rest in the line color. */
    static final class Bar extends View {
        private float part;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Bar(Context c) {
            super(c);
        }

        void set(float done) {
            part = Math.max(0f, Math.min(1f, done));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas k) {
            float h = getHeight();
            float r = h / 2f;
            paint.setColor(Palette.LINE);
            k.drawRoundRect(0, 0, getWidth(), h, r, r, paint);
            if (part > 0f) {
                paint.setColor(Palette.ACCENT);
                k.drawRoundRect(0, 0, Math.max(h, getWidth() * part), h, r, r, paint);
            }
        }
    }

    /** A plate saying how far a long job got: a line of words and the bar under it. */
    static LinearLayout progress(Context c, TextView words, Bar bar) {
        LinearLayout p = plate(c);
        p.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 16));
        p.addView(words);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 4));
        bp.topMargin = dp(c, 10);
        p.addView(bar, bp);
        return p;
    }

    /**
     * Holds one fixed-size child and shrinks it, never grows it, to whatever
     * height the screen leaves. A portrait drawn for a tall phone then fits a
     * shorter one, and the words under it are never covered.
     */
    static final class Fit extends android.widget.FrameLayout {
        Fit(Context c) {
            super(c);
            setClipChildren(false);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (getChildCount() == 0) {
                return;
            }
            View child = getChildAt(0);
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float k = Math.min(1f, Math.min((r - l) / (float) w, (b - t) / (float) h));
            child.setPivotX(w / 2f);
            child.setPivotY(h / 2f);
            child.setScaleX(k);
            child.setScaleY(k);
        }
    }

    static int dp(Context c, float v) {
        return Ui.dp(c, v);
    }
}
