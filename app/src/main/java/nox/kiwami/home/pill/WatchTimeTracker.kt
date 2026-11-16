package nox.kiwami.home.pill

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import nox.kiwami.home.streak.StreakManager
import nox.kiwami.media.manga.mangareader.MangaReaderActivity
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.util.Logger
import org.json.JSONObject

object WatchTimeTracker {

    data class Minutes(val anime: Int, val manga: Int) {
        val total get() = anime + manga
    }

    private const val KEEP_DAYS = 60
    private const val FLUSH_AFTER_MS = 15_000L
    private const val MAX_ANIME_GAP_MS = 6_000L
    private const val MAX_MANGA_STRETCH_MS = 45 * 60_000L

    private var pendingAnimeMs = 0L
    private var pendingMangaMs = 0L
    private var pendingDay = -1L

    private var lastAnimeTick = 0L
    private var mangaStart = 0L
    private var installed = false

    @Synchronized
    fun tickAnime(playing: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (playing && lastAnimeTick > 0L && !incognito()) {
            addPending(anime = (now - lastAnimeTick).coerceIn(0L, MAX_ANIME_GAP_MS), manga = 0L)
        }
        lastAnimeTick = if (playing) now else 0L
    }

    @Synchronized
    fun stopAnime() {
        lastAnimeTick = 0L
        flush()
    }

    @Synchronized
    private fun addPending(anime: Long, manga: Long) {
        if (anime <= 0L && manga <= 0L) return
        val day = StreakManager.today()
        if (pendingDay != -1L && pendingDay != day) flush()
        pendingDay = day
        pendingAnimeMs += anime
        pendingMangaMs += manga
        if (pendingAnimeMs + pendingMangaMs >= FLUSH_AFTER_MS) flush()
    }

    @Synchronized
    fun flush() {
        if (pendingDay == -1L || (pendingAnimeMs <= 0L && pendingMangaMs <= 0L)) return
        try {
            val root = readRoot()
            val key = pendingDay.toString()
            val old = root.optJSONArray(key)
            val a = (old?.optLong(0) ?: 0L) + pendingAnimeMs / 1000
            val m = (old?.optLong(1) ?: 0L) + pendingMangaMs / 1000
            pendingAnimeMs %= 1000
            pendingMangaMs %= 1000
            root.put(key, org.json.JSONArray().put(a).put(m))
            val cutoff = StreakManager.today() - KEEP_DAYS
            root.keys().asSequence().toList().forEach { k ->
                if ((k.toLongOrNull() ?: 0L) < cutoff) root.remove(k)
            }
            PrefManager.setVal(PrefName.WatchTimeData, root.toString())
        } catch (e: Exception) {
            Logger.log(e)
        }
    }

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MangaReaderActivity) mangaStart = SystemClock.elapsedRealtime()
            }

            override fun onActivityPaused(activity: Activity) {
                if (activity is MangaReaderActivity) endMangaStretch()
            }

            override fun onActivityCreated(a: Activity, s: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }

    @Synchronized
    private fun endMangaStretch() {
        val start = mangaStart
        mangaStart = 0L
        if (start <= 0L || incognito()) return
        val ms = (SystemClock.elapsedRealtime() - start).coerceIn(0L, MAX_MANGA_STRETCH_MS)
        addPending(anime = 0L, manga = ms)
        flush()
    }

    private fun incognito(): Boolean = try {
        PrefManager.getVal<Boolean>(PrefName.Incognito)
    } catch (e: Exception) {
        false
    }

    @Synchronized
    fun todayMinutes(): Minutes {
        val today = StreakManager.today()
        var a = 0L
        var m = 0L
        try {
            val stored = readRoot().optJSONArray(today.toString())
            a = stored?.optLong(0) ?: 0L
            m = stored?.optLong(1) ?: 0L
        } catch (e: Exception) {
            Logger.log(e)
        }
        if (pendingDay == today) {
            a += pendingAnimeMs / 1000
            m += pendingMangaMs / 1000
        }
        if (mangaStart > 0L && !incognito()) {
            m += (SystemClock.elapsedRealtime() - mangaStart).coerceIn(0L, MAX_MANGA_STRETCH_MS) / 1000
        }
        return Minutes((a / 60).toInt(), (m / 60).toInt())
    }

    private fun readRoot(): JSONObject {
        val raw = PrefManager.getVal<String>(PrefName.WatchTimeData)
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }
}
