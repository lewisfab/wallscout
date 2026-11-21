package dev.tristan.freshwall

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class WallpaperChanger(private val context: Context) {
    private val prefs = Prefs(context)

    private class Picked(val wallpaper: Wallpaper, val bytes: ByteArray, val bitmap: Bitmap)

    /**
     * Searches, picks, crops and applies new wallpaper(s). [only] limits it to some screens
     * (e.g. replacing a trashed image); otherwise the "Apply to" setting decides.
     * Returns a status line for the UI.
     */
    fun run(only: Set<Preview>? = null): String {
        val terms = prefs.searchTerms
        require(terms.isNotEmpty()) { "No search terms configured" }

        val target = prefs.target
        val setHome = only?.contains(Preview.HOME) ?: (target != Target.LOCK)
        val setLock = only?.contains(Preview.LOCK) ?: (target != Target.HOME)
        val sameImage = setHome && setLock && target == Target.BOTH_SAME
        val needed = if (setHome && setLock && !sameImage) 2 else 1

        // Favourites, when included, are one more entry in the draw alongside the search terms.
        val useFavorites = prefs.includeFavorites && prefs.favorites.isNotEmpty() &&
            (terms + FAVORITES).random() == FAVORITES
        var source = "favorites"
        val picked = (if (useFavorites) pickFavorites(needed) else null) ?: run {
            val term = terms.random()
            source = "\"$term\""
            pickFromSearch(term, needed)
        }

        val first = picked[0]
        val homePick = first.takeIf { setHome }
        val lockPick = when {
            !setLock -> null
            setHome && !sameImage -> picked.getOrElse(1) { first }
            else -> first
        }
        apply(homePick, lockPick, clearOthers = only == null, shared = sameImage)
        return status("Updated ${now()} from $source")
    }

    private fun pickFromSearch(term: String, needed: Int): List<Picked> {
        val candidates = findCandidates(buildQuery(term), needed)
        if (candidates.isEmpty()) throw IllegalStateException("Wallhaven has no results for \"$term\"")
        val picked = download(candidates.take(MAX_DOWNLOADS), needed)
        if (picked.isEmpty()) throw IOException("Couldn't download a usable image")
        return picked
    }

    /**
     * Favourites not on screen now, the ones seen longest ago first. Returns null (search
     * instead) if there aren't enough to fill the screens being changed.
     */
    private fun pickFavorites(needed: Int): List<Picked>? {
        val onScreen = Preview.entries.mapNotNull { prefs.currentId(it) }.toSet()
        val used = prefs.used
        val choices = prefs.favorites.filter { it.id !in onScreen && it.id !in prefs.blocked }
            .shuffled()
            .sortedBy { used.lastIndexOf(it.id) } // never/least recently used first
            .map { Wallpaper(it.id, it.imageUrl, it.pageUrl, 0, 0) }
        return download(choices, needed).takeIf { it.size == needed }
    }

    private fun download(candidates: List<Wallpaper>, needed: Int): List<Picked> {
        val (width, height) = screenSize()
        val picked = mutableListOf<Picked>()
        for (wallpaper in candidates) {
            if (picked.size == needed) break
            val bytes = try {
                WallhavenSource.download(wallpaper.imageUrl)
            } catch (e: IOException) {
                Log.w(TAG, "Skipping ${wallpaper.imageUrl}: ${e.message}")
                continue
            }
            render(bytes, width, height)?.let { picked += Picked(wallpaper, bytes, it) }
        }
        return picked
    }

    /** Sets a wallpaper from History or Favorites again on [screens]. */
    fun setAgain(entry: RecentEntry, screens: Set<Preview>): String {
        val (width, height) = screenSize()
        val bytes = WallhavenSource.download(entry.imageUrl)
        val bitmap = render(bytes, width, height) ?: throw IOException("Couldn't read that image")
        val pick = Picked(Wallpaper(entry.id, entry.imageUrl, entry.pageUrl, bitmap.width, bitmap.height), bytes, bitmap)
        val home = Preview.HOME in screens
        val lock = Preview.LOCK in screens
        apply(pick.takeIf { home }, pick.takeIf { lock }, clearOthers = false, shared = home && lock)
        return status("Set again ${now()}")
    }

    /**
     * Puts the current home image on the lock screen too, cropped from the saved full-size copy,
     * so nothing is downloaded. (The saved home copy may be wider for parallax; it's re-cropped.)
     */
    fun shareHome(): String {
        val (width, height) = screenSize()
        val saved = Preview.HOME.originalFile(context)
        val lock = render(saved.readBytes(), width, height) ?: throw IOException("Couldn't read the saved home image")
        applyAdjusted(null, lock)
        saveOriginal(Preview.LOCK, lock)
        Preview.HOME.file(context).copyTo(Preview.LOCK.file(context), overwrite = true)
        prefs.sp.edit()
            .putString(Preview.LOCK.urlKey, prefs.sp.getString(Preview.HOME.urlKey, null))
            .putString(Preview.LOCK.idKey, prefs.currentId(Preview.HOME))
            .putLong(Preview.LOCK.timeKey, System.currentTimeMillis())
            .putBoolean(Preview.KEY_SHARED, true)
            .apply()
        return status("Updated ${now()}")
    }

    /**
     * Applies the picks (with parallax and adjustments) and records them. [clearOthers] forgets a
     * screen that wasn't set (the "Apply to" setting no longer covers it).
     */
    private fun apply(homePick: Picked?, lockPick: Picked?, clearOthers: Boolean, shared: Boolean) {
        val (width, height) = screenSize()
        // Parallax: give the home screen a wider image so the launcher can pan it between pages.
        val homeBase = homePick?.let {
            if (prefs.parallax) render(it.bytes, (width * PARALLAX_WIDTH).roundToInt(), height) ?: it.bitmap
            else it.bitmap
        }
        applyAdjusted(homeBase, lockPick?.bitmap)
        prefs.used = prefs.used + listOfNotNull(homePick, lockPick).map { it.wallpaper.id }

        for ((preview, pick, base) in listOf(
            Triple(Preview.HOME, homePick, homeBase),
            Triple(Preview.LOCK, lockPick, lockPick?.bitmap),
        )) {
            if (pick != null) {
                saveOriginal(preview, base)
                savePreview(preview, pick)
            } else if (clearOthers) {
                saveOriginal(preview, null)
                savePreview(preview, null)
            }
        }
        // Same image on both screens: the app can show a single "Home + lock screen" preview.
        prefs.sp.edit().putBoolean(Preview.KEY_SHARED, shared).apply()

        for (pick in listOfNotNull(homePick, lockPick).distinctBy { it.wallpaper.id }) {
            val w = pick.wallpaper
            val preview = if (pick === homePick) Preview.HOME else Preview.LOCK
            prefs.addRecent(RecentEntry(w.id, w.pageUrl, w.imageUrl, System.currentTimeMillis()), preview.file(context))
        }
    }

    /** Status text listing what's on each screen now. */
    private fun status(headline: String) = buildString {
        append(headline)
        fun url(p: Preview) = prefs.sp.getString(p.urlKey, null)?.takeIf { p.file(context).exists() }
        val home = url(Preview.HOME)
        val lock = url(Preview.LOCK)
        if (prefs.sp.getBoolean(Preview.KEY_SHARED, false) && home != null) {
            append("\n\nHome + lock: ").append(home)
        } else {
            append("\n")
            home?.let { append("\nHome: ").append(it) }
            lock?.let { append("\nLock: ").append(it) }
        }
    }

    /** Adds exclusions to the search: "-anime", "-\"digital art\"", etc. */
    private fun buildQuery(term: String): String {
        val exclude = prefs.excludeTerms + if (prefs.photosOnly) PHOTO_EXCLUDE else emptyList()
        return (listOf(term) + exclude.distinctBy { it.lowercase() }.map { if (' ' in it) "-\"$it\"" else "-$it" })
            .joinToString(" ")
    }

    /**
     * Walks the search results (best first) collecting images we haven't used yet.
     * Portrait images come first when preferred; landscape ones fill in if there aren't enough.
     * With "Sharp images only", only images at least as large as the screen (as wide as the
     * parallax image, when that's on) are used, so nothing is scaled up and looks soft; if a search
     * has none that big, it falls back to the 1080p minimum rather than failing. If everything has
     * been used, falls back to repeats. Blocked images are never returned.
     */
    private fun findCandidates(term: String, needed: Int): List<Wallpaper> {
        val history = prefs.used.toSet()
        val blocked = prefs.blocked
        val fresh = LinkedHashMap<String, Wallpaper>()
        val seen = LinkedHashMap<String, Wallpaper>()
        val passes = if (prefs.preferPortrait) listOf(true, false) else listOf(false)
        val (width, height) = screenSize()
        val minWidth = if (prefs.parallax) (width * PARALLAX_WIDTH).roundToInt() else width
        for (relaxed in if (prefs.sharpOnly) listOf(false, true) else listOf(true)) {
            for (portrait in passes) {
                val atleast = when {
                    !relaxed -> "${minWidth}x$height"
                    portrait -> "1080x1920"
                    else -> "1920x1080"
                }
                var page = 1
                while (fresh.size < needed + SPARE && page <= MAX_PAGES) {
                    val result = WallhavenSource.search(term, prefs.sort, portrait, atleast, page)
                    for (w in result.wallpapers) {
                        if (w.id in blocked) continue
                        seen.putIfAbsent(w.id, w)
                        if (w.id !in history) fresh.putIfAbsent(w.id, w)
                    }
                    if (page >= result.lastPage) break
                    page++
                }
                if (fresh.size >= needed) break
            }
            if (seen.isNotEmpty()) break
        }
        return if (fresh.size >= needed) fresh.values.toList() else (fresh.values + seen.values.shuffled()).distinct()
    }

    /** Re-applies the current adjustments to the wallpapers last set, without downloading anything. */
    fun reapply() {
        fun load(p: Preview) = p.originalFile(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        val home = load(Preview.HOME)
        val lock = load(Preview.LOCK)
        if (home != null || lock != null) applyAdjusted(home, lock)
    }

    private fun applyAdjusted(home: Bitmap?, lock: Bitmap?) {
        val wm = WallpaperManager.getInstance(context)
        home?.let { wm.setBitmap(Effects.apply(it, prefs.adjustFor(Preview.HOME)), null, true, WallpaperManager.FLAG_SYSTEM) }
        lock?.let { wm.setBitmap(Effects.apply(it, prefs.adjustFor(Preview.LOCK)), null, true, WallpaperManager.FLAG_LOCK) }
        prefs.appliedAdjust = prefs.adjustSignature()
    }

    private fun saveOriginal(preview: Preview, bitmap: Bitmap?) {
        val file = preview.originalFile(context)
        if (bitmap == null) file.delete() else writeJpeg(bitmap, file, 95)
    }

    /** Keeps a small unadjusted copy of what we set; the app draws the adjustments on top live. */
    private fun savePreview(preview: Preview, picked: Picked?) {
        if (picked == null) {
            preview.file(context).delete()
            prefs.sp.edit().remove(preview.idKey).remove(preview.urlKey).remove(preview.timeKey).apply()
            return
        }
        val b = picked.bitmap
        val small = Bitmap.createScaledBitmap(b, PREVIEW_WIDTH, PREVIEW_WIDTH * b.height / b.width, true)
        writeJpeg(small, preview.file(context), 85)
        if (small !== b) small.recycle()
        prefs.sp.edit()
            .putString(preview.urlKey, picked.wallpaper.pageUrl)
            .putString(preview.idKey, picked.wallpaper.id)
            .putLong(preview.timeKey, System.currentTimeMillis())
            .apply()
    }

    private fun writeJpeg(bitmap: Bitmap, file: File, quality: Int) {
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        tmp.renameTo(file)
    }

    /** Center-crops the image to the screen's aspect ratio and scales it to exactly screen size. */
    private fun render(bytes: ByteArray, tw: Int, th: Int): Bitmap? {
        val decoder = try {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } catch (e: IOException) {
            return null
        }
        try {
            val w = decoder.width
            val h = decoder.height
            val ratio = tw.toFloat() / th
            val crop = if (w.toFloat() / h > ratio) {
                val cw = (h * ratio).roundToInt()
                val left = (w - cw) / 2
                Rect(left, 0, left + cw, h)
            } else {
                val ch = (w / ratio).roundToInt()
                val top = (h - ch) / 2
                Rect(0, top, w, top + ch)
            }
            var sample = 1
            while (crop.width() / (sample * 2) >= tw) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val region = decoder.decodeRegion(crop, opts) ?: return null
            if (region.width == tw && region.height == th) return region
            val scaled = Bitmap.createScaledBitmap(region, tw, th, true)
            if (scaled !== region) region.recycle()
            return scaled
        } catch (e: RuntimeException) {
            Log.w(TAG, "Decode failed", e)
            return null
        } finally {
            decoder.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        display.getRealMetrics(m)
        return min(m.widthPixels, m.heightPixels) to max(m.widthPixels, m.heightPixels)
    }

    companion object {
        private const val TAG = "Wallscout"
        private const val MAX_DOWNLOADS = 6
        private const val MAX_PAGES = 5
        private const val SPARE = 3
        private const val PREVIEW_WIDTH = 360
        private const val PARALLAX_WIDTH = 1.5f
        /** Stands in for "pick a favourite" in the daily draw. */
        private const val FAVORITES = "<favorites>"

        /** Wallhaven tags that mark artwork rather than photos. */
        private val PHOTO_EXCLUDE = listOf(
            "anime", "anime girls", "digital art", "artwork", "illustration", "fantasy art", "concept art",
            "fan art", "CGI", "render", "3D", "video games", "comics", "cartoon", "painting", "drawing",
            "pixel art", "space art", "infographics", "minimalism", "simple background", "vector", "typography",
            "women", "men",
        )

        fun now(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))
    }
}
