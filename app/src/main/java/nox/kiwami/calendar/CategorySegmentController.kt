package nox.kiwami.calendar

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.updateLayoutParams
import nox.kiwami.getThemeColor
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

class CategorySegmentController(
    private val segment: View,
    private val thumb: View,
    private val tabs: List<TextView>,
    initial: Int,
    private val onTabClick: (Int) -> Unit
) {
    private val density = segment.resources.displayMetrics.density
    private val inset = 4 * density
    private val damping = 2f * DAMPING_RATIO * sqrt(STIFFNESS)

    private var position = initial.toFloat()
    private var velocity = 0f
    private var target = initial
    private var running = false
    private var lastFrameNanos = 0L
    private var colorOn = 0
    private var colorOff = 0

    private val frame = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.034f)
            lastFrameNanos = now
            var left = dt
            val goal = target.toFloat()
            while (left > 0f) {
                val h = min(left, SUB_STEP)
                velocity += (STIFFNESS * (goal - position) - damping * velocity) * h
                position += velocity * h
                left -= h
            }
            if (abs(goal - position) < 0.0008f && abs(velocity) < 0.01f) {
                running = false
                position = goal
                velocity = 0f
                render()
            } else {
                render()
                segment.postOnAnimation(this)
            }
        }
    }

    init {
        tabs.forEachIndexed { index, tab -> tab.setOnClickListener { onTabClick(index) } }
        segment.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                layoutThumb()
                render()
            }
        }
        segment.post {
            colorOn = segment.context.getThemeColor(com.google.android.material.R.attr.colorOnPrimary)
            colorOff = segment.context.getThemeColor(com.google.android.material.R.attr.colorOutline)
            layoutThumb()
            render()
        }
    }

    fun animateTo(category: Int, initialVelocity: Float = 0f) {
        target = category
        velocity = initialVelocity
        if (running) return
        running = true
        lastFrameNanos = System.nanoTime()
        segment.postOnAnimation(frame)
    }

    fun drag(pos: Float) {
        segment.removeCallbacks(frame)
        running = false
        velocity = 0f
        position = pos
        render()
    }

    fun cancel() {
        segment.removeCallbacks(frame)
        running = false
    }

    private fun thumbWidth(): Float = (segment.width - inset * 2) / (tabs.size.toFloat())

    private fun layoutThumb() {
        val w = thumbWidth()
        if (w <= 0f) return
        thumb.updateLayoutParams<ViewGroup.LayoutParams> { width = w.toInt() }
    }

    private fun render() {
        val w = thumbWidth()
        if (w <= 0f || colorOn == 0) return
        val clamped = position.coerceIn(-0.2f, tabs.size - 1f + 0.2f)
        val stretch = min(abs(velocity) * 0.16f, 0.6f)
        thumb.pivotX = if (velocity >= 0f) 0f else thumb.width.toFloat()
        thumb.pivotY = thumb.height / 2f
        thumb.translationX = clamped * w
        thumb.scaleX = 1f + stretch
        thumb.scaleY = 1f - stretch * 0.16f

        tabs.forEachIndexed { i, tab ->
            val near = (1f - abs(position - i)).coerceIn(0f, 1f)
            val eased = near * near * (3f - 2f * near)
            tab.setTextColor(ColorUtils.blendARGB(colorOff, colorOn, eased))
            val scale = 1f + 0.1f * eased
            tab.scaleX = scale
            tab.scaleY = scale
            tab.translationY = -1.5f * density * eased
            tab.alpha = 0.7f + 0.3f * eased
        }
    }

    private companion object {
        const val STIFFNESS = 420f
        const val DAMPING_RATIO = 0.6f
        const val SUB_STEP = 1f / 240f
    }
}
