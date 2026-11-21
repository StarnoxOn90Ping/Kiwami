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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nox.kiwami.FileUrl
import nox.kiwami.connections.anilist.Anilist
import nox.kiwami.media.Media
import nox.kiwami.media.MediaType
import nox.kiwami.media.anime.getEpisode
import nox.kiwami.util.Logger

object DownloadRefresher {
    data class Result(val total: Int, val refreshed: Int)

    private const val COVER = "cover.jpg"
    private const val BANNER = "banner.jpg"
    private const val MEDIA_FILE = "media.json"

    private val gson = GsonBuilder()
        .registerTypeAdapter(SChapter::class.java, InstanceCreator<SChapter> { SChapterImpl() })
        .registerTypeAdapter(SAnime::class.java, InstanceCreator<SAnime> { SAnimeImpl() })
        .registerTypeAdapter(SEpisode::class.java, InstanceCreator<SEpisode> { SEpisodeImpl() })
        .create()

    suspend fun refreshAll(
        context: Context,
        manager: DownloadsManager,
        onProgress: (Int, Int) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val groups = listOf(
            MediaType.ANIME to manager.animeDownloadedTypes,
            MediaType.MANGA to manager.mangaDownloadedTypes,
            MediaType.NOVEL to manager.novelDownloadedTypes
        ).flatMap { (type, list) ->
            list.groupBy { it.titleName }.map { Triple(type, it.key, it.value) }
        }
        var refreshed = 0
        groups.forEachIndexed { index, (type, title, items) ->
            onProgress(index + 1, groups.size)
            try {
                if (refreshTitle(context, type, title, items)) refreshed++
            } catch (e: Exception) {
                Logger.log("Failed to refresh $title: ${e.message}")
            }
            OfflineLibraryCache.invalidate(title)
        }
        Result(groups.size, refreshed)
    }

    private suspend fun refreshTitle(
        context: Context,
        type: MediaType,
        title: String,
        items: List<DownloadedType>
    ): Boolean {
        val directory = DownloadsManager.findSubDirectory(context, type, title) ?: return false
        val media = DownloadMetadata.read(context, directory, MEDIA_FILE, Media::class.java)
            ?: return false
        val before = media.cover
        Anilist.query.mediaDetails(media)
        val fetched = media.cover != before || media.cover?.startsWith("http") == true

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
