package com.shumtugle.hora;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/**
 * The alarm that opens the audiobook window, the "start now" of its notification, and
 * the phone's start, after which a waiting queue needs its service again. Runs in the
 * worker's process and only tells the service to look again; a short wake lock
 * carries the phone from the alarm to the service's own.
 */
public final class ExportWake extends BroadcastReceiver {
    static final String LOOK = "com.shumtugle.hora.export.LOOK";
    static final String NOW = "com.shumtugle.hora.export.NOW";

    @Override
    public void onReceive(Context c, Intent i) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hora:export-wake");
        w.acquire(15000);
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
            // Right after the phone starts, a service may be started from here.
            ExportService.kick(c);
            return;
        }
        ExportService.wake(c, NOW.equals(i.getAction()));
    }
}
