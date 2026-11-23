package nox.kiwami.download.hub

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import nox.kiwami.R
import nox.kiwami.databinding.ActivityDownloadsBinding
import nox.kiwami.download.DownloadRefresher
import nox.kiwami.download.DownloadsManager
import nox.kiwami.download.OfflineLibraryCache
import nox.kiwami.download.anime.OfflineAnimeFragment
import nox.kiwami.download.manga.OfflineMangaFragment
import nox.kiwami.media.MediaType
import nox.kiwami.getThemeColor
import nox.kiwami.initActivity
import nox.kiwami.isOnline
import nox.kiwami.snackString
import nox.kiwami.navBarHeight
import nox.kiwami.download.queue.DownloadQueueActivity
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.statusBarHeight
import nox.kiwami.themes.ThemeManager
import nox.kiwami.util.fadeInDown
import nox.kiwami.util.setGradientText
import nox.kiwami.util.setPressBounce
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DownloadsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDownloadsBinding
    private val downloadsManager = Injekt.get<DownloadsManager>()

    private lateinit var bannerArrow: DownloadArrowAnimator
    private var lastAnimeCount = -1
    private var lastMangaCount = -1
    private var lastCountsVersion = -1
    private var currentPage = 0
    private var seenRefreshId = DownloadRefresher.completed.value?.first ?: 0
    private var spinner: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initActivity(this)
        binding.downloadsRoot.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        binding.downloadsPager.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }

        binding.downloadsTitle.setGradientText(
            getThemeColor(androidx.appcompat.R.attr.colorPrimary),
            getThemeColor(com.google.android.material.R.attr.colorTertiary)
        )
        binding.downloadsTitle.fadeInDown()
        binding.downloadsSubtitle.fadeInDown(120)
        binding.downloadsBack.setPressBounce()
        binding.downloadsRefreshButton.setPressBounce()
        binding.downloadsBanner.setPressBounce(0.97f)

        binding.downloadsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val openQueue = View.OnClickListener {
            startActivity(Intent(this, DownloadQueueActivity::class.java))
        }
        binding.downloadsBanner.setOnClickListener(openQueue)
        binding.downloadsQueueButton.setPressBounce()
        binding.downloadsQueueButton.setOnClickListener(openQueue)
        binding.downloadsRefreshButton.setOnClickListener { refreshDownloads() }

        bannerArrow = DownloadArrowAnimator(
            binding.downloadsBannerIcon,
            33f * resources.displayMetrics.density
        )
        setupPager()
        refreshCounts()
        startLiveUpdates()
    }

    private fun refreshDownloads() {
        if (DownloadRefresher.isRunning) {
            snackString(getString(R.string.hub_refresh_running))
            return
        }
        if (DownloadActivityState.snapshot().isActive) {
            snackString(getString(R.string.hub_refresh_busy))
            return
        }
        if (!isOnline(this)) {
            snackString(getString(R.string.hub_refresh_offline))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hub_refresh_warning_title)
            .setMessage(R.string.hub_refresh_warning_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.hub_refresh_continue) { _, _ -> pickDownloadsToRefresh() }
            .show()
    }

    private fun pickDownloadsToRefresh() {
        val targets = DownloadRefresher.targets(downloadsManager)
        if (targets.isEmpty()) {
            snackString(getString(R.string.hub_refresh_nothing))
            return
        }
        val labels = targets.map {
            val kind = when (it.type) {
                MediaType.ANIME -> getString(R.string.anime)
                MediaType.MANGA -> getString(R.string.manga)
                else -> getString(R.string.hub_refresh_novel)
            }
            "${it.title} ($kind)"
        }.toTypedArray()
        val checked = BooleanArray(targets.size)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hub_refresh_pick_title)
            .setMultiChoiceItems(labels, checked) { dlg, which, isChecked ->
                checked[which] = isChecked
                (dlg as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = checked.any { it }
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.hub_refresh_select_all, null)
            .setPositiveButton(R.string.hub_refresh_start) { _, _ ->
                val selected = targets.filterIndexed { index, _ -> checked[index] }
                if (!DownloadRefresher.start(applicationContext, downloadsManager, selected)) {
                    snackString(getString(R.string.hub_refresh_running))
                }
            }
            .create()
        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            positive.isEnabled = false
            neutral.setOnClickListener {
                val all = !checked.all { it }
                checked.fill(all)
                for (i in checked.indices) dialog.listView.setItemChecked(i, all)
                positive.isEnabled = all
                neutral.setText(if (all) R.string.hub_refresh_select_none else R.string.hub_refresh_select_all)
            }
        }
        dialog.show()
    }

    private fun renderRefresh() {
        val progress = DownloadRefresher.progress.value
        if (progress != null) {
            if (spinner == null) {
                spinner = ObjectAnimator.ofFloat(binding.downloadsRefreshIcon, View.ROTATION, 0f, 360f).apply {
                    duration = 900
                    repeatCount = ObjectAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    start()
                }
            }
            binding.downloadsSubtitle.text =
                getString(R.string.hub_refreshing, progress.done, progress.total)
            return
        }
        spinner?.let {
            it.cancel()
            spinner = null
            binding.downloadsRefreshIcon.rotation = 0f
            lastCountsVersion = -1
            lastAnimeCount = -1
            lastMangaCount = -1
        }
        val completed = DownloadRefresher.completed.value
        if (completed != null && completed.first != seenRefreshId) {
            seenRefreshId = completed.first
            snackString(getString(R.string.hub_refresh_done, completed.second.refreshed, completed.second.total))
            recreate()
        }
    }

    private fun setupPager() {
        val pager = binding.downloadsPager
        pager.adapter = HubAdapter()
        pager.offscreenPageLimit = 1
        pager.setPageTransformer { page, position ->
            val d = abs(position).coerceAtMost(1f)
            page.alpha = 1f - 0.65f * d
            val scale = 1f - 0.08f * d
            page.scaleX = scale
            page.scaleY = scale
        }

        val startPage = PrefManager.getCustomVal("downloads_hub_last_tab", 0).coerceIn(0, 1)
        currentPage = startPage
        pager.setCurrentItem(startPage, false)

        binding.downloadsTabAnime.setOnClickListener { pager.setCurrentItem(0, true) }
        binding.downloadsTabManga.setOnClickListener { pager.setCurrentItem(1, true) }

        val segment = binding.downloadsSegment
        val thumb = binding.downloadsSegmentThumb
        val inset = (4 * resources.displayMetrics.density)

        fun thumbWidth(): Float = (segment.width - inset * 2) / 2f

        fun place(position: Int, offset: Float) {
            val w = thumbWidth()
            if (w <= 0f) return
            thumb.translationX = (position + offset) * w
            val travel = (position + offset)
            val bump = sin(PI * (travel - floor(travel))).toFloat().coerceAtLeast(0f)
            thumb.scaleX = 1f + 0.22f * bump
            thumb.scaleY = 1f - 0.12f * bump
            val progress = (position + offset).coerceIn(0f, 1f)
            tint(progress)
        }

        segment.post {
            thumb.updateLayoutParams<ViewGroup.LayoutParams> { width = thumbWidth().toInt() }
            place(currentPage, 0f)
        }

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                place(position, positionOffset)
            }

            override fun onPageSelected(position: Int) {
                currentPage = position
                PrefManager.setCustomVal("downloads_hub_last_tab", position)
            }
        })
    }

    private fun tint(progress: Float) {
        val on = getThemeColor(com.google.android.material.R.attr.colorOnPrimary)
        val off = getThemeColor(com.google.android.material.R.attr.colorOutline)
        binding.downloadsTabAnime.setTextColor(blend(on, off, progress))
        binding.downloadsTabManga.setTextColor(blend(off, on, progress))
        val a = 1f + 0.1f * (1f - progress)
        val m = 1f + 0.1f * progress
        binding.downloadsTabAnime.scaleX = a
        binding.downloadsTabAnime.scaleY = a
        binding.downloadsTabManga.scaleX = m
        binding.downloadsTabManga.scaleY = m
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val r = (android.graphics.Color.red(from) * (1 - t) + android.graphics.Color.red(to) * t).toInt()
        val g = (android.graphics.Color.green(from) * (1 - t) + android.graphics.Color.green(to) * t).toInt()
        val b = (android.graphics.Color.blue(from) * (1 - t) + android.graphics.Color.blue(to) * t).toInt()
        return android.graphics.Color.rgb(r, g, b)
    }

    private inner class HubAdapter : FragmentStateAdapter(supportFragmentManager, lifecycle) {
        override fun getItemCount() = 2
        override fun createFragment(position: Int): Fragment {
            val fragment: Fragment = if (position == 0) OfflineAnimeFragment() else OfflineMangaFragment()
            fragment.arguments = Bundle().apply { putBoolean(ARG_EMBEDDED, true) }
            return fragment
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCounts()
    }

    private fun refreshCounts() {
        val version = downloadsManager.version
        if (version == lastCountsVersion) return
        lastCountsVersion = version
        val anime = downloadsManager.distinctTitleCount(MediaType.ANIME)
        val manga = downloadsManager.distinctTitleCount(MediaType.MANGA)
        if (anime == lastAnimeCount && manga == lastMangaCount) return
        lastAnimeCount = anime
        lastMangaCount = manga
        binding.downloadsSubtitle.text = getString(R.string.hub_downloads_subtitle, anime, manga)
        binding.downloadsTabAnime.text = getString(R.string.hub_tab_count, getString(R.string.anime), anime)
        binding.downloadsTabManga.text = getString(R.string.hub_tab_count, getString(R.string.manga), manga)
    }

    private fun startLiveUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    renderState(DownloadActivityState.snapshot())
                    renderRefresh()
                    refreshCounts()
                    delay(600)
                }
            }
        }
    }

    private fun renderState(state: DownloadActivityState) {
        val banner = binding.downloadsBanner
        val badge = binding.downloadsQueueBadge
        if (state.isActive) {
            badge.text = if (state.total > 99) "99+" else state.total.toString()
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }
        if (state.isActive) {
            val pct = state.percent
            binding.downloadsBannerText.text =
                if (pct >= 0) getString(R.string.hub_downloading_pct, state.total, pct)
                else getString(R.string.hub_downloading, state.total)
            if (pct >= 0) {
                binding.downloadsBannerProgress.isIndeterminate = false
                binding.downloadsBannerProgress.setProgressCompat(pct, true)
            } else if (!binding.downloadsBannerProgress.isIndeterminate) {
                binding.downloadsBannerProgress.isIndeterminate = true
            }

            if (banner.visibility != View.VISIBLE) {
                banner.alpha = 0f
                banner.visibility = View.VISIBLE
                banner.animate().alpha(1f).setDuration(250).start()
            }
            bannerArrow.start()
        } else {
            bannerArrow.stop()
            if (banner.visibility == View.VISIBLE) {
                banner.animate().alpha(0f).setDuration(200)
                    .withEndAction { banner.visibility = View.GONE }.start()
            }
        }
    }

    override fun onDestroy() {
        if (::bannerArrow.isInitialized) bannerArrow.stop()
        super.onDestroy()
        if (::binding.isInitialized) binding.downloadsPager.adapter = null
    }

    companion object {
        const val ARG_EMBEDDED = "embedded"
    }
}
