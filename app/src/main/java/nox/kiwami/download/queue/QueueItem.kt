package nox.kiwami.download.queue

import nox.kiwami.download.anime.AnimeDownloaderService
import nox.kiwami.download.manga.MangaDownloaderService
import nox.kiwami.download.novel.NovelDownloaderService
import nox.kiwami.media.MediaType

enum class HoldReason { PAUSED, FAILED }

sealed class QueueItem {
    abstract val title: String
    abstract val subTitle: String
    abstract val type: String
    abstract val uniqueId: String
    abstract val isActive: Boolean
    abstract val mediaType: MediaType
    abstract val heldReason: HoldReason?

    abstract fun held(reason: HoldReason): QueueItem

    data class Anime(
        val task: AnimeDownloaderService.AnimeDownloadTask,
        override val isActive: Boolean,
        override val heldReason: HoldReason? = null
    ) : QueueItem() {
        override val title: String get() = task.title
        override val subTitle: String get() = task.episode
        override val type: String get() = "Anime"
        override val uniqueId: String get() = task.getTaskName()
        override val mediaType: MediaType get() = MediaType.ANIME
        override fun held(reason: HoldReason) = copy(isActive = false, heldReason = reason)
    }

    data class Manga(
        val task: MangaDownloaderService.DownloadTask,
        override val isActive: Boolean,
        override val heldReason: HoldReason? = null
    ) : QueueItem() {
        override val title: String get() = task.title
        override val subTitle: String get() = task.chapter
        override val type: String get() = "Manga"
        override val uniqueId: String get() = task.chapter
        override val mediaType: MediaType get() = MediaType.MANGA
        override fun held(reason: HoldReason) = copy(isActive = false, heldReason = reason)
    }

    data class Novel(
        val task: NovelDownloaderService.DownloadTask,
        override val isActive: Boolean,
        override val heldReason: HoldReason? = null
    ) : QueueItem() {
        override val title: String get() = task.title
        override val subTitle: String get() = task.chapter
        override val type: String get() = "Novel"
        override val uniqueId: String get() = task.chapter
        override val mediaType: MediaType get() = MediaType.NOVEL
        override fun held(reason: HoldReason) = copy(isActive = false, heldReason = reason)
    }
}
