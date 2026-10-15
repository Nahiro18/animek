package eu.kanade.tachiyomi.animeextension.es.tiodonghua

import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.rumbleextractor.RumbleExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class Tiodonghua :
    DooPlay(
        "es",
        "Tiodonghua.com",
        "https://tiodonghua.lat",
    ) {

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/donghua/page/$page", headers)

    override fun popularAnimeSelector() = "#archive-content article"

    override fun popularAnimeNextPageSelector() = "div.resppages > a > span.fa-chevron-right"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return super.searchAnimeRequest(page, query, filters)
        return GET("$baseUrl/page/$page/?s=${URLEncoder.encode(query, "UTF-8")}", headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a[href]")?.attr("href").orEmpty())
        element.selectFirst("img")?.let {
            title = it.attr("alt")
            thumbnail_url = it.getImageUrl()
        }
    }

    override val latestUpdatesPath = "episodios"

    override fun latestUpdatesSelector() = "#archive-content article"

    override fun getSeasonEpisodes(season: Element): List<SEpisode> {
        val seasonName = season.selectFirst("span.se-t")?.text()?.trim().orEmpty()
        return season.select(episodeListSelector()).mapNotNull { element ->
            runCatching { episodeFromElement(element, seasonName) }.getOrNull()
        }
    }

    override fun episodeFromElement(element: Element, seasonName: String): SEpisode = SEpisode.create().apply {
        val epNum = element.selectFirst("div.numerando")?.text()?.trim()
            ?.let { episodeNumberRegex.find(it)?.groupValues?.last() } ?: "0"
        val link = element.selectFirst("div.episodiotitle a[href]")
        val episodeName = link?.ownText()?.trim().orEmpty()
        setUrlWithoutDomain(link?.attr("href").orEmpty())
        episode_number = epNum.toFloatOrNull() ?: 0F
        date_upload = element.selectFirst("span.date")?.text().let(dateFormat::tryParse)
        name = if (episodeName.isBlank() || episodeName.equals("Episodio $epNum", ignoreCase = true)) {
            "T$seasonName - Episodio $epNum"
        } else {
            "T$seasonName - Episodio $epNum: $episodeName"
        }
    }

    override fun videoListSelector() = "li.dooplay_player_option"

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select(videoListSelector())
            .filter { it.hasAttr("data-post") && it.hasAttr("data-nume") }
            .parallelCatchingFlatMapBlocking { option ->
                val server = option.selectFirst("span.title")?.text()?.trim().orEmpty()
                val embedUrl = resolveEmbedUrl(option, document.location())
                    ?: return@parallelCatchingFlatMapBlocking emptyList()
                extractEmbedVideos(embedUrl, server)
            }
    }

    private suspend fun resolveEmbedUrl(option: Element, referer: String): String? {
        val post = option.attr("data-post")
        val nume = option.attr("data-nume")
        val type = option.attr("data-type").ifBlank { "tv" }
        runCatching {
            client.newCall(GET("$baseUrl/wp-json/dooplayer/v2/$post/$type/$nume", headers))
                .awaitSuccess()
                .bodyString()
                .parseAs<PlayerResponse>()
                .let { extractIframeSrc(it.embed_url) }
                ?.let { return it }
        }
        val form = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", post)
            .add("nume", nume)
            .add("type", type)
            .build()
        val ajaxHeaders = headers.newBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .set("Referer", referer)
            .build()
        val request = Request.Builder()
            .url("$baseUrl/wp-admin/admin-ajax.php")
            .headers(ajaxHeaders)
            .post(form)
            .build()
        return runCatching {
            client.newCall(request)
                .awaitSuccess()
                .bodyString()
                .parseAs<PlayerResponse>()
                .let { extractIframeSrc(it.embed_url) }
        }.getOrNull()
    }

    private fun extractIframeSrc(embedUrl: String): String? {
        val url = embedUrl.trim()
        if (url.startsWith("http", ignoreCase = true)) return url
        val src = iframeSrcRegex.find(url)?.groupValues?.get(1) ?: return null
        return if (src.startsWith("//")) "https:$src" else src
    }

    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val rumbleExtractor by lazy { RumbleExtractor(client, headers) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private suspend fun extractEmbedVideos(url: String, server: String): List<Video> {
        val prefix = if (server.isBlank()) "" else "$server "
        return when {
            "dailymotion.com" in url -> dailymotionExtractor.videosFromUrl(url, prefix = prefix)
            "ok.ru" in url -> okruExtractor.videosFromUrl(url, prefix = prefix)
            "rumble.com" in url -> rumbleExtractor.videosFromUrl(url, prefix = prefix)
            "streamtape.com" in url -> streamtapeExtractor.videoFromUrl(url, "${prefix}StreamTape")?.let(::listOf).orEmpty()
            "filemoon" in url || "moonplayer" in url -> filemoonExtractor.videosFromUrl(url, prefix = prefix)
            else -> runCatching { universalExtractor.videosFromUrl(url, headers, prefix = prefix) }.getOrDefault(emptyList())
        }
    }

    override val fetchGenres = false

    private val dateFormat = SimpleDateFormat("MMM. dd, yyyy", Locale.ENGLISH)

    @Serializable
    data class PlayerResponse(
        val embed_url: String = "",
        val type: String = "",
    )

    companion object {
        private val iframeSrcRegex = """<iframe[^>]+src=["']([^"']+)""".toRegex(RegexOption.IGNORE_CASE)
    }
}
