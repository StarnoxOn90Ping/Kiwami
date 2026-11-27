package nox.kiwami.home.continuebar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

class HomeSlotLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onPageChanged: ((Int) -> Unit)? = null
    var page = 0
        private set

    private var topPane: View? = null
    private var bottomPane: View? = null
    private var dots: View? = null
    private var swapEnabled = false
    private var animator: ValueAnimator? = null
    private var tracker: VelocityTracker? = null
    private var state = IDLE
    private var downX = 0f
    private var downY = 0f
    private var originY = 0f
    private var progress = 0f

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 6f

    fun bind(top: View, bottom: View, dotsView: View) {
        topPane = top
        bottomPane = bottom
        dots = dotsView
    }

    fun configure(hasTop: Boolean, hasBottom: Boolean, preferred: Int) {
        val top = topPane ?: return
        val bottom = bottomPane ?: return
        val wasBoth = swapEnabled
        when {
            !hasTop && !hasBottom -> {
                stopAnimation()
                swapEnabled = false
                top.visibility = GONE
                bottom.visibility = GONE
                dots?.visibility = GONE
                visibility = GONE
            }

            hasTop != hasBottom -> {
                stopAnimation()
                swapEnabled = false
                visibility = VISIBLE
                dots?.visibility = GONE
                page = if (hasTop) 0 else 1
                reset(top)
                reset(bottom)
                top.visibility = if (hasTop) VISIBLE else GONE
                bottom.visibility = if (hasBottom) VISIBLE else GONE
            }

            else -> {
                visibility = VISIBLE
                swapEnabled = true
                dots?.visibility = VISIBLE
                if (wasBoth) return
                val target = preferred.coerceIn(0, 1)
                val shown = when {
                    top.visibility == VISIBLE -> 0
                    bottom.visibility == VISIBLE -> 1
                    else -> -1
                }
                if (shown == -1 || shown == target) {
                    page = target
                    settle()
                } else {
                    page = shown
                    reset(top)
                    reset(bottom)
                    top.visibility = if (shown == 0) VISIBLE else INVISIBLE
                    bottom.visibility = if (shown == 1) VISIBLE else INVISIBLE
                    post {
                        beginMove()
                        animateSwap(0f, true, false)
                    }
                }
            }
        }
    }

    fun toggle() {
        if (!swapEnabled || state != IDLE || animator?.isRunning == true) return
        beginMove()
        animateSwap(0f, true, true)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!swapEnabled) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.end()
                downX = ev.x
                downY = ev.y
                state = UNDECIDED
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                parent?.requestDisallowInterceptTouchEvent(true)
                return super.dispatchTouchEvent(ev)
            }

            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                when (state) {
                    UNDECIDED -> {
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        if (abs(dx) > slop || abs(dy) > slop) {
                            val vertical = abs(dy) > abs(dx)
                            val allowed = (page == 0 && dy < 0) || (page == 1 && dy > 0)
                            if (vertical && allowed) {
                                state = DRAGGING
                                originY = ev.y
                                cancelChildren(ev)
                                beginMove()
                                return true
                            }
                            state = PASSING
                            if (vertical) parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        return super.dispatchTouchEvent(ev)
                    }

                    DRAGGING -> {
                        val raw = ev.y - originY
                        val signed = if (page == 0) -raw else raw
                        progress = (signed / slotHeight()).coerceIn(0f, 1f)
                        applyProgress(progress)
                        return true
                    }

                    else -> return super.dispatchTouchEvent(ev)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = state == DRAGGING
                var velocity = 0f
                tracker?.let {
                    it.addMovement(ev)
                    it.computeCurrentVelocity(1000)
                    velocity = it.yVelocity
                    it.recycle()
                }
                tracker = null
                state = IDLE
                if (wasDragging) {
                    val toward = if (page == 0) -velocity else velocity
                    val commit = ev.actionMasked == MotionEvent.ACTION_UP &&
                        (progress > 0.3f || toward > flingVelocity)
                    animateSwap(progress, commit, true)
                    return true
                }
                return super.dispatchTouchEvent(ev)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    private fun slotHeight(): Float =
        if (height > 0) height.toFloat() else 100f * resources.displayMetrics.density

    private fun pane(index: Int): View? = if (index == 0) topPane else bottomPane

    private fun cancelChildren(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun beginMove() {
        val out = pane(page) ?: return
        val inc = pane(1 - page) ?: return
        progress = 0f
        out.visibility = VISIBLE
        inc.visibility = VISIBLE
        out.setLayerType(LAYER_TYPE_HARDWARE, null)
        inc.setLayerType(LAYER_TYPE_HARDWARE, null)
        applyProgress(0f)
    }

    private fun applyProgress(p: Float) {
        val out = pane(page) ?: return
        val inc = pane(1 - page) ?: return
        val h = slotHeight()
        val dir = if (page == 0) -1f else 1f
        out.translationY = dir * p * h
        out.alpha = 1f - p
        inc.translationY = -dir * (1f - p) * h
        inc.alpha = p
    }

    private fun animateSwap(from: Float, commit: Boolean, fromUser: Boolean) {
        val end = if (commit) 1f else 0f
        animator?.cancel()
        val span = abs(end - from).coerceAtLeast(0.25f)
        animator = ValueAnimator.ofFloat(from, end).apply {
            duration = (260f * span).toLong().coerceAtLeast(140L)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                progress = it.animatedValue as Float
                applyProgress(progress)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    finish(commit, fromUser)
                }
            })
            start()
        }
    }

    private fun finish(commit: Boolean, fromUser: Boolean) {
        if (commit) page = 1 - page
        settle()
        progress = 0f
        if (commit && fromUser) onPageChanged?.invoke(page)
    }

    private fun settle() {
        val top = topPane ?: return
        val bottom = bottomPane ?: return
        reset(top)
        reset(bottom)
        top.visibility = if (page == 0) VISIBLE else INVISIBLE
        bottom.visibility = if (page == 1) VISIBLE else INVISIBLE
    }

    private fun reset(view: View) {
        view.translationY = 0f
        view.alpha = 1f
        view.setLayerType(LAYER_TYPE_NONE, null)
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
        state = IDLE
    }

    private companion object {
        const val IDLE = 0
        const val UNDECIDED = 1
        const val DRAGGING = 2
        const val PASSING = 3
    }
}
