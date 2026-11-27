package nox.kiwami.settings.data

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.anggrayudi.storage.file.getAbsolutePath
import androidx.recyclerview.widget.LinearLayoutManager
import nox.kiwami.R
import nox.kiwami.databinding.ActivitySettingsDataBinding
import nox.kiwami.download.DownloadsManager
import nox.kiwami.download.storage.DownloadStorageActivity
import nox.kiwami.download.storage.DownloadStorageStats
import nox.kiwami.util.SizeFormatter
import nox.kiwami.initActivity
import nox.kiwami.media.MediaType
import nox.kiwami.navBarHeight
import nox.kiwami.util.LauncherWrapper
import nox.kiwami.util.StoragePermissions
import nox.kiwami.restartApp
import nox.kiwami.savePrefsToDownloads
import nox.kiwami.settings.Settings
import nox.kiwami.settings.SettingsAdapter
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.settings.saving.internal.Location
import nox.kiwami.settings.saving.internal.PreferencePackager
import nox.kiwami.statusBarHeight
import nox.kiwami.themes.ThemeManager
import nox.kiwami.toast
import nox.kiwami.util.customAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsDataActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsDataBinding
    private lateinit var launcher: LauncherWrapper
    private var cacheSize: Long = 0L
    private var cacheCalculated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivitySettingsDataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val context = this
        binding.settingsDataLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.dataSettingsBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val contract = ActivityResultContracts.OpenDocumentTree()
        launcher = LauncherWrapper(this, contract)

        val openDocumentLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    try {
                        val jsonString =
                            contentResolver.openInputStream(uri)?.readBytes()
                                ?: throw Exception("Error reading file")
                        val name = DocumentFile.fromSingleUri(this, uri)?.name ?: "settings"
                        if (name.endsWith(".ani")) {
                            val decryptedJson = jsonString.toString(Charsets.UTF_8)
                            if (PreferencePackager.unpack(decryptedJson)) restartApp()
                        } else {
                            toast(getString(R.string.unknown_file_type))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        toast(getString(R.string.error_importing_settings))
                    }
                }
            }

        updateStorageInfo()
        setupRecyclerView(openDocumentLauncher)
        calculateCacheSize()
    }

    override fun onResume() {
        super.onResume()
        calculateDownloadStorage()
    }

    private fun calculateDownloadStorage() {
        val manager = Injekt.get<DownloadsManager>()
        if (DownloadStorageStats.isCurrent(manager)) {
            binding.dataSettingsRecyclerView.adapter?.notifyDataSetChanged()
            return
        }
        lifecycleScope.launch {
            DownloadStorageStats.calculate(this@SettingsDataActivity, manager)
            binding.dataSettingsRecyclerView.adapter?.notifyDataSetChanged()
        }
    }

    private fun updateStorageInfo() {
        try {
            val path = Environment.getDataDirectory().path
            val stat = StatFs(path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val totalBytes = totalBlocks * blockSize
            val availableBytes = availableBlocks * blockSize
            val usedBytes = totalBytes - availableBytes

            val progress = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0
            binding.storageProgressBar.progress = progress

            val freeFormatted = Formatter.formatFileSize(this, availableBytes)
            val totalFormatted = Formatter.formatFileSize(this, totalBytes)
            binding.storageAvailableText.text =
                getString(R.string.storage_available_info, freeFormatted, totalFormatted)

            val downloadsDirUri = PrefManager.getVal<String>(PrefName.DownloadsDir)
            val displayPath = getReadableDownloadPath(downloadsDirUri)
            binding.storageDirectoryText.text = "Downloads: $displayPath"
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getReadableDownloadPath(uriString: String): String {
        val defaultPath = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path}/Kiwami"
        if (uriString.isBlank()) return defaultPath

        return try {
            val uri = Uri.parse(uriString)

            val docFile = DocumentFile.fromTreeUri(this, uri)
            val resolvedPath = runCatching { docFile?.getAbsolutePath(this) }.getOrNull()
            if (!resolvedPath.isNullOrBlank() && !resolvedPath.contains("/tree/") && !resolvedPath.contains("msd:")) {
                return resolvedPath
            }

            val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?: uri.lastPathSegment ?: ""
            if (docId.startsWith("msd:") || uriString.contains("msd:")) {
                val mediaId = docId.substringAfterLast(":")
                if (mediaId.isNotEmpty() && mediaId.all { it.isDigit() }) {
                    val contentUri = MediaStore.Files.getContentUri("external")
                    val projection = arrayOf(
                        MediaStore.MediaColumns.DATA,
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.DISPLAY_NAME
                    )
                    contentResolver.query(
                        contentUri,
                        projection,
                        "${MediaStore.MediaColumns._ID} = ?",
                        arrayOf(mediaId),
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                            if (dataIdx != -1) {
                                val path = cursor.getString(dataIdx)
                                if (!path.isNullOrBlank()) return path
                            }
                            val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                            val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                            val rel = if (relIdx != -1) cursor.getString(relIdx) else null
                            val name = if (nameIdx != -1) cursor.getString(nameIdx) else null
                            if (!rel.isNullOrBlank()) {
                                val sub = rel.trimEnd('/')
                                return "/storage/emulated/0/$sub"
                            } else if (!name.isNullOrBlank()) {
                                return "/storage/emulated/0/Download/$name"
                            }
                        }
                    }
                }
            }

            if (docId.contains("primary:") || uriString.contains("primary:") || uriString.contains("primary%3A")) {
                val decoded = Uri.decode(docId)
                val relative = decoded.substringAfter("primary:").substringAfter("primary%3A").trimStart('/')
                return "/storage/emulated/0/$relative"
            }

            if (docId.contains(":")) {
                val volume = docId.substringBefore(":")
                val relative = docId.substringAfter(":").trimStart('/')
                if (volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"))) {
                    return "/storage/$volume/$relative"
                }
            }

            val docName = docFile?.name
            if (!docName.isNullOrBlank() && !docName.contains("msd:")) {
                return "/storage/emulated/0/Download/$docName"
            }

            defaultPath
        } catch (e: Exception) {
            defaultPath
        }
    }

    private fun calculateCacheSize() {
        lifecycleScope.launch(Dispatchers.IO) {
            var size = 0L
            cacheDir?.let { size += getFolderSize(it) }
            externalCacheDir?.let { size += getFolderSize(it) }
            cacheSize = size
            cacheCalculated = true
            withContext(Dispatchers.Main) {
                binding.dataSettingsRecyclerView.adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun getFolderSize(dir: File): Long {
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (file in files) {
            size += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return size
    }

    private fun clearAppCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            val sizeToClear = cacheSize
            try {
                cacheDir?.deleteRecursively()
                externalCacheDir?.deleteRecursively()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            cacheSize = 0L
            withContext(Dispatchers.Main) {
                val formatted = Formatter.formatFileSize(this@SettingsDataActivity, sizeToClear)
                toast(getString(R.string.cache_cleared, formatted))
                binding.dataSettingsRecyclerView.adapter?.notifyDataSetChanged()
                calculateCacheSize()
                updateStorageInfo()
            }
        }
    }

    @SuppressLint("SimpleDateFormat")
    private fun setupRecyclerView(openDocumentLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>) {
        val context = this
        val intervals = arrayOf(
            getString(R.string.auto_backup_interval_off),
            getString(R.string.auto_backup_interval_6h),
            getString(R.string.auto_backup_interval_12h),
            getString(R.string.auto_backup_interval_24h),
            getString(R.string.auto_backup_interval_48h),
            getString(R.string.auto_backup_interval_weekly)
        )
        val copyCounts = arrayOf("1", "2", "3", "5")

        val settingsList = arrayListOf(
            Settings(
                type = 1,
                name = getString(R.string.download_storage),
                desc = getString(R.string.download_storage_desc),
                icon = R.drawable.ic_download_24,
                attach = { b ->
                    b.settingsDesc.text = if (DownloadStorageStats.calculatedOnce) {
                        getString(
                            R.string.download_storage_row_desc,
                            SizeFormatter.formatBytes(DownloadStorageStats.totalBytes())
                        )
                    } else {
                        getString(R.string.cache_calculating)
                    }
                },
                onClick = {
                    startActivity(Intent(context, DownloadStorageActivity::class.java))
                },
                isActivity = true
            ),

            Settings(
                type = 2,
                name = getString(R.string.auto_delete_downloads),
                desc = getString(R.string.auto_delete_downloads_desc),
                icon = R.drawable.ic_round_delete_24,
                isChecked = PrefManager.getCustomVal("auto_delete_downloads", false),
                switch = { isChecked, _ ->
                    PrefManager.setCustomVal("auto_delete_downloads", isChecked)
                }
            ),

            Settings(
                type = 1,
                name = getString(R.string.clear_cache),
                desc = getString(
                    R.string.clear_cache_desc,
                    if (cacheSize > 0) Formatter.formatFileSize(context, cacheSize) else getString(R.string.cache_calculating)
                ),
                icon = R.drawable.ic_round_delete_24,
                attach = { b ->
                    b.settingsDesc.text = getString(
                        R.string.clear_cache_desc,
                        if (cacheCalculated) Formatter.formatFileSize(context, cacheSize)
                        else getString(R.string.cache_calculating)
                    )
                },
                onClick = {
                    context.customAlertDialog().apply {
                        setTitle(R.string.clear_cache)
                        setMessage(R.string.clear_cache)
                        setPosButton(R.string.ok) {
                            clearAppCache()
                        }
                        setNegButton(R.string.cancel)
                        show()
                    }
                }
            ),

            Settings(
                type = 1,
                name = getString(R.string.auto_backup_interval),
                desc = intervals.getOrElse(PrefManager.getVal(PrefName.AutoBackupInterval)) { intervals[0] },
                icon = R.drawable.ic_round_auto_awesome_24,
                onClick = {
                    val current = PrefManager.getVal<Int>(PrefName.AutoBackupInterval)
                    context.customAlertDialog().apply {
                        setTitle(R.string.auto_backup_interval)
                        singleChoiceItems(intervals, current) { which ->
                            PrefManager.setVal(PrefName.AutoBackupInterval, which)
                            AutoBackupWorker.schedule(context, which)
                            binding.dataSettingsRecyclerView.adapter?.notifyDataSetChanged()
                        }
                        setPosButton(R.string.ok)
                        show()
                    }
                }
            ),

            Settings(
                type = 1,
                name = getString(R.string.auto_backup_max_copies),
                desc = "${PrefManager.getVal<Int>(PrefName.AutoBackupMaxCopies)} backups",
                icon = R.drawable.backup_restore,
                onClick = {
                    val current = PrefManager.getVal<Int>(PrefName.AutoBackupMaxCopies)
                    val currentIndex = copyCounts.indexOf(current.toString()).coerceAtLeast(0)
                    context.customAlertDialog().apply {
                        setTitle(R.string.auto_backup_max_copies)
                        singleChoiceItems(copyCounts, currentIndex) { which ->
                            val copies = copyCounts[which].toIntOrNull() ?: 3
                            PrefManager.setVal(PrefName.AutoBackupMaxCopies, copies)
                            binding.dataSettingsRecyclerView.adapter?.notifyDataSetChanged()
                        }
                        setPosButton(R.string.ok)
                        show()
                    }
                }
            ),

            Settings(
                type = 1,
                name = getString(R.string.auto_backup),
                desc = run {
                    val timestamp = PrefManager.getVal<Long>(PrefName.LastAutoBackupTimestamp)
                    if (timestamp > 0) {
                        val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
                        getString(R.string.last_auto_backup, formattedDate)
                    } else {
                        getString(R.string.last_auto_backup_never)
                    }
                },
                icon = R.drawable.backup_restore,
                onClick = {}
            ),

            Settings(
                type = 1,
                name = getString(R.string.backup_now),
                desc = getString(R.string.backup_now_desc),
                icon = R.drawable.backup_restore,
                onClick = {
                    StoragePermissions.downloadsPermission(context)
                    val exportableLocations = Location.entries.filter { it.exportable }
                    val timestampStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
                    savePrefsToDownloads(
                        "Kiwami_${timestampStr}",
                        PrefManager.exportAllPrefs(exportableLocations),
                        context
                    )
                }
            ),

            Settings(
                type = 1,
                name = getString(R.string.restore_backup),
                desc = getString(R.string.restore_backup_desc),
                icon = R.drawable.backup_restore,
                onClick = {
                    StoragePermissions.downloadsPermission(context)
                    openDocumentLauncher.launch(arrayOf("*/*"))
                }
            ),

            Settings(
                type = 1,
                name = getString(R.string.change_download_directory),
                desc = getString(R.string.change_download_directory_desc),
                icon = R.drawable.ic_round_folder_24,
                onClick = {
                    val oldUri = PrefManager.getVal<String>(PrefName.DownloadsDir)
                    context.customAlertDialog().apply {
                        setTitle(R.string.change_download_directory)
                        setMessage(R.string.change_download_directory_confirm)
                        setPosButton(R.string.ok) {
                            launcher.registerForCallback { success ->
                                if (success) {
                                    toast(getString(R.string.please_wait))
                                    val newUri = PrefManager.getVal<String>(PrefName.DownloadsDir)
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        Injekt.get<DownloadsManager>().moveDownloadsDir(
                                            context,
                                            Uri.parse(oldUri),
                                            Uri.parse(newUri),
                                        ) { finished, message ->
                                            if (finished) {
                                                toast(getString(R.string.success))
                                                updateStorageInfo()
                                            } else {
                                                toast(message)
                                            }
                                        }
                                    }
                                } else {
                                    toast(getString(R.string.error))
                                }
                            }
                            launcher.launch()
                        }
                        setNegButton(R.string.cancel)
                        show()
                    }
                }
            ),

            Settings(
                type = 1,
                name = getString(R.string.rebuild_download_index),
                desc = getString(R.string.rebuild_download_index_desc),
                icon = R.drawable.ic_download_24,
                onClick = {
                    context.customAlertDialog().apply {
                        setTitle(R.string.rebuild_download_index)
                        setMessage(R.string.rebuild_download_index_msg)
                        setPosButton(R.string.ok) {
                            toast(getString(R.string.please_wait))
                            lifecycleScope.launch(Dispatchers.IO) {
                                val recovered = Injekt.get<DownloadsManager>().rebuildIndexFromDisk()
                                withContext(Dispatchers.Main) {
                                    toast(getString(R.string.rebuild_download_index_done, recovered))
                                    updateStorageInfo()
                                }
                            }
                        }
                        setNegButton(R.string.cancel)
                        show()
                    }
                }
            ),

            Settings(
                type = 1,
                name = getString(R.string.purge_downloads),
                desc = getString(R.string.purge_downloads_desc),
                icon = R.drawable.ic_round_delete_24,
                onClick = {
                    context.customAlertDialog().apply {
                        setTitle(R.string.purge_downloads)
                        setMessage(R.string.purge_confirm, "${getString(R.string.anime)} & ${getString(R.string.manga)}")
                        setPosButton(R.string.yes) {
                            val downloadsManager = Injekt.get<DownloadsManager>()
                            downloadsManager.purgeDownloads(MediaType.ANIME)
                            downloadsManager.purgeDownloads(MediaType.MANGA)
                            updateStorageInfo()
                        }
                        setNegButton(R.string.no)
                        show()
                    }
                }
            )
        )

        binding.dataSettingsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            adapter = SettingsAdapter(settingsList)
            setHasFixedSize(true)
        }
    }
}
