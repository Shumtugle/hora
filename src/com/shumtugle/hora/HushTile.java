package com.shumtugle.hora;

import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** The quick settings tile: lit while Hora speaks, a touch silences it. */
public final class HushTile extends TileService {
    @Override
    public void onStartListening() {
        paint();
    }

    @Override
    public void onClick() {
        // One touch: silence, and whatever speaks now stops. Another: Hora speaks again.
        boolean on = !Prefs.silenceShared(this);
        Prefs.setSilence(this, on);
        if (on) {
            Hush.all(this);
        }
        Diag.mark(this, "hush: tile, silence " + (on ? "on" : "off"));
        paint();
    }

    private void paint() {
        Tile t = getQsTile();
        if (t == null) {
            return;
        }
        boolean silence = Prefs.silenceShared(this);
        t.setState(silence ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            t.setSubtitle(getString(silence ? R.string.tile_silence_on
                    : Hush.speaking(this) ? R.string.tile_hush_speaking : R.string.tile_silence_off));
        }
        t.updateTile();
    }
}
