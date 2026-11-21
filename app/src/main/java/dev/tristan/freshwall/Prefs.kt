package dev.tristan.freshwall

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Sort(val label: String, val sorting: String, val topRange: String?) {
    FAVORITES("Most favorited (all time)", "favorites", null),
    TOP_YEAR("Top this year", "toplist", "1y"),
    TOP_MONTH("Top this month", "toplist", "1M"),
    RANDOM("Random", "random", null),
}

enum class Target(val label: String) {
    BOTH_DIFFERENT("Home + lock screen (different images)"),
    BOTH_SAME("Home + lock screen (same image)"),
    HOME("Home screen only"),
    LOCK("Lock screen only"),
}

/** Small copies of the current wallpapers, shown in the app. */
enum class Preview(val scope: String, val urlKey: String, val idKey: String, val timeKey: String) {
    HOME("home", "homeUrl", "homeId", "homeTime"),
    LOCK("lock", "lockUrl", "lockId", "lockTime");

    /** Small unadjusted copy shown in the app. */
    fun file(context: Context) = File(context.filesDir, "preview_$scope.jpg")

    /** Full-size unadjusted copy, so adjustments can be re-applied without downloading again. */
    fun originalFile(context: Context) = File(context.filesDir, "original_$scope.jpg")

    companion object {
        /** True when the home preview is also the lock screen (same image on both). */
        const val KEY_SHARED = "previewShared"
    }
}

/** A wallpaper that was set recently, with a small thumbnail kept on disk. */
data class RecentEntry(val id: String, val pageUrl: String, val imageUrl: String, val time: Long) {
    fun thumbFile(context: Context) = File(File(context.filesDir, "history"), "$id.jpg")
}

