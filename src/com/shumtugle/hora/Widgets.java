package com.shumtugle.hora;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

/**
 * What the home-screen widgets share: their size in pixels, the one tap that
 * pauses and resumes the book, the narrator's scene, and refreshing them all.
 * Each widget is one picture drawn to its own measures, so it can be
 * stretched to any width without losing its look.
 */
final class Widgets {
    private static final int FALLBACK_W = 300;
    private static final int FALLBACK_H = 150;
    /** A widget picture travels between processes; held under this many pixels. */
    private static final int MAX_PIXELS = 600000;
    private static final String FILE = "widgets";
    private static final String SCENE_VOICE = "scene_voice";
    private static final String SCENE_BOOK = "scene_book";
    private static final String SCENE_NUMBER = "scene_number";

    private Widgets() {
    }

    /** Redraws every placed widget of every kind; cheap when none is placed. */
    static void refresh(Context c) {
        SceneWidget.refresh(c);
        BookmarkWidget.refresh(c);
        ExlibrisWidget.refresh(c);
    }

    /** The widget's size in dp: width and height as the launcher reports them. */
    static int[] sizeDp(AppWidgetManager m, int id) {
        Bundle o = m.getAppWidgetOptions(id);
        int w = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
        int h = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
        if (w <= 0 || h <= 0) {
            return new int[] {FALLBACK_W, FALLBACK_H};
        }
        return new int[] {w, h};
    }

    /** Pixels per dp for a picture of that size. */
    static float scale(Context c, int wDp, int hDp) {
        float s = Math.min(c.getResources().getDisplayMetrics().density, 3f);
        float area = wDp * hDp * s * s;
        if (area > MAX_PIXELS) {
            s *= (float) Math.sqrt(MAX_PIXELS / area);
        }
        return s;
    }

    /** With a book open, the tap pauses or resumes it; without one it opens the book screen. */
    static PendingIntent tap(Context c, int request) {
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        if (!Reading.uri(c).isEmpty()) {
            return PendingIntent.getForegroundService(c, request,
                    BookPlayer.intent(c, BookPlayer.ACTION_TOGGLE), flags);
        }
        Intent i = new Intent(c, BookActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, request, i, flags);
    }

    /** What a screen reader says about any of the widgets. */
    static String describe(Context c) {
        String title = Reading.title(c);
        if (title.isEmpty()) {
            return c.getString(R.string.widget_say_no_book);
        }
        return Reading.playing(c)
                ? c.getString(R.string.widget_say_playing, title,
                        Cast.name(c, Prefs.roleShared(c, Cast.NARRATOR)))
                : c.getString(R.string.widget_say_paused, title);
    }

    /** "214 of 1630", or empty while the book has no paragraphs counted. */
    static String where(Context c) {
        int total = Reading.total(c);
        return total <= 0 ? "" : c.getString(R.string.widget_where, Reading.index(c) + 1, total);
    }

