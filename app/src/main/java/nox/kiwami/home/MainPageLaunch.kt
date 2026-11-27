package nox.kiwami.home

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    private suspend fun prepareUser(context: Context) {
        Anilist.userid = PrefManager.getNullableVal<String>(PrefName.AnilistUserId, null)
            ?.toIntOrNull()
        getUserId(context) {}
    }

    private suspend fun warmPage(
        context: Context,
        type: String,
        onList: Boolean,
        isLoaded: () -> Boolean,
        markLoaded: () -> Unit,
        load: suspend () -> Unit
    ) {
        if (isStartPage(type) || isLoaded()) return
        val cached = MainPageCache.has(type, MainPageCache.key(onList))
        if (!cached) {
            delay(DELAY_MS)
            if (isLoaded() || !isOnline(context)) return
            prepareUser(context)
        }
        markLoaded()
        load()
    }

    suspend fun warmUp(
        context: Context,
        anime: AnilistAnimeViewModel,
        manga: AnilistMangaViewModel
    ) = coroutineScope {
        val animeOnList = PrefManager.getVal<Boolean>(PrefName.PopularAnimeList)
        val mangaOnList = PrefManager.getVal<Boolean>(PrefName.PopularMangaList)
        launch {
            warmPage(
                context, MainPageCache.ANIME, animeOnList,
                { anime.loaded }, { anime.loaded = true }, { anime.loadAll(animeOnList) }
            )
        }
        launch {
            warmPage(
                context, MainPageCache.MANGA, mangaOnList,
                { manga.loaded }, { manga.loaded = true }, { manga.loadAll(mangaOnList) }
            )
        }
    }
}
