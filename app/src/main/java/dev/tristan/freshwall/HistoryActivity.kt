package dev.tristan.freshwall

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Grid of recent wallpapers, or of favourites when started with [EXTRA_FAVORITES]. Tap one to set
 * it again, open it on Wallhaven, (un)favourite or block it; the star on each toggles favourite.
 */
class HistoryActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private var favoritesMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        prefs = Prefs(this)
        favoritesMode = intent.getBooleanExtra(EXTRA_FAVORITES, false)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = if (favoritesMode) "Favorites" else "History"
            setNavigationOnClickListener { finish() }
        }
        if (favoritesMode) {
            findViewById<TextView>(R.id.hint).text = "Tap an image to set it, open it on Wallhaven, or remove it"
            findViewById<TextView>(R.id.empty).text = "No favorites yet.\nTap the star on a wallpaper to save it here."
        }
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
    }

    override fun onStart() {
        super.onStart()
        showGrid()
    }

    private fun list() = if (favoritesMode) prefs.favorites else entries(this, prefs)

    private fun thumb(entry: RecentEntry): File =
        if (favoritesMode) prefs.favoriteThumb(entry.id) else entry.thumbFile(this)

    private fun showGrid() {
        val list = list()
        findViewById<View>(R.id.empty).visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val grid = findViewById<LinearLayout>(R.id.grid)
        grid.removeAllViews()
        val gap = dp(10)
        // 3 across on phones; more on wider screens, keeping thumbnails about the same size.
        val columns = maxOf(3, resources.configuration.screenWidthDp / 150)
        for (rowItems in list.chunked(columns)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until columns) {
                row.addView(cell(rowItems.getOrNull(i)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = gap
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = gap
            })
        }
    }

    /** Thumbnail with a star, and the date underneath; an invisible placeholder fills short rows. */
    private fun cell(entry: RecentEntry?): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (entry == null) {
            visibility = View.INVISIBLE
            return@apply
        }
        val frame = FrameLayout(this@HistoryActivity)
        frame.addView(ImageView(this@HistoryActivity).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            setBackgroundResource(R.drawable.preview_bg)
            setImageBitmap(BitmapFactory.decodeFile(thumb(entry).path))
            contentDescription = if (favoritesMode) "Favorite wallpaper" else "Previous wallpaper"
            setOnClickListener { menu(entry) }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val size = dp(32)
        frame.addView(ImageButton(this@HistoryActivity).apply {
            setBackgroundResource(R.drawable.trash_bg)
            setImageResource(if (prefs.isFavorite(entry.id)) R.drawable.ic_star else R.drawable.ic_star_outline)
            contentDescription = "Favorite"
            setOnClickListener { toggleFavorite(entry) }
        }, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply {
            // Same corner as the star on the main screen's previews.
            topMargin = dp(6)
            marginStart = dp(6)
        })
        addView(frame)
        if (entry.time > 0) {
            addView(TextView(this@HistoryActivity).apply {
                text = dateFormat().format(Instant.ofEpochMilli(entry.time).atZone(ZoneId.systemDefault()))
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
                setPadding(0, 6, 0, 0)
            })
        }
    }

    private fun toggleFavorite(entry: RecentEntry) {
        if (prefs.isFavorite(entry.id)) {
            prefs.removeFavorite(entry.id)
            Toast.makeText(this, "Removed from favorites", Toast.LENGTH_SHORT).show()
        } else {
            prefs.addFavorite(entry, thumb(entry))
            Toast.makeText(this, "Added to favorites", Toast.LENGTH_SHORT).show()
        }
        showGrid()
    }

    private fun menu(entry: RecentEntry) {
        val home = setOf(Preview.HOME)
        val lock = setOf(Preview.LOCK)
        val both = Preview.entries.toSet()
        // Offer the screens the "Apply to" setting uses.
        val setOptions = when (prefs.target) {
            Target.BOTH_DIFFERENT -> listOf("Set on home screen" to home, "Set on lock screen" to lock, "Set on both screens" to both)
            Target.BOTH_SAME -> listOf("Set as wallpaper" to both)
            Target.HOME -> listOf("Set as wallpaper" to home)
            Target.LOCK -> listOf("Set as wallpaper" to lock)
        }
        val favoriteLabel = if (prefs.isFavorite(entry.id)) "Remove from favorites" else "Add to favorites"
        val labels = setOptions.map { it.first } + listOf("Open on Wallhaven", "Save to Photos", favoriteLabel, "Block")
        MaterialAlertDialogBuilder(this)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < setOptions.size -> {
                        prefs.status = Prefs.WORKING
                        Scheduler.setAgain(this, entry.id, setOptions[which].second)
                        finish() // back to the main screen to watch it change
                    }
                    labels[which] == "Open on Wallhaven" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.pageUrl)))
                    labels[which] == "Save to Photos" -> Saver.saveToPhotos(this, entry)
                    labels[which] == favoriteLabel -> toggleFavorite(entry)
                    else -> confirmBlock(entry)
                }
            }
            .show()
    }

    private fun confirmBlock(entry: RecentEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Block this image?")
            .setMessage("Wallscout won't use it again, and it's removed from favorites.")
            .setPositiveButton("Block") { _, _ ->
                Scheduler.block(this, entry.id)
                Toast.makeText(this, "Blocked", Toast.LENGTH_SHORT).show()
                showGrid()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dateFormat() =
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(this)) "MMM d, HH:mm" else "MMM d, h:mm a")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_FAVORITES = "favorites"

        /** Recent wallpapers that aren't on screen now, newest first. */
        fun entries(context: Context, prefs: Prefs): List<RecentEntry> {
            val current = Preview.entries.mapNotNull { prefs.currentId(it) }.toSet()
            return prefs.recent.filter { it.id !in current && it.thumbFile(context).exists() }.take(Prefs.HISTORY_SHOWN)
        }
    }
}
