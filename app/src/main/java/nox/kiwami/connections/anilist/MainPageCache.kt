package nox.kiwami.connections.anilist

import nox.kiwami.currContext
import nox.kiwami.media.Media
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.TimeZone

object MainPageCache {
    const val ANIME = "anime"
    const val MANGA = "manga"

    private const val DAY_MS = 24L * 60 * 60 * 1000

    private class Snapshot(
        val day: Long,
        val key: String,
        val data: Map<String, ArrayList<Media>>
    ) : Serializable

    private fun today(): Long {
        val now = System.currentTimeMillis()
        return (now + TimeZone.getDefault().getOffset(now)) / DAY_MS
    }

    private fun file(type: String): File? {
        val base = currContext()?.filesDir ?: return null
        val dir = File(base, "main_page_cache").apply { mkdirs() }
        return File(dir, "$type.bin")
    }

    fun key(onList: Boolean): String {
        val adult = runCatching { PrefManager.getVal<Boolean>(PrefName.AdultOnly) }.getOrDefault(false)
        val listOnly = runCatching { PrefManager.getVal<Boolean>(PrefName.RecentlyListOnly) }.getOrDefault(false)
        return "$onList|$adult|$listOnly"
    }

    fun load(type: String, key: String): Map<String, ArrayList<Media>>? = runCatching {
        val f = file(type)?.takeIf { it.exists() } ?: return@runCatching null
        val snapshot = ObjectInputStream(f.inputStream().buffered()).use { it.readObject() as Snapshot }
        if (snapshot.day == today() && snapshot.key == key) snapshot.data else null
    }.getOrNull()

    fun save(type: String, key: String, data: Map<String, ArrayList<Media>>) {
        if (data.values.none { it.isNotEmpty() }) return
        runCatching {
            val target = file(type) ?: return
            val temp = File(target.parentFile, "$type.tmp")
            ObjectOutputStream(temp.outputStream().buffered()).use {
                it.writeObject(Snapshot(today(), key, data))
            }
            temp.renameTo(target)
        }
    }
}
