package com.shumtugle.hora;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * A ruler along the right edge of the book's ribbon.
 *
 * While the ribbon moves, a thin thread shows there: a mark for where the eye
 * is in the book, the reader's small face where the voice is, and a notch for
 * every chapter. It fades soon after the ribbon stops.
 *
 * Held by its edge, the thread becomes a scale laid out from the finger: near
 * it one paragraph is one finger's step, and toward the two ends the
 * paragraphs crowd on a logarithm, so a small movement goes one paragraph and
 * a long one goes through the book. The scale is fitted so the first and the
 * last paragraph always land on the thread's ends; a book short enough is
 * simply spread evenly. Chapters are knots, the one in hand and its neighbours
 * named. The ribbon goes along and steps back; a tag by the finger says which
 * paragraph of its chapter it is. Let go, the ribbon stays there; the voice
 * reads on where it was.
 *
 * The spacing is a slide rule's: fine at the hold, crowding toward the ends.
 */
final class BookRuler extends View {

    /** What the ruler is laid along, and what it tells. */
    interface Pages {
        int count();

        /** Chapter starts, as paragraph numbers in order; empty when the book has none. */
        int[] chapters();

        String chapterName(int n);

        /** The paragraph being heard, or -1. */
        int voiceAt();

        int voice();

        /** The paragraph beside a height of the ribbon. */
        int beside(float y);

        /** Where the eye is in the book, 0 to 1. */
        float along();

        /** The ribbon moves so a paragraph stands at a height. */
        void bring(int i, float y);

        /** The paragraph in hand changed while held. */
        void holding(int i);

        /** Let go on a paragraph. */
        void letGo(int i);

        /** Let go without having moved, or taken away by the screen: nothing is pinned. */
        void released();

        /** Any movement the ribbon is still making stops under the finger. */
        void still();

        /** A touch at the edge that was no hold: it goes to the paragraph there, as a tap. */
        void tap(float y);
    }

    /** One half of the scale: paragraphs away from the hold over a distance of the screen. */
    static final class Half {
        boolean even;
        float step;
        float span;
        float fine;

        void fit(int rows, float distance, float near) {
            if (rows <= 0 || distance <= 0f) {
                even = true;
                step = near;
                return;
            }
            if (near * rows <= distance) {
                even = true;
                step = distance / rows;
                return;
            }
            even = false;
            // fine * ln(1 + rows / fine) grows with fine toward rows: the fine that fills the distance is found by halving.
            double want = distance / near;
            double lo = 1e-4;
            double hi = 1e7;
            for (int k = 0; k < 60; k++) {
                double mid = (lo + hi) / 2.0;
                if (mid * Math.log(1.0 + rows / mid) < want) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            fine = (float) ((lo + hi) / 2.0);
            span = near * fine;
        }

        float at(float rows) {
            return even ? rows * step : span * (float) Math.log(1.0 + rows / fine);
        }

        float rows(float distance) {
            return even ? distance / step : fine * (float) (Math.exp(distance / span) - 1.0);
        }
    }

    private static final float NEAR = 26f;
    private static final float ZONE = 44f;
    private static final float THREAD = 22f;
    private static final float ENDS = 16f;
    private static final float SHADE = 170f;
    private static final long FADE_AFTER_MS = 1300;

    private final Pages book;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tag = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Half up = new Half();
    private final Half down = new Half();
    private final Matrix faceMatrix = new Matrix();
    private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap face;
    private int faceOf = -1;

    private float shown;
    private float showTo;
    private long lastMoved;
    private boolean held;
    private int grab;
    private float grabY;
    private float fingerY;
    private int pos = -1;
    private float downY;
    /** Whether the finger has moved past a tap since it came down: only then the ruler is in use. */
    private boolean dragging;
    private int[] chapterCache = new int[0];
    private LinearGradient shade;
    private int shadeFor;

