package dev.tristan.freshwall

import android.app.Activity
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import kotlin.concurrent.thread

/** Saves a wallpaper's full-size original into Pictures/Wallscout (shows up in Photos). */
object Saver {
    fun saveToPhotos(activity: Activity, entry: RecentEntry) {
        Toast.makeText(activity, "Saving to Photos…", Toast.LENGTH_SHORT).show()
        thread {
            val message = try {
                val bytes = WallhavenSource.download(entry.imageUrl)
                val ext = entry.imageUrl.substringAfterLast('.', "jpg").lowercase()
                val mime = if (ext == "png") "image/png" else "image/jpeg"
                val resolver = activity.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "wallhaven-${entry.id}.$ext")
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Wallscout")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Couldn't create the file")
                resolver.openOutputStream(uri)!!.use { it.write(bytes) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                "Saved to Photos (Pictures/Wallscout)"
            } catch (e: Exception) {
                "Couldn't save: ${e.message ?: e.javaClass.simpleName}"
            }
            activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
        }
    }
}
