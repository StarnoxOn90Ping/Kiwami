package nox.kiwami.download.storage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.MaterialColors
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nox.kiwami.R
import nox.kiwami.databinding.ActivityDownloadStorageBinding
import nox.kiwami.databinding.ItemDownloadStorageBinding
import nox.kiwami.download.DownloadsManager
import nox.kiwami.initActivity
import nox.kiwami.loadImage
import nox.kiwami.media.MediaType
import nox.kiwami.navBarHeight
import nox.kiwami.statusBarHeight
import nox.kiwami.themes.ThemeManager
import nox.kiwami.util.SizeFormatter
import nox.kiwami.util.customAlertDialog
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DownloadStorageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadStorageBinding
    private lateinit var adapter: StorageAdapter
    private val manager by lazy { Injekt.get<DownloadsManager>() }
    private var selectedTab = 0
    private var calculating = false
    private var calculateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityDownloadStorageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initActivity(this)
        binding.downloadStorageContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.downloadStorageBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        adapter = StorageAdapter { entry -> confirmDelete(entry) }
        binding.downloadStorageList.layoutManager = LinearLayoutManager(this)
        binding.downloadStorageList.adapter = adapter

        val tabs = binding.downloadStorageTabs
        tabs.addTab(tabs.newTab().setText(R.string.anime))
        tabs.addTab(tabs.newTab().setText(R.string.download_queue_tab_manga_novel))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedTab = tab.position
                render()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    override fun onResume() {
        super.onResume()
        render()
        startCalculation()
    }

    override fun onPause() {
        super.onPause()
        calculateJob?.cancel()
        calculateJob = null
        calculating = false
    }

    private fun startCalculation() {
        if (DownloadStorageStats.isCurrent(manager)) return
        calculateJob?.cancel()
        calculating = true
        calculateJob = lifecycleScope.launch {
            try {
                DownloadStorageStats.calculate(this@DownloadStorageActivity, manager) {
                    runOnUiThread { render() }
                }
            } finally {
                calculating = false
                render()
            }
        }
    }

    private fun tabEntries(): List<DownloadStorageStats.Entry> {
        val list = if (selectedTab == 0) {
            DownloadStorageStats.entries(manager, MediaType.ANIME)
        } else {
            DownloadStorageStats.entries(manager, MediaType.MANGA) +
                DownloadStorageStats.entries(manager, MediaType.NOVEL)
        }
        return list.sortedWith(compareByDescending<DownloadStorageStats.Entry> { it.sizeBytes }.thenBy { it.title })
    }

    private fun render() {
        val entries = tabEntries()
        val tabTotal = entries.sumOf { it.sizeBytes }
        val accent = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val secondary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorTertiary)
        val rows = entries.map { entry ->
            val share = if (tabTotal > 0) (entry.sizeBytes * 1000 / tabTotal).toInt() else 0
            StorageRow(entry, share, if (entry.type == MediaType.ANIME) accent else secondary)
        }
        adapter.submitList(rows)
        binding.downloadStorageEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.downloadStorageList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE

        val animeBytes = DownloadStorageStats.totalBytes(MediaType.ANIME)
        val otherBytes = DownloadStorageStats.totalBytes(MediaType.MANGA) +
            DownloadStorageStats.totalBytes(MediaType.NOVEL)
        val track = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutline)
        binding.downloadStorageChart.setData(listOf(animeBytes to accent, otherBytes to secondary), track)
        binding.downloadStorageChartTotal.text = SizeFormatter.formatBytes(animeBytes + otherBytes)
        binding.downloadStorageLegendAnime.text = SizeFormatter.formatBytes(animeBytes)
        binding.downloadStorageLegendOther.text = SizeFormatter.formatBytes(otherBytes)
        binding.downloadStorageDotAnime.background.mutate().setTint(accent)
        binding.downloadStorageDotOther.background.mutate().setTint(secondary)

        binding.downloadStorageSummary.text = if (calculating) {
            getString(R.string.cache_calculating)
        } else {
            getString(R.string.download_storage_tab_summary, SizeFormatter.formatBytes(tabTotal), entries.size)
        }
    }

    private fun confirmDelete(entry: DownloadStorageStats.Entry) {
        customAlertDialog().apply {
            setTitle("Delete Downloads")
            setMessage(getString(R.string.clear_media_confirm, entry.title))
            setPosButton(R.string.yes) {
                manager.removeMedia(entry.title, entry.type)
                DownloadStorageStats.forget(entry.type, entry.title)
                render()
            }
            setNegButton(R.string.no)
            show()
        }
    }
}

data class StorageRow(
    val entry: DownloadStorageStats.Entry,
    val share: Int,
    val color: Int
)

private class StorageDiff : DiffUtil.ItemCallback<StorageRow>() {
    override fun areItemsTheSame(oldItem: StorageRow, newItem: StorageRow) =
        oldItem.entry.title == newItem.entry.title && oldItem.entry.type == newItem.entry.type

    override fun areContentsTheSame(oldItem: StorageRow, newItem: StorageRow) = oldItem == newItem
}

private class StorageAdapter(
    private val onDelete: (DownloadStorageStats.Entry) -> Unit
) : ListAdapter<StorageRow, StorageAdapter.Holder>(StorageDiff()) {

    class Holder(val binding: ItemDownloadStorageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemDownloadStorageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val entry = row.entry
        val b = holder.binding
        b.itemStorageTitle.text = entry.title
        val (unit, label) = when (entry.type) {
            MediaType.ANIME -> (if (entry.count == 1) "episode" else "episodes") to "Anime"
            MediaType.MANGA -> (if (entry.count == 1) "chapter" else "chapters") to "Manga"
            MediaType.NOVEL -> (if (entry.count == 1) "chapter" else "chapters") to "Novel"
        }
        b.itemStorageSubtitle.text = "${entry.count} $unit • $label"
        b.itemStorageSize.text = SizeFormatter.formatBytes(entry.sizeBytes)
        b.itemStorageSize.setTextColor(row.color)
        b.itemStorageBar.setIndicatorColor(row.color)
        b.itemStorageBar.setProgressCompat(row.share, false)
        if (entry.cover != null) {
            b.itemStorageImage.loadImage(entry.cover)
            b.itemStorageImageCard.visibility = View.VISIBLE
        } else {
            b.itemStorageImageCard.visibility = View.GONE
        }
        b.itemStorageDelete.setOnClickListener { onDelete(entry) }
    }
}
