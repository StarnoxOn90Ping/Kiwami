package nox.kiwami.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.AutoCompleteTextView
import androidx.cardview.widget.CardView

object OledSurface {

    private const val OUTLINE = 0x26808080

    fun drawable(context: Context, radiusDp: Float): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = radiusDp * density
            setColor(Color.BLACK)
            setStroke(density.toInt().coerceAtLeast(1), OUTLINE)
        }
    }

    fun view(target: View, radiusDp: Float) {
        if (!ThemeSurface.isOled(target.context)) return
        target.background = drawable(target.context, radiusDp)
    }

    fun dropdown(target: AutoCompleteTextView) {
        if (!ThemeSurface.isOled(target.context)) return
        target.setDropDownBackgroundDrawable(drawable(target.context, 16f))
    }

    fun card(target: CardView, radiusDp: Float) {
        if (!ThemeSurface.isOled(target.context)) return
        target.setCardBackgroundColor(Color.BLACK)
        (target.getChildAt(0) as? View)?.background = drawable(target.context, radiusDp)
    }
}
