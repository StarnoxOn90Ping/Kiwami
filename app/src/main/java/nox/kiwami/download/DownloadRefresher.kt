package nox.kiwami.download

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SEpisodeImpl
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nox.kiwami.FileUrl
import nox.kiwami.connections.anilist.Anilist
import nox.kiwami.media.Media
import nox.kiwami.media.MediaType
import nox.kiwami.media.anime.getEpisode
import nox.kiwami.util.Logger

object DownloadRefresher {
    data class Result(val total: Int, val refreshed: Int)
    data class Target(val type: MediaType, val title: String)
    data class Progress(val done: Int, val total: Int)

    private const val COVER = "cover.jpg"
    private const val BANNER = "banner.jpg"
    private const val MEDIA_FILE = "media.json"
    private const val TITLE_GAP_MS = 600L
    private const val RETRY_GAP_MS = 2500L
    private const val MAX_ATTEMPTS = 3

    private val gson = GsonBuilder()
        .registerTypeAdapter(SChapter::class.java, InstanceCreator<SChapter> { SChapterImpl() })
        .registerTypeAdapter(SAnime::class.java, InstanceCreator<SAnime> { SAnimeImpl() })
        .registerTypeAdapter(SEpisode::class.java, InstanceCreator<SEpisode> { SEpisodeImpl() })
        .create()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    private val _completed = MutableStateFlow<Pair<Int, Result>?>(null)
    val completed: StateFlow<Pair<Int, Result>?> = _completed

    val isRunning: Boolean get() = _progress.value != null

    fun targets(manager: DownloadsManager): List<Target> = listOf(
        MediaType.ANIME to manager.animeDownloadedTypes,
        MediaType.MANGA to manager.mangaDownloadedTypes,
        MediaType.NOVEL to manager.novelDownloadedTypes
    ).flatMap { (type, list) ->
        list.map { it.titleName }.distinct().sortedBy { it.lowercase() }.map { Target(type, it) }
    }

    fun start(context: Context, manager: DownloadsManager, selected: List<Target>): Boolean {
        if (selected.isEmpty()) return false
        synchronized(this) {
            if (_progress.value != null) return false
            _progress.value = Progress(0, selected.size)
        }
        val appContext = context.applicationContext
        scope.launch {
            var refreshed = 0
            try {
                selected.forEachIndexed { index, target ->
                    if (index > 0) delay(TITLE_GAP_MS)
                    _progress.value = Progress(index + 1, selected.size)
                    val items = (when (target.type) {
                        MediaType.ANIME -> manager.animeDownloadedTypes
                        MediaType.MANGA -> manager.mangaDownloadedTypes
                        else -> manager.novelDownloadedTypes
                    }).filter { it.titleName == target.title }
                    try {
                        if (refreshTitle(appContext, target.type, target.title, items)) refreshed++
                    } catch (e: Exception) {
                        Logger.log("Failed to refresh ${target.title}: ${e.message}")
                    }
                    OfflineLibraryCache.invalidate(target.title)
                }
            } finally {
                OfflineLibraryCache.clear()
                val id = (_completed.value?.first ?: 0) + 1
                _completed.value = id to Result(selected.size, refreshed)
                _progress.value = null
            }
        }
        return true
    }

    private suspend fun refreshTitle(
        context: Context,
        type: MediaType,
        title: String,
        items: List<DownloadedType>
    ): Boolean {
        val directory = DownloadsManager.findSubDirectory(context, type, title) ?: return false
        val mediaText = DownloadMetadata.readText(context, directory, MEDIA_FILE) ?: return false
        val media = try {
            gson.fromJson(mediaText, Media::class.java)
        } catch (e: Exception) {
            Logger.log("Failed to parse $MEDIA_FILE for $title: ${e.message}")
            null
        } ?: return false
        var fetched = false
        for (attempt in 0 until MAX_ATTEMPTS) {
            waitForRateLimit()
            Anilist.query.mediaDetails(media)
            fetched = media.cover?.startsWith("http") == true ||
                media.banner?.startsWith("http") == true
            if (fetched) break
            delay(RETRY_GAP_MS * (attempt + 1))
        }

        media.cover = refreshImage(context, media.cover, directory, COVER)
        media.banner = refreshImage(context, media.banner, directory, BANNER)

        items.forEach { item ->
            val folder = DownloadsManager.findSubDirectory(context, type, title, item.chapterName)
                ?: return@forEach
            if (type == MediaType.ANIME) refreshEpisode(context, media, item, folder)
            else refreshChapter(context, media, item, folder)
        }

        DownloadMetadata.writeText(context, directory, MEDIA_FILE, gson.toJson(media))
        return fetched
    }

    private suspend fun waitForRateLimit() {
        val remaining = Anilist.rateLimitReset - System.currentTimeMillis() / 1000
        if (remaining > 0) delay((remaining + 1) * 1000)
    }

    private fun refreshImage(
        context: Context,
        url: String?,
        directory: DocumentFile,
        name: String
    ): String? {
        if (url != null && url.startsWith("http")) {
            DownloadMetadata.saveImage(context, url, directory, name)?.let { return it }
        }
        val existing = directory.findFile(name)
        if (existing != null && existing.isFile && existing.length() > 0L) {
            return existing.uri.toString()
        }
        return url
    }

    private fun refreshEpisode(
        context: Context,
        media: Media,
        item: DownloadedType,
        folder: DocumentFile
    ) {
        val episode = media.anime?.episodes?.getEpisode(item.chapterName)
        val existing = DownloadMetadata.readEpisode(context, folder)
        var thumbnail = folder.findFile(DownloadMetadata.EPISODE_THUMBNAIL)
            ?.takeIf { it.isFile && it.length() > 0L }
            ?.name
        if (thumbnail == null) {
            val url = episode?.thumb?.url?.takeIf { it.startsWith("http") }
            if (url != null) {
                thumbnail = DownloadMetadata
                    .saveImage(context, url, folder, DownloadMetadata.EPISODE_THUMBNAIL)
                    ?.let { DownloadMetadata.EPISODE_THUMBNAIL }
            }
        }
        if (thumbnail != null && episode != null) {
            folder.findFile(thumbnail)?.let { episode.thumb = FileUrl(it.uri.toString()) }
        }
        DownloadMetadata.write(
            context,
            folder,
            DownloadMetadata.EPISODE_FILE,
            EpisodeInfo(
                number = item.chapterName,
                title = episode?.title ?: existing?.title,
                description = episode?.desc ?: existing?.description,
                thumbnail = thumbnail,
                filler = episode?.filler ?: existing?.filler ?: false,
                date = episode?.date ?: existing?.date,
                rating = episode?.rating ?: existing?.rating
            )
        )
    }

    private fun refreshChapter(
        context: Context,
        media: Media,
        item: DownloadedType,
        folder: DocumentFile
    ) {
        val chapters = media.manga?.chapters?.values
        val chapter = chapters?.firstOrNull {
            it.number == item.chapterName && it.scanlator == item.scanlator
        } ?: chapters?.firstOrNull { it.number == item.chapterName }
        val existing = DownloadMetadata.readChapter(context, folder)
        DownloadMetadata.write(
            context,
            folder,
            DownloadMetadata.CHAPTER_FILE,
            ChapterInfo(
                number = item.chapterName,
                title = chapter?.title ?: existing?.title,
                description = chapter?.description ?: existing?.description,
                scanlator = existing?.scanlator ?: chapter?.scanlator ?: item.scanlator,
                date = chapter?.date ?: existing?.date
            )
        )
    }
}
