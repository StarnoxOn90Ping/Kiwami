package nox.kiwami.media.anime

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import nox.kiwami.R
import androidx.documentfile.provider.DocumentFile
import nox.kiwami.download.DownloadsManager
import nox.kiwami.download.video.DrmDownloader
import nox.kiwami.media.Media
import nox.kiwami.media.MediaType
import nox.kiwami.parsers.Subtitle
import nox.kiwami.parsers.Video
import nox.kiwami.parsers.VideoServer
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.toast
import nox.kiwami.util.Logger

/**
 * Third party video players Kiwami can hand a stream over to.
 * [id] is what gets saved in [PrefName.ExternalPlayer], so never change an existing id.
 * 0 is reserved for "off" (the built in player).
 */
enum class ExternalPlayer(
    val id: Int,
    val displayName: String,
    val packageName: String,
    val activityName: String
) {
    VLC(1, "VLC", "org.videolan.vlc", "org.videolan.vlc.gui.video.VideoPlayerActivity");

    companion object {
        const val OFF = 0

        fun fromId(id: Int): ExternalPlayer? = entries.firstOrNull { it.id == id }
    }
}

object ExternalPlayerLauncher {

    /** The player picked in Settings > Anime > External player, or null when the built in player is used. */
    fun selected(): ExternalPlayer? =
        ExternalPlayer.fromId(PrefManager.getVal(PrefName.ExternalPlayer))

    fun isInstalled(context: Context, player: ExternalPlayer): Boolean = try {
        context.packageManager.getPackageInfo(player.packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Opens [video] (or, for a downloaded episode, the downloaded file) in the selected external player.
     *
     * Returns true when the external player was started, so the caller must not start the built in
     * player. Returns false when nothing was launched (no external player selected, an encrypted
     * download the other app cannot decrypt, or the player is not installed), in which case the
     * caller carries on with the built in player.
     */
    fun launch(
        context: Context,
        media: Media,
        episode: Episode,
        video: Video,
        server: VideoServer,
        subtitles: List<Subtitle>
    ): Boolean {
        val player = selected() ?: return false

        // Where the video comes from: a stream url, or a downloaded file in the downloads folder
        val videoUri: Uri
        var subtitleUri: String? = null
        var headers: Map<String, String> = emptyMap()
        if (server.offline) {
            val files = findDownloadedFiles(context, server) ?: return false
            videoUri = files.first.uri
            subtitleUri = files.second?.uri?.toString()
        } else {
            val url = video.file.url
            if (!url.startsWith("http://") && !url.startsWith("https://")) return false
            videoUri = Uri.parse(url)
            headers = video.file.headers
            val subtitle = subtitles.firstOrNull { it.language.contains("english", ignoreCase = true) }
                ?: subtitles.firstOrNull()
            subtitleUri = subtitle?.file?.url?.takeIf { it.startsWith("http") }
        }

        if (!isInstalled(context, player)) {
            toast(context.getString(R.string.external_player_not_installed, player.displayName))
            return false
        }

        val episodeName = episode.title?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.episode_num, episode.number)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(player.packageName, player.activityName)
            setDataAndType(videoUri, "video/*")
            putExtra("title", "${media.userPreferredName} - $episodeName")
            if (headers.isNotEmpty()) {
                // Alternating key, value pairs
                putExtra("headers", headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
            }
            subtitleUri?.let { putExtra("subtitles_location", it) }
            if (server.offline) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (server.offline) {
            // The downloads folder belongs to Kiwami, so give the player read access to the files
            runCatching {
                context.grantUriPermission(player.packageName, videoUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                subtitleUri?.let {
                    context.grantUriPermission(player.packageName, Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }

        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Logger.log(e)
            toast(context.getString(R.string.external_player_not_installed, player.displayName))
            false
        } catch (e: SecurityException) {
            Logger.log(e)
            toast(context.getString(R.string.external_player_failed, player.displayName))
            false
        }
    }

    /**
     * The video file (and subtitle file, if there is one) of a downloaded episode, found the same
     * way the built in player finds them. Null when the files are missing or the download is
     * encrypted (DRM), which only the built in player can play.
     */
    private fun findDownloadedFiles(context: Context, server: VideoServer): Pair<DocumentFile, DocumentFile?>? {
        val parts = server.name.split("/")
        val directory = DownloadsManager.findSubDirectory(
            context, MediaType.ANIME, parts.first(), parts.last()
        ) ?: return null
        if (DrmDownloader.offlineMediaItem(context, directory) != null) return null

        val files = directory.listFiles().filter { it.isFile && !it.name.orEmpty().startsWith(".") }
        val videos = files.filter { !it.name.orEmpty().contains("subtitle", ignoreCase = true) }
        val video = videos.firstOrNull {
            val name = it.name.orEmpty()
            name.endsWith(".mp4", true) || name.endsWith(".mkv", true) ||
                name.endsWith(".webm", true) || name.endsWith(".ts", true) ||
                it.type?.startsWith("video/") == true
        } ?: videos.firstOrNull() ?: return null
        val subtitle = files.firstOrNull { it.name.orEmpty().contains("subtitle", ignoreCase = true) }
        return video to subtitle
    }
}
