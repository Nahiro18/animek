package eu.kanade.tachiyomi.animeextension.es.animefenix

import aniyomi.lib.megaextractor.MegaExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeFenix : AnimeHttpLegacySource() {

    override val name = "AnimeFenix"

    override val baseUrl = "https://tvanime.tv"

    override val lang = "es"

    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/directorio?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("article.anime-card").map { popularAnimeFromElement(it) }
        val hasNextPage = document.select("nav[aria-label*=Paginación] a").any { it.text().trim() == "›" }
        return AnimesPage(animeList, hasNextPage)
    }

    private fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val link = element.selectFirst("a.anime-card-image") ?: element.selectFirst("h2.anime-card-title a")
        setUrlWithoutDomain(link?.attr("abs:href").orEmpty())
        title = element.selectFirst("h2.anime-card-title a")?.text().orEmpty()
        thumbnail_url = element.selectFirst("img")?.attr("abs:src").orEmpty()
    }

    override fun latestUpdatesRequest(page: Int) = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val seen = mutableSetOf<String>()
        val animeList = document.select("a.home-episode-card[href]").mapNotNull { card ->
            val animeUrl = card.attr("abs:href").substringBefore("/episodio/")
            if (animeUrl.isBlank() || !seen.add(animeUrl)) return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(animeUrl)
                title = card.selectFirst("strong.home-episode-anime")?.text().orEmpty()
                thumbnail_url = card.selectFirst("img")?.attr("abs:src").orEmpty()
            }
        }
        return AnimesPage(animeList, false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genre = filterList.filterIsInstance<AnimeFenixFilters.UriPartFilter>().firstOrNull()?.toUriPart().orEmpty()
        return when {
            query.isNotBlank() -> GET("$baseUrl/directorio?q=${URLEncoder.encode(query, "UTF-8")}&page=$page", headers)
            genre.isNotBlank() -> GET("$baseUrl/genero/$genre?page=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1.anime-title")?.text()?.trim().orEmpty()
            description = document.selectFirst("p.anime-synopsis")?.text()?.trim()
            genre = document.select("div.anime-genres a").joinToString { it.text().trim() }
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("article img")?.attr("abs:src")
            status = SAnime.UNKNOWN
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select("a.episode-card[href]").mapNotNull { card ->
            val href = card.attr("href")
            val num = href.substringAfterLast("episodio-").substringBefore("/").toFloatOrNull()
                ?: return@mapNotNull null
            SEpisode.create().apply {
                episode_number = num
                name = "Episodio ${num.toString().removeSuffix(".0")}"
                setUrlWithoutDomain(card.attr("abs:href"))
            }
        }.sortedBy { it.episode_number }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select("[data-server-url]").mapNotNull { button ->
            val url = button.attr("data-server-url")
            if (url.isBlank()) return@mapNotNull null
            val server = button.attr("data-server-name").takeIf(String::isNotBlank) ?: url
            server to url
        }.parallelCatchingFlatMapBlocking { (server, url) ->
            serverVideoResolver(url, server)
        }
    }

    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val megaExtractor by lazy { MegaExtractor(client, headers) }
    private val unsExtractor by lazy { UnsExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private suspend fun serverVideoResolver(url: String, server: String = ""): List<Video> {
        val target = (server + " " + url).lowercase()
        return runCatching {
            when {
                "voe" in target -> voeExtractor.videosFromUrl(url)
                "mp4upload" in target -> mp4uploadExtractor.videosFromUrl(url, headers)
                "mega" in target -> megaExtractor.videosFromUrl(url, prefix = "Mega:")
                "uns.bio" in target || "upnshare" in target -> unsExtractor.videosFromUrl(url)
                else -> universalExtractor.videosFromUrl(url, headers)
            }
        }.getOrDefault(emptyList())
    }

    override fun getFilterList(): AnimeFilterList = AnimeFenixFilters.FILTER_LIST
}
