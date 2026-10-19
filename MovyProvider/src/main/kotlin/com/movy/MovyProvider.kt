package com.movy

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
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
import org.json.JSONObject
import java.net.URLEncoder

class MovyProvider : MainAPI() {
    override var mainUrl = "https://www.movy.sx"
    override var name = "Movy"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true
    override val mainPage = listOf(MainPageData("Home", "", false))

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    private fun JSONObject.str(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.searchResponse(): SearchResponse? {
        val type = when (str("mediaType")) {
            "movie" -> "movie"
            "tv" -> "tv"
            else -> {
                val slug = str("slug") ?: return null
                when {
                    slug.startsWith("/movie/") -> "movie"
                    slug.startsWith("/tv/") -> "tv"
                    else -> return null
                }
            }
        }

        var id = optInt("id", 0)
        if (id == 0) {
            val slug = str("slug") ?: return null
            id = slug.trim('/').split('/').lastOrNull()?.toIntOrNull() ?: return null
        }

        val title = str("title") ?: str("name") ?: return null
        val poster = str("poster") ?: str("image")
        val date = str("release_date") ?: str("first_air_date")
        val year = date?.take(4)?.toIntOrNull()
        val rating = optDouble("rating", 0.0)
        val score = if (rating > 0) Score.from10(rating) else null

        return if (type == "movie") {
            newMovieSearchResponse(title, "movie/$id", TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        } else {
            newTvSeriesSearchResponse(title, "tv/$id", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList<HomePageList>())
        val document = app.get("$mainUrl/", headers = headers).document
        val nextData = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return newHomePageResponse(emptyList())
        val pageProps = JSONObject(nextData)
            .optJSONObject("props")
            ?.optJSONObject("pageProps")
            ?: return newHomePageResponse(emptyList())

        val sections = mutableListOf<HomePageList>()
        val seen = mutableSetOf<String>()

        fun addSection(sectionName: String, array: org.json.JSONArray?) {
            if (array == null) return
            val name = sectionName.replace('_', ' ').replaceFirstChar { it.uppercase() }
            if (!seen.add(name)) return
            val items = mutableListOf<SearchResponse>()
            for (i in 0 until minOf(array.length(), 30)) {
                val item = array.optJSONObject(i)?.searchResponse() ?: continue
                items.add(item)
            }
            if (items.isNotEmpty()) sections.add(HomePageList(name, items))
        }

        for (key in listOf("trendingSections", "defaultSections", "streamingSections")) {
            val groups = pageProps.optJSONArray(key) ?: continue
            for (i in 0 until groups.length()) {
                val group = groups.optJSONObject(i) ?: continue
                addSection(group.optString("name"), group.optJSONArray("movies"))
            }
        }
        addSection("Upcoming TV", pageProps.optJSONArray("upcomingTv"))
        addSection("Recently Added 4K", pageProps.optJSONArray("recent4k"))

        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "$DB/search/multi",
            params = mapOf("query" to query, "page" to "1"),
            headers = headers
        )
        val results = runCatching { JSONObject(response.text) }.getOrNull()
            ?.optJSONArray("results")
            ?: return emptyList()

        val items = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            val type = obj.str("media_type") ?: continue
            if (type != "movie" && type != "tv") continue
            val id = obj.optInt("id", 0)
            if (id == 0) continue
            val title = if (type == "movie") obj.str("title") else obj.str("name")
            if (title.isNullOrBlank()) continue
            val date = if (type == "movie") obj.str("release_date") else obj.str("first_air_date")
            val year = date?.take(4)?.toIntOrNull()
            val poster = obj.str("poster_path")?.let { "$IMG$it" }
            val rating = obj.optDouble("vote_average", 0.0)
            val score = if (rating > 0) Score.from10(rating) else null

            items.add(
                if (type == "movie") {
                    newMovieSearchResponse(title, "movie/$id", TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                        this.score = score
                    }
                } else {
                    newTvSeriesSearchResponse(title, "tv/$id", TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                        this.score = score
                    }
                }
            )
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        val match = Regex("(movie|tv)/(\\d+)").find(url) ?: return null
        val type = match.groupValues[1]
        val id = match.groupValues[2].toIntOrNull() ?: return null

        val detail = runCatching {
            JSONObject(app.get("$DB/$type/$id?append_to_response=external_ids", headers = headers).text)
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

        if (type == "movie") {
            val runtime = detail.optInt("runtime", 0).takeIf { it > 0 }
            return newMovieLoadResponse(title, url, TvType.Movie, "movie|$id") {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
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
                episodes.add(newEpisode(data = "tv|$id|$season|$number") {
                    this.name = epName
                    this.season = season
                    this.episode = number
                    this.posterUrl = epPoster
                    this.description = epPlot
                    this.score = epRating
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
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
        val parts = data.split("|")
        if (parts.size < 2) return false
        val type = parts[0]
        val id = parts[1].toIntOrNull() ?: return false
        if (type != "movie" && type != "tv") return false
        val season = parts.getOrNull(2)?.toIntOrNull()
        val episode = parts.getOrNull(3)?.toIntOrNull()

        val detail = runCatching {
            JSONObject(app.get("$DB/$type/$id?append_to_response=external_ids", headers = headers).text)
        }.getOrNull() ?: return false

        val title = detail.str("title") ?: detail.str("name") ?: return false
        val date = detail.str("release_date") ?: detail.str("first_air_date")
        val year = date?.take(4)?.toIntOrNull()
        val imdbId = detail.optJSONObject("external_ids")?.str("imdb_id")
            ?: detail.str("imdb_id")
        val totalSeasons = detail.optInt("number_of_seasons", 1).coerceAtLeast(1)

        val seed = runCatching {
            JSONObject(
                app.get(
                    "$API/seed",
                    params = mapOf("mediaId" to id.toString()),
                    headers = headers
                ).text
            ).getString("seed")
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

        for (server in SERVERS) {
            val body = runCatching {
                app.get("$API/$server/sources", params = params, headers = headers).text
            }.getOrNull() ?: continue

            val decoded = runCatching { MovyCrypto.decrypt(body, seed, id) }.getOrNull() ?: continue
            val json = runCatching { JSONObject(decoded) }.getOrNull() ?: continue

            val sources = json.optJSONArray("sources") ?: continue
            if (sources.length() == 0) continue

            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue
                val sourceUrl = source.str("url") ?: continue
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
            }

            val subtitles = json.optJSONArray("subtitles")
            if (subtitles != null) {
                for (i in 0 until subtitles.length()) {
                    val subtitleObj = subtitles.optJSONObject(i) ?: continue
                    val subtitleUrl = subtitleObj.str("url") ?: continue
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
            return true
        }
        return false
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
