package nox.kiwami.media

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import nox.kiwami.R
import nox.kiwami.util.SourceGlowView
import nox.kiwami.util.SourceStatusView

class SourceCardController(
    private val card: View,
    private val glow: SourceGlowView,
    private val badge: ImageView,
    private val letter: TextView,
    private val nameView: TextView,
    private val indicator: SourceStatusView,
    private val statusText: TextView,
    private val chevron: View,
    private val pill: View,
    private val pillText: TextView,
    private val pillIcon: ImageView,
    private val gear: View,
    private val nameSource: AutoCompleteTextView,
    private val titleSource: TextView,
    private val unit: String,
    private val iconFor: (String) -> Drawable?,
    private val onOpen: () -> Unit
) {

    private enum class Phase { SEARCHING, FOUND, MISSING }

    private val density = card.resources.displayMetrics.density
    private var phase = Phase.SEARCHING
    private var title = ""
    private var busyText: String? = null
    private var count = 0
    private var settled = false
    private var pulse: ObjectAnimator? = null

    private val nameWatcher = watcher { applyName() }
    private val titleWatcher = watcher { applyTitle() }

    fun bind() {
        nameSource.addTextChangedListener(nameWatcher)
        titleSource.addTextChangedListener(titleWatcher)
        nameSource.setOnDismissListener { rotateChevron(false) }
        card.setOnClickListener {
            rotateChevron(true)
            onOpen()
        }
        pressScale(card)
        styleStatic()
        badge.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 14f * density)
            }
        }
        badge.clipToOutline = true
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 24f * density)
            }
        }
        card.clipToOutline = true
        applyName()
        applyTitle()
        render()
        settled = true
    }

    fun release() {
        nameSource.removeTextChangedListener(nameWatcher)
        titleSource.removeTextChangedListener(titleWatcher)
        nameSource.setOnDismissListener(null)
        pulse?.cancel()
        pulse = null
    }

    fun onResult(found: Boolean, total: Int) {
        if (found) {
            count = total
            busyText = null
            if (title.isEmpty()) title = nameView.text.toString()
            phase = Phase.FOUND
        } else {
            count = 0
            phase = Phase.MISSING
        }
        render()
    }

    fun onLoading() {
        if (phase == Phase.SEARCHING) return
        phase = Phase.SEARCHING
        count = 0
        render()
    }

    private fun applyName() {
        val name = nameSource.text?.toString().orEmpty().trim()
        val previous = nameView.text.toString()
        if (name == previous && settled) return
        nameView.text = name
        val icon = iconFor(name)
        val color = colorFor(name, icon)
        glow.setGlowColor(color, settled)
        if (icon != null) {
            badge.setImageDrawable(icon)
            badge.background = null
            letter.visibility = View.GONE
        } else {
            badge.setImageDrawable(null)
            badge.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(color, darker(color))
            ).apply { cornerRadius = 14f * density }
            letter.text = name.take(1).uppercase()
            letter.visibility = View.VISIBLE
        }
        if (settled && previous.isNotEmpty() && previous != name) {
            badge.animate().cancel()
            badge.scaleX = 0.75f
            badge.scaleY = 0.75f
            letter.scaleX = 0.75f
            letter.scaleY = 0.75f
            badge.animate().scaleX(1f).scaleY(1f).setDuration(420)
                .setInterpolator(OvershootInterpolator(3f)).start()
            letter.animate().scaleX(1f).scaleY(1f).setDuration(420)
                .setInterpolator(OvershootInterpolator(3f)).start()
        }
    }

    private fun applyTitle() {
        val raw = titleSource.text?.toString().orEmpty().trim()
        if (raw.isEmpty()) return
        val split = raw.indexOf(" : ")
        val head = if (split > 0) raw.substring(0, split).trim() else ""
        val tail = if (split > 0) raw.substring(split + 3).trim() else raw
        val context = card.context
        when {
            head.equals("Searching", true) -> {
                phase = Phase.SEARCHING
                title = tail
                busyText = null
                count = 0
            }

            head.equals(context.getString(R.string.found), true) ||
                    head.equals(context.getString(R.string.selected), true) -> {
                phase = Phase.FOUND
                title = tail
                busyText = null
            }

            else -> {
                phase = Phase.SEARCHING
                busyText = raw
            }
        }
        render()
    }

    private var shownPhase: Phase? = null

    private fun render() {
        val changed = shownPhase != phase
        shownPhase = phase
        when (phase) {
            Phase.SEARCHING -> {
                indicator.setMode(SourceStatusView.Mode.SPIN, settled)
                statusText.text = busyText
                    ?: if (title.isNotEmpty()) "Searching · $title" else "Searching…"
                startPulse()
            }

            Phase.FOUND -> {
                indicator.setMode(SourceStatusView.Mode.CHECK, settled)
                stopPulse()
                statusText.text = if (count > 0) "$title · $count $unit" else title
                if (changed && settled) haptic()
            }

            Phase.MISSING -> {
                indicator.setMode(SourceStatusView.Mode.WARN, settled)
                stopPulse()
                statusText.text = "No match on this source"
                if (changed && settled) shake()
            }
        }
    }

    private fun startPulse() {
        if (pulse != null) return
        pulse = ObjectAnimator.ofFloat(statusText, View.ALPHA, 1f, 0.5f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        statusText.alpha = 1f
    }

    private fun haptic() {
        card.performHapticFeedback(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                HapticFeedbackConstants.CONFIRM
            else
                HapticFeedbackConstants.CONTEXT_CLICK
        )
    }

    private fun shake() {
        ObjectAnimator.ofFloat(
            card, View.TRANSLATION_X, 0f, -10f, 10f, -7f, 7f, -3f, 3f, 0f
        ).setDuration(380).start()
    }

    private fun rotateChevron(open: Boolean) {
        chevron.animate()
            .rotation(if (open) 180f else 0f)
            .setDuration(260)
            .setInterpolator(OvershootInterpolator(1.3f))
            .start()
    }

    private fun pressScale(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f)
                    .setDuration(90).setInterpolator(DecelerateInterpolator()).start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f)
                    .scaleY(1f).setDuration(300).setInterpolator(OvershootInterpolator(2.2f))
                    .start()
            }
            false
        }
    }

    private fun styleStatic() {
        val primary = resolve(androidx.appcompat.R.attr.colorPrimary)
        val onBackground = resolve(com.google.android.material.R.attr.colorOnBackground)
        val ripple = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 0x33))
        pill.background = RippleDrawable(
            ripple,
            GradientDrawable().apply {
                cornerRadius = 20f * density
                setColor(ColorUtils.setAlphaComponent(primary, 0x1F))
                setStroke(density.toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(primary, 0x73))
            },
            GradientDrawable().apply {
                cornerRadius = 20f * density
                setColor(Color.BLACK)
            }
        )
        pillText.setTextColor(primary)
        pillIcon.imageTintList = ColorStateList.valueOf(primary)
        gear.background = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(onBackground, 0x2E)),
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ColorUtils.setAlphaComponent(onBackground, 0x14))
            },
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.BLACK)
            }
        )
        chevron.alpha = 0.6f
    }

    private fun colorFor(name: String, icon: Drawable?): Int {
        val sampled = icon?.let { averageColor(it) }
        if (sampled != null) {
            val hsv = FloatArray(3)
            Color.colorToHSV(sampled, hsv)
            hsv[1] = maxOf(hsv[1], 0.5f)
            hsv[2] = maxOf(hsv[2], 0.85f)
            return Color.HSVToColor(hsv)
        }
        val hue = ((name.lowercase().hashCode() and 0x7fffffff) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.95f))
    }

    private fun averageColor(drawable: Drawable): Int? {
        val size = 16
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val saved = Rect(drawable.bounds)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        drawable.setBounds(saved.left, saved.top, saved.right, saved.bottom)
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.recycle()
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (pixel in pixels) {
            if (Color.alpha(pixel) > 40) {
                r += Color.red(pixel)
                g += Color.green(pixel)
                b += Color.blue(pixel)
                n++
            }
        }
        if (n == 0) return null
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun darker(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = hsv[2] * 0.62f
        return Color.HSVToColor(hsv)
    }

    private fun resolve(attr: Int): Int {
        val value = TypedValue()
        card.context.theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun watcher(block: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            block()
        }
    }
}
