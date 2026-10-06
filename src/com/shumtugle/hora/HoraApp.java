package com.shumtugle.hora;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.content.SharedPreferences;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Starts first in every process of the app. Two things go to the journal
 * from here, so a fall can be told from a cleanup: a crash in this process,
 * with its trace, and why earlier processes ended, as the system recorded it
 * (out of memory, a crash, an update...).
 */
public final class HoraApp extends Application {
    private static final String SEEN = "exits_seen";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    Diag.log(HoraApp.this, "crash in " + processName() + ", thread " + t.getName(), e);
                } catch (Throwable ignored) {
                    // The crash goes on to the system whatever happens here.
                }
                if (before != null) {
                    before.uncaughtException(t, e);
                }
            }
        });
        if (Build.VERSION.SDK_INT >= 30) {
            reportExits();
        }
        // The audiobook queue's wake-up is not kept over a restart of the phone: asked for again here.
        if (getPackageName().equals(processName())) {
            Export.schedule(this);
            watchForeground();
        }
        for (String name : new String[] {"settings", "hora", "weather"}) {
            getSharedPreferences(name, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changed);
        }
    }

    /** Any setting changed: the transfer archive is rewritten a minute later. Held here so it is not collected. */
    private final SharedPreferences.OnSharedPreferenceChangeListener changed =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                    if (key == null || !key.startsWith("transfer_")) {
                        Transfer.soon(HoraApp.this);
                    }
                }
            };

    /** How long the model stays awake after the last screen of Hora is gone. */
    private static final long BRAIN_GRACE_MS = 30_000L;
    private int started;
    private final android.os.Handler later = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable sleepBrain = new Runnable() {
        @Override
        public void run() {
            if (started == 0) {
                Brain.sleepInBackground();
            }
        }
    };

    /** Counts visible screens; when none is left for a while, the model goes to sleep. */
    private void watchForeground() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(android.app.Activity a) {
                started++;
                later.removeCallbacks(sleepBrain);
            }

            @Override
            public void onActivityStopped(android.app.Activity a) {
                started = Math.max(0, started - 1);
                if (started == 0) {
                    later.postDelayed(sleepBrain, BRAIN_GRACE_MS);
                }
            }

            @Override
            public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {
            }

            @Override
            public void onActivityResumed(android.app.Activity a) {
            }

            @Override
            public void onActivityPaused(android.app.Activity a) {
            }

            @Override
            public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {
            }

            @Override
            public void onActivityDestroyed(android.app.Activity a) {
            }
        });
    }

    private String processName() {
        return Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : getPackageName();
    }

    /** Writes, once each, the recorded ends of earlier processes newer than the last one written. */
    private void reportExits() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<ApplicationExitInfo> all = am.getHistoricalProcessExitReasons(getPackageName(), 0, 10);
            SharedPreferences sp = getSharedPreferences("diag", MODE_PRIVATE);
            long seen = sp.getLong(SEEN, 0);
            long newest = seen;
            for (int i = all.size() - 1; i >= 0; i--) {
                ApplicationExitInfo x = all.get(i);
                if (x.getTimestamp() <= seen) {
                    continue;
                }
                newest = Math.max(newest, x.getTimestamp());
                String name = x.getProcessName();
                int colon = name.indexOf(':');
                String desc = x.getDescription();
                Diag.mark(this, "ended: " + (colon < 0 ? "main" : name.substring(colon)) + " [" + x.getPid() + "] at "
                        + new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(x.getTimestamp())) + ", "
                        + reason(x.getReason()) + (desc == null || desc.isEmpty() ? "" : " (" + desc + ")")
                        + ", " + x.getPss() / 1024 + " MB");
            }
            sp.edit().putLong(SEEN, newest).apply();
        } catch (RuntimeException e) {
            Diag.log(this, "ended: not readable", e);
        }
    }

    private static String reason(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "exited";
            case ApplicationExitInfo.REASON_SIGNALED: return "signalled";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "low memory";
            case ApplicationExitInfo.REASON_CRASH: return "crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native crash";
            case ApplicationExitInfo.REASON_ANR: return "not responding";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "too much resource use";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "stopped by the user";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "user stopped";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "dependency died";
            case ApplicationExitInfo.REASON_OTHER: return "other";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "permission change";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "start failed";
            default: return "reason " + r;
        }
    }
}
