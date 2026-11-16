package nox.kiwami.home.streak

import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.util.Logger
import com.google.gson.Gson
import java.time.LocalDateTime

/**
 * Watch / read streak, stored on the device only.
 *
 * Rules
 *  - Finishing an episode (80% watched) or a chapter counts for "today", at most once per day.
 *  - A day runs 04:00 -> 04:00, so late-night sessions still belong to the previous day.
 *  - One missed day per week is forgiven (the "grace day"): the streak carries on, it just does
 *    not grow for the skipped day.
 *  - The data is a JSON string in [PrefName.StreakData] (Location.General), so it is part of the
 *    normal backup / restore.
 */
object StreakManager {

    val STAGE_STARTS = intArrayOf(0, 1, 3, 7, 14, 30)
    const val MAX_STAGE = 5

    const val BUTTON_FIRE_STAGE = 3

    fun stageOf(streak: Int): Int {
        var stage = 0
        for (i in STAGE_STARTS.indices) if (streak >= STAGE_STARTS[i]) stage = i
        return stage
    }

    data class Record(
        var streak: Int = 0,
        var best: Int = 0,
        var lastDay: Long = -1L,
        var graceWeek: Long = -1L,
        var days: MutableList<Long> = mutableListOf(),
        var graceDays: MutableList<Long> = mutableListOf()
    )

    enum class Status {
        NONE,

        ACTIVE,

        AT_RISK,

        GRACE,

        LOST
    }

    enum class Dot { EMPTY, LIT, GRACE }

    data class Snapshot(
        val count: Int,
        val best: Int,
        val status: Status,
        val lostStreak: Int,
        val doneToday: Boolean,
        val week: List<Dot>
    )

    private val gson = Gson()
    private var doneDay = -1L

    fun today(): Long = LocalDateTime.now().minusHours(dayStartHour().toLong()).toLocalDate().toEpochDay()

    /** Monday-based week index of an epoch day (epoch day 4 = Monday 5 Jan 1970). */
    private fun weekOf(day: Long): Long = Math.floorDiv(day - 4L, 7L)

    fun dayStartHour(): Int = PrefManager.getVal<Int>(PrefName.StreakDayStart).coerceIn(0, 8)

    fun graceEnabled(): Boolean = PrefManager.getVal(PrefName.StreakGraceDay)

    fun episodeThreshold(): Float =
        PrefManager.getVal<Int>(PrefName.StreakThreshold).coerceIn(50, 100) / 100f

    fun intensityBoost(): Float = 1f

    fun debugEnabled(): Boolean = PrefManager.getVal(PrefName.StreakDebug)

    val DEBUG_STATUSES = listOf(null, Status.ACTIVE, Status.AT_RISK, Status.GRACE, Status.LOST)

    private fun load(): Record = try {
        val raw = PrefManager.getVal<String>(PrefName.StreakData)
        if (raw.isBlank()) Record() else gson.fromJson(raw, Record::class.java) ?: Record()
    } catch (e: Exception) {
        Logger.log("Streak: could not read data, starting fresh")
        Record()
    }

    private fun save(r: Record) {
        val cutoff = today() - 30
        r.days = r.days.filter { it >= cutoff }.toMutableList()
        r.graceDays = r.graceDays.filter { it >= cutoff }.toMutableList()
        PrefManager.setVal(PrefName.StreakData, gson.toJson(r))
    }

    @Synchronized
    fun recordActivity(): Boolean {
        val today = today()
        if (doneDay == today) return false

        val r = load()
        if (r.lastDay == today) {
            doneDay = today
            return false
        }

        val gap = if (r.lastDay < 0) Long.MAX_VALUE else today - r.lastDay
        when {
            gap < 0 -> return false
            r.lastDay < 0 -> r.streak = 1
            gap == 1L -> r.streak += 1
            gap == 2L && graceAvailable(r, r.lastDay + 1) -> {
                val missed = r.lastDay + 1
                r.graceWeek = weekOf(missed)
                r.graceDays.add(missed)
                r.streak += 1
            }

            else -> r.streak = 1
        }
        r.lastDay = today
        if (!r.days.contains(today)) r.days.add(today)
        if (r.streak > r.best) r.best = r.streak
        save(r)
        doneDay = today
        return true
    }

    private fun graceAvailable(r: Record, missedDay: Long) =
        graceEnabled() && r.graceWeek != weekOf(missedDay)

    @Synchronized
    fun reset() {
        PrefManager.setVal(PrefName.StreakData, "")
        doneDay = -1L
    }

    fun snapshot(): Snapshot {
        val r = load()
        val today = today()
        val gap = if (r.lastDay < 0) Long.MAX_VALUE else today - r.lastDay

        var count = 0
        var status = Status.NONE
        var lost = 0
        when {
            r.lastDay < 0 || r.streak <= 0 -> Unit
            gap <= 0L -> { count = r.streak; status = Status.ACTIVE }
            gap == 1L -> { count = r.streak; status = Status.AT_RISK }
            gap == 2L && graceAvailable(r, r.lastDay + 1) -> { count = r.streak; status = Status.GRACE }
            else -> if (r.streak >= 3) { status = Status.LOST; lost = r.streak }
        }

        val week = (6 downTo 0).map { back ->
            val d = today - back
            when {
                r.days.contains(d) -> Dot.LIT
                r.graceDays.contains(d) -> Dot.GRACE
                else -> Dot.EMPTY
            }
        }
        val real = Snapshot(count, r.best, status, lost, status == Status.ACTIVE, week)
        return if (debugEnabled()) debugSnapshot(real) else real
    }

    private fun debugSnapshot(real: Snapshot): Snapshot {
        val n = PrefManager.getVal<Int>(PrefName.StreakDebugCount).coerceAtLeast(0)
        val forced = DEBUG_STATUSES.getOrNull(PrefManager.getVal<Int>(PrefName.StreakDebugStatus))
        val status = forced ?: if (n > 0) Status.ACTIVE else Status.NONE
        return when (status) {
            Status.LOST -> real.copy(count = 0, status = status, lostStreak = maxOf(n, 3), doneToday = false)
            Status.NONE -> real.copy(count = 0, status = status, lostStreak = 0, doneToday = false)
            else -> real.copy(
                count = n, best = maxOf(real.best, n), status = status, lostStreak = 0,
                doneToday = status == Status.ACTIVE
            )
        }
    }
}
