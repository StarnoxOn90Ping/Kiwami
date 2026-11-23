package nox.kiwami.util

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import nox.kiwami.px

class PagerDots @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val dotSize = 6.px
    private val activeWidth = 18.px
    private val gap = 6.px
    private val activeColor =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.WHITE)
    private val idleColor = ColorUtils.setAlphaComponent(
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnBackground, Color.WHITE),
        90
    )
    private var selectedIndex = -1

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun setCount(count: Int) {
        removeAllViews()
        selectedIndex = -1
        repeat(count) { index ->
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dotSize.toFloat()
                    setColor(idleColor)
                }
            }
            addView(dot, LayoutParams(dotSize, dotSize).apply {
                if (index > 0) marginStart = gap
            })
        }
        if (count > 0) select(0)
    }

    fun select(index: Int) {
        if (index == selectedIndex || index !in 0 until childCount) return
        val previous = selectedIndex
        selectedIndex = index
        if (previous in 0 until childCount) transition(getChildAt(previous), activeWidth, dotSize, activeColor, idleColor)
        transition(getChildAt(index), dotSize, activeWidth, idleColor, activeColor)
    }

    private fun transition(dot: View, fromWidth: Int, toWidth: Int, fromColor: Int, toColor: Int) {
        val background = dot.background as GradientDrawable
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            addUpdateListener {
                val fraction = it.animatedFraction
                dot.layoutParams = dot.layoutParams.apply {
                    width = fromWidth + ((toWidth - fromWidth) * fraction).toInt()
                }
                background.setColor(ColorUtils.blendARGB(fromColor, toColor, fraction))
            }
            doOnEnd { background.setColor(toColor) }
            start()
        }
    }
}
