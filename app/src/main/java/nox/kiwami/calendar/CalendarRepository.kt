package nox.kiwami.calendar

import nox.kiwami.connections.anilist.Anilist
import nox.kiwami.currContext
import nox.kiwami.isOnline
import nox.kiwami.media.Media
import nox.kiwami.media.anime.Anime
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

object CalendarRepository {

    enum class State { IDLE, SYNCING, OFFLINE, FAILED }

    class Snapshot(
        val fetchedAtMs: Long,
        val fullAtMs: Long,
        val fromSec: Long,
        val toSec: Long,
        val entries: List<CalendarEntry>
    )

    private data class LibItem(val id: Int, val status: String?, val progress: Int?)

    private sealed class Plan {
        object None : Plan()
        object Full : Plan()
        class Partial(val ids: Set<Int>) : Plan()
    }

    private const val SNAP_KEY = "cal_snapshot_v1_"
    private const val LIB_KEY = "cal_library_v1"
    private const val DIRTY_KEY = "cal_dirty_v1"
    private const val FULL_DIRTY_KEY = "cal_full_dirty_v1"
    private const val MINE_MAX_AGE_MS = 8L * 3600 * 1000
    private const val ALL_MAX_AGE_MS = 2L * 3600 * 1000
    private const val RETRY_AGE_MS = 5L * 60 * 1000
    private const val WARM_GAP_MS = 60_000L
    private const val DAY_SEC = 86_400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val memory = HashMap<Int, Snapshot>()
    private val forcing = HashSet<Int>()
    private var lastWarm = 0L

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun window(source: Int): Pair<Long, Long> {
        val fromCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -CalendarPrefs.pastDays) }
        val toCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, CalendarPrefs.aheadDays + 1) }
        val from = startOfDay(fromCal.timeInMillis) / 1000
        var to = startOfDay(toCal.timeInMillis) / 1000
        if (source == CalendarPrefs.SOURCE_ALL) {
            val cap = startOfDay(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 8) }.timeInMillis) / 1000
            to = minOf(to, cap)
        }
        return from to to
    }

    fun entriesIn(snap: Snapshot, from: Long, to: Long): List<CalendarEntry> =
        snap.entries.filter { it.airingAt in from until to }

    @Synchronized
    fun snapshot(source: Int): Snapshot? {
        memory[source]?.let { return it }
        val raw = PrefManager.getCustomVal(SNAP_KEY + source, "")
        if (raw.isBlank()) return null
        return try {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("entries")
            val entries = (0 until arr.length()).map { entryFromJson(arr.getJSONObject(it)) }
            Snapshot(o.getLong("fetched"), o.getLong("full"), o.getLong("from"), o.getLong("to"), entries)
                .also { memory[source] = it }
        } catch (e: Exception) {
            Logger.log(e)
            null
        }
    }

    @Synchronized
    private fun saveSnapshot(source: Int, snap: Snapshot) {
        memory[source] = snap
        try {
            val arr = JSONArray()
            snap.entries.forEach { arr.put(entryToJson(it)) }
            PrefManager.setCustomVal(
                SNAP_KEY + source,
                JSONObject().put("fetched", snap.fetchedAtMs).put("full", snap.fullAtMs)
                    .put("from", snap.fromSec).put("to", snap.toSec).put("entries", arr).toString()
            )
        } catch (e: Exception) {
            Logger.log(e)
        }
    }

    fun refresh(force: Boolean = false, source: Int = CalendarPrefs.source): Job? {
        synchronized(forcing) {
            if (force && !forcing.add(source)) return null
        }
        if (_state.value != State.SYNCING) _state.value = State.IDLE
        return scope.launch {
            try {
                lock.withLock { run(source, force) }
            } finally {
                synchronized(forcing) { forcing.remove(source) }
            }
        }
    }

    fun warm() {
        val now = System.currentTimeMillis()
        if (now - lastWarm < WARM_GAP_MS) return
        lastWarm = now
        refresh(false)
    }

    fun onListEdited(mediaId: Int, status: String?, progress: Int?) {
        scope.launch {
            var needsFetch = false
            try {
                lock.withLock {
                    val lib = loadLib()?.toMutableList() ?: return@withLock
                    val idx = lib.indexOfFirst { it.id == mediaId }
                    val old = lib.getOrNull(idx)
                    val item = LibItem(mediaId, status ?: old?.status, progress ?: old?.progress)
                    if (idx < 0) lib.add(item) else lib[idx] = item
                    saveLib(lib)

                    val snap = snapshot(CalendarPrefs.SOURCE_MINE)
                    if (snap != null && snap.entries.any { it.media.id == mediaId }) {
                        val patched = snap.entries.map {
                            if (it.media.id == mediaId) it.copy(listStatus = item.status, progress = item.progress) else it
                        }
                        saveSnapshot(
                            CalendarPrefs.SOURCE_MINE,
                            Snapshot(snap.fetchedAtMs, snap.fullAtMs, snap.fromSec, snap.toSec, patched)
                        )
                        _version.value = _version.value + 1
                    }
                    if (idx < 0 && status != null) {
                        saveDirty(loadDirty() + mediaId)
                        needsFetch = true
                    }
                }
            } catch (e: Exception) {
                Logger.log(e)
            }
            if (needsFetch) refresh(false)
        }
    }

    fun onListDeleted() {
        PrefManager.setCustomVal(FULL_DIRTY_KEY, true)
        refresh(false)
    }

    private suspend fun run(source: Int, force: Boolean) {
        if (source == CalendarPrefs.SOURCE_MINE && Anilist.userid == null) return
        val (from, to) = window(source)
        val now = System.currentTimeMillis()
        val snap = snapshot(source)
        val plan = plan(snap, source, from, to, now, force)
        if (plan is Plan.None) return

        val ctx = currContext()
        if (ctx != null && !isOnline(ctx)) {
            _state.value = State.OFFLINE
            return
        }

        _state.value = State.SYNCING
        try {
            val ok = when (plan) {
                is Plan.Full -> runFull(source, from, to, snap)
                is Plan.Partial -> runPartial(source, from, to, snap!!, plan.ids)
                else -> true
            }
            _state.value = if (ok) State.IDLE else State.FAILED
        } catch (e: CancellationException) {
            _state.value = State.IDLE
            throw e
        } catch (e: Exception) {
            Logger.log(e)
            _state.value = State.FAILED
        }
    }

    private fun plan(snap: Snapshot?, source: Int, from: Long, to: Long, now: Long, force: Boolean): Plan {
        val fullDirty = PrefManager.getCustomVal(FULL_DIRTY_KEY, false)
        if (snap == null || force || fullDirty) return Plan.Full
        val covers = snap.fromSec <= from && snap.toSec >= to - DAY_SEC
        val maxAge = if (source == CalendarPrefs.SOURCE_MINE) MINE_MAX_AGE_MS else ALL_MAX_AGE_MS
        if (!covers || now - snap.fullAtMs > maxAge) return Plan.Full
        if (source != CalendarPrefs.SOURCE_MINE) return Plan.None
        val aired = snap.entries
            .filter { it.airingAt * 1000 > snap.fetchedAtMs && it.airingAt * 1000 <= now }
            .map { it.media.id }
        val ids = loadDirty() + aired
        return if (ids.isEmpty()) Plan.None else Plan.Partial(ids)
    }

    private suspend fun runFull(source: Int, from: Long, to: Long, old: Snapshot?): Boolean {
        val now = System.currentTimeMillis()
        val entries: List<CalendarEntry>
        var fullAt = now

        if (source == CalendarPrefs.SOURCE_ALL) {
            entries = Anilist.query.recentlyUpdated(from, to).mapNotNull { m ->
                val parts = m.relation?.split(",") ?: return@mapNotNull null
                val ep = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val at = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                CalendarEntry(m, ep, at, m.userStatus, m.userProgress)
            }.sortedBy { it.airingAt }
        } else {
            val userId = Anilist.userid ?: return true
            val library = Anilist.query.getMediaLists(true, userId)["All"] ?: arrayListOf()
            if (library.isEmpty()) {
                if (old != null) return false
                fullAt = now - MINE_MAX_AGE_MS + RETRY_AGE_MS
                saveSnapshot(source, Snapshot(now, fullAt, from, to, emptyList()))
                _version.value = _version.value + 1
                return true
            }
            saveLib(library.map { LibItem(it.id, it.userStatus, it.userProgress) })
            val byId = library.associateBy { it.id }
            val ids = library.filter {
                val st = it.status?.uppercase() ?: return@filter true
                st.contains("RELEASING") || st.contains("NOT")
            }.map { it.id }

            val fetched = if (ids.isEmpty()) emptyList() else Anilist.query.airingScheduleFor(ids, from, to)
            entries = fetched.map { (media, ep, at) ->
                val own = byId[media.id]
                CalendarEntry(media, ep, at, own?.userStatus ?: media.userStatus, own?.userProgress ?: media.userProgress)
            }.sortedBy { it.airingAt }

            val nowSec = now / 1000
            if (entries.isEmpty() && ids.isNotEmpty() && old?.entries?.any { it.airingAt > nowSec } == true) {
                return false
            }
        }

        saveSnapshot(source, Snapshot(now, fullAt, from, to, entries))
        PrefManager.setCustomVal(FULL_DIRTY_KEY, false)
        if (source == CalendarPrefs.SOURCE_MINE) saveDirty(emptySet())
        _version.value = _version.value + 1
        return true
    }

    private suspend fun runPartial(source: Int, from: Long, to: Long, snap: Snapshot, ids: Set<Int>): Boolean {
        val now = System.currentTimeMillis()
        val lib = loadLib().orEmpty().associateBy { it.id }
        val fetched = Anilist.query.airingScheduleFor(ids.toList(), from, to)
        val nowSec = now / 1000

        val hadUpcoming = snap.entries.any { it.media.id in ids && it.airingAt > nowSec }
        if (fetched.isEmpty() && hadUpcoming) return false

        val fresh = fetched.map { (media, ep, at) ->
            val own = lib[media.id]
            CalendarEntry(media, ep, at, own?.status ?: media.userStatus, own?.progress ?: media.userProgress)
        }
        val merged = (snap.entries.filter { it.media.id !in ids } + fresh).sortedBy { it.airingAt }
        saveSnapshot(source, Snapshot(now, snap.fullAtMs, snap.fromSec, snap.toSec, merged))
        saveDirty(loadDirty() - ids)
        _version.value = _version.value + 1
        return true
    }

    private fun loadLib(): List<LibItem>? {
        val raw = PrefManager.getCustomVal(LIB_KEY, "")
        if (raw.isBlank()) return null
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                LibItem(o.getInt("id"), o.str("s"), o.int("p"))
            }
        } catch (e: Exception) {
            Logger.log(e)
            null
        }
    }

    private fun saveLib(items: List<LibItem>) {
        try {
            val arr = JSONArray()
            items.forEach {
                arr.put(
                    JSONObject().put("id", it.id).put("s", it.status ?: JSONObject.NULL)
                        .put("p", it.progress ?: JSONObject.NULL)
                )
            }
            PrefManager.setCustomVal(LIB_KEY, arr.toString())
        } catch (e: Exception) {
            Logger.log(e)
        }
    }

    private fun loadDirty(): Set<Int> {
        val raw = PrefManager.getCustomVal(DIRTY_KEY, "")
        if (raw.isBlank()) return emptySet()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getInt(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun saveDirty(ids: Set<Int>) {
        PrefManager.setCustomVal(DIRTY_KEY, JSONArray(ids.toList()).toString())
    }

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else getString(key)

    private fun JSONObject.int(key: String): Int? = if (isNull(key)) null else getInt(key)

    private fun entryToJson(e: CalendarEntry): JSONObject {
        val m = e.media
        return JSONObject()
            .put("id", m.id)
            .put("mal", m.idMAL ?: JSONObject.NULL)
            .put("n", m.name ?: JSONObject.NULL)
            .put("r", m.nameRomaji)
            .put("u", m.userPreferredName)
            .put("c", m.cover ?: JSONObject.NULL)
            .put("b", m.banner ?: JSONObject.NULL)
            .put("a", m.isAdult)
            .put("ms", m.status ?: JSONObject.NULL)
            .put("f", m.format ?: JSONObject.NULL)
            .put("sc", m.meanScore ?: JSONObject.NULL)
            .put("te", m.anime?.totalEpisodes ?: JSONObject.NULL)
            .put("ep", e.episode)
            .put("at", e.airingAt)
            .put("ls", e.listStatus ?: JSONObject.NULL)
            .put("pr", e.progress ?: JSONObject.NULL)
    }

    private fun entryFromJson(o: JSONObject): CalendarEntry {
        val status = o.str("ls")
        val progress = o.int("pr")
        val media = Media(
            anime = Anime(totalEpisodes = o.int("te")),
            id = o.getInt("id"),
            idMAL = o.int("mal"),
            name = o.str("n"),
            nameRomaji = o.getString("r"),
            userPreferredName = o.getString("u"),
            cover = o.str("c"),
            banner = o.str("b"),
            isAdult = o.optBoolean("a", false),
            userProgress = progress,
            userStatus = status,
            status = o.str("ms"),
            format = o.str("f"),
            meanScore = o.int("sc")
        )
        return CalendarEntry(media, o.getInt("ep"), o.getLong("at"), status, progress)
    }
}
