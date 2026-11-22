package keiyoushi.utils

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Request
import okhttp3.Response

private fun isAndroidRuntime(): Boolean = runCatching { Class.forName("com.anymex.desktop.AniyomiSourceMethods") }.isFailure

abstract class AnimeHttpLegacySource : AnimeHttpSource() {
    // On AnymeX desktop the bridge turns already-resolved videos into hosters
    // with an empty URL and then tries to fetch that URL, failing with
    // "Expected URL scheme 'http' or 'https'". Returning no hosters there makes
    // the bridge use getVideoList(episode) directly. Android is unchanged.
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = if (isAndroidRuntime()) getVideoList(episode).toHosterList() else emptyList()

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    open suspend fun getVideoList(episode: SEpisode): List<Video> = client.newCall(videoListRequest(episode))
        .awaitSuccess()
        .use(::videoListParse)

    open fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)
    open fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response) = throw UnsupportedOperationException()
}
