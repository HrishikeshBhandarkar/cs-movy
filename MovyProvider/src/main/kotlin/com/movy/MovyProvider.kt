package com.movy

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
import android.util.Log
import org.json.JSONObject
import java.net.URLEncoder

class MovyProvider : MainAPI() {
    override var mainUrl = "https://www.movy.sx"
    override var name = "Movy"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true

    override val mainPage = mainPageOf("" to "Home")

    private val bingr = BingrProvider()
    private val netMirror = NetMirrorProvider()

    private data class HomeRow(val title: String, val url: String, val type: String? = null)

    private val homeRows = listOf(
        HomeRow("Trending", "$DB/trending/all/day"),
        HomeRow("Popular Movies", "$DB/movie/popular", "movie"),
        HomeRow("Popular TV Shows", "$DB/tv/popular", "tv"),
        HomeRow("Now Playing in Theaters", "$DB/movie/now_playing", "movie"),
        HomeRow("Netflix", "$DB/discover/tv?with_networks=213", "tv"),
        HomeRow("Disney+", "$DB/discover/tv?with_networks=2739", "tv"),
        HomeRow("HBO", "$DB/discover/tv?with_networks=49", "tv"),
        HomeRow("Top Rated Movies", "$DB/movie/top_rated", "movie"),
        HomeRow("Top Rated TV Shows", "$DB/tv/top_rated", "tv")
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json,text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    private fun JSONObject.str(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.toSearchResponse(defaultType: String? = null): SearchResponse? {
        val type = when {
            has("media_type") -> str("media_type")
            has("mediaType") -> str("mediaType")
            defaultType != null -> defaultType
            has("title") -> "movie"
            has("name") -> "tv"
            else -> {
                val slug = str("slug")
                when {
                    slug?.startsWith("/movie/") == true -> "movie"
                    slug?.startsWith("/tv/") == true -> "tv"
                    else -> null
                }
            }
        } ?: return null

        if (type != "movie" && type != "tv") return null

        var id = optInt("id", 0)
        if (id == 0) {
            val slug = str("slug") ?: return null
            id = slug.trim('/').split('/').lastOrNull()?.toIntOrNull() ?: return null
        }

        val title = str("title") ?: str("name") ?: return null
        val posterPath = str("poster_path")
        val poster = if (!posterPath.isNullOrBlank()) {
            if (posterPath.startsWith("http")) posterPath else "$IMG$posterPath"
        } else {
            str("poster") ?: str("image")
        }

        val date = str("release_date") ?: str("first_air_date")
        val year = date?.take(4)?.toIntOrNull()
        val rating = optDouble("vote_average", optDouble("rating", 0.0))
        val score = if (rating > 0) Score.from10(rating) else null
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList<HomePageList>())

        val sections = mutableListOf<HomePageList>()
        for (row in homeRows) {
            val responseText = runCatching {
                app.get(row.url, headers = headers).text
            }.getOrNull() ?: continue

            val results = runCatching { JSONObject(responseText) }.getOrNull()
                ?.optJSONArray("results") ?: continue

            val items = mutableListOf<SearchResponse>()
            for (i in 0 until minOf(results.length(), 30)) {
                val obj = results.optJSONObject(i) ?: continue
                val item = obj.toSearchResponse(row.type) ?: continue
                items.add(item)
            }
            if (items.isNotEmpty()) sections.add(HomePageList(row.title, items))
        }

        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val responseText = runCatching {
            app.get(
                "$DB/search/multi",
                params = mapOf("query" to query, "page" to "1"),
                headers = headers
            ).text
        }.getOrNull() ?: return emptyList()

        val results = runCatching { JSONObject(responseText) }.getOrNull()
            ?.optJSONArray("results")
            ?: return emptyList()

        val items = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            val item = obj.toSearchResponse() ?: continue
            items.add(item)
        }
        return items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val clean = url.trim()
        val match = Regex("""(?:^|/|\|)(movie|tv)(?:/|\|)(\d+)""").find(clean)
            ?: Regex(""""(?:media_?type|type)"\s*:\s*"(movie|tv)".*?"id"\s*:\s*(\d+)""").find(clean)
            ?: Regex(""""id"\s*:\s*(\d+).*?"(?:media_?type|type)"\s*:\s*"(movie|tv)"""").find(clean)

        val (type, id) = if (match != null) {
            match.groupValues[1] to (match.groupValues[2].toIntOrNull() ?: return null)
        } else {
            val parts = clean.trim('/').split('/')
            val t = parts.firstOrNull { it == "movie" || it == "tv" } ?: return null
            val i = parts.lastOrNull()?.toIntOrNull() ?: return null
            t to i
        }

        val detail = runCatching {
            JSONObject(app.get("$DB/$type/$id?append_to_response=external_ids,images,credits", headers = headers).text)
        }.getOrNull() ?: return null

        val title = detail.str("title") ?: detail.str("name") ?: return null
        val poster = detail.str("poster_path")?.let { "$IMG$it" }
        val backdrop = detail.str("backdrop_path")?.let { "$BACKDROP$it" }
        val plot = detail.str("overview")
        val date = detail.str("release_date") ?: detail.str("first_air_date")
        val year = date?.take(4)?.toIntOrNull()
        val rating = detail.optDouble("vote_average", 0.0)
        val score = if (rating > 0) Score.from10(rating) else null
        val genres = detail.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.str("name") }
        }.orEmpty()