    BookRuler(Context c, Pages book) {
        super(c);
        this.book = book;
        label.setTypeface(Palette.display(c));
        tag.setTypeface(Palette.bodyStrong(c));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private float px(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private float top() {
        return px(ENDS);
    }

    private float bottom() {
        return getHeight() - px(ENDS);
    }

    private float threadX() {
        return getWidth() - px(THREAD);
    }

    /** The ribbon moved: the thread shows, and fades a little after it stops. */
    void moved() {
        if (book.count() < 2) {
            return;
        }
        lastMoved = SystemClock.uptimeMillis();
        showTo = 1f;
        removeCallbacks(fade);
        postDelayed(fade, FADE_AFTER_MS);
        postInvalidateOnAnimation();
    }

    private final Runnable fade = new Runnable() {
        @Override
        public void run() {
            if (!held && SystemClock.uptimeMillis() - lastMoved >= FADE_AFTER_MS - 50) {
                showTo = 0f;
                postInvalidateOnAnimation();
            }
        }
    };

    /** Where a paragraph stands on the even thread, when not held. */
    private float evenY(float i) {
        int last = Math.max(1, book.count() - 1);
        return top() + (bottom() - top()) * i / last;
    }

    /** Where a paragraph stands on the scale laid out from the hold. */
    private float yOf(float i) {
        return i < grab ? grabY - up.at(grab - i) : grabY + down.at(i - grab);
    }

    private float rowAt(float y) {
        float r = y < grabY ? grab - up.rows(grabY - y) : grab + down.rows(y - grabY);
        return Math.max(0f, Math.min(book.count() - 1, r));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (e.getX() < getWidth() - px(ZONE) || shown < 0.3f || book.count() < 2) {
                    return false;
                }
                held = true;
                dragging = false;
                downY = e.getY();
                book.still();
                removeCallbacks(fade);
                showTo = 1f;
                grabY = Math.max(top(), Math.min(bottom(), e.getY()));
                grab = Math.max(0, Math.min(book.count() - 1, book.beside(grabY)));
                up.fit(grab, grabY - top(), px(NEAR));
                down.fit(book.count() - 1 - grab, bottom() - grabY, px(NEAR));
                fingerY = grabY;
                pos = grab;
                chapterCache = book.chapters();
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!held) {
                    return false;
                }
                if (!dragging) {
                    if (Math.abs(e.getY() - downY) < android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                        return true;
                    }
                    dragging = true;
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    book.holding(pos);
                }
                fingerY = Math.max(top(), Math.min(bottom(), e.getY()));
                int now = Math.round(rowAt(fingerY));
                if (now != pos) {
                    boolean knot = false;
                    for (int c : chapterCache) {
                        if ((c > Math.min(pos, now)) && (c <= Math.max(pos, now))) {
                            knot = true;
                            break;
                        }
                    }
                    performHapticFeedback(knot ? HapticFeedbackConstants.KEYBOARD_TAP
                            : HapticFeedbackConstants.CLOCK_TICK);
                    pos = now;
                    book.bring(pos, fingerY);
                    book.holding(pos);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!held) {
                    return false;
                }
                held = false;
                boolean cancelled = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                if (!dragging) {
                    // A tap, not a hold: the paragraph under it answers as it would without the ruler.
                    book.released();
                    if (!cancelled) {
                        book.tap(e.getY());
                    }
                } else if (cancelled) {
                    book.released();
                } else {
                    book.bring(pos, fingerY);
                    book.letGo(pos);
                }
                dragging = false;
                moved();
                invalidate();
                return true;
            }
            default:
                return held;
        }
    }

