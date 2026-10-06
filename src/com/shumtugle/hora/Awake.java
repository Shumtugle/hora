package com.shumtugle.hora;

import android.content.Context;
import android.os.PowerManager;

/**
 * Keeps the processor running while speech is being made. With the screen
 * off the phone sleeps in any gap where no sound is playing, and a gap is
 * exactly when the next paragraph is being computed. One lock for the voice
 * process, counted, with a time limit so a lost release cannot keep the
 * phone awake for good.
 */
final class Awake {
    /** Longest a single hold may last; each new hold starts the time again. */
    private static final long LIMIT_MS = 10 * 60 * 1000L;
    private static final String TAG = "hora:speech";
    private static PowerManager.WakeLock lock;
    private static int holders;

    private Awake() {
    }

    static synchronized void hold(Context c) {
        try {
            if (lock == null) {
                PowerManager pm = (PowerManager) c.getApplicationContext().getSystemService(Context.POWER_SERVICE);
                lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
                lock.setReferenceCounted(false);
            }
            holders++;
            lock.acquire(LIMIT_MS);
        } catch (RuntimeException e) {
            // Without the lock speech still works while the screen is on.
        }
    }

    static synchronized void let(Context c) {
        if (holders > 0) {
            holders--;
        }
        if (holders == 0 && lock != null && lock.isHeld()) {
            try {
                lock.release();
            } catch (RuntimeException e) {
                // Already timed out.
            }
        }
    }
}
