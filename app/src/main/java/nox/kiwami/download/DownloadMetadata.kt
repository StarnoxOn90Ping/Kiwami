package nox.kiwami.download

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.forceDelete
import com.anggrayudi.storage.file.openOutputStream
import com.google.gson.GsonBuilder
import nox.kiwami.util.Logger

data class EpisodeInfo(
    val number: String,
    val title: String? = null,
    val description: String? = null,
    val thumbnail: String? = null,
    val filler: Boolean = false,
    val date: String? = null,
    val rating: String? = null
)

data class ChapterInfo(
    val number: String,
    val title: String? = null,
    val description: String? = null,
    val scanlator: String? = null,
    val date: Long? = null
)

object DownloadMetadata {
    const val EPISODE_FILE = "episode.json"
    const val CHAPTER_FILE = "chapter.json"
    const val EPISODE_THUMBNAIL = "episodeImage.jpg"

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun write(context: Context, directory: DocumentFile, name: String, value: Any) {
        writeText(context, directory, name, gson.toJson(value))
    }

    fun writeText(context: Context, directory: DocumentFile, name: String, text: String) {
        try {
            directory.findFile(name)?.forceDelete(context)
            val file = directory.createFile("application/json", name) ?: return
            file.openOutputStream(context, false).use { output ->
                output?.write(text.toByteArray())
            }
        } catch (e: Exception) {
            Logger.log("Failed to write $name: ${e.message}")
        }
    }

    fun <T> read(context: Context, directory: DocumentFile, name: String, type: Class<T>): T? {
        return try {
            val file = directory.findFile(name) ?: return null
            val text = context.contentResolver.openInputStream(file.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return null
            gson.fromJson(text, type)
        } catch (e: Exception) {
            Logger.log("Failed to read $name: ${e.message}")
            null
        }
    }

    fun readEpisode(context: Context, directory: DocumentFile): EpisodeInfo? =
        read(context, directory, EPISODE_FILE, EpisodeInfo::class.java)

    fun readChapter(context: Context, directory: DocumentFile): ChapterInfo? =
        read(context, directory, CHAPTER_FILE, ChapterInfo::class.java)

    fun saveImage(context: Context, url: String, directory: DocumentFile, name: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return null
            directory.findFile(name)?.forceDelete(context)
            val file = directory.createFile("image/jpeg", name) ?: return null
            file.openOutputStream(context, false).use { output -> output?.write(bytes) }
            file.uri.toString()
        } catch (e: Exception) {
            Logger.log("Failed to save $name: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
