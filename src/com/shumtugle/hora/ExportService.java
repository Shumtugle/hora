package com.shumtugle.hora;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Makes the audiobook queue, at once or on a schedule, with or without the charger.
 *
 * While there is work, the service stays in the foreground with one notification
 * that says what it does or what it waits for. That is what keeps the work going
 * at night: a phone off the charger falls into deep sleep, and work asked of the
 * system in the ordinary way would be let out for a minute now and then. Waiting
 * holds nothing awake; an alarm that may ring in deep sleep brings the start, and
 * the charger being plugged in ends a wait for power. Work itself runs in slices
 * under a wake lock, and between slices the window and the battery are looked at
 * again. The system's job is only a fallback; the two never work at once.
 */
public final class ExportService extends Service {
    private static final String ACTION_LOOK = "look";
    private static final int NOTE_ID = 7302;
    private static final String CHANNEL = "export-work";
    /** How soon to look again when the fallback job is still finishing a slice. */
    private static final long BUSY_RETRY_MS = 60 * 1000L;

    private final Object lock = new Object();
    /** The worker thread, or null; only changed under the lock. */
    private Thread worker;
    /** A look asked for while the worker ran: the worker goes round once more. */
    private boolean again;
    private volatile boolean stopping;
    private volatile int lastStart;
    private BroadcastReceiver power;
    /** The running service, for the wake-up receiver in the same process; null when there is none. */
    private static volatile ExportService running;

    /**
     * Makes sure the queue will be worked on: starts this service, or, where the system does
     * not let a service start from the background, tells a running one by broadcast; with no
     * service running, the system's job takes over.
     */
    static void kick(Context c) {
        if (Export.queue(c).length() == 0) {
            return;
        }
        try {
            c.startForegroundService(new Intent(c, ExportService.class).setAction(ACTION_LOOK));
        } catch (RuntimeException e) {
            tell(c);
        }
    }

    static boolean isRunning() {
        return running != null;
    }

    /** Asks a running service to look again: after a book left the queue or the window changed. */
    static void tell(Context c) {
        c.sendBroadcast(new Intent(c, ExportWake.class).setAction(ExportWake.LOOK));
    }

    /** From the wake-up receiver: the running service looks again, or the system's job takes over. */
    static void wake(Context c, boolean now) {
        ExportService s = running;
        if (now) {
            Export.setNowOnce(c, true);
            Diag.mark(c, "export: started now from the notification");
        }
        if (s != null) {
            s.look();
        } else if (Export.queue(c).length() > 0) {
            Diag.mark(c, "export: no service to wake, the system job instead");
            Export.scheduleJob(c, Export.untilAllowed(c));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        // The service does the work now; a job left waiting would only compete with it.
        ((JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE)).cancel(Export.JOB);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStart = startId;
        try {
            startForeground(NOTE_ID, note(getString(R.string.export_note_looking), false));
        } catch (RuntimeException e) {
            // Refused from the background: the job carries on instead.
            Diag.log(this, "export: the service may not come to the foreground", e);
            Export.scheduleJob(this, Export.untilAllowed(this));
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        look();
        // Not restarted by the system after a crash: the alarm, the job and the next start of
        // the app bring it back, from places where a foreground start is allowed.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        running = null;
        dropPower();
        super.onDestroy();
    }

    /** Looks at the queue again on the worker; a request during a run is answered after it. */
    void look() {
        synchronized (lock) {
            if (stopping) {
                return;
            }
            if (worker != null) {
                again = true;
                return;
            }
            again = false;
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "export-service");
            worker.start();
        }
    }

    private void loop() {
        boolean end = false;
        while (!stopping) {
            synchronized (lock) {
                again = false;
            }
            if (!Export.anyLeft(this)) {
                synchronized (lock) {
                    if (again) {
                        continue;
                    }
                    end = true;
                }
                break;
            }
            int gate = Export.gate(this);
            if (gate == Export.WAIT_TIME) {
                dropPower();
                ring(Export.nextStart(this));
                say(getString(R.string.export_note_waits_time, Export.clock(Export.startMinutes(this))), true);
                Diag.mark(this, "export: waits for " + Export.clock(Export.startMinutes(this)));
                break;
            }
            if (gate == Export.WAIT_POWER) {
                listenPower();
                say(getString(R.string.export_note_waits_power, Export.battery(this)), false);
                Diag.mark(this, "export: battery at " + Export.battery(this) + " %, waits for the charger");
                break;
            }
            dropPower();
            if (!Export.BUSY.compareAndSet(false, true)) {
                // The fallback job is finishing its slice: look again in a minute.
                ring(System.currentTimeMillis() + BUSY_RETRY_MS);
                say(getString(R.string.export_note_later), false);
                break;
            }
            say(getString(R.string.export_note_working), false);
            long next;
            // Once told to stop, a slice stays stopped, even if the reason goes away a moment later.
            final AtomicBoolean told = new AtomicBoolean();
            try {
                Awake.hold(this);
                next = Export.work(this, new Voice.Cancel() {
                    @Override
                    public boolean cancelled() {
                        if (!told.get() && (stopping || Export.gate(ExportService.this) != Export.GO)) {
                            told.set(true);
                        }
                        return told.get();
                    }
                });
            } catch (Throwable t) {
                Diag.log(this, "export: slice failed", t);
                next = Export.LATER_MS;
            } finally {
                Awake.let(this);
                Export.BUSY.set(false);
            }
            if (next > 0) {
                // A book is playing, the voice is missing or a slice failed: look again later.
                ring(System.currentTimeMillis() + next);
                say(getString(Reading.playing(this) ? R.string.export_note_yields : R.string.export_note_later), false);
                break;
            }
        }
        boolean more;
        synchronized (lock) {
            worker = null;
            more = again && !stopping;
        }
        if (more) {
            look();
        } else if (end) {
            Export.setNowOnce(this, false);
            finish();
        }
    }

    /** An alarm that may ring in deep sleep; it only asks the service to look again. */
    private void ring(long at) {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(this, ExportWake.class).setAction(ExportWake.LOOK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    /** Waits for the charger without holding anything awake. */
    private synchronized void listenPower() {
        if (power != null) {
            return;
        }
        power = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                Diag.mark(c, "export: charger in");
                look();
            }
        };
        registerReceiver(power, new IntentFilter(Intent.ACTION_POWER_CONNECTED));
    }

    private synchronized void dropPower() {
        if (power != null) {
            try {
                unregisterReceiver(power);
            } catch (RuntimeException ignored) {
                // Already gone with the service.
            }
            power = null;
        }
    }

    /**
     * Ends the service when the queue is done. A start that came in meanwhile keeps it alive
     * and looks at the queue itself.
     */
    private void finish() {
        dropPower();
        Diag.mark(this, "export: nothing left, the service ends");
        if (!stopSelfResult(lastStart)) {
            // A newer start is on its way; its own look follows.
            return;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void say(String text, boolean canStartNow) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTE_ID, note(text, canStartNow));
    }

    private Notification note(String text, boolean canStartNow) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.export_channel_work),
                    NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, ExportActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_book)
                .setContentTitle(getString(R.string.export_title))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (canStartNow) {
            PendingIntent now = PendingIntent.getBroadcast(this, 1,
                    new Intent(this, ExportWake.class).setAction(ExportWake.NOW), PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, getString(R.string.export_start_now), now).build());
        }
        return b.build();
    }
}
