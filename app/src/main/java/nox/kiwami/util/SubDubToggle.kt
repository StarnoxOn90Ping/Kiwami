package nox.kiwami.util

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.animation.OvershootInterpolator
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import nox.kiwami.R

class SubDubToggle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val segmentWidth = (56 * density).toInt()
    private val segmentHeight = (30 * density).toInt()
    private val inset = (3 * density).toInt()
    private val primary = resolve(androidx.appcompat.R.attr.colorPrimary)
    private val onPrimary = resolve(com.google.android.material.R.attr.colorOnPrimary)
    private val onBackground = resolve(com.google.android.material.R.attr.colorOnBackground)
    private val thumb = View(context)
    private val sub = label("SUB")
    private val dub = label("DUB")
    private var checked = false
    private var listener: ((SubDubToggle, Boolean) -> Unit)? = null
    private var tint: ValueAnimator? = null

    var isChecked: Boolean
        get() = checked
        set(value) = update(value, isLaidOut, true)

    init {
        clipChildren = false
        clipToPadding = false
        setPadding(inset, inset, inset, inset)
        background = GradientDrawable().apply {
            cornerRadius = 20f * density
            setColor(ColorUtils.setAlphaComponent(onBackground, 0x14))
            setStroke(density.toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(onBackground, 0x24))
        }
        thumb.background = GradientDrawable().apply {
            cornerRadius = 15f * density
            setColor(primary)
        }
        thumb.elevation = 3f * density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            thumb.outlineSpotShadowColor = primary
            thumb.outlineAmbientShadowColor = primary
        }
        addView(thumb, LayoutParams(segmentWidth, segmentHeight))
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.elevation = 4f * density
        row.addView(sub, LinearLayout.LayoutParams(segmentWidth, segmentHeight))
        row.addView(dub, LinearLayout.LayoutParams(segmentWidth, segmentHeight))
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        sub.setOnClickListener { choose(false) }
        dub.setOnClickListener { choose(true) }
        paint(false, false)
    }

    fun setOnCheckedChangeListener(onChanged: ((SubDubToggle, Boolean) -> Unit)?) {
        listener = onChanged
    }

    private fun choose(value: Boolean) {
        if (value == checked) return
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        update(value, true, true)
    }

    private fun update(value: Boolean, animate: Boolean, notify: Boolean) {
        if (value == checked) return
        checked = value
        val target = if (value) segmentWidth.toFloat() else 0f
        thumb.animate().cancel()
        if (animate) {
            thumb.animate().translationX(target).setDuration(340)
                .setInterpolator(OvershootInterpolator(1.15f)).start()
        } else {
            thumb.translationX = target
        }
        paint(value, animate)
        if (notify) listener?.invoke(this, value)
    }

    private fun paint(dubbed: Boolean, animate: Boolean) {
        val active = onPrimary
        val idle = ColorUtils.setAlphaComponent(onBackground, 0x99)
        tint?.cancel()
        if (!animate) {
            sub.setTextColor(if (dubbed) idle else active)
            dub.setTextColor(if (dubbed) active else idle)
            return
        }
        val fromSub = sub.currentTextColor
        val fromDub = dub.currentTextColor
        val toSub = if (dubbed) idle else active
        val toDub = if (dubbed) active else idle
        tint = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            addUpdateListener {
                val f = it.animatedFraction
                sub.setTextColor(ColorUtils.blendARGB(fromSub, toSub, f))
                dub.setTextColor(ColorUtils.blendARGB(fromDub, toDub, f))
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        tint?.cancel()
    }

    private fun label(text: String) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        includeFontPadding = false
        textSize = 12f
        letterSpacing = 0.04f
        typeface = ResourcesCompat.getFont(context, R.font.poppins_bold)
        setTextColor(Color.WHITE)
    }

    private fun resolve(attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }
}