    /**
     * Which of the narrator's scenes to show. Kept while the same voice reads
     * the same book, so the widget does not flicker with every paragraph;
     * a new reader or a new book draws another.
     */
    static int sceneNumber(Context c, int voice) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS);
        String book = Reading.uri(c);
        int n = p.getInt(SCENE_NUMBER, 0);
        if (n > 0 && p.getInt(SCENE_VOICE, 0) == voice && book.equals(p.getString(SCENE_BOOK, ""))) {
            return n;
        }
        int count = sceneCount(c, voice);
        n = count <= 0 ? 0 : 1 + new Random().nextInt(count);
        p.edit().putInt(SCENE_VOICE, voice).putString(SCENE_BOOK, book).putInt(SCENE_NUMBER, n).commit();
        return n;
    }

    private static int sceneCount(Context c, int voice) {
        try {
            String[] all = c.getAssets().list("cards/" + voice);
            return all == null ? 0 : all.length;
        } catch (IOException e) {
            return 0;
        }
    }

    /** A scene decoded no larger than needed for the given width; null when missing. */
    static Bitmap scene(Context c, int voice, int number, int widthPx) {
        if (number <= 0) {
            return null;
        }
        String path = "cards/" + voice + "/" + number + ".jpg";
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = c.getAssets().open(path);
            try {
                BitmapFactory.decodeStream(in, null, bounds);
            } finally {
                in.close();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 1;
            while (bounds.outWidth / (o.inSampleSize * 2) >= widthPx) {
                o.inSampleSize *= 2;
            }
            in = c.getAssets().open(path);
            try {
                return BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Fills the area with the picture, cropped to its shape; focus is where
     * the crop sits from top (0) to bottom (1). Drained of color when grey.
     */
    static void cover(Canvas k, Bitmap src, RectF area, float focus, boolean grey) {
        cover(k, src, area, focus, grey ? filter(0.62f, 0.62f, 0.62f) : null);
    }

    static void cover(Canvas k, Bitmap src, RectF area, float focus, ColorFilter filter) {
        float want = area.width() / area.height();
        int sw = src.getWidth();
        int sh = src.getHeight();
        int cw = sw;
        int ch = Math.round(sw / want);
        if (ch > sh) {
            ch = sh;
            cw = Math.round(sh * want);
        }
        int left = (sw - cw) / 2;
        int top = Math.round((sh - ch) * focus);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        p.setColorFilter(filter);
        k.drawBitmap(src, new Rect(left, top, left + cw, top + ch), area, p);
    }

    /** No color left, then each channel scaled: grey when equal, old paper when warm. */
    static ColorFilter filter(float r, float g, float b) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0f);
        ColorMatrix scale = new ColorMatrix();
        scale.setScale(r, g, b, 1f);
        m.postConcat(scale);
        return new ColorMatrixColorFilter(m);
    }

    /** Clips the canvas to a rounded rectangle. */
    static void roundClip(Canvas k, int w, int h, float radius) {
        Path p = new Path();
        p.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
        k.clipPath(p);
    }

    /** Pause bars or a play triangle, centered on (cx, cy), size across. */
    static void glyph(Canvas k, boolean playing, float cx, float cy, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        if (playing) {
            float bw = size * 0.17f;
            float bh = size * 0.58f;
            float gap = size * 0.16f;
            float r = bw * 0.25f;
            k.drawRoundRect(new RectF(cx - gap / 2 - bw, cy - bh / 2, cx - gap / 2, cy + bh / 2), r, r, p);
            k.drawRoundRect(new RectF(cx + gap / 2, cy - bh / 2, cx + gap / 2 + bw, cy + bh / 2), r, r, p);
        } else {
            float h = size * 0.56f;
            float x0 = cx - h * 0.36f;
            Path t = new Path();
            t.moveTo(x0, cy - h / 2);
            t.lineTo(x0 + h * 0.86f, cy);
            t.lineTo(x0, cy + h / 2);
            t.close();
            k.drawPath(t, p);
        }
    }

    static TextPaint paint(android.graphics.Typeface face, float size, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    /** Cut to the width with an ellipsis. */
    static String fit(TextPaint p, String s, float width) {
        return TextUtils.ellipsize(s, p, Math.max(0f, width), TextUtils.TruncateAt.END).toString();
    }

    /** A color between a and b, t of the way to b. */
    static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    /**
     * A voice's portrait in an oval (a circle when the box is square), cut
     * around the face. Faded to old paper when faded is set.
     */
    static void portrait(Context c, Canvas k, int voice, RectF box, float focus, boolean faded) {
        Bitmap face = BitmapFactory.decodeResource(c.getResources(), Cast.portrait(voice));
        if (face == null) {
            return;
        }
        // Drawn as a filled oval rather than through a clip: a clip on a widget's
        // canvas has no smoothing, so its edge came out stepped.
        float want = box.width() / box.height();
        int sw = face.getWidth();
        int sh = face.getHeight();
        float cw = sw;
        float ch = sw / want;
        if (ch > sh) {
            ch = sh;
            cw = sh * want;
        }
        float left = (sw - cw) / 2f;
        float top = (sh - ch) * focus;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setRectToRect(new RectF(left, top, left + cw, top + ch), box, android.graphics.Matrix.ScaleToFit.FILL);
        android.graphics.BitmapShader shader = new android.graphics.BitmapShader(face,
                android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
        shader.setLocalMatrix(m);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        p.setShader(shader);
        p.setColorFilter(faded ? filter(0.95f, 0.86f, 0.72f) : null);
        k.drawOval(box, p);
    }

    /** Reading progress from 0 to 1. */
    static float progress(Context c) {
        int total = Reading.total(c);
        if (total > 0) {
            return Math.min(1f, (Reading.index(c) + 1) / (float) total);
        }
        return Reading.permille(c) / 1000f;
    }
}
