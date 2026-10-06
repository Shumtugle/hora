package com.shumtugle.hora;

import android.animation.AnimatorSet;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The title page. The main voice introduces the app in a few lines of text
 * and stays silent until asked to tell it aloud. Under it, the ways in,
 * notifications first. Shown once on the first start; afterwards it is
 * reached from the sign in the header or from the settings, and the host is
 * always the current main voice.
 */
public final class IntroActivity extends Activity {
    private int built;
    private boolean first;
    private int voice;
    private boolean speaking;

    private ImageView backdrop;
    private View glow;
    private View ring;
    private ImageView photo;
    private TextView name;
    private TextView lead;
    private TextView listen;
    private final ImageView[] dots = new ImageView[Cast.COUNT];
    private View heraldWay;
    private View bookWay;
    private View workshopWay;
    private View folderWay;
    private AnimatorSet breathGlow;
    private AnimatorSet breathRing;

    private final BroadcastReceiver idle = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            showSpeaking(false);
        }
    };

    /** Opens the title page from anywhere; it is never the first-start page then. */
    static void open(Activity a) {
        a.startActivity(new Intent(a, IntroActivity.class));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        first = !Prefs.introSeen(this);
        // Seen once is enough: whichever way it is left, the app opens on the voice from now on.
        Prefs.setIntroSeen(this);
        setContentView(build());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        // The main voice may have changed elsewhere; access may have been given meanwhile.
        showVoice(Prefs.role(this, Cast.TALK));
        breathGlow.start();
        breathRing.start();
        IntentFilter f = new IntentFilter(VoiceService.ACTION_IDLE);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(idle, f, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(idle, f);
        }
    }

    @Override
    protected void onPause() {
        breathGlow.cancel();
        breathRing.cancel();
        unregisterReceiver(idle);
        if (speaking) {
            VoiceService.stop(this);
            showSpeaking(false);
        }
        super.onPause();
    }

    private void showSpeaking(boolean on) {
        speaking = on;
        listen.setText(on ? R.string.ws_stop : R.string.ws_listen);
        listen.setCompoundDrawablesRelativeWithIntrinsicBounds(
                on ? R.drawable.ic_stop_small : R.drawable.ic_play_small, 0, 0, 0);
    }

    private void tell() {
        if (speaking) {
            VoiceService.stop(this);
            showSpeaking(false);
            return;
        }
        String said = SpeechLanguage.resources(this).getString(R.string.intro_speech, Cast.name(this, voice));
        VoiceService.speak(this, said, voice);
        showSpeaking(true);
    }

    /** Makes a voice the host, as on the main screen: it greets here and answers in the talk. */
    private void choose(int v) {
        if (speaking) {
            VoiceService.stop(this);
            showSpeaking(false);
        }
        Prefs.setRole(this, Cast.TALK, v);
        Prefs.setHomeVoice(this, v);
        Prefs.troupeHintUsed(this);
        Widgets.refresh(this);
        showVoice(v);
    }

    private void showVoice(int v) {
        voice = v;
        Ui.setFace(photo, v);
        photo.setContentDescription(Cast.name(this, v));
        name.setText(Cast.name(this, v));
        lead.setText(getString(R.string.intro_lead, Cast.name(this, v)));
        glow.setBackground(Ui.oval(Palette.voice(v, 0x29), 0, 0, this));
        ring.setBackground(Ui.oval(Color.TRANSPARENT, Palette.voice(v), 2, this));
        for (int i = 0; i < dots.length; i++) {
            boolean on = i + 1 == v;
            int size = dp(on ? 36 : 26);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(size, size);
            p.leftMargin = dp(7);
            p.rightMargin = dp(7);
            dots[i].setLayoutParams(p);
            int pad = on ? dp(3) : 0;
            dots[i].setPadding(pad, pad, pad, pad);
            dots[i].setBackground(on ? Ui.oval(Palette.BG, Palette.voice(i + 1), 2, this) : null);
            dots[i].setAlpha(on ? 1f : 0.55f);
        }
        Kit.backdrop(this, backdrop, v);
        Kit.setEntry(heraldWay, getString(R.string.intro_herald),
                getString(Herald.granted(this) ? R.string.intro_herald_on : R.string.intro_herald_off));
        String uri = Reading.uri(this);
        Library.Entry e = uri.isEmpty() ? null : Library.get(this, uri);
        String sub = getString(R.string.intro_book_none);
        if (e != null) {
            String title = e.title != null && !e.title.isEmpty() ? e.title : Reading.title(this);
            int ch = e.chapterAt(e.index);
            sub = ch < 0 ? title : title + " \u00b7 " + Bot.chapterName(this, e.chapters.get(ch).label);
        }
        Kit.setEntry(bookWay, getString(R.string.intro_book), sub);
        Kit.setEntry(workshopWay, getString(R.string.home_workshop, Cast.name(this, v)),
                getString(R.string.home_workshop_sub));
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
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        whole.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout root = Ui.column(this);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root, new FrameLayout.LayoutParams(-1, -2));

        // On the first start there is nowhere to go back to; later the page is a room like any other.
        if (!first) {
            root.addView(Ui.header(this, null, false), Ui.lp(Ui.MATCH, dp(48)));
        }

        FrameLayout face = new FrameLayout(this);
        face.setClipChildren(false);
        glow = new View(this);
        face.addView(glow, new FrameLayout.LayoutParams(dp(188), dp(188), Gravity.CENTER));
        ring = new View(this);
        face.addView(ring, new FrameLayout.LayoutParams(dp(160), dp(160), Gravity.CENTER));
        photo = Ui.portrait(this, 1);
        face.addView(photo, new FrameLayout.LayoutParams(dp(134), dp(134), Gravity.CENTER));
        LinearLayout.LayoutParams fp = Ui.lp(dp(208), dp(208));
        fp.topMargin = dp(first ? 24 : 4);
        root.addView(face, fp);
        breathGlow = Ui.breathe(glow, 0.92f, 1.02f, 0.5f, 1f);
        breathRing = Ui.breathe(ring, 0.94f, 1.04f, 0.55f, 1f);

        name = Ui.title(this, "", 34);
        LinearLayout.LayoutParams np = Ui.lp(Ui.WRAP, Ui.WRAP);
        np.topMargin = dp(10);
        root.addView(name, np);

        lead = Ui.title(this, "", 19);
        lead.setTypeface(Palette.display(this));
        lead.setGravity(Gravity.CENTER);
        lead.setLineSpacing(0, 1.15f);
        lead.setMaxWidth(dp(320));
        LinearLayout.LayoutParams lp = Ui.lp(Ui.WRAP, Ui.WRAP);
        lp.topMargin = dp(10);
        root.addView(lead, lp);

        // Silent by default: the voice speaks only when asked.
        listen = Ui.pill(this, getString(R.string.ws_listen), Palette.ACCENT, 0, Palette.ON_ACCENT, true);
        listen.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Palette.ON_ACCENT));
        listen.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_play_small, 0, 0, 0);
        listen.setCompoundDrawablePadding(dp(8));
        listen.setPadding(dp(22), 0, dp(22), 0);
        listen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tell();
            }
        });
        LinearLayout.LayoutParams bp = Ui.lp(Ui.WRAP, dp(48));
        bp.topMargin = dp(18);
        root.addView(listen, bp);

        LinearLayout troupe = Ui.row(this);
        troupe.setGravity(Gravity.CENTER);
        for (int i = 0; i < dots.length; i++) {
            final int v = i + 1;
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
            troupe.addView(dots[i]);
        }
        LinearLayout.LayoutParams tp = Ui.lp(Ui.WRAP, Ui.WRAP);
        tp.topMargin = dp(24);
        root.addView(troupe, tp);
        TextView hint = Ui.text(this, getString(R.string.intro_troupe), 12, Palette.MUTED);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = Ui.lp(Ui.WRAP, Ui.WRAP);
        hp.topMargin = dp(8);
        root.addView(hint, hp);

        // The ways in, in the order the app is used: notifications, books, talk, the voice itself.
        LinearLayout ways = Kit.plate(this);
        // Until Hora knows its folder, the first way in is to show it: books, settings and voices live there.
        if (HoraFolder.tree(this) == null) {
            folderWay = Kit.rowEntry(this, R.drawable.ic_folder, getString(R.string.intro_folder),
                    getString(R.string.intro_folder_sub), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            HoraFolder.ask(IntroActivity.this);
                        }
                    });
            ways.addView(folderWay, Ui.lp(Ui.MATCH, Ui.WRAP));
        }
        ways.setPadding(0, dp(4), 0, dp(4));
        heraldWay = Kit.rowEntry(this, R.drawable.ic_bell, "", "", open(HeraldActivity.class));
        ways.addView(heraldWay, Ui.lp(Ui.MATCH, Ui.WRAP));
        bookWay = Kit.rowEntry(this, R.drawable.ic_book, "", "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // With no book yet, the shelf is where one is added.
                startActivity(new Intent(IntroActivity.this,
                        Reading.uri(IntroActivity.this).isEmpty() ? ShelfActivity.class : BookActivity.class));
            }
        });
        ways.addView(bookWay, Ui.lp(Ui.MATCH, Ui.WRAP));
        ways.addView(Kit.rowEntry(this, R.drawable.ic_talk, getString(R.string.talk_title),
                getString(R.string.intro_talk_sub), open(TalkActivity.class)), Ui.lp(Ui.MATCH, Ui.WRAP));
        workshopWay = Kit.rowEntry(this, R.drawable.ic_sliders, "", "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LabActivity.open(IntroActivity.this, voice);
            }
        });
        ways.addView(workshopWay, Ui.lp(Ui.MATCH, Ui.WRAP));
        LinearLayout.LayoutParams wp = Ui.lp(Ui.MATCH, Ui.WRAP);
        wp.topMargin = dp(28);
        root.addView(ways, wp);

        if (first) {
            View start = Kit.primary(this, getString(R.string.intro_start), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
            LinearLayout.LayoutParams sp = Ui.lp(Ui.MATCH, Ui.WRAP);
            sp.topMargin = dp(20);
            root.addView(start, sp);
        }
        Kit.edgeToEdge(this, root, dp(20), dp(20), dp(20), dp(24));
        return whole;
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != HoraFolder.PICK || result != RESULT_OK || !HoraFolder.accept(this, data)) {
            return;
        }
        if (folderWay != null) {
            folderWay.setVisibility(View.GONE);
        }
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Books.moveOut(app);
            }
        }, "intro-folder").start();
        // A phone that had Hora before: offer to bring it all back.
        RestoreActivity.offerIfFresh(this);
    }

    private View.OnClickListener open(final Class<?> screen) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(IntroActivity.this, screen));
            }
        };
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
