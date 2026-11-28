package nox.kiwami.home.continuebar

import nox.kiwami.media.Media
import nox.kiwami.settings.saving.PrefManager

object ContinueTracker {
    private const val ANIME = "Anime"
    private const val MANGA = "Manga"

    fun stampAnime(id: Int, number: Float?) {
        LastPlayed.record(true, id, number)
        stamp(ANIME)
    }

    fun stampManga(id: Int, number: Float?) {
        LastPlayed.record(false, id, number)
        stamp(MANGA)
    }

    fun markNovel(id: Int) {
        @Suppress("UNCHECKED_CAST")
        val list = (PrefManager.getNullableCustomVal(
            "continueMangaList",
            listOf<Int>(),
            List::class.java
        ) as List<Int>).toMutableList()
        list.remove(id)
        list.add(id)
        PrefManager.setCustomVal("continueMangaList", list)
        stampManga(id, null)
    }

    fun pick(anime: List<Media>?, manga: List<Media>?): Media? {
        val last = LastPlayed.get()
        if (last != null) {
            val source = if (last.anime) anime else manga
            val match = source?.firstOrNull { it.id == last.mediaId && canContinue(it) }
            if (match != null) return match
        }
        val a = anime?.firstOrNull { canContinue(it) }
        val m = manga?.firstOrNull { canContinue(it) }
        if (a == null || m == null) return a ?: m
        val sa = stampOf(ANIME)
        val sm = stampOf(MANGA)
        if (sa != sm) return if (sa > sm) a else m
        return if ((a.userUpdatedAt ?: 0L) >= (m.userUpdatedAt ?: 0L)) a else m
    }

    fun nextNumber(media: Media): Int {
        val next = (media.userProgress ?: 0) + 1
        val last = LastPlayed.get() ?: return next
        if (last.mediaId != media.id || last.anime != (media.anime != null)) return next
        return maxOf(next, last.number?.toInt() ?: 0)
    }

    fun canContinue(media: Media): Boolean {
        if (media.id <= 0) return false
        val progress = media.userProgress ?: 0
        val anime = media.anime
        if (anime != null) {
            val limit = anime.nextAiringEpisode ?: anime.totalEpisodes
            return limit == null || progress < limit
        }
        val total = media.manga?.totalChapters
        return total == null || progress < total
    }

    private fun stamp(type: String) {
        PrefManager.setCustomVal("continue${type}Stamp", System.currentTimeMillis())
    }

    private fun stampOf(type: String): Long =
        PrefManager.getCustomVal("continue${type}Stamp", 0L)
}
