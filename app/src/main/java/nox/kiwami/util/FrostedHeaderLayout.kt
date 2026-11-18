package nox.kiwami.util

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView

class FrostedHeaderLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var backdrop: View? = null
    private var backdropColor = 0
    private var blurNode: Any? = null
    private val tintPaint = Paint()
    private val edgePaint = Paint()
    private val hairline = resources.displayMetrics.density
    private var hidden = 0f
    private var settler: ValueAnimator? = null
    private var pumpUntil = 0L
    private var pumping = false

    private val pumpStep = object : Runnable {
        override fun run() {
            invalidate()
            if (SystemClock.uptimeMillis() < pumpUntil) postOnAnimation(this) else pumping = false
        }
    }

    fun bind(list: RecyclerView, source: View, backdropColor: Int, edgeColor: Int) {
        backdrop = source
        this.backdropColor = backdropColor
        edgePaint.color = edgeColor
        val blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        tintPaint.color = ColorUtils.setAlphaComponent(backdropColor, if (blurs) 0xA6 else 0xF5)
        if (blurs) createBlurNode()

        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy == 0) return
                settler?.cancel()
                applyHidden(if (rv.canScrollVertically(-1)) hidden + dy else 0f)
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                if (hidden <= 0f || hidden >= height) return
                settleTo(if (hidden > height / 2f) height.toFloat() else 0f)
            }
        })
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (hidden != 0f && !list.canScrollVertically(-1)) settleTo(0f)
        }
        invalidate()
    }

    fun reveal() {
        if (hidden != 0f) settleTo(0f)
    }

    fun pump(durationMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        pumpUntil = maxOf(pumpUntil, SystemClock.uptimeMillis() + durationMs)
        if (pumping) return
        pumping = true
        postOnAnimation(pumpStep)
    }

    private fun applyHidden(value: Float) {
        hidden = value.coerceIn(0f, height.toFloat())
        translationY = -hidden
        invalidate()
    }

    private fun settleTo(target: Float) {
        settler?.cancel()
        settler = ValueAnimator.ofFloat(hidden, target).apply {
            duration = 240
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { applyHidden(it.animatedValue as Float) }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        settler?.cancel()
        removeCallbacks(pumpStep)
        pumping = false
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w > 0f && h > 0f) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated) {
                drawBlur(canvas)
            }
            canvas.drawRect(0f, 0f, w, h, tintPaint)
            canvas.drawRect(0f, h - hairline, w, h, edgePaint)
        }
        super.dispatchDraw(canvas)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createBlurNode() {
        val radius = 24f * resources.displayMetrics.density
        blurNode = RenderNode("frosted-header").apply {
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun drawBlur(canvas: Canvas) {
        val source = backdrop ?: return
        val node = blurNode as? RenderNode ?: return
        if (translationY <= -height.toFloat()) return
        node.setPosition(0, 0, width, height)
        val recording = node.beginRecording()
        try {
            recording.drawColor(backdropColor)
            recording.translate(-left.toFloat(), -(top + translationY))
            source.draw(recording)
        } finally {
            node.endRecording()
        }
        canvas.drawRenderNode(node)
    }
}
