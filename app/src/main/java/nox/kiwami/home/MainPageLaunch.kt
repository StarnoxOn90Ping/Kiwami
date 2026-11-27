package nox.kiwami.home

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay
import nox.kiwami.connections.anilist.Anilist
import nox.kiwami.connections.anilist.AnilistAnimeViewModel
import nox.kiwami.connections.anilist.AnilistMangaViewModel
import nox.kiwami.connections.anilist.MainPageCache
import nox.kiwami.connections.anilist.getUserId
import nox.kiwami.isOnline
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName

object MainPageLaunch {
    const val DELAY_MS = 2000L

    private var startedAt = 0L
    private var startPage = 1

    fun begin(page: Int) {
        startedAt = SystemClock.elapsedRealtime()
        startPage = page
    }

    private fun isStartPage(type: String): Boolean =
        (type == MainPageCache.ANIME && startPage == 0) || (type == MainPageCache.MANGA && startPage == 2)

    fun isOpen(type: String): Boolean =
        isStartPage(type) || SystemClock.elapsedRealtime() - startedAt >= DELAY_MS

    private fun needsWarmUp(type: String, onList: Boolean): Boolean =
        !isStartPage(type) && !MainPageCache.has(type, MainPageCache.key(onList))

    private suspend fun prepareUser(context: Context) {
        Anilist.userid = PrefManager.getNullableVal<String>(PrefName.AnilistUserId, null)
            ?.toIntOrNull()
        getUserId(context) {}
    }

    suspend fun warmUp(
        context: Context,
        anime: AnilistAnimeViewModel,
        manga: AnilistMangaViewModel
    ) {
        delay(DELAY_MS)
        if (!isOnline(context)) return

        val animeOnList = PrefManager.getVal<Boolean>(PrefName.PopularAnimeList)
        if (!anime.loaded && needsWarmUp(MainPageCache.ANIME, animeOnList)) {
            prepareUser(context)
            anime.loaded = true
            anime.loadAll(animeOnList)
        }

        val mangaOnList = PrefManager.getVal<Boolean>(PrefName.PopularMangaList)
        if (!manga.loaded && needsWarmUp(MainPageCache.MANGA, mangaOnList)) {
            prepareUser(context)
            manga.loaded = true
            manga.loadAll(mangaOnList)
        }
    }
}
