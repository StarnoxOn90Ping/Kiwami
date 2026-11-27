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
        val data: Map<String, ArrayList<Media>>
    ) : Serializable

    private fun dayOf(millis: Long): Long =
        (millis + TimeZone.getDefault().getOffset(millis)) / DAY_MS

    private fun dir(): File? {
        val base = currContext()?.filesDir ?: return null
        return File(base, "main_page_cache").apply { mkdirs() }
    }

    private fun file(type: String, key: String): File? =
        dir()?.let { File(it, "${type}_${key.hashCode().toUInt()}.bin") }

    fun key(onList: Boolean): String {
        val adult = runCatching { PrefManager.getVal<Boolean>(PrefName.AdultOnly) }.getOrDefault(false)
        val listOnly = runCatching { PrefManager.getVal<Boolean>(PrefName.RecentlyListOnly) }.getOrDefault(false)
        return "$onList|$adult|$listOnly"
    }

    fun has(type: String, key: String): Boolean = runCatching {
        val f = file(type, key) ?: return false
        f.exists() && dayOf(f.lastModified()) == dayOf(System.currentTimeMillis())
    }.getOrDefault(false)

    fun load(type: String, key: String): Map<String, ArrayList<Media>>? = runCatching {
        val f = file(type, key)?.takeIf { it.exists() } ?: return@runCatching null
        val snapshot = ObjectInputStream(f.inputStream().buffered()).use { it.readObject() as Snapshot }
        if (snapshot.day == dayOf(System.currentTimeMillis())) snapshot.data else null
    }.getOrNull()

    fun save(type: String, key: String, data: Map<String, ArrayList<Media>>) {
        if (data.values.none { it.isNotEmpty() }) return
        runCatching {
            val target = file(type, key) ?: return
            val temp = File(target.parentFile, "$type.tmp")
            ObjectOutputStream(temp.outputStream().buffered()).use {
                it.writeObject(Snapshot(dayOf(System.currentTimeMillis()), data))
            }
            if (temp.renameTo(target)) {
                target.parentFile?.listFiles { f -> f.name.startsWith("${type}_") && f != target }
                    ?.forEach { it.delete() }
            }
        }
    }
}
