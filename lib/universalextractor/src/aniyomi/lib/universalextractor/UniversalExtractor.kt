package aniyomi.lib.universalextractor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UniversalExtractor(private val client: OkHttpClient) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun videosFromUrl(origRequestUrl: String, origRequestHeader: Headers, customQuality: String? = null, prefix: String = ""): List<Video> {
        // AnymeX's desktop runtime ships a no-op WebView stub: loadUrl() never fetches
        // anything and shouldInterceptRequest() is never called. On JVM platforms use a
        // pure-OkHttp resolver instead. Android keeps the original WebView path untouched.
        if (!isAndroidRuntime()) {
            return videosFromUrlDesktop(origRequestUrl, origRequestHeader, customQuality, prefix)
        }

        val host = origRequestUrl.toHttpUrl().host.substringBefore(".").proper()
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var resultUrl = ""
        val playlistUtils by lazy { PlaylistUtils(client, origRequestHeader) }
        val headers = origRequestHeader.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()

        handler.post {
            val newView = WebView(context)
            webView = newView
            with(newView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = origRequestHeader["User-Agent"]
            }
            newView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    if (VIDEO_REGEX.containsMatchIn(url)) {
                        resultUrl = url
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webView?.loadUrl(origRequestUrl, headers)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
        // terabox special case start
        if ("M3U8_AUTO_360" in resultUrl) {
            val qualities = listOf("1080", "720", "480", "360")
            val allVideos = mutableListOf<Video>()

            for (quality in qualities) {
                val modifiedUrl = resultUrl.replace("M3U8_AUTO_360", "M3U8_AUTO_$quality")
                val videos = playlistUtils.extractFromHls(modifiedUrl, origRequestUrl, videoNameGen = { "$prefix - $host: $it ${quality}p" })

                if (videos.isNotEmpty()) {
                    allVideos.addAll(videos)
                }
            }

            if (allVideos.isNotEmpty()) {
                return allVideos
            }
        }
        // terabox special case end

        return when {
            "m3u8" in resultUrl -> {
                Log.d("UniversalExtractor", "m3u8 URL: $resultUrl")
                playlistUtils.extractFromHls(resultUrl, origRequestUrl, videoNameGen = { "$prefix - $host: $it" })
            }
            "mpd" in resultUrl -> {
                Log.d("UniversalExtractor", "mpd URL: $resultUrl")
                playlistUtils.extractFromDash(resultUrl, { it -> "$prefix - $host: $it" }, referer = origRequestUrl)
            }
            "mp4" in resultUrl -> {
                Log.d("UniversalExtractor", "mp4 URL: $resultUrl")
                Video(resultUrl, "$prefix - $host: ${customQuality ?: "Mirror"}", resultUrl, origRequestHeader.newBuilder().add("referer", origRequestUrl).build()).let(::listOf)
            }
            else -> emptyList()
        }
    }

    /**
     * Desktop (JVM) fallback. The AnymeX runtime has no real WebView, so media URLs are
     * resolved from the HTML/JS sources with OkHttp. This is never used on Android.
     */
    private fun videosFromUrlDesktop(
        origRequestUrl: String,
        origRequestHeader: Headers,
        customQuality: String?,
        prefix: String,
    ): List<Video> {
        val host = runCatching { origRequestUrl.toHttpUrl().host.substringBefore(".") }
            .getOrDefault("video").proper()
        val playlistUtils = PlaylistUtils(client, origRequestHeader)

        val visited = HashSet<String>()
        val queue = ArrayDeque<String>().apply { add(origRequestUrl) }
        val found = LinkedHashSet<String>()
        var depth = 0

        while (queue.isNotEmpty() && depth < MAX_DEPTH && found.isEmpty() && visited.size < MAX_PAGES) {
            val pageUrl = queue.removeFirst()
            if (!visited.add(pageUrl)) continue

            val body = try {
                client.newCall(GET(pageUrl, origRequestHeader)).execute().use { response ->
                    if (response.isSuccessful) response.body?.string().orEmpty() else ""
                }
            } catch (e: Exception) {
                Log.e("UniversalExtractor", "Desktop fetch failed for $pageUrl: ${e.message}")
                ""
            }
            if (body.isBlank()) {
                depth++
                continue
            }

            val normalized = normalizeSource(body)
            collectMediaUrls(normalized, pageUrl, found)
            collectAtobUrls(body, pageUrl, found)

            if (found.isEmpty()) {
                IFRAME_URL_REGEX.findAll(normalized)
                    .mapNotNull { resolveUrl(pageUrl, unescapeUrl(it.groupValues[1])) }
                    .filter { url ->
                        url !in visited && (
                            url.contains("embed", true) ||
                                url.contains("player", true) ||
                                url.contains("stream", true) ||
                                url.contains("watch", true) ||
                                runCatching { URI(url).host == URI(pageUrl).host }.getOrDefault(false)
                            )
                    }
                    .forEach { queue.add(it) }
            }
            depth++
        }

        if (found.isEmpty()) {
            Log.e("UniversalExtractor", "Desktop resolver found no media URL for $origRequestUrl")
            return emptyList()
        }

        return found.flatMap { mediaUrl ->
            when {
                mediaUrl.contains("m3u8", true) ->
                    playlistUtils.extractFromHls(mediaUrl, origRequestUrl, videoNameGen = { "$prefix - $host: $it" })

                mediaUrl.contains("mpd", true) ->
                    playlistUtils.extractFromDash(mediaUrl, { "$prefix - $host: $it" }, referer = origRequestUrl)

                else -> {
                    val headers = origRequestHeader.newBuilder()
                        .set("Referer", origRequestUrl)
                        .build()
                    listOf(Video(mediaUrl, "$prefix - $host: ${customQuality ?: "Mirror"}", mediaUrl, headers))
                }
            }
        }.distinctBy { it.videoUrl }
    }

    private fun collectMediaUrls(source: String, pageUrl: String, out: MutableSet<String>) {
        MEDIA_URL_REGEX.findAll(source)
            .mapNotNull { resolveUrl(pageUrl, unescapeUrl(it.groupValues[1])) }
            .forEach { out.add(it) }

        RELATIVE_MEDIA_URL_REGEX.findAll(source)
            .mapNotNull { resolveUrl(pageUrl, unescapeUrl(it.groupValues[1])) }
            .forEach { out.add(it) }
    }

    private fun collectAtobUrls(source: String, pageUrl: String, out: MutableSet<String>) {
        ATOB_REGEX.findAll(source).forEach { match ->
            val decoded = runCatching {
                String(Base64.decode(match.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault("")
            if (decoded.isNotBlank()) collectMediaUrls(decoded, pageUrl, out)
        }
    }

    private fun normalizeSource(source: String): String = source
        .replace("\\/", "/")
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\u003d", "=", ignoreCase = true)
        .replace("\\u003a", ":", ignoreCase = true)
        .replace("\\u002f", "/", ignoreCase = true)
        .replace("&amp;", "&")
        .replace("\\n", " ")
        .replace("\\t", " ")

    private fun unescapeUrl(raw: String): String = raw
        .trim()
        .trimEnd(',', ';', ')', ']', '}', '\'', '"')
        .replace("\\/", "/")
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("&amp;", "&")

    private fun resolveUrl(base: String, raw: String): String? = runCatching {
        when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("//") -> "${URI(base).scheme ?: "https"}:$raw"
            raw.startsWith("/") -> {
                val uri = URI(base)
                "${uri.scheme}://${uri.authority}$raw"
            }
            else -> URI(base).resolve(raw).toString()
        }.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }.getOrNull()

    private fun isAndroidRuntime(): Boolean = runCatching { Class.forName("com.anymex.desktop.AniyomiSourceMethods") }.isFailure

    private fun String.proper(): String = this.replaceFirstChar {
        if (it.isLowerCase()) {
            it.titlecase(Locale.getDefault())
        } else {
            it.toString()
        }
    }

    companion object {
        const val TIMEOUT_SEC: Long = 10
        private const val MAX_DEPTH = 3
        private const val MAX_PAGES = 12
        private val VIDEO_REGEX by lazy { Regex(".*\\.(mp4|m3u8|mpd)(\\?.*)?$") }

        private val MEDIA_URL_REGEX by lazy {
            Regex(
                """(?:https?:)?//[^\s"'<>\\)]+?\.(?:m3u8|mpd|mp4)(?:\?[^\s"'<>\\)]*)?""",
                RegexOption.IGNORE_CASE,
            )
        }
        private val RELATIVE_MEDIA_URL_REGEX by lazy {
            Regex(
                """["'](/[^"']+?\.(?:m3u8|mpd|mp4)(?:\?[^"']*)?)["']""",
                RegexOption.IGNORE_CASE,
            )
        }
        private val IFRAME_URL_REGEX by lazy {
            Regex("""(?:src|href)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        }
        private val ATOB_REGEX by lazy {
            Regex("""atob\(["']([A-Za-z0-9+/=]{8,})["']\)""")
        }
    }
}
