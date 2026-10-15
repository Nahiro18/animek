package eu.kanade.tachiyomi.animeextension.es.animeflv

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.decodeHexToString
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class AnimeFlv :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "AnimeFLV"

    override val baseUrl = "https://vww.animeflv.one"

    override val lang = "es"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    private val SharedPreferences.userAgent by preferences.delegate(PREF_USER_AGENT, DESKTOP_USER_AGENT)

    override fun headersBuilder() = Headers.Builder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("User-Agent", preferences.userAgent.ifBlank { network.defaultUserAgentProvider() })

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val SERVER_LIST = arrayOf("StreamWish", "YourUpload", "Okru", "Streamtape")

        private const val PREF_USER_AGENT = "preferred_user_agent"
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    }

    override fun popularAnimeSelector(): String = "div.ul.x6 article.li"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes?pag=$page", headers)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("figure.i a")!!
        anime.setUrlWithoutDomain(link.attr("abs:href"))
        anime.title = element.selectFirst("h3.h a")?.text() ?: link.attr("title")
        anime.thumbnail_url = element.selectFirst("figure.i a img")?.let {
            it.attr("abs:data-src").ifBlank { it.attr("abs:src") }
        }
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pag li a[title*=Siguiente]"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val slug = document.selectFirst("*[data-sl]")?.attr("data-sl") ?: return emptyList()
        val rawEps = document.selectFirst("script:containsData(var eps =)")?.data() ?: return emptyList()
        val epsJson = rawEps.substringAfter("var eps =").substringBefore(";").trim()
        return json.decodeFromString<List<List<String>>>(epsJson).map { ep ->
            val number = ep[0]
            val code = ep.getOrNull(2).orEmpty()
            SEpisode.create().apply {
                setUrlWithoutDomain("/ver/$slug-$number${if (code.isNotEmpty()) "-$code" else ""}")
                name = "Episodio $number"
                episode_number = number.toFloatOrNull() ?: 0F
            }
        }
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    /*--------------------------------Video extractors------------------------------------*/
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val opt = document.selectFirst(".opt")?.attr("data-encrypt")?.takeIf { it.isNotBlank() } ?: return emptyList()
        val mediaType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
        val body = "acc=opt&i=$opt".toRequestBody(mediaType)
        val request = Request.Builder()
            .url("$baseUrl/flv")
            .post(body)
            .addHeader("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            .addHeader("referer", document.location())
            .addHeader("x-requested-with", "XMLHttpRequest")
            .build()
        val serversDocument = client.newCall(request).execute().asJsoup()
        return serversDocument.select("li[encrypt]").parallelCatchingFlatMapBlocking {
            serverVideoResolver(it.attr("encrypt").decodeHexToString())
        }
    }

    private suspend fun serverVideoResolver(url: String): List<Video> = when {
        "ok.ru" in url -> okruExtractor.videosFromUrl(url)
        "yourupload" in url -> yourUploadExtractor.videoFromUrl(url, headers = headers)
        "streamtape" in url -> streamTapeExtractor.videosFromUrl(url)
        arrayOf("streamwish", "strwish", "wishembed", "filelion", "fviplions", "dhcplay").any { url.contains(it, ignoreCase = true) } ->
            streamWishExtractor.videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
        else -> universalExtractor.videosFromUrl(url, headers)
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeFlvFilters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> GET("$baseUrl/animes?buscar=$query&pag=$page", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/animes${params.getQuery()}&pag=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFlvFilters.FILTER_LIST

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst(".ti h2 i")?.text()?.trim()
            ?: document.selectFirst(".info-t h1")!!.text().trim()
        anime.thumbnail_url = document.selectFirst(".info-l .i img")?.let {
            it.attr("abs:data-src").ifBlank { it.attr("abs:src") }
        }
        anime.description = document.selectFirst(".info-r .tx p")?.text()
        anime.genre = document.select(".gn a").joinToString { it.text() }
        anime.status = parseStatus(document.selectFirst(".st")?.text().orEmpty())
        return anime
    }

    private fun parseStatus(statusString: String): Int = when {
        statusString.contains("En emision", ignoreCase = true) -> SAnime.ONGOING
        statusString.contains("Finalizado", ignoreCase = true) -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    override fun latestUpdatesRequest(page: Int) = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val episodes = document.select("div.ul.hm").firstOrNull()
            ?.select("article.li")
            .orEmpty()
            .filter { it.selectFirst("a[href*=/ver/]") != null }
            .map(::latestUpdatesFromElement)
        return AnimesPage(episodes, false)
    }

    override fun latestUpdatesNextPageSelector() = null

    override fun latestUpdatesSelector() = "div.ul.hm article.li"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("a[href*=/ver/]")!!
        anime.setUrlWithoutDomain(link.attr("abs:href").replace("/ver/", "/anime/").substringBeforeLast("-"))
        anime.title = element.selectFirst("span")?.text() ?: link.attr("title")
        anime.thumbnail_url = element.selectFirst("img")?.let {
            it.attr("abs:data-src").ifBlank { it.attr("abs:src") }
        }
        return anime
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(server, true) },
                { it.videoTitle.contains(quality) },
                { Regex("""(\d+)p""").find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        screen.addEditTextPreference(
            key = PREF_USER_AGENT,
            title = "User-Agent",
            default = DESKTOP_USER_AGENT,
            summary = "Leave blank to use the default app user agent.",
            restartRequired = true,
        )
    }
}
