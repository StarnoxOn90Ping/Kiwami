package nox.kiwami.home.continuebar

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.color.MaterialColors
import nox.kiwami.FileUrl
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

    private val pressDistance by lazy { 2f * binding.root.resources.displayMetrics.density }

    @SuppressLint("ClickableViewAccessibility")
    fun start(owner: LifecycleOwner) {
        binding.homeContinueBar.strokeColor = ColorUtils.setAlphaComponent(activeDot, 110)
        binding.homeContinuePlayHalo.backgroundTintList = ColorStateList.valueOf(activeDot)
        binding.homeContinueBar.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> press(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press(false)
            }
            false
        }
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

    private fun press(down: Boolean) {
        val bar = if (down) PRESS_BAR_SCALE else 1f
        val core = if (down) PRESS_CORE_SCALE else 1f
        binding.homeContinueBar.animate().scaleX(bar).scaleY(bar).setDuration(PRESS_MS).start()
        binding.homeContinuePlayCore.animate().scaleX(core).scaleY(core).setDuration(PRESS_MS).start()
        binding.homeContinuePlayIcon.animate()
            .translationX(if (down) pressDistance else 0f)
            .setDuration(PRESS_MS)
            .start()
    }

    private fun bind(media: Media) {
        val progress = media.userProgress ?: 0
        val key = "${media.id}:$progress"
        if (key == boundKey) return
        boundKey = key
        val context = binding.root.context
        val isAnime = media.anime != null
        (media.banner ?: media.cover)?.let {
            binding.homeContinueBarImage.loadImage(FileUrl(it), BANNER_WIDTH, BANNER_HEIGHT)
        }
        binding.homeContinueBarTitle.text = media.mainName()
        binding.homeContinueBarLabel.text = context.getString(
            if (isAnime) R.string.home_continue_episode else R.string.home_continue_chapter,
            progress + 1
        )
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
        const val BANNER_WIDTH = 260
        const val BANNER_HEIGHT = 66
        const val PRESS_MS = 120L
        const val PRESS_BAR_SCALE = 0.97f
        const val PRESS_CORE_SCALE = 0.9f
    }
}
