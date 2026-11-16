package nox.kiwami.connections.discord

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import nox.kiwami.plainHttpClient

object DiscordHttpClient {
    val instance: OkHttpClient by lazy {
        plainHttpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
