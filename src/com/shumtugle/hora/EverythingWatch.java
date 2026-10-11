package com.shumtugle.hora;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * One notification for the whole set while it comes, in place of a notice per
 * file. The system does the downloading; this only looks every two seconds and
 * shows where it stands, then says once that it is over and goes.
 */
public final class EverythingWatch extends Service {
    private static final String CHANNEL = "everything";
    private static final int NOTE = 7301;
    private static final int DONE = 7302;
    private static final long EVERY_MS = 2000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean looking;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            look();
        }
    };

    /** Starts the watch from a screen; harmless when it already runs. */
    static void begin(Context c) {
        try {
            c.startForegroundService(new Intent(c, EverythingWatch.class));
        } catch (RuntimeException e) {
            // Not allowed from the background on this phone: the screens still show the strip.
            Diag.log(c, "everything: watch not started", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        channel();
        startForeground(NOTE, note(Everything.state(this)));
        if (!looking) {
            looking = true;
            main.post(tick);
        }
        return START_NOT_STICKY;
    }

    private void look() {
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Everything.State st = Everything.state(app);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        NotificationManager nm = getSystemService(NotificationManager.class);
                        if (st.running) {
                            nm.notify(NOTE, note(st));
                            main.postDelayed(tick, EVERY_MS);
                            return;
                        }
                        looking = false;
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        nm.notify(DONE, over(st));
                        Diag.mark(app, "everything: over, " + (st.missing == 0 ? "all here" : st.missing + " missing"));
                        stopSelf();
                    }
                });
            }
        }, "everything-look").start();
    }

    private void channel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.all_title),
                    NotificationManager.IMPORTANCE_LOW));
        }
    }

    private PendingIntent open() {
        return PendingIntent.getActivity(this, 0, new Intent(this, LanguagesActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification note(Everything.State st) {
        int pct = st.total <= 0 ? 0 : (int) (100L * st.done / st.total);
        String line;
        if (st.waitingWifi) {
            line = getString(R.string.lang_waiting_wifi);
        } else {
            String now = Everything.nowWords(this, st.now);
            line = Everything.gb(st.done) + " / " + Everything.gb(st.total) + " " + getString(R.string.all_gb)
                    + (st.voiceReady ? " · " + getString(R.string.all_voice_ready) : "")
                    + (now.isEmpty() ? "" : " · " + now);
        }
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(getString(R.string.all_title) + " · " + pct + " %")
                .setContentText(line)
                .setProgress(100, pct, st.total <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open())
                .build();
    }

    private Notification over(Everything.State st) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(getString(st.missing == 0 ? R.string.all_done : R.string.all_partly))
                .setContentText(getString(st.missing == 0 ? R.string.all_done_text : R.string.all_partly_text))
                .setAutoCancel(true)
                .setContentIntent(open())
                .build();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
