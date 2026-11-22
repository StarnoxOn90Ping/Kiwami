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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.AutoCompleteTextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputLayout
import nox.kiwami.R
import nox.kiwami.databinding.DialogSourcePickerBinding
import nox.kiwami.databinding.ItemSourcePickerBinding
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.util.ThemeSurface

object SourcePicker {

    data class Item(val name: String, val subtitle: String)

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
        title: String = "Choose source",
        countText: String = "${items.size} installed",
        keepOrder: Boolean = false,
        searchable: Boolean = items.size > 5
    ) {
        val binding = DialogSourcePickerBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(binding.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.background = sheetBackground(context)
        }
        dialog.setOnDismissListener { onDismiss() }

        val key = "kiwami_recent_sources_$recentsKey"
        val stored: String = if (recentsKey.isEmpty()) "" else PrefManager.getCustomVal(key, "")
        val recents = stored.split("\n").filter { name -> name.isNotEmpty() && items.any { it.name == name } }
        val recentNames = recents.filter { it != selected }.take(3)

        val selectedIndex = items.indexOfFirst { it.name == selected }
        val ordered = if (keepOrder) items.indices.toList() else buildList {
            if (selectedIndex >= 0) add(selectedIndex)
            recentNames.forEach { name -> add(items.indexOfFirst { it.name == name }) }
            items.indices.forEach { if (!contains(it)) add(it) }
        }

        var picked = false
        val adapter = RowAdapter(items, selected, recentNames.toSet()) { index, row ->
            if (picked) return@RowAdapter
            picked = true
            row.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            if (recentsKey.isNotEmpty()) {
                val updated = (listOf(items[index].name) + recents.filter { it != items[index].name }).take(5)
                PrefManager.setCustomVal(key, updated.joinToString("\n"))
            }
            if (items[index].name != selected) onPick(index)
            row.postDelayed({ dialog.dismiss() }, 220)
        }
        adapter.rows = ordered

        binding.sourcePickerTitle.text = title
        binding.sourcePickerCount.text = countText
        binding.sourcePickerCount.visibility = if (countText.isEmpty()) View.GONE else View.VISIBLE
        binding.sourcePickerList.layoutManager = LinearLayoutManager(context)
        binding.sourcePickerList.adapter = adapter
        binding.sourcePickerList.itemAnimator = null
        binding.sourcePickerSearchBox.visibility = if (searchable) View.VISIBLE else View.GONE
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

    fun showOptions(
        context: Context,
        title: String,
        names: List<String>,
        selected: String,
        onPick: (String) -> Unit
    ) {
        show(
            context = context,
            items = names.map { Item(it, "") },
            selected = selected,
            recentsKey = "",
            onPick = { onPick(names[it]) },
            onDismiss = {},
            title = title,
            countText = "",
            keepOrder = true,
            searchable = false
        )
    }

    fun bindDropdown(
        layout: TextInputLayout,
        field: AutoCompleteTextView,
        title: String,
        names: List<String>
    ) {
        val open = {
            showOptions(field.context, title, names, field.text.toString()) {
                field.setText(it, false)
            }
        }
        field.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) open()
            true
        }
        layout.setEndIconOnClickListener { open() }
    }

    fun sheetBackground(context: Context): Drawable? {
        if (!ThemeSurface.isOled(context)) {
            return ContextCompat.getDrawable(context, R.drawable.bg_source_sheet)
        }
        val density = context.resources.displayMetrics.density
        val radius = 28f * density
        return GradientDrawable().apply {
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            setColor(Color.BLACK)
            setStroke(density.toInt().coerceAtLeast(1), 0x26808080)
        }
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
            return Holder(
                ItemSourcePickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
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
            b.sourceRowSubtitle.visibility = if (item.subtitle.isEmpty()) View.GONE else View.VISIBLE

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