        val actors = detail.optJSONObject("credits")?.optJSONArray("cast")?.let { castArr ->
            (0 until minOf(castArr.length(), 10)).mapNotNull { i ->
                castArr.optJSONObject(i)?.str("name")
            }
        }.orEmpty()

        // Extract transparent logo for movie/series header
        val logos = detail.optJSONObject("images")?.optJSONArray("logos")
        val logoPath = if (logos != null) {
            var enLogo: String? = null
            var firstLogo: String? = null
            for (i in 0 until logos.length()) {
                val obj = logos.optJSONObject(i) ?: continue
                val path = obj.str("file_path") ?: continue
                if (firstLogo == null) firstLogo = path
                val lang = obj.str("iso_639_1")
                if (lang == "en") {
                    enLogo = path
                    break
                }
            }
            enLogo ?: firstLogo
        } else null
        val logoUrl = logoPath?.let { "$IMG$it" }

        val loadUrl = "$mainUrl/$type/$id"

        val encodedTitle = encodeComponent(title)
        val yearStr = year?.toString().orEmpty()

        if (type == "movie") {
            val runtime = detail.optInt("runtime", 0).takeIf { it > 0 }
            return newMovieLoadResponse(title, loadUrl, TvType.Movie, "movie|$id|0|0|$encodedTitle|$yearStr") {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logoUrl
                this.plot = plot
                this.year = year
                this.tags = genres
                this.duration = runtime
                this.score = score
            }
        }

        val episodes = mutableListOf<Episode>()
        val totalSeasons = detail.optInt("number_of_seasons", 0)
        for (season in 1..totalSeasons) {
            val seasonJson = runCatching {
                JSONObject(app.get("$DB/tv/$id/season/$season", headers = headers).text)
            }.getOrNull() ?: continue
            val array = seasonJson.optJSONArray("episodes") ?: continue
            for (i in 0 until array.length()) {
                val ep = array.optJSONObject(i) ?: continue
                val number = ep.optInt("episode_number", 0)
                if (number <= 0) continue
                val epName = ep.str("name") ?: "Episode $number"
                val epPoster = ep.str("still_path")?.let { "$IMG$it" }
                val epPlot = ep.str("overview")
                val epRatingValue = ep.optDouble("vote_average", 0.0)
                val epRating = if (epRatingValue > 0) Score.from10(epRatingValue) else null
                episodes.add(newEpisode(data = "tv|$id|$season|$number|$encodedTitle|$yearStr") {
                    this.name = epName
                    this.season = season
                    this.episode = number
                    this.posterUrl = epPoster
                    this.description = epPlot
                    this.score = epRating
                })
            }
        }

