package com.shumtugle.hora;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Small builders shared by the screens, so they all speak one visual language. */
final class Ui {
    static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;
    static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int PRESS = 0x33EDE6D6;
    /** One breath of the rings around a portrait. */
    private static final long BREATH_MS = 1800;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Palette.body(c));
        t.setIncludeFontPadding(false);
        return t;
    }

    /** A name or a title, in the display face. */
    static TextView title(Context c, CharSequence s, float sp) {
        TextView t = text(c, s, sp, Palette.INK);
        t.setTypeface(Palette.display(c));
        return t;
    }

    static GradientDrawable round(int fill, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (Palette.OUTLINED && fill != Palette.BG && fill != android.graphics.Color.TRANSPARENT) {
            // In the contrast look shapes are told apart by their edge, not their shade.
            d.setStroke(Math.max(1, dp(c, 1.5f)), Palette.LINE);
        }
        return d;
    }

    /** Takes the chosen look and colors the system bars; returns the look's generation for stale(). */
    static int prepare(android.app.Activity a) {
        int g = Palette.apply(a);
        a.getWindow().setStatusBarColor(Palette.BG);
        a.getWindow().setNavigationBarColor(Palette.BG);
        return g;
    }

    /** Rebuilds a screen whose look changed while it waited underneath another. */
    static boolean stale(android.app.Activity a, int built) {
        Palette.apply(a);
        if (built != Palette.generation()) {
            a.recreate();
            return true;
        }
        return false;
    }

    static GradientDrawable outlined(int fill, int stroke, float strokeDp, float radiusDp, Context c) {
        GradientDrawable d = round(fill, radiusDp, c);
        d.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        return d;
    }

    static GradientDrawable oval(int fill, int stroke, float strokeDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        if (strokeDp > 0) {
            d.setStroke(dp(c, strokeDp), stroke);
        }
        return d;
    }

    /** Background with a touch ripple on top. */
    static Drawable pressable(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(PRESS), content, content);
    }

    /** A rounded button made of text. */
    static TextView pill(Context c, CharSequence label, int fill, int stroke, int ink, boolean strong) {
        TextView b = text(c, label, 15, ink);
        if (strong) {
            b.setTypeface(Palette.bodyStrong(c));
        }
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 44));
        b.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        b.setBackground(pressable(stroke == 0
                ? round(fill, 22, c) : outlined(fill, stroke, 1, 22, c)));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /** A round icon button. */
    static ImageView iconButton(Context c, int icon, int fill, int sizeDp, String label) {
        ImageView b = new ImageView(c);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackground(pressable(oval(fill, 0, 0, c)));
        b.setContentDescription(label);
        b.setClickable(true);
        b.setFocusable(true);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return b;
    }

    /** A picture in a rounded frame, cropped to fill it. */
    static ImageView frame(Context c, final float radiusDp) {
        ImageView p = new ImageView(c);
        p.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final float r = dp(c, radiusDp);
        p.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        p.setClipToOutline(true);
        return p;
    }

    /** A portrait cut into a circle. */
    static ImageView portrait(Context c, int voice) {
        ImageView p = new ImageView(c);
        // The face is a circle of its own inside the padding, so a ring around it
        // (the view's background) never shows square corners of the photo.
        p.setScaleType(ImageView.ScaleType.FIT_XY);
        setFace(p, voice);
        p.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        p.setClipToOutline(true);
        return p;
    }

    private static final android.graphics.Bitmap[] FACES = new android.graphics.Bitmap[8];

    /** Puts a reader's face into a view made by portrait(). */
    static void setFace(ImageView v, int voice) {
        v.setImageDrawable(new RoundFace(face(v.getContext(), voice)));
    }

    private static synchronized android.graphics.Bitmap face(Context c, int voice) {
        int i = Math.max(0, Math.min(FACES.length - 1, voice));
        if (FACES[i] == null) {
            FACES[i] = android.graphics.BitmapFactory.decodeResource(c.getResources(), Cast.portrait(voice));
        }
        return FACES[i];
    }

    /** A photo cut to a smooth circle filling its bounds, cropped to the middle. */
    private static final class RoundFace extends Drawable {
        private final android.graphics.Bitmap bitmap;
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Matrix matrix = new android.graphics.Matrix();
        private final android.graphics.RectF oval = new android.graphics.RectF();

        RoundFace(android.graphics.Bitmap b) {
            bitmap = b;
            paint.setShader(new android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP));
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect r) {
            float scale = Math.max(r.width() / (float) bitmap.getWidth(), r.height() / (float) bitmap.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate(r.left + (r.width() - bitmap.getWidth() * scale) / 2f,
                    r.top + (r.height() - bitmap.getHeight() * scale) / 2f);
            paint.getShader().setLocalMatrix(matrix);
            oval.set(r);
        }

        @Override
        public void draw(android.graphics.Canvas k) {
            k.drawOval(oval, paint);
        }

        @Override
        public void setAlpha(int a) {
            paint.setAlpha(a);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter f) {
            paint.setColorFilter(f);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return bitmap.getWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return bitmap.getHeight();
        }
    }

    static SeekBar slider(Context c, int max, int progress) {
        SeekBar bar = new SeekBar(c);
        bar.setMax(max);
        bar.setProgress(Math.max(0, Math.min(max, progress)));
        bar.setProgressTintList(ColorStateList.valueOf(Palette.ACCENT));
        bar.setThumbTintList(ColorStateList.valueOf(Palette.ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Palette.LINE));
        bar.setMinimumHeight(dp(c, 44));
        return bar;
    }

    /** Slow breathing: scale and opacity swing between two values, forever. */
    static AnimatorSet breathe(View v, float scaleLow, float scaleHigh, float alphaLow, float alphaHigh) {
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                swing(v, "scaleX", scaleLow, scaleHigh),
                swing(v, "scaleY", scaleLow, scaleHigh),
                swing(v, "alpha", alphaLow, alphaHigh));
        return set;
    }

    private static ObjectAnimator swing(View v, String property, float from, float to) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, property, from, to);
        a.setDuration(BREATH_MS);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        return a;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        // Lining children up by their text baselines shifts rounded buttons and cuts their lower edge.
        l.setBaselineAligned(false);
        return l;
    }

    /**
     * A text field, or text the user may select, in the app's colors: the
     * caret, the selection and its handles in the accent instead of the
     * system's own tint.
     */
    static void field(TextView e) {
        e.setHighlightColor((Palette.ACCENT_TEXT & 0x00FFFFFF) | 0x55000000);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            GradientDrawable caret = new GradientDrawable();
            caret.setColor(Palette.ACCENT_TEXT);
            caret.setSize(dp(e.getContext(), 2), 1);
            e.setTextCursorDrawable(caret);
            android.graphics.drawable.Drawable[] handles = {
                e.getTextSelectHandle(), e.getTextSelectHandleLeft(), e.getTextSelectHandleRight(),
            };
            for (int i = 0; i < handles.length; i++) {
                if (handles[i] != null) {
                    handles[i] = handles[i].mutate();
                    handles[i].setTint(Palette.ACCENT_TEXT);
                }
            }
            if (handles[0] != null) {
                e.setTextSelectHandle(handles[0]);
            }
            if (handles[1] != null) {
                e.setTextSelectHandleLeft(handles[1]);
            }
            if (handles[2] != null) {
                e.setTextSelectHandleRight(handles[2]);
            }
        }
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, WRAP, w);
    }

    /**
     * A question in the app's own look: a card of the panel color, the title
     * in the display face, the answer buttons stacked, the one that goes on
     * above the one that turns back.
     */
    static android.app.Dialog ask(final android.app.Activity a, int title, int message, int yes, int no,
            final Runnable onYes) {
        final android.app.Dialog d = new android.app.Dialog(a);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout card = column(a);
        card.setBackground(round(Palette.SURFACE, 28, a));
        card.setPadding(dp(a, 24), dp(a, 24), dp(a, 24), dp(a, 16));
        card.addView(title(a, a.getString(title), 26));
        TextView body = text(a, a.getString(message), 15, Palette.MUTED);
        body.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams bp = lp(MATCH, WRAP);
        bp.topMargin = dp(a, 14);
        card.addView(body, bp);
        TextView go = pill(a, a.getString(yes), Palette.ACCENT, 0, Palette.ON_ACCENT, true);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                onYes.run();
            }
        });
        LinearLayout.LayoutParams gp = lp(MATCH, dp(a, 48));
        gp.topMargin = dp(a, 22);
        card.addView(go, gp);
        TextView back = pill(a, a.getString(no), Palette.SURFACE, 0, Palette.MUTED, false);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        LinearLayout.LayoutParams np = lp(MATCH, dp(a, 48));
        np.topMargin = dp(a, 6);
        card.addView(back, np);
        d.setContentView(card);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            int side = dp(a, 20);
            w.getDecorView().setPadding(side, 0, side, 0);
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.7f);
        }
        d.show();
        return d;
    }

    /**
     * One line of text that shrinks to fit rather than wrapping or being cut.
     * Needs a width decided by the layout (a weight or match), not by the text.
     */
    static void oneLine(TextView t, int minSp, int maxSp) {
        t.setMaxLines(1);
        t.setAutoSizeTextTypeUniformWithConfiguration(minSp, maxSp, 1, TypedValue.COMPLEX_UNIT_SP);
    }

    /**
     * The one header every screen shares: on the left the app's sign on a
     * main screen or a way back on a nested one, then the title, and on the
     * right of a main screen the settings, always in the same place.
     */
    static LinearLayout header(final android.app.Activity a, CharSequence title, boolean main) {
        LinearLayout head = row(a);
        if (main) {
            ImageView sign = new ImageView(a);
            sign.setImageResource(R.drawable.hora_sign);
            sign.setScaleType(ImageView.ScaleType.FIT_CENTER);
            sign.setBackground(round(0xFFA83428, 12, a));
            // The sign is the way back to the title page, as a site's logo leads to its first page.
            sign.setContentDescription(a.getString(R.string.intro_open));
            sign.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    IntroActivity.open(a);
                }
            });
            head.addView(sign, lp(dp(a, 44), dp(a, 44)));
        } else {
            ImageView back = iconButton(a, R.drawable.ic_back, Palette.SURFACE, 44, a.getString(R.string.ws_back));
            back.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.finish();
                }
            });
            head.addView(back);
        }
        TextView t = title(a, title == null ? "" : title, 28);
        oneLine(t, 18, 28);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(a, 12);
        tp.rightMargin = dp(a, 8);
        head.addView(t, tp);
        if (main) {
            ImageView gear = iconButton(a, R.drawable.ic_settings, Palette.SURFACE, 44,
                    a.getString(R.string.settings));
            gear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.startActivity(new android.content.Intent(a, SettingsActivity.class));
                }
            });
            head.addView(gear);
        }
        return head;
    }

    static final int NAV_VOICE = 0;
    static final int NAV_BOOK = 1;
    static final int NAV_ROLES = 2;
    static final int NAV_SHELF = 3;

    /** The bottom bar shared by the main screens; the current one is lit and does nothing. */
    static LinearLayout navBar(final android.app.Activity a, int current) {
        final Class<?>[] screens = {MainActivity.class, BookActivity.class, RolesActivity.class, ShelfActivity.class};
        int[] labels = {R.string.nav_voice, R.string.nav_book, R.string.nav_roles, R.string.nav_shelf};
        LinearLayout nav = row(a);
        nav.setPadding(0, dp(a, 14), 0, 0);
        for (int i = 0; i < screens.length; i++) {
            final int which = i;
            boolean on = i == current;
            TextView t = pill(a, a.getString(labels[i]), on ? Palette.RAISED : Palette.BG, 0,
                    on ? Palette.INK : Palette.MUTED, on);
            if (!on) {
                // A tab not chosen has no plate of its own, so a scene behind the screen shows through it.
                t.setBackground(new RippleDrawable(ColorStateList.valueOf(PRESS), null, round(0xFF000000, 22, a)));
            }
            t.setPadding(dp(a, 6), 0, dp(a, 6), 0);
            oneLine(t, 11, 14);
            if (!on) {
                t.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        android.content.Intent go = new android.content.Intent(a, screens[which]);
                        if (which == NAV_VOICE) {
                            // The main screen is already underneath: return to it rather than stack another.
                            go.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        }
                        a.startActivity(go);
                    }
                });
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(a, 44), 1f);
            p.leftMargin = dp(a, 2);
            p.rightMargin = dp(a, 2);
            nav.addView(t, p);
        }
        return nav;
    }

    /**
     * A choice in the app's own look: a question and two ways to answer it,
     * the likelier one in the accent, and a quiet way to put it off.
     */
    static android.app.Dialog choose(android.app.Activity a, String question, String first, String second,
            String later, final Runnable onFirst, final Runnable onSecond) {
        final android.app.Dialog d = new android.app.Dialog(a);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout card = column(a);
        card.setBackground(round(Palette.SURFACE, 28, a));
        card.setPadding(dp(a, 24), dp(a, 24), dp(a, 24), dp(a, 16));
        TextView q = title(a, question, 24);
        q.setLineSpacing(0, 1.1f);
        card.addView(q);
        TextView one = pill(a, first, Palette.ACCENT, 0, Palette.ON_ACCENT, true);
        one.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                onFirst.run();
            }
        });
        LinearLayout.LayoutParams p1 = lp(MATCH, dp(a, 48));
        p1.topMargin = dp(a, 20);
        card.addView(one, p1);
        TextView two = pill(a, second, Palette.SURFACE, Palette.LINE, Palette.INK, false);
        two.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                onSecond.run();
            }
        });
        LinearLayout.LayoutParams p2 = lp(MATCH, dp(a, 48));
        p2.topMargin = dp(a, 10);
        card.addView(two, p2);
        TextView no = pill(a, later, Palette.SURFACE, 0, Palette.MUTED, false);
        no.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        LinearLayout.LayoutParams p3 = lp(MATCH, dp(a, 48));
        p3.topMargin = dp(a, 6);
        card.addView(no, p3);
        d.setContentView(card);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            int side = dp(a, 20);
            w.getDecorView().setPadding(side, 0, side, 0);
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        d.show();
        return d;
    }

    /** Drops stress marks: they help the voice, not the reader. */
    static String plain(String spoken) {
        return spoken.replace("\u0301", "");
    }
}
