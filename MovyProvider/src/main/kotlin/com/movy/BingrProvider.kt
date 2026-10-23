package com.movy

import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
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
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

class BingrProvider : MainAPI() {
    override var mainUrl = "https://bingr.one"
    override var name = "Bingr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true

    override val mainPage = mainPageOf("" to "Home")

    private data class Server(val id: String, val name: String)

    private data class LinkData(
        val type: String,
        val id: Int,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
        val year: Int? = null
    )

    private data class StreamResult(val server: Server, val json: JSONObject)

    private val servers = listOf(
        Server("s70", "Polaris"),
        Server("s40", "Aphelion"),
        Server("s80", "Moscow"),
        Server("s81", "Novo"),
        Server("s62", "Bastion")
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/"
    )

    private fun JSONObject.str(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.toSearchResponse(defaultType: String? = null): SearchResponse? {
        val type = str("type") ?: str("media_type") ?: defaultType ?: return null
        if (type != "movie" && type != "tv") return null

        val id = optInt("id", 0)
        if (id <= 0) return null

        val title = str("title") ?: str("name") ?: return null
        val year = str("year")?.toIntOrNull()
            ?: str("release_date")?.take(4)?.toIntOrNull()
        val rating = optDouble("rating", optDouble("vote_average", 0.0))
        val score = if (rating > 0) Score.from10(rating) else null
        val poster = str("poster")
        val href = "$mainUrl/$type/$id"

        return if (type == "movie") {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        }
    }

    private suspend fun fetchResults(path: String, page: Int): List<SearchResponse> {
        val results = runCatching {
            JSONObject(
                app.get(
                    path,
                    params = mapOf("page" to page.toString()),
                    headers = headers
                ).text
            ).optJSONArray("results")
        }.getOrNull() ?: return emptyList()

        val items = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            items.add(obj.toSearchResponse() ?: continue)
        }
        return items
    }

    private suspend fun fetchDetail(type: String, id: Int): JSONObject? {
        val response = runCatching {
            app.get(
                "$API/details/$type/$id",
                params = mapOf("v" to "1"),
                headers = headers
            )
        }.onFailure {
            Log.e(TAG, "detail $type/$id failed", it)
        }.getOrNull() ?: return null
        Log.d(TAG, "detail $type/$id code=${response.code} length=${response.text.length}")
        return runCatching { JSONObject(response.text) }
            .onFailure {
                Log.e(TAG, "detail $type/$id invalid JSON: ${response.text.take(200)}", it)
            }
            .getOrNull()
    }

