package com.shumtugle.hora;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.TextPaint;
import android.widget.RemoteViews;

import java.util.Locale;

/**
 * A book plate: paper in a double frame, the narrator in an oval medallion,
 * the book's title and a small line in spaced capitals. Paused, a ribbon
 * hangs over the frame, the medallion fades to old paper and the line tells
 * where the bookmark lies. A bigger plate grows the medallion and the title,
 * never the frame's margins; a wide one puts the medallion at the left.
 */
public final class ExlibrisWidget extends AppWidgetProvider {
    private static final int REQUEST = 13;
    private static final float RADIUS = 20f;
    private static final float OUTER = 9f;
    private static final float BETWEEN = 3f;
    private static final float LINE = 1f;
    /** The design's plate is 176 by 184; inside both frames that leaves 148 by 156. */
    private static final float BASE_W = 148f;
    private static final float BASE_H = 156f;
    private static final float MEDAL_W = 70f;
    private static final float MEDAL_H = 86f;
    private static final float RING = 2f;
    private static final float GAP = 7f;
    private static final float TITLE = 22f;
    private static final float CAPTION = 9.5f;
    private static final float RIBBON_W = 18f;
    private static final float RIBBON_H = 46f;
    private static final float RIBBON_RIGHT = 22f;
    private static final int ON_PAPER = 0xFF2A1215;
    private static final int ON_PAPER_QUIET = 0xFF6E4C46;
    private static final int RING_OUT = 0xFFC9B9A0;

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) {
            draw(c, m, id);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
        draw(c, m, id);
    }

    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int id : m.getAppWidgetIds(new ComponentName(c, ExlibrisWidget.class))) {
            draw(c, m, id);
        }
    }

    private static void draw(Context c, AppWidgetManager m, int id) {
        int[] dp = Widgets.sizeDp(m, id);
        float s = Widgets.scale(c, dp[0], dp[1]);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_picture);
        v.setImageViewBitmap(R.id.widget_picture,
                picture(c, Math.round(dp[0] * s), Math.round(dp[1] * s), s));
        v.setOnClickPendingIntent(R.id.widget_root, Widgets.tap(c, REQUEST));
        v.setContentDescription(R.id.widget_root, Widgets.describe(c));
        m.updateAppWidget(id, v);
    }

    /** The plate, w by h pixels, s pixels to a dp. */
    static Bitmap picture(Context c, int w, int h, float s) {
        Palette.apply(c);
        boolean bold = Palette.contrast();
        float grow = bold ? 1.2f : 1f;
        int ground = bold ? Palette.BG : Palette.INK;
        int ink = bold ? Palette.INK : ON_PAPER;
        int quiet = bold ? Palette.INK : ON_PAPER_QUIET;
        String title = Reading.title(c);
        boolean has = !title.isEmpty();
        boolean playing = has && Reading.playing(c);
        int voice = Prefs.roleShared(c, Cast.NARRATOR);

        Bitmap out = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas k = new Canvas(out);
        // The paper itself is a smooth rounded shape, not a clip: a clip here would leave stepped corners.
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(ground);
        float r = Math.min(RADIUS * s, Math.min(w, h) / 2f);
        k.drawRoundRect(new RectF(0, 0, w, h), r, r, fill);

        Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(LINE * s * grow);
        frame.setColor(Palette.ACCENT);
        float half = frame.getStrokeWidth() / 2f;
        float o = OUTER * s;
        k.drawRoundRect(new RectF(o + half, o + half, w - o - half, h - o - half), 13 * s, 13 * s, frame);
        float i = o + (LINE + BETWEEN) * s;
        k.drawRoundRect(new RectF(i + half, i + half, w - i - half, h - i - half), 10 * s, 10 * s, frame);
        RectF inner = new RectF(i + LINE * s, i + LINE * s, w - i - LINE * s, h - i - LINE * s);

        String first = has ? title : c.getString(R.string.widget_no_book);
        String second;
        if (!has) {
            second = c.getString(R.string.widget_pick_book);
        } else if (playing) {
            second = c.getString(R.string.widget_exlibris_reads, Cast.name(c, voice));
        } else {
            second = c.getString(R.string.widget_exlibris_bookmark, Reading.index(c) + 1);
        }
        second = second.toUpperCase(Locale.getDefault());

        boolean wide = inner.width() > inner.height() * 1.6f;
        float k1 = wide ? inner.height() / BASE_H : Math.min(inner.width() / BASE_W, inner.height() / BASE_H);
        k1 = Math.max(0.6f, Math.min(2.4f, k1));
        TextPaint name = Widgets.paint(Palette.display(c), TITLE * s * k1 * grow, ink);
        TextPaint sub = Widgets.paint(Palette.bodyStrong(c), Math.max(9f, CAPTION * Math.min(k1, 1.6f)) * s * grow, quiet);
        sub.setLetterSpacing(0.14f);
        Paint.FontMetrics fn = name.getFontMetrics();
        Paint.FontMetrics fs = sub.getFontMetrics();
        float hName = fn.descent - fn.ascent;
        float hSub = fs.descent - fs.ascent;
        float gap = GAP * s * k1;

        RectF medal;
        if (wide) {
            // Wide: the medallion at the left, the words beside it, both centred on the height.
            float mh = Math.min(inner.height() - 2 * 12 * s, MEDAL_H * s * k1);
            float mw = mh * MEDAL_W / MEDAL_H;
            float cy = inner.centerY();
            float left = inner.left + 16 * s;
            medal = new RectF(left, cy - mh / 2f, left + mw, cy + mh / 2f);
            float textLeft = medal.right + 16 * s;
            float room = inner.right - 16 * s - textLeft;
            float y0 = cy - (hName + gap + hSub) / 2f;
            name.setTextAlign(Paint.Align.LEFT);
            sub.setTextAlign(Paint.Align.LEFT);
            k.drawText(Widgets.fit(name, first, room), textLeft, y0 - fn.ascent, name);
            k.drawText(Widgets.fit(sub, second, room), textLeft, y0 + hName + gap - fs.ascent, sub);
        } else {
            float mw = MEDAL_W * s * k1;
            float mh = MEDAL_H * s * k1;
            float block = mh + gap + hName + gap + hSub;
            float top = inner.centerY() - block / 2f;
            float cx = inner.centerX();
            medal = new RectF(cx - mw / 2f, top, cx + mw / 2f, top + mh);
            float room = inner.width() - 2 * 10 * s;
            name.setTextAlign(Paint.Align.CENTER);
            sub.setTextAlign(Paint.Align.CENTER);
            float yName = medal.bottom + gap - fn.ascent;
            k.drawText(Widgets.fit(name, first, room), cx, yName, name);
            k.drawText(Widgets.fit(sub, second, room), cx, yName + fn.descent + gap - fs.ascent, sub);
        }

        Widgets.portrait(c, k, voice, medal, 0.3f, has && !playing);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(RING * s * grow);
        ring.setColor(playing || !has ? Palette.ACCENT : (bold ? quiet : RING_OUT));
        float rh = ring.getStrokeWidth() / 2f;
        k.drawOval(new RectF(medal.left + rh, medal.top + rh, medal.right - rh, medal.bottom - rh), ring);

        if (has && !playing) {
            // The ribbon hangs from the top edge over both frames, with a swallowtail end.
            float rw = RIBBON_W * s;
            float rl = RIBBON_H * s;
            float x = w - RIBBON_RIGHT * s - rw;
            Path ribbon = new Path();
            ribbon.moveTo(x, 0);
            ribbon.lineTo(x + rw, 0);
            ribbon.lineTo(x + rw, rl);
            ribbon.lineTo(x + rw / 2f, rl * 0.78f);
            ribbon.lineTo(x, rl);
            ribbon.close();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Palette.ACCENT);
            k.drawPath(ribbon, p);
        }
        return out;
    }
}