        return newTvSeriesLoadResponse(title, loadUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.logoUrl = logoUrl
            this.plot = plot
            this.year = year
            this.tags = genres
            this.score = score
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks data=$data")
        val type: String
        val id: Int
        var season: Int? = null
        var episode: Int? = null

        if (data.contains("|")) {
            val parts = data.split("|")
            type = parts.getOrNull(0)?.substringAfterLast("/")?.trimEnd('/') ?: return false
            id = parts.getOrNull(1)?.toIntOrNull() ?: return false
            season = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }
            episode = parts.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 }
        } else {
            val match = Regex("""(?:^|/)(movie|tv)/(\d+)""").find(data) ?: return false
            type = match.groupValues[1]
            id = match.groupValues[2].toIntOrNull() ?: return false
        }

        if (type != "movie" && type != "tv") return false
        Log.d(TAG, "parsed type=$type id=$id season=$season episode=$episode")

        val detail = runCatching {
            JSONObject(app.get("$DB/$type/$id?append_to_response=external_ids", headers = headers).text)
        }.onFailure {
            Log.e(TAG, "detail request failed for $type/$id", it)
        }.getOrNull() ?: return false

        val title = detail.str("title") ?: detail.str("name") ?: run {
            Log.e(TAG, "detail missing title for $type/$id")
            return false
        }
        val date = detail.str("release_date") ?: detail.str("first_air_date")
        val year = date?.take(4)?.toIntOrNull()
        val imdbId = detail.optJSONObject("external_ids")?.str("imdb_id")
            ?: detail.str("imdb_id")
        val totalSeasons = detail.optInt("number_of_seasons", 1).coerceAtLeast(1)
        Log.d(TAG, "detail title=$title year=$year imdb=$imdbId seasons=$totalSeasons")

        val seed = runCatching {
            JSONObject(
                app.get(
                    "$API/seed",
                    params = mapOf("mediaId" to id.toString()),
                    headers = headers
                ).text
            ).getString("seed")
        }.onFailure {
            Log.e(TAG, "seed request failed for $id", it)
        }.getOrNull() ?: return false

        val params = mutableMapOf(
            "title" to encodeComponent(title),
            "mediaType" to type,
            "year" to (year?.toString() ?: ""),
            "tmdbId" to id.toString(),
            "imdbId" to (imdbId ?: ""),
            "enc" to "2",
            "seed" to seed
        )
        if (type == "tv") {
            params["totalSeasons"] = totalSeasons.toString()
            params["seasonId"] = (season ?: 1).toString()
            params["episodeId"] = (episode ?: 1).toString()
        }

        val seenSources = mutableSetOf<String>()
        val seenSubtitles = mutableSetOf<String>()
        var foundSource = false

        for (server in SERVERS) {
            val body = runCatching {
                app.get("$API/$server/sources", params = params, headers = headers).text
            }.onFailure {
                Log.e(TAG, "movy $server request failed", it)
            }.getOrNull()
            if (body == null) {
                Log.d(TAG, "movy $server no response")
                continue
            }
            Log.d(TAG, "movy $server response chars=${body.length}")

            val decoded = runCatching { MovyCrypto.decrypt(body, seed, id) }
                .onFailure { Log.e(TAG, "movy $server decrypt failed", it) }
                .getOrNull()
            if (decoded == null) continue
            val json = runCatching { JSONObject(decoded) }
                .onFailure { Log.e(TAG, "movy $server json failed", it) }
                .getOrNull()
            if (json == null) continue

            val sources = json.optJSONArray("sources")
            if (sources == null) {
                Log.d(TAG, "movy $server no sources array")
                continue
            }
            Log.d(TAG, "movy $server sources=${sources.length()}")
            if (sources.length() == 0) continue

            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue
                val sourceUrl = source.str("url") ?: continue
                if (sourceUrl.isBlank() || !seenSources.add(sourceUrl)) continue
                val quality = source.optString("quality")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = if (quality.isBlank()) name else "$name $quality",
                        url = sourceUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = qualityOf(quality)
                    }
                )
                foundSource = true
            }

            val subtitles = json.optJSONArray("subtitles")
            if (subtitles != null) {
                for (i in 0 until subtitles.length()) {
                    val subtitleObj = subtitles.optJSONObject(i) ?: continue
                    val subtitleUrl = subtitleObj.str("url") ?: continue
                    if (subtitleUrl.isBlank() || !seenSubtitles.add(subtitleUrl)) continue
                    val language = subtitleObj.str("lang") ?: subtitleObj.str("language") ?: "Unknown"
                    subtitleCallback.invoke(
                        newSubtitleFile(language, subtitleUrl) {
                            this.headers = mapOf(
                                "Referer" to "$mainUrl/",
                                "User-Agent" to USER_AGENT
                            )
                        }
                    )
                }
            }
            break
        }

        Log.d(TAG, "movy stage done found=$foundSource; starting NetMirror")
        val netMirrorFound = runCatching {
            netMirror.loadLinksForTitle(
                title = title,
                season = season,
                episode = episode,
                subtitleCallback = { subtitle ->
                    if (seenSubtitles.add(subtitle.url)) subtitleCallback.invoke(subtitle)
                },
                callback = { link ->
                    if (seenSources.add(link.url)) {
                        callback.invoke(link)
                        foundSource = true
                    }
                }
            )
        }.onFailure {
            Log.e(TAG, "NetMirror hybrid failed", it)
        }.getOrDefault(false)
        Log.d(TAG, "NetMirror stage done found=$netMirrorFound")

        Log.d(TAG, "starting Bingr")
        val bingrData = if (data.contains("|") && data.split("|").size >= 5) {
            data
        } else {
            val s = season ?: 0
            val e = episode ?: 0
            "$type|$id|$s|$e|${encodeComponent(title)}|${year ?: ""}"
        }
        val bingrFound = runCatching {
            bingr.loadLinks(
                data = bingrData,
                isCasting = isCasting,
                subtitleCallback = { subtitle ->
                    if (seenSubtitles.add(subtitle.url)) subtitleCallback.invoke(subtitle)
                },
                callback = { link ->
                    if (seenSources.add(link.url)) {
                        callback.invoke(link)
                        foundSource = true
                    }
                }
            )
        }.onFailure {
            Log.e(TAG, "Bingr hybrid failed", it)
        }.getOrDefault(false)
        Log.d(TAG, "Bingr stage done found=$bingrFound")

        Log.d(TAG, "final found=$foundSource netMirror=$netMirrorFound bingr=$bingrFound")
        return foundSource || netMirrorFound || bingrFound
    }

    private fun encodeComponent(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun qualityOf(quality: String): Int = when (quality.lowercase()) {
        "2160p", "4k" -> Qualities.P2160.value
        "1080p" -> Qualities.P1080.value
        "720p" -> Qualities.P720.value
        "480p" -> Qualities.P480.value
        "360p" -> Qualities.P360.value
        "240p" -> Qualities.P240.value
        else -> Qualities.Unknown.value
    }

    companion object {
        private const val TAG = "MovyProvider"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val API = "https://api.wecollege.net"
        private const val DB = "https://db.wecollege.net/3"
        private const val IMG = "https://image.tmdb.org/t/p/w500"
        private const val BACKDROP = "https://image.tmdb.org/t/p/w780"
        private val SERVERS = listOf("miami", "boise", "vegas")
    }
}

