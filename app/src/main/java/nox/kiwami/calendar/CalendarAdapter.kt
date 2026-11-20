package nox.kiwami.calendar

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import nox.kiwami.R
import nox.kiwami.databinding.ItemCalendarEntryBinding
import nox.kiwami.databinding.ItemCalendarHeaderBinding
import nox.kiwami.loadImage
import nox.kiwami.setAnimation
import nox.kiwami.util.setPressBounce
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

sealed class CalendarRow {
    data class Header(val dayStart: Long, val title: String, val subtitle: String) : CalendarRow()
    data class Item(val entry: CalendarEntry) : CalendarRow()
}

fun formatDuration(totalSeconds: Long): String {
    val s = kotlin.math.abs(totalSeconds)
    val m = s / 60
    val h = m / 60
    val d = h / 24
    return when {
        m < 1 -> "<1m"
        h < 1 -> "${m}m"
        d < 1 -> "${h}h ${m % 60}m"
        else -> "${d}d ${h % 24}h"
    }
}

fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

class CalendarAdapter(
    private val onClick: (CalendarEntry) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var rows: List<CalendarRow> = emptyList()
        private set
    private var compact = false
    private var timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    fun submit(newRows: List<CalendarRow>, compact: Boolean, use24h: Boolean) {
        rows = newRows
        this.compact = compact
        timeFormat = SimpleDateFormat(if (use24h) "HH:mm" else "h:mm a", Locale.getDefault())
        notifyDataSetChanged()
    }

    fun tick() {
        if (rows.isNotEmpty()) notifyItemRangeChanged(0, rows.size, TICK)
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) =
        if (rows[position] is CalendarRow.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemCalendarHeaderBinding.inflate(inflater, parent, false))
        } else {
            EntryHolder(ItemCalendarEntryBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        bindRow(holder, position)
        setAnimation(holder.itemView.context, holder.itemView)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(TICK)) bindRow(holder, position)
        else super.onBindViewHolder(holder, position, payloads)
    }

    private fun bindRow(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is CalendarRow.Header -> (holder as HeaderHolder).bind(row)
            is CalendarRow.Item -> (holder as EntryHolder).bind(row.entry)
        }
    }

    inner class HeaderHolder(private val b: ItemCalendarHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(h: CalendarRow.Header) {
            b.headerTitle.text = h.title
            b.headerCount.text = h.subtitle
        }
    }

    inner class EntryHolder(private val b: ItemCalendarEntryBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(e: CalendarEntry) {
            val ctx = b.root.context
            val density = ctx.resources.displayMetrics.density
            val nowMs = System.currentTimeMillis()
            val diff = e.airingAt - nowMs / 1000
            val aired = e.hasAired(nowMs)

            b.entryTitle.text = e.media.userPreferredName
            if (e.isManga) {
                b.entryMeta.text = if (e.episode > 0) ctx.getString(R.string.cal_chapter_expected, e.episode)
                else ctx.getString(R.string.cal_chapter_next_expected)
                val days = ((startOfDay(e.airingAt * 1000) - startOfDay(nowMs)) / DAY_MS).toInt()
                if (days == 0) {
                    b.entryCountdown.text = ctx.getString(R.string.cal_today)
                    b.entryCountdownLabel.text = ""
                } else {
                    b.entryCountdown.text = ctx.getString(R.string.cal_short_days, kotlin.math.abs(days))
                    b.entryCountdownLabel.text = ctx.getString(if (days < 0) R.string.cal_ago else R.string.cal_left)
                }
            } else {
                b.entryMeta.text = ctx.getString(
                    R.string.cal_episode_at, e.episode, timeFormat.format(e.airingAt * 1000)
                )
                b.entryCountdown.text = formatDuration(diff)
                b.entryCountdownLabel.text = ctx.getString(if (aired) R.string.cal_ago else R.string.cal_left)
            }
            b.entryCountdown.alpha = if (aired) 0.6f else 1f
            b.entryRow.alpha = if (aired) 0.62f else 1f

            val status = ListStatus.from(e.listStatus)
            if (status != null && !compact) {
                b.entryChipRow.visibility = View.VISIBLE
                b.entryStatus.text = ctx.getString(statusLabel(status, e.isManga))
                b.entryStatus.background = GradientDrawable().apply {
                    cornerRadius = 20f * density
                    setColor(status.color)
                }
                val progress = e.progress
                if (progress != null && (status == ListStatus.CURRENT || status == ListStatus.REPEATING)) {
                    val behind = (e.episode - 1) - progress
                    b.entryProgress.visibility = View.VISIBLE
                    val atLabel = ctx.getString(
                        if (e.isManga) R.string.cal_progress_at_chapter else R.string.cal_progress_at, progress
                    )
                    b.entryProgress.text = if (behind > 0) {
                        atLabel + " · " + ctx.getString(R.string.cal_progress_behind, behind)
                    } else atLabel
                } else b.entryProgress.visibility = View.GONE
            } else {
                b.entryChipRow.visibility = View.GONE
            }

            b.entryCoverCard.layoutParams = b.entryCoverCard.layoutParams.apply {
                width = ((if (compact) 42 else 62) * density).toInt()
                height = ((if (compact) 60 else 88) * density).toInt()
            }
            b.entryCover.loadImage(e.media.cover)
            if (compact) {
                b.entryBanner.visibility = View.GONE
            } else {
                b.entryBanner.visibility = View.VISIBLE
                b.entryBanner.loadImage(e.media.banner ?: e.media.cover)
            }

            b.root.setPressBounce(0.97f)
            b.root.setOnClickListener { onClick(e) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
        private const val TICK = "tick"
        private const val DAY_MS = 86_400_000L

        fun statusLabel(s: ListStatus, manga: Boolean = false): Int = when (s) {
            ListStatus.CURRENT -> if (manga) R.string.cal_status_reading else R.string.cal_status_current
            ListStatus.PLANNING -> R.string.cal_status_planning
            ListStatus.REPEATING -> if (manga) R.string.cal_status_rereading else R.string.cal_status_repeating
            ListStatus.PAUSED -> R.string.cal_status_paused
            ListStatus.COMPLETED -> R.string.cal_status_completed
            ListStatus.DROPPED -> R.string.cal_status_dropped
        }
    }
}
