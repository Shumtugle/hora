package com.shumtugle.hora;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The answer to "are these your headphones?", given in the notification itself. */
public final class EarsAnswer extends BroadcastReceiver {
    static final String EXTRA_KEY = "key";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_NOTE = "note";

    /** Told to the reader in the other process: an answer changed, look again. */
    static final String ACTION_CHANGED = "com.shumtugle.hora.EARS_CHANGED";

    @Override
    public void onReceive(Context c, Intent i) {
        String key = i.getStringExtra(EXTRA_KEY);
        int state = i.getIntExtra(EXTRA_STATE, Ears.UNKNOWN);
        if (key == null || state == Ears.UNKNOWN) {
            return;
        }
        Ears.set(c, key, state);
        Diag.mark(c, "herald: device answered, " + (state == Ears.MINE ? "own headphones" : "speaker or car"));
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(Ears.NOTE_TAG, Ears.noteId(key));
        }
        c.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(c.getPackageName()));
    }
}
