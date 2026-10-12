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
 * quietly. "Listen" reads the demo novel from where it stopped; a swipe hands
 * the book to the next voice, so voices are compared on prose. The voice's own
 * makeup (pitch, timbre, air) is a sheet over its card, heard while it reads.
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
    /** Whether the demo is being read here; a swipe hands it to the next voice. */
    private boolean listening;
    /** The book that was open before the demo was started here, put back when the voices are left. */
    private String beforeUri;
    private String beforeName;
    /** The demo's own reader before this visit, put back with the book. */
    private int beforeDemoVoice = -1;
    /** Whether the demo was started on this visit; one found already reading is not paused on leaving. */
    private boolean startedHere;
    /** Counts starts, so a start that comes back from its thread after a stop does nothing. */
    private int ticket;
    private TextView listenButton;
    private LinearLayout duties;

    static final String EXTRA_MAKEUP = "makeup";

    static void open(Activity a, int voice) {
        a.startActivity(new Intent(a, VoicesActivity.class).putExtra(EXTRA_VOICE, voice));
    }

    /** Opens the voice's card with its makeup sheet already over it. */
    static void openMakeup(Activity a, int voice) {
        a.startActivity(new Intent(a, VoicesActivity.class).putExtra(EXTRA_VOICE, voice).putExtra(EXTRA_MAKEUP, true));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        int voice = getIntent().getIntExtra(EXTRA_VOICE, Prefs.role(this, Cast.TALK));
        place = Math.max(0, Math.min(Cast.size(this) - 1, state != null ? state.getInt("place", 0) : Cast.place(voice)));
        if (state != null) {
            beforeUri = state.getString("beforeUri");
            beforeName = state.getString("beforeName");
            beforeDemoVoice = state.getInt("beforeDemoVoice", -1);
        }
        // The demo may already be reading when the voices open: the button says so, and a swipe hands it on.
        listening = Demo.is(this, Reading.uri(this)) && Reading.playing(this);
        if (state != null) {
            startedHere = state.getBoolean("startedHere");
        } else if (listening) {
            // Found reading: on leaving it reads on, with the reader it had.
            beforeDemoVoice = Demo.voice(this);
        }
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
        if (state == null && getIntent().getBooleanExtra(EXTRA_MAKEUP, false)) {
            frame.post(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing()) {
                        makeup(Cast.at(place));
                    }
                }
            });
        }
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
        out.putBoolean("listening", listening);
        out.putString("beforeUri", beforeUri);
        out.putString("beforeName", beforeName);
        out.putInt("beforeDemoVoice", beforeDemoVoice);
        out.putBoolean("startedHere", startedHere);
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
    protected void onStop() {
        // The screen going dark or turning is not leaving: the demo reads on.
        boolean dark = !((android.os.PowerManager) getSystemService(POWER_SERVICE)).isInteractive();
        if (!dark && !isChangingConfigurations()) {
            if (startedHere && (listening || beforeUri != null)) {
                stopDemo(true);
            } else if (beforeDemoVoice >= 0 && beforeDemoVoice != Demo.voice(this)) {
                // A demo found reading goes on, handed back to the reader it had.
                Demo.setVoice(this, beforeDemoVoice);
                beforeDemoVoice = -1;
            }
        }
        super.onStop();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // Back from elsewhere: the button tells the truth again.
        listening = Demo.is(this, Reading.uri(this)) && Reading.playing(this);
        showListen();
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
        goTo(place + step);
    }

    /** Goes to the voice at a place in the row, the page sliding the way the row runs. */
    private void goTo(int next) {
        if (moving || next == place || next < 0 || next >= Cast.size(this)) {
            return;
        }
        int step = next > place ? 1 : -1;
        moving = true;
        place = next;
        if (listening) {
            // The book goes round the table: the next voice reads on from the same place.
            Demo.setVoice(this, Cast.at(place));
            Diag.mark(this, "voices: demo handed to " + Cast.at(place));
        }
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

        LinearLayout below = Ui.column(this);
        below.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView name = Ui.text(this, Cast.name(this, v), 40, Palette.INK);
        name.setTypeface(Palette.display(this));
        name.setGravity(Gravity.CENTER);
        below.addView(name, Ui.lp(Ui.MATCH, Ui.WRAP));
        if (Cast.beta(v) && !Cast.echo(v)) {
            // A voice from the developer room says so in a word, in the accent colour, as
            // temporary things do elsewhere.
            TextView beta = Ui.text(this, getString(R.string.voices_beta), 13, Palette.ACCENT_TEXT);
            beta.setGravity(Gravity.CENTER);
            below.addView(beta, Ui.lp(Ui.MATCH, Ui.WRAP));
        }

        // What the voice does: three duties as chips. A lit one is this voice's; a touch on a dark
        // one gives the duty to this voice, taking it from whoever had it.
        TextView ask = Ui.text(this, getString(Cast.female(v) ? R.string.voices_assign_f : R.string.voices_assign_m),
                13, Palette.HINT);
        ask.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ap = Ui.lp(Ui.WRAP, Ui.WRAP);
        ap.topMargin = dp(10);
        below.addView(ask, ap);
        duties = Ui.row(this);
        duties.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dp0 = Ui.lp(Ui.MATCH, Ui.WRAP);
        dp0.topMargin = dp(8);
        below.addView(duties, dp0);
        fillDuties(v);

        // Listen and tune, in words.
        LinearLayout pair = Ui.row(this);
        listenButton = Kit.secondary(this, "", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (listening) {
                    stopDemo(false);
                } else {
                    startDemo(v);
                }
            }
        });
        pair.addView(listenButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView tune = Kit.secondary(this, getString(R.string.voices_tune), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                makeup(v);
            }
        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        tp.leftMargin = dp(10);
        pair.addView(tune, tp);
        LinearLayout.LayoutParams pp = Ui.lp(Ui.MATCH, Ui.WRAP);
        pp.topMargin = dp(18);
        below.addView(pair, pp);
        // What "listen" reads, said once: without it the button promises nothing.
        TextView what = Ui.text(this, getString(R.string.voices_demo_note, Demo.title(this)), 12, Palette.HINT);
        what.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams wp = Ui.lp(Ui.MATCH, Ui.WRAP);
        wp.topMargin = dp(8);
        below.addView(what, wp);
        showListen();

        // The whole row of voices, this one in its ring: there are several, and a touch goes there.
        LinearLayout strip = Ui.row(this);
        strip.setGravity(Gravity.CENTER);
        int n = Cast.size(this);
        float width = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        int cell = Math.max(24, Math.min(44, (int) ((width - 72) / n)));
        int side = cell - 8;
        for (int i = 0; i < n; i++) {
            final int at = i;
            int fv = Cast.at(i);
            boolean on = fv == v;
            ImageView f = Ui.portrait(this, fv);
            f.setPadding(dp(2), dp(2), dp(2), dp(2));
            f.setBackground(on ? Ui.oval(Palette.BG, Palette.voice(fv), 2, this)
                    : Ui.oval(Color.TRANSPARENT, Palette.LINE, 1, this));
            f.setAlpha(on ? 1f : 0.6f);
            f.setContentDescription(Cast.name(this, fv));
            f.setClickable(true);
            f.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    goTo(at);
                }
            });
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(dp(side), dp(side));
            fp.leftMargin = dp(4);
            fp.rightMargin = dp(4);
            strip.addView(f, fp);
        }
        LinearLayout.LayoutParams sp = Ui.lp(Ui.MATCH, Ui.WRAP);
        sp.topMargin = dp(22);
        below.addView(strip, sp);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        card.addView(below, bp);
        Kit.edgeToEdge(this, below, dp(36), 0, dp(36), dp(28));

        album = new Album(v, count, back, front, marks);
        return card;
    }

    /** The listen button and the line under it, as things stand. */
    private void showListen() {
        if (listenButton == null) {
            return;
        }
        listenButton.setText(listening ? "\u275a\u275a  " + getString(R.string.voices_pause)
                : "\u25b7  " + getString(R.string.lab_listen));
    }

    /** Starts the demo with this voice, from where the demo stopped; the open book is remembered. */
    private void startDemo(final int v) {
        if (!Voice.ready(this)) {
            android.widget.Toast.makeText(this, R.string.voice_pack_needed, android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        final String now = Reading.uri(this);
        if (!Demo.is(this, now) && beforeUri == null) {
            beforeUri = now;
            beforeName = Reading.name(this);
        }
        if (beforeDemoVoice < 0) {
            beforeDemoVoice = Demo.voice(this);
        }
        listening = true;
        startedHere = true;
        showListen();
        final int mine = ++ticket;
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Demo.book(app);
                } catch (java.io.IOException e) {
                    Diag.log(app, "voices: the demo could not be prepared", e);
                    return;
                }
                // Back on the screen's thread: a stop that came meanwhile wins.
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (mine != ticket || !listening || isFinishing()) {
                            return;
                        }
                        Demo.setVoice(app, v);
                        Diag.mark(app, "voices: demo read by " + v);
                        if (Demo.is(app, Reading.uri(app))) {
                            startForegroundService(BookPlayer.intent(app, BookPlayer.ACTION_PLAY));
                        } else {
                            BookActivity.open(app, Demo.uri(app), "book.txt", true);
                        }
                    }
                });
            }
        }, "voices-demo").start();
    }

    /**
     * Stops the demo. On leaving the voices, the book that was open before comes back,
     * quietly, at its own place, with the demo's own reader as it was: listening here
     * never takes anything away. With no book open before, the demo stays, paused.
     */
    private void stopDemo(boolean leaving) {
        ticket++;
        listening = false;
        showListen();
        startForegroundService(BookPlayer.intent(this, BookPlayer.ACTION_PAUSE));
        if (leaving) {
            if (beforeDemoVoice >= 0) {
                Demo.setVoice(this, beforeDemoVoice);
            }
            if (beforeUri != null && !beforeUri.isEmpty()) {
                BookActivity.open(getApplicationContext(), beforeUri, beforeName, false);
            }
            beforeUri = null;
            beforeDemoVoice = -1;
            startedHere = false;
        }
    }

    /** Pitch, timbre and air of one voice, in a sheet; each move is heard at once. */
    private void makeup(final int v) {
        final LinearLayout box = Ui.column(this);
        fillMakeup(box, v);
        Kit.liveSheet(this, Cast.name(this, v), box);
    }

    private void fillMakeup(final LinearLayout box, final int v) {
        box.removeAllViews();
        box.addView(Kit.slider(this, getString(R.string.ws_pitch), getString(R.string.lab_lower),
                getString(R.string.lab_higher), Grim.PITCH_MAX - Grim.PITCH_MIN, Prefs.grimPitch(this, v) - Grim.PITCH_MIN,
                new Kit.Value() {
                    @Override
                    public String show(int p) {
                        return signed(p + Grim.PITCH_MIN);
                    }

                    @Override
                    public void set(int p) {
                        Prefs.setGrim(VoicesActivity.this, v, Prefs.grimTimbre(VoicesActivity.this, v), p + Grim.PITCH_MIN);
                    }
                }));
        box.addView(Kit.slider(this, getString(R.string.ws_timbre), getString(R.string.lab_warmer),
                getString(R.string.lab_brighter), Grim.TIMBRE_MAX - Grim.TIMBRE_MIN,
                Prefs.grimTimbre(this, v) - Grim.TIMBRE_MIN, new Kit.Value() {
                    @Override
                    public String show(int p) {
                        return signed(p + Grim.TIMBRE_MIN);
                    }

                    @Override
                    public void set(int p) {
                        Prefs.setGrim(VoicesActivity.this, v, p + Grim.TIMBRE_MIN, Prefs.grimPitch(VoicesActivity.this, v));
                    }
                }));
        box.addView(Kit.slider(this, getString(R.string.lab_air), getString(R.string.lab_none),
                getString(R.string.lab_much), Grim.AIR_MAX, Prefs.grimAir(this, v), new Kit.Value() {
                    @Override
                    public String show(int p) {
                        return String.valueOf(p);
                    }

                    @Override
                    public void set(int p) {
                        Prefs.setGrimAir(VoicesActivity.this, v, p);
                    }
                }));
        TextView reset = Kit.link(this, getString(R.string.lab_reset), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Prefs.setGrim(VoicesActivity.this, v, Prefs.defaultTimbre(v), Prefs.defaultPitch(v));
                Prefs.setGrimAir(VoicesActivity.this, v, Prefs.defaultAir(v));
                fillMakeup(box, v);
            }
        });
        box.addView(reset, Kit.below(this, 8));
    }

    private static String signed(int n) {
        return n > 0 ? "+" + n : String.valueOf(n);
    }

    private static final int[] DUTIES = {Cast.TALK, Cast.NARRATOR, Cast.HERALD};
    private static final int[] DUTY_NAMES = {R.string.role_talk, R.string.voices_duty_books, R.string.role_herald};

    /** The three duties of a voice as chips, lit where the duty is this voice's. */
    private void fillDuties(final int v) {
        duties.removeAllViews();
        for (int i = 0; i < DUTIES.length; i++) {
            final int role = DUTIES[i];
            boolean mine = Prefs.role(this, role) == v;
            TextView chip = Ui.pill(this, (mine ? "\u2713 " : "") + getString(DUTY_NAMES[i]),
                    mine ? Palette.RAISED : Palette.SURFACE, mine ? Palette.voice(v) : Palette.LINE,
                    mine ? Palette.INK : Palette.MUTED, mine);
            // Three must fit on a narrow phone in either language: smaller type, tighter sides.
            chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setPadding(dp(11), 0, dp(11), 0);
            chip.setSingleLine(true);
            if (!mine) {
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        give(role, v);
                    }
                });
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
            if (i > 0) {
                p.leftMargin = dp(6);
            }
            duties.addView(chip, p);
        }
    }

    /** Gives a duty to a voice, as the main screen and the roles do: quietly, heard the next time it speaks. */
    private void give(int role, int v) {
        if (moving) {
            return;
        }
        Prefs.setRole(this, role, v);
        if (role == Cast.TALK) {
            Prefs.setHomeVoice(this, v);
        }
        Widgets.refresh(this);
        Diag.mark(this, "voices: " + v + " takes role " + role);
        fillDuties(v);
    }

    private int sceneCount(int v) {
        try {
            String[] all = getAssets().list("cards/" + Cast.origin(v));
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
        try (InputStream in = getAssets().open("cards/" + Cast.origin(v) + "/" + n + ".jpg")) {
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
