package com.shumtugle.hora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;

import java.util.Locale;

/**
 * A book cover Hora draws itself: paper, a thread of the accent along the
 * top, the title in the display face and the author in spaced capitals.
 * Books bring no pictures of their own (a text file has none and never will),
 * so one plain cover keeps the shelf whole. Two to three, at any size: the
 * title takes about a sixth of the width, the author about a sixteenth.
 */
final class Cover extends View {
    private static final int PAPER = 0xFFF3E7CF;
    private static final int ON_PAPER = 0xFF2A1215;
    private static final int ON_PAPER_QUIET = 0xFF6E4C46;

    private String title = "";
    private String author = "";
    private final Paint paper = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rule = new Paint();
    private final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint by = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    Cover(Context c) {
        super(c);
        name.setTypeface(Palette.display(c));
        by.setTypeface(Palette.bodyStrong(c));
        by.setLetterSpacing(0.12f);
        setElevation(Kit.dp(c, 6));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius());
            }
        });
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void set(String title, String author) {
        this.title = title == null ? "" : title;
        this.author = author == null ? "" : Book.shortName(author).toUpperCase(Locale.getDefault());
        invalidate();
    }

    private float radius() {
        return Math.min(Kit.dp(getContext(), 10), getWidth() * 0.1f);
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, Math.round(width * 1.5f));
    }

    @Override
    protected void onDraw(Canvas k) {
        int w = getWidth();
        int h = getHeight();
        boolean bold = Palette.contrast();
        paper.setColor(bold ? Palette.BG : PAPER);
        box.set(0, 0, w, h);
        float r = radius();
        k.drawRoundRect(box, r, r, paper);
        if (bold) {
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, w * 0.01f));
            edge.setColor(Palette.ACCENT);
            k.drawRoundRect(box, r, r, edge);
        }
        float pad = w * 0.1f;
        rule.setColor(Palette.ACCENT);
        k.drawRect(pad, pad, w - pad, pad + Math.max(1f, w * 0.0125f), rule);

        int room = Math.max(1, Math.round(w - 2 * pad));
        name.setColor(bold ? Palette.INK : ON_PAPER);
        name.setTextSize(w * 0.15f);
        StaticLayout t = StaticLayout.Builder.obtain(title, 0, title.length(), name, room)
                .setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.95f).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        k.save();
        k.translate(pad, pad * 1.9f);
        t.draw(k);
        k.restore();

        if (!author.isEmpty()) {
            by.setColor(bold ? Palette.INK : ON_PAPER_QUIET);
            by.setTextSize(Math.max(w * 0.0625f, Kit.dp(getContext(), 6)));
            CharSequence line = TextUtils.ellipsize(author, by, room, TextUtils.TruncateAt.END);
            k.drawText(line, 0, line.length(), pad, h - pad, by);
        }
    }
}
