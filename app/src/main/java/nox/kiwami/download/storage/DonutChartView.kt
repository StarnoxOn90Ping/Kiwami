package nox.kiwami.download.storage

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val strokeWidthPx = 16f * density
    private val bounds = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
    }
    private var segments: List<Pair<Long, Int>> = emptyList()
    private var trackColor = 0
    private var sweep = 0f
    private var entranceDone = false
    private var animator: ValueAnimator? = null

    fun setData(values: List<Pair<Long, Int>>, track: Int) {
        segments = values
        trackColor = track
        val hasData = values.sumOf { it.first } > 0
        if (hasData && !entranceDone) {
            entranceDone = true
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    sweep = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (animator?.isRunning != true) {
            sweep = if (hasData) 1f else 0f
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = strokeWidthPx / 2f + 2f * density
        bounds.set(inset, inset, width - inset, height - inset)
        paint.color = trackColor
        paint.alpha = 90
        canvas.drawArc(bounds, 0f, 360f, false, paint)
        paint.alpha = 255

        val visible = segments.filter { it.first > 0 }
        val total = visible.sumOf { it.first }.toFloat()
        if (total <= 0f) return

        val radius = bounds.width() / 2f
        val gap = if (visible.size > 1) {
            Math.toDegrees((strokeWidthPx / radius).toDouble()).toFloat() + 6f
        } else {
            0f
        }
        val available = 360f - gap * visible.size
        var start = -90f + gap / 2f
        for ((value, color) in visible) {
            val full = value / total * available
            paint.color = color
            canvas.drawArc(bounds, start, (full * sweep).coerceAtLeast(0.1f), false, paint)
            start += full + gap
        }
    }
}
