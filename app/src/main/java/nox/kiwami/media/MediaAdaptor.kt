package nox.kiwami.media

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nox.kiwami.R
import nox.kiwami.blurImage
import nox.kiwami.databinding.ItemMediaCompactBinding
import nox.kiwami.databinding.ItemMediaLargeBinding
import nox.kiwami.databinding.ItemMediaPageBinding
import nox.kiwami.databinding.ItemMediaPageSmallBinding
import nox.kiwami.loadBannerImage
import nox.kiwami.loadImage
import nox.kiwami.loadLogoImage
import nox.kiwami.setAnimation
import nox.kiwami.setSafeOnClickListener
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import com.flaviofaria.kenburnsview.RandomTransitionGenerator
import java.io.Serializable


class MediaAdaptor(
    var type: Int,
    val mediaList: MutableList<Media>?,
    private val activity: FragmentActivity,
    private val matchParent: Boolean = false,
    private val viewPager: ViewPager2? = null,
    private val fav: Boolean = false,
    private val isOtherUser: Boolean = false,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val releasingText by lazy { activity.getString(R.string.status_releasing) }
    private val episodePlural by lazy { activity.getString(R.string.episode_plural) }
    private val episodeSingular by lazy { activity.getString(R.string.episode_singular) }
    private val chapterPlural by lazy { activity.getString(R.string.chapter_plural) }
    private val chapterSingular by lazy { activity.getString(R.string.chapter_singular) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (type) {
            0 -> MediaViewHolder(
                ItemMediaCompactBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            1 -> MediaLargeViewHolder(
                ItemMediaLargeBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            2 -> MediaPageViewHolder(
                ItemMediaPageBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            3 -> MediaPageSmallViewHolder(
                ItemMediaPageSmallBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> throw IllegalArgumentException()
        }

    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (type) {
            0 -> {
                val vh = holder as MediaViewHolder
                val b = vh.binding
                setAnimation(activity, b.root)
                val media = mediaList?.getOrNull(position)
                if (media != null) {
                    if (media.id < 0) {
                        if (vh.skeleton != true) {
                            showSkeletonLayout(b)
                            vh.skeleton = true
                        }
                        b.itemCompactTitle.text = ""
                        b.itemCompactUserProgress.text = ""
                        b.itemCompactTotal.text = ""
                        b.itemCompactScoreBG.isVisible = false
                        b.itemCompactOngoing.isVisible = false
                        b.itemCompactType.isVisible = false
                        b.itemCompactImage.setImageResource(R.drawable.skeleton_cover_placeholder)
                    } else {
                        if (vh.skeleton != false) {
                            showContentLayout(b)
                            vh.skeleton = false
                        }
                        b.itemCompactScoreBG.isVisible = true
                        b.itemCompactImage.loadImage(media.cover)
                        b.itemCompactOngoing.isVisible =
                            media.status == releasingText
                        b.itemCompactTitle.text = media.userPreferredName
                        b.itemCompactScore.text =
                            ((if (media.userScore == 0) (media.meanScore
                                ?: 0) else media.userScore) / 10.0).toString()
                        b.itemCompactScoreBG.setBackgroundResource(if (media.userScore != 0) R.drawable.item_user_score else R.drawable.item_score)
                        b.itemCompactUserProgress.text = (media.userProgress ?: "~").toString()
                        if (media.relation != null) {
                            b.itemCompactRelation.text = "${media.relation}  "
                            b.itemCompactType.visibility = View.VISIBLE

                            if (media.relation!!.contains("\n")) {
                                b.itemCompactRelation.apply {
                                    isSingleLine = false
                                    maxLines = 2
                                    ellipsize = TextUtils.TruncateAt.START

                                    includeFontPadding = false
                                    setLineSpacing(0f, 0.9f)
                                }
                            } else {
                                b.itemCompactRelation.isSingleLine = true
                                b.itemCompactRelation.maxLines = 1
                            }
                        } else {
                            b.itemCompactType.visibility = View.GONE
                        }
                        
                        if (media.anime != null) {
                            if (media.relation != null) b.itemCompactTypeImage.setImageResource(R.drawable.ic_round_movie_filter_24)
                            b.itemCompactTotal.text =
                                " | ${if (media.anime.nextAiringEpisode != null) (media.anime.nextAiringEpisode.toString() + " | " + (media.anime.totalEpisodes ?: "~").toString()) else (media.anime.totalEpisodes ?: "~").toString()}"
                        } else if (media.manga != null) {
                            if (media.relation != null) b.itemCompactTypeImage.setImageResource(R.drawable.ic_round_import_contacts_24)
                            b.itemCompactTotal.text = " | ${media.manga.totalChapters ?: "~"}"
                        }
                    }
                    b.itemCompactProgressContainer.visibility = if (fav) View.GONE else View.VISIBLE
                }
            }

            1 -> {
                val b = (holder as MediaLargeViewHolder).binding
                setAnimation(activity, b.root)
                val media = mediaList?.get(position)
                if (media != null) {
                    b.itemCompactImage.loadImage(media.cover)
                    blurImage(b.itemCompactBanner, media.banner ?: media.cover)
                    b.itemCompactOngoing.isVisible =
                        media.status == releasingText
                    b.itemCompactTitle.text = media.userPreferredName
                    b.itemCompactScore.text =
                        ((if (media.userScore == 0) (media.meanScore
                            ?: 0) else media.userScore) / 10.0).toString()
                    b.itemCompactScoreBG.setBackgroundResource(if (media.userScore != 0) R.drawable.item_user_score else R.drawable.item_score)
                    if (media.anime != null) {
                        val itemTotal = " " + if ((media.anime.totalEpisodes
                                ?: 0) != 1
                        ) episodePlural else episodeSingular
                        b.itemTotal.text = itemTotal
                        b.itemCompactTotal.text =
                            if (media.anime.nextAiringEpisode != null) (media.anime.nextAiringEpisode.toString() + " / " + (media.anime.totalEpisodes
                                ?: "??").toString()) else (media.anime.totalEpisodes
                                ?: "??").toString()
                    } else if (media.manga != null) {
                        val itemTotal = " " + if ((media.manga.totalChapters
                                ?: 0) != 1
                        ) chapterPlural else chapterSingular
                        b.itemTotal.text = itemTotal
                        b.itemCompactTotal.text = "${media.manga.totalChapters ?: "??"}"
                    }
                    if (position == mediaList.size - 2 && viewPager != null) viewPager.post {
                        val start = mediaList.size
                        mediaList.addAll(mediaList)
                        val end = mediaList.size - start
                        notifyItemRangeInserted(start, end)
                    }
                }
            }

            2 -> {
                val b = (holder as MediaPageViewHolder).binding
                val media = mediaList?.get(position)
                if (media != null) {

                    val bannerAnimations: Boolean = PrefManager.getVal(PrefName.BannerAnimations)
                    if (bannerAnimations)
                        b.itemCompactBanner.setTransitionGenerator(
                            RandomTransitionGenerator(
                                (10000 + 15000 * ((PrefManager.getVal(PrefName.AnimationSpeed)) as Float)).toLong(),
                                AccelerateDecelerateInterpolator()
                            )
                        )
                    bindHeroBanner(media, if (bannerAnimations) b.itemCompactBanner else b.itemCompactBannerNoKen)
                    bindHeroContent(
                        media,
                        b.itemCompactTitle,
                        b.itemCompactLogo,
                        b.itemCompactLogoContainer,
                        b.itemCompactSynopsis,
                        b.itemCompactStatus,
                        b.itemCompactTotal,
                        b.itemTotal,
                        b.itemCompactScore,
                        b.itemCompactScoreBG,
                        b.itemCompactAddToList,
                        b.itemCompactCoverCard,
                        b.itemCompactCover,
                        b.itemCompactGenreChips
                    )
                    @SuppressLint("NotifyDataSetChanged")
                    if (position == mediaList.size - 2 && viewPager != null) viewPager.post {
                        val size = mediaList.size
                        mediaList.addAll(mediaList)
                        notifyItemRangeInserted(size - 1, mediaList.size)
                    }
                }
            }

            3 -> {
                val b = (holder as MediaPageSmallViewHolder).binding
                val media = mediaList?.get(position)
                if (media != null) {
                    val bannerAnimations: Boolean = PrefManager.getVal(PrefName.BannerAnimations)
                    if (bannerAnimations)
                        b.itemCompactBanner.setTransitionGenerator(
                            RandomTransitionGenerator(
                                (10000 + 15000 * ((PrefManager.getVal(PrefName.AnimationSpeed) as Float))).toLong(),
                                AccelerateDecelerateInterpolator()
                            )
                        )
                    bindHeroBanner(media, if (bannerAnimations) b.itemCompactBanner else b.itemCompactBannerNoKen)
                    bindHeroContent(
                        media,
                        b.itemCompactTitle,
                        b.itemCompactLogo,
                        b.itemCompactLogoContainer,
                        b.itemCompactSynopsis,
                        b.itemCompactStatus,
                        b.itemCompactTotal,
                        b.itemTotal,
                        b.itemCompactScore,
                        b.itemCompactScoreBG,
                        b.itemCompactAddToList,
                        b.itemCompactCoverCard,
                        b.itemCompactCover,
                        b.itemCompactGenreChips
                    )
                    @SuppressLint("NotifyDataSetChanged")
                    if (position == mediaList.size - 2 && viewPager != null) viewPager.post {
                        val size = mediaList.size
                        mediaList.addAll(mediaList)
                        notifyItemRangeInserted(size - 1, mediaList.size)
                    }
                }
            }
        }
    }

    private fun bindHeroBanner(media: Media, target: ImageView) {
        if (media.banner.isNullOrBlank()) blurImage(target, media.cover) else loadBannerImage(target, media.banner)
    }

    private fun bindHeroContent(
        media: Media,
        title: TextView,
        logo: ImageView,
        logoContainer: View,
        synopsis: TextView,
        status: TextView,
        total: TextView,
        totalLabel: TextView,
        score: TextView,
        scoreBackground: View,
        addToList: TextView,
        coverCard: View,
        cover: ImageView,
        chips: ViewGroup
    ) {
        title.text = media.userPreferredName
        bindCarouselLogo(media, title, logo, logoContainer = logoContainer)

        val description = media.description?.let { sanitizeDescription(it) }
        synopsis.text = description
        synopsis.isVisible = !description.isNullOrBlank()

        val hasBanner = !media.banner.isNullOrBlank()
        coverCard.isVisible = !hasBanner
        if (!hasBanner) cover.loadImage(media.cover)

        status.text = media.status ?: ""
        addToList.text = activity.getString(if (media.userStatus != null) R.string.hero_edit_list else R.string.hero_add_to_list)
        score.text = ((if (media.userScore == 0) (media.meanScore ?: 0) else media.userScore) / 10.0).toString()
        scoreBackground.setBackgroundResource(if (media.userScore != 0) R.drawable.hero_score_pill_user else R.drawable.hero_score_pill)

        if (media.anime != null) {
            totalLabel.text = " " + if ((media.anime.totalEpisodes ?: 0) != 1) episodePlural else episodeSingular
            total.text =
                if (media.anime.nextAiringEpisode != null) "${media.anime.nextAiringEpisode} / ${media.anime.totalEpisodes ?: "??"}"
                else (media.anime.totalEpisodes ?: "??").toString()
        } else if (media.manga != null) {
            totalLabel.text = " " + if ((media.manga.totalChapters ?: 0) != 1) chapterPlural else chapterSingular
            total.text = "${media.manga.totalChapters ?: "??"}"
        }

        chips.removeAllViews()
        val labels = media.genres.ifEmpty { media.tags }.take(4)
        val inflater = LayoutInflater.from(chips.context)
        labels.forEach {
            val chip = inflater.inflate(R.layout.item_hero_genre_chip, chips, false) as TextView
            chip.text = it
            chips.addView(chip)
        }
    }

    private fun showSkeletonLayout(b: ItemMediaCompactBinding) {
        val density = activity.resources.displayMetrics.density
        b.itemCompactTitle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            width = (108 * density).toInt()
            height = (12 * density).toInt()
            bottomMargin = (4 * density).toInt()
            topMargin = (4 * density).toInt()
        }
        b.itemCompactTitle.background = ContextCompat.getDrawable(b.root.context, R.drawable.skeleton_text_placeholder)
        b.itemCompactProgressContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            width = (54 * density).toInt()
            height = (8 * density).toInt()
            topMargin = (2 * density).toInt()
        }
        b.itemCompactProgressContainer.background = ContextCompat.getDrawable(b.root.context, R.drawable.skeleton_stats_placeholder)
    }

    private fun showContentLayout(b: ItemMediaCompactBinding) {
        val density = activity.resources.displayMetrics.density
        b.itemCompactTitle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            width = (108 * density).toInt()
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            bottomMargin = 0
            topMargin = 0
        }
        b.itemCompactTitle.background = null
        b.itemCompactProgressContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            topMargin = 0
        }
        b.itemCompactProgressContainer.background = null
    }

    private fun bindCarouselLogo(
        media: Media,
        titleView: TextView,
        logoView: ImageView,
        logoContainer: View? = null
    ) {
        val clearLogoEnabled: Boolean = PrefManager.getVal(PrefName.CarouselClearLogo)
        if (!clearLogoEnabled) {
            titleView.visibility = View.VISIBLE
            logoView.visibility = View.GONE
            logoContainer?.visibility = View.GONE
            return
        }

        val currentId = media.id
        logoView.tag = currentId

        fun showLogo(url: String) {
            logoView.loadLogoImage(
                url,
                onAccepted = {
                    if (logoView.tag == currentId) {
                        logoView.visibility = View.VISIBLE
                        logoContainer?.visibility = View.VISIBLE
                        titleView.visibility = View.GONE
                    }
                },
                onRejected = {
                    if (logoView.tag == currentId) {
                        titleView.visibility = View.VISIBLE
                        logoView.visibility = View.GONE
                        logoContainer?.visibility = View.GONE
                    }
                }
            )
        }

        fun showTitle() {
            titleView.visibility = View.VISIBLE
            logoView.visibility = View.GONE
            logoContainer?.visibility = View.GONE
        }

        if (!media.clearLogo.isNullOrBlank()) {
            showTitle()
            showLogo(media.clearLogo!!)
        } else {
            showTitle()
            activity.lifecycleScope.launch(Dispatchers.Main) {
                val logo = CarouselLogoResolver.resolve(media)
                if (logoView.tag == currentId) {
                    if (!logo.isNullOrBlank()) {
                        media.clearLogo = logo
                        showLogo(logo)
                    } else {
                        showTitle()
                    }
                }
            }
        }
    }

    private fun sanitizeDescription(desc: String): String {
        return desc
            .replace(Regex("<[^>]*>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    override fun getItemCount() = mediaList!!.size

    override fun getItemViewType(position: Int): Int {
        return type
    }

    fun randomOptionClick() {
        val media = if (!mediaList.isNullOrEmpty()) {
            mediaList.random()
        } else {
            null
        }
        media?.let {
            val index = mediaList?.indexOf(it) ?: -1
            clicked(index, null)
        }
    }

    inner class MediaViewHolder(val binding: ItemMediaCompactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        var skeleton: Boolean? = null

        init {
            if (matchParent) itemView.updateLayoutParams { width = -1 }
            itemView.setSafeOnClickListener {
                val currentAdapter = bindingAdapter as? MediaAdaptor ?: this@MediaAdaptor
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setSafeOnClickListener
                val media = currentAdapter.mediaList?.getOrNull(pos)
                if (media == null || media.id < 0) return@setSafeOnClickListener
                currentAdapter.clicked(
                    pos,
                    binding.itemCompactImage,
                    resizeBitmap(getBitmapFromImageView(binding.itemCompactImage), 100)
                )
            }
            itemView.setOnLongClickListener {
                val currentAdapter = bindingAdapter as? MediaAdaptor ?: this@MediaAdaptor
                currentAdapter.longClicked(bindingAdapterPosition)
            }
        }
    }

    inner class MediaLargeViewHolder(val binding: ItemMediaLargeBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setSafeOnClickListener {
                val currentAdapter = bindingAdapter as? MediaAdaptor ?: this@MediaAdaptor
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setSafeOnClickListener
                val media = currentAdapter.mediaList?.getOrNull(pos)
                if (media == null || media.id < 0) return@setSafeOnClickListener
                currentAdapter.clicked(
                    pos,
                    binding.itemCompactImage,
                    resizeBitmap(getBitmapFromImageView(binding.itemCompactImage), 100)
                )
            }
            itemView.setOnLongClickListener {
                val currentAdapter = bindingAdapter as? MediaAdaptor ?: this@MediaAdaptor
                currentAdapter.longClicked(bindingAdapterPosition)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class MediaPageViewHolder(val binding: ItemMediaPageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.itemCompactTitleContainer.setSafeOnClickListener { openHeroMedia(this) }
            binding.itemCompactDetails.setSafeOnClickListener { openHeroMedia(this) }
            binding.itemCompactAddToList.setSafeOnClickListener { editHeroList(this) }
            itemView.setOnTouchListener { _, _ -> true }
            binding.itemCompactTitleContainer.setOnLongClickListener { editHeroList(this) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class MediaPageSmallViewHolder(val binding: ItemMediaPageSmallBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.itemCompactTitleContainer.setSafeOnClickListener { openHeroMedia(this) }
            binding.itemCompactDetails.setSafeOnClickListener { openHeroMedia(this) }
            binding.itemCompactAddToList.setSafeOnClickListener { editHeroList(this) }
            itemView.setOnTouchListener { _, _ -> true }
            binding.itemCompactTitleContainer.setOnLongClickListener { editHeroList(this) }
        }
    }

    private fun openHeroMedia(holder: RecyclerView.ViewHolder) {
        val currentAdapter = holder.bindingAdapter as? MediaAdaptor ?: this
        val pos = holder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        val media = currentAdapter.mediaList?.getOrNull(pos)
        if (media == null || media.id < 0) return
        currentAdapter.clicked(pos, null)
    }

    private fun editHeroList(holder: RecyclerView.ViewHolder): Boolean {
        val currentAdapter = holder.bindingAdapter as? MediaAdaptor ?: this
        return currentAdapter.longClicked(holder.bindingAdapterPosition)
    }

    fun clicked(position: Int, itemCompactImage: ImageView?, bitmap: Bitmap? = null) {
        if ((mediaList?.size ?: 0) > position && position != -1) {
            val media = mediaList?.get(position) ?: return
            if (media.id < 0) return
            if (bitmap != null) MediaSingleton.bitmap = bitmap
            ContextCompat.startActivity(
                activity,
                Intent(activity, MediaDetailsActivity::class.java).putExtra(
                    "media",
                    media as Serializable
                ),
                if (itemCompactImage != null) {
                    ActivityOptionsCompat.makeSceneTransitionAnimation(
                        activity,
                        itemCompactImage,
                        ViewCompat.getTransitionName(itemCompactImage)!!
                    ).toBundle()
                } else {
                    null
                }
            )
        }
    }


    fun longClicked(position: Int): Boolean {
        if (isOtherUser) return false
        if ((mediaList?.size ?: 0) > position && position != -1) {
            val media = mediaList?.get(position) ?: return false
            if (media.id < 0) return false
            if (activity.supportFragmentManager.findFragmentByTag("list") == null) {
                MediaListDialogSmallFragment.newInstance(media)
                    .show(activity.supportFragmentManager, "list")
                return true
            }
        }
        return false
    }

    fun getBitmapFromImageView(imageView: ImageView): Bitmap? {
        val drawable = imageView.drawable ?: return null

        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }

        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else imageView.width
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else imageView.height
        if (width <= 0 || height <= 0) return null

        val bitmap = try {
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            )
        } catch (_: Exception) {
            return null
        }

        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        return bitmap
    }

    fun resizeBitmap(source: Bitmap?, maxDimension: Int): Bitmap? {
        if (source == null) return null
        val width = source.width
        val height = source.height
        val newWidth: Int
        val newHeight: Int

        if (width > height) {
            newWidth = maxDimension
            newHeight = (height * (maxDimension.toFloat() / width)).toInt()
        } else {
            newHeight = maxDimension
            newWidth = (width * (maxDimension.toFloat() / height)).toInt()
        }

        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }

}
