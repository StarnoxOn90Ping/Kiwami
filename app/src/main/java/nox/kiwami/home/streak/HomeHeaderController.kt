package nox.kiwami.home.streak

import android.animation.ValueAnimator
import android.graphics.LinearGradient
import android.graphics.Rect
import android.graphics.Matrix
import android.graphics.Shader
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.constraintlayout.widget.ConstraintLayout
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import nox.kiwami.R
import nox.kiwami.databinding.FragmentHomeBinding
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlin.random.Random

class HomeHeaderController(private val b: FragmentHomeBinding) {

    private val ctx = b.root.context
    private val d = ctx.resources.displayMetrics.density
    private val onSurface = MaterialColors.getColor(
        b.root, com.google.android.material.R.attr.colorOnSurface
    )

    private var waveAnim: ValueAnimator? = null
    private var glowAnim: ValueAnimator? = null
    private var countAnim: ValueAnimator? = null
    private var styledStatus: StreakManager.Status? = null
    private var styledStage = -1
    private var lastCount = -1
    private var lastLineKey = ""
    private var lastRawLine = ""
    private var released = false
    private var resumedOnce = false

    private var animeAura: CardFireView? = null
    private var mangaAura: CardFireView? = null
    private var headroomHooked = false

    private var burningNow = false
    private var ignitionDone = false
    private var ignitionPending = false
    private var inPlaceSince = 0L
    private val ignitionCheck = Runnable { checkIgnition() }

