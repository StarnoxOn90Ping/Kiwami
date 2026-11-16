package nox.kiwami.util

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.Interpolator
import android.widget.EdgeEffect
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

class SpringInterpolator(
    private val damping: Float = 4.2f,
    private val cycles: Float = 1.35f
) : Interpolator {
    override fun getInterpolation(t: Float): Float {
        if (t >= 1f) return 1f
        return (1f - exp(-damping * t) * cos(2.0 * PI * cycles * t)).toFloat()
    }
}

private const val SPRING_MS = 650L

private fun rubber(pull: Float, max: Float): Float {
    if (max <= 0f) return 0f
    val s = if (pull < 0) -1f else 1f
    return s * max * (1f - exp(-0.55f * abs(pull) / max))
}

private fun unrubber(offset: Float, max: Float): Float {
    if (max <= 0f) return 0f
    val s = if (offset < 0) -1f else 1f
    val r = (abs(offset) / max).coerceIn(0f, 0.999f)
    return s * (-max / 0.55f) * ln(1f - r)
}

private class Mover(private val apply: (Float) -> Unit, private val get: () -> Float) {
    private var anim: ValueAnimator? = null
    val running get() = anim?.isRunning == true

    fun cancel() {
        anim?.cancel()
        anim = null
    }

    fun settle() {
        val from = get()
        cancel()
        if (from == 0f) return
        val spring = SpringInterpolator()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SPRING_MS
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { apply(from * (1f - spring.getInterpolation(it.animatedFraction))) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) = apply(0f)
            })
            start()
        }
    }

    fun kick(peak: Float) {
        cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 750
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction
                val rise = 0.2f
                val lobe = (t / rise) * exp(1f - t / rise)
                val tail = (1f - t) * (1f - t)
                apply(peak * lobe * tail * 1.15f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) = apply(0f)
            })
            start()
        }
    }
}

class BounceRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.recyclerview.R.attr.recyclerViewStyle
) : RecyclerView(context, attrs, defStyleAttr) {
    init {
        overScrollMode = OVER_SCROLL_ALWAYS
        edgeEffectFactory = object : EdgeEffectFactory() {
            override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect =
                if (direction == DIRECTION_TOP || direction == DIRECTION_BOTTOM) {
                    BounceEdgeEffect(view, if (direction == DIRECTION_BOTTOM) -1f else 1f)
                } else super.createEdgeEffect(view, direction)
        }
    }
}

private class BounceEdgeEffect(private val rv: RecyclerView, private val sign: Float) :
    EdgeEffect(rv.context) {

    private var offset = 0f
    private var appliedThisCall = false
    private val mover = Mover(::move) { offset }

    private fun move(v: Float) {
        offset = v
        for (i in 0 until rv.childCount) rv.getChildAt(i).translationY = v
    }

    private fun pull(delta: Float) {
        mover.cancel()
        appliedThisCall = true
        val max = rv.height * 0.22f
        val room = (1f - abs(offset) / max).coerceIn(0.08f, 1f)
        move(offset + sign * rv.height * delta * 0.55f * room)
    }

    override fun onPull(deltaDistance: Float) {
        pull(deltaDistance); super.onPull(deltaDistance)
    }

    override fun onPull(deltaDistance: Float, displacement: Float) {
        pull(deltaDistance); super.onPull(deltaDistance, displacement)
    }

    override fun onPullDistance(deltaDistance: Float, displacement: Float): Float {
        appliedThisCall = false
        val r = super.onPullDistance(deltaDistance, displacement)
        if (!appliedThisCall) pull(deltaDistance)
        return r
    }

    override fun onRelease() {
        super.onRelease(); mover.settle()
    }

    override fun onAbsorb(velocity: Int) {
        super.onAbsorb(velocity)
        val peak = min(velocity * 0.014f, rv.height * 0.12f)
        mover.kick(sign * peak)
    }

    override fun draw(canvas: Canvas): Boolean = false
}

class BounceNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.core.R.attr.nestedScrollViewStyle
) : NestedScrollView(context, attrs, defStyleAttr) {

    private var pull = 0f
    private val maxOffset get() = height * 0.2f
    private val mover = Mover(::move) { content()?.translationY ?: 0f }

    init {
        overScrollMode = OVER_SCROLL_NEVER
    }

    private fun content() = if (childCount > 0) getChildAt(0) else null

    private fun move(offset: Float) {
        content()?.translationY = offset
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            mover.cancel()
            pull = unrubber(content()?.translationY ?: 0f, maxOffset)
        }
        val handled = super.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (pull != 0f) {
                pull = 0f
                mover.settle()
            }
        }
        return handled
    }

    private fun releasePull() {
        if (pull != 0f) {
            pull = 0f
            mover.settle()
        }
    }

    // a gesture that ended somewhere we never saw the UP of (e.g. a nested list) must not leave a bounce behind
    override fun stopNestedScroll(type: Int) {
        super.stopNestedScroll(type)
        if (type == ViewCompat.TYPE_TOUCH) releasePull()
    }

    override fun onStopNestedScroll(target: android.view.View, type: Int) {
        super.onStopNestedScroll(target, type)
        if (type == ViewCompat.TYPE_TOUCH) releasePull()
    }

    override fun dispatchNestedPreScroll(
        dx: Int, dy: Int, consumed: IntArray?, offsetInWindow: IntArray?, type: Int
    ): Boolean {
        // the bounce state only makes sense while we really are at that edge; otherwise drop it so it can never swallow scrolling
        if (pull > 0f && canScrollVertically(-1) || pull < 0f && canScrollVertically(1)) {
            pull = 0f
            mover.settle()
        }
        var used = 0
        if (type == ViewCompat.TYPE_TOUCH && pull != 0f && pull * dy > 0f) {
            val c = if (pull > 0) min(dy.toFloat(), pull) else maxOf(dy.toFloat(), pull)
            pull -= c
            move(rubber(pull, maxOffset))
            used = c.toInt()
        }
        val result = super.dispatchNestedPreScroll(dx, dy - used, consumed, offsetInWindow, type)
        if (used != 0 && consumed != null) consumed[1] += used
        return result || used != 0
    }

    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?, type: Int, consumed: IntArray
    ) {
        super.dispatchNestedScroll(
            dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type, consumed
        )
        val rest = dyUnconsumed - consumed[1]
        if (rest == 0) return
        if (type == ViewCompat.TYPE_TOUCH) {
            mover.cancel()
            pull = (pull - rest).coerceIn(-maxOffset * 6f, maxOffset * 6f)
            move(rubber(pull, maxOffset))
        } else if (!mover.running && pull == 0f) {
            val peak = min(abs(rest) * 1.6f, maxOffset * 0.6f)
            mover.kick(if (rest < 0) peak else -peak)
        }
    }
}
