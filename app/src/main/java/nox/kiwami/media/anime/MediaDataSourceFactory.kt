package nox.kiwami.media.anime

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import nox.kiwami.util.Logger
import okhttp3.OkHttpClient
import org.chromium.net.CronetProvider
import java.util.concurrent.Executors

@UnstableApi
object MediaDataSourceFactory {

    fun resolveHttpFactory(
        context: Context,
        headers: Map<String, String>,
        okHttpClient: OkHttpClient,
        isLocalhost: Boolean = false,
    ): HttpDataSource.Factory {
        if (isLocalhost) {
            Logger.log("DataSource: localhost stream — using OkHttp (HTTP/2, QUIC bypassed)")
            return buildOkHttpFactory(okHttpClient, headers)
        }

        tryCronetProvider(context, headers)?.let { return it }

        Logger.log("DataSource: using OkHttp (HTTP/2)")
        return buildOkHttpFactory(okHttpClient, headers)
    }

    /**
     * Attempts to build a [CronetDataSource.Factory] via the GMS Cronet provider.
     *
     * Catches [Throwable] (not just [Exception]) because on F-Droid builds the
     * [CronetProvider] class is absent → [NoClassDefFoundError] (a [LinkageError]).
     */
    private fun tryCronetProvider(
        context: Context,
        headers: Map<String, String>,
    ): HttpDataSource.Factory? = runCatching {
        val providers = CronetProvider.getAllProviders(context)
        val provider = providers.firstOrNull { p ->
            p.isEnabled && p.name != CronetProvider.PROVIDER_NAME_FALLBACK
        } ?: run {
            Logger.log("DataSource: no active GMS Cronet provider found")
            return@runCatching null
        }
        val engine = provider.createBuilder()
            .enableQuic(true)
            .enableHttp2(true)
            .build()
        CronetDataSource.Factory(engine, Executors.newCachedThreadPool())
            .apply { setDefaultRequestProperties(headers) }
            .also { Logger.log("DataSource: using GMS Cronet '${provider.name}' (HTTP/3 + HTTP/2)") }
    }.getOrElse { e ->
        // NoClassDefFoundError on F-Droid, or runtime failure on GMS device — safe to swallow.
        Logger.log("DataSource: GMS Cronet unavailable — ${e.message}")
        null
    }

    fun buildOkHttpFactory(
        okHttpClient: OkHttpClient,
        headers: Map<String, String>,
    ): OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(okHttpClient).apply {
            setDefaultRequestProperties(headers)
            headers["User-Agent"]?.let { setUserAgent(it) }
        }
}
