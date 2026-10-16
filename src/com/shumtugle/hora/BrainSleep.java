package com.shumtugle.hora;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The notification's "unload": the model leaves memory now, in the app's main process where it lives. */
public final class BrainSleep extends BroadcastReceiver {
    @Override
    public void onReceive(final Context c, Intent i) {
        final Context app = c.getApplicationContext();
        Diag.mark(app, "brain: unloaded from the notification");
        // The hold goes at once; stopping the model may wait for a load still in progress.
        BrainHold.let(app);
        // Not tied to the broadcast: a model still loading may hold the stop for a while.
        new Thread(new Runnable() {
            @Override
            public void run() {
                Brain.get(app).stop();
            }
        }, "brain-unload").start();
    }
}