object MovyCrypto {
    private class State(val s: IntArray, val present: BooleanArray, var acc: Int)

    private fun unsignedMod(value: Int, mod: Int): Int =
        ((value.toLong() and 0xffffffffL) % mod).toInt()

    private fun murmur(value: Int): Int {
        var x = value
        x = x xor (x ushr 16)
        x *= 0x85ebca6b.toInt()
        x = x xor (x ushr 13)
        x *= 0xc2b2ae35.toInt()
        x = x xor (x ushr 16)
        return x
    }

    private fun fnv(value: String): Int {
        var hash = 0x811c9dc5.toInt()
        for (char in value) {
            hash = (hash xor char.code) * 0x1000193
        }
        return murmur(hash)
    }

    private fun buildState(seed: String, mediaId: Int): State {
        val s = IntArray(61)
        val present = BooleanArray(61)
        var n = murmur(fnv(seed) xor murmur(mediaId xor 0x9e3779b9.toInt()))
        for (i in 0 until 8) {
            val index = unsignedMod(n, 61)
            n = Integer.rotateLeft(n + 0x9e3779b9.toInt(), 7 + (7 and i))
            s[index] = n xor murmur(n)
            present[index] = true
            n = murmur(n + index)
        }
        return State(s, present, murmur(0xa5a5a5a5.toInt() xor n))
    }

