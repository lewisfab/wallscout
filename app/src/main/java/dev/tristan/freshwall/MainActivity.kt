package dev.tristan.freshwall

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.view.WindowInsets
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Main screen. Every setting saves as soon as it's changed. */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var previews: PreviewPanel

    private val statusListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // Both: the status and which screens are changing are saved separately.
        if (key == Prefs.KEY_STATUS || key == Prefs.KEY_REFRESHING) runOnUiThread { showState() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (WelcomeActivity.shouldShow(prefs, Preview.entries.any { it.file(this).exists() })) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        previews = PreviewPanel(this, prefs, ::confirmTrash, ::refresh, ::toggleFavorite, ::imageMenu)

        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }

        findViewById<View>(R.id.adjustRow).setOnClickListener {
            startActivity(Intent(this, AdjustActivity::class.java))
        }
        findViewById<View>(R.id.historyRow).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<View>(R.id.favoritesRow).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java).putExtra(HistoryActivity.EXTRA_FAVORITES, true))
        }

        Logotype.apply(findViewById(R.id.title), findViewById(R.id.logoMark))

        // Image source
        // Credit Wallhaven, with the site name linked.
        findViewById<TextView>(R.id.credit).apply {
            val str = android.text.SpannableString(text)
            val start = text.indexOf("wallhaven.cc")
            str.setSpan(android.text.style.URLSpan("https://wallhaven.cc"), start, start + "wallhaven.cc".length, 0)
            text = str
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
        bindChips(R.id.searchChips, "Add search terms", "mountains, city at night", prefs.searchTerms, keepOne = true) {
            prefs.setSearchTerms(it)
        }
        bindChips(R.id.excludeChips, "Add excluded terms", "marvel, star wars", prefs.excludeTerms, keepOne = false) {
            prefs.setExcludeTerms(it)
        }
        bindSwitch(R.id.photosOnly, prefs.photosOnly) { prefs.photosOnly = it }
        bindSwitch(R.id.includeFavorites, prefs.includeFavorites) { prefs.includeFavorites = it }
        bindDropdown(R.id.sort, Sort.entries.map { it.label }, prefs.sort.ordinal) { prefs.sort = Sort.entries[it] }

        // Schedule
        bindSwitch(R.id.enabled, prefs.enabled) {
            prefs.enabled = it
            showSchedule()
            Scheduler.apply(this, replace = true)
        }
        bindDropdown(R.id.interval, Prefs.INTERVALS.map(::intervalLabel), Prefs.INTERVALS.indexOf(prefs.intervalHours)) {
            prefs.intervalHours = Prefs.INTERVALS[it]
            showSchedule()
            Scheduler.apply(this, replace = true)
        }
        findViewById<View>(R.id.timeRow).setOnClickListener { pickTime() }
        bindSwitch(R.id.wifiOnly, prefs.wifiOnly) {
            prefs.wifiOnly = it
            Scheduler.apply(this, replace = true)
        }
        findViewById<View>(R.id.firstWallpaper).setOnClickListener {
            prefs.status = Prefs.WORKING
            Scheduler.runNow(this)
        }
        // Opens Wallscout's App info page, where battery usage can be set to Unrestricted so the
        // daily change isn't delayed. (Play doesn't allow the direct Allow/Deny prompt for this app.)
        findViewById<View>(R.id.allowBackground).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            android.widget.Toast.makeText(this, "Tap App battery usage, then Unrestricted", android.widget.Toast.LENGTH_LONG).show()
        }

        // Display
        bindDropdown(R.id.target, Target.entries.map { it.label }, prefs.target.ordinal) {
            val old = prefs.target
            prefs.target = Target.entries[it]
            applyTargetChange(old, prefs.target)
        }
        bindSwitch(R.id.portrait, prefs.preferPortrait) { prefs.preferPortrait = it }
        bindSwitch(R.id.sharpOnly, prefs.sharpOnly) { prefs.sharpOnly = it }
        bindSwitch(R.id.parallax, prefs.parallax) { prefs.parallax = it }

        // More: Quick Settings tile and backup
        findViewById<View>(R.id.addTile).setOnClickListener { addTile() }
        findViewById<View>(R.id.exportRow).setOnClickListener {
            exportBackup.launch("wallscout-backup-${java.time.LocalDate.now()}.json")
        }
        findViewById<View>(R.id.importRow).setOnClickListener { importBackup.launch(arrayOf("application/json", "*/*")) }

        // Failure alerts: off by default; turning on asks for notification permission.
        bindSwitch(R.id.failureAlerts, prefs.failureAlerts) { on ->
            if (!on) {
                prefs.failureAlerts = false
            } else if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                prefs.failureAlerts = true
            } else {
                askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        findViewById<View>(R.id.pauseButton).setOnClickListener { if (prefs.isPaused) resume() else pickPause() }

        // Make sure the daily job exists on first launch without resetting its timing later.
        Scheduler.apply(this, replace = false)
    }

    override fun onStart() {
        super.onStart()
        showState()
        showSchedule()
        prefs.sp.registerOnSharedPreferenceChangeListener(statusListener)
    }

    override fun onResume() {
        super.onResume()
        // Only offer the button while Android may still delay background work.
        val allowed = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val visibility = if (allowed) View.GONE else View.VISIBLE
        findViewById<View>(R.id.allowBackground).visibility = visibility
        findViewById<View>(R.id.allowBackgroundHint).visibility = visibility
    }

    override fun onStop() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(statusListener)
        super.onStop()
    }

    /** Refresh on a preview: a new image for that screen, or both if one image covers both. */
    private fun refresh(preview: Preview) {
        val screens = if (previews.isSingle(prefs.separateAdjust)) Preview.entries.toSet() else setOf(preview)
        prefs.status = Prefs.WORKING
        Scheduler.replace(this, screens)
    }

    /** Preview, caption, progress and row summaries, all driven by the saved status. */
    private fun showState() {
        previews.show(prefs.separateAdjust, prefs::adjustFor)
        val status = prefs.status
        val working = status == Prefs.WORKING
        val failed = status.startsWith("Failed") || status.startsWith("Couldn't")
        // Only shown while working or after a failure; times are under each image otherwise.
        findViewById<TextView>(R.id.caption).apply {
            visibility = if (working || failed) View.VISIBLE else View.GONE
            text = if (working) "Finding a new wallpaper…" else status
            setTextColor(
                if (failed) MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError)
                else MaterialColors.getColor(this, android.R.attr.textColorSecondary)
            )
        }
        findViewById<View>(R.id.progress).visibility = if (working) View.VISIBLE else View.GONE
        val hasWallpaper = findViewById<View>(R.id.previews).visibility == View.VISIBLE
        findViewById<View>(R.id.firstWallpaper).visibility = if (hasWallpaper || working) View.GONE else View.VISIBLE
        previews.setWorking(if (working) prefs.refreshing else emptySet(), prefs.separateAdjust)
        showSummaries()
    }

    /** One-line summaries under the Image Adjustments and History rows. */
    private fun showSummaries() {
        val adjust = Preview.entries.map { prefs.adjustFor(it) }.distinct()
        findViewById<TextView>(R.id.adjustSummary).text = when {
            adjust.all { it.isNeutral } -> "No adjustments"
            adjust.size == 1 -> adjust[0].let { "Brightness ${it.brightness}% · Saturation ${it.saturation}% · Blur ${it.blur}" }
            else -> "Separate settings for home and lock screen"
        }
        val favorites = prefs.favorites.size
        findViewById<TextView>(R.id.favoritesSummary).text = when (favorites) {
            0 -> "Tap the star on a wallpaper to save it here"
            1 -> "1 favorite"
            else -> "$favorites favorites"
        }
        val count = HistoryActivity.entries(this, prefs).size
        findViewById<TextView>(R.id.historySummary).text = when (count) {
            0 -> "Previous wallpapers show up here"
            1 -> "1 previous wallpaper"
            else -> "$count previous wallpapers"
        }
    }

    /**
     * Makes the screens match a new "Apply to" choice now instead of at the next change:
     * same image -> put the home image on the lock screen too (from the saved copy, no download);
     * different -> new lock image.
     */
    private fun applyTargetChange(old: Target, new: Target) {
        val home = prefs.currentId(Preview.HOME)
        val lock = prefs.currentId(Preview.LOCK)
        when {
            new == Target.BOTH_SAME && home != lock -> {
                prefs.status = Prefs.WORKING
                // No saved home image (e.g. it was lock-only before): fetch a new one for both.
                if (home != null && Preview.HOME.originalFile(this).exists()) Scheduler.shareHome(this)
                else Scheduler.replace(this, Preview.entries.toSet())
            }
            new == Target.BOTH_DIFFERENT && old != Target.BOTH_DIFFERENT && (lock == null || lock == home) -> {
                prefs.status = Prefs.WORKING
                Scheduler.replace(this, setOf(Preview.LOCK))
            }
        }
    }

    private val askNotifications = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        prefs.failureAlerts = granted
        if (!granted) {
            findViewById<MaterialSwitch>(R.id.failureAlerts).isChecked = false
            Toast.makeText(this, "Notifications are off for Wallscout. You can allow them in Android settings.", Toast.LENGTH_LONG).show()
        }
    }

    // ---- Pause ----

    private fun pickPause() {
        val day = 24L * 60 * 60 * 1000
        val options = listOf("For 1 day" to day, "For 3 days" to 3 * day, "For 1 week" to 7 * day, "Until I resume" to Long.MAX_VALUE)
        MaterialAlertDialogBuilder(this)
            .setTitle("Pause automatic changes?")
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                val length = options[which].second
                prefs.pausedUntil = if (length == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + length
                showPause()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resume() {
        prefs.pausedUntil = 0L
        showPause()
    }

    /** "Next change at about 11:00 PM" or "Paused until Fri at 11:00 PM", with Pause / Resume. */
    private fun showPause() {
        findViewById<View>(R.id.pauseRow).visibility = if (prefs.enabled) View.VISIBLE else View.GONE
        val paused = prefs.isPaused
        findViewById<com.google.android.material.button.MaterialButton>(R.id.pauseButton).text = if (paused) "Resume" else "Pause"
        val now = java.time.ZonedDateTime.now()
        findViewById<TextView>(R.id.pauseText).text = when {
            !paused -> "Next change at about ${whenText(Scheduler.nextRun(prefs, now), now)}"
            prefs.pausedUntil == Long.MAX_VALUE -> "Paused until you resume"
            else -> {
                val until = java.time.Instant.ofEpochMilli(prefs.pausedUntil).atZone(now.zone)
                "Paused until ${whenText(until, now)}"
            }
        }
    }

    /** "11:00 PM", "tomorrow at 11:00 PM", "Fri at 11:00 PM" or "Oct 21 at 11:00 PM". */
    private fun whenText(time: java.time.ZonedDateTime, now: java.time.ZonedDateTime): String {
        val clock = time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), time.toLocalDate())
        return when {
            days == 0L -> clock
            days == 1L -> "tomorrow at $clock"
            days < 7 -> "${time.format(DateTimeFormatter.ofPattern("EEE"))} at $clock"
            else -> "${time.format(DateTimeFormatter.ofPattern("MMM d"))} at $clock"
        }
    }

    private val exportBackup = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val message = try {
            Backup.export(this, uri)
            "Settings exported"
        } catch (e: Exception) {
            "Couldn't export: ${e.message}"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private val importBackup = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        MaterialAlertDialogBuilder(this)
            .setTitle("Import settings?")
            .setMessage("This replaces your current settings, favorites and blocked images with the backup's.")
            .setPositiveButton("Import") { _, _ ->
                try {
                    Backup.import(this, uri)
                    Toast.makeText(this, "Settings imported", Toast.LENGTH_SHORT).show()
                    recreate() // reload every control from the imported settings
                } catch (e: Exception) {
                    Toast.makeText(this, "Couldn't import: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Asks Android to add the "New wallpaper" tile to Quick Settings (Android shows its own prompt). */
    private fun addTile() {
        getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
            android.content.ComponentName(this, RefreshTileService::class.java),
            "New wallpaper",
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile_refresh),
            mainExecutor,
        ) { result ->
            val message = when (result) {
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tile added"
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "The tile is already in Quick Settings"
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> null
                else -> "Add it by editing Quick Settings and dragging in \"New wallpaper\""
            }
            message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        }
    }

    /** Tapping a preview: open its Wallhaven page or save the full image to Photos. */
    private fun imageMenu(preview: Preview) {
        val id = prefs.currentId(preview) ?: return
        val entry = prefs.findEntry(id)
        val options = listOf("Open on Wallhaven") + if (entry != null) listOf("Save to Photos") else emptyList()
        MaterialAlertDialogBuilder(this)
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    prefs.sp.getString(preview.urlKey, null)?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                } else if (entry != null) {
                    Saver.saveToPhotos(this, entry)
                }
            }
            .show()
    }

    /** Star on a preview: add the image to Favorites, or take it off. */
    private fun toggleFavorite(preview: Preview) {
        val id = prefs.currentId(preview) ?: return
        if (prefs.isFavorite(id)) {
            prefs.removeFavorite(id)
            Toast.makeText(this, "Removed from favorites", Toast.LENGTH_SHORT).show()
        } else {
            val entry = prefs.findEntry(id) ?: run {
                Toast.makeText(this, "Can't favorite this one; it was set by an older version", Toast.LENGTH_SHORT).show()
                return
            }
            prefs.addFavorite(entry, preview.file(this))
            Toast.makeText(this, "Added to favorites", Toast.LENGTH_SHORT).show()
        }
        showState()
    }

    /** Trash on a preview: block that image for good and fetch a replacement for its screen(s). */
    private fun confirmTrash(preview: Preview) {
        val id = prefs.currentId(preview) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Block this image?")
            .setMessage("Wallscout won't use it again, and will set a new one now.")
            .setPositiveButton("Block") { _, _ -> Scheduler.block(this, id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Schedule ----

    private fun intervalLabel(hours: Int) = when (hours) {
        1 -> "Every hour"
        24 -> "Every 24 hours (daily)"
        else -> "Every $hours hours"
    }

    private fun showSchedule() {
        showPause()
        val shown = if (prefs.enabled) View.VISIBLE else View.GONE
        findViewById<View>(R.id.timeRow).visibility = shown
        findViewById<View>(R.id.intervalBox).visibility = shown
        // Daily: the time it changes. More often: when the cycle starts.
        findViewById<TextView>(R.id.timeLabel).text = if (prefs.intervalHours == 24) "At about" else "Starting from"
        findViewById<TextView>(R.id.timeValue).text =
            LocalTime.of(prefs.hour, prefs.minute).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }

    private fun pickTime() {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(prefs.hour)
            .setMinute(prefs.minute)
            .setTitleText("Change wallpaper at about")
            .build()
        picker.addOnPositiveButtonClickListener {
            prefs.hour = picker.hour
            prefs.minute = picker.minute
            showSchedule()
            Scheduler.apply(this, replace = true)
        }
        picker.show(supportFragmentManager, "time")
    }

    // ---- Controls that save straight away ----

    /**
     * Chips for a list of terms, each with an ✕ to remove, plus an "Add" chip that takes one or
     * more comma-separated terms. [keepOne] stops the last term being removed.
     */
    private fun bindChips(
        groupId: Int,
        title: String,
        example: String,
        initial: List<String>,
        keepOne: Boolean,
        save: (List<String>) -> Unit,
    ) {
        val group = findViewById<ChipGroup>(groupId)
        val terms = initial.toMutableList()
        fun render() {
            group.removeAllViews()
            for (term in terms) {
                group.addView(Chip(this).apply {
                    // Without this each chip reserves a 48dp-tall touch area, leaving big gaps between rows.
                    setEnsureMinTouchTargetSize(false)
                    text = term
                    isCloseIconVisible = true
                    setOnCloseIconClickListener {
                        if (keepOne && terms.size == 1) {
                            Toast.makeText(this@MainActivity, "Keep at least one search term", Toast.LENGTH_SHORT).show()
                        } else {
                            terms.remove(term)
                            save(terms)
                            render()
                        }
                    }
                })
            }
            group.addView(Chip(this).apply {
                setEnsureMinTouchTargetSize(false)
                text = "Add"
                setChipIconResource(R.drawable.ic_add)
                isChipIconVisible = true
                setOnClickListener {
                    askForTerms(title, example) { added ->
                        val fresh = added.filter { a -> terms.none { it.equals(a, ignoreCase = true) } }
                        if (fresh.isNotEmpty()) {
                            terms += fresh
                            save(terms)
                            render()
                        }
                    }
                }
            })
        }
        render()
    }

    private fun askForTerms(title: String, example: String, onAdd: (List<String>) -> Unit) {
        val layout = layoutInflater.inflate(R.layout.dialog_text, null) as TextInputLayout
        val input = layout.findViewById<TextInputEditText>(R.id.input)
        layout.hint = "e.g. $example"
        layout.helperText = "Separate several with commas"
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                onAdd(input.text.toString().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() })
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun bindSwitch(id: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<MaterialSwitch>(id).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
            // Switches with a summary sit in a tappable row; tapping the row flips the switch.
            (parent as? View)?.takeIf { it.isClickable }?.setOnClickListener { toggle() }
        }
    }

    private fun bindDropdown(id: Int, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        findViewById<MaterialAutoCompleteTextView>(id).apply {
            setSimpleItems(items.toTypedArray())
            setText(items[selected], false)
            setOnItemClickListener { _, _, position, _ -> onPick(position) }
        }
    }
}
