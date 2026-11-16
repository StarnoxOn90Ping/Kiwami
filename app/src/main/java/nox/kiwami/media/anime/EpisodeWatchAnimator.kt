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
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import java.util.Random
import java.util.WeakHashMap
import kotlin.math.sin

internal object EpisodeWatchAnimator {

    const val BADGE_DELAY_MS = 1000L

    private const val WIPE_MS = 900L
    private const val UNWIPE_MS = 600L
    private const val BURST_MS = 1300L
    private const val BURST_DELAY_MS = 260L
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

        val glitter = if (fancy) GlitterTrail(card, if (index == 0) 14 else 7) else null
        val burst = if (fancy) CheckBurst(card, anchor) else null
        glitter?.let { card.overlay.add(it) }
        burst?.let { card.overlay.add(it) }

        val cleanup = {
            animators.forEach {
                it.removeAllListeners()
                it.cancel()
            }
            glitter?.let { card.overlay.remove(it) }
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
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                val f = it.animatedValue as Float
                cover.scaleX = f
                glitter?.progress = f
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
            .setDuration(220)
            .withEndAction {
                card.animate().setStartDelay(0).scaleX(1f).scaleY(1f).setDuration(560)
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
            duration = UNWIPE_MS
            interpolator = DecelerateInterpolator(1.4f)
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

    private class GlitterTrail(card: ViewGroup, count: Int) : Drawable() {

        var progress = 0f
            set(value) {
                field = value
                invalidateSelf()
            }

        private val density = card.resources.displayMetrics.density
        private val trail = 64 * density
        private val random = Random(card.hashCode().toLong())
        private val lag = FloatArray(count) { random.nextFloat() }
        private val row = FloatArray(count) { random.nextFloat() }
        private val size = FloatArray(count) { (0.8f + random.nextFloat() * 1.5f) * density }
        private val phase = FloatArray(count) { random.nextFloat() * TWO_PI }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = density
            strokeCap = Paint.Cap.ROUND
        }

        init {
            setBounds(0, 0, card.width, card.height)
        }

        override fun draw(canvas: Canvas) {
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()
            val edge = progress * (width + trail)
            val ending = if (progress > 0.8f) (1f - progress) / 0.2f else 1f

            for (i in lag.indices) {
                val x = edge - lag[i] * trail
                if (x < 0f || x > width) continue
                val twinkle = 0.5f + 0.5f * sin(phase[i] + progress * 24f)
                val strength = (1f - lag[i]) * twinkle * ending
                if (strength < 0.03f) continue
                val y = row[i] * height + sin(phase[i] + progress * 7f) * 2f * density

                glow.alpha = (strength * 70).toInt()
                canvas.drawCircle(x, y, size[i] * 2.6f, glow)
                core.alpha = (strength * 255).toInt()
                canvas.drawCircle(x, y, size[i], core)

                if (size[i] > 1.7f * density) {
                    val arm = size[i] * 2.4f * (0.6f + 0.4f * twinkle)
                    cross.alpha = (strength * 200).toInt()
                    canvas.drawLine(x - arm, y, x + arm, y, cross)
                    canvas.drawLine(x, y - arm, x, y + arm, cross)
                }
            }
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        private companion object {
            const val TWO_PI = 6.2831855f
        }
    }

    private class CheckBurst(card: ViewGroup, anchor: View?) : Drawable() {

        var progress = 0f
            set(value) {
                field = value
                invalidateSelf()
            }

        private val density = card.resources.displayMetrics.density
        private val radius = minOf(22 * density, card.height / 3f)
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        private val pulse = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
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
        private val pop = OvershootInterpolator(2f)
        private val ease = DecelerateInterpolator(1.8f)

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
            val fade = if (p > 0.8f) 1f - (p - 0.8f) / 0.2f else 1f
            val alpha = (255 * fade).toInt()
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()

            val pulseT = ((p - 0.12f) / 0.63f).coerceIn(0f, 1f)
            if (pulseT > 0f) {
                pulse.alpha = (alpha * 0.7f * (1f - pulseT)).toInt()
                canvas.drawCircle(cx, cy, radius * (1f + 0.9f * pulseT), pulse)
            }

            val discR = radius * pop.getInterpolation((p / 0.28f).coerceIn(0f, 1f))
            disc.alpha = (0xB3 * fade).toInt()
            canvas.drawCircle(cx, cy, discR, disc)
            rim.alpha = (alpha * 0.9f).toInt()
            canvas.drawCircle(cx, cy, discR, rim)

            val checkT = ease.getInterpolation(((p - 0.22f) / 0.4f).coerceIn(0f, 1f))
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

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
