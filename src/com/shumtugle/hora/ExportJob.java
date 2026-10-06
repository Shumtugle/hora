package com.shumtugle.hora;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Woken by the system while the phone charges: works on the audiobook queue
 * for a few minutes on its own thread, then asks to be woken again if work
 * remains. Stops when the system takes the charger condition away. Only one
 * worker runs at a time; each run has its own stop flag, so a new start can
 * never wake up a run that was told to stop.
 */
public final class ExportJob extends JobService {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private volatile AtomicBoolean current;

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (!BUSY.compareAndSet(false, true)) {
            // A run is still finishing; it asks for the next wake-up itself.
            return false;
        }
        final AtomicBoolean stopped = new AtomicBoolean();
        current = stopped;
        new Thread(new Runnable() {
            @Override
            public void run() {
                long next = Export.NOTHING_LEFT;
                try {
                    Awake.hold(ExportJob.this);
                    next = Export.work(ExportJob.this, new Voice.Cancel() {
                        @Override
                        public boolean cancelled() {
                            return stopped.get();
                        }
                    });
                } catch (Throwable t) {
                    Diag.log(ExportJob.this, "export: slice failed", t);
                    next = Export.LATER_MS;
                } finally {
                    Awake.let(ExportJob.this);
                    BUSY.set(false);
                    jobFinished(params, false);
                    // Asked for even after a stop: a start refused while this run was finishing
                    // would otherwise be lost. The charger condition keeps it waiting if need be.
                    if (next != Export.NOTHING_LEFT) {
                        Export.schedule(ExportJob.this, next);
                    }
                }
            }
        }, "export").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        AtomicBoolean s = current;
        if (s != null) {
            s.set(true);
        }
        // The system will run it again when the phone charges once more.
        return true;
    }
}
