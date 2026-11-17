package nox.kiwami.onboarding

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import nox.kiwami.R
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class KiwamiMarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private inner class Layer(
        resId: Int,
        x0: Int, y0: Int, x1: Int, y1: Int,
        dirX: Float, dirY: Float,
        val from: Float, val to: Float
    ) {
        val bitmap: Bitmap = BitmapFactory.decodeResource(resources, resId)
        val left = x0 / SOURCE_SIZE
        val top = y0 / SOURCE_SIZE
        val right = x1 / SOURCE_SIZE
        val bottom = y1 / SOURCE_SIZE
        val ux = dirX / hypot(dirX, dirY)
        val uy = dirY / hypot(dirX, dirY)
        val angle = Math.toDegrees(atan2(uy, ux).toDouble()).toFloat()
        val dest = RectF()
    }

    private val stem = Layer(R.drawable.kiwami_sketch_stem, 141, 56, 299, 994, 0.06f, 1f, 0f, 0.32f)
    private val arm = Layer(R.drawable.kiwami_sketch_arm, 274, 470, 439, 701, 1f, -0.95f, 0.30f, 0.46f)
    private val upper = Layer(R.drawable.kiwami_sketch_diag_up, 499, 70, 938, 487, 1f, -0.83f, 0.30f, 0.62f)
    private val lower = Layer(R.drawable.kiwami_sketch_diag_down, 437, 460, 828, 989, 0.85f, 1f, 0.58f, 0.84f)
    private val playLayer = Layer(R.drawable.kiwami_sketch_play, 437, 438, 595, 606, 1f, 0f, 0.84f, 1f)
    private val wiped = listOf(stem, arm, upper, lower)

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        shader = LinearGradient(
            0f, 0f, 1f, 0f,
            0xFFFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP
        )
    }
    private val maskMatrix = Matrix()
    private val ease = DecelerateInterpolator(1.4f)
    private val pop = OvershootInterpolator(2.2f)

    private var progress = 0f
    private var animator: ValueAnimator? = null

    fun play(durationMs: Long = 1500L, delayMs: Long = 150L) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            startDelay = delayMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            progress = 0f
            start()
        }
        invalidate()
    }

    fun reset() {
        animator?.cancel()
        animator = null
        progress = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height) * 0.92f
        val left = (width - size) / 2f
        val top = (height - size) / 2f

        val glow = (progress * 3f).coerceIn(0f, 1f)
        glowPaint.shader = RadialGradient(
            width / 2f, height / 2f, size * 0.62f,
            intArrayOf(0xE6191A1E.toInt(), 0x99191A1E.toInt(), 0x00191A1E),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        glowPaint.alpha = (glow * 255).toInt()
        canvas.drawCircle(width / 2f, height / 2f, size * 0.62f, glowPaint)

        bitmapPaint.alpha = 255
        wiped.forEach { layer ->
            val t = ((progress - layer.from) / (layer.to - layer.from)).coerceIn(0f, 1f)
            if (t <= 0f) return@forEach
            layer.dest.set(
                left + layer.left * size, top + layer.top * size,
                left + layer.right * size, top + layer.bottom * size
            )
            if (t >= 1f) {
                canvas.drawBitmap(layer.bitmap, null, layer.dest, bitmapPaint)
            } else {
                drawWipe(canvas, layer, ease.getInterpolation(t))
            }
        }

        val tt = ((progress - playLayer.from) / (playLayer.to - playLayer.from)).coerceIn(0f, 1f)
        if (tt > 0f) {
            playLayer.dest.set(
                left + playLayer.left * size, top + playLayer.top * size,
                left + playLayer.right * size, top + playLayer.bottom * size
            )
            val scale = 0.45f + 0.55f * pop.getInterpolation(tt)
            bitmapPaint.alpha = (min(1f, tt * 3f) * 255).toInt()
            canvas.save()
            canvas.scale(scale, scale, playLayer.dest.centerX(), playLayer.dest.centerY())
            canvas.drawBitmap(playLayer.bitmap, null, playLayer.dest, bitmapPaint)
            canvas.restore()
            bitmapPaint.alpha = 255
        }
    }

    private fun drawWipe(canvas: Canvas, layer: Layer, p: Float) {
        val d = layer.dest
        var tMin = Float.MAX_VALUE
        var tMax = -Float.MAX_VALUE
        for (cx in floatArrayOf(d.left, d.right)) {
            for (cy in floatArrayOf(d.top, d.bottom)) {
                val proj = cx * layer.ux + cy * layer.uy
                tMin = min(tMin, proj)
                tMax = max(tMax, proj)
            }
        }
        val feather = (tMax - tMin) * FEATHER
        val head = tMin + (tMax - tMin + feather) * p
        val start = head - feather

        maskMatrix.setScale(feather, 1f)
        maskMatrix.postRotate(layer.angle)
        maskMatrix.postTranslate(layer.ux * start, layer.uy * start)
        maskPaint.shader.setLocalMatrix(maskMatrix)

        val saved = canvas.saveLayer(d, null)
        canvas.drawBitmap(layer.bitmap, null, d, bitmapPaint)
        canvas.drawRect(d, maskPaint)
        canvas.restoreToCount(saved)
    }

    private companion object {
        const val SOURCE_SIZE = 1024f
        const val FEATHER = 0.3f
    }
}
