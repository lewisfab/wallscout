package dev.tristan.freshwall

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateFormat
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.ImageView
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The home/lock preview thumbnails (layout/previews.xml), shared by both screens.
 * [onTrash], [onRefresh] and [onStar] show those buttons on each preview; null hides them.
 */
class PreviewPanel(
    private val activity: Activity,
    private val prefs: Prefs,
    private val onTrash: ((Preview) -> Unit)? = null,
    private val onRefresh: ((Preview) -> Unit)? = null,
    private val onStar: ((Preview) -> Unit)? = null,
    /** Tapping an image; null opens its Wallhaven page. */
    private val onImageTap: ((Preview) -> Unit)? = null,
) {
    private class Views(
        val column: Int, val image: Int, val trash: Int, val refresh: Int, val arrows: Int, val star: Int, val time: Int,
    )

    private val views = mapOf(
        Preview.HOME to Views(R.id.homeColumn, R.id.homePreview, R.id.homeTrash, R.id.homeRefresh, R.id.homeRefreshArrows, R.id.homeStar, R.id.homeTime),
        Preview.LOCK to Views(R.id.lockColumn, R.id.lockPreview, R.id.lockTrash, R.id.lockRefresh, R.id.lockRefreshArrows, R.id.lockStar, R.id.lockTime),
    )

    /**
     * Shows the saved thumbnails with [adjust] drawn on top. The same image on both screens shows
     * once, unless each screen is adjusted [separate]ly.
     */
    fun show(separate: Boolean, adjust: (Preview) -> Adjust) {
        val home = showOne(Preview.HOME)
        val lock = showOne(Preview.LOCK)
        val single = isSingle(separate)
        activity.findViewById<TextView>(R.id.homeLabel).text = if (single) "Home + lock screen" else "Home screen"
        if (single) activity.findViewById<View>(R.id.lockColumn).visibility = View.GONE
        activity.findViewById<View>(R.id.previews).visibility = if (home || lock) View.VISIBLE else View.GONE
        updateEffects(adjust)
    }

    /** True when one preview stands for both screens. */
    fun isSingle(separate: Boolean) = prefs.sp.getBoolean(Preview.KEY_SHARED, false) && !separate

    /** Re-draws the adjustments, scaled so blur looks the same as on the real wallpaper. */
    fun updateEffects(adjust: (Preview) -> Adjust) {
        val screenWidth = activity.resources.displayMetrics.widthPixels.toFloat()
        for ((preview, v) in views) {
            val view = activity.findViewById<ImageView>(v.image)
            view.post { view.setRenderEffect(Effects.renderEffect(adjust(preview), view.width / screenWidth)) }
        }
    }

    /**
     * Spins the refresh buttons of the [screens] getting a new wallpaper (none when empty).
     * A single shared preview spins if either screen is changing.
     */
    fun setWorking(screens: Set<Preview>, separate: Boolean) {
        val spinning = if (screens.isNotEmpty() && isSingle(separate)) setOf(Preview.HOME) else screens
        for ((preview, v) in views) {
            // Only the arrows turn; the photo in the middle stays put.
            val button = activity.findViewById<View>(v.arrows)
            val working = preview in spinning
            if (working && button.animation == null) {
                button.startAnimation(
                    RotateAnimation(0f, 360f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f).apply {
                        duration = 1000
                        repeatCount = Animation.INFINITE
                        interpolator = LinearInterpolator()
                    }
                )
            } else if (!working) {
                button.clearAnimation()
            }
        }
    }

    /** Tapping a thumbnail opens the image's Wallhaven page. */
    private fun showOne(preview: Preview): Boolean {
        val v = views.getValue(preview)
        val bitmap = BitmapFactory.decodeFile(preview.file(activity).path)
        activity.findViewById<View>(v.column).visibility = if (bitmap != null) View.VISIBLE else View.INVISIBLE
        val image = activity.findViewById<ImageView>(v.image)
        image.setImageBitmap(bitmap)
        val url = prefs.sp.getString(preview.urlKey, null)
        image.setOnClickListener {
            when {
                onImageTap != null -> onImageTap.invoke(preview)
                url != null -> activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        bindButton(v.trash, bitmap != null, onTrash, preview)
        bindButton(v.refresh, bitmap != null, onRefresh, preview)
        bindButton(v.star, bitmap != null, onStar, preview)
        activity.findViewById<ImageView>(v.star).setImageResource(
            if (prefs.isFavorite(prefs.currentId(preview))) R.drawable.ic_star else R.drawable.ic_star_outline
        )

        val time = prefs.sp.getLong(preview.timeKey, 0L)
        activity.findViewById<TextView>(v.time).apply {
            visibility = if (bitmap != null && time > 0) View.VISIBLE else View.GONE
            text = timeFormat().format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
        }
        return bitmap != null
    }

    private fun bindButton(id: Int, hasImage: Boolean, action: ((Preview) -> Unit)?, preview: Preview) {
        val button = activity.findViewById<View>(id)
        button.visibility = if (hasImage && action != null) View.VISIBLE else View.GONE
        button.setOnClickListener { action?.invoke(preview) }
    }

    /** "Oct 14, 1:45 PM", or 24-hour if the phone uses it. */
    private fun timeFormat() =
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(activity)) "MMM d, HH:mm" else "MMM d, h:mm a")
}
