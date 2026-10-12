package com.shumtugle.hora;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/**
 * Reads a book aloud. One thread makes the coming paragraphs into sound
 * ahead of time, the other plays them back to back, so a paragraph starts
 * the moment the last one ends instead of after it has been computed. A
 * jump anywhere discards what was made for the old place. The place is kept
 * in the reading state, for the book screen, the widget and the next start.
 */
public final class BookPlayer extends Service {
    static final String ACTION_OPEN = "open";
    static final String ACTION_TOGGLE = "toggle";
    static final String ACTION_PLAY = "play";
    static final String ACTION_PAUSE = "pause";
    static final String ACTION_NEXT = "next";
    static final String ACTION_PREV = "prev";
    static final String ACTION_SEEK = "seek";
    /** Loads the book and the voice without reading, so a later touch sounds at once. */
    static final String ACTION_WARM = "warm";
    /** Jumps to a paragraph and reads from it, loading the book first if needed. */
    static final String ACTION_FROM = "from";
    /** Pauses the reading after EXTRA_MINUTES minutes; zero minutes takes the timer away. */
    static final String ACTION_SLEEP = "sleep";
    static final String EXTRA_MINUTES = "minutes";
    /** The background under the book was changed: a playing one follows at once. */
    static final String ACTION_BED = "bed";
    /** EXTRA_ON true: the voice falls silent and the background goes on; false: the voice reads again. */
    static final String ACTION_VOICE_QUIET = "voice_quiet";
    /** Who reads, or how a voice sounds, changed: the sound made ahead is dropped and the paragraph read anew. */
    static final String ACTION_REVOICE = "revoice";
    static final String EXTRA_ON = "on";
    static final String EXTRA_URI = "uri";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_INDEX = "index";
    static final String EXTRA_PLAY = "play_now";

    private static final String CHANNEL = "reading";
    private static final int NOTE = 7;
    /** How much sound is made ahead of the reader. */
    private static final float AHEAD_S = 45f;
    /** Frames written to the output at a time, so a pause or a jump is heard at once. */
    private static final int SLICE = 2048;

    private final Object lock = new Object();
    private final ArrayDeque<Chunk> queue = new ArrayDeque<Chunk>();
    private volatile boolean alive = true;
    private volatile boolean playing;
    /** The instance living in this process, for the notification reader next door. */
    private static volatile BookPlayer live;
    /** Paused to let a notification through; resumes when it is over. */
    private boolean heldForNews;
    /** The voice is silent on purpose while the background plays on. */
    private boolean bedOnly;
    /** A paragraph is being made; the end of the book is not reached while it is. */
    private volatile boolean busy;
    private volatile int generation;
    /** Each request to open a book takes a ticket; only the newest one may install its book. */
    private int openTicket;
    /** A book being read from its file right now, or null; reopening the last book waits for it. */
    private String opening;
    /** Play was asked for while a book was still opening. */
    private boolean playWhenOpened;
    private float queuedSeconds;
    private int nextIndex;
    private volatile int playIndex;
    private volatile List<String> paragraphs;
    private String uri = "";
    private String title = "";
    /**
     * The book's own media session: the headphones' button and the phone's media controls
     * reach the book through it. It lives as long as the player; a voice speaking over the
     * book takes the button for itself only while it speaks.
     */
    private volatile MediaSession session;
    /** A book left paused this long gives the headphones' button back to other players. */
    private static final long BUTTON_KEPT_MS = 10 * 60 * 1000L;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable letButtonGo = new Runnable() {
        @Override
        public void run() {
            MediaSession s = session;
            if (s != null && !playing && !bedOnly) {
                try {
                    s.setActive(false);
                    Diag.log(BookPlayer.this, "book: the headphone button is given back");
                } catch (RuntimeException ignored) {
                    // Gone with the service.
                }
            }
        }
    };
    private int rate;
    private Thread maker;
    private Thread player;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private boolean receiverOn;

    private static final class Chunk {
        final int generation;
        final int index;
        final float[] sound;

        Chunk(int generation, int index, float[] sound) {
            this.generation = generation;
            this.index = index;
            this.sound = sound;
        }
    }

