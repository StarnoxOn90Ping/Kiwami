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

    fun patch(mediaId: Int, isAnime: Boolean, progress: Int?, status: String?): Boolean = runCatching {
        if (status != null && status != "CURRENT" && status != "REPEATING") return@runCatching false
        val target = file()?.takeIf { it.exists() } ?: return@runCatching false
        val snapshot = ObjectInputStream(target.inputStream().buffered()).use { it.readObject() as Snapshot }
        val row = snapshot.data[if (isAnime) "currentAnime" else "currentManga"]
            ?: return@runCatching false
        val media = row.firstOrNull { it.id == mediaId } ?: return@runCatching false
        progress?.let { media.userProgress = it }
        status?.let { media.userStatus = it }
        media.userUpdatedAt = System.currentTimeMillis() / 1000
        write(target, snapshot)
        true
    }.getOrDefault(false)

    private fun write(target: File, snapshot: Snapshot) {
        val temp = File(target.parentFile, "home.tmp")
        ObjectOutputStream(temp.outputStream().buffered()).use { it.writeObject(snapshot) }
        temp.renameTo(target)
    }

    fun save(data: Map<String, ArrayList<Media>>) {
        runCatching {
            val rows = SmartCache.homeRowKeys()
            val selected = data.filterKeys { it in rows }
            val target = file() ?: return
            if (selected.isEmpty()) {
                target.delete()
                return
            }
            write(target, Snapshot(System.currentTimeMillis(), selected))
        }
    }

    fun clear() {
        runCatching { file()?.delete() }
    }
}
