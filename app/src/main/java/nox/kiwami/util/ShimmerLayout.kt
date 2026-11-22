package nox.kiwami.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils

class ShimmerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val sweepPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }
    private val sweepMatrix = Matrix()
    private var shader: LinearGradient? = null
    private var shaderWidth = 0f
    private var sweep = 0f
    private var sweeper: ValueAnimator? = null
    private var fader: ValueAnimator? = null
    private var fadingTo: Boolean? = null

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (sweeper == null || w <= 0f) {
            super.dispatchDraw(canvas)
            return
        }
        val band = w * 0.6f
        if (shader == null || shaderWidth != band) {
            val highlight = ColorUtils.setAlphaComponent(resolve(com.google.android.material.R.attr.colorOnBackground), 0x30)
            shader = LinearGradient(
                0f, 0f, band, 0f,
                intArrayOf(Color.TRANSPARENT, highlight, Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            shaderWidth = band
            sweepPaint.shader = shader
        }
        sweepMatrix.setTranslate(-band + (w + band) * sweep, 0f)
        shader?.setLocalMatrix(sweepMatrix)
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, w, h, sweepPaint)
        canvas.restoreToCount(layer)
    }

    override fun setVisibility(visibility: Int) {
        val show = visibility == VISIBLE
        val current = fadingTo ?: (super.getVisibility() == VISIBLE)
        if (show == current || !isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            fader?.cancel()
            fader = null
            fadingTo = null
            alpha = 1f
            super.setVisibility(visibility)
            return
        }
        fade(show, visibility)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateSweep()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSweep()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        fader?.cancel()
        fader = null
        fadingTo = null
        alpha = 1f
        stopSweep()
    }

    private fun fade(show: Boolean, finalVisibility: Int) {
        val from = if (fader != null) alpha else if (show) 0f else 1f
        fader?.cancel()
        fadingTo = show
        if (show) super.setVisibility(VISIBLE)
        alpha = from
        val animation = ValueAnimator.ofFloat(from, if (show) 1f else 0f)
        animation.duration = if (show) 220L else 260L
        animation.addUpdateListener { alpha = it.animatedValue as Float }
        animation.addListener(object : AnimatorListenerAdapter() {
            private var canceled = false

            override fun onAnimationCancel(a: Animator) {
                canceled = true
            }

            override fun onAnimationEnd(a: Animator) {
                if (canceled) return
                fader = null
                fadingTo = null
                if (!show) super@ShimmerLayout.setVisibility(finalVisibility)
                alpha = 1f
            }
        })
        fader = animation
        animation.start()
    }

    private fun updateSweep() {
        if (isAttachedToWindow && isShown && ValueAnimator.areAnimatorsEnabled()) startSweep() else stopSweep()
    }

    private fun startSweep() {
        if (sweeper != null) return
        sweeper = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweep = (SystemClock.uptimeMillis() % 1500L) / 1500f
                invalidate()
            }
            start()
        }
    }

    private fun stopSweep() {
        sweeper?.cancel()
        sweeper = null
        invalidate()
    }

    private fun resolve(attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }
}
