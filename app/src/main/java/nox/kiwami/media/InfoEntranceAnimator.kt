package nox.kiwami.media

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import androidx.core.view.doOnPreDraw

object InfoEntranceAnimator {
    private const val MAX_ANIMATED = 16
    private const val STAGGER_MS = 55L
    private const val DURATION_MS = 520L
    private const val START_SCALE = 0.93f

    private val running = HashMap<ViewGroup, MutableList<Animator>>()

    private fun targets(root: ViewGroup): List<View> {
        val out = ArrayList<View>()
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val flatten = child is LinearLayout &&
                child.orientation == LinearLayout.VERTICAL &&
                child.background == null &&
                child.childCount > 1
            if (flatten) {
                val group = child as ViewGroup
                for (j in 0 until group.childCount) {
                    val inner = group.getChildAt(j)
                    if (inner.visibility == View.VISIBLE) out.add(inner)
                }
            } else {
                out.add(child)
            }
        }
        return out
    }

    fun cancel(root: ViewGroup) {
        running.remove(root)?.forEach { it.cancel() }
    }

    fun play(root: ViewGroup) {
        cancel(root)
        root.doOnPreDraw {
            val screenHeight = root.resources.displayMetrics.heightPixels
            val rise = root.resources.displayMetrics.density * 28f
            val location = IntArray(2)
            val list = mutableListOf<Animator>()
            running[root] = list
            var index = 0
            for (view in targets(root)) {
                if (index >= MAX_ANIMATED) break
                view.getLocationOnScreen(location)
                if (location[1] > screenHeight) continue
                view.pivotX = view.width / 2f
                view.pivotY = 0f
                view.alpha = 0f
                view.translationY = rise
                view.scaleX = START_SCALE
                view.scaleY = START_SCALE
                val glide = PathInterpolator(0.16f, 1f, 0.3f, 1f)
                val pop = OvershootInterpolator(1.6f)
                val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = DURATION_MS
                    startDelay = index * STAGGER_MS
                    addUpdateListener {
                        val t = it.animatedFraction
                        val g = glide.getInterpolation(t)
                        val p = pop.getInterpolation(t)
                        view.alpha = (g * 1.4f).coerceAtMost(1f)
                        view.translationY = rise * (1f - g)
                        val scale = START_SCALE + (1f - START_SCALE) * p
                        view.scaleX = scale
                        view.scaleY = scale
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        private fun settle() {
                            view.alpha = 1f
                            view.translationY = 0f
                            view.scaleX = 1f
                            view.scaleY = 1f
                        }

                        override fun onAnimationCancel(animation: Animator) = settle()
                        override fun onAnimationEnd(animation: Animator) = settle()
                    })
                }
                list.add(animator)
                animator.start()
                index++
            }
        }
    }
}
