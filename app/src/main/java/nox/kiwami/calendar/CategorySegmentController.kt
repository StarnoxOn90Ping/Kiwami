package nox.kiwami.calendar

import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import nox.kiwami.R
import nox.kiwami.getThemeColor
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class CategorySegmentController(
    private val segment: View,
    private val thumb: View,
    private val tabs: List<TextView>,
    initial: Int,
    private val onTabClick: (Int) -> Unit
) {
    private val inset = 4 * segment.resources.displayMetrics.density
    private val count = tabs.size
    private val regular: Typeface? = ResourcesCompat.getFont(segment.context, R.font.poppins)
    private val bold: Typeface? = ResourcesCompat.getFont(segment.context, R.font.poppins_bold)

    private var left = initial.toFloat()
    private var right = initial + 1f
    private var leftVelocity = 0f
    private var rightVelocity = 0f
    private var leftStiffness = TRAIL_STIFFNESS
    private var rightStiffness = LEAD_STIFFNESS
    private var target = initial
    private var running = false
    private var lastFrameNanos = 0L
    private var colorOn = 0
    private var colorOff = 0
    private var emphasised = -1

    private val frame = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.034f)
            lastFrameNanos = now
            var remaining = dt
            while (remaining > 0f) {
                val h = min(remaining, SUB_STEP)
                integrate(h)
                remaining -= h
            }
            val settled = abs(left - target) < 0.002f && abs(right - (target + 1f)) < 0.002f &&
                    abs(leftVelocity) < 0.02f && abs(rightVelocity) < 0.02f
            if (settled) {
                running = false
                left = target.toFloat()
                right = target + 1f
                leftVelocity = 0f
                rightVelocity = 0f
                render()
            } else {
                render()
                segment.postOnAnimation(this)
            }
        }
    }

    init {
        tabs.forEachIndexed { index, tab -> tab.setOnClickListener { onTabClick(index) } }
        segment.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) render()
        }
        segment.post {
            colorOn = segment.context.getThemeColor(com.google.android.material.R.attr.colorOnPrimary)
            colorOff = segment.context.getThemeColor(com.google.android.material.R.attr.colorOutline)
            render()
        }
    }

    fun animateTo(category: Int) {
        target = category.coerceIn(0, count - 1)
        val forward = target > left
        val backward = target < left
        rightStiffness = if (backward) TRAIL_STIFFNESS else LEAD_STIFFNESS
        leftStiffness = if (forward) TRAIL_STIFFNESS else LEAD_STIFFNESS
        if (running) return
        running = true
        lastFrameNanos = System.nanoTime()
        segment.postOnAnimation(frame)
    }

    fun drag(position: Float) {
        segment.removeCallbacks(frame)
        running = false
        val p = position.coerceIn(0f, (count - 1).toFloat())
        left = p
        right = p + 1f
        leftVelocity = 0f
        rightVelocity = 0f
        render()
    }

    fun cancel() {
        segment.removeCallbacks(frame)
        running = false
    }

    private fun integrate(h: Float) {
        val leftGoal = target.toFloat()
        val rightGoal = target + 1f
        leftVelocity += (leftStiffness * (leftGoal - left) - 2f * sqrt(leftStiffness) * leftVelocity) * h
        rightVelocity += (rightStiffness * (rightGoal - right) - 2f * sqrt(rightStiffness) * rightVelocity) * h
        left = (left + leftVelocity * h).coerceIn(0f, count.toFloat())
        right = (right + rightVelocity * h).coerceIn(0f, count.toFloat())
        if (right - left < MIN_WIDTH) {
            val mid = (left + right) / 2f
            left = mid - MIN_WIDTH / 2f
            right = mid + MIN_WIDTH / 2f
        }
    }

    private fun render() {
        val cell = (segment.width - inset * 2) / count
        if (cell <= 0f || colorOn == 0) return

        thumb.translationX = left * cell
        val width = ((right - left) * cell).roundToInt()
        val params = thumb.layoutParams
        if (params.width != width) {
            params.width = width
            thumb.layoutParams = params
        }

        val centre = ((left + right) / 2f).toInt().coerceIn(0, count - 1)
        tabs.forEachIndexed { i, tab ->
            val overlap = (min(right, i + 1f) - max(left, i.toFloat())).coerceIn(0f, 1f)
            val eased = overlap * overlap * (3f - 2f * overlap)
            tab.setTextColor(ColorUtils.blendARGB(colorOff, colorOn, eased))
            tab.alpha = 0.7f + 0.3f * eased
        }
        if (centre != emphasised) {
            emphasised = centre
            tabs.forEachIndexed { i, tab -> tab.typeface = if (i == centre) bold else regular }
        }
    }

    private companion object {
        const val LEAD_STIFFNESS = 900f
        const val TRAIL_STIFFNESS = 240f
        const val MIN_WIDTH = 0.8f
        const val SUB_STEP = 1f / 240f
    }
}
