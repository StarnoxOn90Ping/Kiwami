package nox.kiwami.home

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LayoutAnimationController
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import nox.kiwami.MediaPageTransformer
import nox.kiwami.R
import nox.kiwami.connections.anilist.Anilist
import nox.kiwami.databinding.ItemMangaPageBinding
import nox.kiwami.databinding.LayoutTrendingBinding
import nox.kiwami.getAppString
import nox.kiwami.loadImage
import nox.kiwami.media.GenreActivity
import nox.kiwami.media.Media
import nox.kiwami.media.MediaAdaptor
import nox.kiwami.media.MediaListViewActivity
import nox.kiwami.media.SearchActivity
import nox.kiwami.setSafeOnClickListener
import nox.kiwami.setSlideIn
import nox.kiwami.setSlideUp
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.statusBarHeight

class MangaPageAdapter : RecyclerView.Adapter<MangaPageAdapter.MangaPageViewHolder>() {
    private val cardPool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 16) }
    val ready = MutableLiveData(false)
    lateinit var binding: ItemMangaPageBinding
    private lateinit var trendingBinding: LayoutTrendingBinding
    private var trendHandler: Handler? = null
    private lateinit var trendRun: Runnable
    var trendingViewPager: ViewPager2? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MangaPageViewHolder {
        val binding =
            ItemMangaPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MangaPageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MangaPageViewHolder, position: Int) {
        binding = holder.binding
        trendingBinding = LayoutTrendingBinding.bind(binding.root)
        trendingViewPager = trendingBinding.trendingViewPager

        binding.mangaGenreImage.loadImage("https://s4.anilist.co/file/anilistcdn/media/manga/banner/105778-wk5qQ7zAaTGl.jpg")
        binding.mangaTopScoreImage.loadImage("https://s4.anilist.co/file/anilistcdn/media/manga/banner/30002-3TuoSMl20fUX.jpg")

        binding.mangaGenre.setOnClickListener {
            ContextCompat.startActivity(
                it.context,
                Intent(it.context, GenreActivity::class.java).putExtra("type", "MANGA"),
                null
            )
        }
        binding.mangaTopScore.setOnClickListener {
            ContextCompat.startActivity(
                it.context,
                Intent(it.context, SearchActivity::class.java)
                    .putExtra("type", "MANGA")
                    .putExtra("sortBy", Anilist.sortBy[0])
                    .putExtra("search", true),
                null
            )
        }

        binding.mangaIncludeList.isVisible = Anilist.token != null

        binding.mangaIncludeList.isChecked = PrefManager.getVal(PrefName.PopularMangaList)

        binding.mangaIncludeList.setOnCheckedChangeListener { _, isChecked ->
            onIncludeListClick.invoke(isChecked)

            PrefManager.setVal(PrefName.PopularMangaList, isChecked)
        }
        if (ready.value == false)
            ready.postValue(true)
    }

    lateinit var onIncludeListClick: ((Boolean) -> Unit)

    override fun getItemCount(): Int = 1

    fun updateHeight() {
        trendingViewPager!!.updateLayoutParams { height += statusBarHeight }
    }

    fun updateTrending(adaptor: MediaAdaptor) {
        trendingBinding.trendingProgressBar.visibility = View.GONE
        trendingBinding.trendingViewPager.adapter = adaptor
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
                    trendHandler?.removeCallbacks(trendRun)
                    if (PrefManager.getVal(PrefName.TrendingScroller))
                        trendHandler!!.postDelayed(trendRun, 4000)
                }
            }
        )

        trendingBinding.trendingViewPager.layoutAnimation =
            LayoutAnimationController(setSlideIn(), 0.25f)
        binding.mangaListContainer.layoutAnimation =
            LayoutAnimationController(setSlideIn(), 0.25f)

    }

    fun updateTrendingManga(adaptor: MediaAdaptor, media: MutableList<Media>) {
        binding.apply {
            init(
                adaptor,
                mangaTrendingMangaRecyclerView,
                mangaTrendingMangaProgressBar,
                mangaTrendingManga,
                mangaTrendingMangaMore,
                getAppString(R.string.trending_manga),
                media
            )
        }
    }

    fun updateTrendingManhwa(adaptor: MediaAdaptor, media: MutableList<Media>) {
        binding.apply {
            init(
                adaptor,
                mangaTrendingManhwaRecyclerView,
                mangaTrendingManhwaProgressBar,
                mangaTrendingManhwa,
                mangaTrendingManhwaMore,
                getAppString(R.string.trending_manhwa),
                media
            )
        }
    }

    fun updateNovel(adaptor: MediaAdaptor, media: MutableList<Media>) {
        binding.apply {
            init(
                adaptor,
                mangaNovelRecyclerView,
                mangaNovelProgressBar,
                mangaNovel,
                mangaNovelMore,
                getAppString(R.string.trending_novel),
                media
            )
        }
    }

    fun updateTopRated(adaptor: MediaAdaptor, media: MutableList<Media>) {
        binding.apply {
            init(
                adaptor,
                mangaTopRatedRecyclerView,
                mangaTopRatedProgressBar,
                mangaTopRated,
                mangaTopRatedMore,
                getAppString(R.string.top_rated),
                media
            )
        }
    }

    fun updateMostFav(adaptor: MediaAdaptor, media: MutableList<Media>) {
        binding.apply {
            init(
                adaptor,
                mangaMostFavRecyclerView,
                mangaMostFavProgressBar,
                mangaMostFav,
                mangaMostFavMore,
                getAppString(R.string.most_favourite),
                media
            )
            mangaPopular.visibility = View.VISIBLE
            mangaPopular.startAnimation(setSlideUp())
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

    inner class MangaPageViewHolder(val binding: ItemMangaPageBinding) :
        RecyclerView.ViewHolder(binding.root)
}
