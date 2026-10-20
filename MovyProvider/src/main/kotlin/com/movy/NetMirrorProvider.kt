package com.movy

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.util.UUID

class NetMirrorProvider : MainAPI() {
    override var mainUrl = "https://net52.cc"
    override var name = "NetMirror"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true

    override val mainPage = mainPageOf("" to "Home")

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/javascript, text/html, */*; q=0.01",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "X-Requested-With" to "XMLHttpRequest"
    )

    private val verifyHeaders = mapOf(
        "User-Agent" to DESKTOP_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to "https://net77.cc",
        "Referer" to "https://net77.cc/verify2",
        "Content-Type" to "application/x-www-form-urlencoded"
    )

    @Volatile
    private var lastCookies: Map<String, String> = emptyMap()

    private data class LoadData(val id: String, val title: String)

    private fun now(): String = (System.currentTimeMillis() / 1000L).toString()

    private fun encodeData(id: String, title: String): String =
        "$id|${title.replace("|", " ")}"

    private fun parseData(data: String): LoadData? {
        val index = data.indexOf('|')
        if (index <= 0) return null
        val id = data.substring(0, index).trim()
        val title = data.substring(index + 1).trim()
        if (id.isBlank()) return null
        return LoadData(id, title)
    }

    private fun cookieExpired(value: String?): Boolean {
        if (value.isNullOrBlank()) return true
        val expiry = Regex("""(?:::|%3A%3A)(\d{10})(?:::|%3A%3A)""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: return false
        return expiry <= System.currentTimeMillis() / 1000L + 60L
    }

    private suspend fun session(force: Boolean = false): Map<String, String> {
        val saved = NetMirrorStore.cookies()
        if (force || cookieExpired(saved["t_hash_t"])) {
            runCatching {
                val response = app.post(
                    "$mainUrl/verify.php",
                    data = mapOf("g-recaptcha-response" to UUID.randomUUID().toString()),
                    headers = verifyHeaders,
                    allowRedirects = false
                )
                NetMirrorStore.update(response.cookies)
            }
        }
        val cookies = NetMirrorStore.cookies()
        cookies["ott"] = "nf"
        cookies["hd"] = "on"
        lastCookies = cookies
        return cookies
    }

    private suspend fun getJson(
        url: String,
        params: Map<String, String>,
        referer: String
    ): JSONObject {
        var cookies = session()
        var response = app.get(
            url,
            params = params,
            headers = headers,
            cookies = cookies,
            referer = referer
        )
        var text = response.text
        if (response.code == 403 || text.contains("/verify2") || text.contains("Just a moment")) {
            cookies = session(force = true)
            response = app.get(
                url,
                params = params,
                headers = headers,
                cookies = cookies,
                referer = referer
            )
            text = response.text
        }
        return JSONObject(text)
    }

    private fun poster(id: String): String = "https://imgcdn.kim/poster/v/$id.jpg"

    private fun widePoster(id: String): String = "https://imgcdn.kim/poster/h/$id.jpg"

    private fun episodePoster(id: String): String = "https://imgcdn.kim/epimg/780/$id.jpg"

    private fun card(id: String, title: String, posterUrl: String?): SearchResponse =
        newMovieSearchResponse(title, "$mainUrl/nf/$id", TvType.Movie) {
            this.posterUrl = posterUrl ?: poster(id)
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }

    private suspend fun homeDocument(): Document? {
        var response = runCatching {
            app.get(
                "$mainUrl/mobile/home?app=1",
                headers = headers,
                cookies = session(),
                referer = "$mainUrl/mobile/home?app=1"
            )
        }.getOrNull() ?: return null
        if (response.code == 403 ||
            response.text.contains("/verify2") ||
            response.text.contains("Just a moment")
        ) {
            response = runCatching {
                app.get(
                    "$mainUrl/mobile/home?app=1",
                    headers = headers,
                    cookies = session(force = true),
                    referer = "$mainUrl/mobile/home?app=1"
                )
            }.getOrNull() ?: return null
        }
        return response.document
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList<HomePageList>())

        val document = homeDocument()
            ?: return newHomePageResponse(emptyList<HomePageList>())

        val sections = mutableListOf<HomePageList>()

        val topTen = document.select("#top10 .top10-post").mapNotNull { element ->
            val id = element.attr("data-post").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            card(id, "", null)
        }
        if (topTen.isNotEmpty()) {
            sections.add(HomePageList("Top 10", topTen, isHorizontalImages = false))
        }

        document.select("div.tray-container").forEach { tray ->
            val title = tray.selectFirst("h2.tray-title")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@forEach
            val items = tray.select("article.tray-slide").mapNotNull { article ->
                val id = article.selectFirst("a[data-post]")?.attr("data-post")
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                card(id, "", null)
            }
            if (items.isNotEmpty()) {
                sections.add(HomePageList(title, items.take(30), isHorizontalImages = false))
            }
        }

        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val json = runCatching {
            getJson(
                "$mainUrl/mobile/search.php",
                mapOf("s" to query, "t" to now(), "ADSearch" to "false"),
                "$mainUrl/mobile/home?app=1"
            )
        }.getOrNull() ?: return emptyList()

        val results = json.optJSONArray("searchResult") ?: return emptyList()
        val items = mutableListOf<SearchResponse>()
        for (i in 0 until minOf(results.length(), 40)) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val title = item.optString("t").takeIf { it.isNotBlank() } ?: continue
            items.add(card(id, title, null))
        }
        return items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("""/nf/(\d+)""").find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""(\d+)""").find(url)?.groupValues?.getOrNull(1)
            ?: return null

        var post = runCatching {
            getJson(
                "$mainUrl/mobile/post.php",
                mapOf("id" to id, "t" to now()),
                "$mainUrl/mobile/home?app=1"
            )
        }.getOrNull() ?: return null

        if (post.optString("status") == "n") {
            session(force = true)
            post = runCatching {
                getJson(
                    "$mainUrl/mobile/post.php",
                    mapOf("id" to id, "t" to now()),
                    "$mainUrl/mobile/home?app=1"
                )
            }.getOrNull() ?: return null
        }

        val title = post.optString("title").takeIf { it.isNotBlank() } ?: id
        val year = post.optString("year").take(4).toIntOrNull()
        val plot = post.optString("desc").takeIf { it.isNotBlank() }
        val tags = post.optString("genre")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val actors = post.optString("cast")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { com.lagradost.cloudstream3.ActorData(com.lagradost.cloudstream3.Actor(it)) }
        val duration = parseRuntime(post.optString("runtime"))
        val suggestions = post.optJSONArray("suggest")?.let { array ->
            (0 until minOf(array.length(), 20)).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val suggestId = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                card(suggestId, "", null)
            }
        }.orEmpty()
        val description = encodeData(id, title)

        if (post.optString("type") != "t") {
            return newMovieLoadResponse(title, url, TvType.Movie, description) {
                this.posterUrl = poster(id)
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                this.backgroundPosterUrl = widePoster(id)
                this.plot = plot
                this.year = year
                this.tags = tags
                this.actors = actors
                this.duration = duration
                this.recommendations = suggestions
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasons = post.optJSONArray("season")
        if (seasons != null && seasons.length() > 0) {
            for (i in 0 until seasons.length()) {
                val season = seasons.optJSONObject(i) ?: continue
                val seasonId = season.optString("id").takeIf { it.isNotBlank() } ?: continue
                val seasonNumber = season.optString("s").toIntOrNull() ?: (i + 1)
                episodes.addAll(fetchEpisodes(id, seasonId, seasonNumber, title))
            }
        }

        if (episodes.isEmpty()) {
            val fallback = post.optJSONArray("episodes")
            if (fallback != null) {
                for (i in 0 until fallback.length()) {
                    val item = fallback.optJSONObject(i) ?: continue
                    val epId = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    episodes.add(
                        newEpisode(encodeData(epId, title)) {
                            this.name = item.optString("t").ifBlank { "Episode" }
                            this.season = item.optString("s").toIntOrNull()
                            this.episode = item.optString("ep").toIntOrNull()
                            this.runTime = item.optString("time").toIntOrNull()
                            this.posterUrl = episodePoster(epId)
                        }
                    )
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster(id)
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.backgroundPosterUrl = widePoster(id)
            this.plot = plot
            this.year = year
            this.tags = tags
            this.actors = actors
            this.recommendations = suggestions
        }
    }

    private suspend fun fetchEpisodes(
        seriesId: String,
        seasonId: String,
        seasonNumber: Int,
        seriesTitle: String
    ): List<Episode> {
        val result = mutableListOf<Episode>()
        var page = 1
        var currentSeason = seasonId
        var guard = 0
        while (guard++ < 20) {
            val params = mutableMapOf(
                "s" to currentSeason,
                "series" to seriesId,
                "t" to now()
            )
            if (page > 1) params["page"] = page.toString()
            val json = runCatching {
                getJson(
                    "$mainUrl/mobile/episodes.php",
                    params,
                    "$mainUrl/mobile/post.php?id=$seriesId"
                )
            }.getOrNull() ?: break

            val episodes = json.optJSONArray("episodes") ?: break
            for (i in 0 until episodes.length()) {
                val item = episodes.optJSONObject(i) ?: continue
                val epId = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val epTitle = item.optString("t").ifBlank { "Episode" }
                result.add(
                    newEpisode(encodeData(epId, seriesTitle)) {
                        this.name = epTitle
                        this.season = item.optString("s").toIntOrNull() ?: seasonNumber
                        this.episode = item.optString("ep").toIntOrNull() ?: (i + 1)
                        this.runTime = item.optString("time").toIntOrNull()
                        this.posterUrl = episodePoster(epId)
                    }
                )
            }

            if (json.optInt("nextPageShow", 0) != 1) break
            page = json.optInt("nextPage", page + 1)
            currentSeason = json.optString("nextPageSeason", currentSeason).ifBlank { currentSeason }
        }
        return result
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseData(data) ?: return false
        var cookies = session()
        lastCookies = cookies

        if (cookies["user_token"].isNullOrBlank()) {
            NetMirrorSettings.showLogin(
                "NetMirror needs a user_token before playback. Log in or play one ad, then save and retry."
            )
            throw ErrorLoadingException(
                "NetMirror playback is locked. Finish the login popup, then retry."
            )
        }

        val playlistParams = mapOf(
            "id" to loadData.id,
            "t" to loadData.title,
            "tm" to now()
        )
        val playlistResponse = runCatching {
            app.get(
                "$mainUrl/mobile/playlist.php",
                params = playlistParams,
                headers = headers,
                cookies = cookies,
                referer = "$mainUrl/mobile/home?app=1"
            )
        }.getOrNull() ?: return false
        var playlist = playlistResponse.text
        if (playlistResponse.code == 403 || playlist.contains("/verify2")) {
            cookies = session(force = true)
            lastCookies = cookies
            playlist = runCatching {
                app.get(
                    "$mainUrl/mobile/playlist.php",
                    params = playlistParams,
                    headers = headers,
                    cookies = cookies,
                    referer = "$mainUrl/mobile/home?app=1"
                ).text
            }.getOrNull() ?: return false
        }
        if (playlist.isBlank() ||
            playlist.contains("/verify2") ||
            playlist.contains("Just a moment")
        ) {
            NetMirrorSettings.showLogin(
                "Your NetMirror session expired. Log in again to unlock playback."
            )
            throw ErrorLoadingException("NetMirror session expired. Complete the login popup, then retry.")
        }

        val array = runCatching { JSONArray(playlist) }.getOrNull() ?: return false
        if (array.length() == 0) {
            NetMirrorSettings.showLogin(
                "Your NetMirror session expired. Log in again to unlock playback."
            )
            throw ErrorLoadingException("NetMirror session expired. Complete the login popup, then retry.")
        }
        val item = array.optJSONObject(0) ?: return false
        val sources = item.optJSONArray("sources") ?: return false

        var first = true
        var found = false
        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            val file = source.optString("file").takeIf { it.isNotBlank() } ?: continue
            val label = source.optString("label").ifBlank { "NetMirror" }
            val url = if (file.startsWith("http")) file else "$mainUrl$file"

            if (first) {
                val master = runCatching {
                    app.get(
                        url,
                        headers = headers,
                        cookies = cookies,
                        referer = "$mainUrl/"
                    ).text
                }.getOrNull()
                if (master == null) {
                    throw ErrorLoadingException("Could not load the NetMirror stream. Check your connection and retry.")
                }
                if (master.contains("/220884/")) {
                    NetMirrorSettings.showLogin(
                        "Your NetMirror credentials are locked or expired. Log in again, save, then retry."
                    )
                    throw ErrorLoadingException(
                        "NetMirror session expired. Complete the login popup, then retry."
                    )
                }
                first = false
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = if (label == "Auto") name else "$name $label",
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = qualityOf(label)
                    this.headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "Origin" to mainUrl,
                        "Cookie" to cookieHeader(cookies),
                        "User-Agent" to USER_AGENT
                    )
                }
            )
            found = true
        }

        val tracks = item.optJSONArray("tracks")
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val track = tracks.optJSONObject(i) ?: continue
                val kind = track.optString("kind").lowercase()
                if (kind != "captions" && kind != "subtitles") continue
                val file = track.optString("file").takeIf { it.isNotBlank() } ?: continue
                val subUrl = when {
                    file.startsWith("//") -> "https:$file"
                    file.startsWith("http") -> file
                    file.startsWith("/") -> "https://subscdn.top$file"
                    else -> "https://subscdn.top/$file"
                }
                val label = track.optString("label").ifBlank { "Subtitle" }
                subtitleCallback.invoke(
                    newSubtitleFile(label, subUrl) {
                        this.headers = mapOf("Referer" to "$mainUrl/")
                    }
                )
            }
        }

        return found
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return Interceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val builder = request.newBuilder()
            extractorLink.referer?.takeIf { it.isNotBlank() }?.let {
                builder.header("Referer", it)
            }
            if (host.contains("net52") ||
                host.contains("net77") ||
                host.contains("freecdn") ||
                host.contains("subscdn") ||
                host.contains("imgcdn")
            ) {
                builder.header("Origin", mainUrl)
                val cookie = cookieHeader(lastCookies)
                if (cookie.isNotBlank()) builder.header("Cookie", cookie)
            }
            chain.proceed(builder.build())
        }
    }

    private fun cookieHeader(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun parseRuntime(runtime: String): Int? {
        val hours = Regex("""(\d+)\s*h""").find(runtime)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val minutes = Regex("""(\d+)\s*m""").find(runtime)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val total = (hours ?: 0) * 60 + (minutes ?: 0)
        return total.takeIf { it > 0 }
    }

    private fun qualityOf(label: String): Int = when {
        label.contains("2160", true) || label.contains("4K", true) -> Qualities.P2160.value
        label.contains("1080", true) || label.contains("Full HD", true) -> Qualities.P1080.value
        label.contains("720", true) || label.contains("Mid HD", true) -> Qualities.P720.value
        label.contains("480", true) || label.contains("Low HD", true) -> Qualities.P480.value
        label.contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 " +
                "Safari/537.36 /OS.Gatu v3.0"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
    }
}
