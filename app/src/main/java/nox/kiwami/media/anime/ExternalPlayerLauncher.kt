package nox.kiwami.media.anime

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import nox.kiwami.R
import nox.kiwami.media.Media
import nox.kiwami.parsers.Subtitle
import nox.kiwami.parsers.Video
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
     * Opens [video] in the selected external player.
     *
     * Returns true when the external player was started, so the caller must not start the built in
     * player. Returns false when nothing was launched (no external player selected, a local or
     * offline file the other app cannot read, or the player is not installed), in which case the
     * caller carries on with the built in player.
     */
    fun launch(
        context: Context,
        media: Media,
        episode: Episode,
        video: Video,
        subtitles: List<Subtitle>
    ): Boolean {
        val player = selected() ?: return false
        val url = video.file.url
        // Downloaded episodes live in Kiwami's own storage, which other apps cannot open
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false

        if (!isInstalled(context, player)) {
            toast(context.getString(R.string.external_player_not_installed, player.displayName))
            return false
        }

        val episodeName = episode.title?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.episode_num, episode.number)
        val subtitle = subtitles.firstOrNull { it.language.contains("english", ignoreCase = true) }
            ?: subtitles.firstOrNull()

        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(player.packageName, player.activityName)
            setDataAndType(Uri.parse(url), "video/*")
            putExtra("title", "${media.userPreferredName} - $episodeName")
            if (video.file.headers.isNotEmpty()) {
                // Alternating key, value pairs
                putExtra(
                    "headers",
                    video.file.headers.flatMap { listOf(it.key, it.value) }.toTypedArray()
                )
            }
            subtitle?.file?.url?.takeIf { it.startsWith("http") }?.let {
                putExtra("subtitles_location", it)
            }
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
}
