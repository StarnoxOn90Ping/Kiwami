package nox.kiwami.download.storage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
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
import nox.kiwami.databinding.ItemDownloadQueueBinding
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
        tabs.addTab(tabs.newTab().setText(R.string.manga))
        tabs.addTab(tabs.newTab().setText(R.string.hub_refresh_novel))
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

    private fun currentType() = when (selectedTab) {
        0 -> MediaType.ANIME
        1 -> MediaType.MANGA
        else -> MediaType.NOVEL
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

    private fun render() {
        val type = currentType()
        val entries = DownloadStorageStats.entries(manager, type)
        adapter.submitList(entries)
        binding.downloadStorageEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.downloadStorageList.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE

        val typeLabel = getString(
            when (type) {
                MediaType.ANIME -> R.string.anime
                MediaType.MANGA -> R.string.manga
                MediaType.NOVEL -> R.string.hub_refresh_novel
            }
        )
        binding.downloadStorageSummary.text = if (calculating) {
            getString(R.string.cache_calculating)
        } else {
            getString(
                R.string.download_storage_summary,
                SizeFormatter.formatBytes(DownloadStorageStats.totalBytes()),
                typeLabel,
                SizeFormatter.formatBytes(DownloadStorageStats.totalBytes(type)),
                entries.size
            )
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

private class StorageDiff : DiffUtil.ItemCallback<DownloadStorageStats.Entry>() {
    override fun areItemsTheSame(
        oldItem: DownloadStorageStats.Entry,
        newItem: DownloadStorageStats.Entry
    ) = oldItem.title == newItem.title && oldItem.type == newItem.type

    override fun areContentsTheSame(
        oldItem: DownloadStorageStats.Entry,
        newItem: DownloadStorageStats.Entry
    ) = oldItem == newItem
}

private class StorageAdapter(
    private val onDelete: (DownloadStorageStats.Entry) -> Unit
) : ListAdapter<DownloadStorageStats.Entry, StorageAdapter.Holder>(StorageDiff()) {

    class Holder(val binding: ItemDownloadQueueBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemDownloadQueueBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = getItem(position)
        val b = holder.binding
        b.itemDownloadTitle.text = entry.title
        val unit = when (entry.type) {
            MediaType.ANIME -> if (entry.count == 1) "episode" else "episodes"
            else -> if (entry.count == 1) "chapter" else "chapters"
        }
        b.itemDownloadSubtitle.text = "${entry.count} $unit • ${SizeFormatter.formatBytes(entry.sizeBytes)}"
        if (entry.cover != null) {
            b.itemDownloadImage.loadImage(entry.cover)
            b.itemDownloadImageCard.visibility = View.VISIBLE
        } else {
            b.itemDownloadImageCard.visibility = View.GONE
        }
        b.itemDownloadCancel.setImageResource(R.drawable.ic_round_delete_24)
        b.itemDownloadCancel.setOnClickListener { onDelete(entry) }
        b.itemDownloadProgressLayout.visibility = View.GONE
    }
}
