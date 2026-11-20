package nox.kiwami.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import nox.kiwami.R

class SourceChipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val primary = resolve(androidx.appcompat.R.attr.colorPrimary)
    private val onBackground = resolve(com.google.android.material.R.attr.colorOnBackground)
    private val icon = ImageView(context)
    private val text = TextView(context)
    private val arrow = ImageView(context)
    private var options: List<String> = emptyList()
    private var labels: List<String> = emptyList()
    private var selected = 0
    private var onPick: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        minimumHeight = (34 * density).toInt()
        setPadding((11 * density).toInt(), 0, (6 * density).toInt(), 0)

        icon.imageTintList = ColorStateList.valueOf(onBackground)
        addView(icon, LayoutParams((16 * density).toInt(), (16 * density).toInt()))

        text.setTextColor(onBackground)
        text.textSize = 12f
        text.includeFontPadding = false
        text.maxLines = 1
        text.ellipsize = TextUtils.TruncateAt.END
        text.maxWidth = (150 * density).toInt()
        text.typeface = ResourcesCompat.getFont(context, R.font.poppins_bold)
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = (6 * density).toInt()
        })

        arrow.setImageResource(R.drawable.ic_round_arrow_drop_down_24)
        arrow.imageTintList = ColorStateList.valueOf(onBackground)
        arrow.alpha = 0.6f
        addView(arrow, LayoutParams((20 * density).toInt(), (20 * density).toInt()))

        val radius = 17f * density
        background = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(onBackground, 0x2E)),
            GradientDrawable().apply {
                cornerRadius = radius
                setColor(ColorUtils.setAlphaComponent(onBackground, 0x14))
                setStroke(density.toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(onBackground, 0x24))
            },
            GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.BLACK)
            }
        )
        setOnClickListener { showOptions() }
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f)
                    .setDuration(90).setInterpolator(DecelerateInterpolator()).start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f)
                    .scaleY(1f).setDuration(280).setInterpolator(OvershootInterpolator(2.2f)).start()
            }
            false
        }
    }

    fun setIcon(resource: Int) {
        icon.setImageResource(resource)
    }

    fun setOptions(
        entries: List<String>,
        chipLabels: List<String>,
        selectedIndex: Int,
        onChosen: (Int) -> Unit
    ) {
        options = entries
        labels = chipLabels
        selected = selectedIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        onPick = onChosen
        showLabel()
    }

    private fun showLabel() {
        text.text = labels.getOrNull(selected).orEmpty()
    }

    private fun showOptions() {
        if (options.isEmpty()) return
        val popup = ListPopupWindow(context)
        popup.anchorView = this
        popup.setAdapter(OptionAdapter())
        popup.setContentWidth(maxOf(width, (210 * density).toInt()))
        popup.isModal = true
        popup.verticalOffset = (6 * density).toInt()
        popup.setBackgroundDrawable(
            GradientDrawable().apply {
                cornerRadius = 18f * density
                setColor(resolve(android.R.attr.colorBackground))
                setStroke(density.toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(onBackground, 0x30))
            }
        )
        popup.setOnItemClickListener { _, _, position, _ ->
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            selected = position
            showLabel()
            onPick?.invoke(position)
            popup.dismiss()
        }
        popup.setOnDismissListener { rotateArrow(false) }
        rotateArrow(true)
        popup.show()
    }

    private fun rotateArrow(open: Boolean) {
        arrow.animate().rotation(if (open) 180f else 0f).setDuration(240)
            .setInterpolator(OvershootInterpolator(1.3f)).start()
    }

    private inner class OptionAdapter : BaseAdapter() {
        override fun getCount() = options.size

        override fun getItem(position: Int) = options[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? TextView) ?: TextView(context).apply {
                textSize = 13f
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                typeface = ResourcesCompat.getFont(context, R.font.poppins_bold)
                compoundDrawablePadding = (10 * density).toInt()
                setPadding((18 * density).toInt(), (13 * density).toInt(), (14 * density).toInt(), (13 * density).toInt())
            }
            val active = position == selected
            row.text = options[position]
            row.setTextColor(if (active) primary else onBackground)
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, if (active) R.drawable.ic_source_check_24 else 0, 0
            )
            TextViewCompat.setCompoundDrawableTintList(row, ColorStateList.valueOf(primary))
            return row
        }
    }

    private fun resolve(attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }
}
