package dev.tristan.freshwall

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject

/**
 * Settings, favourites (with thumbnails), blocked list and adjustments in one JSON file.
 * What's on screen right now, History and status messages aren't included.
 */
object Backup {
    private const val VERSION = 1
    /** Identifies a backup file. */
    private const val MARKER = "wallscout"
    /** Marker written by versions before 1.2. */
    private const val OLD_MARKER = "freshwall"

    /** Saved state that describes the current wallpapers rather than your settings. */
    private val SKIP = setOf(
        Prefs.KEY_STATUS, Prefs.KEY_REFRESHING, Prefs.KEY_PAUSED_UNTIL, "appliedAdjust", "recent", Preview.KEY_SHARED,
    ) + Preview.entries.flatMap { listOf(it.urlKey, it.idKey, it.timeKey) }

    fun export(context: Context, uri: Uri) {
        val prefs = Prefs(context)
        val values = JSONObject()
        for ((key, value) in prefs.sp.all) {
            if (key in SKIP || value == null) continue
            val type = when (value) {
                is String -> "s"
                is Int -> "i"
                is Long -> "l"
                is Boolean -> "b"
                else -> continue
            }
            values.put(key, JSONObject().put("t", type).put("v", value))
        }
        val thumbs = JSONObject()
        for (fav in prefs.favorites) {
            val file = prefs.favoriteThumb(fav.id)
            if (file.exists()) thumbs.put(fav.id, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
        }
        val json = JSONObject()
            .put(MARKER, VERSION)
            .put("settings", values)
            .put("favoriteThumbs", thumbs)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toString(2).toByteArray()) }
    }

    /** Replaces current settings with the backup's. Throws if the file isn't a backup (older backups use [OLD_MARKER]). */
    fun import(context: Context, uri: Uri) {
        val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val json = JSONObject(text)
        require(json.optInt(MARKER, 0) > 0 || json.optInt(OLD_MARKER, 0) > 0) { "That isn't a Wallscout backup" }
        val prefs = Prefs(context)
        val values = json.getJSONObject("settings")
        val edit = prefs.sp.edit()
        for (key in values.keys()) {
            if (key in SKIP) continue
            val entry = values.getJSONObject(key)
            when (entry.getString("t")) {
                "s" -> edit.putString(key, entry.getString("v"))
                "i" -> edit.putInt(key, entry.getInt("v"))
                "l" -> edit.putLong(key, entry.getLong("v"))
                "b" -> edit.putBoolean(key, entry.getBoolean("v"))
            }
        }
        edit.commit()
        val thumbs = json.optJSONObject("favoriteThumbs") ?: JSONObject()
        for (id in thumbs.keys()) {
            val file = prefs.favoriteThumb(id)
            file.parentFile?.mkdirs()
            file.writeBytes(Base64.decode(thumbs.getString(id), Base64.NO_WRAP))
        }
        Scheduler.apply(context, replace = true)
    }
}
