package com.shumtugle.hora;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Lives in the voice's process while the AI model is in memory, with a quiet notification. The
 * model takes several gigabytes; without this, the system frees them by ending the voice first,
 * between two answers, and the next answer waits for the voice to start again. The notification
 * says what holds the memory and gives it back with one touch. The model's own sleep, and leaving
 * Hora's screens, end the hold; so does a model that is gone without a word, found by knocking
 * at its door now and then.
 */
public final class BrainHold extends Service {
    private static final String ACTION_HOLD = "com.shumtugle.hora.action.BRAIN_HOLD";
    private static final String ACTION_LET = "com.shumtugle.hora.action.BRAIN_LET";
    static final String EXTRA_PORT = "port";
    private static final String CHANNEL = "brain";
    /** Notification numbers belong to the whole app: this one is used by nothing else. */
    private static final int NOTE_ID = 7310;
    private static final long KNOCK_MS = 30_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int port;
    private int missed;
    /** Set once the service is gone: a knock still on its way is then dropped. */
    private boolean gone;
    /** The latest start, so that stopping never cuts off a hold that has just arrived. */
    private int lastStart;

    /** Any process. The model is up on this local port: the voice holds on. */
    static void hold(Context c, int port) {
        try {
            c.startForegroundService(new Intent(c, BrainHold.class).setAction(ACTION_HOLD).putExtra(EXTRA_PORT, port));
        } catch (RuntimeException e) {
            // A start the system refuses (Hora not on screen): the voice simply stays unguarded.
            Diag.log(c, "brain: hold refused", e);
        }
    }

    /** Any process. The model is gone: the voice lets go. */
    static void let(Context c) {
        try {
            c.startService(new Intent(c, BrainHold.class).setAction(ACTION_LET));
        } catch (RuntimeException e) {
            // Nothing is held, so there is nothing to let go of.
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
            port = intent.getIntExtra(EXTRA_PORT, 0);
            missed = 0;
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTE_ID, notification());
            }
            handler.removeCallbacks(knock);
            handler.postDelayed(knock, KNOCK_MS);
            return START_NOT_STICKY;
        }
        // Stops only if no hold arrived after this letting go: a hold owes the system its notification.
        handler.removeCallbacks(knock);
        stopForeground(true);
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        gone = true;
        handler.removeCallbacks(knock);
        super.onDestroy();
    }

    private void finish() {
        handler.removeCallbacks(knock);
        stopForeground(true);
        stopSelf(lastStart);
    }

    /** Checks off the main thread that the model still answers at its door; twice silent, it lets go. */
    private final Runnable knock = new Runnable() {
        @Override
        public void run() {
            final int p = port;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean open = false;
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress("127.0.0.1", p), 2000);
                        open = true;
                    } catch (java.io.IOException gone) {
                        open = false;
                    }
                    final boolean up = open;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (gone) {
                                return;
                            }
                            missed = up ? 0 : missed + 1;
                            if (missed >= 2) {
                                Diag.mark(BrainHold.this, "brain: hold ended, the model is gone");
                                finish();
                            } else {
                                handler.removeCallbacks(knock);
                                handler.postDelayed(knock, KNOCK_MS);
                            }
                        }
                    });
                }
            }, "brain-knock").start();
        }
    };

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.brain_title), NotificationManager.IMPORTANCE_MIN));
        }
        PendingIntent unload = PendingIntent.getBroadcast(this, 0,
                new Intent(this, BrainSleep.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, TalkActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_talk)
                .setContentTitle(getString(R.string.brain_held_title))
                .setContentText(getString(R.string.brain_held_text))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_talk),
                        getString(R.string.brain_sleep_now), unload).build())
                .build();
    }
}
