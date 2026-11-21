package dev.tristan.freshwall

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: a new wallpaper without opening the app. */
class RefreshTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "New wallpaper"
            updateTile()
        }
    }

    override fun onClick() {
        Prefs(this).status = Prefs.WORKING
        Scheduler.runNow(this)
        qsTile?.apply {
            subtitle = "Changing…"
            updateTile()
        }
    }
}
