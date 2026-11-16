package nox.kiwami.home.pill

import nox.kiwami.notifications.airing.AiringAlarmScheduler
import nox.kiwami.notifications.subscription.SubscriptionHelper
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.util.Logger
import org.json.JSONArray
import org.json.JSONObject

object InfoPillStore {

    data class AiringItem(
        val mediaId: Int,
        val name: String,
        val episode: Int,
        val airingAtSec: Long
    )

    data class AiringCache(val fetchedAtMs: Long, val idsKey: Int, val items: List<AiringItem>)

    data class PlannedSnapshot(
        val savedAtMs: Long,
        val anime: List<Long>,
        val manga: List<Long>
    )

    private const val AIRING_KEY = "pill_airing_v1"
    private const val PLANNED_KEY = "pill_planned_v1"

    @Synchronized
    fun loadAiring(): AiringCache? {
        val raw = PrefManager.getCustomVal(AIRING_KEY, "")
        if (raw.isBlank()) return null
        return try {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("items")
            val items = (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                AiringItem(e.getInt("id"), e.getString("name"), e.getInt("ep"), e.getLong("at"))
            }
            AiringCache(o.getLong("fetched"), o.optInt("key"), items)
        } catch (e: Exception) {
            Logger.log(e)
            null
        }
    }

    @Synchronized
    fun saveAiring(cache: AiringCache) {
        try {
            val arr = JSONArray()
            cache.items.forEach {
                arr.put(
                    JSONObject().put("id", it.mediaId).put("name", it.name)
                        .put("ep", it.episode).put("at", it.airingAtSec)
                )
            }
            PrefManager.setCustomVal(
                AIRING_KEY,
                JSONObject().put("fetched", cache.fetchedAtMs).put("key", cache.idsKey)
                    .put("items", arr).toString()
            )
        } catch (e: Exception) {
            Logger.log(e)
        }
    }

    fun alarmItems(): List<AiringItem> = try {
        SubscriptionHelper.getSubscriptions().filterValues { it.isAnime }.keys.mapNotNull { id ->
            AiringAlarmScheduler.getAlarm(id)?.let {
                AiringItem(it.mediaId, it.name, it.episode, it.airingAtSec)
            }
        }
    } catch (e: Exception) {
        Logger.log(e)
        emptyList()
    }

    fun allAiring(cached: List<AiringItem>): List<AiringItem> =
        (cached + alarmItems()).distinctBy { it.mediaId to it.episode }

    @Synchronized
    fun loadPlanned(): PlannedSnapshot? {
        val raw = PrefManager.getCustomVal(PLANNED_KEY, "")
        if (raw.isBlank()) return null
        return try {
            val o = JSONObject(raw)
            fun longs(a: JSONArray) = (0 until a.length()).map { a.getLong(it) }
            PlannedSnapshot(o.getLong("saved"), longs(o.getJSONArray("a")), longs(o.getJSONArray("m")))
        } catch (e: Exception) {
            Logger.log(e)
            null
        }
    }

    @Synchronized
    fun savePlanned(s: PlannedSnapshot) {
        try {
            PrefManager.setCustomVal(
                PLANNED_KEY,
                JSONObject().put("saved", s.savedAtMs)
                    .put("a", JSONArray(s.anime)).put("m", JSONArray(s.manga)).toString()
            )
        } catch (e: Exception) {
            Logger.log(e)
        }
    }
}
