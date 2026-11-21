package nox.kiwami.media

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.drawToBitmap
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import nox.kiwami.R
import nox.kiwami.databinding.DialogSourcePickerBinding
import nox.kiwami.databinding.ItemSourcePickerBinding
import nox.kiwami.settings.saving.PrefManager

object SourcePicker {

    data class Item(val name: String, val icon: Drawable?, val subtitle: String)

    fun subtitle(lang: String?, version: String?, nsfw: Boolean): String {
        if (lang == null && version == null) return "Built-in"
        val parts = mutableListOf<String>()
        if (lang != null) parts.add(if (lang.equals("all", true)) "Multi" else lang.uppercase())
        if (version != null) parts.add("v$version")
        if (nsfw) parts.add("18+")
        return parts.joinToString(" · ")
    }

    fun show(
        context: Context,
        items: List<Item>,
        selected: String,
        recentsKey: String,
        onPick: (Int) -> Unit,
        onDismiss: () -> Unit,
        origin: View? = null,
        onLift: (Boolean) -> Unit = {}
    ) {
        val binding = DialogSourcePickerBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(binding.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        var cleanup: (() -> Unit)? = null
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundResource(R.drawable.bg_source_sheet)
            if (origin != null && items.any { it.name == selected }) {
                cleanup = flyBadge(dialog, origin, binding.sourcePickerList, onLift)
            }
        }
        dialog.setOnDismissListener {
            cleanup?.invoke()
            if (origin != null) onLift(false)
            onDismiss()
        }

        val key = "kiwami_recent_sources_$recentsKey"
        val stored: String = PrefManager.getCustomVal(key, "")
        val recents = stored.split("\n").filter { name -> name.isNotEmpty() && items.any { it.name == name } }
        val recentNames = recents.filter { it != selected }.take(3)

        val selectedIndex = items.indexOfFirst { it.name == selected }
        val ordered = buildList {
            if (selectedIndex >= 0) add(selectedIndex)
            recentNames.forEach { name -> add(items.indexOfFirst { it.name == name }) }
            items.indices.forEach { if (!contains(it)) add(it) }
        }

        var picked = false
        val adapter = RowAdapter(items, selected, recentNames.toSet()) { index, row ->
            if (picked) return@RowAdapter
            picked = true
            row.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            val updated = (listOf(items[index].name) + recents.filter { it != items[index].name }).take(5)
            PrefManager.setCustomVal(key, updated.joinToString("\n"))
            if (items[index].name != selected) onPick(index)
            row.postDelayed({ dialog.dismiss() }, 220)
        }
        adapter.rows = ordered

        binding.sourcePickerCount.text = "${items.size} installed"
        binding.sourcePickerList.layoutManager = LinearLayoutManager(context)
        binding.sourcePickerList.adapter = adapter
        binding.sourcePickerList.itemAnimator = null
        binding.sourcePickerSearchBox.visibility = if (items.size > 5) View.VISIBLE else View.GONE
        binding.sourcePickerSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty().trim()
                adapter.animateIn = false
                adapter.showPills = query.isEmpty()
                adapter.rows = ordered.filter { items[it].name.contains(query, true) }
                adapter.notifyDataSetChanged()
                binding.sourcePickerEmpty.visibility =
                    if (adapter.rows.isEmpty()) View.VISIBLE else View.GONE
            }
        })
        binding.sourcePickerList.postDelayed({ adapter.animateIn = false }, 800)
        dialog.show()
    }

    private fun flyBadge(
        dialog: BottomSheetDialog,
        origin: View,
        list: RecyclerView,
        onLift: (Boolean) -> Unit
    ): (() -> Unit)? {
        val decor = dialog.window?.decorView as? ViewGroup ?: return null
        if (origin.width == 0 || origin.height == 0) return null
        val fly = ImageView(dialog.context)
        fly.setImageBitmap(origin.drawToBitmap())
        val radius = 14f * origin.resources.displayMetrics.density
        fly.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        fly.clipToOutline = true
        val from = IntArray(2)
        origin.getLocationOnScreen(from)
        val base = IntArray(2)
        decor.getLocationOnScreen(base)
        decor.addView(fly, FrameLayout.LayoutParams(origin.width, origin.height))
        fly.pivotX = 0f
        fly.pivotY = 0f
        fly.x = (from[0] - base[0]).toFloat()
        fly.y = (from[1] - base[1]).toFloat()
        onLift(true)
        list.postDelayed({
            if (!dialog.isShowing) return@postDelayed
            val holder = list.findViewHolderForAdapterPosition(0) as? RowAdapter.Holder
            val target = holder?.binding?.sourceRowBadge?.parent as? View
            if (target == null) {
                decor.removeView(fly)
                return@postDelayed
            }
            target.alpha = 0f
            val to = IntArray(2)
            target.getLocationOnScreen(to)
            fly.animate()
                .x((to[0] - base[0]).toFloat())
                .y((to[1] - base[1]).toFloat())
                .scaleX(target.width.toFloat() / origin.width)
                .scaleY(target.height.toFloat() / origin.height)
                .setDuration(520)
                .setInterpolator(OvershootInterpolator(0.8f))
                .withEndAction {
                    decor.removeView(fly)
                    target.alpha = 1f
                    target.scaleX = 0.85f
                    target.scaleY = 0.85f
                    target.animate().scaleX(1f).scaleY(1f).setDuration(320)
                        .setInterpolator(OvershootInterpolator(3f)).start()
                }
                .start()
        }, 340)
        return { decor.removeView(fly) }
    }

    private class RowAdapter(
        private val items: List<Item>,
        private val selectedName: String,
        private val recentNames: Set<String>,
        private val onClick: (Int, View) -> Unit
    ) : RecyclerView.Adapter<RowAdapter.Holder>() {

        var rows: List<Int> = emptyList()
        var showPills = true
        var animateIn = true

        class Holder(val binding: ItemSourcePickerBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val holder = Holder(
                ItemSourcePickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
            val radius = 12f * parent.resources.displayMetrics.density
            holder.binding.sourceRowBadge.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            holder.binding.sourceRowBadge.clipToOutline = true
            return holder
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val index = rows[position]
            val item = items[index]
            val b = holder.binding
            val context = b.root.context
            val density = context.resources.displayMetrics.density
            val primary = resolve(context, androidx.appcompat.R.attr.colorPrimary)
            val onBackground = resolve(context, com.google.android.material.R.attr.colorOnBackground)

            b.root.animate().cancel()
            b.root.alpha = 1f
            b.root.translationY = 0f
            b.root.scaleX = 1f
            b.root.scaleY = 1f

            b.sourceRowName.text = item.name
            b.sourceRowSubtitle.text = item.subtitle
            val color = SourceVisuals.colorFor(item.name, item.icon)
            if (item.icon != null) {
                b.sourceRowBadge.setImageDrawable(item.icon)
                b.sourceRowBadge.background = null
                b.sourceRowLetter.visibility = View.GONE
            } else {
                b.sourceRowBadge.setImageDrawable(null)
                b.sourceRowBadge.background = SourceVisuals.letterBadge(color, 12f * density)
                b.sourceRowLetter.text = item.name.take(1).uppercase()
                b.sourceRowLetter.visibility = View.VISIBLE
            }

            val isSelected = item.name == selectedName
            val fill = GradientDrawable().apply {
                cornerRadius = 18f * density
                if (isSelected) {
                    setColor(ColorUtils.setAlphaComponent(primary, 0x1F))
                    setStroke(density.toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(primary, 0x80))
                } else {
                    setColor(ColorUtils.setAlphaComponent(onBackground, 0x0A))
                }
            }
            val mask = GradientDrawable().apply {
                cornerRadius = 18f * density
                setColor(Color.BLACK)
            }
            b.root.background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(onBackground, 0x26)),
                fill,
                mask
            )
            b.sourceRowCheck.imageTintList = ColorStateList.valueOf(primary)
            b.sourceRowCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            b.sourceRowCheck.scaleX = 1f
            b.sourceRowCheck.scaleY = 1f
            b.sourceRowPill.visibility =
                if (!isSelected && showPills && item.name in recentNames) View.VISIBLE else View.GONE

            b.root.setOnClickListener {
                if (!isSelected) {
                    b.sourceRowCheck.visibility = View.VISIBLE
                    b.sourceRowCheck.scaleX = 0.2f
                    b.sourceRowCheck.scaleY = 0.2f
                    b.sourceRowCheck.animate().scaleX(1f).scaleY(1f).setDuration(380)
                        .setInterpolator(OvershootInterpolator(3f)).start()
                    b.sourceRowPill.visibility = View.GONE
                }
                b.root.animate().scaleX(0.97f).scaleY(0.97f).setDuration(110)
                    .setInterpolator(DecelerateInterpolator()).start()
                onClick(index, b.root)
            }

            if (animateIn && position < 10) {
                b.root.alpha = 0f
                b.root.translationY = 26f * density
                b.root.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(position * 40L)
                    .setDuration(420)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .start()
            }
        }

        private fun resolve(context: Context, attr: Int): Int {
            val value = TypedValue()
            context.theme.resolveAttribute(attr, value, true)
            return value.data
        }
    }
}
