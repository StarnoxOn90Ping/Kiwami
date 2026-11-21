package nox.kiwami.calendar

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nox.kiwami.client
import nox.kiwami.media.Media
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

object MangaReleaseSource {

    class Guess(val nextMs: Long, val intervalDays: Int, val nextChapter: Double?)

    class Outcome(val guess: Guess?, val permanent: Boolean, val reason: String?)

    private const val BASE = "https://api.mangadex.org"
    private const val RATINGS = "&contentRating%5B%5D=safe&contentRating%5B%5D=suggestive" +
            "&contentRating%5B%5D=erotica&contentRating%5B%5D=pornographic"
    private const val MIN_RELEASES = 3
    private const val MIN_PATIENCE_DAYS = 45L
    private const val MAX_PATIENCE_DAYS = 150L
    private val headers = mapOf("User-Agent" to "Kiwami/1.0 (release calendar)")

    private class Failure(val reason: String) : Exception(reason)

    suspend fun predict(media: Media): Outcome = withContext(Dispatchers.IO) {
        try {
            val id = findId(media)
                ?: return@withContext Outcome(null, true, "series not found")
            val days = releaseDays(id)
            if (days.isEmpty()) return@withContext Outcome(null, true, "no English chapters")
            val today = LocalDate.now().toEpochDay()
            val distinct = days.map { it.day }.distinct().sortedDescending()
            if (distinct.size < MIN_RELEASES) {
                return@withContext Outcome(null, true, "too little release history")
            }
            val sample = distinct.take(10)
            val gaps = sample.zipWithNext { newer, older -> (newer - older).toInt() }.sorted()
            val interval = gaps[gaps.size / 2].coerceIn(1, 60)
            val patience = (interval * 3L).coerceIn(MIN_PATIENCE_DAYS, MAX_PATIENCE_DAYS)
            if (today - distinct.first() > patience) {
                return@withContext Outcome(null, true, "no recent chapters")
            }
            val zone = ZoneId.systemDefault()
            val next = LocalDate.ofEpochDay(distinct.first() + interval)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val latest = days.mapNotNull { it.chapter }.maxOrNull()
            Outcome(Guess(next, interval, latest?.let { Math.floor(it) + 1.0 }), false, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Failure) {
            Outcome(null, false, e.reason)
        } catch (e: Exception) {
            Outcome(null, false, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    private class Release(val day: Long, val chapter: Double?)

    private suspend fun getJson(url: String): JSONObject {
        val response = client.get(url, headers = headers)
        if (response.code == 429) throw Failure("rate limited")
        if (response.code !in 200..299) throw Failure("HTTP ${response.code}")
        return JSONObject(response.text)
    }

    private suspend fun findId(media: Media): String? {
        val titles = listOf(media.nameRomaji, media.name, media.userPreferredName)
            .filterNotNull()
            .filter { it.isNotBlank() }
            .distinct()
            .take(3)
        val anilist = media.id.toString()
        val mal = media.idMAL?.toString()
        for (title in titles) {
            val url = "$BASE/manga?title=${Uri.encode(title)}&limit=10$RATINGS"
            val data = getJson(url).optJSONArray("data") ?: continue
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val links = item.optJSONObject("attributes")?.optJSONObject("links") ?: continue
                if (links.optString("al") == anilist || (mal != null && links.optString("mal") == mal)) {
                    return item.getString("id")
                }
            }
        }
        return null
    }

    private suspend fun releaseDays(mangaId: String): List<Release> {
        val url = "$BASE/manga/$mangaId/feed?translatedLanguage%5B%5D=en" +
                "&order%5BpublishAt%5D=desc&limit=100&includeExternalUrl=1$RATINGS"
        val data = getJson(url).optJSONArray("data") ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val out = mutableListOf<Release>()
        for (i in 0 until data.length()) {
            val attributes = data.getJSONObject(i).optJSONObject("attributes") ?: continue
            val published = attributes.optString("publishAt")
            if (published.isNullOrBlank()) continue
            val day = try {
                OffsetDateTime.parse(published).atZoneSameInstant(zone).toLocalDate().toEpochDay()
            } catch (e: Exception) {
                continue
            }
            val chapter = attributes.optString("chapter").toDoubleOrNull()
            out.add(Release(day, chapter))
        }
        return out.sortedByDescending { it.day }
    }
}
