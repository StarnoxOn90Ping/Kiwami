package nox.kiwami.util

import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.TextView

class CountLabelController(private val label: TextView) {

    private var shown: Int? = null

    fun show(count: Int) {
        if (count <= 0) {
            hide()
            return
        }
        val previous = shown
        shown = count
        val text = "· $count"
        val offset = 10f * label.resources.displayMetrics.density
        label.animate().cancel()
        if (label.visibility != View.VISIBLE) {
            label.text = text
            label.visibility = View.VISIBLE
            label.alpha = 0f
            label.translationY = offset
            label.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(380)
                .setInterpolator(OvershootInterpolator(1.8f))
                .start()
            return
        }
        if (previous == null || previous == count) {
            label.text = text
            label.alpha = 1f
            label.translationY = 0f
            return
        }
        val direction = if (count > previous) 1 else -1
        label.animate()
            .translationY(-direction * offset)
            .alpha(0f)
            .setDuration(110)
            .withEndAction {
                label.text = text
                label.translationY = direction * offset
                label.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(260)
                    .setInterpolator(OvershootInterpolator(2f))
                    .start()
            }
            .start()
    }

    fun hide() {
        shown = null
        if (label.visibility != View.VISIBLE) return
        label.animate().cancel()
        label.animate()
            .alpha(0f)
            .setDuration(160)
            .withEndAction { label.visibility = View.GONE }
            .start()
    }
}
