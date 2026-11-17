package nox.kiwami.onboarding

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

class OnboardingScrim : Drawable() {

    private val topPaint = Paint()
    private val mainPaint = Paint()
    private var shaderHeight = -1
    private var shaderSplit = -1f

    var split: Float = 0.58f
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height()
        if (h <= 0) return
        if (h != shaderHeight || split != shaderSplit) {
            shaderHeight = h
            shaderSplit = split
            val hf = h.toFloat()
            topPaint.shader = LinearGradient(
                0f, 0f, 0f, hf * 0.14f,
                0x99191A1E.toInt(), 0x00191A1E, Shader.TileMode.CLAMP
            )
            mainPaint.shader = LinearGradient(
                0f, 0f, 0f, hf,
                intArrayOf(0x00191A1E, 0xB3191A1E.toInt(), 0xFF191A1E.toInt()),
                floatArrayOf(split - 0.17f, split - 0.06f, split + 0.04f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h.toFloat(), mainPaint)
        canvas.drawRect(0f, 0f, w, h * 0.14f, topPaint)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
