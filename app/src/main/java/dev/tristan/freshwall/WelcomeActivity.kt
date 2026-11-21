package dev.tristan.freshwall

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * First launch only: pick a few topics (the search terms), then get the first wallpaper.
 * Skipping keeps the default terms. Either way it opens the main screen and never shows again.
 */
class WelcomeActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private val picked = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)
        prefs = Prefs(this)

        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        Logotype.apply(findViewById(R.id.title), findViewById(R.id.logoMark))

        val group = findViewById<ChipGroup>(R.id.topics)
        for (topic in TOPICS) group.addView(topicChip(topic))
        group.addView(Chip(this).apply {
            setEnsureMinTouchTargetSize(false)
            text = "Add your own"
            setChipIconResource(R.drawable.ic_add)
            isChipIconVisible = true
            setOnClickListener { askForTopics(group) }
        })

        findViewById<View>(R.id.start).setOnClickListener {
            prefs.setSearchTerms(picked.toList())
            prefs.status = Prefs.WORKING
            Scheduler.runNow(this)
            done()
        }
        findViewById<View>(R.id.skip).setOnClickListener { done() }
        onBackPressedDispatcher.addCallback(this) { done() }
    }

    private fun topicChip(topic: String, checked: Boolean = false) =
        (layoutInflater.inflate(R.layout.chip_filter, null, false) as Chip).apply {
            setEnsureMinTouchTargetSize(false)
            text = topic
            isCheckable = true
            isChecked = checked
            setOnCheckedChangeListener { _, on ->
                if (on) picked += topic else picked -= topic
                updateStart()
            }
            if (checked) picked += topic
        }

    private fun updateStart() {
        findViewById<MaterialButton>(R.id.start).apply {
            isEnabled = picked.isNotEmpty()
            text = if (picked.isEmpty()) "Pick at least one topic" else "Get my first wallpaper"
        }
    }

    /** Adds the typed topics as ticked chips, just before the "Add your own" chip. */
    private fun askForTopics(group: ChipGroup) {
        val layout = layoutInflater.inflate(R.layout.dialog_text, null) as TextInputLayout
        val input = layout.findViewById<TextInputEditText>(R.id.input)
        layout.hint = "e.g. lighthouse"
        layout.helperText = "Separate several with commas"
        MaterialAlertDialogBuilder(this)
            .setTitle("Add your own")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val existing = (0 until group.childCount).map { (group.getChildAt(it) as Chip).text.toString().lowercase() }
                input.text.toString().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                    .distinctBy { it.lowercase() }
                    .filter { it.lowercase() !in existing }
                    .forEach { group.addView(topicChip(it, checked = true), group.childCount - 1) }
                updateStart()
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun done() {
        prefs.welcomeDone = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private val TOPICS = listOf(
            "space", "nebula", "milky way", "aurora", "mountains", "ocean", "forest",
            "waterfall", "desert", "snow", "sunset", "cities", "architecture",
        )

        /** Fresh installs only: not shown to anyone who already has a wallpaper or history. */
        fun shouldShow(prefs: Prefs, hasWallpaper: Boolean) =
            !prefs.welcomeDone && !hasWallpaper && prefs.used.isEmpty()
    }
}
