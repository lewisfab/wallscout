package dev.tristan.freshwall

import android.graphics.Rect
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Header lockup: the round logo mark, then "Wall" in the normal title style and "scout" bold in peach.
 * The mark is sized from the title text and centred on its capitals, so it dips just below the baseline.
 */
object Logotype {
    private const val PEACH = 0xFFFF9466.toInt()
    private const val MARK_SIZE = 0.95f // x the title text size
    private const val MARK_GAP = 0.22f // em, between the mark and the wordmark

    fun apply(title: TextView, mark: ImageView) {
        val name = title.text.toString()
        val bold = name.indexOf("scout").takeIf { it > 0 }
        if (bold != null) {
            title.text = SpannableString(name).apply {
                setSpan(StyleSpan(Typeface.BOLD), bold, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(PEACH), bold, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        val em = title.textSize
        val size = (MARK_SIZE * em).roundToInt()
        val capHeight = Rect().also { title.paint.getTextBounds("H", 0, 1, it) }.let { -it.top.toFloat() }
        (mark.layoutParams as ViewGroup.MarginLayoutParams).apply {
            width = size
            height = size
            marginEnd = (MARK_GAP * em).roundToInt()
        }
        // Shares the row's baseline: the mark's centre lines up with the middle of the capitals.
        mark.baseline = (size / 2f + capHeight / 2).roundToInt()
        mark.requestLayout()
    }
}
