package dev.tristan.freshwall

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class WallpaperWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = Prefs(applicationContext)
        val changer = WallpaperChanger(applicationContext)
        val screens = inputData.getStringArray(KEY_SCREENS)?.map { Preview.valueOf(it) }?.toSet()
        when (inputData.getString(KEY_MODE)) {
            MODE_REAPPLY -> return@withContext try {
                changer.reapply()
                Result.success()
            } catch (e: Exception) {
                prefs.status = "Couldn't apply adjustments: ${e.message ?: e.javaClass.simpleName}"
                Result.failure()
            }
        }
        // Paused: skip scheduled changes (the schedule itself keeps running).
        if (inputData.getString(KEY_MODE) == MODE_SCHEDULED && prefs.isPaused) return@withContext Result.success()
        try {
            prefs.status = when (inputData.getString(KEY_MODE)) {
                MODE_SHARE_HOME -> changer.shareHome()
                MODE_SET_AGAIN -> {
                    val id = inputData.getString(KEY_ID)
                    val entry = id?.let { prefs.findEntry(it) }
                        ?: throw IllegalStateException("That image is no longer in History or Favorites")
                    changer.setAgain(entry, screens ?: Preview.entries.toSet())
                }
                else -> changer.run(only = screens)
            }
            Notifier.clear(applicationContext)
            Result.success()
        } catch (e: Exception) {
            // Network trouble and rate limits are worth retrying; config errors are not.
            val retry = e is IOException && runAttemptCount < 4
            val reason = e.message ?: e.javaClass.simpleName
            prefs.status = "Failed ${WallpaperChanger.now()}: $reason" +
                if (retry) "\nWill retry in a few minutes." else ""
            // A scheduled change that has used up its retries: tell the user, since the app is
            // probably closed. (Changes started from the app show the error there.)
            if (!retry && inputData.getString(KEY_MODE) == MODE_SCHEDULED && prefs.failureAlerts) {
                Notifier.automaticChangeFailed(applicationContext, reason)
            }
            if (retry) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_MODE = "mode"
        const val KEY_SCREENS = "screens"
        const val KEY_ID = "id"
        const val MODE_REAPPLY = "reapply"
        const val MODE_REPLACE = "replace"
        const val MODE_SET_AGAIN = "setAgain"
        const val MODE_SHARE_HOME = "shareHome"
        const val MODE_SCHEDULED = "scheduled"
    }
}
