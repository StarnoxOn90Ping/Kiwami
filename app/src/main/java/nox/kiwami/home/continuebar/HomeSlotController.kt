package nox.kiwami.home.continuebar

import android.content.Intent
import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.color.MaterialColors
import nox.kiwami.R
import nox.kiwami.connections.anilist.AnilistHomeViewModel
import nox.kiwami.databinding.FragmentHomeBinding
import nox.kiwami.loadImage
import nox.kiwami.media.Media
import nox.kiwami.media.MediaDetailsActivity
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import java.io.Serializable

class HomeSlotController(
    private val binding: FragmentHomeBinding,
    private val model: AnilistHomeViewModel
) {
    private val slot = binding.homeSlot
    private var shown: Media? = null
    private var boundKey: String? = null

    private val activeDot by lazy {
        MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)
    }
    private val idleDot by lazy {
        ColorUtils.setAlphaComponent(
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface),
            90
        )
    }

    fun start(owner: LifecycleOwner) {
        slot.bind(binding.homeSlotContinue, binding.homeSlotSocial, binding.homeSlotDots)
        slot.onPageChanged = { page ->
            PrefManager.setVal(PrefName.HomeSlotPage, page)
            paintDots(page)
        }
        binding.homeSlotDots.setOnClickListener { slot.toggle() }
        binding.homeContinueBar.setOnClickListener { open() }
        model.getAnimeContinue().observe(owner) { refresh() }
        model.getMangaContinue().observe(owner) { refresh() }
        refresh()
    }

    fun refresh() {
        val layout = PrefManager.getVal<List<Boolean>>(PrefName.HomeLayout)
        val barOn = layout.getOrElse(CONTINUE_INDEX) { true }
        val socialOn = layout.getOrElse(SOCIAL_INDEX) { true }
        val media = if (barOn) {
            ContinueTracker.pick(model.getAnimeContinue().value, model.getMangaContinue().value)
        } else {
            null
        }
        val hasSocial = socialOn && binding.homeUserStatusContainer.visibility != View.GONE
        shown = media
        if (media != null) bind(media)
        slot.configure(media != null, hasSocial, PrefManager.getVal(PrefName.HomeSlotPage))
        paintDots(slot.page)
    }

    private fun paintDots(page: Int) {
        binding.homeSlotDotTop.backgroundTintList =
            ColorStateList.valueOf(if (page == 0) activeDot else idleDot)
        binding.homeSlotDotBottom.backgroundTintList =
            ColorStateList.valueOf(if (page == 1) activeDot else idleDot)
    }

    private fun bind(media: Media) {
        val progress = media.userProgress ?: 0
        val key = "${media.id}:$progress"
        if (key == boundKey) return
        boundKey = key
        val context = binding.root.context
        val isAnime = media.anime != null
        val total = (if (isAnime) media.anime?.totalEpisodes else media.manga?.totalChapters) ?: 0
        val fraction = if (total > 0) (progress.toFloat() / total).coerceIn(0f, 1f) else 0f
        binding.homeContinueBarImage.loadImage(media.banner ?: media.cover)
        binding.homeContinueBarTitle.text = media.mainName()
        binding.homeContinueBarText.text = context.getString(
            if (isAnime) R.string.home_continue_episode else R.string.home_continue_chapter,
            progress + 1
        )
        binding.homeContinueBarProgress.updateLayoutParams<LinearLayout.LayoutParams> {
            weight = fraction
        }
        binding.homeContinueBarProgressEmpty.updateLayoutParams<LinearLayout.LayoutParams> {
            weight = 1f - fraction
        }
    }

    private fun open() {
        val media = shown ?: return
        media.cameFromContinue = true
        val context = binding.root.context
        context.startActivity(
            Intent(context, MediaDetailsActivity::class.java)
                .putExtra("media", media as Serializable)
                .putExtra("forceContinue", true)
        )
    }

    private companion object {
        const val SOCIAL_INDEX = 7
        const val CONTINUE_INDEX = 9
    }
}