    /** Headphones pulled out: stop before the speaker takes over. */
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            pause("headphones gone");
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusChange = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int change) {
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                pause(change == AudioManager.AUDIOFOCUS_LOSS
                        ? "another app took the sound" : "another app took the sound for a while");
            }
        }
    };

    /** Main thread only. Pauses a playing book; true if it did. */
    static boolean holdForNews() {
        BookPlayer p = live;
        if (p == null || !p.playing) {
            return false;
        }
        p.pause("notification");
        p.heldForNews = true;
        return true;
    }

    /** How loud the book stays under a route hint: low enough to hear the hint, never silent. */
    private static final float DUCKED = 0.3f;
    /** The way down or up takes this long, in this many steps, so the book never clicks. */
    private static final int DUCK_STEPS = 10;
    private static final long DUCK_STEP_MS = 30;
    /** Where the book's loudness is heading, and where it is now (1 is full). */
    private static volatile float duckTarget = 1f;
    private volatile float gain = duckTarget;
    /** The track that plays right now, so the loudness can be set from outside the play loop. */
    private volatile AudioTrack out;
    private final Runnable ramp = new Runnable() {
        @Override
        public void run() {
            float target = duckTarget;
            float step = (1f - DUCKED) / DUCK_STEPS;
            float g = gain;
            g = g < target ? Math.min(target, g + step) : Math.max(target, g - step);
            gain = g;
            loudness(out, g);
            if (g != target) {
                main.postDelayed(this, DUCK_STEP_MS);
            }
        }
    };

    /**
     * Any thread. Makes a playing book quieter while a short hint is said over it, or brings
     * it back. The book keeps playing and keeps its place: a hint on the way is not news
     * that stops the story. The system's own ducking is not relied on, as the book and the
     * hint are the same app.
     */
    static void duck(boolean on) {
        duckTarget = on ? DUCKED : 1f;
        BookPlayer p = live;
        if (p != null) {
            p.main.removeCallbacks(p.ramp);
            p.main.post(p.ramp);
        }
    }

    private static void loudness(AudioTrack t, float g) {
        if (t == null) {
            return;
        }
        try {
            t.setVolume(g);
        } catch (RuntimeException ignored) {
            // Released meanwhile; the next track takes the loudness when it opens.
        }
    }

    /** Main thread only. Resumes a book paused by holdForNews(), unless the user acted since. */
    static void releaseAfterNews() {
        BookPlayer p = live;
        if (p != null && p.heldForNews) {
            p.heldForNews = false;
            p.play("notification over");
        }
    }

    static Intent intent(Context c, String action) {
        return new Intent(c, BookPlayer.class).setAction(action);
    }

    /**
     * Tells a reading book that its voices changed. Up to some forty seconds of
     * sound is made ahead with the old voice; without this, the change would be
     * heard only paragraphs later. Nothing happens when no book is playing.
     */
    static void revoice(Context c) {
        if (!Reading.playing(c)) {
            return;
        }
        try {
            c.startService(intent(c, ACTION_REVOICE));
        } catch (RuntimeException e) {
            Diag.log(c, "book: could not tell the player about the new voice", e);
        }
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        live = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.book_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        maker = new Thread(new Runnable() {
            @Override
            public void run() {
                // Ahead of ordinary work, so the system is slower to push it onto the weak cores.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
                makeLoop();
            }
        }, "book-maker");
        player = new Thread(new Runnable() {
            @Override
            public void run() {
                playLoop();
            }
        }, "book-player");
        maker.start();
        player.start();
        try {
            session = new MediaSession(this, "hora-book");
            session.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    heldForNews = false;
                    if (paragraphs == null) {
                        ensureLoaded(true);
                    } else {
                        play("headphone button");
                    }
                }

                @Override
                public void onPause() {
                    heldForNews = false;
                    pause("headphone button");
                }

                @Override
                public void onStop() {
                    heldForNews = false;
                    pause("headphone button");
                }

                @Override
                public void onSkipToNext() {
                    if (paragraphs != null) {
                        seek(playIndex + 1);
                    }
                }

                @Override
                public void onSkipToPrevious() {
                    if (paragraphs != null) {
                        seek(Math.max(0, playIndex - 1));
                    }
                }
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
            // Not active yet: the button is taken only once the book is read, never just by opening it.
        } catch (RuntimeException e) {
            // Without the session the screen, the notification and the tile still work.
            Diag.log(this, "book: no media session", e);
            session = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every start through the foreground path must show itself at once.
        startForeground(NOTE, note());
        // Whatever the user asks for now outranks a pause made for a notification.
        heldForNews = false;
        String action = intent == null ? ACTION_TOGGLE : intent.getAction();
        if (ACTION_OPEN.equals(action)) {
            open(intent.getStringExtra(EXTRA_URI), intent.getStringExtra(EXTRA_NAME),
                    intent.getBooleanExtra(EXTRA_PLAY, false));
        } else if (ACTION_PLAY.equals(action)) {
            ensureLoaded(true);
        } else if (ACTION_PAUSE.equals(action)) {
            pause("pause button");
        } else if (ACTION_NEXT.equals(action)) {
            seek(playIndex + 1);
        } else if (ACTION_PREV.equals(action)) {
            seek(Math.max(0, playIndex - 1));
        } else if (ACTION_BED.equals(action)) {
            followBed();
        } else if (ACTION_VOICE_QUIET.equals(action)) {
            if (intent.getBooleanExtra(EXTRA_ON, false)) {
                voiceQuiet();
            } else {
                ensureLoaded(true);
            }
        } else if (ACTION_SLEEP.equals(action)) {
            sleepIn(intent.getIntExtra(EXTRA_MINUTES, 0));
        } else if (ACTION_WARM.equals(action)) {
            if (paragraphs == null && !Reading.uri(this).isEmpty()) {
                ensureLoaded(false);
            }
            warm();
        } else if (ACTION_REVOICE.equals(action)) {
            if (paragraphs != null) {
                Diag.mark(this, "book: voices changed, paragraph " + playIndex + " read anew");
                seek(playIndex);
            }
        } else if (ACTION_SEEK.equals(action)) {
            int at = intent.getIntExtra(EXTRA_INDEX, playIndex);
            if (paragraphs != null) {
                seek(at);
            } else {
                // Not loaded yet: move the kept place, then load without reading.
                String last = Reading.uri(this);
                if (!last.isEmpty()) {
                    Reading.setSavedIndex(this, last, at);
                }
                ensureLoaded(false);
            }
        } else if (ACTION_FROM.equals(action)) {
            int at = intent.getIntExtra(EXTRA_INDEX, playIndex);
            if (paragraphs != null) {
                seek(at);
                play("from a paragraph");
            } else {
                String last = Reading.uri(this);
                if (!last.isEmpty()) {
                    Reading.setSavedIndex(this, last, at);
                }
                ensureLoaded(true);
            }
        } else {
            if (playing) {
                pause("tap");
            } else {
                ensureLoaded(true);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(letButtonGo);
        MediaSession s = session;
        session = null;
        if (s != null) {
            try {
                s.setActive(false);
                s.release();
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        }
        Bed.stop();
        main.removeCallbacks(ramp);
        if (live == this) {
            live = null;
        }
        alive = false;
        synchronized (lock) {
            generation++;
            lock.notifyAll();
        }
        if (playing) {
            Awake.let(this);
        }
        if (receiverOn) {
            unregisterReceiver(noisy);
        }
        super.onDestroy();
    }

    /** Reopens the last book after the process was gone, then plays if asked. */
    private void ensureLoaded(final boolean play) {
        synchronized (lock) {
            // A book asked for a moment ago is still being read: it is the one to load, not the last
            // one remembered, which the new book has not yet replaced.
            if (opening != null) {
                playWhenOpened |= play;
                return;
            }
        }
        if (paragraphs != null) {
            if (play) {
                play("asked");
            }
            return;
        }
        String last = Reading.uri(this);
        if (last.isEmpty()) {
            stopSelf();
            return;
        }
        open(last, Reading.name(this), play);
    }

    private void open(final String u, final String name, final boolean play) {
        if (u == null || u.isEmpty()) {
            return;
        }
        final int ticket;
        synchronized (lock) {
            generation++;
            queue.clear();
            queuedSeconds = 0;
            playing = false;
            ticket = ++openTicket;
            opening = u;
            playWhenOpened = play;
            lock.notifyAll();
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<String> p;
                Book book = null;
                String failed = null;
                try {
                    InputStream in = getContentResolver().openInputStream(Uri.parse(u));
                    try {
                        android.content.res.Resources res = SpeechLanguage.resources(BookPlayer.this);
                        book = BookText.read(name, in, res.getString(R.string.chapter_words),
                                res.getString(R.string.chapter_ordinals));
                        p = book.paragraphs;
                    } finally {
                        if (in != null) {
                            in.close();
                        }
                    }
                } catch (Exception e) {
                    Diag.log(BookPlayer.this, "book: cannot open", e);
                    p = null;
                    failed = "error";
                }
                if (p != null && p.isEmpty()) {
                    Diag.mark(BookPlayer.this, "book: no text found");
                    failed = "empty";
                }
                int at = failed == null ? Math.min(p.size() - 1, Reading.savedIndex(BookPlayer.this, u)) : 0;
                boolean playNow;
                synchronized (lock) {
                    if (ticket != openTicket) {
                        // Another book was asked for while this one was being read: that one wins.
                        Diag.mark(BookPlayer.this, "book: an older request dropped, " + (p == null ? 0 : p.size())
                                + " paragraphs");
                        return;
                    }
                    opening = null;
                    playNow = playWhenOpened;
                    playWhenOpened = false;
                    if (failed != null) {
                        return;
                    }
                    paragraphs = p;
                    uri = u;
                    title = book.title;
                    nextIndex = at;
                    playIndex = at;
                    generation++;
                    Reading.setBook(BookPlayer.this, u, name);
                }
                // The demo is kept on the shelf like a book, unless it was taken off.
                if (Demo.is(BookPlayer.this, u) && Library.get(BookPlayer.this, u) != null) {
                    // Already on the shelf: only its place moves, it does not jump before other books.
                    Library.demoPlace(BookPlayer.this, u, at);
                } else if (Demo.is(BookPlayer.this, u)) {
                    // A demo opened before the shelf put it up: it stands where a found book does.
                    if (Demo.kept(BookPlayer.this, u)) {
                        Library.found(BookPlayer.this, u, name, book);
                        Library.demoPlace(BookPlayer.this, u, at);
                    }
                } else if (Demo.kept(BookPlayer.this, u)) {
                    Library.opened(BookPlayer.this, u, name, book, at, Prefs.roleShared(BookPlayer.this, Cast.NARRATOR));
                }
                publish();
                Diag.mark(BookPlayer.this, "book: opened, " + p.size() + " paragraphs, "
                        + book.chapters.size() + " chapters, at " + at);
                if (playNow) {
                    play("opened");
                }
            }
        }, "book-open").start();
    }

    private void play() {
        play("asked");
    }

    private void play(String why) {
        if (paragraphs == null || playing) {
            return;
        }
        if (!Voice.ready(this)) {
            // A book is read only by the voices themselves; without their pack there is nothing to read with.
            Diag.mark(this, "book: not read, the voice pack is not fetched yet");
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    android.widget.Toast.makeText(BookPlayer.this, R.string.voice_pack_needed,
                            android.widget.Toast.LENGTH_LONG).show();
                }
            });
            return;
        }
        if (bedOnly) {
            // The background was already playing with focus and the phone awake; the voice joins it.
            bedOnly = false;
            Prefs.setBedOnly(this, false);
            Diag.mark(this, "book: the voice is back, " + why);
            synchronized (lock) {
                playing = true;
                lock.notifyAll();
            }
            publish();
            return;
        }
        Diag.mark(this, "book: playing, " + why + ", " + Diag.state(this));
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes())
                    .setOnAudioFocusChangeListener(focusChange)
                    .build();
        }
        audio.requestAudioFocus(focus);
        if (!receiverOn) {
            registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            receiverOn = true;
        }
        Awake.hold(this);
        synchronized (lock) {
            playing = true;
            lock.notifyAll();
        }
        Bed.start(this);
        publish();
    }

    private void pause(String why) {
        if (!playing && !bedOnly) {
            return;
        }
        Diag.mark(this, "book: paused, " + why);
        bedOnly = false;
        Prefs.setBedOnly(this, false);
        synchronized (lock) {
            playing = false;
            lock.notifyAll();
        }
        Bed.stop();
        Awake.let(this);
        if (focus != null) {
            audio.abandonAudioFocusRequest(focus);
        }
        publish();
    }

    /**
     * The voice falls silent while the background plays on, as when a person
     * wants only the rain for a while. The place in the book stays where it is.
     */
    private void voiceQuiet() {
        if (Prefs.bed(this) == Bed.OFF || bedOnly) {
            return;
        }
        if (playing) {
            synchronized (lock) {
                playing = false;
                lock.notifyAll();
            }
        } else {
            if (focus == null) {
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attributes())
                        .setOnAudioFocusChangeListener(focusChange)
                        .build();
            }
            audio.requestAudioFocus(focus);
            if (!receiverOn) {
                registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
                receiverOn = true;
            }
            Awake.hold(this);
        }
        bedOnly = true;
        Prefs.setBedOnly(this, true);
        Bed.start(this);
        Diag.mark(this, "book: voice quiet, the background goes on");
        publish();
    }

    /** A change of the background made on a screen, heard at once if anything plays. */
    private void followBed() {
        if (!playing && !bedOnly) {
            return;
        }
        if (bedOnly && Prefs.bed(this) == Bed.OFF) {
            pause("background off");
            return;
        }
        Bed.start(this);
        Bed.retune(this);
    }

    private void seek(int index) {
        List<String> p = paragraphs;
        if (p == null) {
            return;
        }
        int to = Math.max(0, Math.min(p.size() - 1, index));
        synchronized (lock) {
            generation++;
            queue.clear();
            queuedSeconds = 0;
            nextIndex = to;
            playIndex = to;
            lock.notifyAll();
        }
        publish();
    }

    private final android.os.Handler timers = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable sleep = new Runnable() {
        @Override
        public void run() {
            pause("sleep timer");
        }
    };

    /** The sleep timer: the reading pauses after the given minutes; zero clears it. */
    private void sleepIn(int minutes) {
        timers.removeCallbacks(sleep);
        if (minutes > 0) {
            timers.postDelayed(sleep, minutes * 60_000L);
            Diag.mark(this, "book: sleep timer, " + minutes + " min");
        } else {
            Diag.mark(this, "book: sleep timer cleared");
        }
    }

    private volatile boolean warming;

    /** Loads the voice and the stress lists away from the player, once; later calls return at once. */
    private void warm() {
        if (warming) {
            return;
        }
        warming = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                long t0 = SystemClock.elapsedRealtime();
                try {
                    Voice.get(BookPlayer.this).prepare();
                    Lexicon.get(BookPlayer.this);
                    Diag.log(BookPlayer.this, String.format(Locale.ROOT, "book: voice warmed in %.1f s",
                            (SystemClock.elapsedRealtime() - t0) / 1000f));
                } catch (Throwable t) {
                    Diag.log(BookPlayer.this, "book: warming failed", t);
                }
            }
        }, "book-warm").start();
    }

    /** Makes the coming paragraphs into sound while the current one plays. */
    private void makeLoop() {
        Voice voice = Voice.get(this);
        while (alive) {
            final int gen;
            int index;
            String text;
            boolean quick;
            synchronized (lock) {
                while (alive && (!playing || paragraphs == null || nextIndex >= paragraphs.size()
                        || queuedSeconds >= AHEAD_S)) {
                    waitLock();
                }
                if (!alive) {
                    return;
                }
                gen = generation;
                index = nextIndex++;
                text = paragraphs.get(index);
                busy = true;
                // Nothing made ahead means the listener is waiting in silence: start with a short phrase.
                quick = queue.isEmpty() && queuedSeconds <= 0.5f;
            }
            try {
                voice.prepare();
                rate = voice.sampleRate();
                final long t0 = SystemClock.elapsedRealtime();
                String spoken = voice.normalize(Lexicon.get(this).applyRules(TextPrep.clean(text)));
                final int at = index;
                final float[] made = new float[1];
                final long[] firstAt = new long[] {-1};
                // Each sentence goes to the player the moment it is made: the first one sounds
                // while the rest of the paragraph is still being made.
                // The demo may be read by a voice of its own in place of the narrator; nothing else is.
                final int reader = Demo.is(this, uri) ? Demo.voice(this) : 0;
                voice.render(spoken, SpeechLanguage.locale(), Prefs.speedShared(this),
                        Prefs.pauseMsShared(this), new Voice.Cancel() {
                            @Override
                            public boolean cancelled() {
                                return !alive || gen != generation;
                            }
                        }, new Voice.Sink() {
                            @Override
                            public void take(float[] piece) {
                                float seconds = piece.length / (float) Math.max(1, rate);
                                made[0] += seconds;
                                if (firstAt[0] < 0) {
                                    firstAt[0] = SystemClock.elapsedRealtime() - t0;
                                }
                                synchronized (lock) {
                                    if (gen == generation) {
                                        queue.add(new Chunk(gen, at, piece));
                                        queuedSeconds += seconds;
                                        lock.notifyAll();
                                    }
                                }
                            }
                        }, quick, reader);
                long spent = SystemClock.elapsedRealtime() - t0;
                Diag.log(this, String.format(Locale.ROOT,
                        "book: paragraph %d, %.1f s of speech in %.1f s (%.2fx), first sound after %.1f s, %.0f s ahead, %s",
                        index, made[0], spent / 1000f, spent > 0 ? made[0] * 1000f / spent : 0f,
                        Math.max(0, firstAt[0]) / 1000f, queuedSeconds, Diag.state(this)));
            } catch (Voice.Missing m) {
                // No voice to read with: stop at this paragraph instead of running through the book in silence.
                synchronized (lock) {
                    if (gen == generation) {
                        nextIndex = index;
                    }
                }
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        pause("no voice pack");
                        android.widget.Toast.makeText(BookPlayer.this, R.string.voice_pack_needed,
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });
                // Give the pause a moment to land before this loop looks for work again.
                SystemClock.sleep(1000);
            } catch (Throwable t) {
                Diag.log(this, "book: paragraph " + index + " failed", t);
            } finally {
                synchronized (lock) {
                    busy = false;
                    lock.notifyAll();
                }
            }
        }
    }

    /** Plays what was made, paragraph after paragraph, and keeps the place. */
    private void playLoop() {
        AudioTrack track = null;
        int trackRate = 0;
        long dryFrom = 0;
        while (alive) {
            Chunk c;
            synchronized (lock) {
                while (alive && (!playing || queue.isEmpty())) {
                    // Waiting while playing is silence the listener hears; waiting while paused is not.
                    if (playing && dryFrom == 0) {
                        dryFrom = SystemClock.elapsedRealtime();
                    } else if (!playing) {
                        dryFrom = 0;
                    }
                    if (track != null && !playing) {
                        track.pause();
                    }
                    if (playing && !busy && queue.isEmpty() && paragraphs != null && nextIndex >= paragraphs.size()) {
                        // The book is over.
                        playing = false;
                        finished();
                    }
                    waitLock();
                }
                if (!alive) {
                    break;
                }
                c = queue.poll();
                queuedSeconds -= c.sound.length / (float) Math.max(1, rate);
                if (dryFrom > 0) {
                    long dry = SystemClock.elapsedRealtime() - dryFrom;
                    if (dry > 500) {
                        Diag.mark(this, String.format(Locale.ROOT, "book: silent %.1f s waiting for paragraph %d",
                                dry / 1000f, c.index));
                    }
                    dryFrom = 0;
                }
                lock.notifyAll();
            }
            if (c.generation != generation) {
                continue;
            }
            if (track == null || trackRate != rate) {
                if (track != null) {
                    out = null;
                    track.release();
                }
                trackRate = rate;
                track = openTrack(trackRate);
                out = track;
                loudness(track, gain);
            }
            playIndex = c.index;
            publish();
            track.play();
            int at = 0;
            while (at < c.sound.length && alive) {
                if (c.generation != generation) {
                    track.pause();
                    track.flush();
                    break;
                }
                if (!playing) {
                    track.pause();
                    synchronized (lock) {
                        while (alive && !playing && c.generation == generation) {
                            waitLock();
                        }
                    }
                    if (c.generation != generation) {
                        track.flush();
                        break;
                    }
                    track.play();
                }
                int n = Math.min(SLICE, c.sound.length - at);
                track.write(c.sound, at, n, AudioTrack.WRITE_BLOCKING);
                at += n;
            }
        }
        out = null;
        if (track != null) {
            track.release();
        }
    }

    private void finished() {
        Diag.mark(this, "book: the end");
        Bed.fadeOut(this);
        Awake.let(this);
        publish();
    }

    private void waitLock() {
        try {
            lock.wait(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private AudioAttributes attributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
    }

    private AudioTrack openTrack(int r) {
        int min = AudioTrack.getMinBufferSize(r, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        return new AudioTrack.Builder()
                .setAudioAttributes(attributes())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(r)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(min, r * 4 / 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    /** Tells the book screen, the widget and the notification where the reading is. */
    private void publish() {
        List<String> p = paragraphs;
        if (p == null) {
            return;
        }
        int i = Math.max(0, Math.min(p.size() - 1, playIndex));
        Reading.set(this, uri, title, i, p.size(), p.get(i), playing);
        if (Demo.kept(this, uri)) {
            Library.progress(this, uri, i, Prefs.roleShared(this, Cast.NARRATOR));
        }
        tellSession();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTE, note());
        if (!playing && !bedOnly) {
            stopForeground(STOP_FOREGROUND_DETACH);
        } else {
            startForeground(NOTE, note());
        }
    }

    /** The session says what the headphones' button would do now, and what is being read. */
    private void tellSession() {
        MediaSession s = session;
        if (s == null) {
            return;
        }
        // Reading: the button is the book's. Paused: it stays so for a while, then goes back.
        main.removeCallbacks(letButtonGo);
        if (playing || bedOnly) {
            try {
                s.setActive(true);
            } catch (RuntimeException ignored) {
                // Gone with the service.
            }
        } else {
            main.postDelayed(letButtonGo, BUTTON_KEPT_MS);
        }
        try {
            s.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(playing || bedOnly ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build());
            s.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title.isEmpty() ? getString(R.string.nav_book) : title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, Cast.name(this, Prefs.roleShared(this, Cast.NARRATOR)))
                    .build());
        } catch (RuntimeException ignored) {
            // A session gone with the process: nothing to tell.
        }
    }

    private Notification note() {
        List<String> p = paragraphs;
        String text = p == null ? getString(R.string.book_opening)
                : getString(R.string.book_where, playIndex + 1, p.size());
        Intent open = new Intent(this, BookActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(title.isEmpty() ? getString(R.string.nav_book) : title)
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(this, 0, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(action(R.drawable.ic_prev, R.string.book_prev, ACTION_PREV, 1))
                .addAction(action(playing ? R.drawable.ic_pause : R.drawable.ic_play_small,
                        playing ? R.string.book_pause : R.string.book_play, ACTION_TOGGLE, 2))
                .addAction(action(R.drawable.ic_next, R.string.book_next, ACTION_NEXT, 3))
                .setLargeIcon(Demo.coverBitmap(this, uri, 200))
                .setStyle(style())
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();
    }

    /** The notification's media look, tied to the book's session while it has one. */
    private Notification.MediaStyle style() {
        Notification.MediaStyle st = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        MediaSession s = session;
        if (s != null) {
            st.setMediaSession(s.getSessionToken());
        }
        return st;
    }

    private Notification.Action action(int icon, int label, String what, int request) {
        PendingIntent pi = PendingIntent.getForegroundService(this, request, intent(this, what),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon),
                getString(label), pi).build();
    }
}
