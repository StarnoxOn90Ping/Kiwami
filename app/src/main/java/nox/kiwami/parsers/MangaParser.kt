package nox.kiwami.parsers

import nox.kiwami.FileUrl
import nox.kiwami.media.MediaNameAdapter
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import java.io.Serializable

abstract class MangaParser : BaseParser() {

    abstract suspend fun loadChapters(
        mangaLink: String,
        extra: Map<String, String>?,
        sManga: SManga
    ): List<MangaChapter>

    open suspend fun getLatestChapter(
        mangaLink: String,
        extra: Map<String, String>?,
        sManga: SManga,
        latest: Float
    ): MangaChapter? {
        val chapter = loadChapters(mangaLink, extra, sManga)
        val max = chapter
            .maxByOrNull { MediaNameAdapter.findChapterNumber(it.number) ?: 0f }
        return max
            ?.takeIf { latest < (MediaNameAdapter.findChapterNumber(it.number) ?: 0.001f) }
    }

    abstract suspend fun loadImages(chapterLink: String, sChapter: SChapter): List<MangaImage>


    open fun getTransformation(): BitmapTransformation? = null
}

class EmptyMangaParser : MangaParser() {
    override val name: String = "None"
    override val saveName: String = "None"

    override suspend fun loadChapters(
        mangaLink: String,
        extra: Map<String, String>?,
        sManga: SManga
    ): List<MangaChapter> = emptyList()

    override suspend fun loadImages(chapterLink: String, sChapter: SChapter): List<MangaImage> =
        emptyList()

    override suspend fun search(query: String): List<ShowResponse> = emptyList()
}

data class MangaChapter(
    val number: String,

    val link: String,

    val title: String? = null,
    val description: String? = null,
    val scanlator: String,
    val sChapter: SChapter,
    val date: Long? = null,
)

data class MangaImage(
    val url: FileUrl,

    val useTransformation: Boolean = false,

    @Transient val page: Page? = null,
) : Serializable {
    constructor(url: String, useTransformation: Boolean = false, page: Page? = null)
            : this(FileUrl(url), useTransformation, page)
}
