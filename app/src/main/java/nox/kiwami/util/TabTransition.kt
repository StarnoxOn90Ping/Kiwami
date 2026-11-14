package nox.kiwami.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Depth-parallax tab transition. It is driven by the scroll position on every frame, so it is one
 * continuous motion (nothing restarts or flashes when a page settles):
 *  - both pages drift towards the middle at half speed, so they glide over each other
 *  - the page that is leaving shrinks back a little and fades out, the new one grows in and fades up
 *  - the fade is eased (squared), so the content never dips noticeably dark halfway through
 */
class TabPageTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        val p = position.coerceIn(-1f, 1f)
        val a = abs(p)
        if (a >= 1f) {
            // Off-screen pages must go back to their real position. Left shifted by the parallax they
            // sit over the screen invisibly and swallow every touch on that half.
            page.alpha = 0f
            page.translationX = 0f
            page.scaleX = 1f
            page.scaleY = 1f
            return
        }
        val scale = 1f - 0.07f * a
        page.alpha = 1f - a * a
        page.scaleX = scale
        page.scaleY = scale
        page.translationX = -p * page.width * 0.5f
    }
}

private val running = WeakHashMap<ViewPager2, ValueAnimator>()
private val guarded = WeakHashMap<ViewPager2, Boolean>()

/** Quiet safety net: once the pager is idle, make sure every page is in its resting look. */
private fun ViewPager2.ensureRestGuard() {
    if (guarded.put(this, true) != null) return
    val pager = this
    registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrollStateChanged(state: Int) {
            if (state == ViewPager2.SCROLL_STATE_IDLE) pager.post { pager.restPages() }
        }
    })
}

/** Selected page fully visible, the others hidden. Does nothing when the pages are already right. */
private fun ViewPager2.restPages() {
    if (scrollState != ViewPager2.SCROLL_STATE_IDLE || isFakeDragging) return
    if (running[this]?.isRunning == true) return
    val rv = getChildAt(0) as? RecyclerView ?: return
    for (i in 0 until rv.childCount) {
        val page = rv.getChildAt(i)
        val selected = rv.getChildAdapterPosition(page) == currentItem
        val alpha = if (selected) 1f else 0f
        if (page.alpha != alpha) page.alpha = alpha
        if (page.scaleX != 1f) { page.scaleX = 1f; page.scaleY = 1f }
        if (page.translationX != 0f) page.translationX = 0f
    }
}

/** Where the pager really is right now, in pages (1.5 = halfway between tab 1 and tab 2). */
private fun ViewPager2.pagePosition(): Float {
    val lm = (getChildAt(0) as? RecyclerView)?.layoutManager as? LinearLayoutManager
        ?: return currentItem.toFloat()
    val first = lm.findFirstVisibleItemPosition()
    val view = lm.findViewByPosition(first) ?: return currentItem.toFloat()
    return first - view.left / width.toFloat()
}

/**
 * Switches to [item] with a long, soft glide (fast start, gentle landing).
 *
 * One glide drives the whole switch. If you tap another tab while it is still moving, the same
 * glide is simply re-aimed from wherever the pages are right now, so quick taps flow into each
 * other instead of stopping, jumping or restarting. The fade and scale come from
 * [TabPageTransformer], which follows the scroll position, so they look right at any speed.
 */
fun ViewPager2.switchTabSmoothly(item: Int, duration: Long = 480L) {
    ensureRestGuard()
    if (width == 0) {
        setCurrentItem(item, false)
        return
    }
    val pager = this
    running[pager]?.let {
        it.removeAllListeners()
        it.cancel()
    }
    val diff = item - pagePosition()
    if (abs(diff) < 0.01f) {
        if (isFakeDragging) endFakeDrag()
        return
    }
    // keeps the running fake drag when re-aiming, only starts one when idle
    if (!isFakeDragging && !beginFakeDrag()) {
        setCurrentItem(item, true)
        return
    }
    var last = 0f
    val animator = ValueAnimator.ofFloat(0f, diff * width).apply {
        this.duration = (duration * abs(diff).coerceIn(0.45f, 1f)).toLong()
        interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f) // easeOutQuint-ish
        addUpdateListener {
            val v = it.animatedValue as Float
            fakeDragBy(-(v - last))
            last = v
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (running[pager] === animation) running.remove(pager)
                if (pager.isFakeDragging) pager.endFakeDrag()
            }
        })
    }
    running[pager] = animator
    animator.start()
}
