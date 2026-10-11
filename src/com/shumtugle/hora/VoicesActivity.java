package com.shumtugle.hora;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;

/**
 * Getting to know the voices: one card per voice, a page at a time, swiped up
 * and down. The carousel stands still until it is swiped. Inside each card the
 * voice's scenes live as a small album: a slow drift over one scene, then a
 * fade into the next. The main button makes the voice the one that answers,
 * quietly; it speaks only when "Listen" is touched.
 */
public final class VoicesActivity extends Activity {
    static final String EXTRA_VOICE = "voice";

    /** How long one scene drifts before the next fades in, and how long the fade takes. */
    private static final long SCENE_MS = 6000;
    private static final long FADE_MS = 900;
    private static final long PAGE_MS = 280;

    private int built;
    private FrameLayout frame;
    private View page;
    private int place;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Album album;
    private boolean moving;
    private boolean resumed;
    private GestureDetector swipe;
    /** Set by a fling, so the touch that made it does not also press a button under the finger. */
    private boolean flung;

    static void open(Activity a, int voice) {
        a.startActivity(new Intent(a, VoicesActivity.class).putExtra(EXTRA_VOICE, voice));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        int voice = getIntent().getIntExtra(EXTRA_VOICE, Prefs.role(this, Cast.TALK));
        place = Math.max(0, Math.min(Cast.size(this) - 1, state != null ? state.getInt("place", 0) : Cast.place(voice)));
        frame = new FrameLayout(this);
        frame.setBackgroundColor(Palette.BG);
        setContentView(frame);
        swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || Math.abs(vy) < Math.abs(vx) || Math.abs(vy) < 600) {
                    return false;
                }
                flung = true;
                turn(vy < 0 ? 1 : -1);
                return true;
            }
        });
        page = build(Cast.at(place));
        frame.addView(page, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** Every touch passes the swipe detector first, so a swipe works over the buttons too. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (swipe != null) {
            swipe.onTouchEvent(e);
        }
        if (flung && (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL)) {
            flung = false;
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("place", place);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        resumed = true;
        if (album != null && !moving) {
            album.start();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (album != null) {
            album.stop();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (album != null) {
            album.release();
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Swipes to the neighbouring voice: up for the next, down for the one before. No wrap at the ends. */
    private void turn(int step) {
        int next = place + step;
        if (moving || next < 0 || next >= Cast.size(this)) {
            return;
        }
        moving = true;
        place = next;
        final Album gone = album;
        if (gone != null) {
            gone.stop();
        }
        final View old = page;
        page = build(Cast.at(place));
        int h = frame.getHeight();
        page.setTranslationY(step * h);
        frame.addView(page, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        page.requestApplyInsets();
        old.animate().setListener(null).translationY(-step * h).setDuration(PAGE_MS).start();
        page.animate().setListener(null).translationY(0).setDuration(PAGE_MS).withEndAction(new Runnable() {
            @Override
            public void run() {
                frame.removeView(old);
                if (gone != null) {
                    gone.release();
                }
                moving = false;
                if (resumed && !isFinishing()) {
                    album.start();
                }
            }
        }).start();
    }

    /** One voice's card: the album, its ticks, the place among the voices, name, roles, two buttons. */
    private View build(final int v) {
        FrameLayout card = new FrameLayout(this);
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int pictureH = Math.round(h * 0.68f);

        FrameLayout pictures = new FrameLayout(this);
        pictures.setClipChildren(true);
        ImageView back = new ImageView(this);
        ImageView front = new ImageView(this);
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        front.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pictures.addView(back, new FrameLayout.LayoutParams(w, pictureH));
        pictures.addView(front, new FrameLayout.LayoutParams(w, pictureH));
        // The picture sinks into the room's colour towards its lower edge.
        View sink = new View(this);
        sink.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {Color.TRANSPARENT, Color.TRANSPARENT, Palette.BG}));
        pictures.addView(sink, new FrameLayout.LayoutParams(w, pictureH));
        card.addView(pictures, new FrameLayout.LayoutParams(w, pictureH));

        LinearLayout ticks = Ui.row(this);
        int count = sceneCount(v);
        View[] marks = new View[count];
        for (int i = 0; i < count; i++) {
            marks[i] = new View(this);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(3), 1f);
            if (i > 0) {
                p.leftMargin = dp(6);
            }
            ticks.addView(marks[i], p);
        }

        LinearLayout over = Ui.column(this);
        over.addView(ticks, Ui.lp(Ui.MATCH, dp(3)));
        ImageView close = Ui.iconButton(this, R.drawable.ic_back, (Palette.BG & 0x00FFFFFF) | 0xCC000000, 44,
                getString(R.string.ws_back));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        LinearLayout.LayoutParams cp = Ui.lp(dp(44), dp(44));
        cp.topMargin = dp(14);
        over.addView(close, cp);
        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        card.addView(over, op);
        Kit.edgeToEdge(this, over, dp(20), dp(12), dp(20), 0);

        // Where this voice stands among the others: a dot each, its own in its colour.
        LinearLayout dots = Ui.column(this);
        dots.setGravity(Gravity.CENTER_HORIZONTAL);
        for (int i = 0; i < Cast.size(this); i++) {
            View dot = new View(this);
            boolean on = i == place;
            int d = dp(on ? 12 : 8);
            dot.setBackground(Ui.oval(on ? Palette.voice(Cast.at(i)) : Palette.MUTED, 0, 0, this));
            dot.setAlpha(on ? 1f : 0.6f);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(d, d);
            p.topMargin = dp(9);
            dots.addView(dot, p);
        }
        FrameLayout.LayoutParams where = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        where.rightMargin = dp(14);
        where.bottomMargin = h / 6;
        card.addView(dots, where);

        LinearLayout below = Ui.column(this);
        below.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView name = Ui.text(this, Cast.name(this, v), 40, Palette.INK);
        name.setTypeface(Palette.display(this));
        name.setGravity(Gravity.CENTER);
        below.addView(name, Ui.lp(Ui.MATCH, Ui.WRAP));
        TextView roles = Ui.text(this, roles(v), 14, Palette.MUTED);
        roles.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rp = Ui.lp(Ui.WRAP, Ui.WRAP);
        rp.topMargin = dp(6);
        below.addView(roles, rp);
        boolean answering = Prefs.role(this, Cast.TALK) == v;
        if (answering) {
            // Already the one that answers: said plainly instead of a button that does nothing.
            TextView now = Ui.text(this, getString(Cast.female(v) ? R.string.voices_answers_f : R.string.voices_answers_m),
                    15, Palette.ACCENT_TEXT);
            now.setTypeface(Palette.bodyStrong(this));
            now.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams np = Ui.lp(Ui.MATCH, dp(52));
            np.topMargin = dp(20);
            below.addView(now, np);
        } else {
            TextView choose = Kit.primary(this, getString(Cast.female(v) ? R.string.voices_choose_f
                    : R.string.voices_choose_m), new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            choose(v);
                        }
                    });
            LinearLayout.LayoutParams pp = Ui.lp(Ui.MATCH, dp(52));
            pp.topMargin = dp(20);
            below.addView(choose, pp);
        }
        TextView listen = Kit.secondary(this, "▷  " + getString(R.string.lab_listen), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                VoiceService.speak(VoicesActivity.this, getString(Cast.female(v) ? R.string.intro_lead_f
                        : R.string.intro_lead, Cast.spokenName(VoicesActivity.this, v)), v);
            }
        });
        LinearLayout.LayoutParams lp = Ui.lp(dp(180), dp(48));
        lp.topMargin = dp(12);
        below.addView(listen, lp);
        // Where the swipe leads, said in words: the next voice by name, a touch goes there too.
        if (place + 1 < Cast.size(this)) {
            TextView onward = Ui.text(this, getString(R.string.voices_next, Cast.name(this, Cast.at(place + 1))),
                    13, Palette.MUTED);
            onward.setGravity(Gravity.CENTER);
            onward.setPadding(0, dp(14), 0, dp(4));
            onward.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    turn(1);
                }
            });
            below.addView(onward, Ui.lp(Ui.MATCH, Ui.WRAP));
        }
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        card.addView(below, bp);
        Kit.edgeToEdge(this, below, dp(36), 0, dp(36), dp(28));

        album = new Album(v, count, back, front, marks);
        return card;
    }

    /** Makes the voice the one that answers, as the main screen does: quietly. */
    private void choose(int v) {
        if (moving) {
            return;
        }
        Prefs.setRole(this, Cast.TALK, v);
        Prefs.setHomeVoice(this, v);
        Widgets.refresh(this);
        Diag.mark(this, "voices: " + v + " answers now");
        View old = page;
        Album gone = album;
        if (gone != null) {
            gone.stop();
        }
        page = build(v);
        frame.addView(page, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        page.requestApplyInsets();
        frame.removeView(old);
        if (gone != null) {
            gone.release();
        }
        if (resumed) {
            album.start();
        }
    }

    /** What the voice does now, in plain words; nothing when it holds no role. */
    private String roles(int v) {
        StringBuilder s = new StringBuilder();
        if (Prefs.role(this, Cast.NARRATOR) == v) {
            s.append(getString(R.string.voices_role_books));
        }
        if (Prefs.role(this, Cast.HERALD) == v) {
            s.append(s.length() > 0 ? " · " : "").append(getString(R.string.voices_role_news));
        }
        if (Prefs.role(this, Cast.TALK) == v) {
            s.append(s.length() > 0 ? " · " : "").append(getString(R.string.voices_role_talk));
        }
        return s.length() > 0 ? s.toString() : getString(R.string.voices_role_none);
    }

    private int sceneCount(int v) {
        try {
            String[] all = getAssets().list("cards/" + v);
            int n = 0;
            while (all != null && java.util.Arrays.asList(all).contains((n + 1) + ".jpg")) {
                n++;
            }
            return n;
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    private Bitmap scene(int v, int n) {
        try (InputStream in = getAssets().open("cards/" + v + "/" + n + ".jpg")) {
            // Half the memory of full colour; a photograph does not lose to it on a phone screen.
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** A voice's scenes as an album: drift over one, fade into the next, round and round. */
    private final class Album implements Runnable {
        private final int voice;
        private final int count;
        private ImageView shown;
        private ImageView next;
        private final View[] marks;
        private int index;
        private boolean running;
        private ValueAnimator drift;

        Album(int voice, int count, ImageView back, ImageView front, View[] marks) {
            this.voice = voice;
            this.count = count;
            this.shown = front;
            this.next = back;
            this.marks = marks;
            shown.setImageBitmap(count > 0 ? scene(voice, 1) : null);
            next.setAlpha(0f);
            paintMarks();
        }

        void start() {
            if (running || count == 0) {
                return;
            }
            running = true;
            driftOver(shown);
            if (count > 1) {
                handler.postDelayed(this, SCENE_MS);
            }
        }

        void stop() {
            running = false;
            handler.removeCallbacks(this);
            if (drift != null) {
                drift.cancel();
            }
            // A fade caught halfway is finished at once, so the album resumes from a settled scene.
            next.animate().cancel();
            shown.animate().cancel();
            if (next.getAlpha() > 0.5f) {
                ImageView was = shown;
                shown = next;
                next = was;
            }
            shown.setAlpha(1f);
            next.setAlpha(0f);
            next.setImageBitmap(null);
        }

        /** Lets go of the pictures once the card is gone. */
        void release() {
            stop();
            shown.setImageBitmap(null);
            next.setImageBitmap(null);
        }

        @Override
        public void run() {
            if (!running) {
                return;
            }
            index = (index + 1) % count;
            next.setImageBitmap(scene(voice, index + 1));
            next.setScaleX(1f);
            next.setScaleY(1f);
            next.setTranslationX(0f);
            // Just above the scene on show, still under the shade that sinks the picture into the room.
            android.view.ViewGroup holder = (android.view.ViewGroup) next.getParent();
            android.view.ViewGroup.LayoutParams keep = next.getLayoutParams();
            holder.removeView(next);
            holder.addView(next, holder.indexOfChild(shown) + 1, keep);
            // The fade lets the next scene come through over the one drifting away.
            next.animate().alpha(1f).setDuration(FADE_MS).withEndAction(new Runnable() {
                @Override
                public void run() {
                    ImageView gone = shown;
                    shown = next;
                    next = gone;
                    next.setAlpha(0f);
                    next.setImageBitmap(null);
                }
            }).start();
            driftOver(next);
            paintMarks();
            handler.postDelayed(this, SCENE_MS);
        }

        /** A slow push in with a little sideways glide, over the time a scene stays. */
        private void driftOver(final ImageView v) {
            if (drift != null) {
                drift.cancel();
            }
            final float side = (index % 2 == 0 ? 1 : -1) * dp(10);
            drift = ValueAnimator.ofFloat(0f, 1f);
            drift.setDuration(SCENE_MS + FADE_MS);
            drift.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    float t = (float) a.getAnimatedValue();
                    v.setScaleX(1f + 0.06f * t);
                    v.setScaleY(1f + 0.06f * t);
                    v.setTranslationX(side * t);
                }
            });
            drift.start();
        }

        private void paintMarks() {
            for (int i = 0; i < marks.length; i++) {
                marks[i].setBackground(Ui.round(i <= index ? Palette.INK : Palette.MUTED, 2, VoicesActivity.this));
                marks[i].setAlpha(i <= index ? 0.95f : 0.45f);
            }
        }
    }

    private int dp(float v) {
        return Kit.dp(this, v);
    }
}
