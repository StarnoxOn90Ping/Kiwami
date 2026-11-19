package nox.kiwami.connections.subtitles

import nox.kiwami.Mapper
import nox.kiwami.okHttpClient
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object OpenSubtitlesRestApi {
    private const val API_KEY = "uyBLgFD17MgrYmA0gSXoKllMJBelOYj2"
    private const val HOST = "https://api.opensubtitles.com/api/v1"
    private const val USER_AGENT = "Cloudstream3 v0.2"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun languageCodes(): String {
        val names = try {
            PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleLanguages)
        } catch (_: Exception) {
            emptySet()
        }
        val codes = names.mapNotNull { name ->
            java.util.Locale.getISOLanguages().firstOrNull {
                java.util.Locale(it).getDisplayLanguage(java.util.Locale.ENGLISH).equals(name, true) ||
                    it.equals(name, true)
            }
        }
        return codes.sorted().joinToString(",")
    }

    private fun buildUrl(params: Map<String, String>): String {
        val query = params.toSortedMap().entries.joinToString("&") {
            "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }
        return "$HOST/subtitles?$query"
    }

    suspend fun search(
        imdbId: String?,
        episode: Int,
        season: Int? = null,
        queryText: String? = null,
        isMovie: Boolean = false
    ): List<OpenSubRestItem> {
        return withContext(Dispatchers.IO) {
            try {
                val cleanImdb = imdbId?.removePrefix("tt")?.trimStart('0')
                val languages = languageCodes()
                val urls = mutableListOf<String>()

                fun base(vararg pairs: Pair<String, String>): MutableMap<String, String> {
                    val map = mutableMapOf(*pairs)
                    if (languages.isNotBlank()) map["languages"] = languages
                    return map
                }

                if (!cleanImdb.isNullOrBlank()) {
                    if (isMovie) {
                        urls.add(buildUrl(base("imdb_id" to cleanImdb)))
                    } else {
                        val s = season ?: 1
                        urls.add(buildUrl(base("parent_imdb_id" to cleanImdb, "season_number" to s.toString(), "episode_number" to episode.toString())))
                        if (s != 1) {
                            urls.add(buildUrl(base("parent_imdb_id" to cleanImdb, "season_number" to "1", "episode_number" to episode.toString())))
                        }
                    }
                }
                if (!queryText.isNullOrBlank()) {
                    val cleanQuery = queryText
                        .replace(Regex("(?i)\\s*episode\\s*\\d+\\s*$"), "")
                        .trim()
                    if (cleanQuery.isNotBlank()) {
                        val params = base("query" to cleanQuery)
                        if (!isMovie) params["episode_number"] = episode.toString()
                        urls.add(buildUrl(params))
                    }
                }

                val results = mutableListOf<OpenSubRestItem>()
                for (url in urls) {
                    try {
                        val req = Request.Builder()
                            .url(url)
                            .addHeader("Api-Key", API_KEY)
                            .addHeader("User-Agent", USER_AGENT)
                            .addHeader("Accept", "application/json")
                            .build()
                        okHttpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                Logger.log("OpenSubtitlesRestApi: HTTP ${resp.code} for $url")
                                return@use
                            }
                            val json = resp.body.string()
                            val parsed = Mapper.json.decodeFromString<OpenSubRestResponse>(json)
                            parsed.data.forEach { item ->
                                val file = item.attributes.files.firstOrNull() ?: return@forEach
                                val fileId = file.fileId ?: return@forEach
                                val fileName = file.fileName ?: item.attributes.release ?: "OpenSubtitles Subtitle"
                                val lang = item.attributes.language ?: "en"
                                results.add(
                                    OpenSubRestItem(
                                        fileId = fileId,
                                        fileName = fileName,
                                        language = lang,
                                        hearingImpaired = item.attributes.hearingImpaired == true
                                    )
                                )
                            }
                        }
                        if (results.isNotEmpty()) break
                    } catch (e: Exception) {
                        Logger.log("OpenSubtitlesRestApi request error: ${e.message}")
                    }
                }
                results.distinctBy { it.fileId }
            } catch (e: Exception) {
                Logger.log("OpenSubtitlesRestApi error: ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun getDownloadUrl(fileId: Int): String? {
        return withContext(Dispatchers.IO) {
            try {
                val bodyStr = """{"file_id":$fileId}"""
                val req = Request.Builder()
                    .url("$HOST/download")
                    .addHeader("Api-Key", API_KEY)
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(bodyStr.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = resp.body.string()
                        val parsed = Mapper.json.decodeFromString<OpenSubDownloadResponse>(json)
                        parsed.link
                    } else null
                }
            } catch (e: Exception) {
                Logger.log("OpenSubtitles download error: ${e.message}")
                null
            }
        }
    }
}

@Serializable
data class OpenSubRestItem(
    val fileId: Int,
    val fileName: String,
    val language: String,
    val hearingImpaired: Boolean = false
)

@Serializable
data class OpenSubRestResponse(
    val data: List<OpenSubData> = emptyList()
)

@Serializable
data class OpenSubData(
    val id: String? = null,
    val attributes: OpenSubAttributes
)

@Serializable
data class OpenSubAttributes(
    val language: String? = null,
    val release: String? = null,
    val files: List<OpenSubFile> = emptyList(),
    @SerialName("hearing_impaired") val hearingImpaired: Boolean? = null
)

@Serializable
data class OpenSubFile(
    @SerialName("file_id") val fileId: Int? = null,
    @SerialName("file_name") val fileName: String? = null
)

@Serializable
data class OpenSubDownloadResponse(
    val link: String? = null,
    @SerialName("file_name") val fileName: String? = null
)
