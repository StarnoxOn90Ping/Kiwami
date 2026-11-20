package nox.kiwami.calendar

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import nox.kiwami.R
import nox.kiwami.databinding.ItemCalendarDayBinding
import nox.kiwami.getThemeColor
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class DayStripController(
    private val scroll: HorizontalScrollView,
    private val strip: LinearLayout,
    private val track: LinearLayout,
    private val indicator: View,
    private val onDayClick: (Int) -> Unit
) {
    private val density = scroll.resources.displayMetrics.density
    private val cellWidth = 54 * density
    private val step = 62 * density
    private val weekdayFormat = SimpleDateFormat("EEE", Locale.getDefault())
    private val numberFormat = SimpleDateFormat("d", Locale.getDefault())
    private val damping = 2f * DAMPING_RATIO * sqrt(STIFFNESS)

    private var cells: List<ItemCalendarDayBinding> = emptyList()
    private var fractions = FloatArray(0)
    private var todayIndex = -1
    private var colorPrimary = 0
    private var colorOnPrimary = 0
    private var colorOnSurface = 0

    private var position = 0f
    private var velocity = 0f
    private var target = -1
    private var hasSelection = false
    private var running = false
    private var lastFrameNanos = 0L
    private var followX = 0f
    private var lastSetX = 0
    private var followPaused = false

    private val frame = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.034f)
            lastFrameNanos = now
            val springSettled = stepSpring(dt)
            render()
            val followSettled = followScroll(dt)
            if (springSettled && followSettled) {
                running = false
                position = target.toFloat()
                velocity = 0f
                render()
            } else {
                scroll.postOnAnimation(this)
            }
        }
    }

    init {
        scroll.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) followPaused = true
            false
        }
    }

    fun setDays(days: List<Long>, counts: List<Int>, todayStart: Long) {
        cancel()
        val context = scroll.context
        colorPrimary = context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
        colorOnPrimary = context.getThemeColor(com.google.android.material.R.attr.colorOnPrimary)
        colorOnSurface = context.getThemeColor(com.google.android.material.R.attr.colorOnSurface)

        strip.removeAllViews()
        track.removeAllViews()
        val inflater = LayoutInflater.from(context)
        cells = days.mapIndexed { index, day ->
            val cell = ItemCalendarDayBinding.inflate(inflater, strip, false)
            cell.dayWeekday.text = weekdayFormat.format(day)
            cell.dayNumber.text = numberFormat.format(day)
            cell.dayDots.text = "•".repeat((counts.getOrNull(index) ?: 0).coerceAtMost(3))
            cell.root.setOnClickListener { onDayClick(index) }
            strip.addView(cell.root)
            track.addView(
                View(context).apply {
                    layoutParams = LinearLayout.LayoutParams((54 * density).toInt(), (80 * density).toInt()).apply {
                        marginEnd = (8 * density).toInt()
                    }
                    setBackgroundResource(R.drawable.bg_day_cell)
                }
            )
            cell
        }
        todayIndex = days.indexOf(todayStart)
        fractions = FloatArray(cells.size)
        cells.indices.forEach { style(it, 0f) }

        hasSelection = false
        target = -1
        position = 0f
        velocity = 0f
        followPaused = false
        indicator.pivotX = cellWidth / 2f
        indicator.pivotY = 40 * density
        indicator.visibility = View.INVISIBLE
    }

    fun select(index: Int) {
        if (index !in cells.indices) return
        if (hasSelection && index == target) return
        val first = !hasSelection
        hasSelection = true
        target = index
        followPaused = false
        if (first) {
            position = index.toFloat()
            velocity = 0f
            indicator.visibility = View.VISIBLE
            render()
            snapScroll()
            return
        }
        followX = scroll.scrollX.toFloat()
        lastSetX = scroll.scrollX
        start()
    }

    fun cancel() {
        scroll.removeCallbacks(frame)
        running = false
    }

    private fun start() {
        if (running) return
        running = true
        lastFrameNanos = System.nanoTime()
        scroll.postOnAnimation(frame)
    }

    private fun stepSpring(dt: Float): Boolean {
        val goal = target.toFloat()
        var left = dt
        while (left > 0f) {
            val h = min(left, SUB_STEP)
            velocity += (STIFFNESS * (goal - position) - damping * velocity) * h
            position += velocity * h
            left -= h
        }
        return abs(goal - position) < 0.0015f && abs(velocity) < 0.02f
    }

    private fun render() {
        val stretch = min(abs(velocity) * 0.05f, 0.5f)
        indicator.translationX = position * step
        indicator.scaleX = 1f + stretch
        indicator.scaleY = 1f - stretch * 0.12f
        for (i in cells.indices) {
            val raw = (1f - abs(i - position)).coerceIn(0f, 1f)
            val f = raw * raw * (3f - 2f * raw)
            val previous = fractions[i]
            if (f != previous && (abs(f - previous) > 0.002f || f == 0f || f == 1f)) style(i, f)
        }
    }

    private fun style(i: Int, f: Float) {
        val cell = cells.getOrNull(i) ?: return
        fractions[i] = f
        val idleNumber = if (i == todayIndex) colorPrimary else colorOnSurface
        cell.dayWeekday.setTextColor(ColorUtils.blendARGB(colorOnSurface, colorOnPrimary, f))
        cell.dayNumber.setTextColor(ColorUtils.blendARGB(idleNumber, colorOnPrimary, f))
        cell.dayDots.setTextColor(ColorUtils.blendARGB(colorPrimary, colorOnPrimary, f))
        cell.dayWeekday.alpha = 0.6f + 0.3f * f
        val numberScale = 1f + 0.14f * f
        cell.dayNumber.scaleX = numberScale
        cell.dayNumber.scaleY = numberScale
        val dotScale = 1f + 0.2f * f
        cell.dayDots.scaleX = dotScale
        cell.dayDots.scaleY = dotScale
        cell.root.translationY = -2f * density * f
    }

    private fun desiredScroll(pos: Float): Float? {
        val content = scroll.getChildAt(0) ?: return null
        if (scroll.width == 0 || content.width == 0) return null
        val center = scroll.paddingLeft + pos * step + cellWidth / 2f
        val max = (content.width + scroll.paddingLeft + scroll.paddingRight - scroll.width).coerceAtLeast(0)
        return (center - scroll.width / 2f).coerceIn(0f, max.toFloat())
    }

    private fun snapScroll() {
        val desired = desiredScroll(position)
        if (desired == null) {
            scroll.post { snapScroll() }
            return
        }
        scroll.scrollTo(desired.roundToInt(), 0)
        followX = scroll.scrollX.toFloat()
        lastSetX = scroll.scrollX
    }

    private fun followScroll(dt: Float): Boolean {
        if (followPaused) return true
        val desired = desiredScroll(position) ?: return true
        if (abs(scroll.scrollX - lastSetX) > 1.5f) {
            followPaused = true
            return true
        }
        followX += (desired - followX) * (1f - exp(-dt * FOLLOW_RATE))
        val x = followX.roundToInt()
        if (x != scroll.scrollX) scroll.scrollTo(x, 0)
        lastSetX = scroll.scrollX
        return abs(desired - followX) < 0.5f
    }

    private companion object {
        const val STIFFNESS = 320f
        const val DAMPING_RATIO = 0.78f
        const val SUB_STEP = 1f / 240f
        const val FOLLOW_RATE = 16f
    }
}
