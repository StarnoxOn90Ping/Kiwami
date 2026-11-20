package nox.kiwami.calendar

import nox.kiwami.connections.anilist.Anilist
import nox.kiwami.currContext
import nox.kiwami.isOnline
import nox.kiwami.media.Media
import nox.kiwami.media.anime.Anime
import nox.kiwami.media.manga.Manga
import nox.kiwami.media.mangaupdates.MangaAnimeUtil
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    private class Prediction(
        val next: Long,
        val interval: Int,
        val chapter: Double?,
        val fetchedAt: Long,
        val none: Boolean
    )

    private sealed class Plan {
        object None : Plan()
        object Full : Plan()
        class Partial(val ids: Set<Int>) : Plan()
    }

    private const val SNAP_KEY = "cal_snapshot_v1_"
    private const val LIB_KEY = "cal_library_v1"
    private const val DIRTY_KEY = "cal_dirty_v1"
    private const val FULL_DIRTY_KEY = "cal_full_dirty_v1"
    private const val MANGA_DIRTY_KEY = "cal_manga_dirty_v1"
    private const val PREDICTION_KEY = "cal_manga_prediction_v1"
    private const val MANGA_MAX_AGE_MS = 6L * 3600 * 1000
    private const val PREDICTION_TTL_MS = 24L * 3600 * 1000
    private const val PREDICTION_FAIL_TTL_MS = 6L * 3600 * 1000
    private const val MANGA_FETCH_MASK = 1 or 2 or 4 or 8
    private const val MANGA_PARALLEL = 3
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

    private fun patchMangaSnapshot(mediaId: Int, status: String?, progress: Int?): Boolean {
        val snap = snapshot(CalendarPrefs.SOURCE_MANGA) ?: return false
        if (snap.entries.none { it.media.id == mediaId }) return false
        val patched = snap.entries.map {
            if (it.media.id == mediaId) {
                it.copy(listStatus = status ?: it.listStatus, progress = progress ?: it.progress)
            } else it
        }
        saveSnapshot(
            CalendarPrefs.SOURCE_MANGA,
            Snapshot(snap.fetchedAtMs, snap.fullAtMs, snap.fromSec, snap.toSec, patched)
        )
        _version.value = _version.value + 1
        return true
    }

    fun onListEdited(mediaId: Int, status: String?, progress: Int?) {
        scope.launch {
            var needsFetch = false
            try {
                lock.withLock {
                    if (patchMangaSnapshot(mediaId, status, progress)) return@withLock
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
        PrefManager.setCustomVal(MANGA_DIRTY_KEY, true)
        refresh(false)
    }

    private suspend fun run(source: Int, force: Boolean) {
        if (source != CalendarPrefs.SOURCE_ALL && Anilist.userid == null) return
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
                is Plan.Full -> runFull(source, from, to, snap, force)
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
        val dirtyKey = if (source == CalendarPrefs.SOURCE_MANGA) MANGA_DIRTY_KEY else FULL_DIRTY_KEY
        val fullDirty = PrefManager.getCustomVal(dirtyKey, false)
        if (snap == null || force || fullDirty) return Plan.Full
        val covers = snap.fromSec <= from && snap.toSec >= to - DAY_SEC
        val maxAge = when (source) {
            CalendarPrefs.SOURCE_MINE -> MINE_MAX_AGE_MS
            CalendarPrefs.SOURCE_MANGA -> MANGA_MAX_AGE_MS
            else -> ALL_MAX_AGE_MS
        }
        if (!covers || now - snap.fullAtMs > maxAge) return Plan.Full
        if (source != CalendarPrefs.SOURCE_MINE) return Plan.None
        val aired = snap.entries
            .filter { it.airingAt * 1000 > snap.fetchedAtMs && it.airingAt * 1000 <= now }
            .map { it.media.id }
        val ids = loadDirty() + aired
        return if (ids.isEmpty()) Plan.None else Plan.Partial(ids)
    }

    private suspend fun runFull(source: Int, from: Long, to: Long, old: Snapshot?, force: Boolean): Boolean {
        if (source == CalendarPrefs.SOURCE_MANGA) return runFullManga(from, to, old, force)
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

    private suspend fun runFullManga(from: Long, to: Long, old: Snapshot?, force: Boolean): Boolean {
        val userId = Anilist.userid ?: return true
        val now = System.currentTimeMillis()
        val library = Anilist.query.getMediaLists(false, userId)["All"] ?: arrayListOf()
        if (library.isEmpty()) {
            if (old != null) return false
            val fullAt = now - MANGA_MAX_AGE_MS + RETRY_AGE_MS
            saveSnapshot(CalendarPrefs.SOURCE_MANGA, Snapshot(now, fullAt, from, to, emptyList()))
            _version.value = _version.value + 1
            return true
        }

        val tracked = library.filter {
            val st = ListStatus.from(it.userStatus)
            st != null && (MANGA_FETCH_MASK and st.bit) != 0 && it.status?.uppercase() == "RELEASING"
        }

        val cache = loadPredictions()
        val gate = Semaphore(MANGA_PARALLEL)
        val fetched = coroutineScope {
            tracked.map { media ->
                async { gate.withPermit { media to predictionFor(media, cache[media.id], now, force) } }
            }.awaitAll()
        }

        val fresh = HashMap<Int, Prediction>()
        val entries = mutableListOf<CalendarEntry>()
        fetched.forEach { (media, prediction) ->
            fresh[media.id] = prediction
            entries.addAll(expandPrediction(media, prediction, from, to, now))
        }
        savePredictions(fresh)

        val nowSec = now / 1000
        val everyLookupFailed = tracked.isNotEmpty() && fresh.values.all { it.none }
        if (everyLookupFailed && old?.entries?.any { it.airingAt > nowSec } == true) return false

        saveSnapshot(
            CalendarPrefs.SOURCE_MANGA,
            Snapshot(now, now, from, to, entries.sortedBy { it.airingAt })
        )
        PrefManager.setCustomVal(MANGA_DIRTY_KEY, false)
        _version.value = _version.value + 1
        return true
    }

    private suspend fun predictionFor(media: Media, cached: Prediction?, now: Long, force: Boolean): Prediction {
        if (!force && cached != null) {
            val ttl = if (cached.none) PREDICTION_FAIL_TTL_MS else PREDICTION_TTL_MS
            if (now - cached.fetchedAt < ttl) return cached
        }
        val result = try {
            val series = MangaAnimeUtil.getSeriesFromMedia(media)
            MangaAnimeUtil.getNextChapterPrediction(media, series)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log(e)
            null
        }
        val date = result?.nextReleaseDate
        if (result == null || result.error != null || date == null) {
            val keep = cached?.takeIf { !it.none }
            return keep?.let {
                Prediction(it.next, it.interval, it.chapter, now - PREDICTION_TTL_MS + PREDICTION_FAIL_TTL_MS, false)
            } ?: Prediction(0L, 0, null, now, true)
        }
        val chapter = result.nextChapter
            ?.let { Regex("([0-9]+(?:[.][0-9]+)?)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        return Prediction(
            startOfDay(date.time),
            (result.averageIntervalDays ?: 7).coerceAtLeast(1),
            chapter,
            now,
            false
        )
    }

    private fun expandPrediction(media: Media, p: Prediction, from: Long, to: Long, now: Long): List<CalendarEntry> {
        if (p.none) return emptyList()
        val cal = Calendar.getInstance().apply { timeInMillis = p.next }
        var chapter = p.chapter
        val today = startOfDay(now)
        var guard = 0
        while (cal.timeInMillis < today && guard++ < 400) {
            cal.add(Calendar.DAY_OF_YEAR, p.interval)
            chapter = chapter?.plus(1)
        }
        val out = mutableListOf<CalendarEntry>()
        guard = 0
        while (cal.timeInMillis < to * 1000 && guard++ < 120) {
            if (cal.timeInMillis >= from * 1000) {
                val noon = (cal.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 12) }
                out.add(
                    CalendarEntry(
                        media,
                        chapter?.toInt() ?: 0,
                        noon.timeInMillis / 1000,
                        media.userStatus,
                        media.userProgress,
                        isManga = true
                    )
                )
            }
            cal.add(Calendar.DAY_OF_YEAR, p.interval)
            chapter = chapter?.plus(1)
        }
        return out
    }

    private fun loadPredictions(): Map<Int, Prediction> {
        val raw = PrefManager.getCustomVal(PREDICTION_KEY, "")
        if (raw.isBlank()) return emptyMap()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).associate {
                val o = arr.getJSONObject(it)
                o.getInt("id") to Prediction(
                    o.getLong("n"),
                    o.getInt("i"),
                    if (o.isNull("c")) null else o.getDouble("c"),
                    o.getLong("f"),
                    o.optBoolean("x", false)
                )
            }
        } catch (e: Exception) {
            Logger.log(e)
            emptyMap()
        }
    }

    private fun savePredictions(items: Map<Int, Prediction>) {
        try {
            val arr = JSONArray()
            items.forEach { (id, p) ->
                arr.put(
                    JSONObject().put("id", id).put("n", p.next).put("i", p.interval)
                        .put("c", p.chapter ?: JSONObject.NULL).put("f", p.fetchedAt).put("x", p.none)
                )
            }
            PrefManager.setCustomVal(PREDICTION_KEY, arr.toString())
        } catch (e: Exception) {
            Logger.log(e)
        }
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
            .put("te", m.anime?.totalEpisodes ?: m.manga?.totalChapters ?: JSONObject.NULL)
            .put("mg", e.isManga)
            .put("ep", e.episode)
            .put("at", e.airingAt)
            .put("ls", e.listStatus ?: JSONObject.NULL)
            .put("pr", e.progress ?: JSONObject.NULL)
    }

    private fun entryFromJson(o: JSONObject): CalendarEntry {
        val status = o.str("ls")
        val progress = o.int("pr")
        val isManga = o.optBoolean("mg", false)
        val media = Media(
            anime = if (isManga) null else Anime(totalEpisodes = o.int("te")),
            manga = if (isManga) Manga(totalChapters = o.int("te")) else null,
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
        return CalendarEntry(media, o.getInt("ep"), o.getLong("at"), status, progress, isManga)
    }
}
