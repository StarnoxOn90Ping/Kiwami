package nox.kiwami.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

object SourceVisuals {

    fun colorFor(name: String, icon: Drawable?): Int {
        val sampled = icon?.let { averageColor(it) }
        if (sampled != null) {
            val hsv = FloatArray(3)
            Color.colorToHSV(sampled, hsv)
            hsv[1] = maxOf(hsv[1], 0.5f)
            hsv[2] = maxOf(hsv[2], 0.85f)
            return Color.HSVToColor(hsv)
        }
        val hue = ((name.lowercase().hashCode() and 0x7fffffff) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.95f))
    }

    fun darker(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = hsv[2] * 0.62f
        return Color.HSVToColor(hsv)
    }

    fun letterBadge(color: Int, radius: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(color, darker(color))).apply {
            cornerRadius = radius
        }

    private fun averageColor(drawable: Drawable): Int? {
        val size = 16
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val saved = Rect(drawable.bounds)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        drawable.setBounds(saved.left, saved.top, saved.right, saved.bottom)
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.recycle()
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (pixel in pixels) {
            if (Color.alpha(pixel) > 40) {
                r += Color.red(pixel)
                g += Color.green(pixel)
                b += Color.blue(pixel)
                n++
            }
        }
        if (n == 0) return null
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
