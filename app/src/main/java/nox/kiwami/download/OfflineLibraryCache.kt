package nox.kiwami.download

import android.content.Context
import android.net.Uri
import nox.kiwami.download.anime.OfflineAnimeModel
import nox.kiwami.download.manga.OfflineMangaModel
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.util.Logger
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

object OfflineLibraryCache {
    private const val FILE_NAME = "offline_library_cache_v1.json"

    private class UriAdapter : JsonSerializer<Uri>, JsonDeserializer<Uri> {
        override fun serialize(src: Uri, typeOfSrc: Type, context: JsonSerializationContext) =
            JsonPrimitive(src.toString())

        override fun deserialize(
            json: JsonElement, typeOfT: Type, context: JsonDeserializationContext
        ): Uri = Uri.parse(json.asString)
    }

    private class Snapshot(
        val dir: String,
        val anime: Map<String, OfflineAnimeModel>,
        val manga: Map<String, OfflineMangaModel>
    )

    private val gson = GsonBuilder()
        .registerTypeHierarchyAdapter(Uri::class.java, UriAdapter())
        .create()

    private val anime = ConcurrentHashMap<String, OfflineAnimeModel>()
    private val manga = ConcurrentHashMap<String, OfflineMangaModel>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    private val stateLock = Any()
    private var loadedFromDisk = false
    private var currentDir: String? = null

    private fun downloadsDir(): String = PrefManager.getVal<String>(PrefName.DownloadsDir)

    private fun mangaKey(type: String, title: String) = "$type|$title"

    fun ensureLoaded(context: Context) {
        synchronized(stateLock) {
            val dir = downloadsDir()
            if (currentDir != null && currentDir != dir) {
                anime.clear()
                manga.clear()
                currentDir = dir
            }
            if (loadedFromDisk) return
            loadedFromDisk = true
            currentDir = dir
            try {
                val file = File(context.applicationContext.cacheDir, FILE_NAME)
                if (!file.exists()) return
                val snapshot = gson.fromJson(file.readText(), Snapshot::class.java) ?: return
                if (snapshot.dir != dir) return
                anime.putAll(snapshot.anime)
                manga.putAll(snapshot.manga)
            } catch (e: Exception) {
                Logger.log("OfflineLibraryCache: ignoring unreadable cache (${e.message})")
            }
        }
    }

    fun getAnime(title: String): OfflineAnimeModel? = anime[title]

    fun getManga(type: String, title: String): OfflineMangaModel? = manga[mangaKey(type, title)]

    suspend fun animeOrLoad(
        title: String,
        load: suspend () -> OfflineAnimeModel
    ): OfflineAnimeModel {
        anime[title]?.let { return it }
        return locks.getOrPut("A|$title") { Mutex() }.withLock {
            anime[title] ?: load().also { if (it.episodes != FALLBACK) anime[title] = it }
        }
    }

    suspend fun mangaOrLoad(
        type: String,
        title: String,
        load: suspend () -> OfflineMangaModel
    ): OfflineMangaModel {
        val key = mangaKey(type, title)
        manga[key]?.let { return it }
        return locks.getOrPut("M|$key") { Mutex() }.withLock {
            manga[key] ?: load().also { if (it.chapters != FALLBACK) manga[key] = it }
        }
    }

    fun invalidate(title: String) {
        val valid = title.findValidName()
        anime.remove(valid)
        anime.remove(title)
        for (type in listOf("manga", "novel", "MANGA", "NOVEL")) {
            manga.remove(mangaKey(type, valid))
            manga.remove(mangaKey(type, title))
        }
    }

    fun clearAnime() = anime.clear()

    fun clearManga() = manga.clear()

    fun clear() {
        anime.clear()
        manga.clear()
        synchronized(stateLock) { currentDir = downloadsDir() }
    }

    fun persist(context: Context) {
        try {
            val snapshot = Snapshot(downloadsDir(), HashMap(anime), HashMap(manga))
            val dir = context.applicationContext.cacheDir
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(gson.toJson(snapshot))
            if (!tmp.renameTo(File(dir, FILE_NAME))) tmp.delete()
        } catch (e: Exception) {
            Logger.log("OfflineLibraryCache: could not save cache (${e.message})")
        }
    }

    const val FALLBACK = "hmm"
}
