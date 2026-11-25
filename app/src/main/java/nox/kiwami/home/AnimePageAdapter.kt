package nox.kiwami.home

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LayoutAnimationController
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.color.MaterialColors
import nox.kiwami.MediaPageTransformer
import nox.kiwami.R
import nox.kiwami.connections.anilist.Anilist
import nox.kiwami.databinding.ItemAnimePageBinding
import nox.kiwami.databinding.LayoutTrendingBinding
import nox.kiwami.getAppString
import nox.kiwami.loadImage
import nox.kiwami.media.CalendarActivity
import nox.kiwami.media.GenreActivity
import nox.kiwami.media.Media
import nox.kiwami.media.MediaAdaptor
import nox.kiwami.media.MediaListViewActivity
import nox.kiwami.setSafeOnClickListener
import nox.kiwami.setSlideIn
import nox.kiwami.setSlideUp
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.statusBarHeight

class AnimePageAdapter : RecyclerView.Adapter<AnimePageAdapter.AnimePageViewHolder>() {
    private val cardPool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 16) }
    val ready = MutableLiveData(false)
    lateinit var binding: ItemAnimePageBinding
    private lateinit var trendingBinding: LayoutTrendingBinding
    private var trendHandler: Handler? = null
    private lateinit var trendRun: Runnable
    var trendingViewPager: ViewPager2? = null
    var selectedSeason = 1
    private var seasonSwitching = false
    private var seasonOutRunning = false
    private var seasonDirection = 0
    private var seasonToken = 0
    private var seasonPendingSwap: (() -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnimePageViewHolder {
        val binding =
            ItemAnimePageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AnimePageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AnimePageViewHolder, position: Int) {
        binding = holder.binding
        trendingBinding = LayoutTrendingBinding.bind(binding.root)
        trendingViewPager = trendingBinding.trendingViewPager

        listOf(
            binding.animePreviousSeason,
            binding.animeThisSeason,
            binding.animeNextSeason
        ).forEachIndexed { i, it ->
            it.setSafeOnClickListener {
                val direction = (i - selectedSeason).coerceIn(-1, 1)
                selectSeason(i, true)
                beginSeasonSwitch(direction)
                onSeasonClick.invoke(i)
            }
            it.setOnLongClickListener { onSeasonLongClick.invoke(i) }
        }
        binding.animeSeasonsCont.doOnLayout { updateSeasonSelector(false) }

        binding.animeGenreImage.loadImage("https://s4.anilist.co/file/anilistcdn/media/anime/banner/16498-8jpFCOcDmneX.jpg")
        binding.animeCalendarImage.loadImage("https://s4.anilist.co/file/anilistcdn/media/anime/banner/125367-hGPJLSNfprO3.jpg")

        binding.animeGenre.setOnClickListener {
            ContextCompat.startActivity(
                it.context,
                Intent(it.context, GenreActivity::class.java).putExtra("type", "ANIME"),
                null
            )
        }
        binding.animeCalendar.setOnClickListener {
            ContextCompat.startActivity(
                it.context,
                Intent(it.context, CalendarActivity::class.java),
                null
            )
        }

        binding.animeIncludeList.isVisible = Anilist.token != null

        binding.animeIncludeList.isChecked = PrefManager.getVal(PrefName.PopularAnimeList)

        binding.animeIncludeList.setOnCheckedChangeListener { _, isChecked ->
            onIncludeListClick.invoke(isChecked)

            PrefManager.setVal(PrefName.PopularAnimeList, isChecked)
        }
        if (ready.value == false)
            ready.postValue(true)
    }

    private fun seasonShift(): Float =
        20f * trendingBinding.trendingContainer.resources.displayMetrics.density

    private fun beginSeasonSwitch(direction: Int) {
        val container = trendingBinding.trendingContainer
        val token = ++seasonToken
        seasonSwitching = true
        seasonOutRunning = true
        seasonDirection = direction
        seasonPendingSwap = null
        container.animate().cancel()
        container.animate()
            .alpha(0.2f)
            .translationX(-direction * seasonShift())
            .setDuration(180)
            .setInterpolator(AccelerateInterpolator())
            .withLayer()
            .withEndAction {
                if (token != seasonToken) return@withEndAction
                seasonOutRunning = false
                seasonPendingSwap?.let { swap ->
                    seasonPendingSwap = null
                    swap()
                    playSeasonIn()
                }
            }
            .start()
    }

    private fun playSeasonIn() {
        val container = trendingBinding.trendingContainer
        seasonSwitching = false
        container.animate().cancel()
        container.translationX = seasonDirection * seasonShift()
        container.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withLayer()
            .start()
    }

    fun runSeasonSwitch(swap: () -> Unit) {
        when {
            !seasonSwitching -> swap()
            seasonOutRunning -> seasonPendingSwap = swap
            else -> {
                swap()
                playSeasonIn()
            }
        }
    }

    fun cancelSeasonSwitch() {
        if (!seasonSwitching) return
        val container = trendingBinding.trendingContainer
        seasonToken++
        seasonSwitching = false
        seasonOutRunning = false
        seasonPendingSwap = null
        container.animate().cancel()
        container.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(200)
            .withLayer()
            .start()
    }

    fun selectSeason(index: Int, animate: Boolean) {
        if (animate && index == selectedSeason) return
        selectedSeason = index
        updateSeasonSelector(animate)
    }

    private fun updateSeasonSelector(animate: Boolean) {
        val container = binding.animeSeasonsCont
        val indicator = binding.animeSeasonIndicator
        val itemWidth = (container.width - container.paddingStart - container.paddingEnd) / 3
        if (itemWidth <= 0) return
        if (indicator.layoutParams.width != itemWidth) {
            indicator.updateLayoutParams { width = itemWidth }
        }
        val target = (itemWidth * selectedSeason).toFloat()
        indicator.animate().cancel()
        if (animate) {
            indicator.animate()
                .translationX(target)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        } else {
            indicator.translationX = target
        }
        val selectedColor = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnPrimary)
        val idleColor = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant)
        listOf(
            binding.animePreviousSeason,
            binding.animeThisSeason,
            binding.animeNextSeason
        ).forEachIndexed { i, label ->
            val color = if (i == selectedSeason) selectedColor else idleColor
            if (animate && label.currentTextColor != color) {
                ObjectAnimator.ofArgb(label, "textColor", label.currentTextColor, color)
                    .setDuration(240)
                    .start()
            } else {
                label.setTextColor(color)
            }
        }
    }

    lateinit var onSeasonClick: ((Int) -> Unit)
    lateinit var onSeasonLongClick: ((Int) -> Boolean)
    lateinit var onIncludeListClick: ((Boolean) -> Unit)

    override fun getItemCount(): Int = 1

    fun updateHeight() {
        trendingViewPager!!.updateLayoutParams { height += statusBarHeight }
    }

    fun updateTrending(adaptor: MediaAdaptor) {
        trendingBinding.trendingProgressBar.visibility = View.GONE
        trendingBinding.trendingViewPager.adapter = adaptor
        val dotCount = adaptor.itemCount
        trendingBinding.trendingDots.setCount(dotCount)
        trendingBinding.trendingViewPager.offscreenPageLimit = 3
        trendingBinding.trendingViewPager.getChildAt(0).overScrollMode =
            RecyclerView.OVER_SCROLL_NEVER
        trendingBinding.trendingViewPager.setPageTransformer(MediaPageTransformer())
        trendHandler = Handler(Looper.getMainLooper())
        trendRun = Runnable {
            trendingBinding.trendingViewPager.currentItem += 1
        }
        trendingBinding.trendingViewPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    if (dotCount > 0) trendingBinding.trendingDots.select(position % dotCount)
                    trendHandler?.removeCallbacks(trendRun)
                    if (PrefManager.getVal(PrefName.TrendingScroller)) {
                        trendHandler!!.postDelayed(trendRun, 4000)
                    }
                }
            }
        )

        trendingBinding.trendingViewPager.layoutAnimation =
            LayoutAnimationController(setSlideIn(), 0.25f)
        binding.animeListContainer.layoutAnimation =
            LayoutAnimationController(setSlideIn(), 0.25f)
        binding.animeSeasonsCont.layoutAnimation =
            LayoutAnimationController(setSlideIn(), 0.25f)
    }

    fun updateRecent(adaptor: MediaAdaptor, media: MutableList<Media>) {
        binding.apply {
            init(
                adaptor,
                animeUpdatedRecyclerView,
                animeUpdatedProgressBar,
                animeRecently,
                animeRecentlyMore,
                getAppString(R.string.updated),
                media
            )
            animePopular.visibility = View.VISIBLE
            animePopular.startAnimation(setSlideUp())
            if (adaptor.itemCount == 0) {
                animeRecentlyContainer.visibility = View.GONE
            }
        }

    }

    fun updateMovies(adaptor: MediaAdaptor, media: MutableList<Media>) {
        binding.apply {
            init(
                adaptor,
                animeMoviesRecyclerView,
                animeMoviesProgressBar,
                animeMovies,
                animeMoviesMore,
                getAppString(R.string.trending_movies),
                media
            )
        }
    }

    fun updateTopRated(adaptor: MediaAdaptor, media: MutableList<Media>) {
        binding.apply {
            init(
                adaptor,
                animeTopRatedRecyclerView,
                animeTopRatedProgressBar,
                animeTopRated,
                animeTopRatedMore,
                getAppString(R.string.top_rated),
                media
            )
        }
    }

    fun updateMostFav(adaptor: MediaAdaptor, media: MutableList<Media>) {
        binding.apply {
            init(
                adaptor,
                animeMostFavRecyclerView,
                animeMostFavProgressBar,
                animeMostFav,
                animeMostFavMore,
                getAppString(R.string.most_favourite),
                media
            )
        }
    }

    fun init(
        adaptor: MediaAdaptor,
        recyclerView: RecyclerView,
        progress: View,
        title: View,
        more: View,
        string: String,
        media: MutableList<Media>
    ) {
        progress.visibility = View.GONE
        recyclerView.setHasFixedSize(true)
        val llm = LinearLayoutManager(
            recyclerView.context,
            LinearLayoutManager.HORIZONTAL,
            false
        ).apply {
            initialPrefetchItemCount = 4
        }
        recyclerView.setRecycledViewPool(cardPool)
        recyclerView.layoutManager = llm
        recyclerView.adapter = adaptor

        more.setOnClickListener {
            MediaListViewActivity.passedMedia = media.toCollection(ArrayList())
            ContextCompat.startActivity(
                it.context, Intent(it.context, MediaListViewActivity::class.java)
                    .putExtra("title", string),
                null
            )
        }
        recyclerView.visibility = View.VISIBLE
        title.visibility = View.VISIBLE
        more.visibility = View.VISIBLE
        title.startAnimation(setSlideUp())
        more.startAnimation(setSlideUp())
        recyclerView.layoutAnimation =
            LayoutAnimationController(setSlideIn(), 0.25f)
    }

    inner class AnimePageViewHolder(val binding: ItemAnimePageBinding) :
        RecyclerView.ViewHolder(binding.root)
}
