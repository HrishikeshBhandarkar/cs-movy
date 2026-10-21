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
import android.util.Base64
import android.util.Log
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

    @Volatile
    private var lastSessionAt: Long = 0L

    @Volatile
    private var lastVerifyAt: Long = 0L

    @Volatile
    private var newTvBase: String? = null

    private val newTvHeaders = mapOf(
        "Cache-Control" to "no-cache, no-store, must-revalidate",
        "Pragma" to "no-cache",
        "Expires" to "0",
        "X-Requested-With" to "NetmirrorNewTV v1.0",
        "User-Agent" to NEWTV_USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Ott" to "nf"
    )

    private fun currentCookies(): MutableMap<String, String> {
        val cookies = NetMirrorStore.cookies()
        cookies["ott"] = "nf"
        cookies["hd"] = "on"
        return cookies
    }

    private fun newTvRequestHeaders(): Map<String, String> {
        val cookies = currentCookies()
        val result = newTvHeaders.toMutableMap()
        val cookie = cookieHeader(cookies)
        if (cookie.isNotBlank()) result["Cookie"] = cookie
        result["Usertoken"] = cookies["user_token"].orEmpty()
        return result
    }

    private fun streamRequestHeaders(
        cookies: Map<String, String>,
        referer: String
    ): Map<String, String> {
        val result = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to referer,
            "Origin" to mainUrl
        )
        val cookie = cookieHeader(cookies)
        if (cookie.isNotBlank()) result["Cookie"] = cookie
        cookies["user_token"]?.takeIf { it.isNotBlank() }?.let {
            result["Usertoken"] = it
        }
        return result
    }

    private data class LoadData(val id: String, val title: String)

    private fun now(): String = (System.currentTimeMillis() / 1000L).toString()

    private fun normalizeId(raw: String): String {
        val cleaned = raw.trim().substringBefore('?')
        return Regex("""(\d+)/?$""").find(cleaned)?.groupValues?.getOrNull(1) ?: cleaned
    }

    private fun encodeData(id: String, title: String): String =
        "${normalizeId(id)}|${title.replace("|", " ")}"

    private fun parseData(data: String): LoadData? {
        val index = data.indexOf('|')
        if (index <= 0) return null
        val id = normalizeId(data.substring(0, index))
        val title = data.substring(index + 1).trim()
        if (id.isBlank()) return null
        return LoadData(id, title)
    }

    private suspend fun session(force: Boolean = false): Map<String, String> {
        val nowMs = System.currentTimeMillis()
        val saved = NetMirrorStore.cookies()
        val savedToken = saved["t_hash_t"]
        val savedUserToken = saved["user_token"]
        val memoryMatchesStore =
            lastCookies["t_hash_t"] == savedToken &&
                lastCookies["user_token"] == savedUserToken
        if (!force &&
            memoryMatchesStore &&
            !lastCookies["t_hash_t"].isNullOrBlank() &&
            nowMs - lastSessionAt < SESSION_TTL_MS
        ) {
            return lastCookies
        }

        Log.d(
            TAG,
            "NetMirror session before force=$force keys=${saved.keys.sorted()} " +
                "hash=${savedToken?.length ?: 0}"
        )

        // The 10-digit field inside t_hash_t is the creation time, not the
        // expiry. Treating it as an expiry made every request mint a new token,
        // which triggered NetMirror's anti-abuse block.
        if (!savedToken.isNullOrBlank()) {
            val cookies = saved.toMutableMap()
            cookies["ott"] = "nf"
            cookies["hd"] = "on"
            lastCookies = cookies
            lastSessionAt = nowMs
            Log.d(
                TAG,
                if (force) "NetMirror forced refresh skipped; keeping saved token"
                else "NetMirror session reused saved token"
            )
            return cookies
        }

        if (!force && nowMs - lastVerifyAt < VERIFY_COOLDOWN_MS &&
            !lastCookies["t_hash_t"].isNullOrBlank()
        ) {
            Log.d(TAG, "NetMirror verify cooldown active")
            return lastCookies
        }

        lastVerifyAt = nowMs
        runCatching {
            val response = app.post(
                "$mainUrl/verify.php",
                data = mapOf("g-recaptcha-response" to UUID.randomUUID().toString()),
                headers = verifyHeaders,
                allowRedirects = false
            )
            val setCookie = response.headers["set-cookie"].orEmpty()
            val hash = Regex("""(?i)(?:^|[,\s])t_hash_t=([^;,\s]+)""")
                .find(setCookie)
                ?.groupValues
                ?.getOrNull(1)
            val updates = response.cookies.toMutableMap()
            if (!hash.isNullOrBlank()) updates["t_hash_t"] = hash
            NetMirrorStore.update(updates)
            Log.d(
                TAG,
                "NetMirror verify code=${response.code} setCookie=${setCookie.isNotBlank()} " +
                    "parsedHash=${hash?.length ?: 0} responseKeys=${response.cookies.keys.sorted()}"
            )
        }.onFailure {
            Log.d(TAG, "NetMirror verify failed: ${it.message}")
        }
        val cookies = NetMirrorStore.cookies()
        cookies["ott"] = "nf"
        cookies["hd"] = "on"
        lastCookies = cookies
        lastSessionAt = System.currentTimeMillis()
        Log.d(TAG, "NetMirror session after keys=${cookies.keys.sorted()} hash=${cookies["t_hash_t"]?.length ?: 0}")
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

    private fun normalizeTitle(value: String): String =
        value.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()

    private suspend fun findTitleMatch(title: String): SearchResponse? {
        val wanted = normalizeTitle(title)
        if (wanted.isBlank()) return null
        val results = runCatching { search(title) }.getOrNull().orEmpty()
        return results.firstOrNull {
            val candidate = normalizeTitle(it.name)
            candidate == wanted ||
                candidate.removePrefix("the ").trim() == wanted.removePrefix("the ").trim()
        }
    }

    private suspend fun fetchPost(id: String): JSONObject? = runCatching {
        getJson(
            "$mainUrl/mobile/post.php",
            mapOf("id" to id, "t" to now()),
            "$mainUrl/mobile/home?app=1"
        )
    }.getOrNull()

    private suspend fun resolveEpisodeData(
        seriesId: String,
        seriesTitle: String,
        season: Int,
        episode: Int
    ): LoadData? {
        var post = fetchPost(seriesId) ?: return null
        if (post.optString("status") == "n") {
            session(force = true)
            post = fetchPost(seriesId) ?: return null
        }
        if (post.optString("type") != "t") return null

        val seasons = post.optJSONArray("season")
        if (seasons != null) {
            for (i in 0 until seasons.length()) {
                val item = seasons.optJSONObject(i) ?: continue
                val seasonNumber = item.optString("s").toIntOrNull() ?: (i + 1)
                if (seasonNumber != season) continue
                val seasonId = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val target = fetchEpisodes(seriesId, seasonId, seasonNumber, seriesTitle)
                    .firstOrNull { it.episode == episode }
                if (target != null) return parseData(target.data)
            }
        }

        val fallback = post.optJSONArray("episodes")
        if (fallback != null) {
            for (i in 0 until fallback.length()) {
                val item = fallback.optJSONObject(i) ?: continue
                val seasonNumber = item.optString("s").toIntOrNull()
                if (seasonNumber != null && seasonNumber != season) continue
                if (item.optString("ep").toIntOrNull() != episode) continue
                val epId = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                return LoadData(epId, seriesTitle)
            }
        }
        return null
    }

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

    private suspend fun resolveNewTvBase(force: Boolean = false): String? {
        val cached = newTvBase
        if (!force && !cached.isNullOrBlank()) return cached
        if (force) newTvBase = null

        for (domain in NEWTV_DOMAINS) {
            val response = runCatching {
                app.get("$domain/checknewtv.php", headers = newTvHeaders)
            }.getOrNull() ?: continue
            if (response.code !in 200..299) continue
            val token = runCatching {
                JSONObject(response.text).optString("token_hash")
            }.getOrNull()
            if (token.isNullOrBlank()) continue
            val decoded = runCatching {
                String(Base64.decode(token, Base64.DEFAULT), Charsets.UTF_8)
                    .trim()
                    .trimEnd('/')
            }.getOrNull()
            if (decoded.isNullOrBlank() || !decoded.startsWith("http")) continue
            newTvBase = decoded
            return decoded
        }
        return null
    }

    private suspend fun filterBrokenVariants(
        masterUrl: String,
        manifest: String,
        referer: String
    ): String? {
        val lines = manifest.lines()
        val output = mutableListOf<String>()
        var removed = false
        var validVariants = 0
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                if (line.isNotEmpty()) output.add(line)
                index++
                continue
            }

            val uriIndex = (index + 1 until lines.size).firstOrNull {
                val candidate = lines[it].trim()
                candidate.isNotEmpty() && !candidate.startsWith("#")
            }
            if (uriIndex == null) {
                removed = true
                index++
                continue
            }

            val uri = absoluteUrl(masterUrl, lines[uriIndex].trim())
            val candidateManifest = runCatching {
                app.get(
                    uri,
                    headers = newTvRequestHeaders() + mapOf("Accept" to "*/*"),
                    referer = referer
                ).text
            }.getOrNull().orEmpty()
            if (candidateManifest.contains("#EXTM3U")) {
                output.add(line)
                output.add(uri)
                validVariants++
            } else {
                removed = true
                Log.d(TAG, "Dropping invalid NetMirror variant $uri")
            }
            index = uriIndex + 1
        }

        if (!removed) return null
        if (validVariants == 0) return ""
        return output.joinToString("\n")
    }

    private fun normalizeNewTvLink(link: String): String {
        val marker = "/newtv/hls/nf/"
        val index = link.indexOf(marker)
        if (index < 0) return link
        val tail = link.substring(index + marker.length)
        if (!tail.startsWith("http://") && !tail.startsWith("https://")) return link
        val file = tail.substringAfterLast('/').substringBefore('?')
        if (!file.endsWith(".m3u8", ignoreCase = true)) return link
        return link.substring(0, index + marker.length) + file
    }

    private fun absoluteUrl(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        if (ref.startsWith("//")) return "https:$ref"
        val clean = base.substringBefore('#').substringBefore('?')
        if (ref.startsWith('/')) {
            val scheme = clean.substringBefore("://")
            val host = clean.substringAfter("://").substringBefore('/')
            return "$scheme://$host$ref"
        }
        return clean.substringBeforeLast('/') + "/" + ref
    }

    private fun manifestAttribute(line: String, key: String): String? {
        val quoted = Regex(
            """(?:^|,)\s*${Regex.escape(key)}="([^"]*)"""",
            RegexOption.IGNORE_CASE
        ).find(line)
        if (quoted != null) return quoted.groupValues[1]
        return Regex(
            """(?:^|,)\s*${Regex.escape(key)}=([^,]+)""",
            RegexOption.IGNORE_CASE
        ).find(line)?.groupValues?.getOrNull(1)?.trim()?.trim('"')
    }

    private fun manifestQuality(manifest: String): Int {
        val height = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
            .findAll(manifest)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull() ?: return Qualities.Unknown.value
        return when {
            height >= 2160 -> Qualities.P2160.value
            height >= 1080 -> Qualities.P1080.value
            height >= 720 -> Qualities.P720.value
            height >= 480 -> Qualities.P480.value
            height >= 360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private suspend fun resolveSubtitleUrl(url: String, referer: String): String? {
        val encoded = url.replace("[", "%5B").replace("]", "%5D")
        val path = encoded.substringBefore('?').lowercase()
        if (path.endsWith(".vtt") || path.endsWith(".srt") || path.endsWith(".ass")) {
            return encoded
        }
        val text = runCatching {
            app.get(
                encoded,
                headers = streamRequestHeaders(currentCookies(), referer),
                referer = referer
            ).text
        }.getOrNull() ?: return null
        if (text.trimStart().startsWith("WEBVTT")) return encoded
        if (text.contains("<html", ignoreCase = true)) return null
        val child = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return null
        val resolved = absoluteUrl(encoded, child)
        if (resolved.substringBefore('?').lowercase().endsWith(".m3u8")) return null
        return resolved.replace("[", "%5B").replace("]", "%5D")
    }

    private suspend fun registerNewTvSubtitles(
        masterUrl: String,
        manifest: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val seen = mutableSetOf<String>()
        manifest.lineSequence().forEach { line ->
            if (!line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) ||
                !line.contains("TYPE=SUBTITLES", ignoreCase = true)
            ) return@forEach
            val uri = manifestAttribute(line, "URI") ?: return@forEach
            val label = manifestAttribute(line, "NAME")
                ?: manifestAttribute(line, "LANGUAGE")
                ?: "Subtitle"
            val subUrl = resolveSubtitleUrl(absoluteUrl(masterUrl, uri), referer)
                ?: return@forEach
            if (!seen.add(subUrl)) return@forEach
            subtitleCallback.invoke(
                newSubtitleFile(label, subUrl) {
                    this.headers = streamRequestHeaders(currentCookies(), referer)
                }
            )
        }
    }

    private suspend fun loadLinksNewTv(
        loadData: LoadData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var player: JSONObject? = null
        val tvHeaders = newTvRequestHeaders()
        for (attempt in 0..1) {
            val base = if (attempt == 0) {
                resolveNewTvBase()
            } else {
                resolveNewTvBase(force = true)
            } ?: continue
            player = runCatching {
                val response = app.get(
                    "$base/newtv/player.php?id=${loadData.id}",
                    headers = tvHeaders
                )
                JSONObject(response.text)
            }.getOrNull()
            if (player?.optString("status") == "ok" &&
                player?.optString("video_link")?.isNotBlank() == true
            ) break
        }

        val playerJson = player ?: return false
        val status = playerJson.optString("status")
        val userToken = playerJson.optString("usertoken")
        if (status != "ok") {
            Log.d(
                TAG,
                "NewTV stream requires verification status=$status " +
                    "usertoken=$userToken; rejecting"
            )
            return false
        }
        val rawLink = playerJson.optString("video_link").takeIf { it.isNotBlank() } ?: return false
        val link = normalizeNewTvLink(rawLink)
        Log.d(TAG, "NewTV link raw=$rawLink normalized=$link")
        val referer = playerJson.optString("referer").ifBlank { mainUrl }
        val masterResponse = runCatching {
            app.get(
                link,
                headers = tvHeaders + mapOf("Accept" to "*/*"),
                referer = referer
            )
        }.getOrNull()
        var master = masterResponse?.text.orEmpty()
        var isManifest = master.contains("#EXTM3U")
        if (!isManifest) {
            Log.d(
                TAG,
                "NewTV master invalid code=${masterResponse?.code} " +
                    "type=${masterResponse?.headers?.get("Content-Type")} " +
                    "head=${master.take(240).replace("\n", " ")}"
            )
            val plain = runCatching {
                app.get(link, headers = tvHeaders + mapOf("Accept" to "*/*"))
            }.getOrNull()?.text.orEmpty()
            if (plain.contains("#EXTM3U")) {
                master = plain
                isManifest = true
                Log.d(TAG, "NewTV master recovered without custom headers")
            }
        }
        if (!isManifest) {
            Log.d(TAG, "NewTV manifest unavailable; skipping Auto source")
            return false
        }
        if (master.contains("/220884/")) {
            Log.w(TAG, "NetMirror served abuse punishment video 220884; rejecting NewTV stream")
            return false
        }

        val filtered = filterBrokenVariants(link, master, referer)
        if (filtered != null) {
            Log.d(TAG, "NewTV master has invalid variants; skipping Auto source")
            return false
        }

        lastCookies = currentCookies()
        val streamHeaders = streamRequestHeaders(lastCookies, referer)

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name Auto",
                url = link,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = manifestQuality(master)
                this.headers = streamHeaders
            }
        )
        registerNewTvSubtitles(link, master, referer, subtitleCallback)
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseData(data) ?: return false
        if (loadLinksNewTv(loadData, subtitleCallback, callback)) return true

        val cookies = session()
        lastCookies = cookies

        if (cookies["user_token"].isNullOrBlank()) {
            NetMirrorSettings.showLogin(
                "NetMirror needs a user_token before playback. Log in or play one ad, then save and retry."
            )
            throw ErrorLoadingException(
                "NetMirror playback is locked. Finish the login popup, then retry."
            )
        }

        return loadLinksPlaylist(
            loadData,
            cookies,
            subtitleCallback,
            callback,
            allowLoginPrompt = true
        )
    }

    suspend fun loadLinksForTitle(
        title: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val match = findTitleMatch(title) ?: run {
            Log.d(TAG, "Hybrid lookup skipped: no exact title match for \"$title\"")
            return false
        }
        val id = Regex("""/nf/(\d+)""").find(match.url)?.groupValues?.getOrNull(1)
            ?: return false

        val loadData = if (season != null && episode != null) {
            resolveEpisodeData(id, title, season, episode) ?: run {
                Log.d(TAG, "Hybrid lookup skipped: episode S${season}E$episode missing for \"$title\"")
                return false
            }
        } else {
            LoadData(id, title)
        }

        Log.d(TAG, "Hybrid lookup matched \"$title\" -> $id")
        val cookies = session()
        lastCookies = cookies
        Log.d(TAG, "Hybrid trying legacy playlist for \"$title\"")
        if (loadLinksPlaylist(
                loadData,
                cookies,
                subtitleCallback,
                callback,
                allowLoginPrompt = false
            )
        ) return true

        Log.d(TAG, "Hybrid legacy playlist unavailable; trying NewTV for \"$title\"")
        return loadLinksNewTv(loadData, subtitleCallback, callback)
    }

    private fun isRateLimited(text: String): Boolean =
        text.contains("too many requests", ignoreCase = true)

    private suspend fun nativePlaylist(
        loadData: LoadData,
        cookies: Map<String, String>
    ): Pair<String, Int>? {
        val playHeaders = headers + mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Origin" to NATIVE_ORIGIN,
            "Referer" to NATIVE_REFERER,
            "Cookie" to cookieHeader(cookies),
            "Usertoken" to cookies["user_token"].orEmpty()
        )
        val playResponse = runCatching {
            app.post(
                NATIVE_PLAY_URL,
                data = mapOf("id" to loadData.id),
                headers = playHeaders,
                cookies = cookies,
                referer = NATIVE_REFERER
            )
        }.getOrNull() ?: return null
        val token = runCatching { JSONObject(playResponse.text).optString("h") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null

        val playlistResponse = runCatching {
            app.get(
                NATIVE_PLAYLIST_URL,
                params = mapOf(
                    "id" to loadData.id,
                    "t" to loadData.title,
                    "tm" to now(),
                    "h" to token
                ),
                headers = headers + mapOf(
                    "Origin" to NATIVE_ORIGIN,
                    "Referer" to NATIVE_REFERER,
                    "Cookie" to cookieHeader(cookies),
                    "Usertoken" to cookies["user_token"].orEmpty()
                ),
                cookies = cookies,
                referer = NATIVE_REFERER
            )
        }.getOrNull() ?: return null

        Log.d(
            TAG,
            "Native playlist id=${loadData.id} play=${playResponse.code} " +
                "code=${playlistResponse.code} len=${playlistResponse.text.length} " +
                "hLen=${token.length}"
        )
        return playlistResponse.text to playlistResponse.code
    }

    private suspend fun loadLinksPlaylist(
        loadData: LoadData,
        initialCookies: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        allowLoginPrompt: Boolean
    ): Boolean {
        var cookies = initialCookies
        val playlistParams = mapOf(
            "id" to loadData.id,
            "t" to loadData.title,
            "tm" to now()
        )
        var playlist = ""
        var playlistCode = 0
        var playlistSource = "native"

        val native = nativePlaylist(loadData, cookies)
        if (native != null && native.first.isNotBlank() && !isRateLimited(native.first)) {
            playlist = native.first
            playlistCode = native.second
        } else {
            if (native != null && isRateLimited(native.first)) {
                Log.d(TAG, "Native playlist rate-limited; falling back to mobile flow")
            }
            playlistSource = "mobile"
            var mobileResponse = runCatching {
                app.get(
                    "$mainUrl/mobile/playlist.php",
                    params = playlistParams,
                    headers = headers + mapOf(
                            "Cookie" to cookieHeader(cookies),
                            "Usertoken" to cookies["user_token"].orEmpty()
                        ),
                    referer = "$mainUrl/mobile/home?app=1"
                )
            }.getOrNull() ?: return false
            if (mobileResponse.code == 403 || mobileResponse.text.contains("/verify2")) {
                cookies = session(force = true)
                lastCookies = cookies
                mobileResponse = runCatching {
                    app.get(
                        "$mainUrl/mobile/playlist.php",
                        params = playlistParams,
                        headers = headers + mapOf(
                            "Cookie" to cookieHeader(cookies),
                            "Usertoken" to cookies["user_token"].orEmpty()
                        ),
                        referer = "$mainUrl/mobile/home?app=1"
                    )
                }.getOrNull() ?: return false
            }
            playlist = mobileResponse.text
            playlistCode = mobileResponse.code
        }
        Log.d(
            TAG,
            "Legacy playlist source=$playlistSource id=${loadData.id} t=${loadData.title} " +
                "code=$playlistCode length=${playlist.length} " +
                "cookieLen=${cookieHeader(cookies).length} " +
                "hasToken=${!cookies["user_token"].isNullOrBlank()} " +
                "head=${playlist.take(120).replace("\n", " ")}"
        )
        if (isRateLimited(playlist)) {
            Log.d(TAG, "NetMirror rate limit reached; leaving fallback sources available")
            return false
        }
        if (playlist.isBlank() ||
            playlist.contains("/verify2") ||
            playlist.contains("Just a moment")
        ) {
            if (!allowLoginPrompt) return false
            NetMirrorSettings.showLogin(
                "Your NetMirror session expired. Log in again to unlock playback."
            )
            throw ErrorLoadingException("NetMirror session expired. Complete the login popup, then retry.")
        }

        var array = runCatching { JSONArray(playlist) }.getOrNull()
        if (array == null || array.length() == 0) {
            Log.d(TAG, "Legacy playlist empty; forcing a fresh NetMirror session")
            cookies = session(force = true)
            lastCookies = cookies
            playlist = runCatching {
                app.get(
                    "$mainUrl/mobile/playlist.php",
                    params = playlistParams,
                    headers = headers + mapOf(
                            "Cookie" to cookieHeader(cookies),
                            "Usertoken" to cookies["user_token"].orEmpty()
                        ),
                    referer = "$mainUrl/mobile/home?app=1"
                ).text
            }.getOrNull().orEmpty()
            Log.d(
                TAG,
                "Legacy playlist retry length=${playlist.length} " +
                    "cookieLen=${cookieHeader(cookies).length} " +
                    "hasToken=${!cookies["user_token"].isNullOrBlank()} " +
                    "head=${playlist.take(120).replace("\n", " ")}"
            )
            array = runCatching { JSONArray(playlist) }.getOrNull()
        }
        if (array == null || array.length() == 0) {
            if (!allowLoginPrompt) return false
            NetMirrorSettings.showLogin(
                "Your NetMirror session expired. Log in again to unlock playback."
            )
            throw ErrorLoadingException("NetMirror session expired. Complete the login popup, then retry.")
        }
        val item = array.optJSONObject(0) ?: return false
        val sources = item.optJSONArray("sources") ?: return false

        val validSources = mutableListOf<Pair<JSONObject, String>>()
        val probeCache = mutableMapOf<String, String>()
        var punishment = false
        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            val file = source.optString("file").takeIf { it.isNotBlank() } ?: continue
            val url = if (file.startsWith("http")) file else "$mainUrl$file"
            val probeManifest = probeCache.getOrPut(url) {
                runCatching {
                    app.get(
                        url,
                        headers = streamRequestHeaders(cookies, "$mainUrl/"),
                        referer = "$mainUrl/"
                    ).text
                }.getOrNull().orEmpty()
            }
            if (probeManifest.contains("/220884/") ||
                probeManifest.contains("Only Valid Users Allowed", ignoreCase = true)
            ) {
                Log.w(TAG, "NetMirror served abuse punishment video 220884; skipping $url")
                punishment = true
                continue
            }
            if (probeManifest.isNotBlank() && !probeManifest.contains("#EXTM3U")) {
                Log.w(TAG, "NetMirror source is not an HLS manifest; skipping $url")
                continue
            }
            validSources.add(source to url)
        }
        if (validSources.isEmpty()) {
            if (punishment) {
                Log.w(TAG, "NetMirror served only abuse punishment video 220884; rejecting legacy stream")
                if (allowLoginPrompt) {
                    NetMirrorSettings.showLogin(
                        "NetMirror needs a verified login/ad session. Complete the ad or paste user_token, then retry."
                    )
                }
            }
            return false
        }

        var found = false
        for ((source, url) in validSources) {
            val label = source.optString("label").ifBlank { "NetMirror" }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = if (label == "Auto") name else "$name $label",
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = qualityOf(label)
                    this.headers = streamRequestHeaders(cookies, "$mainUrl/")
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
                        this.headers = streamRequestHeaders(cookies, "$mainUrl/")
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
                host.contains("nm-cdn") ||
                host.contains("subscdn") ||
                host.contains("imgcdn")
            ) {
                builder.header("Origin", mainUrl)
                val cookie = cookieHeader(lastCookies)
                if (cookie.isNotBlank()) builder.header("Cookie", cookie)
                (lastCookies["user_token"] ?: NetMirrorStore.userToken())?.takeIf {
                    it.isNotBlank()
                }?.let { builder.header("Usertoken", it) }
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
        private const val TAG = "NetMirrorProvider"
        private const val NATIVE_ORIGIN = "https://net77.cc"
        private const val NATIVE_REFERER = "https://net77.cc/home"
        private const val NATIVE_PLAY_URL = "https://net77.cc/play.php"
        private const val NATIVE_PLAYLIST_URL = "https://net52.cc/playlist.php"
        private const val SESSION_TTL_MS = 15L * 60L * 60L * 1000L
        private const val VERIFY_COOLDOWN_MS = 60L * 1000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 " +
                "Safari/537.36 /OS.Gatu v3.0"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
        private const val NEWTV_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) " +
                "Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0"
        private val NEWTV_DOMAINS = listOf(
            "https://mobiledetects.com",
            "https://mobiledetect.app",
            "https://mobidetect.art",
            "https://mobidetect.cc",
            "https://mobidetect.click",
            "https://mobidetect.ink",
            "https://mobidetect.live",
            "https://mobidetect.pro",
            "https://mobidetect.shop",
            "https://mobidetect.site",
            "https://mobidetect.space",
            "https://mobidetect.store",
            "https://mobidetect.vip",
            "https://mobidetect.wiki",
            "https://mobidetect.xyz",
            "https://mobiledetects.art",
            "https://mobiledetects.cc",
            "https://mobiledetects.info",
            "https://mobiledetects.ink",
            "https://mobiledetects.live",
            "https://mobiledetects.pro",
            "https://mobiledetects.store",
            "https://mobiledetects.top",
            "https://mobiledetects.xyz"
        )
    }
}
