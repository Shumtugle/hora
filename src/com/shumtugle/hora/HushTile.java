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
        Hush.all(this);
        Diag.mark(this, "hush: tile");
        Tile t = getQsTile();
        if (t != null) {
            t.setState(Tile.STATE_INACTIVE);
            t.updateTile();
        }
    }

    private void paint() {
        Tile t = getQsTile();
        if (t == null) {
            return;
        }
        boolean on = Hush.speaking(this);
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            t.setSubtitle(getString(on ? R.string.tile_hush_speaking : R.string.tile_hush_silent));
        }
        t.updateTile();
    }
}
