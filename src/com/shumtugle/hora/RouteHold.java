package com.shumtugle.hora;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Lives in the voice's process while a navigator leads the way, with a quiet notification. Beside
 * a navigator the phone is short of memory, and without this the system ends the voice every few
 * minutes; each time it starts again, a turn may pass in silence. It ends when the navigator's
 * card is gone, when the reader of notifications stops, and in any case after some hours.
 */
public final class RouteHold extends Service {
    private static final String ACTION_HOLD = "com.shumtugle.hora.action.ROUTE_HOLD";
    private static final String ACTION_LET = "com.shumtugle.hora.action.ROUTE_LET";
    private static final String CHANNEL = "route";
    /** Notification numbers belong to the whole app: this one is used by nothing else. */
    private static final int NOTE_ID = 7311;
    /** No way lasts longer than this; a card that never says goodbye does not hold the voice for ever. */
    private static final long LONGEST_MS = 6 * 60 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** The latest start, so that stopping never cuts off a hold that has just arrived. */
    private int lastStart;
    /** Whether this process holds the voice now, as far as it asked. */
    private static volatile boolean held;
    private static volatile long refusedAt;
    private static final long AGAIN_MS = 60_000L;

    /** Voice process. A navigator leads the way: the voice holds on, if the system allows it. */
    static void hold(Context c) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (held || (refusedAt != 0 && now - refusedAt < AGAIN_MS)) {
            return;
        }
        try {
            c.startForegroundService(new Intent(c, RouteHold.class).setAction(ACTION_HOLD));
            held = true;
            Diag.mark(c, "nav: voice held for the route");
        } catch (RuntimeException e) {
            // Refused from the background: the voice stays unguarded, as before; asked again a minute
            // later, not at every change of the card, and the journal says so once.
            refusedAt = now;
            Diag.markOnce(c, "nav-hold-refused", "nav: voice not held, " + e.getClass().getSimpleName());
        }
    }

    /** Voice process. The way is over: the voice lets go. */
    static void let(Context c) {
        if (!held) {
            return;
        }
        held = false;
        try {
            c.startService(new Intent(c, RouteHold.class).setAction(ACTION_LET));
            Diag.mark(c, "nav: voice let go");
        } catch (RuntimeException e) {
            // The service is gone already.
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStart = startId;
        if (intent != null && ACTION_HOLD.equals(intent.getAction())) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } else {
                    startForeground(NOTE_ID, notification());
                }
            } catch (RuntimeException e) {
                // Allowed to start, but not to stand in front: nothing is held, and the journal says so;
                // the system may still end the process for the start it was promised.
                held = false;
                refusedAt = android.os.SystemClock.elapsedRealtime();
                Diag.markOnce(this, "nav-hold-refused", "nav: voice not held, " + e.getClass().getSimpleName());
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            handler.removeCallbacks(tooLong);
            handler.postDelayed(tooLong, LONGEST_MS);
            return START_NOT_STICKY;
        }
        // Stops only if no hold arrived after this letting go: a hold owes the system its notification.
        handler.removeCallbacks(tooLong);
        stopForeground(true);
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tooLong);
        held = false;
        super.onDestroy();
    }

    private final Runnable tooLong = new Runnable() {
        @Override
        public void run() {
            Diag.mark(RouteHold.this, "nav: voice let go after hours");
            held = false;
            stopForeground(true);
            stopSelf(lastStart);
        }
    };

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.route_title), NotificationManager.IMPORTANCE_MIN));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, RouteActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_next)
                .setContentTitle(getString(R.string.route_held_title))
                .setContentText(getString(R.string.route_held_text))
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }
}