    @Override
    protected void onDraw(Canvas k) {
        // A short ease toward showing or hiding.
        if (shown != showTo) {
            shown += (showTo - shown) * 0.22f;
            if (Math.abs(showTo - shown) < 0.02f) {
                shown = showTo;
            }
            postInvalidateOnAnimation();
        }
        if (shown <= 0f || book.count() < 2) {
            return;
        }
        int a = Math.round(255 * shown);
        float x = threadX();
        boolean scale = held && dragging;
        if (scale) {
            // The ribbon steps back; a shade rises from the edge for the scale to stand on.
            paint.setShader(null);
            paint.setColor((Palette.BG & 0x00FFFFFF) | 0x73000000);
            k.drawRect(0, 0, getWidth(), getHeight(), paint);
            if (shade == null || shadeFor != getWidth()) {
                shade = new LinearGradient(getWidth() - px(SHADE), 0, getWidth(), 0,
                        Palette.BG & 0x00FFFFFF, (Palette.BG & 0x00FFFFFF) | 0xCC000000, Shader.TileMode.CLAMP);
                shadeFor = getWidth();
            }
            paint.setShader(shade);
            k.drawRect(getWidth() - px(SHADE), 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
        }
        paint.setStrokeWidth(px(1));
        paint.setColor(withAlpha(scale ? Palette.MUTED : Palette.LINE, a));
        k.drawLine(x, top(), x, bottom(), paint);
        int[] ch = held ? chapterCache : book.chapters();
        if (!scale) {
            paint.setColor(withAlpha(Palette.HINT, a));
            for (int c : ch) {
                float y = evenY(c);
                k.drawLine(x - px(3), y, x + px(3), y, paint);
            }
            drawFace(k, x, evenY(Math.max(0, book.voiceAt())), px(11), a);
            paint.setColor(withAlpha(Palette.ACCENT, a));
            k.drawCircle(x, top() + (bottom() - top()) * Math.max(0f, Math.min(1f, book.along())), px(5), paint);
            return;
        }
        // Paragraphs: short notches, longer and brighter near the finger; where they crowd
        // closer than two pixels, only one is drawn.
        int count = book.count();
        float lastY = Float.NaN;
        float gap = 2f;
        for (int i = 0; i < count; i++) {
            float y = yOf(i);
            boolean near = Math.abs(i - pos) <= 6;
            if (!near && !Float.isNaN(lastY) && Math.abs(y - lastY) < gap) {
                continue;
            }
            lastY = y;
            paint.setStrokeWidth(px(near ? 1.5f : 1f));
            paint.setColor(withAlpha(near ? Palette.INK : Palette.MUTED, near ? a : a / 2));
            k.drawLine(x - px(near ? 6 : 3), y, x, y, paint);
        }
        // Chapters: knots, and the names of the one in hand and its neighbours.
        int in = -1;
        for (int n = 0; n < ch.length; n++) {
            if (ch[n] <= pos) {
                in = n;
            }
        }
        paint.setStrokeWidth(px(2));
        for (int n = 0; n < ch.length; n++) {
            float y = yOf(ch[n]);
            paint.setColor(withAlpha(Palette.ACCENT_TEXT, a));
            k.drawLine(x - px(12), y, x + px(6), y, paint);
            k.drawCircle(x, y, px(3), paint);
            if (Math.abs(n - in) <= 1) {
                label.setTextSize(px(n == in ? 18 : 15));
                label.setColor(withAlpha(n == in ? Palette.INK : Palette.MUTED, a));
                String s = book.chapterName(n);
                k.drawText(s, x - px(18) - label.measureText(s), y + px(5), label);
            }
        }
        drawFace(k, x, yOf(Math.max(0, book.voiceAt())), px(12), a);
        // The finger's mark, and the paragraph's place in its chapter beside it.
        paint.setColor(Palette.ACCENT);
        k.drawCircle(x, fingerY, px(11), paint);
        paint.setColor(Palette.ON_ACCENT);
        k.drawCircle(x, fingerY, px(4), paint);
        String t = tagFor(pos);
        tag.setTextSize(px(13));
        float tw = tag.measureText(t);
        box.set(x - px(24) - tw - px(20), fingerY - px(15), x - px(18), fingerY + px(15));
        paint.setColor(Palette.ACCENT);
        k.drawRoundRect(box, px(15), px(15), paint);
        tag.setColor(Palette.ON_ACCENT);
        k.drawText(t, box.left + px(10), fingerY + px(5), tag);
    }

    /** "4 of 10" within the chapter, or of the whole when there are no chapters. */
    private String tagFor(int i) {
        int[] ch = chapterCache;
        int from = -1;
        int to = book.count();
        for (int n = 0; n < ch.length; n++) {
            if (ch[n] <= i) {
                from = ch[n];
                to = n + 1 < ch.length ? ch[n + 1] : book.count();
            }
        }
        if (from < 0) {
            return getContext().getString(R.string.ruler_tag, i + 1, book.count());
        }
        return getContext().getString(R.string.ruler_tag, Math.max(1, i - from), Math.max(1, to - from - 1));
    }

    private void drawFace(Canvas k, float x, float y, float r, int alpha) {
        int v = book.voice();
        if (book.voiceAt() < 0 || v <= 0) {
            return;
        }
        if (face == null || faceOf != v) {
            face = Ui.face(getContext(), v);
            faceOf = v;
        }
        paint.setColor(withAlpha(Palette.voice(v), alpha));
        k.drawCircle(x, y, r + px(2), paint);
        if (face != null) {
            BitmapShader s = new BitmapShader(face, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = 2 * r / face.getWidth();
            faceMatrix.setScale(scale, scale);
            faceMatrix.postTranslate(x - r, y - r);
            s.setLocalMatrix(faceMatrix);
            facePaint.setShader(s);
            facePaint.setAlpha(alpha);
            k.drawCircle(x, y, r, facePaint);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }
}