    fun start() {
        b.homeUserDataProgressBar.visibility = View.GONE
        b.homeUserDataContainer.visibility = View.VISIBLE
        b.homeUserDataContainer.setOnClickListener {
            if (PrefManager.getVal<Boolean>(PrefName.StreakHaptics)) {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            b.homeStreakFire.flare()
        }
        centerOnRow(b.homeStreakCount, "0123456789")
        centerOnRow(b.homeStreakLine, null)
        refresh()

        // entrance: the three parts fade up one after another. (Alpha is animated on the leaf
        // views only, never on a parent, so nothing gets clipped by an off-screen layer.)
        listOf(b.homeStreakFire, b.homeStreakNumberBox, b.homeStreakLineBox).forEachIndexed { i, v ->
            val target = v.alpha
            v.alpha = 0f
            v.translationY = 16f * d
            v.animate().alpha(target).translationY(0f)
                .setStartDelay(120L + i * 120L).setDuration(520L)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    fun release() {
        released = true
        b.homeListContainer.removeCallbacks(ignitionCheck)
        waveAnim?.cancel(); waveAnim = null
        glowAnim?.cancel(); glowAnim = null
        countAnim?.cancel(); countAnim = null
        b.homeStreakCount.paint.shader = null
    }

    fun onReturn() {
        if (released) return
        if (!resumedOnce) {
            resumedOnce = true
            refresh()
        } else {
            refresh(forceNewLine = true)
        }
    }

    fun refresh(forceNewLine: Boolean = false) {
        if (released) return
        val snap = StreakManager.snapshot()
        val stage = StreakManager.stageOf(snap.count)
        val dim = when (snap.status) {
            StreakManager.Status.ACTIVE -> 0f
            StreakManager.Status.AT_RISK, StreakManager.Status.GRACE -> 0.45f
            else -> 1f
        }

        val showStreak = PrefManager.getVal<Boolean>(PrefName.StreakShow)
        val animated = PrefManager.getVal<Boolean>(PrefName.StreakFireAnimated)
        val sparks = PrefManager.getVal<Boolean>(PrefName.StreakSparks)
        val showLines = PrefManager.getVal<Boolean>(PrefName.StreakLines)
        val boost = StreakManager.intensityBoost()

        val vis = if (showStreak) View.VISIBLE else View.GONE
        b.homeStreakFire.visibility = vis
        b.homeStreakNumberBox.visibility = vis
        b.homeStreakLineBox.visibility = if (showStreak && showLines) View.VISIBLE else View.GONE
        b.homeUserDataContainer.isClickable = showStreak

        b.homeStreakFire.apply {
            setStage(stage)
            setBoost(boost)
            setAnimated(animated)
            setSparks(sparks)
            setDim(dim)
        }

        b.homeStreakNumFire.visibility = View.GONE

        styleNumber(snap.status, stage)
        setCount(snap.count)

        val key = "${snap.status}-${band(snap.count)}-${StreakManager.today()}"
        if (forceNewLine || key != lastLineKey) {
            lastLineKey = key
            val line = pickLine(snap)
            b.homeStreakLine.text = line
        }

        updateButtonFire(snap, stage, boost, animated, sparks)
    }

    private fun styleNumber(status: StreakManager.Status, stage: Int) {
        if (status == styledStatus && stage == styledStage) return
        styledStatus = status
        styledStage = stage

        val tv = b.homeStreakCount
        waveAnim?.cancel(); glowAnim?.cancel()

        val fm = tv.paint.fontMetrics
        val h = (fm.descent - fm.ascent).coerceAtLeast(1f)

        val fire = intArrayOf(0xFFFF5722.toInt(), 0xFFFFA000.toInt(), 0xFFFFE082.toInt(), 0xFFFFA000.toInt(), 0xFFFF5722.toInt())
        val grey = ColorUtils.setAlphaComponent(onSurface, 130)

        val burning = status == StreakManager.Status.ACTIVE
        val muted = status == StreakManager.Status.AT_RISK || status == StreakManager.Status.GRACE

        val colors = when {
            burning -> fire
            muted -> fire.map { ColorUtils.blendARGB(it, 0xFF808080.toInt(), 0.5f) }.toIntArray()
            else -> intArrayOf(grey, grey)
        }
        val shader = LinearGradient(0f, 0f, 0f, h, colors, null, Shader.TileMode.MIRROR)
        tv.paint.shader = shader

        if (burning || muted) {
            val m = Matrix()
            // the colour pattern repeats every [h] pixels, so sliding by [h] loops seamlessly
            waveAnim = ValueAnimator.ofFloat(0f, h).apply {
                duration = if (burning) 1600L else 3400L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    m.setTranslate(0f, -(it.animatedValue as Float))
                    shader.setLocalMatrix(m)
                    tv.invalidate()
                }
                start()
            }
            val glow = if (burning) 0xFFFF6D00.toInt() else 0x99B5651D.toInt()
            val maxR = (if (burning) 8f + 3f * stage else 7f) * d
            glowAnim = ValueAnimator.ofFloat(4f * d, maxR).apply {
                duration = 900L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    tv.setShadowLayer(it.animatedValue as Float, 0f, 0f, glow)
                }
                start()
            }
        } else {
            tv.setShadowLayer(0f, 0f, 0f, 0)
            tv.invalidate()
        }
    }

    private fun setCount(count: Int) {
        countAnim?.cancel()
        val tv = b.homeStreakCount
        if (lastCount in 0 until count) {
            countAnim = ValueAnimator.ofInt(lastCount, count).apply {
                duration = 800L
                interpolator = DecelerateInterpolator()
                addUpdateListener { tv.text = (it.animatedValue as Int).toString() }
                start()
            }
            b.homeStreakFire.flare(1800L)
        } else {
            tv.text = count.toString()
        }
        lastCount = count
    }

    private fun band(count: Int) = when {
        count <= 0 -> 0
        count == 1 -> 1
        count < 7 -> 2
        count < 14 -> 3
        count < 30 -> 4
        count < 60 -> 5
        count < 100 -> 6
        count < 200 -> 7
        count < 365 -> 8
        else -> 9
    }

    private fun pickLine(s: StreakManager.Snapshot): String {
        val res = when (s.status) {
            StreakManager.Status.NONE -> R.array.streak_lines_none
            StreakManager.Status.LOST -> R.array.streak_lines_lost
            StreakManager.Status.AT_RISK -> R.array.streak_lines_at_risk
            StreakManager.Status.GRACE -> R.array.streak_lines_grace
            StreakManager.Status.ACTIVE -> when (band(s.count)) {
                1 -> R.array.streak_lines_day1
                2 -> R.array.streak_lines_early
                3 -> R.array.streak_lines_week
                4 -> R.array.streak_lines_fortnight
                5 -> R.array.streak_lines_month
                6 -> R.array.streak_lines_m60
                7 -> R.array.streak_lines_m100
                8 -> R.array.streak_lines_m200
                else -> R.array.streak_lines_m365
            }
        }
        val lines = ctx.resources.getStringArray(res)
        val pool = lines.filter { it != lastRawLine }.ifEmpty { lines.toList() }
        val raw = pool[Random.nextInt(pool.size)]
        lastRawLine = raw
        return raw.replace("{n}", s.count.toString())
    }

    private fun updateButtonFire(
        snap: StreakManager.Snapshot, stage: Int, boost: Float, animated: Boolean, sparks: Boolean
    ) {
        val burning = stage >= StreakManager.BUTTON_FIRE_STAGE &&
                PrefManager.getVal<Boolean>(PrefName.StreakButtonFire) &&
                PrefManager.getVal<Boolean>(PrefName.StreakShow)
        burningNow = burning
        if (burning) {
            animeAura = animeAura ?: addAura(b.homeAnimeList)
            mangaAura = mangaAura ?: addAura(b.homeMangaList)
            hookHeadroom()
            if (!ignitionDone) scheduleIgnition()
        }
        val dimmed = if (snap.status == StreakManager.Status.ACTIVE) 0f else 0.35f

        listOfNotNull(animeAura, mangaAura).forEach { f ->
            if (burning) {
                f.setStage(stage)
                f.setBoost(boost)
                f.setAnimated(animated)
                f.setSparks(sparks)
                f.setDim(dimmed)
                f.visibility = View.VISIBLE
            } else {
                f.visibility = View.GONE
            }
        }
    }

    private fun addAura(card: View): CardFireView {
        val parent = card.parent as ConstraintLayout
        val fire = CardFireView(ctx).apply {
            setCornerRadius((card as? MaterialCardView)?.radius ?: (16f * d))
        }
        parent.addView(
            fire,
            ConstraintLayout.LayoutParams(0, 0).apply {
                startToStart = card.id
                endToEnd = card.id
                topToTop = card.id
                bottomToBottom = card.id
            }
        )
        return fire
    }

    private fun scheduleIgnition() {
        if (ignitionPending || ignitionDone || released) return
        ignitionPending = true
        inPlaceSince = 0L
        b.homeListContainer.postOnAnimation(ignitionCheck)
    }

    private fun checkIgnition() {
        if (released) return
        if (ignitionDone || !burningNow) {
            ignitionPending = false
            return
        }
        val inPlace = listOf(b.homeAnimeList, b.homeMangaList).all { c ->
            c.visibility == View.VISIBLE && c.isLaidOut && c.width > 0 &&
                    (c.animation?.hasEnded() ?: true)
        } && b.root.isShown
        if (inPlace) {
            val now = SystemClock.uptimeMillis()
            if (inPlaceSince == 0L) inPlaceSince = now
            if (now - inPlaceSince >= 350L) {
                ignitionDone = true
                ignitionPending = false
                listOfNotNull(animeAura, mangaAura).forEach { it.ignite() }
                return
            }
        } else {
            inPlaceSince = 0L
        }
        b.homeListContainer.postOnAnimation(ignitionCheck)
    }

    private fun hookHeadroom() {
        if (headroomHooked) return
        headroomHooked = true
        val list = b.homeListContainer
        val update = {
            val row = b.homeUserDataContainer.parent as? View
            if (row != null && row.parent === list.parent) {
                val headerContentBottom = row.bottom - row.paddingBottom
                listOf(b.homeAnimeList to animeAura, b.homeMangaList to mangaAura).forEach { (card, aura) ->
                    val free = list.top + card.top - headerContentBottom - 4 * d
                    aura?.setHeadroom(free.coerceAtLeast(0f))
                }
            }
        }
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        b.homeAnimeList.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        list.post { update() }
    }

    /**
     * Moves [tv] vertically so the part of its text you actually see is centred in the view, which
     * is itself centred on the header row (like the fire icon, search button and avatar).
     *
     * Fonts keep room above and below their glyphs for accents and descenders, so a TextView that
     * is centred by its line box looks a few dp off. Here the visible part is measured instead:
     * the top of a capital letter (or digit) on the first line down to the baseline of the last.
     * Re-measured whenever the view is laid out again (new text, auto-size).
     */
    private fun centerOnRow(tv: TextView, sample: String?) {
        val r = Rect()
        tv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val t = v as TextView
            val layout = t.layout
            if (layout == null || t.lineCount == 0 || t.height == 0) return@addOnLayoutChangeListener
            val text = sample ?: "H"
            t.paint.getTextBounds(text, 0, text.length, r)
            val glyphTop = r.top.toFloat()
            val firstBaseline = t.getLineBounds(0, null).toFloat()
            val lastBaseline = t.getLineBounds(t.lineCount - 1, null).toFloat()
            val visibleCentre = ((firstBaseline + glyphTop) + lastBaseline) / 2f
            val shift = t.height / 2f - visibleCentre
            if (kotlin.math.abs(shift) < 12f * d) t.translationY = shift
        }
    }
}