    private fun nextWord(state: State, counter: Int): Int {
        var acc = state.acc
        val index = unsignedMod(acc, 61)
        val present = state.present[index]
        val value = if (present) state.s[index] else 0
        val mixed = (value xor (0x9e3779b9.toInt() * (counter + 1)))
        var b = if (present) acc or mixed else acc xor mixed
        b = Integer.rotateLeft(b + acc, 31 and index) xor
            Integer.rotateLeft(acc, 31 and (index * 7))
        acc = murmur(b + 0x9e3779b9.toInt())
        state.s[index] = acc
        state.present[index] = true
        state.acc = acc
        return acc
    }

    private fun decodeBase64Url(value: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val lookup = IntArray(128) { -1 }
        alphabet.forEachIndexed { index, char -> lookup[char.code] = index }
        val cleaned = value.trim().trimEnd('=')
        val out = ByteArray((cleaned.length * 3) / 4 + 3)
        var buffer = 0
        var bits = 0
        var length = 0
        for (char in cleaned) {
            val index = if (char.code < 128) lookup[char.code] else -1
            if (index < 0) continue
            buffer = (buffer shl 6) or index
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[length++] = ((buffer shr bits) and 0xff).toByte()
            }
        }
        return out.copyOf(length)
    }

    fun decrypt(body: String, seed: String, mediaId: Int): String {
        val normalized = body.filterNot { it.isWhitespace() }
            .replace('+', '-')
            .replace('/', '_')
        val cipher = decodeBase64Url(normalized)
        val state = buildState(seed, mediaId)
        val out = ByteArray(cipher.size)
        var i = 0
        var counter = 0
        while (i < cipher.size) {
            val word = nextWord(state, counter++)
            out[i] = (cipher[i].toInt() xor (word and 0xff)).toByte()
            i++
            if (i < cipher.size) {
                out[i] = (cipher[i].toInt() xor ((word ushr 8) and 0xff)).toByte()
                i++
            }
            if (i < cipher.size) {
                out[i] = (cipher[i].toInt() xor ((word ushr 16) and 0xff)).toByte()
                i++
            }
            if (i < cipher.size) {
                out[i] = (cipher[i].toInt() xor ((word ushr 24) and 0xff)).toByte()
                i++
            }
        }
        if (out.size < 4 ||
            out[0] != 109.toByte() || out[1] != 118.toByte() ||
            out[2] != 109.toByte() || out[3] != 49.toByte()
        ) {
            throw IllegalStateException("Invalid Movy payload")
        }
        return String(out, 4, out.size - 4, Charsets.UTF_8)
    }
}
