package com.shumtugle.hora;

import android.animation.AnimatorSet;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
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

import java.util.Calendar;

/**
 * The main screen: the current voice as a portrait, what it last said, a few
 * quick questions and a line to type anything for it to read.
 */
public final class MainActivity extends Activity {
    /** The look this screen was built with. */
    private int built;
    private static final String NOTIFY_PERMISSION = "android.permission.POST_NOTIFICATIONS";
    private static final String STATE_SPOKEN = "spoken";
    private static final String STATE_CARD = "card";
    /** The last answer on screen as it is to be spoken; a tap on it says it again. */
    private String shown;
    /** The line on screen is the voice's card: a tap makes the voice speak about itself, never word for word. */
    private boolean card = true;

    private int voice;
    private final ImageView[] dots = new ImageView[Cast.COUNT];
    private TextView hint;
    private View glow;
    private View ring;
    private ImageView photo;
    private TextView name;
    private TextView epithet;
    private TextView spoken;
    private ImageView backdrop;
    private View bookWay;
    private View workshopWay;
    private AnimatorSet breathGlow;
    private AnimatorSet breathRing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        setContentView(build());
        if (state != null && state.getCharSequence(STATE_SPOKEN) != null) {
            spoken.setText(state.getCharSequence(STATE_SPOKEN));
            card = state.getBoolean(STATE_CARD, true);
        } else {
            showCard(Prefs.role(this, Cast.TALK));
        }
        if (state == null && !Prefs.introSeen(this)) {
            // The first start opens on its scene and questions, then the title page; the voice screen waits under them.
            startActivity(new Intent(this, FirstRunActivity.class));
            return;
        }
        askNotify();
    }

    private boolean askedNotify;

    /** Without this the speech notification, and its stop button, stay hidden. Asked once per screen. */
    private void askNotify() {
        if (askedNotify) {
            return;
        }
        askedNotify = true;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(NOTIFY_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {NOTIFY_PERMISSION}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        // After a restore from another phone: what this phone still has to give.
        RestoreActivity.checkIfPending(this);
        // The workshop, or the title page, may have changed the voice.
        showVoice(Prefs.role(this, Cast.TALK));
        if (card) {
            showCard(voice);
        }
        if (Prefs.introSeen(this)) {
            askNotify();
        }
        breathGlow.start();
        breathRing.start();
    }

    @Override
    protected void onPause() {
        breathGlow.cancel();
        breathRing.cancel();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putCharSequence(STATE_SPOKEN, spoken.getText());
        out.putBoolean(STATE_CARD, card);
    }

    private void say(String phrase) {
        spoken.setText(Ui.plain(phrase));
        shown = phrase;
        card = false;
        VoiceService.speak(this, phrase, voice);
    }

    private void askTime() {
        Calendar now = Calendar.getInstance();
        say(TimePhrase.build(SpeechLanguage.resources(this),
                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)));
    }

    private static final int REQUEST_PLACE = 40;
    private static final int REQUEST_LOCATION = 41;
    private static final String LOCATION_PERMISSION = "android.permission.ACCESS_COARSE_LOCATION";

    /** The weather, told by the voice on stage; without a place, the voice asks for one first. */
    private void askWeather() {
        if (!Weather.hasPlace(this)) {
            say(Weather.line(this, "ask." + voice));
            Ui.choose(this, getString(R.string.place_question), getString(R.string.place_by_phone),
                    getString(R.string.place_by_name), getString(R.string.place_later),
                    new Runnable() {
                        @Override
                        public void run() {
                            locate();
                        }
                    },
                    new Runnable() {
                        @Override
                        public void run() {
                            pickPlace();
                        }
                    });
            return;
        }
        spoken.setText(Weather.line(this, "wait"));
        final int teller = voice;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String told = Weather.report(MainActivity.this, teller);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        say(told != null ? told : Weather.line(MainActivity.this, "fail"));
                    }
                });
            }
        }, "weather").start();
    }

    /** Waits this long for the phone to tell where it is, then turns to the search by name. */
    private static final long LOCATE_MS = 8000;
    private boolean locating;

    private void pickPlace() {
        startActivityForResult(new android.content.Intent(this, PlaceActivity.class), REQUEST_PLACE);
    }

    /** Asks the phone once, roughly, where it is; the answer becomes the weather's place. */
    private void locate() {
        if (checkSelfPermission(LOCATION_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {LOCATION_PERMISSION}, REQUEST_LOCATION);
            return;
        }
        android.location.LocationManager lm =
                (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        android.location.Location best = null;
        try {
            for (String provider : lm.getProviders(true)) {
                android.location.Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
            boolean network = lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
            if (best == null && network && Build.VERSION.SDK_INT >= 30) {
                // A fresh answer, but never waited for longer than a few seconds.
                final android.os.CancellationSignal stop = new android.os.CancellationSignal();
                locating = true;
                spoken.setText(Weather.line(this, "wait"));
                lm.getCurrentLocation(android.location.LocationManager.NETWORK_PROVIDER, stop, getMainExecutor(),
                        new java.util.function.Consumer<android.location.Location>() {
                            @Override
                            public void accept(android.location.Location l) {
                                if (locating) {
                                    locating = false;
                                    placed(l);
                                }
                            }
                        });
                spoken.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (locating) {
                            locating = false;
                            stop.cancel();
                            placed(null);
                        }
                    }
                }, LOCATE_MS);
                return;
            }
        } catch (SecurityException | IllegalArgumentException e) {
            best = null;
        }
        placed(best);
    }

    private void placed(final android.location.Location l) {
        if (l == null) {
            // The phone kept silent: the search by name is the way on.
            say(Weather.line(this, "nolocation"));
            pickPlace();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String name = "";
                try {
                    if (android.location.Geocoder.isPresent()) {
                        java.util.List<android.location.Address> a = new android.location.Geocoder(
                                MainActivity.this, SpeechLanguage.locale()).getFromLocation(l.getLatitude(),
                                l.getLongitude(), 1);
                        if (a != null && !a.isEmpty() && a.get(0).getLocality() != null) {
                            name = a.get(0).getLocality();
                        }
                    }
                } catch (Exception ignored) {
                    // Without a name the place is still known by where it lies.
                }
                Weather.setPlace(MainActivity.this, name, l.getLatitude(), l.getLongitude());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        askWeather();
                    }
                });
            }
        }, "place-name").start();
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] names, int[] results) {
        super.onRequestPermissionsResult(request, names, results);
        if (request != REQUEST_LOCATION) {
            return;
        }
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            locate();
        } else {
            placed(null);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_PLACE && result == RESULT_OK) {
            askWeather();
        }
    }

    /**
     * Makes a voice the host: the one on the main screen and in the talk.
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
        if (Cast.renamable(v) && !Prefs.nameAsked(this, v)) {
            Prefs.setNameAsked(this, v);
            askName(v);
        }
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
                if (card) {
                    showCard(voice);
                }
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
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        field.requestFocus();
        d.show();
    }

    private void showCard(int v) {
        card = true;
        spoken.setText(Ui.plain(String.format(Cast.say(this, R.array.voice_step_forward, v), Cast.spokenName(this, v))));
    }

    /** A tap on the line: the card makes the voice speak about itself; an answer is said again. */
    private void tapLine() {
        if (card || shown == null) {
            TapTalk.Reply r = TapTalk.next(this, voice);
            if (r != null) {
                VoiceService.speak(this, r.text, voice, r.flat ? TapTalk.FLAT_PACE : 1f);
            }
        } else {
            VoiceService.speak(this, shown, voice);
        }
    }

    private void showVoice(int v) {
        voice = v;
        Ui.setFace(photo, v);
        photo.setContentDescription(Cast.name(this, v));
        name.setText(Cast.name(this, v));
        // A small pen beside the one name a person may change.
        name.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, Cast.renamable(v) ? R.drawable.ic_edit : 0, 0);
        name.setCompoundDrawablePadding(dp(6));
        name.setClickable(Cast.renamable(v));
        name.setContentDescription(Cast.renamable(v) ? getString(R.string.name_edit) + ": " + Cast.name(this, v) : null);
        epithet.setText(Cast.epithet(this, v));
        glow.setBackground(Ui.oval(Palette.voice(v, 0x29), 0, 0, this));
        ring.setBackground(Ui.oval(Color.TRANSPARENT, Palette.voice(v), 2, this));
        for (int i = 0; i < dots.length; i++) {
            boolean on = Cast.at(i) == v;
            int size = dp(on ? 36 : 26);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(size, size);
            p.leftMargin = dp(7);
            p.rightMargin = dp(7);
            dots[i].setLayoutParams(p);
            dots[i].setPadding(on ? dp(3) : 0, on ? dp(3) : 0, on ? dp(3) : 0, on ? dp(3) : 0);
            dots[i].setBackground(on ? Ui.oval(Palette.BG, Palette.voice(Cast.at(i)), 2, this) : null);
            dots[i].setAlpha(on ? 1f : 0.55f);
        }
        hint.setVisibility(Prefs.troupeHintsLeft(this) > 0 ? View.VISIBLE : View.GONE);
        Kit.backdrop(this, backdrop, v);
        Kit.setEntry(workshopWay, getString(R.string.home_workshop, Cast.name(this, v)),
                getString(R.string.home_workshop_sub));
        String uri = Reading.uri(this);
        Library.Entry e = uri.isEmpty() ? null : Library.get(this, uri);
        String sub = getString(R.string.home_book_none);
        if (e != null) {
            String title = e.title != null && !e.title.isEmpty() ? e.title : Reading.title(this);
            int ch = e.chapterAt(e.index);
            sub = ch < 0 ? title : title + " \u00b7 " + Bot.chapterName(this, e.chapters.get(ch).label);
        }
        Kit.setEntry(bookWay, getString(R.string.nav_book), sub);
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

        LinearLayout stage = Ui.column(this);
        stage.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout face = new FrameLayout(this);
        face.setClipChildren(false);
        glow = new View(this);
        face.addView(glow, new FrameLayout.LayoutParams(dp(208), dp(208), Gravity.CENTER));
        ring = new View(this);
        face.addView(ring, new FrameLayout.LayoutParams(dp(176), dp(176), Gravity.CENTER));
        photo = Ui.portrait(this, 1);
        photo.setClickable(true);
        // A touch opens the voice's room; a sideways swipe brings the next voice forward.
        final android.view.GestureDetector gestures = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(android.view.MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(android.view.MotionEvent e) {
                        WorkshopActivity.openCabinet(MainActivity.this, voice);
                        return true;
                    }

                    @Override
                    public boolean onFling(android.view.MotionEvent a, android.view.MotionEvent b, float vx, float vy) {
                        if (a == null || Math.abs(vx) < Math.abs(vy) || Math.abs(b.getX() - a.getX()) < dp(40)) {
                            return false;
                        }
                        int step = vx < 0 ? 1 : Cast.COUNT - 1;
                        choose(Cast.at((Cast.place(voice) + step) % Cast.COUNT));
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
        stage.addView(epithet, ep);

        // The whole troupe as a row of small faces: the one speaking larger, in its ring.
        LinearLayout troupe = Ui.row(this);
        troupe.setGravity(Gravity.CENTER);
        for (int i = 0; i < dots.length; i++) {
            final int v = Cast.at(i);
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
            dots[i].setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    WorkshopActivity.openCabinet(MainActivity.this, v);
                    return true;
                }
            });
            troupe.addView(dots[i]);
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

        LinearLayout quick = Ui.row(this);
        quick.setGravity(Gravity.CENTER);
        quick.addView(chip(R.string.home_time, new Runnable() {
            @Override
            public void run() {
                askTime();
            }
        }));
        quick.addView(chip(R.string.home_weather, new Runnable() {
            @Override
            public void run() {
                askWeather();
            }
        }));
        LinearLayout.LayoutParams qp = Ui.lp(Ui.MATCH, Ui.WRAP);
        qp.bottomMargin = dp(14);
        root.addView(quick, qp);

        // Three ways on from here: the talk, the book, and this voice's workshop.
        LinearLayout ways = Kit.plate(this);
        ways.setPadding(0, dp(4), 0, dp(4));
        ways.addView(Kit.rowEntry(this, R.drawable.ic_talk, getString(R.string.talk_title),
                getString(R.string.talk_sub), open(TalkActivity.class)), Ui.lp(Ui.MATCH, Ui.WRAP));
        bookWay = Kit.rowEntry(this, R.drawable.ic_book, getString(R.string.nav_book), "", open(BookActivity.class));
        ways.addView(bookWay, Ui.lp(Ui.MATCH, Ui.WRAP));
        workshopWay = Kit.rowEntry(this, R.drawable.ic_sliders, "", getString(R.string.home_workshop_sub),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        LabActivity.open(MainActivity.this, voice);
                    }
                });
        ways.addView(workshopWay, Ui.lp(Ui.MATCH, Ui.WRAP));
        root.addView(ways, Ui.lp(Ui.MATCH, Ui.WRAP));
        LinearLayout.LayoutParams navGap = Ui.lp(Ui.MATCH, Ui.WRAP);
        navGap.topMargin = dp(10);
        LinearLayout nav = Ui.navBar(this, Ui.NAV_VOICE);
        root.addView(nav, navGap);
        Kit.edgeToEdge(this, root, dp(20), dp(20), dp(20), dp(16));
        return whole;
    }

    private TextView chip(int label, final Runnable action) {
        TextView b = Ui.pill(this, getString(label), Palette.SURFACE, Palette.LINE, Palette.INK, false);
        b.setPadding(dp(8), 0, dp(8), 0);
        Ui.oneLine(b, 11, 15);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1f);
        p.leftMargin = dp(4);
        p.rightMargin = dp(4);
        b.setLayoutParams(p);
        return b;
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
