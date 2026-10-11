package com.shumtugle.hora;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The fallback for when the work service may not start from the background:
 * woken by the system, it works on the audiobook queue for a few minutes on its
 * own thread, within the same window and battery limit, then asks to be woken
 * again if work remains. It stands aside while the service runs. Only one worker
 * runs at a time, the service included; each run has its own stop flag, so a new
 * start can never wake up a run that was told to stop.
 */
public final class ExportJob extends JobService {
    private volatile AtomicBoolean current;

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (ExportService.isRunning()) {
            // The service has the queue; the job is not needed.
            return false;
        }
        if (!Export.BUSY.compareAndSet(false, true)) {
            // A run is still finishing; it asks for the next wake-up itself.
            return false;
        }
        if (Export.gate(this) != Export.GO) {
            Export.BUSY.set(false);
            Export.rescheduleJob(this, Math.max(Export.untilAllowed(this), 15 * 60 * 1000L));
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
                            // Once told to stop, the slice stays stopped.
                            if (!stopped.get() && Export.gate(ExportJob.this) != Export.GO) {
                                stopped.set(true);
                            }
                            return stopped.get();
                        }
                    });
                } catch (Throwable t) {
                    Diag.log(ExportJob.this, "export: slice failed", t);
                    next = Export.LATER_MS;
                } finally {
                    Awake.let(ExportJob.this);
                    Export.BUSY.set(false);
                    jobFinished(params, false);
                    // Asked for even after a stop: a start refused while this run was finishing
                    // would otherwise be lost.
                    if (next != Export.NOTHING_LEFT) {
                        Export.rescheduleJob(ExportJob.this, Math.max(next, Export.untilAllowed(ExportJob.this)));
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
        // The system runs it again when its conditions return.
        return true;
    }
}
