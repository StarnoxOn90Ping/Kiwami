package nox.kiwami.download.storage

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import nox.kiwami.download.DownloadsManager
import nox.kiwami.media.MediaType
import nox.kiwami.settings.saving.PrefManager
import java.util.concurrent.ConcurrentHashMap

object DownloadStorageStats {
    data class Entry(
        val title: String,
        val type: MediaType,
        val count: Int,
        val sizeBytes: Long,
        val cover: String?
    )

    private const val PARALLEL_TITLES = 4

    private val sizes = ConcurrentHashMap<String, Long>()
    private val covers = ConcurrentHashMap<String, String>()
    private val mutex = Mutex()

    @Volatile
    private var version = -1

    @Volatile
    var calculatedOnce = false
        private set

    private fun key(type: MediaType, title: String) = "${type.name}|$title"

    private fun typesOf(manager: DownloadsManager, type: MediaType) = when (type) {
        MediaType.ANIME -> manager.animeDownloadedTypes
        MediaType.MANGA -> manager.mangaDownloadedTypes
        MediaType.NOVEL -> manager.novelDownloadedTypes
    }

    fun entries(manager: DownloadsManager, type: MediaType): List<Entry> {
        return typesOf(manager, type).groupBy { it.titleName }.map { (title, list) ->
            val k = key(type, title)
            Entry(
                title = title,
                type = type,
                count = list.size,
                sizeBytes = sizes[k] ?: 0L,
                cover = covers[k]?.takeIf { it.isNotEmpty() }
            )
        }.sortedBy { it.title }
    }

    fun totalBytes(): Long = sizes.values.sum()

    fun totalBytes(type: MediaType): Long =
        sizes.entries.filter { it.key.startsWith("${type.name}|") }.sumOf { it.value }

    fun forget(type: MediaType, title: String) {
        val k = key(type, title)
        sizes.remove(k)
        covers.remove(k)
    }

    fun isCurrent(manager: DownloadsManager) = calculatedOnce && version == manager.version

    suspend fun calculate(
        context: Context,
        manager: DownloadsManager,
        onProgress: () -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = manager.version
            if (calculatedOnce && version == current) return@withLock
            val live = HashSet<String>()
            val jobs = MediaType.values().flatMap { type ->
                typesOf(manager, type).map { it.titleName }.distinct().map { type to it }
            }
            jobs.forEach { (type, title) -> live.add(key(type, title)) }
            sizes.keys.retainAll(live)
            covers.keys.retainAll(live)
            val gate = Semaphore(PARALLEL_TITLES)
            coroutineScope {
                jobs.map { (type, title) ->
                    async {
                        gate.withPermit {
                            val folder = DownloadsManager.getSubDirectory(context, type, false, title, null)
                            val k = key(type, title)
                            sizes[k] = folderSize(folder)
                            covers[k] = coverOf(folder, title)
                            onProgress()
                        }
                    }
                }.awaitAll()
            }
            version = current
            calculatedOnce = true
        }
    }

    private fun coverOf(folder: DocumentFile?, title: String): String {
        val coverFile = folder?.findFile("cover.jpg")
        if (coverFile != null && coverFile.exists()) return coverFile.uri.toString()
        val cached = PrefManager.getCustomVal<String>("local_cover_$title", "")
        return if (cached.isNotEmpty() && cached != "null") cached else ""
    }

    private fun folderSize(directory: DocumentFile?): Long {
        if (directory == null || !directory.exists()) return 0L
        var size = 0L
        for (file in directory.listFiles()) {
            size += if (file.isDirectory) folderSize(file) else file.length()
        }
        return size
    }
}
