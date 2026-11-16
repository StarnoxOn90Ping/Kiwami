package nox.kiwami.util

import android.annotation.SuppressLint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.core.view.doOnLayout

@SuppressLint("ClickableViewAccessibility")
fun View.setPressBounce(scaleDown: Float = 0.86f) {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate()
                .setStartDelay(0)
                .scaleX(scaleDown).scaleY(scaleDown)
                .setDuration(150)
                .setInterpolator(DecelerateInterpolator())
                .start()

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val held = event.eventTime - event.downTime
                v.animate()
                    .setStartDelay((150L - held).coerceAtLeast(0L))
                    .scaleX(1f).scaleY(1f)
                    .setDuration(750)
                    .setInterpolator(OvershootInterpolator(6f))
                    .start()
            }
        }
        false
    }
}

fun TextView.setGradientText(startColor: Int, endColor: Int) {
    doOnLayout {
        val textWidth = paint.measureText(text.toString())
        val available = (width - paddingLeft - paddingRight).toFloat()
        val left = if ((gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL) {
            paddingLeft + ((available - textWidth) / 2f).coerceAtLeast(0f)
        } else paddingLeft.toFloat()
        paint.shader = LinearGradient(left, 0f, left + textWidth, 0f, startColor, endColor, Shader.TileMode.CLAMP)
        invalidate()
    }
}

fun View.fadeInDown(delay: Long = 0L, distanceDp: Float = 18f) {
    alpha = 0f
    translationY = -distanceDp * resources.displayMetrics.density
    animate().setStartDelay(delay)
        .alpha(1f).translationY(0f)
        .setDuration(480)
        .setInterpolator(DecelerateInterpolator(1.8f))
        .start()
}
