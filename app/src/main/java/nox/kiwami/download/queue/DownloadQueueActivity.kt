package nox.kiwami.download.queue

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.widget.PopupMenu
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.TaskStackBuilder
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nox.kiwami.R
import nox.kiwami.databinding.ActivityDownloadQueueBinding
import nox.kiwami.databinding.ItemQueueDownloadBinding
import nox.kiwami.download.anime.AnimeDownloaderService
import nox.kiwami.download.anime.AnimeServiceDataSingleton
import nox.kiwami.download.manga.MangaDownloaderService
import nox.kiwami.download.manga.MangaServiceDataSingleton
import nox.kiwami.download.novel.NovelDownloaderService
import nox.kiwami.download.novel.NovelServiceDataSingleton
import nox.kiwami.initActivity
import nox.kiwami.media.MediaType
import nox.kiwami.navBarHeight
import nox.kiwami.statusBarHeight
import nox.kiwami.themes.ThemeManager
import nox.kiwami.util.customAlertDialog
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DownloadQueueActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadQueueBinding
    private lateinit var adapter: QueueAdapter
    private var updateJob: Job? = null
    private var selectedTab = 0
    private val selected = LinkedHashSet<String>()
    private var currentItems: List<QueueItem> = emptyList()
    private val selectionBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            clearSelection()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityDownloadQueueBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initActivity(this)
        binding.downloadQueueContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        onBackPressedDispatcher.addCallback(this, selectionBackCallback)
        binding.downloadQueueSelectionBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight + 12.toPx()
        }
        binding.downloadQueueSelectionClose.setOnClickListener { clearSelection() }
        binding.downloadQueueSelectionPause.setOnClickListener { pauseSelected() }
        binding.downloadQueueSelectionResume.setOnClickListener { resumeSelected() }
        binding.downloadQueueSelectionCancel.setOnClickListener { confirmCancelSelected() }
        binding.downloadQueueBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        binding.downloadQueueClear.setOnClickListener {
            customAlertDialog().apply {
                setTitle(R.string.clear_queue)
                setMessage(R.string.clear_queue_confirm)
                setPosButton(R.string.yes) { clearAllQueues() }
                setNegButton(R.string.no)
                show()
            }
        }

        adapter = QueueAdapter(
            onAction = { item -> toggleHold(item) },
            onCancel = { item -> cancelTask(item) },
            onToggle = { item -> toggleSelection(item) },
            onStartSelection = { item -> startSelection(item) }
        )
        binding.downloadQueueRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.downloadQueueRecyclerView.adapter = adapter

        val tabs = binding.downloadQueueTabLayout
        tabs.addTab(tabs.newTab().setText(R.string.anime))
        tabs.addTab(tabs.newTab().setText(R.string.download_queue_tab_manga_novel))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedTab = tab.position
                clearSelection()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    override fun onResume() {
        super.onResume()
        startUpdating()
    }

    override fun onPause() {
        super.onPause()
        updateJob?.cancel()
        updateJob = null
    }

    private fun startUpdating() {
        updateJob?.cancel()
        updateJob = lifecycleScope.launch {
            while (isActive) {
                refresh()
                delay(1000)
            }
        }
    }

    private fun tabTypes() =
        if (selectedTab == 0) listOf(MediaType.ANIME) else listOf(MediaType.MANGA, MediaType.NOVEL)

    private fun tasksOf(type: MediaType): List<QueueItem> {
        val active: List<QueueItem>
        val queued: List<QueueItem>
        val waiting: List<QueueItem>
        when (type) {
            MediaType.ANIME -> {
                active = AnimeServiceDataSingleton.currentTasks.toList().map { QueueItem.Anime(it, true) }
                waiting = AnimeServiceDataSingleton.waitingTasks.toList().map { QueueItem.Anime(it, false) }
                queued = AnimeServiceDataSingleton.downloadQueue.toList().map { QueueItem.Anime(it, false) }
            }
            MediaType.MANGA -> {
                active = MangaServiceDataSingleton.currentTasks.toList().map { QueueItem.Manga(it, true) }
                waiting = MangaServiceDataSingleton.waitingTasks.toList().map { QueueItem.Manga(it, false) }
                queued = MangaServiceDataSingleton.downloadQueue.toList().map { QueueItem.Manga(it, false) }
            }
            MediaType.NOVEL -> {
                active = NovelServiceDataSingleton.currentTasks.toList().map { QueueItem.Novel(it, true) }
                waiting = NovelServiceDataSingleton.waitingTasks.toList().map { QueueItem.Novel(it, false) }
                queued = NovelServiceDataSingleton.downloadQueue.toList().map { QueueItem.Novel(it, false) }
            }
        }
        val held = DownloadHolds.all().filter { it.mediaType == type }
        val all = ArrayList<QueueItem>()
        for (item in active + waiting + queued) {
            val isHeld = held.any { it.title == item.title && it.uniqueId == item.uniqueId }
            if (!isHeld && all.none { it.title == item.title && it.uniqueId == item.uniqueId }) all.add(item)
        }
        all.addAll(held)
        return all
    }

    private fun progressOf(item: QueueItem): Int? = when (item) {
        is QueueItem.Anime -> AnimeServiceDataSingleton.progress[item.uniqueId]
        is QueueItem.Manga -> MangaServiceDataSingleton.progress[item.uniqueId]
        is QueueItem.Novel -> NovelServiceDataSingleton.progress[item.uniqueId]
    }

    private fun refresh() {
        val tabs = binding.downloadQueueTabLayout
        val animeCount = tasksOf(MediaType.ANIME).count { it.heldReason == null }
        val otherCount = (tasksOf(MediaType.MANGA) + tasksOf(MediaType.NOVEL)).count { it.heldReason == null }
        tabs.getTabAt(0)?.let {
            val text = if (animeCount > 0) "${getString(R.string.anime)} ($animeCount)" else getString(R.string.anime)
            if (it.text != text) it.text = text
        }
        tabs.getTabAt(1)?.let {
            val label = getString(R.string.download_queue_tab_manga_novel)
            val text = if (otherCount > 0) "$label ($otherCount)" else label
            if (it.text != text) it.text = text
        }

        val items = tabTypes().flatMap { tasksOf(it) }
        val running = items.count { it.isActive }
        val paused = items.count { it.heldReason == HoldReason.PAUSED }
        val failed = items.count { it.heldReason == HoldReason.FAILED }
        val waiting = items.size - running - paused - failed
        binding.downloadQueueStatus.text = if (items.isEmpty()) {
            getString(R.string.download_queue_desc)
        } else {
            val parts = arrayListOf(getString(R.string.download_queue_status, running, waiting))
            if (paused > 0) parts.add(getString(R.string.download_queue_status_paused, paused))
            if (failed > 0) parts.add(getString(R.string.download_queue_status_failed, failed))
            parts.joinToString(" • ")
        }

        currentItems = items
        selected.retainAll(items.map { keyOf(it) }.toSet())
        selectionBackCallback.isEnabled = selected.isNotEmpty()
        val selecting = selected.isNotEmpty()
        val rows = items.map {
            QueueRow(it, progressOf(it), selected.contains(keyOf(it)), selecting)
        }
        adapter.submitList(rows)
        binding.downloadQueueEmptyState.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.downloadQueueRecyclerView.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        updateSelectionBar()
    }

    private fun keyOf(item: QueueItem) = "${item.mediaType.name}|${item.title}|${item.uniqueId}"

    private fun Int.toPx() = (this * resources.displayMetrics.density).toInt()

    private fun startSelection(item: QueueItem) {
        selected.add(keyOf(item))
        refresh()
    }

    private fun toggleSelection(item: QueueItem) {
        val key = keyOf(item)
        if (!selected.remove(key)) selected.add(key)
        refresh()
    }

    private fun clearSelection() {
        selected.clear()
        refresh()
    }

    private fun selectedItems() = currentItems.filter { selected.contains(keyOf(it)) }

    private fun updateSelectionBar() {
        val bar = binding.downloadQueueSelectionBar
        val chosen = selectedItems()
        if (chosen.isEmpty()) {
            bar.visibility = View.GONE
            binding.downloadQueueRecyclerView.setPadding(0, 0, 0, 64.toPx())
            return
        }
        bar.visibility = View.VISIBLE
        binding.downloadQueueRecyclerView.setPadding(0, 0, 0, 100.toPx())
        binding.downloadQueueSelectionCount.text = getString(R.string.download_queue_selected, chosen.size)
        val running = chosen.filter { it.heldReason == null }
        val held = chosen.filter { it.heldReason != null }
        binding.downloadQueueSelectionPause.isEnabled = running.isNotEmpty()
        binding.downloadQueueSelectionPause.alpha = if (running.isNotEmpty()) 1f else 0.4f
        binding.downloadQueueSelectionResume.isEnabled = held.isNotEmpty()
        binding.downloadQueueSelectionResume.alpha = if (held.isNotEmpty()) 1f else 0.4f
        val onlyFailed = held.isNotEmpty() && held.all { it.heldReason == HoldReason.FAILED }
        binding.downloadQueueSelectionResume.setText(
            if (onlyFailed) R.string.download_retry else R.string.download_resume
        )
        binding.downloadQueueSelectionResume.setIconResource(
            if (onlyFailed) R.drawable.ic_round_refresh_24 else R.drawable.ic_round_play_arrow_24
        )
    }

    private fun pauseSelected() {
        val targets = selectedItems().filter { it.heldReason == null }
        targets.forEach { DownloadControl.pause(this, it) }
        clearSelection()
    }

    private fun resumeSelected() {
        val targets = selectedItems().filter { it.heldReason != null }
        targets.forEach { DownloadControl.resume(this, it) }
        clearSelection()
    }

    private fun confirmCancelSelected() {
        val targets = selectedItems()
        if (targets.isEmpty()) return
        customAlertDialog().apply {
            setTitle(R.string.download_queue_cancel_selected)
            setMessage(getString(R.string.download_queue_cancel_selected_confirm, targets.size))
            setPosButton(R.string.yes) {
                targets.forEach { DownloadControl.cancel(this@DownloadQueueActivity, it) }
                clearSelection()
            }
            setNegButton(R.string.no)
            show()
        }
    }

    private fun toggleHold(item: QueueItem) {
        if (item.heldReason == null) {
            DownloadControl.pause(this, item)
        } else {
            DownloadControl.resume(this, item)
        }
        refresh()
    }

    private fun cancelTask(item: QueueItem) {
        DownloadControl.cancel(this, item)
        refresh()
    }

    private fun clearAllQueues() {
        DownloadHolds.clear()
        AnimeServiceDataSingleton.downloadQueue.clear()
        MangaServiceDataSingleton.downloadQueue.clear()
        NovelServiceDataSingleton.downloadQueue.clear()

        val animeWaiting = AnimeServiceDataSingleton.waitingTasks.toList()
        val mangaWaiting = MangaServiceDataSingleton.waitingTasks.toList()
        val novelWaiting = NovelServiceDataSingleton.waitingTasks.toList()
        for (task in AnimeServiceDataSingleton.currentTasks.toList() + animeWaiting) {
            sendBroadcast(
                Intent(AnimeDownloaderService.ACTION_CANCEL_DOWNLOAD)
                    .putExtra(AnimeDownloaderService.EXTRA_TASK_NAME, task.getTaskName())
            )
        }
        for (task in MangaServiceDataSingleton.currentTasks.toList() + mangaWaiting) {
            sendBroadcast(
                Intent(MangaDownloaderService.ACTION_CANCEL_DOWNLOAD)
                    .putExtra(MangaDownloaderService.EXTRA_CHAPTER, task.chapter)
            )
        }
        for (task in NovelServiceDataSingleton.currentTasks.toList() + novelWaiting) {
            sendBroadcast(
                Intent(NovelDownloaderService.ACTION_CANCEL_DOWNLOAD)
                    .putExtra(NovelDownloaderService.EXTRA_CHAPTER, task.chapter)
            )
        }
        refresh()
    }

    companion object {
        fun pendingIntent(context: Context): PendingIntent? {
            return TaskStackBuilder.create(context)
                .addNextIntentWithParentStack(Intent(context, DownloadQueueActivity::class.java))
                .getPendingIntent(
                    0,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
        }
    }
}

