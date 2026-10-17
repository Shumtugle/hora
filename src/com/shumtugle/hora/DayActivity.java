package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * The Day, the first screen: what today holds, top to bottom. The date, with two buttons that make the talking
 * voice tell the time or the weather; the sky over the chosen place; the book being heard;
 * the last words of the talk. Nothing here speaks by itself: every voice is a touch.
 */
public final class DayActivity extends Activity {
    private static final int REQUEST_PLACE = 40;
    private static final int REQUEST_LOCATION = 41;
    private static final String LOCATION_PERMISSION = "android.permission.ACCESS_COARSE_LOCATION";
    /** Waits this long for the phone to tell where it is, then turns to the search by name. */
    private static final long LOCATE_MS = 8000;
    /** How many coming hours the sky shows. */
    private static final int HOURS = 5;
    private static final String[] SKIES = {"clear", "partly", "overcast", "fog", "drizzle", "rain", "snow", "storm"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int built;
    private int voice = -1;
    private ImageView backdrop;
    private TextView date;
    private LinearLayout weather;
    private LinearLayout book;
    private LinearLayout talk;
    private LinearLayout heard;
    private String drawnHeard = NONE;
    /** The heard ones whose words are shown, by when they were heard; kept while the app runs. */
    private static final java.util.Set<Long> shownHeard = new java.util.HashSet<Long>();
    private LinearLayout news;
    private String drawnNews = NONE;
    private boolean fetchingNews;
    /** The choice of feeds the last asking came back empty for; a failure for another choice is not shown. */
    private String newsFailedFor;
    /** When headlines were last asked for, so the screen asks again on its own every half hour, not every second. */
    private long lastNewsTry;
    private static final long NEWS_FLOOR_MS = 60 * 1000L;
    /** Whether the headlines are unfolded; kept while the app runs, folded again on the next start. */
    private static boolean newsOpen;
    /** What each part was last drawn from, so it is drawn again only when that changed; NONE draws anew. */
    private static final String NONE = "-";
    private String drawnWeather = NONE;
    private String drawnBook = NONE;
    private String drawnTalk = NONE;
    private boolean fetching;
    private boolean locating;
    /** The phone's answer about where it is, while it is awaited; cancelled when the screen goes. */
    private android.os.CancellationSignal locateStop;
    /** The wait for that answer; taken away with the screen, so it never ends a later asking. */
    private Runnable locateTimeout;
    /** Set while the weather is being asked for, so a second touch does not ask, and tell, twice. */
    private boolean asking;
    private TextView weatherChip;
    /** A plate older than this is put away rather than shown as if it were today's. */
    private static final long STALE_MS = 3 * 60 * 60 * 1000L;
    private java.text.DateFormat dayFormat;
    private Locale dayLocale;
    /** The book card's parts that change while it is read; the card is built again only for another book. */
    private String bookUri = NONE;
    private TextView bookPlace;
    private Kit.Bar bookThread;
    private ImageView bookPlay;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            show();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        setContentView(build());
        if (state == null && !Prefs.introSeen(this)) {
            // The first start opens on its scene and questions, then the title page; the Day waits under them.
            startActivity(new Intent(this, FirstRunActivity.class));
            return;
        }
        if (Prefs.introSeen(this)) {
            askNotify();
        }
    }