class Prefs(private val context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var searchRaw: String
        get() = sp.getString("search", "space, nebula, galaxy, milky way")!!
        set(v) = sp.edit().putString("search", v).apply()

    val searchTerms: List<String>
        get() = searchRaw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun setSearchTerms(terms: List<String>) {
        searchRaw = terms.joinToString(", ")
    }

    /** The first-launch welcome screen has been shown (and done or skipped). */
    var welcomeDone: Boolean
        get() = sp.getBoolean("welcomeDone", false)
        set(v) = sp.edit().putBoolean("welcomeDone", v).apply()

    /** Words/tags to leave out of every search, comma separated. */
    var excludeRaw: String
        get() = sp.getString("exclude", "")!!
        set(v) = sp.edit().putString("exclude", v).apply()

    val excludeTerms: List<String>
        get() = excludeRaw.split(',', '\n').map { it.trim().removePrefix("-") }.filter { it.isNotEmpty() }.distinct()

    fun setExcludeTerms(terms: List<String>) {
        excludeRaw = terms.joinToString(", ")
    }

    var photosOnly: Boolean
        get() = sp.getBoolean("photosOnly", true)
        set(v) = sp.edit().putBoolean("photosOnly", v).apply()

    var sort: Sort
        get() = Sort.entries.getOrElse(sp.getInt("wallhavenSort", 0)) { Sort.FAVORITES }
        set(v) = sp.edit().putInt("wallhavenSort", v.ordinal).apply()

    var target: Target
        get() = Target.entries.getOrElse(sp.getInt("target", 0)) { Target.BOTH_DIFFERENT }
        set(v) = sp.edit().putInt("target", v.ordinal).apply()

    var hour: Int
        get() = sp.getInt("hour", 5)
        set(v) = sp.edit().putInt("hour", v).apply()

    /** Hours between automatic changes: one of [INTERVALS]. */
    var intervalHours: Int
        get() = sp.getInt("intervalHours", 24).takeIf { it in INTERVALS } ?: 24
        set(v) = sp.edit().putInt("intervalHours", v).apply()

    var minute: Int
        get() = sp.getInt("minute", 0)
        set(v) = sp.edit().putInt("minute", v).apply()

    var preferPortrait: Boolean
        get() = sp.getBoolean("portrait", true)
        set(v) = sp.edit().putBoolean("portrait", v).apply()

    /** Only use images at least as large as the screen (off: anything 1080p or bigger). */
    var sharpOnly: Boolean
        get() = sp.getBoolean("sharpOnly", false)
        set(v) = sp.edit().putBoolean("sharpOnly", v).apply()

    var parallax: Boolean
        get() = sp.getBoolean("parallax", false)
        set(v) = sp.edit().putBoolean("parallax", v).apply()

    var separateAdjust: Boolean
        get() = sp.getBoolean("separateAdjust", false)
        set(v) = sp.edit().putBoolean("separateAdjust", v).apply()

    /** [scope] is "both", "home" or "lock". */
    fun adjust(scope: String) = Adjust(
        brightness = sp.getInt("adj_${scope}_brightness", 100),
        saturation = sp.getInt("adj_${scope}_saturation", 100),
        blur = sp.getInt("adj_${scope}_blur", 0),
    )

    fun setAdjust(scope: String, a: Adjust) = sp.edit()
        .putInt("adj_${scope}_brightness", a.brightness)
        .putInt("adj_${scope}_saturation", a.saturation)
        .putInt("adj_${scope}_blur", a.blur)
        .apply()

    /** The adjustments that apply to a screen, taking the "separate" switch into account. */
    fun adjustFor(preview: Preview) = adjust(if (separateAdjust) preview.scope else "both")

    /** What was last applied to the actual wallpaper, to skip needless re-applies. */
    var appliedAdjust: String
        get() = sp.getString("appliedAdjust", "")!!
        set(v) = sp.edit().putString("appliedAdjust", v).apply()

    fun adjustSignature() = "${adjustFor(Preview.HOME)}|${adjustFor(Preview.LOCK)}"

    var wifiOnly: Boolean
        get() = sp.getBoolean("wifiOnly", false)
        set(v) = sp.edit().putBoolean("wifiOnly", v).apply()

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Scheduled changes are skipped until this time (epoch ms); [Long.MAX_VALUE] = until resumed. */
    var pausedUntil: Long
        get() = sp.getLong(KEY_PAUSED_UNTIL, 0L)
        set(v) = sp.edit().putLong(KEY_PAUSED_UNTIL, v).apply()

    val isPaused get() = System.currentTimeMillis() < pausedUntil

    /** Notify when scheduled changes keep failing (off until the user turns it on). */
    var failureAlerts: Boolean
        get() = sp.getBoolean("failureAlerts", false)
        set(v) = sp.edit().putBoolean("failureAlerts", v).apply()

    /** Screens a change was started for from the app, so only their refresh buttons spin. */
    var refreshing: Set<Preview>
        get() = sp.getString(KEY_REFRESHING, "")!!.split(',').mapNotNull { n -> Preview.entries.find { it.name == n } }.toSet()
        set(v) = sp.edit().putString(KEY_REFRESHING, v.joinToString(",") { it.name }).apply()

    var status: String
        get() = sp.getString(KEY_STATUS, "Not run yet.")!!
        set(v) = sp.edit().putString(KEY_STATUS, v).apply()

    /** Wallpaper ids already used, newest last, so we don't repeat images. */
    var used: List<String>
        get() = sp.getString("history", "")!!.split(',').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("history", v.takeLast(500).joinToString(",")).apply()

    /** Wallpaper ids the user trashed; never picked again. */
    var blocked: Set<String>
        get() = sp.getString("blocked", "")!!.split(',').filter { it.isNotEmpty() }.toSet()
        set(v) = sp.edit().putString("blocked", v.joinToString(",")).apply()

    /** Recently set wallpapers, newest first. */
    var recent: List<RecentEntry>
        get() = readEntries("recent")
        set(v) = writeEntries("recent", v)

    /** Favourite wallpapers, newest first; kept until unfavourited (or blocked). */
    var favorites: List<RecentEntry>
        get() = readEntries("favorites")
        set(v) = writeEntries("favorites", v)

    /** When on, favourites count as one more "search term" in the daily pick. */
    var includeFavorites: Boolean
        get() = sp.getBoolean("includeFavorites", false)
        set(v) = sp.edit().putBoolean("includeFavorites", v).apply()

    fun isFavorite(id: String?) = id != null && favorites.any { it.id == id }

    fun favoriteThumb(id: String) = File(File(context.filesDir, "favorites"), "$id.jpg")

    fun addFavorite(entry: RecentEntry, thumbnail: File) {
        val dest = favoriteThumb(entry.id)
        dest.parentFile?.mkdirs()
        thumbnail.copyTo(dest, overwrite = true)
        favorites = listOf(entry.copy(time = System.currentTimeMillis())) + favorites.filter { it.id != entry.id }
    }

    fun removeFavorite(id: String) {
        favorites = favorites.filter { it.id != id }
        favoriteThumb(id).delete()
    }

    /** History or favourites entry for [id] (has the full-size image link). */
    fun findEntry(id: String) = recent.firstOrNull { it.id == id } ?: favorites.firstOrNull { it.id == id }

    private fun readEntries(key: String): List<RecentEntry> {
        val json = JSONArray(sp.getString(key, "[]"))
        return (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            RecentEntry(o.getString("id"), o.getString("page"), o.getString("image"), o.optLong("time"))
        }
    }

    private fun writeEntries(key: String, entries: List<RecentEntry>) {
        val json = JSONArray()
        entries.forEach {
            json.put(JSONObject().put("id", it.id).put("page", it.pageUrl).put("image", it.imageUrl).put("time", it.time))
        }
        sp.edit().putString(key, json.toString()).apply()
    }

    /** Moves [entry] to the front, keeping a few more than the UI shows (current ones are hidden there). */
    fun addRecent(entry: RecentEntry, thumbnail: File) {
        val dest = entry.thumbFile(context)
        dest.parentFile?.mkdirs()
        thumbnail.copyTo(dest, overwrite = true)
        val list = (listOf(entry) + recent.filter { it.id != entry.id }).take(MAX_RECENT)
        recent = list
        pruneThumbs(list)
    }

    fun removeRecent(id: String) {
        val list = recent.filter { it.id != id }
        recent = list
        pruneThumbs(list)
    }

    private fun pruneThumbs(keep: List<RecentEntry>) {
        val names = keep.map { "${it.id}.jpg" }.toSet()
        File(context.filesDir, "history").listFiles()?.filter { it.name !in names }?.forEach { it.delete() }
    }

    /** Id of the wallpaper currently on a screen, if Wallscout set it. */
    fun currentId(preview: Preview): String? {
        if (!preview.file(context).exists()) return null
        // Older versions only stored the link (wallhaven.cc/w/<id>).
        return sp.getString(preview.idKey, null)
            ?: sp.getString(preview.urlKey, null)?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val KEY_STATUS = "status"
        const val KEY_PAUSED_UNTIL = "pausedUntil"
        val INTERVALS = listOf(1, 3, 6, 9, 12, 24)
        const val KEY_REFRESHING = "refreshing"
        const val WORKING = "Working… (searching Wallhaven)"

        /** History shows this many; a couple more are kept since the ones on screen are hidden there. */
        const val HISTORY_SHOWN = 12
        private const val MAX_RECENT = HISTORY_SHOWN + 2
    }
}
