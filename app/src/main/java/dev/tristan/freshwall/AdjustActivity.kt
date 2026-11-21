package dev.tristan.freshwall

import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/** Brightness / saturation / blur sliders with live previews. */
class AdjustActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var previews: PreviewPanel
    private lateinit var separate: MaterialSwitch

    /** Slider values being edited, by scope ("both", "home", "lock"); saved on Save. */
    private val edits = mutableMapOf<String, Adjust>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adjust)
        prefs = Prefs(this)
        previews = PreviewPanel(this, prefs)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }

        for (scope in listOf("both", "home", "lock")) edits[scope] = prefs.adjust(scope)
        separate = findViewById(R.id.separateAdjust)
        separate.isChecked = prefs.separateAdjust
        separate.setOnCheckedChangeListener { _, checked ->
            // Start the per-screen sliders from the shared values.
            if (checked) edits["home"] = edits.getValue("both").also { edits["lock"] = it }
            buildSliders()
            showPreviews()
        }
        buildSliders()

        findViewById<View>(R.id.resetAdjust).setOnClickListener {
            edits.keys.forEach { edits[it] = Adjust() }
            buildSliders()
            previews.updateEffects(::currentAdjust)
        }
        findViewById<View>(R.id.save).setOnClickListener {
            prefs.separateAdjust = separate.isChecked
            edits.forEach { (scope, a) -> prefs.setAdjust(scope, a) }
            // Apply to the current wallpaper right away.
            if (prefs.adjustSignature() != prefs.appliedAdjust) Scheduler.reapply(this)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        showPreviews()
    }

    private fun showPreviews() = previews.show(separate.isChecked, ::currentAdjust)

    private fun currentAdjust(preview: Preview) = edits.getValue(if (separate.isChecked) preview.scope else "both")

    private fun buildSliders() {
        val container = findViewById<LinearLayout>(R.id.adjustContainer)
        container.removeAllViews()
        val scopes = if (separate.isChecked) listOf("home" to "Home screen", "lock" to "Lock screen") else listOf("both" to null)
        for ((scope, title) in scopes) {
            if (title != null) {
                container.addView(TextView(this).apply {
                    text = title
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                    setPadding(0, dp(16), 0, 0)
                })
            }
            val a = edits.getValue(scope)
            addSlider(container, "Brightness", Adjust.BRIGHTNESS, 5, a.brightness, "%") { v ->
                edits[scope] = edits.getValue(scope).copy(brightness = v)
            }
            addSlider(container, "Saturation", Adjust.SATURATION, 5, a.saturation, "%") { v ->
                edits[scope] = edits.getValue(scope).copy(saturation = v)
            }
            addSlider(container, "Blur", Adjust.BLUR, 1, a.blur, "") { v ->
                edits[scope] = edits.getValue(scope).copy(blur = v)
            }
        }
    }

    private fun addSlider(
        container: LinearLayout,
        name: String,
        range: IntRange,
        step: Int,
        value: Int,
        unit: String,
        onChange: (Int) -> Unit,
    ) {
        // The slider only accepts values on its step grid, so snap saved values onto it.
        val start = range.first + ((value.coerceIn(range) - range.first + step / 2) / step) * step
        val label = TextView(this).apply {
            text = "$name: $start$unit"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setPadding(0, dp(12), 0, 0)
        }
        val slider = Slider(this).apply {
            valueFrom = range.first.toFloat()
            valueTo = range.last.toFloat()
            stepSize = step.toFloat()
            isTickVisible = false
            this.value = start.toFloat()
            setLabelFormatter { "${it.toInt()}$unit" }
            addOnChangeListener { _, v, _ ->
                label.text = "$name: ${v.toInt()}$unit"
                onChange(v.toInt())
                previews.updateEffects(::currentAdjust)
            }
        }
        container.addView(label)
        container.addView(slider)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
