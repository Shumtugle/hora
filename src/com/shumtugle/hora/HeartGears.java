package com.shumtugle.hora;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A heart drawn open, with two gears meshing inside it, turning while work
 * goes on. The heart is an outline with a gap at the top, as if the lid
 * were lifted to show the works.
 */
final class HeartGears extends View {
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path heart = new Path();
    private final Path big;
    private final Path small;
    private float turn;
    private ValueAnimator spin;

    HeartGears(Context c) {
        super(c);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeCap(Paint.Cap.ROUND);
        outline.setStrokeJoin(Paint.Join.ROUND);
        outline.setColor(Palette.ACCENT);
        gear.setColor(Palette.INK);
        gear.setAlpha(210);
        hole.setColor(Palette.BG);
        // Gears at unit size, scaled when drawn: teeth that mesh need the same pitch, so the
        // tooth counts follow the radii.
        big = gear(12, 1f, 0.80f);
        small = gear(8, 1f, 0.76f);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private static Path gear(int teeth, float outer, float inner) {
        Path p = new Path();
        double step = Math.PI * 2 / teeth;
        for (int i = 0; i < teeth; i++) {
            double a = i * step;
            // Each tooth: rise, top, fall, root; a slightly narrower top reads as a gear, not a star.
            point(p, i == 0, inner, a);
            point(p, false, outer, a + step * 0.12);
            point(p, false, outer, a + step * 0.42);
            point(p, false, inner, a + step * 0.54);
        }
        p.close();
        return p;
    }

    private static void point(Path p, boolean first, float r, double a) {
        float x = (float) (Math.cos(a) * r);
        float y = (float) (Math.sin(a) * r);
        if (first) {
            p.moveTo(x, y);
        } else {
            p.lineTo(x, y);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        spin = ValueAnimator.ofFloat(0f, 360f);
        spin.setDuration(9000);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                turn = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        spin.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (spin != null) {
            spin.cancel();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float s = Math.min(w, h);
        float ox = (w - s) / 2f;
        float oy = (h - s) / 2f;
        outline.setStrokeWidth(s * 0.035f);
        heart.reset();
        // Two lobes meeting in a point below; the top dip is left open.
        heart.moveTo(ox + s * 0.44f, oy + s * 0.24f);
        heart.cubicTo(ox + s * 0.36f, oy + s * 0.10f, ox + s * 0.10f, oy + s * 0.10f,
                ox + s * 0.08f, oy + s * 0.34f);
        heart.cubicTo(ox + s * 0.06f, oy + s * 0.58f, ox + s * 0.36f, oy + s * 0.76f,
                ox + s * 0.50f, oy + s * 0.92f);
        heart.cubicTo(ox + s * 0.64f, oy + s * 0.76f, ox + s * 0.94f, oy + s * 0.58f,
                ox + s * 0.92f, oy + s * 0.34f);
        heart.cubicTo(ox + s * 0.90f, oy + s * 0.10f, ox + s * 0.64f, oy + s * 0.10f,
                ox + s * 0.56f, oy + s * 0.24f);
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        float ox = (getWidth() - s) / 2f;
        float oy = (getHeight() - s) / 2f;
        float rb = s * 0.17f;
        float rs = rb * 8f / 12f;
        // The big gear sits low in the heart, the small one up and right, teeth meshing.
        float bx = ox + s * 0.42f;
        float by = oy + s * 0.50f;
        float d = (rb + rs) * 0.90f;
        float sx = bx + d * 0.80f;
        float sy = by - d * 0.60f;
        drawGear(c, big, bx, by, rb, turn);
        // Opposite way, faster by the ratio of teeth, half a tooth offset so they mesh.
        drawGear(c, small, sx, sy, rs, -turn * 12f / 8f + 22.5f);
        c.drawPath(heart, outline);
    }

    private void drawGear(Canvas c, Path g, float x, float y, float r, float deg) {
        c.save();
        c.translate(x, y);
        c.rotate(deg);
        c.scale(r, r);
        c.drawPath(g, gear);
        c.restore();
        c.drawCircle(x, y, r * 0.28f, hole);
    }
}
