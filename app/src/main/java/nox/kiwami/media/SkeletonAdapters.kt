package nox.kiwami.media

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import nox.kiwami.R

class SkeletonAdapter(
    initialKind: Kind,
    private val maxGrid: Int
) : RecyclerView.Adapter<SkeletonAdapter.Holder>() {

    enum class Kind(val layout: Int, val rows: Int) {
        EPISODE_LIST(R.layout.item_skeleton_episode_list, 5),
        EPISODE_GRID(R.layout.item_skeleton_episode_grid, 4),
        EPISODE_COMPACT(R.layout.item_skeleton_episode_compact, 5),
        CHAPTER_LIST(R.layout.item_skeleton_chapter_list, 8);

        fun span(maxGrid: Int): Int = when (this) {
            EPISODE_GRID -> 2
            EPISODE_COMPACT -> 1
            else -> maxGrid
        }

        fun count(maxGrid: Int): Int = rows * (maxGrid / span(maxGrid))

        companion object {
            fun forAnime(style: Int): Kind = when (style) {
                1 -> EPISODE_GRID
                2 -> EPISODE_COMPACT
                else -> EPISODE_LIST
            }

            fun forManga(style: Int): Kind = if (style == 1) EPISODE_COMPACT else CHAPTER_LIST
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view)

    private var shown = 0
    private var current = initialKind
    private var host: RecyclerView? = null

    var kind: Kind
        get() = current
        set(value) {
            whenSafe { changeKind(value) }
        }

    fun show(on: Boolean) {
        whenSafe { changeVisibility(on) }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        host = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (host === recyclerView) host = null
    }

    private fun whenSafe(change: () -> Unit) {
        val list = host
        if (list != null && list.isComputingLayout) list.post(change) else change()
    }

    private fun changeKind(value: Kind) {
        if (current == value) return
        current = value
        if (shown > 0) {
            shown = value.count(maxGrid)
            notifyDataSetChanged()
        }
    }

    private fun changeVisibility(on: Boolean) {
        val target = if (on) current.count(maxGrid) else 0
        if (target == shown) return
        if (target > shown) {
            notifyItemRangeInserted(shown, target - shown)
        } else {
            notifyItemRangeRemoved(target, shown - target)
        }
        shown = target
    }

    override fun getItemCount(): Int = shown

    override fun getItemViewType(position: Int): Int = current.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = Kind.values()[viewType].layout
        return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {}
}

class HeaderSkeletonAdapter : RecyclerView.Adapter<SkeletonAdapter.Holder>() {

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SkeletonAdapter.Holder {
        return SkeletonAdapter.Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_skeleton_source_header, parent, false)
        )
    }

    override fun onBindViewHolder(holder: SkeletonAdapter.Holder, position: Int) {}
}

object WatchSkeleton {

    fun attach(list: RecyclerView, kind: SkeletonAdapter.Kind, maxGrid: Int) {
        val manager = object : GridLayoutManager(list.context, maxGrid) {
            override fun canScrollVertically(): Boolean = false
        }
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (position == 0) maxGrid else kind.span(maxGrid)
        }
        list.layoutManager = manager
        list.itemAnimator = null
        list.overScrollMode = View.OVER_SCROLL_NEVER
        list.isNestedScrollingEnabled = false
        val tiles = SkeletonAdapter(kind, maxGrid)
        tiles.show(true)
        list.adapter = ConcatAdapter(HeaderSkeletonAdapter(), tiles)
    }
}