    private fun parseLinkData(data: String): LinkData? {
        if (data.contains("|")) {
            val parts = data.split("|")
            val type = parts.getOrNull(0)
                ?.substringAfterLast("/")
                ?.trim()
                ?.takeIf { it == "movie" || it == "tv" }
                ?: return null
            val id = parts.getOrNull(1)?.toIntOrNull() ?: return null
            val season = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }
            val episode = parts.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 }
            val rawTitle = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
            val title = rawTitle?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            val year = parts.getOrNull(5)?.toIntOrNull()
            return LinkData(
                type = type,
                id = id,
                season = season,
                episode = episode,
                title = title,
                year = year
            )
        }

        val match = Regex("""(?:^|/)(movie|tv)/(\d+)(?:/(\d+)/(\d+))?""").find(data)
            ?: return null
        return LinkData(
            type = match.groupValues[1],
            id = match.groupValues[2].toIntOrNull() ?: return null,
            season = match.groupValues[3].toIntOrNull(),
            episode = match.groupValues[4].toIntOrNull()
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rows = listOf(
            "Trending" to "$API/trending/all",
            "Movies" to "$API/trending/movie",
            "TV Shows" to "$API/trending/tv"
        )

        val sections = rows.mapNotNull { (title, path) ->
            val items = fetchResults(path, page)
            if (items.isEmpty()) null else HomePageList(title, items)
        }

        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = runCatching {
            JSONObject(
                app.get(
                    "$API/search",
                    params = mapOf("q" to query, "type" to "multi"),
                    headers = headers
                ).text
            ).optJSONArray("results")
        }.getOrNull() ?: return emptyList()

        val items = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            items.add(obj.toSearchResponse() ?: continue)
        }
        return items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val link = parseLinkData(url) ?: return null
        val detail = fetchDetail(link.type, link.id) ?: return null

        val title = detail.str("title") ?: detail.str("name") ?: return null
        val type = detail.str("type") ?: link.type
        val year = detail.str("year")?.toIntOrNull()
        val poster = detail.str("poster")
        val backdrop = detail.str("backdrop") ?: detail.str("backdrop_original")
        val plot = detail.str("overview")
        val certification = detail.str("certification")
        val rating = detail.optDouble("rating", 0.0)
        val score = if (rating > 0) Score.from10(rating) else null
        val logo = detail.str("logo")?.takeIf { it.endsWith(".png", true) || it.endsWith(".jpg", true) }
        val runtime = detail.optInt("runtime", 0).takeIf { it > 0 }

        val genres = detail.optJSONArray("genres")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { name -> name.isNotBlank() } }
        }.orEmpty()

        val actors = detail.optJSONArray("cast")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val actor = array.optJSONObject(index) ?: return@mapNotNull null
                val actorName = actor.str("name") ?: return@mapNotNull null
                ActorData(
                    actor = Actor(actorName, actor.str("photo")),
                    roleString = actor.str("character")
                )
            }
        }.orEmpty()

        val recommendations = detail.optJSONArray("similars")?.let { array ->
            (0 until minOf(array.length(), 12)).mapNotNull { index ->
                array.optJSONObject(index)?.toSearchResponse()
            }
        }.orEmpty()

        val loadUrl = "$mainUrl/$type/${link.id}"
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val yearStr = year?.toString().orEmpty()

        if (type == "movie") {
            return newMovieLoadResponse(title, loadUrl, TvType.Movie, "movie|${link.id}|0|0|$encodedTitle|$yearStr") {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logo
                this.plot = plot
                this.year = year
                this.tags = genres
                this.actors = actors
                this.recommendations = recommendations
                this.duration = runtime
                this.contentRating = certification
                this.score = score
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasons = detail.optJSONArray("seasons")
        if (seasons != null) {
            for (i in 0 until seasons.length()) {
                val seasonObj = seasons.optJSONObject(i) ?: continue
                val season = seasonObj.optInt("season", 0)
                if (season < 0) continue

                val seasonEpisodes = runCatching {
                    JSONObject(
                        app.get(
                            "$API/episodes/${link.id}/$season",
                            headers = headers
                        ).text
                    ).optJSONArray("episodes")
                }.getOrNull()

                if (seasonEpisodes != null) {
                    for (j in 0 until seasonEpisodes.length()) {
                        val ep = seasonEpisodes.optJSONObject(j) ?: continue
                        val number = ep.optInt("episode", 0)
                        if (number <= 0) continue
                        val episodeRating = ep.optDouble("rating", 0.0)
                        episodes.add(newEpisode(data = "tv|${link.id}|$season|$number|$encodedTitle|$yearStr") {
                            this.name = ep.str("title") ?: "Episode $number"
                            this.season = season
                            this.episode = number
                            this.posterUrl = ep.str("still")
                            this.description = ep.str("overview")
                            this.score = if (episodeRating > 0) Score.from10(episodeRating) else null
                        })
                    }
                } else {
                    val count = seasonObj.optInt("episodes", 0)
                    for (number in 1..count) {
                        episodes.add(newEpisode(data = "tv|${link.id}|$season|$number|$encodedTitle|$yearStr") {
                            this.name = "Episode $number"
                            this.season = season
                            this.episode = number
                            this.posterUrl = seasonObj.str("poster")
                        })
                    }
                }
            }
        }

        return newTvSeriesLoadResponse(title, loadUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.logoUrl = logo
            this.plot = plot
            this.year = year
            this.tags = genres
            this.actors = actors
            this.recommendations = recommendations
            this.contentRating = certification
            this.score = score
        }
    }

    private suspend fun fetchStream(
        server: Server,
        type: String,
        id: Int,
        query: JSONObject
    ): JSONObject? {
        if (server.id == "s40" && type == "tv") {
            val season = query.optString("season", "1")
            val episode = query.optString("episode", "1")
            val responseText = runCatching {
                app.get(
                    "$API/stream/aphelion-tv/$id/$season/$episode",
                    headers = headers
                ).text
            }.onFailure {
                Log.e(TAG, "GET ${server.id} failed", it)
            }.getOrNull() ?: return null
            return runCatching { JSONObject(responseText) }
                .onFailure {
                    Log.e(TAG, "Invalid JSON from ${server.id}: ${responseText.take(300)}", it)
                }
                .getOrNull()
        }

        val body = JSONObject()
            .put("srv", server.id)
            .put("t", type)
            .put("id", id.toString())
            .put("query", JSONObject(query.toString()))
            .toString()
            .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        val responseText = runCatching {
            app.post(
                "$API/stream",
                headers = headers,
                requestBody = body
            ).text
        }.onFailure {
            Log.e(TAG, "POST ${server.id} failed", it)
        }.getOrNull() ?: return null

        return runCatching { JSONObject(responseText) }
            .onFailure {
                Log.e(TAG, "Invalid JSON from ${server.id}: ${responseText.take(300)}", it)
            }
            .getOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = parseLinkData(data) ?: run {
            Log.d(TAG, "Unable to parse link data: $data")
            return false
        }
        val (title, year) = if (!link.title.isNullOrBlank()) {
            link.title to link.year
        } else {
            val detail = fetchDetail(link.type, link.id)
            val dTitle = detail?.str("title") ?: detail?.str("name")
            val dYear = detail?.str("year")?.toIntOrNull()
            dTitle to dYear
        }
        if (title.isNullOrBlank()) {
            Log.d(TAG, "Title unavailable for ${link.type}/${link.id}")
            return false
        }

        val query = JSONObject()
            .put("title", title)
        year?.let { query.put("year", it.toString()) }
        if (link.type == "tv") {
            query.put("season", (link.season ?: 1).toString())
            query.put("episode", (link.episode ?: 1).toString())
        }

        val results = servers.mapNotNull { server ->
            runCatching {
                fetchStream(server, link.type, link.id, query)
            }.getOrNull()?.let { StreamResult(server, it) }
        }
        Log.d(
            TAG,
            "Results for ${link.type}/${link.id}: " + results.joinToString { result ->
                "${result.server.id}=${result.json.optJSONArray("sources")?.length() ?: 0}"
            }
        )

        val seenSources = mutableSetOf<String>()
        val seenSubtitles = mutableSetOf<String>()
        var foundSource = false

        for (result in results) {
            val sources = result.json.optJSONArray("sources")
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val source = sources.optJSONObject(i) ?: continue
                    val sourceUrl = source.str("url")?.trim() ?: continue
                    if (sourceUrl.isBlank() || !seenSources.add(sourceUrl)) continue

                    val qualityText = source.str("quality") ?: ""
                    val sourceType = source.str("type") ?: ""
                    val label = source.str("label") ?: source.str("name") ?: qualityText
                    val linkType = if (
                        sourceUrl.contains(".m3u8", true) ||
                        sourceType.contains("mpegurl", true)
                    ) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }

                    val linkName = buildString {
                        append(result.server.name)
                        if (label.isNotBlank() && !label.equals(result.server.name, true)) {
                            append(" • ")
                            append(label)
                        } else if (qualityText.isNotBlank()) {
                            append(" • ")
                            append(qualityText)
                        }
                    }

                    val sourceHeaders: Map<String, String> = source.optJSONObject("headers")?.let { headerObj ->
                        val map = mutableMapOf<String, String>()
                        val keys = headerObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val value = headerObj.optString(key)
                            if (value.isNotBlank()) map[key] = value
                        }
                        map
                    }.orEmpty()

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = linkName,
                            url = sourceUrl,
                            type = linkType
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = qualityOf(qualityText)
                            if (sourceHeaders.isNotEmpty()) this.headers = sourceHeaders
                        }
                    )
                    foundSource = true
                }
            }

            val subtitles = result.json.optJSONArray("subtitles")
            if (subtitles != null) {
                for (i in 0 until subtitles.length()) {
                    val subtitle = subtitles.optJSONObject(i) ?: continue
                    val subtitleUrl = subtitle.str("url")?.trim() ?: continue
                    if (subtitleUrl.isBlank() || !seenSubtitles.add(subtitleUrl)) continue

                    val label = subtitle.str("label") ?: subtitle.str("lang") ?: "Unknown"
                    subtitleCallback.invoke(
                        newSubtitleFile(label, subtitleUrl) {
                            this.headers = mapOf(
                                "Referer" to "$mainUrl/",
                                "User-Agent" to USER_AGENT
                            )
                        }
                    )
                }
            }
        }

        return foundSource
    }

    private fun qualityOf(quality: String): Int {
        if (quality.contains("4k", true)) return Qualities.P2160.value
        val number = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
            .find(quality)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return when (number) {
            2160 -> Qualities.P2160.value
            1440 -> Qualities.P1440.value
            1080 -> Qualities.P1080.value
            720 -> Qualities.P720.value
            480 -> Qualities.P480.value
            360 -> Qualities.P360.value
            240 -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    companion object {
        private const val TAG = "BingrProvider"
        private const val API = "https://api.bingr.one/api"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
