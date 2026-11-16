package nox.kiwami.download.hub

import nox.kiwami.download.anime.AnimeServiceDataSingleton
import nox.kiwami.download.manga.MangaServiceDataSingleton
import nox.kiwami.download.novel.NovelServiceDataSingleton

data class DownloadActivityState(
    val total: Int,
    val running: Int,
    val percent: Int
) {
    val isActive: Boolean get() = total > 0

    companion object {
        val IDLE = DownloadActivityState(0, 0, -1)

        fun snapshot(): DownloadActivityState {
            return try {
                val queued = AnimeServiceDataSingleton.downloadQueue.size +
                        MangaServiceDataSingleton.downloadQueue.size +
                        NovelServiceDataSingleton.downloadQueue.size
                val running = AnimeServiceDataSingleton.currentTasks.size +
                        MangaServiceDataSingleton.currentTasks.size +
                        NovelServiceDataSingleton.currentTasks.size

                var sum = 0
                var n = 0
                for (map in listOf(
                    AnimeServiceDataSingleton.progress,
                    MangaServiceDataSingleton.progress,
                    NovelServiceDataSingleton.progress
                )) {
                    for (v in map.values) {
                        sum += v.coerceIn(0, 100)
                        n++
                    }
                }
                DownloadActivityState(
                    total = queued + running,
                    running = running,
                    percent = if (n == 0) -1 else sum / n
                )
            } catch (e: Exception) {
                IDLE
            }
        }
    }
}
