package eu.kanade.tachiyomi.animeextension.es.hentaijk

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
data class HentaijkAnime(
    val title: String = "",
    val synopsis: String? = null,
    val image: String? = null,
    val slug: String? = null,
    val url: String? = null,
)

@Serializable
data class HentaijkAnimePage(
    @SerialName("current_page") val currentPage: Int = 1,
    @SerialName("last_page") val lastPage: Int = 1,
    @SerialName("next_page_url") val nextPageUrl: String? = null,
    val data: List<HentaijkAnime> = emptyList(),
)

@Serializable
data class HentaijkEpisode(
    val title: String? = null,
    val number: Double = 0.0,
    val timestamp: String? = null,
)

@Serializable
data class HentaijkEpisodePage(
    @SerialName("current_page") val currentPage: Int = 1,
    @SerialName("last_page") val lastPage: Int = 1,
    @SerialName("next_page_url") val nextPageUrl: String? = null,
    val data: List<HentaijkEpisode> = emptyList(),
)

class Hentaijk :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Hentaijk"

    override val baseUrl = "https://hentaijk.com"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/top/", headers)

    override fun popularAnimeSelector(): String = "div.col.toplist.mb-4"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href]")
        setUrlWithoutDomain(link?.attr("href").orEmpty())
        title = element.selectFirst("h5.card-title")?.text().orEmpty()
        thumbnail_url = element.selectFirst("img.card-img-top")?.attr("src")
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination a[rel=next]"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/directorio/1?p=$page", headers)

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return response.bodyString().toAnimePage()
    }

    private fun String.toAnimePage(): AnimesPage {
        val raw = substringAfter("var animes = ").substringBefore("};") + "}"
        val page = raw.parseAs<HentaijkAnimePage>(json)
        val animes = page.data.map { it.toSAnime() }
        return AnimesPage(animes, page.currentPage < page.lastPage)
    }

    private fun HentaijkAnime.toSAnime(): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(url ?: slug?.let { "$baseUrl/$it/" }.orEmpty())
        title = this@toSAnime.title
        thumbnail_url = image
        description = synopsis
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> {
                val url = baseUrl.toHttpUrl().newBuilder().apply {
                    addPathSegment("buscar")
                    addQueryParameter("q", query)
                }.build()
                GET(url, headers)
            }
            genreFilter.state != 0 -> GET("$baseUrl/genero/${genreFilter.toUriPart()}/?p=$page", headers)
            else -> GET("$baseUrl/directorio/1?p=$page", headers)
        }
    }

    override fun searchAnimeSelector(): String = "div.anime__item, div.dir1 div.card"

    override fun searchAnimeFromElement(element: Element): SAnime = searchAnimeFromCard(element)

    override fun searchAnimeNextPageSelector(): String = "ul.pagination a[rel=next]"

    override fun searchAnimeParse(response: Response): AnimesPage {
        return if (response.request.url.encodedPath.startsWith("/directorio")) {
            response.bodyString().toAnimePage()
        } else {
            val document = response.asJsoup()
            val animes = document.select(searchAnimeSelector()).map(::searchAnimeFromCard)
            val hasNextPage = document.selectFirst(searchAnimeNextPageSelector()) != null
            AnimesPage(animes, hasNextPage)
        }
    }

    private fun searchAnimeFromCard(element: Element): SAnime = SAnime.create().apply {
        if (element.hasClass("anime__item")) {
            val link = element.selectFirst("div.anime__item__text h5 a")
            setUrlWithoutDomain(link?.attr("href").orEmpty())
            title = link?.text().orEmpty()
            thumbnail_url = element.selectFirst("div.anime__item__pic")?.attr("data-setbg")
            status = parseStatus(element.selectFirst("div.anime__item__text ul li")?.text().orEmpty())
            genre = element.select("div.anime__item__text ul li").joinToString { it.text() }
        } else {
            val link = element.selectFirst("a[href]")
            setUrlWithoutDomain(link?.attr("href").orEmpty())
            title = element.selectFirst("h5.card-title")?.text().orEmpty()
            thumbnail_url = element.selectFirst("img.card-img-top")?.attr("src")
            status = parseStatus(element.select("span.badge").joinToString { it.text() })
        }
    }

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div.anime_pic img")?.attr("src")
        title = document.selectFirst("div.anime_info h3")?.text().orEmpty()
        description = document.selectFirst("div.anime_info p.scroll")?.text()
        document.select("div.card.anime_data ul li").forEach { item ->
            val label = item.selectFirst("span")?.text().orEmpty().lowercase()
            when {
                "genero" in label -> genre = item.select("a").joinToString { it.text() }
                "estado" in label -> status = parseStatus(item.text())
                "studio" in label -> author = item.select("a").text().takeIf { it.isNotBlank() }
            }
        }
    }

    private fun parseStatus(statusString: String): Int {
        val normalized = statusString.lowercase()
        return when {
            "concluido" in normalized || "finalizado" in normalized -> SAnime.COMPLETED
            "emisi" in normalized || "estren" in normalized -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeUrl = response.request.url.toString().trimEnd('/')
        val document = response.asJsoup()
        val animeId = document.selectFirst("div.guardar_anime[data-anime]")
            ?.attr("data-anime")
            ?.takeIf { it.isNotBlank() } ?: return emptyList()
        val token = document.selectFirst("meta[name=csrf-token]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() } ?: return emptyList()
        val formData = FormBody.Builder().add("_token", token).build()
        val cookieHeaders = headers.newBuilder().apply {
            val cookies = response.headers.filter { it.first == "set-cookie" }
                .joinToString(" ") { "${it.second.substringBefore(";")};" }
            if (cookies.isNotBlank()) add("Cookie", cookies)
        }.build()

        val episodes = mutableListOf<SEpisode>()
        val firstPage = fetchEpisodes(animeId, 1, cookieHeaders, formData)
        firstPage.data.toEpisodeList(animeUrl).let(episodes::addAll)
        for (page in 2..firstPage.lastPage) {
            runCatching { fetchEpisodes(animeId, page, cookieHeaders, formData) }
                .getOrNull()?.data?.toEpisodeList(animeUrl)?.let(episodes::addAll)
        }
        return episodes.reversed()
    }

    private fun fetchEpisodes(
        animeId: String,
        page: Int,
        cookieHeaders: Headers,
        formData: FormBody,
    ): HentaijkEpisodePage = client.newCall(
        POST("$baseUrl/ajax/episodes/$animeId/$page", headers = cookieHeaders, body = formData),
    ).execute().parseAs(json)

    private fun List<HentaijkEpisode>.toEpisodeList(animeUrl: String): List<SEpisode> = map { episode ->
        SEpisode.create().apply {
            val number = formatNumber(episode.number)
            setUrlWithoutDomain("$animeUrl/$number")
            name = episode.title?.takeIf { it.isNotBlank() } ?: "Episodio $number"
            episode_number = episode.number.toFloat()
            date_upload = episode.timestamp?.toDate() ?: 0L
        }
    }

    private fun formatNumber(number: Double): String =
        if (number % 1 == 0.0) number.toInt().toString() else number.toString()

    private fun String.toDate(): Long =
        runCatching { DATE_FORMATTER.parse(this)?.time }.getOrNull() ?: 0L

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframeById = document.select("script").flatMap { script ->
            IFRAME_REGEX.findAll(script.data()).mapNotNull { match ->
                val src = Jsoup.parseBodyFragment(match.groupValues[2])
                    .selectFirst("iframe[src]")?.attr("src")?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                match.groupValues[1] to src
            }
        }.toMap()
        val servers = document.select("a.servers.btn-show").mapNotNull { button ->
            val src = iframeById[button.attr("data-id")]?.let {
                if (it.startsWith("/")) baseUrl + it else it
            } ?: return@mapNotNull null
            val name = button.text().takeIf { it.isNotBlank() } ?: "Hentaijk"
            name to src
        }
        return servers.parallelCatchingFlatMapBlocking { (name, src) ->
            val iframe = client.newCall(GET(src, headers)).execute().bodyString()
            M3U8_REGEX.findAll(iframe).map { it.value }.toList().distinct()
                .map { Video(it, name, it) }
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> = try {
        val videoSorted = this.sortedWith(
            compareBy<Video> { it.videoTitle.replace("[0-9]".toRegex(), "") }.thenByDescending { getNumberFromString(it.videoTitle) },
        ).toTypedArray()
        val userPreferredQuality = preferences.getString("preferred_quality", "Sabrosio")
        val preferredIdx = videoSorted.indexOfFirst { x -> x.videoTitle == userPreferredQuality }
        if (preferredIdx != -1) {
            videoSorted.drop(preferredIdx + 1)
            videoSorted[0] = videoSorted[preferredIdx]
        }
        videoSorted.toList()
    } catch (_: Exception) {
        this
    }

    private fun getNumberFromString(epsStr: String): String = epsStr.filter { it.isDigit() }.ifEmpty { "0" }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter :
        UriPartFilter(
            "Generos",
            arrayOf(
                Pair("<seleccionar>", "none"),
                Pair("Accion", "accion"),
                Pair("Aventura", "aventura"),
                Pair("Autos", "autos"),
                Pair("Comedia", "comedia"),
                Pair("Dementia", "dementia"),
                Pair("Demonios", "demonios"),
                Pair("Misterio", "misterio"),
                Pair("Drama", "drama"),
                Pair("Ecchi", "ecchi"),
                Pair("Fantasia", "fantasa"),
                Pair("Juegos", "juegos"),
                Pair("Hentai", "hentai"),
                Pair("Historico", "historico"),
                Pair("Terror", "terror"),
                Pair("Niños", "nios"),
                Pair("Magia", "magia"),
                Pair("Artes Marciales", "artes-marciales"),
                Pair("Mecha", "mecha"),
                Pair("Musica", "musica"),
                Pair("Parodia", "parodia"),
                Pair("Samurai", "samurai"),
                Pair("Romance", "romance"),
                Pair("Colegial", "colegial"),
                Pair("Sci-Fi", "sci-fi"),
                Pair("Shoujo", "shoujo"),
                Pair("Shoujo Ai", "shoujo-ai"),
                Pair("Shounen", "shounen"),
                Pair("Shounen Ai", "shounen-ai"),
                Pair("Space", "space"),
                Pair("Deportes", "deportes"),
                Pair("Super Poderes", "super-poderes"),
                Pair("Vampiros", "vampiros"),
                Pair("Yaoi", "yaoi"),
                Pair("Yuri", "yuri"),
                Pair("Harem", "harem"),
                Pair("Cosas de la vida", "cosas-de-la-vida"),
                Pair("Sobrenatural", "sobrenatural"),
                Pair("Militar", "militar"),
                Pair("Policial", "policial"),
                Pair("Psicologico", "psicologico"),
                Pair("Thriller", "thriller"),
                Pair("Seinen", "seinen"),
                Pair("Josei", "josei"),
                Pair("Español Latino", "espaol-latino"),
                Pair("Escolares", "escolares"),
                Pair("Orgias", "orgias"),
                Pair("Virgenes", "virgenes"),
                Pair("Anal", "anal"),
                Pair("Maduras", "maduras"),
                Pair("Ahegao", "ahegao"),
                Pair("Tetonas", "tetonas"),
                Pair("Incesto", "incesto"),
                Pair("Ntr", "ntr"),
                Pair("Vanilla", "vanilla"),
                Pair("Violacion", "violacion"),
                Pair("Hardcore", "hardcore"),
                Pair("Trio", "trio"),
                Pair("Cowgirl", "cowgirl"),
                Pair("Bondage", "bondage"),
                Pair("Lolis", "lolis"),
                Pair("Rubias", "rubias"),
                Pair("Gay", "gay"),
                Pair("Tentaculos", "tentaculos"),
                Pair("Monstruos", "monstruos"),
                Pair("Tsundere", "tsundere"),
                Pair("Sin Censura", "sin-censura"),
                Pair("Softcore", "softcore"),
                Pair("Shota", "shota"),
                Pair("Ninjas", "ninjas"),
                Pair("Waifus", "waifus"),
                Pair("Gordas", "gordas"),
                Pair("Enfermeras", "enfermeras"),
                Pair("Paizuri", "paizuri"),
                Pair("Succubus", "succubus"),
                Pair("Juegos Sexuales", "juegos-sexuales"),
                Pair("Netorare", "netorare"),
                Pair("Petit", "petit"),
                Pair("Maids", "maids"),
                Pair("Rape", "rape"),
                Pair("Gore", "gore"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = arrayOf(
            "Okru:1080p", "Okru:720p", "Okru:480p", "Okru:360p", "Okru:240p", // Okru
            "Xtreme S", "HentaiJk", "Nozomi", "Desu", // video servers without resolution
        )
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = qualities
            entryValues = qualities
            setDefaultValue("Sabrosio")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    companion object {
        private val IFRAME_REGEX = Regex("""video\[(\d+)]\s*=\s*'([^']*)'""")
        private val M3U8_REGEX = Regex("""https?://[^'"\s\\]+\.m3u8[^'"\s\\]*""")
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }
    }
}
