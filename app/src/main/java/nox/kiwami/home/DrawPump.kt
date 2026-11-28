package nox.kiwami.home

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.util.Logger

private const val PUMP_BASE_MS = 1400f
private const val PUMP_MIN_MS = 600L
private const val PUMP_MAX_MS = 4000L

private fun collectRecyclers(view: View, into: MutableList<View>) {
    if (view is RecyclerView) into.add(view)
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) collectRecyclers(view.getChildAt(i), into)
    }
}

fun pumpDraw(vararg views: View) {
    val targets = mutableListOf<View>()
    views.filter { it.isAttachedToWindow }.forEach {
        targets.add(it)
        collectRecyclers(it, targets)
    }
    if (targets.isEmpty()) return
    val speed: Float = PrefManager.getVal(PrefName.AnimationSpeed)
    val duration = (PUMP_BASE_MS * speed).toLong().coerceIn(PUMP_MIN_MS, PUMP_MAX_MS)
    Logger.log("DrawPump: ${targets.size} views for ${duration}ms")
    targets.forEach { it.requestLayout() }
    ValueAnimator.ofInt(0, 1).apply {
        this.duration = duration
        addUpdateListener { targets.forEach { it.invalidate() } }
        start()
    }
}
