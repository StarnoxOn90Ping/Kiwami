package nox.kiwami.download.queue

import nox.kiwami.download.anime.AnimeDownloaderService
import nox.kiwami.download.manga.MangaDownloaderService
import nox.kiwami.download.novel.NovelDownloaderService
import nox.kiwami.media.MediaType
import java.util.concurrent.CopyOnWriteArrayList

object DownloadHolds {
    private val entries = CopyOnWriteArrayList<QueueItem>()

    fun all(): List<QueueItem> = entries.toList()

    fun hold(item: QueueItem) {
        remove(item.mediaType, item.title, item.uniqueId)
        entries.add(item)
    }

    fun remove(type: MediaType, title: String, id: String) {
        entries.removeAll { it.mediaType == type && it.title == title && it.uniqueId == id }
    }

    fun clear() = entries.clear()

    fun holdFailed(task: AnimeDownloaderService.AnimeDownloadTask) =
        hold(QueueItem.Anime(task, false, HoldReason.FAILED))

    fun holdFailed(task: MangaDownloaderService.DownloadTask) =
        hold(QueueItem.Manga(task, false, HoldReason.FAILED))

    fun holdFailed(task: NovelDownloaderService.DownloadTask) =
        hold(QueueItem.Novel(task, false, HoldReason.FAILED))

    fun release(task: AnimeDownloaderService.AnimeDownloadTask) =
        remove(MediaType.ANIME, task.title, task.getTaskName())

    fun release(task: MangaDownloaderService.DownloadTask) =
        remove(MediaType.MANGA, task.title, task.chapter)

    fun release(task: NovelDownloaderService.DownloadTask) =
        remove(MediaType.NOVEL, task.title, task.chapter)
}