data class QueueRow(
    val item: QueueItem,
    val progress: Int?,
    val selected: Boolean,
    val selecting: Boolean
)

private class QueueDiff : DiffUtil.ItemCallback<QueueRow>() {
    override fun areItemsTheSame(oldItem: QueueRow, newItem: QueueRow) =
        oldItem.item.mediaType == newItem.item.mediaType &&
            oldItem.item.title == newItem.item.title &&
            oldItem.item.uniqueId == newItem.item.uniqueId

    override fun areContentsTheSame(oldItem: QueueRow, newItem: QueueRow) =
        oldItem.progress == newItem.progress &&
            oldItem.selected == newItem.selected &&
            oldItem.selecting == newItem.selecting &&
            oldItem.item.isActive == newItem.item.isActive &&
            oldItem.item.heldReason == newItem.item.heldReason
}

private class QueueAdapter(
    private val onAction: (QueueItem) -> Unit,
    private val onCancel: (QueueItem) -> Unit,
    private val onToggle: (QueueItem) -> Unit,
    private val onStartSelection: (QueueItem) -> Unit
) : ListAdapter<QueueRow, QueueAdapter.Holder>(QueueDiff()) {

    class Holder(val binding: ItemQueueDownloadBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemQueueDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val item = row.item
        val b = holder.binding
        val context = b.root.context

        b.queueTaskTitle.text = item.title
        val sub = item.subTitle
        b.queueTaskName.text = when {
            sub.firstOrNull()?.isDigit() != true -> sub
            item.mediaType == MediaType.ANIME -> context.getString(R.string.download_queue_episode, sub)
            else -> context.getString(R.string.download_queue_chapter, sub)
        }

        val held = item.heldReason
        b.queueTaskState.text = when {
            held == HoldReason.PAUSED -> context.getString(R.string.download_state_paused)
            held == HoldReason.FAILED -> context.getString(R.string.download_state_failed)
            item.isActive -> "${row.progress ?: 0}%"
            else -> context.getString(R.string.download_state_queued)
        }

        val bar = b.queueTaskProgress
        if (item.isActive && row.progress == null) {
            bar.isIndeterminate = true
        } else {
            bar.isIndeterminate = false
            bar.setProgressCompat(if (item.isActive || held != null) row.progress ?: 0 else 0, false)
        }

        b.queueTaskMenu.visibility = if (row.selecting) View.INVISIBLE else View.VISIBLE
        b.queueTaskMenu.setOnClickListener { anchor ->
            val menu = PopupMenu(context, anchor)
            val actionLabel = when (held) {
                HoldReason.PAUSED -> R.string.download_resume
                HoldReason.FAILED -> R.string.download_retry
                null -> R.string.download_pause
            }
            menu.menu.add(0, 1, 0, actionLabel)
            menu.menu.add(0, 2, 1, R.string.cancel)
            menu.setOnMenuItemClickListener {
                if (it.itemId == 1) onAction(item) else onCancel(item)
                true
            }
            menu.show()
        }

        b.root.setBackgroundColor(
            if (row.selected) {
                MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorPrimaryContainer)
            } else {
                0
            }
        )
        b.root.setOnClickListener { if (row.selecting) onToggle(item) }
        b.root.setOnLongClickListener {
            if (row.selecting) onToggle(item) else onStartSelection(item)
            true
        }
    }
}
