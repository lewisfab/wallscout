package dev.tristan.freshwall

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader

/** Brightness / saturation in percent (100 = unchanged), blur radius in screen pixels. */
data class Adjust(val brightness: Int = 100, val saturation: Int = 100, val blur: Int = 0) {
    val isNeutral get() = this == Adjust()

    companion object {
        val BRIGHTNESS = 50..150
        val SATURATION = 0..200
        val BLUR = 0..50
    }
}

/**
 * The same RenderEffect drives the live previews (on an ImageView) and the real wallpaper
 * (rendered off-screen on the GPU), so what you see in the app is what you get.
 */
object Effects {

    /** [blurScale] converts the blur radius from screen pixels to the target's pixels. */
    fun renderEffect(a: Adjust, blurScale: Float): RenderEffect? {
        val color = if (a.brightness != 100 || a.saturation != 100) {
            val m = ColorMatrix().apply { setSaturation(a.saturation / 100f) }
            val b = a.brightness / 100f
            m.postConcat(ColorMatrix().apply { setScale(b, b, b, 1f) })
            RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(m))
        } else {
            null
        }
        val radius = a.blur * blurScale
        val blur = if (radius >= 0.5f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null
        return if (color != null && blur != null) RenderEffect.createChainEffect(color, blur) else color ?: blur
    }

    /** Returns [src] with the adjustments applied (or [src] itself if there's nothing to do). */
    fun apply(src: Bitmap, a: Adjust): Bitmap {
        val effect = renderEffect(a, 1f) ?: return src
        val w = src.width
        val h = src.height
        val reader = ImageReader.newInstance(
            w, h, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            val node = RenderNode("wallpaper").apply {
                setPosition(0, 0, w, h)
                setRenderEffect(effect)
            }
            val canvas = node.beginRecording()
            canvas.drawBitmap(src, 0f, 0f, null)
            node.endRecording()
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()

            reader.acquireNextImage().use { image ->
                val buffer = checkNotNull(image.hardwareBuffer) { "No image from renderer" }
                buffer.use {
                    val hw = checkNotNull(Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)))
                    return hw.copy(Bitmap.Config.ARGB_8888, false)
                }
            }
        } finally {
            renderer.destroy()
            reader.close()
        }
    }
}
