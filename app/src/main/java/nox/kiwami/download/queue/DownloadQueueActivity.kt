package nox.kiwami.download.queue

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.TaskStackBuilder
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nox.kiwami.R
import nox.kiwami.databinding.ActivityDownloadQueueBinding
import nox.kiwami.databinding.ItemQueueDownloadBinding
import nox.kiwami.databinding.ItemQueueGroupHeaderBinding
import nox.kiwami.download.DownloadsManager
import nox.kiwami.download.anime.AnimeDownloaderService
import nox.kiwami.download.anime.AnimeServiceDataSingleton
import nox.kiwami.download.manga.MangaDownloaderService
import nox.kiwami.download.manga.MangaServiceDataSingleton
import nox.kiwami.download.novel.NovelDownloaderService
import nox.kiwami.download.novel.NovelServiceDataSingleton
import nox.kiwami.initActivity
import nox.kiwami.loadImage
import nox.kiwami.media.MediaType
import nox.kiwami.navBarHeight
import nox.kiwami.settings.saving.PrefManager
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
    private val coverCache = HashMap<String, String?>()

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
            coverCache,
            onAction = { item -> toggleHold(item) },
            onCancel = { item -> cancelTask(item) }
        )
        binding.downloadQueueRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.downloadQueueRecyclerView.adapter = adapter

        val tabs = binding.downloadQueueTabLayout
        tabs.addTab(tabs.newTab().setText(R.string.anime))
        tabs.addTab(tabs.newTab().setText(R.string.download_queue_tab_manga_novel))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedTab = tab.position
                refresh()
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
        when (type) {
            MediaType.ANIME -> {
                active = AnimeServiceDataSingleton.currentTasks.toList().map { QueueItem.Anime(it, true) }
                queued = AnimeServiceDataSingleton.downloadQueue.toList().map { QueueItem.Anime(it, false) }
            }
            MediaType.MANGA -> {
                active = MangaServiceDataSingleton.currentTasks.toList().map { QueueItem.Manga(it, true) }
                queued = MangaServiceDataSingleton.downloadQueue.toList().map { QueueItem.Manga(it, false) }
            }
            MediaType.NOVEL -> {
                active = NovelServiceDataSingleton.currentTasks.toList().map { QueueItem.Novel(it, true) }
                queued = NovelServiceDataSingleton.downloadQueue.toList().map { QueueItem.Novel(it, false) }
            }
        }
        val held = DownloadHolds.all().filter { it.mediaType == type }
        val all = ArrayList<QueueItem>()
        for (item in active + queued) {
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

        val rows = ArrayList<QueueRow>()
        items.groupBy { "${it.mediaType.name}|${it.title}" }.forEach { (key, group) ->
            rows.add(QueueRow.Group(key, group.first().title, group.size))
            group.forEach { rows.add(QueueRow.Task(it, progressOf(it))) }
        }
        adapter.submitList(rows)
        binding.downloadQueueEmptyState.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.downloadQueueRecyclerView.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        loadMissingCovers(items)
    }

    private fun loadMissingCovers(items: List<QueueItem>) {
        val missing = items.filter { !coverCache.containsKey(it.title) }.distinctBy { it.title }
        if (missing.isEmpty()) return
        missing.forEach { coverCache[it.title] = null }
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                missing.associate { item ->
                    val folder = DownloadsManager.getSubDirectory(
                        this@DownloadQueueActivity, item.mediaType, false, item.title, null
                    )
                    val file = folder?.findFile("cover.jpg")
                    val path = if (file != null && file.exists()) {
                        file.uri.toString()
                    } else {
                        PrefManager.getCustomVal<String>("local_cover_${item.title}", "")
                            .takeIf { it.isNotEmpty() && it != "null" }
                    }
                    item.title to path
                }
            }
            coverCache.putAll(found)
            adapter.notifyDataSetChanged()
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

        for (task in AnimeServiceDataSingleton.currentTasks.toList()) {
            sendBroadcast(
                Intent(AnimeDownloaderService.ACTION_CANCEL_DOWNLOAD)
                    .putExtra(AnimeDownloaderService.EXTRA_TASK_NAME, task.getTaskName())
            )
        }
        for (task in MangaServiceDataSingleton.currentTasks.toList()) {
            sendBroadcast(
                Intent(MangaDownloaderService.ACTION_CANCEL_DOWNLOAD)
                    .putExtra(MangaDownloaderService.EXTRA_CHAPTER, task.chapter)
            )
        }
        for (task in NovelServiceDataSingleton.currentTasks.toList()) {
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

sealed class QueueRow {
    data class Group(val key: String, val title: String, val count: Int) : QueueRow()
    data class Task(val item: QueueItem, val progress: Int?) : QueueRow()
}

private class QueueDiff : DiffUtil.ItemCallback<QueueRow>() {
    override fun areItemsTheSame(oldItem: QueueRow, newItem: QueueRow): Boolean = when {
        oldItem is QueueRow.Group && newItem is QueueRow.Group -> oldItem.key == newItem.key
        oldItem is QueueRow.Task && newItem is QueueRow.Task ->
            oldItem.item.mediaType == newItem.item.mediaType &&
                oldItem.item.uniqueId == newItem.item.uniqueId
        else -> false
    }

    override fun areContentsTheSame(oldItem: QueueRow, newItem: QueueRow): Boolean = when {
        oldItem is QueueRow.Group && newItem is QueueRow.Group -> oldItem == newItem
        oldItem is QueueRow.Task && newItem is QueueRow.Task ->
            oldItem.progress == newItem.progress &&
                oldItem.item.isActive == newItem.item.isActive &&
                oldItem.item.heldReason == newItem.item.heldReason
        else -> false
    }
}

private class QueueAdapter(
    private val covers: Map<String, String?>,
    private val onAction: (QueueItem) -> Unit,
    private val onCancel: (QueueItem) -> Unit
) : ListAdapter<QueueRow, RecyclerView.ViewHolder>(QueueDiff()) {

    private class GroupHolder(val binding: ItemQueueGroupHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    private class TaskHolder(val binding: ItemQueueDownloadBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int) = if (getItem(position) is QueueRow.Group) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            GroupHolder(ItemQueueGroupHeaderBinding.inflate(inflater, parent, false))
        } else {
            TaskHolder(ItemQueueDownloadBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is QueueRow.Group -> {
                val b = (holder as GroupHolder).binding
                b.queueGroupTitle.text = row.title
                val context = b.root.context
                b.queueGroupCount.text = context.resources.getQuantityString(
                    R.plurals.download_queue_group_count, row.count, row.count
                )
                val cover = covers[row.title]
                if (cover != null) {
                    b.queueGroupImage.loadImage(cover)
                    b.queueGroupImageCard.visibility = View.VISIBLE
                } else {
                    b.queueGroupImageCard.visibility = View.GONE
                }
            }

            is QueueRow.Task -> {
                val b = (holder as TaskHolder).binding
                val item = row.item
                val context = b.root.context
                b.queueTaskName.text = item.subTitle
                val state = context.getString(
                    when {
                        item.heldReason == HoldReason.PAUSED -> R.string.download_state_paused
                        item.heldReason == HoldReason.FAILED -> R.string.download_state_failed
                        item.isActive -> R.string.download_state_downloading
                        else -> R.string.download_state_queued
                    }
                )
                b.queueTaskState.text = "$state • ${item.type}"
                if (item.isActive) {
                    b.queueTaskProgressLayout.visibility = View.VISIBLE
                    if (row.progress != null) {
                        b.queueTaskProgress.isIndeterminate = false
                        b.queueTaskProgress.progress = row.progress
                        b.queueTaskPercent.text = "${row.progress}%"
                    } else {
                        b.queueTaskProgress.isIndeterminate = true
                        b.queueTaskPercent.text = "0%"
                    }
                } else {
                    b.queueTaskProgressLayout.visibility = View.GONE
                }
                when (item.heldReason) {
                    HoldReason.PAUSED -> {
                        b.queueTaskAction.setImageResource(R.drawable.ic_round_play_arrow_24)
                        b.queueTaskAction.contentDescription = context.getString(R.string.download_resume)
                    }

                    HoldReason.FAILED -> {
                        b.queueTaskAction.setImageResource(R.drawable.ic_round_refresh_24)
                        b.queueTaskAction.contentDescription = context.getString(R.string.download_retry)
                    }

                    null -> {
                        b.queueTaskAction.setImageResource(R.drawable.ic_round_pause_24)
                        b.queueTaskAction.contentDescription = context.getString(R.string.download_pause)
                    }
                }
                b.queueTaskAction.setOnClickListener { onAction(item) }
                b.queueTaskCancel.setOnClickListener { onCancel(item) }
            }
        }
    }
}
