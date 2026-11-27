package nox.kiwami.connections.anilist

import nox.kiwami.currContext
import nox.kiwami.media.Media
import nox.kiwami.others.SmartCache
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

object QuickLoadHome {

    private class Snapshot(
        val savedAt: Long,
        val data: Map<String, ArrayList<Media>>
    ) : Serializable

    private fun file(): File? {
        val base = currContext()?.cacheDir ?: return null
        return File(File(base, "quick_load").apply { mkdirs() }, "home.bin")
    }

    fun load(): Map<String, ArrayList<Media>>? = runCatching {
        val f = file()?.takeIf { it.exists() } ?: return@runCatching null
        val snapshot = ObjectInputStream(f.inputStream().buffered()).use { it.readObject() as Snapshot }
        if (System.currentTimeMillis() - snapshot.savedAt > SmartCache.maxAgeMs()) return@runCatching null
        val rows = SmartCache.homeRowKeys()
        snapshot.data.filterKeys { it in rows }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun save(data: Map<String, ArrayList<Media>>) {
        runCatching {
            val rows = SmartCache.homeRowKeys()
            val selected = data.filterKeys { it in rows }
            val target = file() ?: return
            if (selected.isEmpty()) {
                target.delete()
                return
            }
            val temp = File(target.parentFile, "home.tmp")
            ObjectOutputStream(temp.outputStream().buffered()).use {
                it.writeObject(Snapshot(System.currentTimeMillis(), selected))
            }
            temp.renameTo(target)
        }
    }

    fun clear() {
        runCatching { file()?.delete() }
    }
}
