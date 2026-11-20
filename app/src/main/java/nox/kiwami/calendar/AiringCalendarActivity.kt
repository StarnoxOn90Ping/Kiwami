package nox.kiwami.calendar

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import nox.kiwami.R
import nox.kiwami.connections.anilist.Anilist
import nox.kiwami.databinding.ActivityAiringCalendarBinding
import nox.kiwami.databinding.DialogCalendarCustomizeBinding
import nox.kiwami.getThemeColor
import nox.kiwami.initActivity
import nox.kiwami.media.Media
import nox.kiwami.media.MediaDetailsActivity
import nox.kiwami.navBarHeight
import nox.kiwami.statusBarHeight
import nox.kiwami.themes.ThemeManager
import nox.kiwami.util.fadeInDown
import nox.kiwami.util.setGradientText
import nox.kiwami.util.setPressBounce
import nox.kiwami.util.Logger
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AiringCalendarActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAiringCalendarBinding
    private val adapter = CalendarAdapter { openMedia(it.media) }

    private var raw: List<CalendarEntry> = emptyList()
    private var snapshotTime = 0L
    private var spinner: ObjectAnimator? = null
    private lateinit var stripController: DayStripController

    private var loadState = LoadState.LOADING
    private enum class LoadState { LOADING, READY, NEEDS_LOGIN, ERROR }

    private var dayStarts: List<Long> = emptyList()
    private var dayCounts: Map<Long, Int> = emptyMap()
    private var headerPositions: Map<Long, Int> = emptyMap()
    private var selectedDay = -1
    private var programmaticScroll = false
    private var segmentPos = 0f
    private var segmentAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityAiringCalendarBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initActivity(this)
        binding.calendarRoot.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        binding.calendarList.setPadding(0, 0, 0, navBarHeight + (24 * resources.displayMetrics.density).toInt())

        binding.calendarTitle.setGradientText(
            getThemeColor(androidx.appcompat.R.attr.colorPrimary),
            getThemeColor(com.google.android.material.R.attr.colorTertiary)
        )
        binding.calendarTitle.fadeInDown()
        binding.calendarMonth.fadeInDown(120)
        binding.calendarToday.fadeInDown(160)
        binding.calendarBack.setPressBounce()
        binding.calendarCustomize.setPressBounce()
        binding.calendarToday.setPressBounce(0.9f)

        binding.calendarBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.calendarCustomize.setOnClickListener { showCustomizeSheet() }
        binding.calendarRefresh.setPressBounce(0.85f)
        binding.calendarRefresh.setOnClickListener { load(force = true) }
        binding.calendarSwipe.setColorSchemeColors(getThemeColor(androidx.appcompat.R.attr.colorPrimary))
        binding.calendarSwipe.setOnRefreshListener { load(force = true) }
        stripController = DayStripController(
            binding.calendarStripScroll,
            binding.calendarStrip,
            binding.calendarTrack,
            binding.calendarIndicator
        ) { selectDay(it, scroll = true) }
        binding.calendarToday.setOnClickListener {
            val today = startOfDay(System.currentTimeMillis())
            val idx = dayStarts.indexOf(today)
            if (idx >= 0) selectDay(idx, scroll = true)
        }

        if (savedInstanceState == null) {
            intent.getIntExtra(EXTRA_CATEGORY, -1).takeIf { it >= 0 }?.let { CalendarPrefs.category = it }
        }
        setupSegment()

        binding.calendarList.layoutManager = LinearLayoutManager(this)
        binding.calendarList.adapter = adapter
        binding.calendarList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) programmaticScroll = false
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (programmaticScroll || dy == 0) return
                val lm = recyclerView.layoutManager as LinearLayoutManager
                val pos = lm.findFirstVisibleItemPosition()
                val day = dayOfRow(adapter.rows.getOrNull(pos)) ?: return
                val idx = dayStarts.indexOf(day)
                if (idx >= 0 && idx != selectedDay) selectDay(idx, scroll = false)
            }
        })

        load()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    CalendarRepository.version.drop(1).collect { onRepositoryChanged() }
                }
                launch {
                    CalendarRepository.state.collect { onSyncState(it) }
                }
                while (true) {
                    if (loadState == LoadState.READY) {
                        adapter.tick()
                        updateSummary()
                    }
                    renderSyncState()
                    delay(30_000)
                }
            }
        }
    }

    private fun isManga() = CalendarPrefs.category == CalendarPrefs.CATEGORY_MANGA

    private fun setupSegment() {
        val segment = binding.calendarSegment
        val thumb = binding.calendarSegmentThumb
        val inset = 4 * resources.displayMetrics.density
        segmentPos = CalendarPrefs.category.toFloat()
        binding.calendarTabAnime.setOnClickListener { setCategory(CalendarPrefs.CATEGORY_ANIME) }
        binding.calendarTabManga.setOnClickListener { setCategory(CalendarPrefs.CATEGORY_MANGA) }
        segment.post {
            thumb.updateLayoutParams<ViewGroup.LayoutParams> { width = ((segment.width - inset * 2) / 2f).toInt() }
            placeSegment(segmentPos)
        }
    }

    private fun placeSegment(pos: Float) {
        segmentPos = pos
        val segment = binding.calendarSegment
        val thumb = binding.calendarSegmentThumb
        val inset = 4 * resources.displayMetrics.density
        val w = (segment.width - inset * 2) / 2f
        if (w <= 0f) return
        thumb.translationX = pos * w
        val bump = kotlin.math.sin(Math.PI * pos).toFloat().coerceAtLeast(0f)
        thumb.scaleX = 1f + 0.22f * bump
        thumb.scaleY = 1f - 0.12f * bump
        val on = getThemeColor(com.google.android.material.R.attr.colorOnPrimary)
        val off = getThemeColor(com.google.android.material.R.attr.colorOutline)
        binding.calendarTabAnime.setTextColor(ColorUtils.blendARGB(on, off, pos))
        binding.calendarTabManga.setTextColor(ColorUtils.blendARGB(off, on, pos))
        val a = 1f + 0.08f * (1f - pos)
        val m = 1f + 0.08f * pos
        binding.calendarTabAnime.scaleX = a
        binding.calendarTabAnime.scaleY = a
        binding.calendarTabManga.scaleX = m
        binding.calendarTabManga.scaleY = m
    }

    private fun animateSegment(to: Int) {
        segmentAnimator?.cancel()
        segmentAnimator = ValueAnimator.ofFloat(segmentPos, to.toFloat()).apply {
            duration = 340
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { placeSegment(it.animatedValue as Float) }
            start()
        }
    }

    private fun setCategory(category: Int) {
        if (CalendarPrefs.category == category) return
        CalendarPrefs.category = category
        animateSegment(category)
        binding.calendarList.alpha = 0f
        binding.calendarList.animate().alpha(1f).setDuration(260).start()
        load()
    }

    private fun load(force: Boolean = false) {
        val source = CalendarPrefs.activeSource
        if (source != CalendarPrefs.SOURCE_ALL && Anilist.userid == null) {
            raw = emptyList()
            snapshotTime = 0L
            loadState = LoadState.NEEDS_LOGIN
            binding.calendarSwipe.isRefreshing = false
            applyFilters()
            renderSyncState()
            return
        }
        val snap = CalendarRepository.snapshot(source)
        if (snap != null) {
            showSnapshot(snap)
        } else {
            snapshotTime = 0L
            loadState = LoadState.LOADING
            showLoading()
        }
        CalendarRepository.refresh(force, source)
        renderSyncState()
    }

    private fun showSnapshot(snap: CalendarRepository.Snapshot, keepPosition: Boolean = false) {
        val (from, to) = CalendarRepository.window(CalendarPrefs.activeSource)
        raw = CalendarRepository.entriesIn(snap, from, to)
        snapshotTime = snap.fetchedAtMs
        loadState = LoadState.READY
        applyFilters(keepPosition)
    }

    private fun onRepositoryChanged() {
        val snap = CalendarRepository.snapshot(CalendarPrefs.activeSource) ?: return
        val (from, to) = CalendarRepository.window(CalendarPrefs.activeSource)
        val fresh = CalendarRepository.entriesIn(snap, from, to)
        fun key(e: CalendarEntry) = listOf(e.media.id, e.episode, e.airingAt, e.listStatus, e.progress)
        val same = loadState == LoadState.READY && raw.size == fresh.size &&
                raw.indices.all { key(raw[it]) == key(fresh[it]) }
        if (same) {
            snapshotTime = snap.fetchedAtMs
        } else {
            showSnapshot(snap, keepPosition = loadState == LoadState.READY)
        }
        renderSyncState()
    }

    private fun onSyncState(state: CalendarRepository.State) {
        val failed = state == CalendarRepository.State.FAILED || state == CalendarRepository.State.OFFLINE
        if (failed && loadState == LoadState.LOADING) {
            raw = emptyList()
            loadState = LoadState.ERROR
            applyFilters()
        }
        renderSyncState()
    }

    private fun renderSyncState() {
        if (!::binding.isInitialized) return
        val state = CalendarRepository.state.value
        val syncing = state == CalendarRepository.State.SYNCING
        val label = when {
            syncing -> getString(R.string.cal_updating)
            snapshotTime <= 0L -> ""
            state == CalendarRepository.State.OFFLINE -> getString(R.string.cal_offline_saved)
            state == CalendarRepository.State.FAILED -> getString(R.string.cal_refresh_failed)
            else -> updatedAgo(snapshotTime)
        }
        binding.calendarUpdated.text = label
        binding.calendarUpdated.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
        if (!syncing) binding.calendarSwipe.isRefreshing = false
        setSpinning(syncing)
    }

    private fun updatedAgo(time: Long): String {
        val minutes = ((System.currentTimeMillis() - time) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> getString(R.string.cal_updated_now)
            minutes < 60 -> getString(R.string.cal_updated_min, minutes.toInt())
            minutes < 60 * 24 -> getString(R.string.cal_updated_hours, (minutes / 60).toInt())
            else -> getString(R.string.cal_updated_days, (minutes / (60 * 24)).toInt())
        }
    }

    private fun setSpinning(on: Boolean) {
        if (on) {
            if (spinner?.isRunning == true) return
            spinner = ObjectAnimator.ofFloat(binding.calendarRefresh, View.ROTATION, 0f, 360f).apply {
                duration = 900
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else {
            spinner?.cancel()
            spinner = null
            binding.calendarRefresh.rotation = 0f
        }
    }

    private fun showLoading() {
        binding.calendarProgress.visibility = View.VISIBLE
        binding.calendarEmpty.visibility = View.GONE
        binding.calendarSummaryCard.visibility = View.GONE
        adapter.submit(emptyList(), CalendarPrefs.compact, use24h())
    }

    private fun use24h(): Boolean = when (CalendarPrefs.timeFormat) {
        1 -> false
        2 -> true
        else -> DateFormat.is24HourFormat(this)
    }

    private fun applyFilters(keepPosition: Boolean = false) {
        binding.calendarProgress.visibility = View.GONE
        val anchorDay = if (keepPosition) {
            val lm = binding.calendarList.layoutManager as LinearLayoutManager
            dayOfRow(adapter.rows.getOrNull(lm.findFirstVisibleItemPosition()))
        } else null

        val nowMs = System.currentTimeMillis()
        val manga = isManga()
        val mask = if (manga) CalendarPrefs.mangaStatusMask else CalendarPrefs.statusMask
        val mine = CalendarPrefs.activeSource != CalendarPrefs.SOURCE_ALL
        val entries = raw.filter { e ->
            val st = ListStatus.from(e.listStatus)
            val statusOk = !mine || (st != null && (mask and st.bit) != 0)
            val airedOk = !CalendarPrefs.hideAired || !e.hasAired(nowMs)
            statusOk && airedOk
        }.sortedBy { it.airingAt }

        buildStrip(entries)

        val byDay = entries.groupBy { startOfDay(it.airingAt * 1000) }
        val rows = mutableListOf<CalendarRow>()
        val positions = mutableMapOf<Long, Int>()
        val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())
        byDay.keys.sorted().forEach { day ->
            val list = byDay.getValue(day)
            positions[day] = rows.size
            rows.add(CalendarRow.Header(day, dayTitle(day), "${dateFmt.format(day)} · ${countLabel(list.size)}"))
            list.forEach { rows.add(CalendarRow.Item(it)) }
        }
        headerPositions = positions
        adapter.submit(rows, CalendarPrefs.compact, use24h())

        if (rows.isEmpty()) showEmpty() else {
            binding.calendarEmpty.visibility = View.GONE
            if (anchorDay == null) binding.calendarList.scrollToPosition(0)
        }
        updateSummary()

        if (anchorDay != null && rows.isNotEmpty()) {
            val pos = positions.filterKeys { it >= anchorDay }.minByOrNull { it.key }?.value ?: 0
            (binding.calendarList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
            val idx = dayStarts.indexOf(anchorDay)
            if (idx >= 0) selectDay(idx, scroll = false)
            return
        }

        val today = startOfDay(System.currentTimeMillis())
        val target = when {
            dayStarts.contains(today) && (byDay.containsKey(today) || byDay.isEmpty()) -> dayStarts.indexOf(today)
            else -> dayStarts.indexOfFirst { it >= today && byDay.containsKey(it) }.takeIf { it >= 0 }
                ?: dayStarts.indexOf(today)
        }
        if (target >= 0) selectDay(target, scroll = rows.isNotEmpty())
    }

    private fun countLabel(n: Int): String = when {
        isManga() -> if (n == 1) getString(R.string.cal_chapter_count_one) else getString(R.string.cal_chapters_count, n)
        else -> if (n == 1) getString(R.string.cal_episode_count_one) else getString(R.string.cal_episodes_count, n)
    }

    private fun showEmpty() {
        val title: Int
        val text: Int
        var action = R.string.cal_customize
        var onAction: () -> Unit = { showCustomizeSheet() }
        when (loadState) {
            LoadState.NEEDS_LOGIN -> {
                title = R.string.cal_login_title
                text = if (isManga()) R.string.cal_login_text_manga else R.string.cal_login_text
                action = if (isManga()) R.string.cal_browse_anime else R.string.cal_source_all
                onAction = {
                    CalendarPrefs.source = CalendarPrefs.SOURCE_ALL
                    if (isManga()) setCategory(CalendarPrefs.CATEGORY_ANIME) else load()
                }
            }
            LoadState.ERROR -> {
                title = R.string.cal_error_title
                text = R.string.cal_error_text
                action = R.string.cal_retry
                onAction = { load(force = true) }
            }
            else -> {
                title = R.string.cal_empty_title
                text = if (isManga()) R.string.cal_empty_text_manga else R.string.cal_empty_text
            }
        }
        binding.calendarEmptyTitle.setText(title)
        binding.calendarEmptyText.setText(text)
        binding.calendarEmptyAction.setText(action)
        binding.calendarEmptyAction.setPressBounce(0.92f)
        binding.calendarEmptyAction.setOnClickListener { onAction() }
        binding.calendarEmpty.visibility = View.VISIBLE
    }

    private fun updateSummary() {
        if (loadState != LoadState.READY) {
            binding.calendarSummaryCard.visibility = View.GONE
            return
        }
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        val today = startOfDay(nowMs)
        val entries = adapter.rows.filterIsInstance<CalendarRow.Item>().map { it.entry }
        if (entries.isEmpty()) {
            binding.calendarSummaryCard.visibility = View.GONE
            return
        }
        val todayCount = entries.count { startOfDay(it.airingAt * 1000) == today }
        binding.calendarSummaryTitle.text = if (todayCount > 0) {
            getString(R.string.cal_summary_today, countLabel(todayCount))
        } else getString(
            if (isManga()) R.string.cal_summary_nothing_today_manga else R.string.cal_summary_nothing_today
        )

        val next = entries.firstOrNull { !it.hasAired(nowMs) }
        binding.calendarSummarySub.text = when {
            next == null -> getString(
                if (isManga()) R.string.cal_summary_next_none_manga else R.string.cal_summary_next_none
            )
            next.isManga -> {
                val days = ((startOfDay(next.airingAt * 1000) - today) / 86_400_000L).toInt()
                val whenText = if (days <= 0) getString(R.string.cal_in_today) else getString(R.string.cal_in_days, days)
                if (next.episode > 0) {
                    getString(R.string.cal_summary_next_chapter, next.media.userPreferredName, next.episode, whenText)
                } else {
                    getString(R.string.cal_summary_next_chapter_unnumbered, next.media.userPreferredName, whenText)
                }
            }
            else -> getString(
                R.string.cal_summary_next,
                next.media.userPreferredName,
                next.episode,
                getString(R.string.cal_in_time, formatDuration(next.airingAt - nowSec))
            )
        }
        binding.calendarSummaryCard.visibility = View.VISIBLE
    }

    private fun dayTitle(dayStart: Long): String {
        val today = startOfDay(System.currentTimeMillis())
        val tomorrow = startOfDay(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis)
        val yesterday = startOfDay(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis)
        return when (dayStart) {
            today -> getString(R.string.cal_today)
            tomorrow -> getString(R.string.cal_tomorrow)
            yesterday -> getString(R.string.cal_yesterday)
            else -> SimpleDateFormat("EEEE", Locale.getDefault()).format(dayStart)
        }
    }

    private fun dayOfRow(row: CalendarRow?): Long? = when (row) {
        is CalendarRow.Header -> row.dayStart
        is CalendarRow.Item -> startOfDay(row.entry.airingAt * 1000)
        null -> null
    }

    private fun buildStrip(entries: List<CalendarEntry>) {
        val past = CalendarPrefs.pastDays
        val ahead = if (CalendarPrefs.activeSource == CalendarPrefs.SOURCE_ALL) minOf(CalendarPrefs.aheadDays, 7)
        else CalendarPrefs.aheadDays

        val starts = (-past..ahead).map { offset ->
            startOfDay(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }.timeInMillis)
        }
        dayStarts = starts
        dayCounts = entries.groupingBy { startOfDay(it.airingAt * 1000) }.eachCount()
        stripController.setDays(starts, starts.map { dayCounts[it] ?: 0 }, startOfDay(System.currentTimeMillis()))
        selectedDay = -1
    }

    private fun updateMonth(index: Int) {
        val text = SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(dayStarts[index])
        val label = binding.calendarMonth
        val hadText = label.text.isNotEmpty()
        if (label.text.toString() == text) return
        label.text = text
        if (hadText) {
            label.animate().cancel()
            label.alpha = 0.15f
            label.translationY = 6 * resources.displayMetrics.density
            label.animate().alpha(0.7f).translationY(0f).setDuration(280)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun selectDay(index: Int, scroll: Boolean) {
        if (index !in dayStarts.indices) return
        selectedDay = index
        stripController.select(index)
        updateMonth(index)
        if (scroll) scrollListToDay(dayStarts[index])
    }

    private fun scrollListToDay(day: Long) {
        val pos = headerPositions[day]
            ?: headerPositions.filterKeys { it > day }.minByOrNull { it.key }?.value
            ?: return
        programmaticScroll = true
        val scroller = object : LinearSmoothScroller(this) {
            override fun getVerticalSnapPreference() = SNAP_TO_START
        }
        scroller.targetPosition = pos
        binding.calendarList.layoutManager?.startSmoothScroll(scroller)
    }

    private fun showCustomizeSheet() {
        val sheet = BottomSheetDialog(this)
        val manga = isManga()
        val b = DialogCalendarCustomizeBinding.inflate(layoutInflater)
        sheet.setContentView(b.root)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true

        fun chip(group: ChipGroup, label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
            val chip = layoutInflater.inflate(R.layout.item_calendar_chip, group, false) as Chip
            chip.text = label
            chip.isChecked = checked
            chip.setOnCheckedChangeListener { _, isChecked -> onChecked(isChecked) }
            group.addView(chip)
        }

        fun refreshStatusVisibility() {
            val vis = if (manga || CalendarPrefs.source == CalendarPrefs.SOURCE_MINE) View.VISIBLE else View.GONE
            b.calStatusLabel.visibility = vis
            b.calStatusGroup.visibility = vis
        }

        fun populate() {
            b.calSourceGroup.removeAllViews()
            b.calStatusGroup.removeAllViews()
            b.calAheadGroup.removeAllViews()
            b.calPastGroup.removeAllViews()

            b.calSourceLabel.visibility = if (manga) View.GONE else View.VISIBLE
            b.calSourceGroup.visibility = if (manga) View.GONE else View.VISIBLE

            chip(b.calSourceGroup, getString(R.string.cal_source_mine), CalendarPrefs.source == CalendarPrefs.SOURCE_MINE) {
                if (it) {
                    CalendarPrefs.source = CalendarPrefs.SOURCE_MINE
                    refreshStatusVisibility()
                    load()
                }
            }
            chip(b.calSourceGroup, getString(R.string.cal_source_all), CalendarPrefs.source == CalendarPrefs.SOURCE_ALL) {
                if (it) {
                    CalendarPrefs.source = CalendarPrefs.SOURCE_ALL
                    refreshStatusVisibility()
                    load()
                }
            }

            val statuses = if (manga) {
                ListStatus.values().filter { (ListStatus.MANGA_DEFAULT_MASK and it.bit) != 0 }
            } else ListStatus.values().toList()
            statuses.forEach { st ->
                val current = if (manga) CalendarPrefs.mangaStatusMask else CalendarPrefs.statusMask
                chip(b.calStatusGroup, getString(CalendarAdapter.statusLabel(st, manga)), (current and st.bit) != 0) { on ->
                    val mask = if (manga) CalendarPrefs.mangaStatusMask else CalendarPrefs.statusMask
                    val updated = if (on) mask or st.bit else mask and st.bit.inv()
                    if (manga) CalendarPrefs.mangaStatusMask = updated else CalendarPrefs.statusMask = updated
                    load()
                }
            }

            CalendarPrefs.AHEAD_OPTIONS.forEach { d ->
                chip(b.calAheadGroup, getString(R.string.cal_days, d), CalendarPrefs.aheadDays == d) {
                    if (it) {
                        CalendarPrefs.aheadDays = d
                        load()
                    }
                }
            }
            CalendarPrefs.PAST_OPTIONS.forEach { d ->
                val label = when (d) {
                    0 -> getString(R.string.cal_off)
                    1 -> getString(R.string.cal_day, d)
                    else -> getString(R.string.cal_days, d)
                }
                chip(b.calPastGroup, label, CalendarPrefs.pastDays == d) {
                    if (it) {
                        CalendarPrefs.pastDays = d
                        load()
                    }
                }
            }
            refreshStatusVisibility()
        }
        populate()

        if (manga) b.calHideAired.setText(R.string.cal_hide_released)
        b.calHideAired.isChecked = CalendarPrefs.hideAired
        b.calHideAired.setOnCheckedChangeListener { _, on ->
            CalendarPrefs.hideAired = on
            load()
        }
        b.calCompact.isChecked = CalendarPrefs.compact
        b.calCompact.setOnCheckedChangeListener { _, on ->
            CalendarPrefs.compact = on
            load()
        }
        b.cal24h.isChecked = use24h()
        b.cal24h.setOnCheckedChangeListener { _, on ->
            CalendarPrefs.timeFormat = if (on) 2 else 1
            load()
        }
        b.calReset.setOnClickListener {
            CalendarPrefs.reset()
            sheet.dismiss()
            load()
        }
        sheet.show()
    }

    private fun openMedia(media: Media) {
        startActivity(
            Intent(this, MediaDetailsActivity::class.java).putExtra("media", media as Serializable)
        )
    }

    companion object {
        const val EXTRA_CATEGORY = "calendar_category"
    }

    override fun onDestroy() {
        super.onDestroy()
        spinner?.cancel()
        segmentAnimator?.cancel()
        if (::stripController.isInitialized) stripController.cancel()
        if (::binding.isInitialized) binding.calendarList.adapter = null
    }
}
