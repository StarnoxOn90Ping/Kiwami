package nox.kiwami.home

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt
import com.google.android.material.R as MaterialR

object OledNavStyle {

    private const val STROKE_ALPHA = 140
    private const val SPOT_GLOW_ALPHA = 110
    private const val AMBIENT_GLOW_ALPHA = 50
    private const val PILL_RADIUS_DP = 40f

    fun pill(view: View) = style(view, GradientDrawable.RECTANGLE, PILL_RADIUS_DP)

    fun circle(view: View) = style(view, GradientDrawable.OVAL, 0f)

    private fun style(view: View, shape: Int, radiusDp: Float) {
        val density = view.resources.displayMetrics.density
        val accent = MaterialColors.getColor(view, MaterialR.attr.colorPrimary)
        view.background = GradientDrawable().apply {
            this.shape = shape
            cornerRadius = radiusDp * density
            setColor(Color.BLACK)
            setStroke(
                density.roundToInt().coerceAtLeast(1),
                ColorUtils.setAlphaComponent(accent, STROKE_ALPHA)
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.outlineSpotShadowColor = ColorUtils.setAlphaComponent(accent, SPOT_GLOW_ALPHA)
            view.outlineAmbientShadowColor = ColorUtils.setAlphaComponent(accent, AMBIENT_GLOW_ALPHA)
        }
    }
}
