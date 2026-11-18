package nox.kiwami.util

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils

class CommentCounterRing @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val stroke = 2.5f * resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val bounds = RectF()
    private val primary = resolve(androidx.appcompat.R.attr.colorPrimary)
    private val onBackground = resolve(com.google.android.material.R.attr.colorOnBackground)
    private var shown = 0f
    private var goal = 0f
    private var runner: ValueAnimator? = null

    fun setProgress(value: Float, animate: Boolean) {
        goal = value.coerceIn(0f, 1f)
        runner?.cancel()
        if (!animate) {
            shown = goal
            invalidate()
            return
        }
        runner = ValueAnimator.ofFloat(shown, goal).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shown = it.animatedValue as Float
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
        if (shown <= 0.001f) return
        val inset = stroke / 2f
        bounds.set(inset, inset, width - inset, height - inset)
        track.color = ColorUtils.setAlphaComponent(onBackground, 0x24)
        canvas.drawOval(bounds, track)
        arc.color = when {
            goal >= 1f -> 0xFFE5484D.toInt()
            goal >= 0.9f -> 0xFFFFB020.toInt()
            else -> primary
        }
        canvas.drawArc(bounds, -90f, 360f * shown, false, arc)
    }

    private fun resolve(attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }
}
