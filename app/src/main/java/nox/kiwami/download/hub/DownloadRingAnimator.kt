package nox.kiwami.download.hub

import android.animation.ValueAnimator
import android.os.SystemClock
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlin.math.exp
import kotlin.math.roundToInt

class DownloadRingAnimator(private val ring: CircularProgressIndicator) {
    private var animator: ValueAnimator? = null
    private var target = 0f
    private var shown = 0f
    private var lastFrame = 0L
    private var finishing = false
    private var onFinished: (() -> Unit)? = null

    fun start() {
        finishing = false
        onFinished = null
        if (animator != null) return
        lastFrame = SystemClock.uptimeMillis()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { onFrame() }
            start()
        }
    }

    fun setPercent(percent: Int) {
        if (finishing) return
        target = percent.coerceIn(0, 100) * SCALE
    }

    fun finish(done: () -> Unit) {
        if (animator == null || finishing) return
        finishing = true
        target = MAX
        onFinished = done
    }

    fun reset() {
        animator?.cancel()
        animator = null
        finishing = false
        onFinished = null
        shown = 0f
        target = 0f
        ring.progress = 0
    }

    private fun onFrame() {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrame).coerceIn(0L, 64L)
        lastFrame = now
        val tau = if (finishing) FINISH_TAU else FOLLOW_TAU
        shown += (target - shown) * (1f - exp(-dt / tau))
        ring.progress = shown.roundToInt().coerceIn(0, MAX.toInt())
        if (finishing && shown >= MAX - 6f) {
            ring.progress = MAX.toInt()
            animator?.cancel()
            animator = null
            val callback = onFinished
            onFinished = null
            callback?.invoke()
        }
    }

    private companion object {
        const val SCALE = 10f
        const val MAX = 1000f
        const val FOLLOW_TAU = 380f
        const val FINISH_TAU = 110f
    }
}
