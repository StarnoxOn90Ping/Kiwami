package nox.kiwami.home

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import androidx.appcompat.R as AppCompatR
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

object NavGlow {

    private const val LAYERS = 5
    private const val SPREAD_DP = 14f
    private const val LAYER_ALPHA = 10

    private class GlowDrawable(layers: Array<Drawable>) : LayerDrawable(layers)

    fun wrap(view: View, shape: Int, radiusDp: Float) {
        val base = view.background ?: return
        if (base is GlowDrawable) return
        val density = view.resources.displayMetrics.density
        val accent = MaterialColors.getColor(view, AppCompatR.attr.colorPrimary)
        val color = ColorUtils.setAlphaComponent(accent, LAYER_ALPHA)
        val spreads = (LAYERS downTo 1).map { SPREAD_DP * it / LAYERS }
        val layers = ArrayList<Drawable>()
        spreads.forEach { spread ->
            layers += GradientDrawable().apply {
                this.shape = shape
                cornerRadius = (radiusDp + spread) * density
                setColor(color)
            }
        }
        layers += base
        val glow = GlowDrawable(layers.toTypedArray())
        spreads.forEachIndexed { i, spread ->
            val inset = -(spread * density).roundToInt()
            glow.setLayerInset(i, inset, inset, inset, inset)
        }
        view.background = glow
    }
}