    private static final String NOTIFY_PERMISSION = "android.permission.POST_NOTIFICATIONS";
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
        if (Prefs.introSeen(this)) {
            askNotify();
        }
        drawnWeather = NONE;
        drawnBook = NONE;
        drawnTalk = NONE;
        drawnHeard = NONE;
        drawnNews = NONE;
        lastNewsTry = 0;
        bookUri = NONE;
        handler.post(tick);
        fetch();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        if (locating) {
            // The answer will not come now; the button wakes for the next time.
            asking(false);
        }
        locating = false;
        if (locateTimeout != null) {
            handler.removeCallbacks(locateTimeout);
            locateTimeout = null;
        }
        if (locateStop != null) {
            locateStop.cancel();
            locateStop = null;
        }
        super.onPause();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
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

        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = Ui.column(this);
        words.addView(Ui.title(this, getString(R.string.nav_day), 34));
        date = Ui.text(this, "", 14, Palette.MUTED);
        words.addView(date);
        head.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        // The settings, where every main screen has them; the faces live on the Voice.
        head.addView(Ui.gear(this), Ui.gearSpot(this));
        root.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(24));
        LinearLayout list = Ui.column(this);
        list.setPadding(0, 0, 0, dp(24));
        scroll.addView(list);

        LinearLayout quick = Ui.row(this);
        quick.addView(chip(R.string.home_time, new Runnable() {
            @Override
            public void run() {
                askTime();
            }
        }));
        weatherChip = chip(R.string.home_weather, new Runnable() {
            @Override
            public void run() {
                askWeather();
            }
        });
        quick.addView(weatherChip);
        LinearLayout.LayoutParams qp = Ui.lp(Ui.MATCH, Ui.WRAP);
        qp.topMargin = dp(16);
        list.addView(quick, qp);

        weather = Kit.plate(this);
        weather.setPadding(dp(20), dp(16), dp(20), dp(16));
        // A touch on the sky: the talking voice says the day in a few sentences.
        weather.setForeground(Ui.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)));
        weather.setClickable(true);
        // The screen reader still reads the sky itself; the touch is named as what it does.
        weather.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, getString(R.string.day_say_action)));
            }
        });
        weather.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sayDay();
            }
        });
        list.addView(weather, gap(14));
        book = Ui.column(this);
        list.addView(book, gap(14));
        news = Ui.column(this);
        list.addView(news, gap(14));
        heard = Ui.column(this);
        list.addView(heard, Ui.lp(Ui.MATCH, Ui.WRAP));
        talk = Ui.column(this);
        list.addView(talk, Ui.lp(Ui.MATCH, Ui.WRAP));

        root.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        root.addView(Ui.navBar(this, Ui.NAV_DAY), Ui.lp(Ui.MATCH, Ui.WRAP));
        Kit.edgeToEdge(this, root, dp(20), dp(20), dp(20), dp(16));
        return whole;
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams p = Ui.lp(Ui.MATCH, Ui.WRAP);
        p.topMargin = dp(top);
        return p;
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

    /** Brings every part in line with what it shows; each is redrawn only when its source changed. */
    private void show() {
        int v = Prefs.role(this, Cast.TALK);
        if (v != voice) {
            voice = v;
            Kit.backdrop(this, backdrop, v);
        }
        Locale here = getResources().getConfiguration().getLocales().get(0);
        if (dayFormat == null || !here.equals(dayLocale)) {
            dayLocale = here;
            dayFormat = new java.text.SimpleDateFormat(
                    android.text.format.DateFormat.getBestDateTimePattern(here, "EEEEdMMMM"), here);
        }
        long now = System.currentTimeMillis();
        String day = dayFormat.format(new java.util.Date(now));
        String line = day + " \u00b7 " + android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(now));
        if (!line.contentEquals(date.getText())) {
            date.setText(line);
        }
        showWeather();
        showBook();
        newsDue();
        showNews();
        showHeard();
        showTalk();
    }

    // ---- The time and the weather, told ----

    private void say(String phrase) {
        if (phrase != null && !phrase.isEmpty()) {
            VoiceService.answer(this, phrase, voice);
        }
    }

    /** Set while the day is being put into words, so a second touch does not say it twice. */
    private boolean summing;

    private void sayDay() {
        if (summing) {
            return;
        }
        summing = true;
        final int teller = voice;
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String said = null;
                try {
                    said = DaySummary.build(app);
                } catch (RuntimeException e) {
                    Diag.mark(app, "day: summary failed, " + e.getClass().getSimpleName());
                } finally {
                    final String told = said;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            summing = false;
                            if (told == null || told.isEmpty() || isFinishing() || isDestroyed()) {
                                return;
                            }
                            try {
                                VoiceService.answerMarked(app, told, teller);
                            } catch (IllegalStateException e) {
                                Diag.mark(app, "day: summary not said, " + e.getClass().getSimpleName());
                            }
                        }
                    });
                }
            }
        }, "day-summary").start();
    }

    private void askTime() {
        say(TimePhrase.dateAndTime(SpeechLanguage.resources(this)));
    }

    /** Marks the weather as being asked for: the button dims and does nothing until the answer is told. */
    private void asking(boolean on) {
        asking = on;
        weatherChip.setAlpha(on ? 0.5f : 1f);
    }

    /** The weather, told by the talking voice; without a place, the voice asks for one first. */
    private void askWeather() {
        if (asking) {
            return;
        }
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
        asking(true);
        final int teller = voice;
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String answer = null;
                try {
                    answer = Weather.report(app, teller);
                } finally {
                    // Whatever happened, the button wakes again.
                    final String told = answer;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            asking(false);
                            say(told != null ? told : Weather.line(app, "fail"));
                            drawnWeather = NONE;
                            if (!isFinishing() && !isDestroyed()) {
                                showWeather();
                            }
                        }
                    });
                }
            }
        }, "weather").start();
    }

    private void pickPlace() {
        startActivityForResult(new Intent(this, PlaceActivity.class), REQUEST_PLACE);
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
                locateStop = stop;
                locating = true;
                asking(true);
                lm.getCurrentLocation(android.location.LocationManager.NETWORK_PROVIDER, stop, getMainExecutor(),
                        new java.util.function.Consumer<android.location.Location>() {
                            @Override
                            public void accept(android.location.Location l) {
                                if (locating && stop == locateStop) {
                                    locating = false;
                                    locateStop = null;
                                    handler.removeCallbacks(locateTimeout);
                                    locateTimeout = null;
                                    asking(false);
                                    placed(l);
                                }
                            }
                        });
                locateTimeout = new Runnable() {
                    @Override
                    public void run() {
                        if (locating && stop == locateStop) {
                            locating = false;
                            locateStop = null;
                            locateTimeout = null;
                            stop.cancel();
                            asking(false);
                            placed(null);
                        }
                    }
                };
                handler.postDelayed(locateTimeout, LOCATE_MS);
                return;
            }
        } catch (SecurityException | IllegalArgumentException e) {
            best = null;
        }
        placed(best);
    }

    private void placed(final android.location.Location l) {
        if (l == null) {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            // The phone kept silent: the search by name is the way on.
            say(Weather.line(this, "nolocation"));
            pickPlace();
            return;
        }
        final android.content.Context app = getApplicationContext();
        asking(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String name = "";
                try {
                    if (android.location.Geocoder.isPresent()) {
                        java.util.List<android.location.Address> a = new android.location.Geocoder(
                                app, SpeechLanguage.locale()).getFromLocation(l.getLatitude(), l.getLongitude(), 1);
                        if (a != null && !a.isEmpty() && a.get(0).getLocality() != null) {
                            name = a.get(0).getLocality();
                        }
                    }
                } catch (Exception ignored) {
                    // Without a name the place is still known by where it lies.
                }
                Weather.setPlace(app, name, l.getLatitude(), l.getLongitude());
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        asking(false);
                        if (!isFinishing() && !isDestroyed()) {
                            askWeather();
                        }
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
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_PLACE && result == RESULT_OK) {
            askWeather();
        }
    }

    // ---- The sky, shown ----

    /** Asks for the sky in the background when a place is known; what is fresh comes from what is kept. */
    private void fetch() {
        if (fetching || !Weather.hasPlace(this)) {
            return;
        }
        fetching = true;
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Weather.now(app);
                } finally {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            fetching = false;
                            drawnWeather = NONE;
                        }
                    });
                }
            }
        }, "day-weather").start();
    }

    private void showWeather() {
        Weather.Kept k = Weather.hasPlace(this) ? Weather.kept(this) : null;
        if (k != null && System.currentTimeMillis() - k.at > STALE_MS) {
            k = null;
        }
        WeatherTalk.Facts f = k == null ? null : k.facts;
        String key = k == null ? "" : k.at + "|" + Weather.placeName(this);
        if (key.equals(drawnWeather)) {
            return;
        }
        drawnWeather = key;
        weather.removeAllViews();
        // Without a place, or before the first answer, the plate stays away; the button above asks for the place.
        weather.setVisibility(f == null ? View.GONE : View.VISIBLE);
        if (f == null) {
            return;
        }
        String place = Weather.placeName(this);
        String at = android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(k.at));
        TextView label = Ui.text(this, (place.isEmpty() ? at : place + " \u00b7 " + at).toUpperCase(Locale.getDefault()),
                11, Palette.ACCENT_TEXT);
        label.setTypeface(Palette.bodyStrong(this));
        label.setLetterSpacing(0.12f);
        weather.addView(label);

        LinearLayout now = Ui.row(this);
        now.setGravity(Gravity.CENTER_VERTICAL);
        TextView temp = Ui.title(this, degrees(f.temp) + "\u00b0", 56);
        now.addView(temp);
        LinearLayout sky = Ui.column(this);
        TextView what = Ui.title(this, skyWord(f.code), 22);
        what.setMaxLines(2);
        sky.addView(what);
        sky.addView(Ui.text(this, getString(R.string.day_feels, degrees(f.feels)), 13, Palette.MUTED));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sp.leftMargin = dp(18);
        now.addView(sky, sp);
        weather.addView(now, Ui.lp(Ui.MATCH, Ui.WRAP));

        int n = Math.min(HOURS, Math.min(f.nextTemp.length, f.nextHour.length));
        if (n > 0) {
            LinearLayout hours = Ui.row(this);
            for (int i = 0; i < n; i++) {
                LinearLayout cell = Ui.column(this);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                TextView t = Ui.text(this, degrees(f.nextTemp[i]), 15, Palette.INK);
                t.setTypeface(Palette.bodyStrong(this));
                cell.addView(t);
                cell.addView(Ui.text(this, hourLabel(f.nextHour[i]), 12, Palette.HINT));
                hours.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            weather.addView(hours, gap(10));
        }
        if (f.tomorrowCode >= 0) {
            TextView next = Ui.text(this, getString(R.string.day_tomorrow, degrees(f.tomorrowHigh),
                    skyWord(f.tomorrowCode)), 13, Palette.MUTED);
            weather.addView(next, gap(8));
        }
    }

    /** "+7", "-3", "0": the sign a reader expects on a weather screen. */
    private static String degrees(int d) {
        return d > 0 ? "+" + d : d < 0 ? "\u2212" + (-d) : "0";
    }

    /** "21" on a 24-hour phone, "9 PM" on a 12-hour one. */
    private String hourLabel(int hour) {
        if (android.text.format.DateFormat.is24HourFormat(this)) {
            return String.valueOf(hour);
        }
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, hour);
        c.set(java.util.Calendar.MINUTE, 0);
        return new java.text.SimpleDateFormat("h a", getResources().getConfiguration().getLocales().get(0))
                .format(c.getTime());
    }

    private String skyWord(int code) {
        String key = WeatherTalk.sky(code);
        String[] words = getResources().getStringArray(R.array.day_sky);
        for (int i = 0; i < SKIES.length && i < words.length; i++) {
            if (SKIES[i].equals(key)) {
                return words[i];
            }
        }
        return "";
    }

    // ---- The book ----

    /**
     * The card is built once for a book; while it is read only its place, thread and button change,
     * so a touch on the button is never lost to a card drawn anew under the finger.
     */
    private void showBook() {
        String uri = Reading.uri(this);
        boolean playing = Reading.playing(this);
        int at = Reading.index(this);
        String key = uri + "|" + at + "|" + playing;
        if (key.equals(drawnBook)) {
            return;
        }
        drawnBook = key;
        Library.Entry e = uri.isEmpty() ? null : Library.get(this, uri);
        if (e == null) {
            book.removeAllViews();
            book.setVisibility(View.GONE);
            bookUri = NONE;
            return;
        }
        if (!uri.equals(bookUri)) {
            bookUri = uri;
            book.removeAllViews();
            book.addView(bookCard(e), Ui.lp(Ui.MATCH, Ui.WRAP));
        }
        book.setVisibility(View.VISIBLE);
        // The shelf's own record of the place, kept per book, as the shelf shows it.
        int total = e.total;
        int index = e.index;
        int pct = e.percent();
        int ch = e.chapterAt(index);
        String where = ch < 0 ? pct + "\u00a0%"
                : Bot.chapterLabel(this, e.chapters.get(ch).label) + " \u00b7 " + pct + "\u00a0%";
        bookPlace.setText(where);
        bookThread.set(total <= 0 ? 0f : Math.min(1f, (index + 1) / (float) total));
        bookPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        bookPlay.setContentDescription(getString(playing ? R.string.shelf_pause : R.string.shelf_continue));
        if (e.index != at) {
            // The player writes the shelf a moment after the place; look again on the next tick.
            drawnBook = NONE;
        }
    }

    private View bookCard(Library.Entry e) {
        LinearLayout card = Ui.row(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.pressable(Ui.round(Palette.SURFACE, 22, this)));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(DayActivity.this, BookActivity.class));
            }
        });
        card.addView(cover(e, 56), Ui.lp(dp(56), Ui.WRAP));

        LinearLayout side = Ui.column(this);
        TextView label = Ui.text(this, getString(R.string.nav_book).toUpperCase(Locale.getDefault()), 11,
                Palette.ACCENT_TEXT);
        label.setTypeface(Palette.bodyStrong(this));
        label.setLetterSpacing(0.12f);
        side.addView(label);
        TextView name = Ui.title(this, titleOf(e), 22);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        side.addView(name, gap(2));
        bookPlace = Ui.text(this, "", 13, Palette.MUTED);
        bookPlace.setSingleLine(true);
        bookPlace.setEllipsize(android.text.TextUtils.TruncateAt.END);
        side.addView(bookPlace, gap(4));
        bookThread = new Kit.Bar(this);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(Ui.MATCH, dp(3));
        tp.topMargin = dp(10);
        side.addView(bookThread, tp);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sp.leftMargin = dp(16);
        sp.rightMargin = dp(12);
        card.addView(side, sp);

        bookPlay = Ui.iconButton(this, R.drawable.ic_play, Palette.ACCENT, 48, getString(R.string.shelf_continue));
        bookPlay.setImageTintList(ColorStateList.valueOf(Palette.ON_ACCENT));
        bookPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startForegroundService(BookPlayer.intent(DayActivity.this, BookPlayer.ACTION_TOGGLE));
            }
        });
        card.addView(bookPlay, Ui.lp(dp(48), dp(48)));
        return card;
    }

    private static String titleOf(Library.Entry e) {
        if (e.title != null && !e.title.isEmpty()) {
            return e.title;
        }
        String n = e.name == null ? "" : e.name;
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** The book's own cover when it carries one, the paper one until it is found and when there is none. */
    private Cover cover(Library.Entry e, int widthDp) {
        final Cover c = new Cover(this);
        c.set(titleOf(e), e.author);
        if (Demo.is(this, e.uri)) {
            c.setPicture(Demo.coverBitmap(this, e.uri, dp(widthDp)));
            return c;
        }
        android.graphics.Bitmap known = Covers.shown(e.uri);
        if (known != null) {
            c.setPicture(known);
            return c;
        }
        Covers.find(this, e.uri, e.name, new Covers.Ready() {
            @Override
            public void ready(final android.graphics.Bitmap picture) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing() && !isDestroyed()) {
                            c.setPicture(picture);
                        }
                    }
                });
            }
        });
        return c;
    }

    // ---- The news ----

    /** Headlines are a developer-room thing: shown only while it is open and they are switched on there. */
    private boolean newsShown() {
        return Beta.open(this) && News.on(this);
    }

    private void fetchNews() {
        fetchNews(false);
    }

    /** Asks for the paper; again, after a touch, also asks at once the feeds that failed a moment ago. */
    private void fetchNews(final boolean again) {
        if (fetchingNews || !newsShown()) {
            return;
        }
        fetchingNews = true;
        lastNewsTry = System.currentTimeMillis();
        final String asked = News.signature(this);
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                News.Paper p = null;
                try {
                    p = News.fetch(app, again);
                } finally {
                    final boolean failed = p == null || p.items.isEmpty();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            fetchingNews = false;
                            newsFailedFor = failed ? asked : null;
                            drawnNews = NONE;
                        }
                    });
                }
            }
        }, "day-news").start();
    }

    /** Asks again when a feed switched on is due. Called every tick; does nothing most of the time. */
    private void newsDue() {
        if (fetchingNews || !newsShown() || News.active(this).isEmpty() || !News.due(this)) {
            return;
        }
        // Which feed is due, and when, the paper decides (half an hour, five minutes after a failure);
        // the Day only never asks more often than once a minute.
        long now = System.currentTimeMillis();
        if (lastNewsTry == 0 || now < lastNewsTry || now - lastNewsTry >= NEWS_FLOOR_MS) {
            fetchNews();
        }
    }

    private void openSources() {
        startActivity(new Intent(this, NewsActivity.class));
    }

    private void showNews() {
        boolean shown = newsShown();
        String sig = shown ? News.signature(this) : "";
        News.Paper p = shown && !sig.isEmpty() ? News.paper(this) : null;
        boolean failed = shown && sig.equals(newsFailedFor);
        final List<News.Item> items = p == null || p.items.isEmpty() ? null : p.items;
        // The same headlines fetched again redraw nothing; the asking shows only while there is nothing to show.
        StringBuilder heads = new StringBuilder();
        if (items != null) {
            for (News.Item it : items) {
                heads.append(it.key()).append('\n');
            }
        }
        java.util.Calendar now = java.util.Calendar.getInstance();
        // Also drawn anew for a name learned meanwhile, and on a new day, when "20:15" turns into a date.
        String key = shown + "|" + sig.hashCode() + "|" + newsOpen + "|" + News.namesVersion() + "|"
                + now.get(java.util.Calendar.DAY_OF_YEAR) + "|"
                + (items == null ? "none|" + fetchingNews + "|" + failed : heads.toString().hashCode() + "|" + p.sources);
        if (key.equals(drawnNews)) {
            return;
        }
        drawnNews = key;
        news.removeAllViews();
        news.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (!shown) {
            return;
        }
        String state;
        if (sig.isEmpty()) {
            state = getString(R.string.day_news_pick);
        } else if (items != null) {
            state = getResources().getQuantityString(R.plurals.day_news_count, items.size(), items.size()) + ", "
                    + getResources().getQuantityString(R.plurals.day_news_sources, p.sources, p.sources);
        } else {
            state = getString(fetchingNews || p == null && !failed ? R.string.day_news_wait : R.string.day_news_none);
        }
        boolean open = newsOpen && items != null;

        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, getString(R.string.day_news), 16, Palette.INK));
        TextView what = Ui.text(this, "\u00b7 " + state, 14, Palette.MUTED);
        what.setSingleLine(true);
        what.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        wp.leftMargin = dp(10);
        head.addView(what, wp);
        head.setContentDescription(getString(R.string.day_news) + ", " + state
                + (items == null && !fetchingNews && !sig.isEmpty() ? ", " + getString(R.string.day_news_retry) : ""));
        if (items != null) {
            // The chevron of the folds in the settings: down while folded, up while open.
            TextView mark = Ui.text(this, "\u203a", 20, Palette.MUTED);
            mark.setRotation(open ? -90f : 90f);
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            head.addView(mark);
            head.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    newsOpen = !newsOpen;
                    drawnNews = NONE;
                    showNews();
                    // The screen reader stays on the paper's title, drawn anew under it, once it is laid out.
                    if (news.getChildCount() > 0) {
                        final View again = news.getChildAt(0) instanceof LinearLayout
                                && ((LinearLayout) news.getChildAt(0)).getChildCount() > 0 && newsOpen
                                ? ((LinearLayout) news.getChildAt(0)).getChildAt(0) : news.getChildAt(0);
                        again.post(new Runnable() {
                            @Override
                            public void run() {
                                again.performAccessibilityAction(
                                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
                            }
                        });
                    }
                }
            });
        } else if (sig.isEmpty()) {
            // Nothing switched on: a touch goes to the sources.
            head.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openSources();
                }
            });
        } else if (!fetchingNews) {
            // No answer: a touch asks again at once.
            head.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    newsFailedFor = null;
                    lastNewsTry = 0;
                    fetchNews(true);
                    drawnNews = NONE;
                    showNews();
                }
            });
        }
        head.setPadding(dp(20), dp(14), dp(16), dp(14));
        head.setMinimumHeight(dp(52));

        if (!open) {
            head.setBackground(Ui.pressable(pill()));
            news.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP));
            return;
        }
        LinearLayout plate = Kit.plate(this);
        head.setBackground(Ui.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)));
        plate.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP));
        java.text.DateFormat clock = android.text.format.DateFormat.getTimeFormat(this);
        java.util.Calendar today = java.util.Calendar.getInstance();
        java.text.DateFormat day = new java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(
                getResources().getConfiguration().getLocales().get(0), "dMMM"), getResources().getConfiguration().getLocales().get(0));
        for (final News.Item it : items) {
            View line = new View(this);
            line.setBackgroundColor(Palette.LINE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Math.max(1, dp(1)));
            lp.leftMargin = dp(20);
            lp.rightMargin = dp(20);
            plate.addView(line, lp);
            LinearLayout row = Ui.row(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), dp(10), dp(12), dp(10));
            row.setBackground(Ui.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)));
            LinearLayout words = Ui.column(this);
            TextView t = Ui.text(this, it.title, 15, Palette.INK);
            t.setMaxLines(3);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setLineSpacing(0, 1.15f);
            words.addView(t);
            java.util.Calendar when = java.util.Calendar.getInstance();
            when.setTimeInMillis(it.at);
            boolean sameDay = when.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)
                    && when.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR);
            String from = News.name(this, it.feed) + (!it.dated ? ""
                    : " \u00b7 " + (sameDay ? clock.format(when.getTime()) : day.format(when.getTime())));
            TextView meta = Ui.text(this, from, 12, Palette.HINT);
            meta.setSingleLine(true);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams mp = Ui.lp(Ui.MATCH, Ui.WRAP);
            mp.topMargin = dp(3);
            words.addView(meta, mp);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tp.rightMargin = dp(12);
            row.addView(words, tp);
            ImageView read = Ui.iconButton(this, R.drawable.ic_play, Palette.RAISED, 36,
                    getString(R.string.day_news_read, it.title));
            read.setImageTintList(ColorStateList.valueOf(Palette.INK));
            // The whole row reads the headline: the screen reader hears it once, as one thing to touch.
            read.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.setContentDescription(getString(R.string.day_news_read, it.title) + ", " + from);
            View.OnClickListener say = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    VoiceService.answerMarked(DayActivity.this, spoken(it), voice);
                }
            };
            read.setOnClickListener(say);
            row.setOnClickListener(say);
            row.addView(read, Ui.lp(dp(36), dp(36)));
            plate.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP));
        }
        LinearLayout links = Ui.row(this);
        links.setGravity(Gravity.CENTER_VERTICAL);
        TextView all = Kit.link(this, getString(R.string.day_news_all), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VoiceService.answerMarked(DayActivity.this, together(items), voice);
            }
        });
        links.addView(all, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        links.addView(Kit.link(this, getString(R.string.day_news_sources_link), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSources();
            }
        }));
        links.setPadding(dp(20), 0, dp(20), dp(4));
        plate.addView(links, Ui.lp(Ui.MATCH, Ui.WRAP));
        news.addView(plate, Ui.lp(Ui.MATCH, Ui.WRAP));
    }

    /** A headline as it is to be said: an English one whole for the English voice, as its feed's language says. */
    private static String spoken(News.Item it) {
        return "en".equals(it.lang) ? Foreign.whole(it.title) : it.title;
    }

    /** The folded headlines: a pill of the screen's own surface with a thin edge. */
    private android.graphics.drawable.GradientDrawable pill() {
        android.graphics.drawable.GradientDrawable g = Ui.round(Palette.SURFACE, 26, this);
        g.setStroke(Math.max(1, dp(1)), Palette.LINE);
        return g;
    }

    /** All the headlines as one text to be read, each ending as a sentence does, each in its own language. */
    private static String together(List<News.Item> items) {
        StringBuilder b = new StringBuilder();
        for (News.Item it : items) {
            if (b.length() > 0) {
                b.append(' ');
            }
            String t = it.title;
            char end = t.isEmpty() ? '.' : t.charAt(t.length() - 1);
            if (end != '.' && end != '!' && end != '?' && end != '\u2026') {
                t = t + ".";
            }
            b.append("en".equals(it.lang) ? Foreign.whole(t) : t);
        }
        return b.toString();
    }

    // ---- What was heard ----

    /**
     * What was read into one's own headphones today: the app and the time; the words only when a row
     * is touched, and hidden again by another touch. Gone at midnight; away when switched off.
     */
    private void showHeard() {
        boolean on = Heard.on(this);
        long day = Heard.startOfToday();
        String key = on + "|" + (on ? Heard.changed(this) : "") + "|" + day;
        if (key.equals(drawnHeard)) {
            return;
        }
        drawnHeard = key;
        heard.removeAllViews();
        List<Heard.Entry> all = on ? Heard.today(this) : new java.util.ArrayList<Heard.Entry>();
        // Yesterday's opened rows are forgotten with yesterday.
        java.util.Iterator<Long> old = shownHeard.iterator();
        while (old.hasNext()) {
            if (old.next() < day) {
                old.remove();
            }
        }
        heard.setVisibility(all.isEmpty() ? View.GONE : View.VISIBLE);
        if (all.isEmpty()) {
            return;
        }
        heard.addView(Kit.section(this, getString(R.string.day_heard, all.size())));
        LinearLayout plate = Kit.plate(this);
        plate.setPadding(0, dp(4), 0, dp(4));
        java.text.DateFormat clock = android.text.format.DateFormat.getTimeFormat(this);
        for (int i = 0; i < all.size(); i++) {
            final Heard.Entry e = all.get(i);
            if (i > 0) {
                plate.addView(Kit.divider(this));
            }
            boolean open = shownHeard.contains(e.at);
            LinearLayout row = Ui.column(this);
            row.setPadding(dp(20), dp(12), dp(20), dp(12));
            row.setBackground(Ui.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)));
            LinearLayout top = Ui.row(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView app = Ui.text(this, e.app, 16, Palette.INK);
            app.setSingleLine(true);
            app.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(app, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            String at = clock.format(new java.util.Date(e.at));
            TextView time = Ui.text(this, at, 13, Palette.HINT);
            LinearLayout.LayoutParams tp = Ui.lp(Ui.WRAP, Ui.WRAP);
            tp.leftMargin = dp(12);
            top.addView(time, tp);
            row.addView(top, Ui.lp(Ui.MATCH, Ui.WRAP));
            final TextView words = Ui.text(this, open ? e.text : getString(R.string.day_heard_show), 13,
                    open ? Palette.INK : Palette.HINT);
            words.setLineSpacing(0, 1.2f);
            LinearLayout.LayoutParams wp = Ui.lp(Ui.MATCH, Ui.WRAP);
            wp.topMargin = dp(4);
            row.addView(words, wp);
            final String when = at;
            row.setContentDescription(e.app + ", " + at + ", " + (open ? e.text : getString(R.string.day_heard_show)));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Changed in place, not drawn anew, so the screen reader stays on the row.
                    boolean nowOpen = !shownHeard.remove(e.at);
                    if (nowOpen) {
                        shownHeard.add(e.at);
                    }
                    words.setText(nowOpen ? e.text : getString(R.string.day_heard_show));
                    words.setTextColor(nowOpen ? Palette.INK : Palette.HINT);
                    v.setContentDescription(e.app + ", " + when + ", "
                            + (nowOpen ? e.text : getString(R.string.day_heard_show)));
                    v.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
                }
            });
            plate.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP));
        }
        heard.addView(plate, Ui.lp(Ui.MATCH, Ui.WRAP));
    }

    // ---- The talk ----

    private void showTalk() {
        String[] last = TalkActivity.lastExchange();
        String key = last == null ? "" : last[0] + "\n" + last[1];
        if (key.equals(drawnTalk)) {
            return;
        }
        drawnTalk = key;
        talk.removeAllViews();
        talk.setVisibility(last == null ? View.GONE : View.VISIBLE);
        if (last == null) {
            return;
        }
        talk.addView(Kit.section(this, getString(R.string.talk_title)));
        LinearLayout plate = Kit.plate(this);
        plate.setPadding(dp(20), dp(14), dp(20), dp(4));
        if (!last[0].isEmpty()) {
            TextView mine = Ui.text(this, last[0], 14, Palette.MUTED);
            mine.setMaxLines(2);
            mine.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mine.setBackground(Ui.round(Palette.RAISED, 16, this));
            mine.setPadding(dp(12), dp(7), dp(12), dp(7));
            LinearLayout.LayoutParams mp = Ui.lp(Ui.WRAP, Ui.WRAP);
            mp.gravity = Gravity.END;
            mp.leftMargin = dp(40);
            plate.addView(mine, mp);
        }
        if (!last[1].isEmpty()) {
            TextView answer = Ui.title(this, Ui.plain(last[1]), 19);
            answer.setTypeface(Palette.display(this));
            answer.setMaxLines(3);
            answer.setEllipsize(android.text.TextUtils.TruncateAt.END);
            answer.setLineSpacing(0, 1.15f);
            plate.addView(answer, gap(last[0].isEmpty() ? 0 : 12));
        }
        plate.addView(Kit.link(this, getString(R.string.day_talk_on), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(DayActivity.this, TalkActivity.class));
            }
        }), Ui.lp(Ui.MATCH, Ui.WRAP));
        talk.addView(plate, Ui.lp(Ui.MATCH, Ui.WRAP));
    }
}
