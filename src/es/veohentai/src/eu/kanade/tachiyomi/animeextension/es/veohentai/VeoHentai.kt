package eu.kanade.tachiyomi.animeextension.es.veohentai

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

open class VeoHentai :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "VeoHentai"

    override val baseUrl = "https://veohentai.com"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val universalExtractor by lazy { UniversalExtractor(client) }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "HentaiPlayer"
        private val SERVER_LIST = arrayOf("HentaiPlayer")

        private val EPISODE_NUMBER_REGEX = """Episodio\s+(\d+(?:\.\d+)?)""".toRegex(RegexOption.IGNORE_CASE)
        private val EMBED_URL_REGEX = """https?://hentaiplayer\.com/v/[^"'\s\\]+""".toRegex()
        private val VID_B64_REGEX = """"vid"\s*:\s*"([^"]+)"""".toRegex()
        private val VIDEO_READY_REGEX = """videoReady\s*=\s*"([^"]+)"""".toRegex()
        private val TRACK_FILE_REGEX = """"file"\s*:\s*"((?:\\.|[^"\\])+\.srt(?:[^"\\])*)"""".toRegex()
    }

    override fun popularAnimeRequest(page: Int) = GET(
        if (page <= 1) {
            "$baseUrl/explorar/?orden=vistas"
        } else {
            "$baseUrl/explorar/?orden=vistas&pagina=$page"
        },
        headers,
    )

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("a.group.block").map { element ->
            SAnime.create().apply {
                title = element.selectFirst("h3")?.text()?.trim().orEmpty()
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")?.takeIf { it.isNotBlank() }
                setUrlWithoutDomain(element.attr("abs:href"))
            }
        }
        val hasNextPage = document.selectFirst("a[rel=next]") != null
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page", headers)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/buscar/?q=${URLEncoder.encode(query, "UTF-8")}", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}/page/$page", headers)
            else -> GET("$baseUrl/explorar/?pagina=$page", headers)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1.h1-display")?.text()?.trim()
                ?: document.selectFirst("h1")?.text()?.trim().orEmpty()
            status = SAnime.UNKNOWN
            description = document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
            genre = document.select("a[href^=/genero/]")
                .map { it.ownText().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString()
            author = document.selectFirst("a[href^=/brand/]")?.ownText()?.trim()
            thumbnail_url = document.select("img[src*=/media/]")
                .firstOrNull { it.attr("alt").isNotBlank() }
                ?.attr("abs:src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = document.select("a.group.block[href*=/ver/]").map { element ->
            SEpisode.create().apply {
                name = element.selectFirst("h3")?.text()?.trim() ?: element.attr("href")
                episode_number = EPISODE_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 1F
                setUrlWithoutDomain(element.attr("abs:href"))
            }
        }
        if (episodes.isNotEmpty()) return episodes.reversed()
        return listOf(
            SEpisode.create().apply {
                episode_number = 1F
                name = document.selectFirst("h1")?.text()?.trim() ?: "Episodio"
                setUrlWithoutDomain(document.location())
            },
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val embedUrl = document.selectFirst("iframe[src*=hentaiplayer.com]")?.attr("abs:src")
            ?.takeIf { it.isNotBlank() && !it.startsWith("about") }
            ?: EMBED_URL_REGEX.find(document.html())?.value
            ?: return emptyList()

        val dataIds = runCatching {
            client.newCall(GET(embedUrl, headers)).execute().use { embedResponse ->
                embedResponse.asJsoup().select("li[data-id]").map { it.attr("data-id") }
            }
        }.getOrDefault(emptyList())

        if (dataIds.isEmpty()) {
            return runCatching {
                universalExtractor.videosFromUrl(embedUrl, headers, prefix = "HentaiPlayer")
            }.getOrDefault(emptyList())
        }

        val videos = mutableListOf<Video>()
        dataIds.forEach { dataId ->
            val direct = runCatching { extractDirectVideo(dataId, embedUrl) }.getOrNull().orEmpty()
            if (direct.isNotEmpty()) {
                videos += direct
            } else {
                videos += runCatching {
                    universalExtractor.videosFromUrl(embedUrl, headers, prefix = "HentaiPlayer")
                }.getOrDefault(emptyList())
            }
        }
        return videos.distinctBy { it.videoUrl }
    }

    private fun extractDirectVideo(dataId: String, embedUrl: String): List<Video> {
        val playerUrl = runCatching { embedUrl.toHttpUrl().resolve(dataId)?.toString() }.getOrNull()
            ?: return emptyList()
        val playerHeaders = headers.newBuilder().add("Referer", embedUrl).build()
        val playerDoc = client.newCall(GET(playerUrl, playerHeaders)).execute().use { it.asJsoup() }
        val script = playerDoc.select("script").firstOrNull { it.data().contains("window._pV") }?.data()

        val file = script?.let {
            VID_B64_REGEX.find(it)?.groupValues?.get(1)
                ?.let { b64 -> runCatching { Base64.decode(b64, Base64.DEFAULT).toString(Charsets.UTF_8) }.getOrNull() }
                ?.substringBefore("|")
                ?.takeIf { url -> url.startsWith("http") }
        } ?: VIDEO_READY_REGEX.find(playerDoc.html())?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
            ?: return emptyList()

        val subtitleUrl = runCatching { playerUrl.toHttpUrl().queryParameter("s") }
            .getOrNull()
            ?.let { runCatching { Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8) }.getOrNull() }
            ?.takeIf { it.startsWith("http") }
            ?: script?.let { TRACK_FILE_REGEX.find(it)?.groupValues?.get(1)?.replace("\\/", "/") }

        val subtitles = listOfNotNull(
            subtitleUrl?.takeIf { it.isNotBlank() }?.let { Track(it, "Español") },
        )

        return listOf(Video(file, "HentaiPlayer:MP4", file, subtitleTracks = subtitles))
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

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter :
        UriPartFilter(
            "Género",
            arrayOf(
                Pair("<Seleccionar>", ""),
                Pair("3D", "genero/3d"),
                Pair("Ahegao", "genero/ahegao"),
                Pair("Anal", "genero/anal"),
                Pair("Bondage", "genero/bondage"),
                Pair("Casadas", "genero/casadas"),
                Pair("Censurado", "genero/censurado"),
                Pair("Chikan", "genero/chikan"),
                Pair("Corridas", "genero/corridas"),
                Pair("Ecchi", "genero/ecchi"),
                Pair("Enfermeras", "genero/enfermeras"),
                Pair("Escolares", "genero/hentai-escolares"),
                Pair("Fantasia", "genero/fantasia"),
                Pair("Futanari", "genero/futanari"),
                Pair("Gore", "genero/gore"),
                Pair("Hardcore", "genero/hardcore"),
                Pair("Harem", "genero/harem"),
                Pair("Incesto", "genero/incesto"),
                Pair("Josei", "genero/josei"),
                Pair("Juegos Sexuales", "genero/juegos-sexuales"),
                Pair("Lesbiana", "genero/lesbiana"),
                Pair("Lolicon", "genero/lolicon"),
                Pair("Maids", "genero/maids"),
                Pair("Manga", "genero/manga"),
                Pair("Masturbación", "genero/masturbacion"),
                Pair("Milfs", "genero/milfs"),
                Pair("Netorare", "genero/netorare"),
                Pair("Ninfomania", "genero/ninfomania"),
                Pair("Ninjas", "genero/ninjas"),
                Pair("Oral", "genero/oral"),
                Pair("Orgias", "genero/orgias"),
                Pair("Romance", "genero/romance"),
                Pair("Shota", "genero/shota"),
                Pair("Sin Censura", "genero/hentai-sin-censura"),
                Pair("Softcore", "genero/softcore"),
                Pair("Succubus", "genero/succubus"),
                Pair("Teacher", "genero/teacher"),
                Pair("Tentaculos", "genero/tentaculos"),
                Pair("Tetonas", "genero/tetonas"),
                Pair("Vanilla", "genero/vanilla"),
                Pair("Violacion", "genero/violacion"),
                Pair("Virgenes", "genero/virgenes"),
                Pair("Yaoi", "genero/yaoi"),
                Pair("Yuri", "genero/yuri"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
