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
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
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

        val shimmerOn = PrefManager.getVal<Boolean>(PrefName.WatchedShimmer)
        val shimmer = if (fancy && shimmerOn) {
            Shimmer(card, if (index == 0) 700 else 240, if (index == 0) 5 else 3)
        } else null
        val burst = if (fancy) CheckBurst(card, anchor) else null
        shimmer?.let { card.overlay.add(it) }
        burst?.let { card.overlay.add(it) }

        val cleanup = {
            animators.forEach {
                it.removeAllListeners()
                it.cancel()
            }
            shimmer?.let { card.overlay.remove(it) }
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
                shimmer?.progress = f
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

    private class Shimmer(card: ViewGroup, count: Int, stars: Int) : Drawable() {

        var progress = 0f
            set(value) {
                field = value
                invalidateSelf()
            }

        private val density = card.resources.displayMetrics.density
        private val count = count.coerceAtMost(DustField.MAX)
        private val offset = Math.abs(card.hashCode()) % DustField.MAX
        private val flip = card.hashCode() % 2 == 0
        private val trail = minOf(card.width * 0.45f, 160 * density)

        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        private val random = Random(card.hashCode().toLong())
        private val lag = FloatArray(stars) { random.nextFloat() }
        private val row = FloatArray(stars) { random.nextFloat() }
        private val size = FloatArray(stars) { (1.6f + random.nextFloat() * 1f) * density }
        private val phase = FloatArray(stars) { random.nextFloat() * TWO_PI }
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
            val ending = if (progress > 0.88f) (1f - progress) / 0.12f else 1f
            val seconds = (SystemClock.uptimeMillis() % 100_000L) / 1000f
            val buckets = DustField.scratch
            val counts = DustField.counts
            counts.fill(0)

            for (i in 0 until count) {
                val j = (offset + i) % DustField.MAX
                val age = (progress - DustField.spawn[j]) / DustField.life[j]
                if (age < 0f || age > 1f) continue

                val envelope = if (age < 0.12f) age / 0.12f else 1f - age
                val twinkle = 0.55f + 0.45f * sin(DustField.phase[j] + seconds * DustField.freq[j])
                val strength = envelope * twinkle * ending
                if (strength < 0.04f) continue

                val x = DustField.spawn[j] * width + DustField.vx[j] * density * age
                if (x < 0f || x > width) continue
                val unitY = if (flip) 1f - DustField.unitY[j] else DustField.unitY[j]
                val y = unitY * height + DustField.vy[j] * density * age +
                        sin(DustField.phase[j] + age * 6f) * 1.5f * density

                val bucket = DustField.cls[j] * DustField.LEVELS + (strength * 3.999f).toInt()
                val slot = counts[bucket]
                buckets[bucket][slot] = x
                buckets[bucket][slot + 1] = y
                counts[bucket] = slot + 2
            }

            for (bucket in counts.indices) {
                if (counts[bucket] == 0) continue
                val cls = bucket / DustField.LEVELS
                val level = bucket % DustField.LEVELS
                dot.strokeWidth = DustField.diameter[cls] * density
                dot.color = DustField.color[cls]
                dot.alpha = ((level + 1) * 255 / DustField.LEVELS * DustField.opacity[cls]).toInt()
                canvas.drawPoints(buckets[bucket], 0, counts[bucket], dot)
            }

            val edge = progress * width
            for (i in lag.indices) {
                val x = edge - lag[i] * trail
                if (x < 0f || x > width) continue
                val twinkle = 0.5f + 0.5f * sin(phase[i] + seconds * 9f)
                val strength = (1f - lag[i] * 0.6f) * twinkle * ending
                if (strength < 0.03f) continue
                val y = row[i] * height

                glow.alpha = (strength * 80).toInt()
                canvas.drawCircle(x, y, size[i] * 2.6f, glow)
                core.alpha = (strength * 255).toInt()
                canvas.drawCircle(x, y, size[i] * 0.7f, core)
                val arm = size[i] * 2.6f * (0.6f + 0.4f * twinkle)
                cross.alpha = (strength * 210).toInt()
                canvas.drawLine(x - arm, y, x + arm, y, cross)
                canvas.drawLine(x, y - arm, x, y + arm, cross)
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

    /**
     * One dust field shared by every tile. Each particle is a pure function of the sweep progress
     * (spawn point, lifetime, drift), so nothing is simulated or allocated per frame and a tile only
     * picks its own starting offset into the field. Positions go into a few buckets by size and
     * brightness, and each bucket is drawn with a single drawPoints call.
     */
    private object DustField {
        const val MAX = 700
        const val LEVELS = 4
        private const val CLASSES = 4

        val spawn = FloatArray(MAX)
        val life = FloatArray(MAX)
        val unitY = FloatArray(MAX)
        val vx = FloatArray(MAX)
        val vy = FloatArray(MAX)
        val phase = FloatArray(MAX)
        val freq = FloatArray(MAX)
        val cls = IntArray(MAX)

        val diameter = floatArrayOf(1.1f, 1.8f, 2.8f, 9f)
        val opacity = floatArrayOf(0.9f, 1f, 1f, 0.16f)
        val color = intArrayOf(
            0xFFC4CAD6.toInt(), 0xFFEEF1F6.toInt(), Color.WHITE, Color.WHITE
        )

        val scratch = Array(CLASSES * LEVELS) { FloatArray(MAX * 2) }
        val counts = IntArray(CLASSES * LEVELS)

        init {
            val random = Random(1337)
            for (i in 0 until MAX) {
                spawn[i] = random.nextFloat()
                life[i] = 0.1f + random.nextFloat() * 0.18f
                unitY[i] = random.nextFloat()
                vx[i] = (random.nextFloat() - 0.6f) * 24f
                vy[i] = -6f + random.nextFloat() * 24f
                phase[i] = random.nextFloat() * 6.2831855f
                freq[i] = 6f + random.nextFloat() * 10f
                val roll = random.nextFloat()
                cls[i] = when {
                    roll < 0.55f -> 0
                    roll < 0.85f -> 1
                    roll < 0.95f -> 2
                    else -> 3
                }
            }
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
