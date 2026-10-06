package com.shumtugle.hora;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The very first start. A short scene: the colour of the room runs from the
 * sign's red through green and settles on the room's own blue while the coin
 * comes up out of it. Then, one to a page, what Hora asks of the phone, each
 * with a way past it; whatever is already given is not asked. At the end the
 * title page.
 */
public final class FirstRunActivity extends Activity {
    private static final String STATE_STEP = "step";
    private static final int RED = 0xFFA83428;
    private static final int GREEN = 0xFF2F4A38;
    private static final long SCENE_MS = 3600;
    private static final String NOTIFY_PERMISSION = "android.permission.POST_NOTIFICATIONS";
    private static final int REQUEST_NOTIFY = 1;
    private static final int REQUEST_PLACE = 2;

    private static final int NOTIFY = 0;
    private static final int HERALD = 1;
    private static final int FOLDER = 2;
    private static final int WEATHER = 3;
    private static final int ENGINE = 4;
    private static final int MODEL = 5;
    private static final int STEPS = 6;

    private static final int[] TITLES = {R.string.first_notify_title, R.string.first_herald_title,
        R.string.first_folder_title, R.string.first_weather_title, R.string.first_engine_title,
        R.string.first_model_title};
    private static final int[] TEXTS = {R.string.first_notify_text, R.string.first_herald_text,
        R.string.first_folder_text, R.string.first_weather_text, R.string.first_engine_text,
        R.string.first_model_text};
    private static final int[] ACTIONS = {R.string.first_notify_action, R.string.first_open_settings,
        R.string.first_folder_action, R.string.first_weather_action, R.string.first_open_settings,
        R.string.first_model_action};

