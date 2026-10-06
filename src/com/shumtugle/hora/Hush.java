package com.shumtugle.hora;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.view.KeyEvent;

/**
 * Ways to silence Hora's speech at once. While a voice speaks, a media
 * session takes the headphones' button, so one press stops it; a flag shared
 * by the processes lights the quick settings tile; and a broadcast to this
 * app alone tells every speaker to fall silent.
 */
final class Hush {
    /** Sent to this app only: whatever speaks, stops. */
    static final String ACTION = "com.shumtugle.hora.action.HUSH";
    private static final String FILE = "hush";
    private static final String SPEAKING = "speaking";
    private static MediaSession session;

    private Hush() {
    }

    /** A voice starts speaking: the headphones' button now stops it. */
    static synchronized void begin(Context c, final Runnable stop) {
        endSession();
        try {
            session = new MediaSession(c.getApplicationContext(), "hora-speech");
            session.setCallback(new MediaSession.Callback() {
                @Override
                public boolean onMediaButtonEvent(Intent i) {
                    KeyEvent k = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (k != null && k.getAction() == KeyEvent.ACTION_DOWN && stops(k.getKeyCode())) {
                        stop.run();
                        return true;
                    }
                    return super.onMediaButtonEvent(i);
                }
            }, new Handler(Looper.getMainLooper()));
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP
                            | PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build());
            session.setActive(true);
        } catch (RuntimeException e) {
            // Without the button the other ways still work.
            session = null;
        }
        speaking(c, true);
    }

    /** The voice is done, finished or stopped. */
    static synchronized void end(Context c) {
        endSession();
        speaking(c, false);
    }

    private static void endSession() {
        if (session != null) {
            try {
                session.setActive(false);
                session.release();
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            session = null;
        }
    }

    private static boolean stops(int code) {
        return code == KeyEvent.KEYCODE_HEADSETHOOK || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_MEDIA_STOP
                || code == KeyEvent.KEYCODE_MEDIA_PLAY;
    }

    @SuppressWarnings("deprecation")
    private static void speaking(Context c, boolean on) {
        c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).edit().putBoolean(SPEAKING, on).commit();
        try {
            TileService.requestListeningState(c, new ComponentName(c, HushTile.class));
        } catch (RuntimeException ignored) {
            // The tile is not placed; nothing to light.
        }
    }

    @SuppressWarnings("deprecation")
    static boolean speaking(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS).getBoolean(SPEAKING, false);
    }

    /** Silences every voice of Hora, in whichever process it speaks. */
    static void all(Context c) {
        c.sendBroadcast(new Intent(ACTION).setPackage(c.getPackageName()));
        try {
            VoiceService.stop(c);
        } catch (RuntimeException ignored) {
            // A start refused from the background: the broadcast has already reached it.
        }
    }
}
