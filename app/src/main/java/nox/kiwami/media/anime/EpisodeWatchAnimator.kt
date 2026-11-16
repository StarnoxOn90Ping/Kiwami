package nox.kiwami.media.anime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import java.util.Random
import java.util.WeakHashMap
import kotlin.math.pow
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

        val shimmer = if (fancy) Shimmer(card, if (index == 0) 5 else 3) else null
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

    private class Shimmer(card: ViewGroup, stars: Int) : Drawable() {

        var progress = 0f
            set(value) {
                field = value
                invalidateSelf()
            }

        private val density = card.resources.displayMetrics.density
        private val dust = DustTexture.get(density)
        private val bandWidth = minOf(dust.width.toFloat(), card.width * 0.45f)
        private val drift = 26 * density
        private val matrix = Matrix()
        private val shaderA = BitmapShader(dust, Shader.TileMode.CLAMP, Shader.TileMode.REPEAT)
        private val shaderB = BitmapShader(dust, Shader.TileMode.CLAMP, Shader.TileMode.REPEAT)
        private val paintA = Paint().apply { shader = shaderA }
        private val paintB = Paint().apply { shader = shaderB }

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
            val edge = progress * (width + bandWidth)
            val ending = if (progress > 0.8f) (1f - progress) / 0.2f else 1f

            canvas.save()
            canvas.clipRect(bounds)

            matrix.setTranslate(edge - dust.width, progress * drift)
            shaderA.setLocalMatrix(matrix)
            paintA.alpha = (255 * ending * (0.65f + 0.35f * sin(progress * 20f))).toInt()
            canvas.drawRect(edge - bandWidth, 0f, edge, height, paintA)

            matrix.setTranslate(edge - dust.width, height * 0.37f + progress * drift * 1.7f)
            shaderB.setLocalMatrix(matrix)
            paintB.alpha = (230 * ending * (0.65f + 0.35f * sin(progress * 20f + Math.PI.toFloat()))).toInt()
            canvas.drawRect(edge - bandWidth, 0f, edge, height, paintB)

            for (i in lag.indices) {
                val x = edge - lag[i] * bandWidth * 0.8f
                if (x < 0f || x > width) continue
                val twinkle = 0.5f + 0.5f * sin(phase[i] + progress * 24f)
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

            canvas.restore()
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        private companion object {
            const val TWO_PI = 6.2831855f
        }
    }

    /** One small glitter image shared by every tile: sparse on the left, dense on the right (the leading edge). */
    private object DustTexture {
        private var bitmap: Bitmap? = null

        fun get(density: Float): Bitmap = bitmap ?: build(density).also { bitmap = it }

        private fun build(density: Float): Bitmap {
            val width = (180 * density).toInt()
            val height = (96 * density).toInt()
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val random = Random(42)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val tones = intArrayOf(
                0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(),
                0xFFD5D9E0.toInt(), 0xFFB8BEC8.toInt(), 0xFF8E95A3.toInt()
            )
            val centers = Array(5) {
                floatArrayOf(width * (0.4f + random.nextFloat() * 0.6f), height * random.nextFloat())
            }

            var drawn = 0
            var attempts = 0
            while (drawn < 650 && attempts < 20000) {
                attempts++
                val x: Float
                val y: Float
                if (random.nextFloat() < 0.4f) {
                    val center = centers[random.nextInt(centers.size)]
                    x = center[0] + random.nextGaussian().toFloat() * width * 0.12f
                    y = ((center[1] + random.nextGaussian().toFloat() * height * 0.2f) % height + height) % height
                } else {
                    x = random.nextFloat() * width
                    y = random.nextFloat() * height
                }
                if (x < 0f || x > width) continue
                if (random.nextFloat() > (x / width).pow(1.8f)) continue

                val roll = random.nextFloat()
                val tone = tones[random.nextInt(tones.size)]
                if (roll < 0.95f) {
                    val radius = if (roll < 0.7f) (0.4f + random.nextFloat() * 0.5f) * density
                    else (0.9f + random.nextFloat() * 0.7f) * density
                    paint.shader = null
                    paint.color = tone
                    paint.alpha = 150 + random.nextInt(106)
                    canvas.drawCircle(x, y, radius, paint)
                } else {
                    val radius = (3f + random.nextFloat() * 2.5f) * density
                    paint.shader = RadialGradient(
                        x, y, radius, 0x5AFFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP
                    )
                    paint.alpha = 255
                    canvas.drawCircle(x, y, radius, paint)
                    paint.shader = null
                }
                drawn++
            }
            return result
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
