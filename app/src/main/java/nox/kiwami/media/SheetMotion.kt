package nox.kiwami.media

import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView

object SheetMotion {

    fun popIn(view: View, index: Int) {
        val density = view.resources.displayMetrics.density
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = 26f * density
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(index * 40L)
            .setDuration(420)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    fun settle(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
    }

    fun popInRows(list: RecyclerView, limit: Int = 10, offset: Int = 0) {
        var active = true
        val listener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (!active) return
                val position = list.getChildAdapterPosition(view)
                if (position < 0 || position >= limit) return
                popIn(view, position + offset)
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                settle(view)
            }
        }
        list.addOnChildAttachStateChangeListener(listener)
        list.postDelayed({
            active = false
            list.removeOnChildAttachStateChangeListener(listener)
        }, 900)
    }
}
