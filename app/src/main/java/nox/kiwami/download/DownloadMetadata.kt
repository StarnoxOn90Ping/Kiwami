package nox.kiwami.download

import android.content.Context
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

object DownloadMetadata {
    const val EPISODE_FILE = "episode.json"
    const val EPISODE_THUMBNAIL = "episodeImage.jpg"

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun write(context: Context, directory: DocumentFile, name: String, value: Any) {
        try {
            directory.findFile(name)?.forceDelete(context)
            val file = directory.createFile("application/json", name) ?: return
            file.openOutputStream(context, false).use { output ->
                output?.write(gson.toJson(value).toByteArray())
            }
        } catch (e: Exception) {
            Logger.log("Failed to write $name: ${e.message}")
        }
    }

    fun readEpisode(context: Context, directory: DocumentFile): EpisodeInfo? {
        return try {
            val file = directory.findFile(EPISODE_FILE) ?: return null
            val text = context.contentResolver.openInputStream(file.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return null
            gson.fromJson(text, EpisodeInfo::class.java)
        } catch (e: Exception) {
            Logger.log("Failed to read $EPISODE_FILE: ${e.message}")
            null
        }
    }
}
