package nox.kiwami.download.queue

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import nox.kiwami.download.anime.AnimeDownloader
import nox.kiwami.download.anime.AnimeDownloaderService
import nox.kiwami.download.anime.AnimeServiceDataSingleton
import nox.kiwami.download.manga.MangaDownloaderService
import nox.kiwami.download.manga.MangaServiceDataSingleton
import nox.kiwami.download.novel.NovelDownloaderService
import nox.kiwami.download.novel.NovelServiceDataSingleton
import nox.kiwami.media.MediaType

object DownloadControl {
    const val EXTRA_TITLE = "extra_title"
    const val EXTRA_MEDIA_TYPE = "extra_media_type"

    fun pause(context: Context, item: QueueItem) {
        if (item.heldReason != null) return
        DownloadHolds.hold(item.held(HoldReason.PAUSED))
        sendCancel(context, item)
    }

    fun cancel(context: Context, item: QueueItem) {
        if (item.heldReason != null) {
            DownloadHolds.remove(item.mediaType, item.title, item.uniqueId)
            return
        }
        sendCancel(context, item)
    }

    fun resume(context: Context, item: QueueItem) {
        if (item.heldReason == null) return
        when (item) {
            is QueueItem.Anime -> {
                val task = item.task.copy(sessionId = -1L, cancelled = false)
                val name = task.getTaskName()
                val busy = AnimeServiceDataSingleton.downloadQueue.any { it.getTaskName() == name } ||
                    AnimeServiceDataSingleton.currentTasks.toList().any { it.getTaskName() == name }
                if (busy) return
                DownloadHolds.remove(item.mediaType, item.title, item.uniqueId)
                task.sourceMedia?.id?.let { AnimeDownloader.startDownload(it, task.episode) }
                AnimeServiceDataSingleton.downloadQueue.offer(task)
                AnimeServiceDataSingleton.isServiceRunning = true
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AnimeDownloaderService::class.java)
                )
            }

            is QueueItem.Manga -> {
                val task = item.task
                val busy = MangaServiceDataSingleton.downloadQueue
                    .any { it.title == task.title && it.chapter == task.chapter } ||
                    MangaServiceDataSingleton.currentTasks.toList()
                        .any { it.title == task.title && it.chapter == task.chapter }
                if (busy) return
                DownloadHolds.remove(item.mediaType, item.title, item.uniqueId)
                MangaServiceDataSingleton.downloadQueue.offer(task)
                MangaServiceDataSingleton.isServiceRunning = true
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MangaDownloaderService::class.java)
                )
            }

            is QueueItem.Novel -> {
                val task = item.task
                val busy = NovelServiceDataSingleton.downloadQueue
                    .any { it.title == task.title && it.chapter == task.chapter } ||
                    NovelServiceDataSingleton.currentTasks.toList()
                        .any { it.title == task.title && it.chapter == task.chapter }
                if (busy) return
                DownloadHolds.remove(item.mediaType, item.title, item.uniqueId)
                NovelServiceDataSingleton.downloadQueue.offer(task)
                NovelServiceDataSingleton.isServiceRunning = true
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, NovelDownloaderService::class.java)
                )
            }
        }
    }

    private fun sendCancel(context: Context, item: QueueItem) {
        val intent = when (item) {
            is QueueItem.Anime -> Intent(AnimeDownloaderService.ACTION_CANCEL_DOWNLOAD)
                .putExtra(AnimeDownloaderService.EXTRA_TASK_NAME, item.uniqueId)

            is QueueItem.Manga -> Intent(MangaDownloaderService.ACTION_CANCEL_DOWNLOAD)
                .putExtra(MangaDownloaderService.EXTRA_CHAPTER, item.uniqueId)
                .putExtra(EXTRA_TITLE, item.title)
                .putExtra(EXTRA_MEDIA_TYPE, MediaType.MANGA.name)

            is QueueItem.Novel -> Intent(NovelDownloaderService.ACTION_CANCEL_DOWNLOAD)
                .putExtra(NovelDownloaderService.EXTRA_CHAPTER, item.uniqueId)
                .putExtra(EXTRA_TITLE, item.title)
                .putExtra(EXTRA_MEDIA_TYPE, MediaType.NOVEL.name)
        }
        context.sendBroadcast(intent)
    }
}
