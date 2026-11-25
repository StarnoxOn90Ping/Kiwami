package nox.kiwami.home

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.flaviofaria.kenburnsview.KenBurnsView
import kotlin.math.abs
import nox.kiwami.R
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName

fun ViewPager2.syncBannerPlayback(position: Int, includeNeighbors: Boolean) {
    val enabled: Boolean = PrefManager.getVal(PrefName.BannerAnimations)
    if (!enabled) return
    val recycler = getChildAt(0) as? RecyclerView ?: return
    val reach = if (includeNeighbors) 1 else 0
    for (i in 0 until recycler.childCount) {
        val child = recycler.getChildAt(i)
        val banner = child.findViewById<View>(R.id.itemCompactBanner) as? KenBurnsView ?: continue
        val childPosition = recycler.getChildAdapterPosition(child)
        if (childPosition == RecyclerView.NO_POSITION) continue
        if (abs(childPosition - position) <= reach) banner.resume() else banner.pause()
    }
}

class TrendingPlaybackCallback(private val pager: ViewPager2) : ViewPager2.OnPageChangeCallback() {
    override fun onPageSelected(position: Int) {
        pager.syncBannerPlayback(position, pager.scrollState != ViewPager2.SCROLL_STATE_IDLE)
    }

    override fun onPageScrollStateChanged(state: Int) {
        pager.syncBannerPlayback(pager.currentItem, state != ViewPager2.SCROLL_STATE_IDLE)
    }
}
