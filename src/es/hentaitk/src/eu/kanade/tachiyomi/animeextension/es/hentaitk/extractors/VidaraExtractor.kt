package eu.kanade.tachiyomi.animeextension.es.hentaitk.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class VidaraExtractor(private val client: OkHttpClient) {

    suspend fun videosFromUrl(url: String): List<Video> {
        val filecode = url.substringAfter("/e/").substringBefore("?").substringBefore("#")
        if (filecode.isBlank()) return emptyList()
        val origin = "https://" + url.substringAfter("https://").substringBefore("/")
        val headers = Headers.Builder().add("Referer", url).build()
        val body = """{"filecode":"$filecode","device":"web","codecs":[]}"""
            .toRequestBody("application/json".toMediaType())
        val res = client.newCall(POST("$origin/api/stream", headers, body))
            .execute().parseAs<VidaraResponse>()
        val streamUrl = res.streamingUrl?.takeIf(String::isNotBlank) ?: return emptyList()
        return listOf(Video(streamUrl, "Vidara", streamUrl))
    }

    @Serializable
    class VidaraResponse(@SerialName("streaming_url") val streamingUrl: String? = null)
}
