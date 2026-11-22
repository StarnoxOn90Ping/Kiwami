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
import nox.kiwami.R

object InfoEntranceAnimator {
    private const val MAX_ANIMATED = 16
    private const val STAGGER_MS = 55L
    private const val DURATION_MS = 520L
    private const val START_SCALE = 0.93f
    private const val WATCHDOG_MARGIN_MS = 250L

    private class Pass {
        val animators = mutableListOf<Animator>()
        val restores = mutableListOf<() -> Unit>()

        fun restoreAll() {
            restores.forEach { it() }
        }
    }

    private val running = HashMap<ViewGroup, Pass>()
    private val generation = HashMap<ViewGroup, Int>()

    private fun targets(root: ViewGroup): List<View> {
        val out = ArrayList<View>()
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (child.id == R.id.mediaInfoSynopsis) continue
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
        generation[root] = (generation[root] ?: 0) + 1
        val pass = running.remove(root) ?: return
        pass.animators.forEach { it.cancel() }
        pass.restoreAll()
    }

    fun play(root: ViewGroup) {
        cancel(root)
        val token = generation[root] ?: 0
        root.doOnPreDraw {
            if (generation[root] != token) return@doOnPreDraw
            val screenHeight = root.resources.displayMetrics.heightPixels
            val rise = root.resources.displayMetrics.density * 28f
            val location = IntArray(2)
            val synopsisLocation = IntArray(2)
            val synopsis = root.findViewById<View>(R.id.mediaInfoSynopsis)
            val synopsisTop = if (synopsis != null && synopsis.visibility == View.VISIBLE) {
                synopsis.getLocationOnScreen(synopsisLocation)
                synopsisLocation[1]
            } else {
                Int.MAX_VALUE
            }
            val pass = Pass()
            running[root] = pass
            var index = 0
            for (view in targets(root)) {
                if (index >= MAX_ANIMATED) break
                view.getLocationOnScreen(location)
                if (location[1] > screenHeight) continue
                val viewRise = if (location[1] < synopsisTop) 0f else rise
                val baseAlpha = view.alpha
                val baseTranslation = view.translationY
                val restore = {
                    view.alpha = baseAlpha
                    view.translationY = baseTranslation
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
                pass.restores.add(restore)
                view.pivotX = view.width / 2f
                view.pivotY = 0f
                view.alpha = 0f
                view.translationY = baseTranslation + viewRise
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
                        view.alpha = (g * 1.4f).coerceAtMost(1f) * baseAlpha
                        view.translationY = baseTranslation + viewRise * (1f - g)
                        val scale = START_SCALE + (1f - START_SCALE) * p
                        view.scaleX = scale
                        view.scaleY = scale
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationCancel(animation: Animator) = restore()
                        override fun onAnimationEnd(animation: Animator) = restore()
                    })
                }
                pass.animators.add(animator)
                animator.start()
                index++
            }
            val total = index * STAGGER_MS + DURATION_MS + WATCHDOG_MARGIN_MS
            root.postDelayed({
                if (running[root] === pass) {
                    pass.restoreAll()
                    running.remove(root)
                }
            }, total)
        }
    }
}
