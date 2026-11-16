package nox.kiwami.others

import android.annotation.SuppressLint
import android.content.Context
import nox.kiwami.okHttpClient
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import okhttp3.Dispatcher
import java.io.InputStream


@GlideModule
class KiwamiGlideApp : AppGlideModule() {
    @SuppressLint("CheckResult")
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        super.applyOptions(context, builder)
        val diskCacheSizeBytes = SmartCache.glideBudgetBytes()
        builder.apply {
            setDiskCache(InternalCacheDiskCacheFactory(context, "img", diskCacheSizeBytes))
        }
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        val imageClient = okHttpClient.newBuilder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = IMAGE_REQUESTS_PER_HOST })
            .build()
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(imageClient)
        )
        super.registerComponents(context, glide, registry)
    }

    private companion object {
        const val IMAGE_REQUESTS_PER_HOST = 16
    }
}