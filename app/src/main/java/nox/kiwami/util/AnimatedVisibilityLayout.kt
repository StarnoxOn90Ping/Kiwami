package nox.kiwami.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout

open class AnimatedVisibilityLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    protected open val expands: Boolean get() = false

    private var runner: ValueAnimator? = null
    private var animatingTo: Boolean? = null
    private var progress = 1f
    private var naturalHeight = 0
    private val slideOffset = 36f * resources.displayMetrics.density

    override fun setVisibility(visibility: Int) {
        val show = visibility == VISIBLE
        val current = animatingTo ?: (super.getVisibility() == VISIBLE)
        if (!canAnimate()) {
            stop()
            super.setVisibility(visibility)
            settle(show)
            return
        }
        if (show == current) {
            if (animatingTo == null) super.setVisibility(visibility)
            return
        }
        start(show, visibility)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        val target = animatingTo
        stop()
        if (target != null) settle(target)
    }

    private fun canAnimate(): Boolean =
        isAttachedToWindow && (parent as? View)?.isLaidOut == true && ValueAnimator.areAnimatorsEnabled()

    private fun stop() {
        runner?.cancel()
        runner = null
        animatingTo = null
    }

    private fun start(show: Boolean, finalVisibility: Int) {
        val from = if (runner != null) progress else if (super.getVisibility() == VISIBLE) 1f else 0f
        runner?.cancel()
        animatingTo = show
        if (show) {
            super.setVisibility(VISIBLE)
            if (expands) naturalHeight = measureNatural()
        } else if (expands && from >= 1f) {
            naturalHeight = height
        }
        render(from)
        val animation = ValueAnimator.ofFloat(from, if (show) 1f else 0f)
        animation.duration = if (show) 420L else 220L
        animation.interpolator = if (show) OvershootInterpolator(1.05f) else AccelerateInterpolator(1.4f)
        animation.addUpdateListener { render(it.animatedValue as Float) }
        animation.addListener(object : AnimatorListenerAdapter() {
            private var canceled = false

            override fun onAnimationCancel(a: Animator) {
                canceled = true
            }

            override fun onAnimationEnd(a: Animator) {
                if (canceled) return
                runner = null
                animatingTo = null
                if (!show) super@AnimatedVisibilityLayout.setVisibility(finalVisibility)
                settle(show)
            }
        })
        runner = animation
        animation.start()
    }

    private fun render(value: Float) {
        progress = value
        alpha = value.coerceIn(0f, 1f)
        if (expands) {
            val params = layoutParams ?: return
            params.height = (naturalHeight * value).toInt().coerceAtLeast(0)
            layoutParams = params
        } else {
            translationY = (1f - value) * slideOffset
        }
    }

    private fun settle(show: Boolean) {
        progress = if (show) 1f else 0f
        alpha = 1f
        translationY = 0f
        val params = layoutParams ?: return
        if (expands && params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            layoutParams = params
        }
    }

    private fun measureNatural(): Int {
        val params = layoutParams as? MarginLayoutParams ?: return 0
        val host = parent as? View ?: return 0
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
        val width = host.width - host.paddingLeft - host.paddingRight -
                params.leftMargin - params.rightMargin
        measure(
            MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        return measuredHeight
    }
}

class SlideVisibilityLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AnimatedVisibilityLayout(context, attrs, defStyleAttr)

class ExpandVisibilityLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AnimatedVisibilityLayout(context, attrs, defStyleAttr) {
    override val expands: Boolean get() = true
}
