package nox.kiwami.onboarding

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import nox.kiwami.R
import kotlin.math.hypot
import kotlin.math.min

class KiwamiMarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Stroke(
        val area: RectF,
        val ax: Float, val ay: Float,
        val bx: Float, val by: Float,
        val from: Float, val to: Float
    )

    private val bitmap: Bitmap =
        BitmapFactory.decodeResource(resources, R.drawable.kiwami_mark)

    private val strokes = listOf(
        Stroke(RectF(0f, 0f, 0.31f, 1f), 0.19f, 0.04f, 0.19f, 1f, 0f, 0.42f),
        Stroke(RectF(0.26f, 0.44f, 0.43f, 0.7f), 0.27f, 0.64f, 0.42f, 0.49f, 0.3f, 0.56f),
        Stroke(RectF(0.48f, 0.03f, 1f, 0.45f), 0.49f, 0.43f, 0.9f, 0.08f, 0.46f, 0.76f),
        Stroke(RectF(0.47f, 0.54f, 0.88f, 1f), 0.48f, 0.56f, 0.8f, 0.94f, 0.6f, 0.92f)
    )
    private val triangle = RectF(0.42f, 0.41f, 0.6f, 0.6f)

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val area = Path()
    private val hole = Path()
    private val dest = RectF()
    private val part = RectF()
    private val ease = DecelerateInterpolator(1.6f)

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
        dest.set(left, top, left + size, top + size)

        val glow = (progress * 3f).coerceIn(0f, 1f)
        glowPaint.shader = RadialGradient(
            width / 2f, height / 2f, size * 0.62f,
            intArrayOf(0xE6191A1E.toInt(), 0x99191A1E.toInt(), 0x00191A1E),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        glowPaint.alpha = (glow * 255).toInt()
        canvas.drawCircle(width / 2f, height / 2f, size * 0.62f, glowPaint)

        if (progress >= 1f) {
            bitmapPaint.alpha = 255
            canvas.drawBitmap(bitmap, null, dest, bitmapPaint)
            return
        }

        bitmapPaint.alpha = 255
        strokes.forEach { stroke ->
            val t = ((progress - stroke.from) / (stroke.to - stroke.from)).coerceIn(0f, 1f)
            if (t <= 0f) return@forEach
            val p = ease.getInterpolation(t)
            val ax = left + stroke.ax * size
            val ay = top + stroke.ay * size
            val dx = (stroke.bx - stroke.ax) * size
            val dy = (stroke.by - stroke.ay) * size
            val length = hypot(dx, dy)
            val ux = dx / length
            val uy = dy / length
            val nx = -uy
            val ny = ux
            val pad = size * 0.12f
            val reach = (length + pad * 2f) * p
            val sx = ax - ux * pad
            val sy = ay - uy * pad
            val ex = sx + ux * reach
            val ey = sy + uy * reach
            val w = size
            clip.reset()
            clip.moveTo(sx + nx * w, sy + ny * w)
            clip.lineTo(ex + nx * w, ey + ny * w)
            clip.lineTo(ex - nx * w, ey - ny * w)
            clip.lineTo(sx - nx * w, sy - ny * w)
            clip.close()
            part.set(
                left + stroke.area.left * size, top + stroke.area.top * size,
                left + stroke.area.right * size, top + stroke.area.bottom * size
            )
            area.reset()
            area.addRect(part, Path.Direction.CW)
            hole.reset()
            hole.addRect(
                left + triangle.left * size, top + triangle.top * size,
                left + triangle.right * size, top + triangle.bottom * size,
                Path.Direction.CW
            )
            area.op(hole, Path.Op.DIFFERENCE)
            canvas.save()
            canvas.clipPath(area)
            canvas.clipPath(clip)
            canvas.drawBitmap(bitmap, null, dest, bitmapPaint)
            canvas.restore()
        }

        val tt = ((progress - 0.8f) / 0.2f).coerceIn(0f, 1f)
        if (tt > 0f) {
            val p = ease.getInterpolation(tt)
            part.set(
                left + triangle.left * size, top + triangle.top * size,
                left + triangle.right * size, top + triangle.bottom * size
            )
            val scale = 0.6f + 0.4f * p
            bitmapPaint.alpha = (p * 255).toInt()
            canvas.save()
            canvas.scale(scale, scale, part.centerX(), part.centerY())
            canvas.clipRect(part)
            canvas.drawBitmap(bitmap, null, dest, bitmapPaint)
            canvas.restore()
            bitmapPaint.alpha = 255
        }
    }
}
