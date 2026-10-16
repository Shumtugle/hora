package com.shumtugle.hora;

import android.animation.AnimatorSet;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The Voice screen: the current voice as a portrait, its troupe, the line it
 * speaks when touched, and the way into the talk. Time and weather live on the Day.
 */
public final class MainActivity extends Activity {
    /** The look this screen was built with. */
    private int built;

    private int voice;
    private ImageView[] dots = new ImageView[0];
    private TextView hint;
    private View glow;
    private View ring;
    private ImageView photo;
    private TextView name;
    private TextView epithet;
    private TextView spoken;
    private ImageView backdrop;
    private AnimatorSet breathGlow;
    private AnimatorSet breathRing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state == null && isTaskRoot()) {
            // Opened on its own, by a shortcut kept from the days it was the first screen: the Day comes first now.
            startActivity(new Intent(this, DayActivity.class));
            finish();
            return;
        }
        built = Ui.prepare(this);
        setContentView(build());
        showCard(Prefs.role(this, Cast.TALK));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        // The voices, or the title page, may have changed the voice.
        showVoice(Prefs.role(this, Cast.TALK));
        showCard(voice);
        breathGlow.start();
        breathRing.start();
    }

    @Override
    protected void onPause() {
        breathGlow.cancel();
        breathRing.cancel();
        super.onPause();
    }

    /**
     * Makes a voice the host: the one on the Voice screen and in the talk.
     * Books and notifications keep their own readers. It steps forward and greets.
     */
    private void choose(int v) {
        Prefs.setRole(this, Cast.TALK, v);
        Prefs.setHomeVoice(this, v);
        Prefs.troupeHintUsed(this);
        showVoice(v);
        Widgets.refresh(this);
        // Changing the voice is quiet: its card is shown, and a tap on it makes the voice speak.
        TapTalk.reset();
        showCard(v);
    }


    /**
     * A sheet with one field for the voice's name. The field is empty with the
     * given name greyed in it: a single letter replaces it, and a field left
     * alone keeps it.
     */
    private void askName(final int v) {
        final android.widget.EditText field = new android.widget.EditText(this);
        Ui.field(field);
        field.setHint(Cast.givenName(this, v));
        String own = Prefs.voiceName(this, v);
        field.setText(own);
        field.setSelection(own.length());
        field.setTextSize(26);
        field.setTypeface(Palette.display(this));
        field.setGravity(Gravity.CENTER);
        field.setSingleLine(true);
        field.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(Prefs.NAME_MAX)});
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        LinearLayout box = Ui.column(this);
        box.addView(Kit.lead(this, getString(R.string.name_ask)));
        LinearLayout.LayoutParams fp = Ui.lp(Ui.MATCH, Ui.WRAP);
        fp.topMargin = dp(16);
        box.addView(field, fp);
        final Runnable save = new Runnable() {
            @Override
            public void run() {
                Prefs.setVoiceName(MainActivity.this, v, field.getText().toString());
                showVoice(voice);
                showCard(voice);
                Widgets.refresh(MainActivity.this);
            }
        };
        final android.app.Dialog d = Kit.sheet(this, null, box, getString(R.string.kit_done), save);
        field.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView t, int action, android.view.KeyEvent e) {
                d.dismiss();
                save.run();
                return true;
            }
        });
        // The keyboard comes only when the field is touched.
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        d.show();
    }

    private void showCard(int v) {
        spoken.setText(Ui.plain(String.format(Cast.say(this, R.array.voice_step_forward, v), Cast.spokenName(this, v))));
    }

    /** A tap on the line: the voice speaks about itself, never word for word. */
    private void tapLine() {
        TapTalk.Reply r = TapTalk.next(this, voice);
        if (r != null) {
            VoiceService.speak(this, r.text, voice, r.flat ? TapTalk.FLAT_PACE : 1f);
        }
    }

    private void showVoice(int v) {
        voice = v;
        Ui.setFace(photo, v);
        photo.setContentDescription(Cast.name(this, v));
        name.setText(Cast.name(this, v));
        // A small pen beside a name a person may change; an unseen twin on the other side keeps the name centred.
        name.setCompoundDrawablesRelativeWithIntrinsicBounds(Cast.renamable(v) ? R.drawable.ic_edit : 0, 0,
                Cast.renamable(v) ? R.drawable.ic_edit : 0, 0);
        android.graphics.drawable.Drawable[] pens = name.getCompoundDrawablesRelative();
        if (pens[0] != null) {
            pens[0].mutate().setAlpha(0);
        }
        name.setCompoundDrawablePadding(dp(6));
        name.setClickable(Cast.renamable(v));
        name.setContentDescription(Cast.renamable(v) ? getString(R.string.name_edit) + ": " + Cast.name(this, v) : null);
        glow.setBackground(Ui.oval(Palette.voice(v, 0x29), 0, 0, this));
        ring.setBackground(Ui.oval(Color.TRANSPARENT, Palette.voice(v), 2, this));
        for (int i = 0; i < dots.length; i++) {
            boolean on = Cast.at(i) == v;
            // Every face keeps the same slot, so choosing one never moves the others.
            Ui.troupeFace(this, dots[i], Cast.at(i), on);
        }
        hint.setVisibility(Prefs.troupeHintsLeft(this) > 0 ? View.VISIBLE : View.GONE);
        Kit.backdrop(this, backdrop, v);
    }

    private View build() {
        FrameLayout whole = new FrameLayout(this);
        whole.setBackgroundColor(Palette.BG);
        backdrop = new ImageView(this);
        backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        whole.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View dim = new View(this);
        dim.setBackgroundColor((Palette.BG & 0x00FFFFFF) | 0xCC000000);
        whole.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout root = Ui.column(this);
        whole.addView(root, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = Ui.header(this, null, true);
        root.addView(head, Ui.lp(Ui.MATCH, dp(48)));

        // The face, the name, the troupe and the line stand as one group in the middle of what is left.
        LinearLayout stage = Ui.column(this);
        stage.setGravity(Gravity.CENTER);
        FrameLayout face = new FrameLayout(this);
        face.setClipChildren(false);
        glow = new View(this);
        face.addView(glow, new FrameLayout.LayoutParams(dp(208), dp(208), Gravity.CENTER));
        ring = new View(this);
        face.addView(ring, new FrameLayout.LayoutParams(dp(176), dp(176), Gravity.CENTER));
        photo = Ui.portrait(this, 1);
        photo.setClickable(true);
        // A touch opens the voices, at this one; a sideways swipe brings the next voice forward.
        final android.view.GestureDetector gestures = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(android.view.MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(android.view.MotionEvent e) {
                        VoicesActivity.open(MainActivity.this, voice);
                        return true;
                    }

                    @Override
                    public boolean onFling(android.view.MotionEvent a, android.view.MotionEvent b, float vx, float vy) {
                        if (a == null || Math.abs(vx) < Math.abs(vy) || Math.abs(b.getX() - a.getX()) < dp(40)) {
                            return false;
                        }
                        int n = dots.length;
                        int step = vx < 0 ? 1 : n - 1;
                        choose(Cast.at((Cast.place(voice) + step) % n));
                        return true;
                    }
                });
        photo.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                return gestures.onTouchEvent(e);
            }
        });
        face.addView(photo, new FrameLayout.LayoutParams(dp(148), dp(148), Gravity.CENTER));
        // The glow and ring breathe past their size; the frame leaves room so they are never cut.
        // The portrait takes the height that is left and shrinks on a shorter screen.
        Kit.Fit fit = new Kit.Fit(this);
        fit.cap(dp(228));
        fit.addView(face, new FrameLayout.LayoutParams(dp(228), dp(228), Gravity.CENTER));
        stage.addView(fit, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        breathGlow = Ui.breathe(glow, 0.92f, 1.02f, 0.5f, 1f);
        breathRing = Ui.breathe(ring, 0.94f, 1.04f, 0.55f, 1f);

        name = Ui.title(this, "", 30);
        name.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (Cast.renamable(voice)) {
                    askName(voice);
                }
            }
        });
        LinearLayout.LayoutParams np = Ui.lp(Ui.WRAP, Ui.WRAP);
        np.topMargin = dp(14);
        stage.addView(name, np);
        epithet = Ui.text(this, "", 12, Palette.MUTED);
        epithet.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams ep = Ui.lp(Ui.WRAP, Ui.WRAP);
        ep.topMargin = dp(4);
        // No titles under the name: the name is enough.
        epithet.setVisibility(View.GONE);
        stage.addView(epithet, ep);

        // The whole troupe as rows of small faces of equal length: eight stand four and four,
        // nine five and four. The chosen one is marked by its ring, not by its size.
        LinearLayout troupe = Ui.column(this);
        troupe.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout line = null;
        dots = new ImageView[Cast.size(this)];
        int perRow = Ui.perRow(dots.length);
        for (int i = 0; i < dots.length; i++) {
            final int v = Cast.at(i);
            if (i % perRow == 0) {
                line = Ui.row(this);
                line.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams linep = Ui.lp(Ui.WRAP, Ui.WRAP);
                if (i > 0) {
                    linep.topMargin = dp(6);
                }
                troupe.addView(line, linep);
            }
            dots[i] = Ui.portrait(this, v);
            dots[i].setClickable(true);
            dots[i].setContentDescription(getString(R.string.home_make_main, Cast.name(this, v)));
            dots[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (v != voice) {
                        choose(v);
                    }
                }
            });
            line.addView(dots[i]);
        }
        LinearLayout.LayoutParams tp = Ui.lp(Ui.WRAP, Ui.WRAP);
        tp.topMargin = dp(14);
        stage.addView(troupe, tp);
        hint = Ui.text(this, getString(R.string.home_troupe_hint), 11, Palette.HINT);
        LinearLayout.LayoutParams hp = Ui.lp(Ui.WRAP, Ui.WRAP);
        hp.topMargin = dp(8);
        stage.addView(hint, hp);

        spoken = Ui.title(this, "", 19);
        spoken.setTypeface(Palette.display(this));
        // The last line only, two lines at most; the whole talk lives in its own room.
        spoken.setMaxLines(2);
        spoken.setEllipsize(android.text.TextUtils.TruncateAt.END);
        spoken.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tapLine();
            }
        });
        spoken.setGravity(Gravity.CENTER);
        spoken.setLineSpacing(0, 1.15f);
        spoken.setMinHeight(dp(56));
        spoken.setMaxWidth(dp(320));
        LinearLayout.LayoutParams sp = Ui.lp(Ui.WRAP, Ui.WRAP);
        sp.topMargin = dp(14);
        sp.bottomMargin = dp(12);
        stage.addView(spoken, sp);
        root.addView(stage, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        // One way on from here: the talk. The book is a tab of its own; the voices are behind the portrait.
        LinearLayout ways = Kit.plate(this);
        ways.setPadding(0, dp(4), 0, dp(4));
        ways.addView(Kit.rowEntry(this, R.drawable.ic_talk, getString(R.string.talk_title),
                getString(R.string.talk_sub), open(TalkActivity.class)), Ui.lp(Ui.MATCH, Ui.WRAP));
        root.addView(ways, Ui.lp(Ui.MATCH, Ui.WRAP));
        LinearLayout.LayoutParams navGap = Ui.lp(Ui.MATCH, Ui.WRAP);
        navGap.topMargin = dp(10);
        LinearLayout nav = Ui.navBar(this, Ui.NAV_VOICE);
        root.addView(nav, navGap);
        Kit.edgeToEdge(this, root, dp(20), dp(20), dp(20), dp(16));
        return whole;
    }

    private View.OnClickListener open(final Class<?> screen) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, screen));
            }
        };
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
