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
 * The narrator at work: a scene of the reading voice above, the book and the
 * place in it below, with a round sign of what a tap will do. Paused, the
 * scene goes grey. Its usual size is two cells by two: there the round sign
 * sits on the scene, so the words below get the whole width. Wide, the sign
 * moves down beside the words; too low for a scene above, the scene moves
 * to the left; a single cell keeps only the scene, a thread and the sign.
 */
public final class SceneWidget extends AppWidgetProvider {
    private static final int REQUEST = 11;
    private static final float RADIUS = 22f;
    private static final float BAND = 68f;
    private static final float MIN_SCENE = 44f;
    private static final float BUTTON = 44f;
    private static final float RULE = 3f;
    /** Below this width (dp) the round sign sits on the scene instead of beside the words. */
    private static final float NARROW = 250f;
    /** Below this size (dp) on either side the widget is a single cell: scene and sign only. */
    private static final float TINY = 100f;

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
        for (int id : m.getAppWidgetIds(new ComponentName(c, SceneWidget.class))) {
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

    /** The widget, w by h pixels, s pixels to a dp. */
    static Bitmap picture(Context c, int w, int h, float s) {
        Palette.apply(c);
        boolean bold = Palette.contrast();
        float grow = bold ? 1.2f : 1f;
        String title = Reading.title(c);
        boolean has = !title.isEmpty();
        boolean playing = has && Reading.playing(c);
        int voice = Prefs.roleShared(c, Cast.NARRATOR);

        Bitmap out = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas k = new Canvas(out);
        Widgets.roundClip(k, w, h, RADIUS * s);
        k.drawColor(Palette.BG);

        if (Math.min(w, h) < TINY * s) {
            single(c, k, w, h, s, grow, voice, has, playing);
            return out;
        }

        float band = Math.min(h, BAND * s * grow);
        boolean side = h - band < MIN_SCENE * s;
        boolean narrow = !side && w < NARROW * s;
        RectF sceneArea;
        RectF words;
        if (side) {
            float wide = Math.min(w * 0.42f, h * 1.5f);
            sceneArea = new RectF(0, 0, wide, h);
            words = new RectF(wide, 0, w, h);
        } else {
            sceneArea = new RectF(0, 0, w, h - band);
            words = new RectF(0, h - band, w, h);
        }
        Bitmap scene = Widgets.scene(c, voice, Widgets.sceneNumber(c, voice), Math.round(sceneArea.width()));
        if (scene != null) {
            Widgets.cover(k, scene, sceneArea, 0.2f, has && !playing);
        }

        Paint rule = new Paint();
        rule.setColor(playing || !has ? Palette.ACCENT : Widgets.mix(Palette.BG, Palette.ACCENT, 0.3f));
        float r = RULE * s * grow;
        if (side) {
            k.drawRect(words.left, 0, words.left + r, h, rule);
        } else {
            k.drawRect(0, words.top, w, words.top + r, rule);
        }

        float top = side ? words.top : words.top + r;
        float cy = (top + words.bottom) / 2f;
        float right = words.right - 16 * s;
        if (has && narrow) {
            // Two by two: the sign rests on the scene's lower right corner, above the thread.
            float d = 40 * s * grow;
            float cx = sceneArea.right - 10 * s - d / 2f;
            float cyb = sceneArea.bottom - 10 * s - d / 2f;
            sign(k, cx, cyb, d, playing, s);
            right = words.right - 14 * s;
        } else if (has) {
            float d = BUTTON * s * grow;
            float cx = right - d / 2f;
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(Palette.ACCENT);
            k.drawCircle(cx, cy, d / 2f, fill);
            Widgets.glyph(k, playing, cx, cy, d, Palette.ON_ACCENT);
            right = cx - d / 2f - 12 * s;
        }
        float left = words.left + (side ? 16 : narrow ? 14 : 18) * s;

        TextPaint name = Widgets.paint(Palette.display(c), (narrow ? 22 : 24) * s * grow, Palette.INK);
        name.setTextSkewX(bold ? 0f : -0.16f);
        TextPaint sub = Widgets.paint(bold ? Palette.bodyStrong(c) : Palette.body(c), 12 * s * grow,
                Palette.ACCENT_TEXT);
        String first = has ? title : c.getString(R.string.widget_no_book);
        String second;
        if (!has) {
            second = c.getString(R.string.widget_pick_book);
        } else {
            String where = Widgets.where(c);
            String who = playing ? Cast.name(c, voice) : c.getString(R.string.widget_paused);
            second = where.isEmpty() ? who : who + " \u00b7 " + where;
        }
        Paint.FontMetrics fn = name.getFontMetrics();
        Paint.FontMetrics fs = sub.getFontMetrics();
        float gap = 2 * s;
        float hName = fn.descent - fn.ascent;
        float hSub = fs.descent - fs.ascent;
        float y0 = cy - (hName + gap + hSub) / 2f;
        k.drawText(Widgets.fit(name, first, right - left), left, y0 - fn.ascent, name);
        k.drawText(Widgets.fit(sub, second, right - left), left, y0 + hName + gap - fs.ascent, sub);
        return out;
    }

    /** The round sign of what a tap will do, with a soft shadow so it reads over a picture. */
    private static void sign(Canvas k, float cx, float cy, float d, boolean playing, float s) {
        Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        shade.setColor(0x59000000);
        k.drawCircle(cx, cy + 2 * s, d / 2f + 1 * s, shade);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Palette.ACCENT);
        k.drawCircle(cx, cy, d / 2f, fill);
        Widgets.glyph(k, playing, cx, cy, d, Palette.ON_ACCENT);
    }

    /** A single cell: the scene cut to the face, a thread along the bottom, the sign in the corner. */
    private static void single(Context c, Canvas k, int w, int h, float s, float grow, int voice, boolean has,
            boolean playing) {
        RectF all = new RectF(0, 0, w, h);
        Bitmap scene = Widgets.scene(c, voice, Widgets.sceneNumber(c, voice), w);
        if (scene != null) {
            Widgets.cover(k, scene, all, 0.22f, has && !playing);
        }
        Paint rule = new Paint();
        rule.setColor(playing || !has ? Palette.ACCENT : Widgets.mix(Palette.BG, Palette.ACCENT, 0.3f));
        float r = RULE * s * grow;
        k.drawRect(0, h - r, w, h, rule);
        if (has) {
            float d = Math.min(30 * s * grow, Math.min(w, h) * 0.42f);
            sign(k, w - 6 * s - d / 2f, h - r - 6 * s - d / 2f, d, playing, s);
        }
    }
}
