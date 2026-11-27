package nox.kiwami.others

import android.content.Context
import com.bumptech.glide.Glide
import nox.kiwami.connections.anilist.Anilist
import nox.kiwami.connections.anilist.MediaDetailsCache
import nox.kiwami.connections.anilist.QuickLoadHome
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import java.io.File

object SmartCache {
    const val MIN_MB = 200
    const val MAX_MB = 1000
    const val STEP_MB = 50

    val HOME_ROW_KEYS = listOf(
        "currentAnime",
        "currentManga",
        "currentAnimePlanned",
        "currentMangaPlanned",
        "favoriteAnime",
        "favoriteManga"
    )

    private val DEFAULT_LIBRARY_KEYS = listOf(
        "currentAnime",
        "currentAnimePlanned",
        "currentManga",
        "currentMangaPlanned"
    )

    private const val DEFAULT_GLIDE_BYTES = 100L * 1024 * 1024
    private const val HOUR_MS = 60L * 60 * 1000

    private fun flag(name: PrefName, default: Boolean): Boolean =
        runCatching { PrefManager.getVal<Boolean>(name) }.getOrDefault(default)

    fun isEnabled(): Boolean = flag(PrefName.SmartCacheEnabled, false)

    fun homeEnabled(): Boolean = isEnabled() && flag(PrefName.QuickLoadHome, true)

    fun mediaEnabled(): Boolean = isEnabled() && flag(PrefName.QuickLoadMedia, true)

    fun imagesEnabled(): Boolean = isEnabled() && flag(PrefName.QuickLoadImages, true)

    fun pinsEnabled(): Boolean = imagesEnabled() && flag(PrefName.QuickLoadKeepLibraryImages, true)

    fun pinOnWifiOnly(): Boolean = flag(PrefName.QuickLoadPinOnWifiOnly, false)

    fun protectApi(): Boolean = isEnabled() && flag(PrefName.QuickLoadProtectApi, true)

    fun maxAgeMs(): Long =
        runCatching { PrefManager.getVal<Int>(PrefName.QuickLoadMaxAgeHours) }
            .getOrDefault(6).coerceIn(1, 48) * HOUR_MS

    fun homeRowKeys(): Set<String> {
        val flags = runCatching { PrefManager.getVal<List<Boolean>>(PrefName.QuickLoadHomeRows) }
            .getOrDefault(emptyList())
        return HOME_ROW_KEYS.filterIndexed { i, _ -> flags.getOrElse(i) { true } }.toSet()
    }

    fun libraryRowKeys(): List<String> =
        if (homeEnabled()) HOME_ROW_KEYS.filter { it in homeRowKeys() } else DEFAULT_LIBRARY_KEYS

    fun shouldSkipRefresh(): Boolean = protectApi() && Anilist.lowOnRequests()

    fun limitMb(): Int =
        runCatching { PrefManager.getVal<Int>(PrefName.SmartCacheLimitMb) }
            .getOrDefault(500).coerceIn(MIN_MB, MAX_MB)

    private fun limitBytes(): Long = limitMb() * 1024L * 1024L

    fun pinnedBudgetBytes(): Long = (limitBytes() * 0.3).toLong()

    fun glideBudgetBytes(): Long =
        if (imagesEnabled()) (limitBytes() * 0.7).toLong() else DEFAULT_GLIDE_BYTES

    fun sizeOf(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun pinnedBytes(context: Context) = sizeOf(File(context.cacheDir, "pinned_images"))
    fun glideBytes(context: Context) = sizeOf(File(context.cacheDir, "img"))
    fun infoBytes(context: Context) = sizeOf(File(context.cacheDir, "media_details"))
    fun homeBytes(context: Context) = sizeOf(File(context.cacheDir, "quick_load"))
    fun imageBytes(context: Context) = pinnedBytes(context) + glideBytes(context)

    fun clearHome() {
        QuickLoadHome.clear()
    }

    fun clearMedia() {
        MediaDetailsCache.clear()
    }

    fun clearImages(context: Context) {
        PinnedImages.clear()
        runCatching { Glide.get(context).clearDiskCache() }
    }

    fun clearAll(context: Context) {
        clearHome()
        clearMedia()
        clearImages(context)
    }

    fun onEnabled() = PinnedImages.requestSync()
    fun onLimitChanged() = PinnedImages.requestSync()
}
