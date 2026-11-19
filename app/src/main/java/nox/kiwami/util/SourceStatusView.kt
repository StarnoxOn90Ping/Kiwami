package nox.kiwami.util

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils

class SourceStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { NONE, SPIN, CHECK, WARN }

    private val density = resources.displayMetrics.density
    private val stroke = 2f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bounds = RectF()
    private val checkPath = Path()
    private val partial = Path()
    private val measure = PathMeasure()
    private val primary = resolve(androidx.appcompat.R.attr.colorPrimary)
    private var mode = Mode.NONE
    private var spin = 0f
    private var drawn = 1f
    private var spinner: ValueAnimator? = null
    private var drawer: ValueAnimator? = null

    fun setMode(next: Mode, withAnimation: Boolean) {
        if (next == mode) return
        mode = next
        spinner?.cancel()
        spinner = null
        drawer?.cancel()
        drawn = 1f
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
        when (next) {
            Mode.SPIN -> startSpin()
            Mode.CHECK -> if (withAnimation) {
                drawn = 0f
                drawer = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 380
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        drawn = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
                scaleX = 0.6f
                scaleY = 0.6f
                animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(3f)).start()
            }

            Mode.WARN -> if (withAnimation) {
                drawer = ValueAnimator.ofFloat(1f, 0.35f).apply {
                    duration = 700
                    repeatCount = 3
                    repeatMode = ValueAnimator.REVERSE
                    addUpdateListener {
                        alpha = it.animatedValue as Float
                    }
                    start()
                }
            }

            Mode.NONE -> Unit
        }
        invalidate()
    }

    private fun startSpin() {
        if (spinner != null || !isAttachedToWindow) return
        spinner = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                spin = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mode == Mode.SPIN) startSpin()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        spinner?.cancel()
        spinner = null
        drawer?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val inset = stroke
        bounds.set(inset, inset, w - inset, h - inset)
        when (mode) {
            Mode.SPIN -> {
                paint.color = ColorUtils.setAlphaComponent(primary, 0x33)
                canvas.drawOval(bounds, paint)
                paint.color = primary
                canvas.drawArc(bounds, spin, 260f, false, paint)
            }

            Mode.CHECK -> {
                paint.color = 0xFF7BD88F.toInt()
                checkPath.reset()
                checkPath.moveTo(w * 0.18f, h * 0.54f)
                checkPath.lineTo(w * 0.42f, h * 0.76f)
                checkPath.lineTo(w * 0.82f, h * 0.28f)
                measure.setPath(checkPath, false)
                partial.reset()
                measure.getSegment(0f, measure.length * drawn, partial, true)
                canvas.drawPath(partial, paint)
            }

            Mode.WARN -> {
                paint.color = 0xFFFFB020.toInt()
                canvas.drawOval(bounds, paint)
                canvas.drawLine(w / 2f, h * 0.3f, w / 2f, h * 0.55f, paint)
                canvas.drawPoint(w / 2f, h * 0.72f, paint)
            }

            Mode.NONE -> Unit
        }
    }

    private fun resolve(attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }
}