    private int built;
    private FrameLayout root;
    /** The page shown, or -1 while the scene plays. */
    private int step = -1;
    /** Set when a page sent the user to another screen: coming back moves on. */
    private boolean away;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        root = new FrameLayout(this);
        setContentView(root);
        Kit.edgeToEdge(this, root, 0, 0, 0, 0);
        if (state != null && state.getInt(STATE_STEP, -1) >= 0) {
            root.setBackgroundColor(Palette.BG);
            show(state.getInt(STATE_STEP));
        } else {
            scene();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_STEP, step);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        if (step >= 0 && (away || done(step))) {
            away = false;
            show(step + 1);
        }
    }

    // The scene.

    private void scene() {
        root.setBackgroundColor(RED);
        LinearLayout col = Ui.column(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        final ImageView coin = new ImageView(this);
        coin.setImageResource(R.drawable.hora_coin);
        coin.setContentDescription(getString(R.string.app_name));
        coin.setAlpha(0f);
        coin.setScaleX(0.9f);
        coin.setScaleY(0.9f);
        col.addView(coin, Ui.lp(dp(220), dp(220)));
        final TextView lead = Ui.text(this, getString(R.string.first_lead), 17, Palette.INK);
        lead.setGravity(Gravity.CENTER);
        lead.setLineSpacing(0, 1.25f);
        lead.setAlpha(0f);
        LinearLayout.LayoutParams lp = Ui.lp(Ui.MATCH, Ui.WRAP);
        lp.topMargin = dp(28);
        col.addView(lead, lp);
        final View start = Kit.primary(this, getString(R.string.intro_start), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(0);
            }
        });
        start.setAlpha(0f);
        start.setEnabled(false);
        LinearLayout.LayoutParams sp = Ui.lp(Ui.MATCH, Ui.WRAP);
        sp.topMargin = dp(36);
        col.addView(start, sp);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.CENTER);
        cp.leftMargin = dp(32);
        cp.rightMargin = dp(32);
        root.addView(col, cp);

        ValueAnimator colour = ValueAnimator.ofObject(new ArgbEvaluator(), RED, GREEN, Palette.BG);
        colour.setDuration(SCENE_MS);
        colour.setInterpolator(new DecelerateInterpolator(0.8f));
        colour.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                root.setBackgroundColor((Integer) a.getAnimatedValue());
            }
        });
        colour.start();
        coin.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(SCENE_MS / 6).setDuration(SCENE_MS / 2)
                .setInterpolator(new DecelerateInterpolator()).start();
        lead.animate().alpha(1f).setStartDelay(SCENE_MS * 3 / 4).setDuration(600).start();
        start.animate().alpha(1f).setStartDelay(SCENE_MS * 3 / 4 + 200).setDuration(600)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        start.setEnabled(true);
                    }
                }).start();
    }

    // The pages.

    /** Shows the first page from the given one on that still asks for something, or ends. */
    private void show(int from) {
        int s = from;
        while (s < STEPS && done(s)) {
            s++;
        }
        if (s >= STEPS) {
            end();
            return;
        }
        step = s;
        root.removeAllViews();
        root.setBackgroundColor(Palette.BG);
        LinearLayout page = Ui.column(this);
        page.setPadding(dp(28), dp(24), dp(28), dp(24));
        page.setGravity(Gravity.CENTER_VERTICAL);

        ImageView sign = new ImageView(this);
        sign.setImageResource(R.drawable.hora_coin);
        sign.setAlpha(0.9f);
        page.addView(sign, Ui.lp(dp(72), dp(72)));

        TextView count = Ui.text(this, getString(R.string.first_step_of, s + 1, STEPS), 13, Palette.MUTED);
        LinearLayout.LayoutParams np = Ui.lp(Ui.MATCH, Ui.WRAP);
        np.topMargin = dp(28);
        page.addView(count, np);
        TextView title = Ui.title(this, getString(TITLES[s]), 32);
        LinearLayout.LayoutParams tp = Ui.lp(Ui.MATCH, Ui.WRAP);
        tp.topMargin = dp(6);
        page.addView(title, tp);
        TextView text = Ui.text(this, getString(TEXTS[s]), 17, Palette.INK);
        text.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams xp = Ui.lp(Ui.MATCH, Ui.WRAP);
        xp.topMargin = dp(14);
        page.addView(text, xp);

        final int which = s;
        View act = Kit.primary(this, getString(ACTIONS[s]), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act(which);
            }
        });
        LinearLayout.LayoutParams ap = Ui.lp(Ui.MATCH, Ui.WRAP);
        ap.topMargin = dp(36);
        page.addView(act, ap);
        TextView skip = Kit.link(this, getString(R.string.first_skip), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(which + 1);
            }
        });
        skip.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams kp = Ui.lp(Ui.MATCH, Ui.WRAP);
        kp.topMargin = dp(4);
        page.addView(skip, kp);

        page.setAlpha(0f);
        root.addView(page, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        Kit.edgeToEdge(this, page, dp(28), dp(24), dp(28), dp(24));
        page.animate().alpha(1f).setDuration(250).start();
    }

    /** Whether what a page asks for is already given. */
    private boolean done(int s) {
        switch (s) {
            case NOTIFY:
                return Build.VERSION.SDK_INT < 33
                        || checkSelfPermission(NOTIFY_PERMISSION) == PackageManager.PERMISSION_GRANTED;
            case HERALD:
                return Herald.granted(this);
            case FOLDER:
                return HoraFolder.tree(this) != null;
            case WEATHER:
                return Weather.hasPlace(this);
            case ENGINE:
                return Transfer.defaultEngine(this);
            case MODEL:
                return Brain.get(this).hasModel() || ModelDownload.active(this, 0);
            default:
                return true;
        }
    }

    private void act(int s) {
        switch (s) {
            case NOTIFY:
                requestPermissions(new String[] {NOTIFY_PERMISSION}, REQUEST_NOTIFY);
                break;
            case HERALD:
                away = true;
                Herald.openAccess(this);
                break;
            case FOLDER:
                HoraFolder.ask(this);
                break;
            case WEATHER:
                startActivityForResult(new Intent(this, PlaceActivity.class), REQUEST_PLACE);
                break;
            case ENGINE:
                away = true;
                try {
                    startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
                } catch (android.content.ActivityNotFoundException e) {
                    away = false;
                    show(s + 1);
                }
                break;
            case MODEL:
                away = true;
                startActivity(new Intent(this, BrainActivity.class));
                break;
            default:
                show(s + 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] names, int[] results) {
        super.onRequestPermissionsResult(request, names, results);
        if (request == REQUEST_NOTIFY) {
            show(NOTIFY + 1);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == HoraFolder.PICK) {
            if (result == RESULT_OK && HoraFolder.accept(this, data)) {
                final android.content.Context app = getApplicationContext();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Books.moveOut(app);
                    }
                }, "first-folder").start();
                show(FOLDER + 1);
                // A phone that had Hora before: the offer to bring it all back.
                RestoreActivity.offerIfFresh(this);
                return;
            }
            show(FOLDER + 1);
        } else if (request == REQUEST_PLACE) {
            show(WEATHER + 1);
        }
    }

    /** The pages are over: the title page, which marks the first start as seen. */
    private void end() {
        startActivity(new Intent(this, IntroActivity.class));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private int dp(float v) {
        return Kit.dp(this, v);
    }
}
