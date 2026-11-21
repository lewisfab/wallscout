package dev.tristan.freshwall

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

object Scheduler {
    private const val DAILY = "daily-wallpaper"
    private const val NOW = "wallpaper-now"
    private const val REAPPLY = "wallpaper-reapply"

    /**
     * Schedules the daily job (WorkManager persists it across reboots).
     * [replace] re-aligns it to the chosen time; otherwise an existing schedule is kept.
     */
    fun apply(context: Context, replace: Boolean) {
        val prefs = Prefs(context)
        val wm = WorkManager.getInstance(context)
        if (!prefs.enabled) {
            wm.cancelUniqueWork(DAILY)
            return
        }
        val interval = prefs.intervalHours.toLong()
        val now = ZonedDateTime.now()
        val next = nextRun(prefs, now)

        val request = PeriodicWorkRequestBuilder<WallpaperWorker>(interval, TimeUnit.HOURS)
            .setInputData(workDataOf(WallpaperWorker.KEY_MODE to WallpaperWorker.MODE_SCHEDULED))
            .setInitialDelay(Duration.between(now, next))
            .setConstraints(constraints(prefs.wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(
            DAILY,
            if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * The first scheduled change after [after]. Changes run every [Prefs.intervalHours], lined
     * up with the chosen time: e.g. every 6 hours from 11 PM is 11 PM, 5 AM, 11 AM, 5 PM.
     */
    fun nextRun(prefs: Prefs, after: ZonedDateTime): ZonedDateTime {
        val interval = prefs.intervalHours.toLong()
        var next = after.truncatedTo(ChronoUnit.DAYS).withHour(prefs.hour).withMinute(prefs.minute)
        while (next.isAfter(after.plusHours(interval))) next = next.minusHours(interval)
        while (!next.isAfter(after)) next = next.plusHours(interval)
        return next
    }

    fun runNow(context: Context) {
        Prefs(context).refreshing = Preview.entries.toSet()
        now(context)
    }

    /** Fetches a new image for just [screens] (e.g. after one was trashed). */
    fun replace(context: Context, screens: Set<Preview>) = now(
        context.also { Prefs(it).refreshing = screens },
        WallpaperWorker.KEY_MODE to WallpaperWorker.MODE_REPLACE,
        WallpaperWorker.KEY_SCREENS to screens.map { it.name }.toTypedArray(),
    )

    /** Puts the current home image on the lock screen too, from the saved copy. */
    fun shareHome(context: Context) = now(
        context.also { Prefs(it).refreshing = Preview.entries.toSet() },
        WallpaperWorker.KEY_MODE to WallpaperWorker.MODE_SHARE_HOME,
    )

    /** Sets the History image [id] on [screens] again. */
    fun setAgain(context: Context, id: String, screens: Set<Preview>) = now(
        context.also { Prefs(it).refreshing = screens },
        WallpaperWorker.KEY_MODE to WallpaperWorker.MODE_SET_AGAIN,
        WallpaperWorker.KEY_ID to id,
        WallpaperWorker.KEY_SCREENS to screens.map { it.name }.toTypedArray(),
    )

    /**
     * Blocks image [id] for good. If it's on screen, those screens get a new image (returns true);
     * otherwise it's just dropped from History.
     */
    fun block(context: Context, id: String): Boolean {
        val prefs = Prefs(context)
        prefs.blocked = prefs.blocked + id
        prefs.removeRecent(id)
        prefs.removeFavorite(id)
        val onScreen = Preview.entries.filter { prefs.currentId(it) == id }.toSet()
        if (onScreen.isEmpty()) return false
        prefs.status = Prefs.WORKING
        replace(context, onScreen)
        return true
    }

    private fun now(context: Context, vararg input: Pair<String, Any?>) {
        val request = OneTimeWorkRequestBuilder<WallpaperWorker>()
            .setInputData(workDataOf(*input))
            .setConstraints(constraints(wifiOnly = false))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    /** Re-applies brightness/saturation/blur to the current wallpapers (no download). */
    fun reapply(context: Context) {
        val request = OneTimeWorkRequestBuilder<WallpaperWorker>()
            .setInputData(workDataOf(WallpaperWorker.KEY_MODE to WallpaperWorker.MODE_REAPPLY))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(REAPPLY, ExistingWorkPolicy.REPLACE, request)
    }

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()
}
