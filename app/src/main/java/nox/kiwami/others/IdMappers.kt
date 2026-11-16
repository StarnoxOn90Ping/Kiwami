package nox.kiwami.others

import nox.kiwami.client
import nox.kiwami.Mapper
import nox.kiwami.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

object IdMappers {

    private const val MAPPER_API_URL = "https://Isagi2025-idmapper.hf.space/api/mapper"

    private val cache = HashMap<Int, AnimeId>()

    suspend fun getIds(anilistId: Int): AnimeId? {
        if (cache.containsKey(anilistId)) {
            return cache[anilistId]
        }

        return withContext(Dispatchers.IO) {
            try {
                val response = client.get("$MAPPER_API_URL?anilist_id=$anilistId")

                val data = Mapper.json.decodeFromString<AnimeId>(response.text)

                if (data.anilistId != null) {
                    cache[data.anilistId] = data
                }

                data
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }


    suspend fun getSimklId(anilistId: Int): Int? {
        return getIds(anilistId)?.simklId
    }

    suspend fun getImdbId(anilistId: Int): String? {
        val mainId = getIds(anilistId)?.imdbId
        if (mainId != null) return mainId

        return getAniZipId(anilistId = anilistId)
    }

    suspend fun getMalId(anilistId: Int): Int? {
        return getIds(anilistId)?.malId
    }

    private suspend fun getAniZipId(anilistId: Int? = null, malId: Int? = null): String? {
        val url = when {
            anilistId != null && anilistId != 0 -> "https://api.ani.zip/mappings?anilist_id=$anilistId"
            malId != null && malId != 0 -> "https://api.ani.zip/mappings?mal_id=$malId"
            else -> return null
        }
        return withContext(Dispatchers.IO) {
            try {
                val response = client.get(url)
                val payload = response.text
                if (payload.isBlank()) {
                    Logger.log("AniZip : empty mapping response for $url")
                    return@withContext null
                }
                val jsonElement = Mapper.json.parseToJsonElement(payload)
                if (jsonElement !is JsonObject) {
                    Logger.log("AniZip : unexpected mapping payload type for $url")
                    return@withContext null
                }
                val data = Mapper.json.decodeFromJsonElement<AniZipResponse>(jsonElement)
                data.mappings.values.firstOrNull()?.imdbId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

}

@Serializable
data class AnimeId(
    @SerialName("anilist_id") val anilistId: Int? = null,
    @SerialName("mal_id") val malIdElement: JsonElement? = null,
    @SerialName("simkl_id") val simklId: Int? = null,
    @SerialName("themoviedb_id") val tmdbId: Int? = null,
    @SerialName("imdb_id") val imdbIdElement: JsonElement? = null,
    @SerialName("kitsu_id") val kitsuId: Int? = null,
    @SerialName("anidb_id") val anidbId: Int? = null,
    @SerialName("anime-planet_id") val animePlanetId: String? = null,
    @SerialName("animenewsnetwork_id") val annId: Int? = null,
    @SerialName("anisearch_id") val anisearchId: Int? = null,
    @SerialName("livechart_id") val livechartId: Int? = null,
    @SerialName("tvdb_id") val tvdbId: Int? = null,
    @SerialName("animecountdown_id") val animeCountdownId: Int? = null,
    @SerialName("tmdb_mappings") val tmdbMappings: Map<String, String>? = null,
    @SerialName("tvdb_mappings") val tvdbMappings: Map<String, String>? = null
) {
    val malId: Int?
        get() = when (val el = malIdElement) {
            is JsonPrimitive -> el.intOrNull
            is JsonArray -> el.firstOrNull()?.jsonPrimitive?.intOrNull
            else -> null
        }
    val imdbId: String?
        get() = when (val el = imdbIdElement) {
            is JsonPrimitive -> el.contentOrNull
            is JsonArray -> el.firstOrNull()?.jsonPrimitive?.contentOrNull
            else -> null
        }
}

@Serializable
data class AniZipResponse(
    val mappings: Map<String, AniZipMapping> = emptyMap()
)

@Serializable
data class AniZipMapping(
    @SerialName("imdb_id") val imdbId: String? = null
)
