package nox.kiwami.others

import android.content.Context
import android.net.ConnectivityManager
import android.util.LruCache
import nox.kiwami.currContext
import nox.kiwami.media.Media
import nox.kiwami.okHttpClient
import nox.kiwami.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

object PinnedImages {
    private const val LIB = "L_"

    @Volatile private var libraryUrls: Set<String> = emptySet()

    private val running = AtomicBoolean(false)
    @Volatile private var pending = false

    private fun dir(): File? {
        val base = currContext()?.cacheDir ?: return null
        return File(base, "pinned_images").apply { mkdirs() }
    }

    private const val HEX = "0123456789abcdef"
    private val hashes = LruCache<String, String>(2048)
    @Volatile private var lookupBase: File? = null

    private fun lookupDir(): File? {
        lookupBase?.let { return it }
        val base = currContext()?.cacheDir ?: return null
        return File(base, "pinned_images").also { lookupBase = it }
    }

    private fun hash(url: String): String {
        hashes.get(url)?.let { return it }
        val bytes = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(HEX[v shr 4]).append(HEX[v and 0x0f])
        }
        return out.toString().also { hashes.put(url, it) }
    }

    fun fileFor(url: String): File? {
        if (!SmartCache.pinsEnabled()) return null
        val d = lookupDir() ?: return null
        return File(d, LIB + hash(url)).takeIf { it.length() > 0 }
    }

    private fun urlsOf(list: Collection<Media>?): Set<String> =
        list.orEmpty().flatMap { listOf(it.cover, it.banner) }
            .filter { !it.isNullOrBlank() && it.startsWith("http") }
            .map { it!! }.toSet()

    fun setLibrary(lists: List<Collection<Media>?>) {
        libraryUrls = lists.flatMap { urlsOf(it) }.toSet()
        sync()
    }

    fun requestSync() = sync()

    fun clear() {
        runCatching { dir()?.listFiles()?.forEach { it.delete() } }
    }

    private fun onMeteredNetwork(): Boolean {
        val manager = currContext()?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return manager?.isActiveNetworkMetered ?: false
    }

    private fun sync() {
        if (!SmartCache.pinsEnabled()) return
        pending = true
        if (!running.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                while (pending) {
                    pending = false
                    runCatching { doSync() }.onFailure { Logger.log(it) }
                }
            } finally {
                running.set(false)
            }
        }
    }

    private fun doSync() {
        val d = dir() ?: return
        val wanted = libraryUrls.associateBy { hash(it) }

        d.listFiles()?.forEach { f ->
            if (!f.name.startsWith(LIB) || f.name.removePrefix(LIB) !in wanted) f.delete()
        }

        fun total() = d.listFiles()?.sumOf { it.length() } ?: 0L
        val maxBytes = SmartCache.pinnedBudgetBytes()

        if (total() > maxBytes) {
            d.listFiles()
                ?.sortedBy { it.lastModified() }
                ?.forEach { if (total() > maxBytes) it.delete() }
        }

        if (SmartCache.pinOnWifiOnly() && onMeteredNetwork()) return

        for ((h, url) in wanted) {
            if (File(d, LIB + h).exists()) continue
            if (total() >= maxBytes) return
            download(url, File(d, LIB + h))
        }
    }

    private fun download(url: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return
                resp.body.byteStream().use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
            }
            if (tmp.length() > 0) tmp.renameTo(target)
        } catch (e: Exception) {
            Logger.log(e)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
