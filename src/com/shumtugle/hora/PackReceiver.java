package com.shumtugle.hora;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Moves a fetch on whenever the system says one of its downloads is over,
 * even with the app closed: the next part starts, or the finished file is put
 * in place.
 */
public final class PackReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context c, Intent i) {
        long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        final String key = id < 0 ? null : Fetch.keyOf(c, id);
        if (key == null) {
            return;
        }
        final PendingResult done = goAsync();
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (key.startsWith("pack:")) {
                        LanguagePack.finish(app, key.substring("pack:".length()));
                    } else if (ModelDownload.slotOf(key) >= 0) {
                        ModelDownload.finish(app, ModelDownload.slotOf(key));
                    }
                } finally {
                    done.finish();
                }
            }
        }, "fetch-step").start();
    }
}
