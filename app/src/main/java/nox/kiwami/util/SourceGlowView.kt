package nox.kiwami.util

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils

class SourceGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var color = 0
    private var runner: ValueAnimator? = null

    fun setGlowColor(target: Int, withAnimation: Boolean) {
        runner?.cancel()
        if (!withAnimation || color == 0) {
            color = target
            invalidate()
            return
        }
        runner = ValueAnimator.ofArgb(color, target).apply {
            duration = 450
            addUpdateListener {
                color = it.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runner?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        if (color == 0 || width == 0) return
        val radius = 170f * density
        paint.shader = RadialGradient(
            46f * density,
            36f * density,
            radius,
            intArrayOf(ColorUtils.setAlphaComponent(color, 0x4D), ColorUtils.setAlphaComponent(color, 0)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
