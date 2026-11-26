package nox.kiwami.home

import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

object NavFade {

    private const val STOPS = 16
    private const val PEAK_ALPHA = 235

    fun apply(view: View) {
        val surface = MaterialColors.getColor(view, android.R.attr.colorBackground)
        val colors = IntArray(STOPS) { i ->
            val t = i / (STOPS - 1f)
            val eased = t * t * (3f - 2f * t)
            ColorUtils.setAlphaComponent(surface, (PEAK_ALPHA * eased).roundToInt())
        }
        view.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)
    }
}
