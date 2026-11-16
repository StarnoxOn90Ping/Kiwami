package nox.kiwami.download.hub

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator

class DownloadArrowAnimator(
    private val icon: View,
    private val travelPx: Float
) {
    private var animator: ValueAnimator? = null

    val isRunning: Boolean get() = animator != null

    fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { icon.translationY = offsetFor(it.animatedValue as Float) }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        icon.translationY = 0f
    }

    private fun offsetFor(f: Float): Float = when {
        f < 0.38f -> {
            val t = f / 0.38f
            travelPx * t * t
        }
        f < 0.5f -> -travelPx
        f < 0.92f -> {
            val t = (f - 0.5f) / 0.42f
            -travelPx * (1f - easeOutBack(t))
        }
        else -> 0f
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.4f
        val c3 = c1 + 1f
        val u = t - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }
}
