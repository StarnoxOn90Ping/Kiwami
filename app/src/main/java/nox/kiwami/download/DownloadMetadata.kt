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
    private const val IMAGE_ATTEMPTS = 2

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun write(context: Context, directory: DocumentFile, name: String, value: Any) {
        val text = gson.toJson(value)
        if (readText(context, directory, name) == text) return
        writeText(context, directory, name, text)
    }

    fun writeText(context: Context, directory: DocumentFile, name: String, text: String) {
        try {
            replaceFile(context, directory, name, "application/json", text.toByteArray())
        } catch (e: Exception) {
            Logger.log("Failed to write $name: ${e.message}")
        }
    }

    private fun replaceFile(
        context: Context,
        directory: DocumentFile,
        name: String,
        mime: String,
        bytes: ByteArray
    ): Boolean {
        val tmpName = "$name.tmp"
        directory.findFile(tmpName)?.forceDelete(context)
        val tmp = directory.createFile("application/octet-stream", tmpName) ?: return false
        try {
            tmp.openOutputStream(context, false).use { output ->
                if (output == null) {
                    tmp.forceDelete(context)
                    return false
                }
                output.write(bytes)
                output.flush()
            }
        } catch (e: Exception) {
            tmp.forceDelete(context)
            throw e
        }
        directory.findFile(name)?.forceDelete(context)
        if (tmp.renameTo(name)) return true
        tmp.forceDelete(context)
        val file = directory.createFile(mime, name) ?: return false
        file.openOutputStream(context, false).use { output -> output?.write(bytes) }
        return true
    }

    fun readText(context: Context, directory: DocumentFile, name: String): String? {
        return try {
            val file = directory.findFile(name) ?: return null
            context.contentResolver.openInputStream(file.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Logger.log("Failed to read $name: ${e.message}")
            null
        }
    }

    fun <T> read(context: Context, directory: DocumentFile, name: String, type: Class<T>): T? {
        val text = readText(context, directory, name) ?: return null
        return try {
            gson.fromJson(text, type)
        } catch (e: Exception) {
            Logger.log("Failed to parse $name: ${e.message}")
            null
        }
    }

    fun readEpisode(context: Context, directory: DocumentFile): EpisodeInfo? =
        read(context, directory, EPISODE_FILE, EpisodeInfo::class.java)

    fun readChapter(context: Context, directory: DocumentFile): ChapterInfo? =
        read(context, directory, CHAPTER_FILE, ChapterInfo::class.java)

    fun saveImage(context: Context, url: String, directory: DocumentFile, name: String): String? {
        repeat(IMAGE_ATTEMPTS) {
            val bytes = fetchImage(url) ?: return@repeat
            return try {
                val existing = directory.findFile(name)
                if (existing != null && existing.isFile && existing.length() == bytes.size.toLong()) {
                    return existing.uri.toString()
                }
                if (!replaceFile(context, directory, name, "image/jpeg", bytes)) return null
                directory.findFile(name)?.uri?.toString()
            } catch (e: Exception) {
                Logger.log("Failed to save $name: ${e.message}")
                null
            }
        }
        return null
    }

    private fun fetchImage(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val expected = connection.contentLengthLong
            val encoded = connection.contentEncoding != null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return null
            if (!encoded && expected > 0 && bytes.size.toLong() != expected) return null
            if (!isComplete(bytes)) return null
            bytes
        } catch (e: Exception) {
            Logger.log("Failed to fetch image: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun isComplete(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val jpeg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        if (jpeg) {
            var end = b.size
            while (end > 2 && b[end - 1] == 0.toByte()) end--
            return b[end - 2] == 0xFF.toByte() && b[end - 1] == 0xD9.toByte()
        }
        val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()
        if (png) return String(b, b.size - 8, 4, Charsets.ISO_8859_1) == "IEND"
        val webp = b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[8] == 'W'.code.toByte()
        if (webp) {
            val size = (b[4].toInt() and 0xFF) or ((b[5].toInt() and 0xFF) shl 8) or
                ((b[6].toInt() and 0xFF) shl 16) or ((b[7].toInt() and 0xFF) shl 24)
            return size.toLong() + 8 <= b.size
        }
        return true
    }
}
