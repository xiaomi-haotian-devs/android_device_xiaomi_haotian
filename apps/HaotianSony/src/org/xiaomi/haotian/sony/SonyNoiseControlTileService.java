// SPDX-License-Identifier: Apache-2.0
package org.xiaomi.haotian.sony;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class SonyNoiseControlTileService extends TileService {
    private SonyControlClient client;
    private SonyState state = new SonyState("{}");
    @Override public void onStartListening() {
        super.onStartListening();
        if (client == null) client = new SonyControlClient(this, value -> { state = value; update(); });
        client.start(); update();
    }
    @Override public void onStopListening() { if (client != null) client.stop(); super.onStopListening(); }
    @Override public void onDestroy() { if (client != null) client.stop(); super.onDestroy(); }
    @Override public void onClick() {
        super.onClick();
        if (!state.canEdit(state.address) || !state.has(8) || client == null) return;
        int mode = state.value("noise.mode", -1);
        if (mode < 0) return;
        int next = mode == 0 ? 1 : mode == 1 && state.has(9) ? 2 : 0;
        client.set(state.address, "noise.mode", next, "");
        Tile tile = getQsTile();
        if (tile != null) { tile.setState(Tile.STATE_UNAVAILABLE); tile.updateTile(); }
    }
    private void update() {
        Tile tile = getQsTile(); if (tile == null) return;
        int mode = state.value("noise.mode", -1);
        boolean ready = state.canEdit(state.address) && state.has(8) && mode >= 0;
        tile.setState(ready ? mode == 0 ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE : Tile.STATE_UNAVAILABLE);
        String[] labels = getResources().getStringArray(R.array.noise_modes);
        tile.setLabel(mode >= 0 && mode < labels.length ? labels[mode] : getString(R.string.noise_tile));
        tile.setSubtitle(state.name.isEmpty() ? getString(R.string.disconnected) : state.name);
        tile.updateTile();
    }
}
