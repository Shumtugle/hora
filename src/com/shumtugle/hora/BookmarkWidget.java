package com.shumtugle.hora;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.TextPaint;
import android.widget.RemoteViews;

/**
 * A strip of paper: the narrator's face in a ring, the book, who reads and
 * how far, a sign of what a tap will do, and a thread of progress along the
 * lower edge. Paused, the face fades to old paper and the ring goes out.
 * Taller strips grow the face and the title, never the empty space.
 */
public final class BookmarkWidget extends AppWidgetProvider {
    private static final int REQUEST = 12;
    private static final float RADIUS = 18f;
    private static final float PAD = 16f;
    private static final float FACE_MIN = 36f;
    private static final float FACE_MAX = 96f;
    private static final float RING = 2.5f;
    private static final float THREAD = 3f;
    private static final float GLYPH = 28f;
    /** Ink and the quieter ink on paper; the paper of the paused ring and of the thread's track. */
    private static final int ON_PAPER = 0xFF2A1215;
    private static final int ON_PAPER_QUIET = 0xFF6E4C46;
    private static final int RING_OUT = 0xFFC9B9A0;
    private static final int TRACK = 0xFFE2D2B6;

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
        for (int id : m.getAppWidgetIds(new ComponentName(c, BookmarkWidget.class))) {
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

    /** The strip, w by h pixels, s pixels to a dp. */
    static Bitmap picture(Context c, int w, int h, float s) {
        Palette.apply(c);
        boolean bold = Palette.contrast();
        float grow = bold ? 1.2f : 1f;
        // Paper in the bordeaux look; in the contrast look the strip is black like everything else.
        int ground = bold ? Palette.BG : Palette.INK;
        int ink = bold ? Palette.INK : ON_PAPER;
        int quiet = bold ? Palette.INK : ON_PAPER_QUIET;
        int track = bold ? Widgets.mix(Palette.BG, Palette.INK, 0.3f) : TRACK;
        String title = Reading.title(c);
        boolean has = !title.isEmpty();
        boolean playing = has && Reading.playing(c);
        int voice = Prefs.roleShared(c, Cast.NARRATOR);

        Bitmap out = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas k = new Canvas(out);
        Widgets.roundClip(k, w, h, Math.min(RADIUS * s, h / 2f));
        k.drawColor(ground);

        float thread = has ? THREAD * s * grow : 0f;
        float inner = h - thread;
        float face = Math.max(FACE_MIN * s, Math.min(FACE_MAX * s, inner - 2 * 14 * s));
        float cy = inner / 2f;
        float left = PAD * s;
        RectF box = new RectF(left, cy - face / 2f, left + face, cy + face / 2f);
        Widgets.portrait(c, k, voice, box, 0.3f, has && !playing);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(RING * s * grow);
        ring.setColor(playing || !has ? Palette.ACCENT : (bold ? quiet : RING_OUT));
        float half = ring.getStrokeWidth() / 2f;
        k.drawOval(new RectF(box.left + half, box.top + half, box.right - half, box.bottom - half), ring);

        float right = w - 18 * s;
        if (has) {
            float g = GLYPH * s * grow;
            float gx = right - g / 2f;
            Widgets.glyph(k, playing, gx, cy, g, Palette.ACCENT);
            right = gx - g / 2f - 12 * s;
        }
        float textLeft = box.right + 14 * s;

        float titleSize = Math.max(22f * s, Math.min(34f * s, face * 0.42f)) * grow;
        TextPaint name = Widgets.paint(Palette.display(c), titleSize, ink);
        name.setTextSkewX(bold ? 0f : -0.16f);
        TextPaint sub = Widgets.paint(bold ? Palette.bodyStrong(c) : Palette.body(c),
                Math.max(12f * s, titleSize * 0.48f), quiet);
        String first = has ? title : c.getString(R.string.widget_no_book);
        String second;
        if (!has) {
            second = c.getString(R.string.widget_pick_book);
        } else {
            String who = c.getString(playing ? R.string.widget_reads : R.string.widget_waits,
                    Cast.name(c, voice));
            String where = Widgets.where(c);
            second = where.isEmpty() ? who : who + " \u00b7 " + where;
        }
        Paint.FontMetrics fn = name.getFontMetrics();
        Paint.FontMetrics fs = sub.getFontMetrics();
        float gap = 1 * s;
        float hName = fn.descent - fn.ascent;
        float hSub = fs.descent - fs.ascent;
        float y0 = cy - (hName + gap + hSub) / 2f;
        k.drawText(Widgets.fit(name, first, right - textLeft), textLeft, y0 - fn.ascent, name);
        k.drawText(Widgets.fit(sub, second, right - textLeft), textLeft, y0 + hName + gap - fs.ascent, sub);

        if (has) {
            Paint p = new Paint();
            p.setColor(track);
            k.drawRect(0, h - thread, w, h, p);
            p.setColor(Palette.ACCENT);
            k.drawRect(0, h - thread, w * Widgets.progress(c), h, p);
        }
        return out;
    }
}
