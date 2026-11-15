package nox.kiwami.media.anime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import java.util.WeakHashMap

internal object EpisodeWatchAnimator {

    private const val WIPE_MS = 560L
    private const val BURST_MS = 760L
    private const val BURST_DELAY_MS = 170L
    private const val STAGGER_MS = 45L
    private const val MAX_STAGGER_MS = 450L
    private const val BATCH_WINDOW_MS = 150L
    private const val MAX_FANCY_BATCH = 8

    private val running = WeakHashMap<View, () -> Unit>()
    private var lastStart = 0L
    private var batchIndex = 0

    fun cancel(card: View) {
        running.remove(card)?.invoke()
    }

    /** Returns the delay the caller should give any extra animation of the same tile. */
    fun markWatched(card: ViewGroup, cover: View, anchor: View?): Long {
        cancel(card)

        val now = SystemClock.uptimeMillis()
        batchIndex = if (now - lastStart < BATCH_WINDOW_MS) batchIndex + 1 else 0
        lastStart = now
        val index = batchIndex
        val stagger = (index * STAGGER_MS).coerceAtMost(MAX_STAGGER_MS)
        val fancy = index < MAX_FANCY_BATCH && card.width > 0

        cover.pivotX = 0f
        cover.scaleX = 0f
        cover.visibility = View.VISIBLE

        val animators = ArrayList<Animator>(3)
        var pending = 0
        val accent = MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary, Color.CYAN)
        val onAccent = MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)

        val edgeWidth = (36 * card.resources.displayMetrics.density).toInt()
        val edge = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.TRANSPARENT, ColorUtils.setAlphaComponent(accent, 150), Color.TRANSPARENT)
        )
        val burst = if (fancy) CheckBurst(card, anchor, accent, onAccent) else null
        if (fancy) card.overlay.add(edge)
        burst?.let { card.overlay.add(it) }

        val cleanup = {
            animators.forEach {
                it.removeAllListeners()
                it.cancel()
            }
            card.overlay.remove(edge)
            burst?.let { card.overlay.remove(it) }
            cover.scaleX = 1f
            card.animate().cancel()
            card.animate().setStartDelay(0)
            card.scaleX = 1f
            card.scaleY = 1f
        }
        running[card] = cleanup

        fun track(animator: ValueAnimator) {
            pending++
            animators.add(animator)
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (--pending == 0) {
                        running.remove(card)
                        cleanup()
                    }
                }
            })
            animator.start()
        }

        track(ValueAnimator.ofFloat(0f, 1f).apply {
            duration = WIPE_MS
            startDelay = stagger
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
                cover.scaleX = f
                if (fancy) {
                    val x = (f * (card.width + edgeWidth) - edgeWidth).toInt()
                    edge.setBounds(x, 0, x + edgeWidth, card.height)
                    edge.alpha = (255 * (1f - f * f)).toInt()
                }
            }
        })

        if (burst != null) {
            track(ValueAnimator.ofFloat(0f, 1f).apply {
                duration = BURST_MS
                startDelay = stagger + BURST_DELAY_MS
                addUpdateListener { burst.progress = it.animatedValue as Float }
            })
            if (index == 0) {
                card.postDelayed({
                    if (running[card] === cleanup) card.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }, BURST_DELAY_MS)
            }
        }

        card.animate().cancel()
        card.animate()
            .setStartDelay(stagger)
            .scaleX(0.97f).scaleY(0.97f)
            .setDuration(160)
            .withEndAction {
                card.animate().setStartDelay(0).scaleX(1f).scaleY(1f).setDuration(460)
                    .setInterpolator(OvershootInterpolator(2.6f)).start()
            }
            .start()

        return stagger
    }

    fun markUnwatched(card: ViewGroup, cover: View) {
        cancel(card)
        if (cover.width == 0) {
            cover.visibility = View.GONE
            return
        }
        cover.pivotX = cover.width.toFloat()
        val animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 360
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { cover.scaleX = it.animatedValue as Float }
        }
        running[card] = {
            animator.removeAllListeners()
            animator.cancel()
            cover.scaleX = 1f
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                running.remove(card)
                cover.visibility = View.GONE
                cover.scaleX = 1f
            }
        })
        animator.start()
    }

    private class CheckBurst(
        card: ViewGroup,
        anchor: View?,
        private val accent: Int,
        private val onAccent: Int
    ) : Drawable() {

        var progress = 0f
            set(value) {
                field = value
                invalidateSelf()
            }

        private val density = card.resources.displayMetrics.density
        private val radius = minOf(22 * density, card.height / 3f)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = onAccent
            style = Paint.Style.STROKE
            strokeWidth = 3.2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val check = Path().apply {
            moveTo(-0.42f * radius, 0.02f * radius)
            lineTo(-0.1f * radius, 0.34f * radius)
            lineTo(0.46f * radius, -0.3f * radius)
        }
        private val measure = PathMeasure(check, false)
        private val segment = Path()
        private val pop = OvershootInterpolator(2.2f)
        private var globalAlpha = 255

        init {
            val area = Rect()
            if (anchor != null && anchor.width > 0) {
                area.set(0, 0, anchor.width, anchor.height)
                card.offsetDescendantRectToMyCoords(anchor, area)
            } else {
                area.set(0, 0, card.width, card.height)
            }
            val half = (radius * 2).toInt()
            setBounds(area.centerX() - half, area.centerY() - half, area.centerX() + half, area.centerY() + half)
        }

        override fun draw(canvas: Canvas) {
            val p = progress
            val fade = if (p > 0.78f) 1f - (p - 0.78f) / 0.22f else 1f
            val alpha = (globalAlpha * fade).toInt()
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()

            val ringT = ((p - 0.15f) / 0.6f).coerceIn(0f, 1f)
            if (ringT > 0f) {
                ring.alpha = (alpha * (1f - ringT)).toInt()
                canvas.drawCircle(cx, cy, radius * (1f + 0.9f * ringT), ring)
            }

            val discT = (p / 0.35f).coerceIn(0f, 1f)
            fill.alpha = alpha
            canvas.drawCircle(cx, cy, radius * pop.getInterpolation(discT), fill)

            val checkT = ((p - 0.2f) / 0.4f).coerceIn(0f, 1f)
            if (checkT > 0f) {
                segment.rewind()
                measure.getSegment(0f, measure.length * checkT, segment, true)
                stroke.alpha = alpha
                canvas.save()
                canvas.translate(cx, cy)
                canvas.drawPath(segment, stroke)
                canvas.restore()
            }
        }

        override fun setAlpha(alpha: Int) {
            globalAlpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
