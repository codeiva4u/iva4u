package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.text.Normalizer


class HDhub4uProvider : MainAPI() {
    companion object {
        private const val TAG = "HDhub4uProvider"
        
        // TMDB API Configuration
        const val TMDBAPI = "https://api.themoviedb.org/3"
        const val TMDBAPIKEY = "1d0d243a7ee844813558da9bbfc80e5c"
        const val TMDBBASE = "https://image.tmdb.org/t/p/original"
    }
    
    // Data classes for TMDB responses
    data class IMDB(val imdbId: String? = null)
    
    data class VideoLocal(
        val title: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val overview: String? = null,
        val thumbnail: String? = null,
        val released: String? = null,
        val rating: Score? = null
    )
    
    data class MetaLocal(
        val name: String? = null,
        val description: String? = null,
        val actorsData: List<ActorData>? = null,
        val year: String? = null,
        val background: String? = null,
        val genres: List<String>? = null,
        val videos: List<VideoLocal>? = null,
        val rating: Score? = null,
        val logo: String? = null
    )
    
    data class ResponseDataLocal(val meta: MetaLocal? = null)

    // Cached domain URL - fetched once per session (async, no blocking)
    private var cachedMainUrl: String? = null
    private var urlsFetched = false
    
    override var mainUrl: String = "https://new2.hdhub4u.fo"

    // Async domain fetch with 10s timeout - no blocking
    private suspend fun fetchMainUrl(): String {
        if (cachedMainUrl != null) return cachedMainUrl!!
        if (urlsFetched) return mainUrl
        
        urlsFetched = true
        try {
            val result = withTimeoutOrNull(10_000L) {
                val response = app.get(
                    "https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json",
                    timeout = 10
                )
                val json = response.text
                val jsonObject = JSONObject(json)
                jsonObject.optString("hdhub4u").takeIf { it.isNotBlank() }
            }
            if (result != null) {
                cachedMainUrl = result
                mainUrl = result
                Log.d(TAG, "Fetched mainUrl: $result")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch mainUrl: ${e.message}")
        }
        return mainUrl
    }

    override var name = "HDHub4U"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries ,TvType.Anime
    )

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/bollywood-movies/" to "Bollywood",
        "category/hollywood-movies/" to "Hollywood",
        "category/hindi-dubbed/" to "Hindi Dubbed",
        "category/south-hindi-movies/" to "South Hindi Dubbed",
        "category/web-series/" to "Web Series",
    )
    private val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0","Cookie" to "xla=s4t")

override suspend fun getMainPage(
    page: Int,
    request: MainPageRequest
): HomePageResponse {
    // Fetch latest mainUrl (async, cached)
    fetchMainUrl()
    
    val url = if (page == 1) {
        "$mainUrl/${request.data}"
    } else {
        "$mainUrl/${request.data}page/$page/"
    }

    Log.d(TAG, "Loading main page: $url")
    val document = app.get(url, headers = headers, timeout = 20).document

    // Correct selector: li.thumb contains movie items
    val home = document.select("li.thumb").mapNotNull {
        it.toSearchResult()
    }

    Log.d(TAG, "Found ${home.size} items")

    return newHomePageResponse(request.name, home)
}

private fun Element.toSearchResult(): SearchResponse? {
    // Structure: li.thumb > figure > a[href] for link, img for poster
    // figcaption > a > p for title

    // Extract link from figure > a or figcaption > a
    val linkElement = selectFirst("figure a[href]")
        ?: selectFirst("figcaption a[href]")
        ?: selectFirst("a[href]")

    val href = linkElement?.attr("href") ?: return null
    if (href.isBlank() || href.contains("category") || href.contains("page/")) return null

    val fixedUrl = fixUrl(href)

    // Extract title from figcaption p, or img alt, or a title
    val titleText = selectFirst("figcaption p")?.text()
        ?: selectFirst("figcaption a")?.text()
        ?: selectFirst("img")?.attr("alt")
        ?: selectFirst("img")?.attr("title")
        ?: selectFirst("a")?.attr("title")
        ?: ""

    // Clean title using Regex
    val title = cleanTitle(titleText)
    if (title.isBlank()) return null

    // Extract poster from figure img
    val posterUrl = selectFirst("figure img, img")?.let { img ->
        val src = img.attr("src").ifBlank {
            img.attr("data-src").ifBlank {
                img.attr("data-lazy-src")
            }
        }
        fixUrlNull(src)
    }

    // Determine type using Regex pattern
    val isSeries = Regex("(?i)(Season|S0\\d|Episode|E0\\d|Complete|All\\s+Episodes)").containsMatchIn(titleText)

    return if (isSeries) {
        newTvSeriesSearchResponse(title, fixedUrl, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    } else {
        newMovieSearchResponse(title, fixedUrl, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }
}

override suspend fun search(query: String): List<SearchResponse> {
    Log.d(TAG, "Searching for: $query")

    val results = mutableListOf<SearchResponse>()
    val searchTerms = query.lowercase().split(" ").filter { it.length > 2 }

    // Since Typesense API is unreliable and website uses JS search,
    // we fetch home page and filter results by title matching
    try {
        // Fetch first 3 pages of content to search through
        for (page in 1..3) {
            val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
            val document = app.get(url, headers = headers, timeout = 30).document

            document.select("li.thumb").forEach { element ->
                val searchResult = element.toSearchResult()
                if (searchResult != null) {
                    // Check if title matches search query
                    val title = searchResult.name.lowercase()
                    val matches = searchTerms.any { term -> title.contains(term) }

                    if (matches && results.none { it.url == searchResult.url }) {
                        results.add(searchResult)
                    }
                }
            }

            // If we found enough results, stop
            if (results.size >= 20) break
        }
    } catch (e: Exception) {
        Log.e(TAG, "Search error: ${e.message}")
    }

    Log.d(TAG, "Found ${results.size} search results")
    return results
}

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, cacheTime = 60, headers = headers).documentLarge
        var title = doc.select(
            ".page-body h2[data-ved=\"2ahUKEwjL0NrBk4vnAhWlH7cAHRCeAlwQ3B0oATAfegQIFBAM\"], " +
                    "h2[data-ved=\"2ahUKEwiP0pGdlermAhUFYVAKHV8tAmgQ3B0oATAZegQIDhAM\"]"
        ).text()
        val seasontitle = title
        val seasonNumber = Regex("(?i)\\bSeason\\s*(\\d+)\\b").find(seasontitle)?.groupValues?.get(1)?.toIntOrNull()

        val image = doc.select("meta[property=og:image]").attr("content")
        val plot = doc.selectFirst(".kno-rdesc .kno-rdesc")?.text()
        val tags = doc.select(".page-meta em").eachText().toMutableList()
        val poster = doc.select("main.page-body img.aligncenter").attr("src")
        val trailer = doc.selectFirst(".responsive-embed-container > iframe:nth-child(1)")?.attr("src")
            ?.replace("/embed/", "/watch?v=")

        val typeraw = doc.select("h1.page-title span").text()
        val tvtype = if (typeraw.contains("movie", ignoreCase = true)) TvType.Movie else TvType.TvSeries
        val isMovie = tvtype == TvType.Movie

        var actorData: List<ActorData> = emptyList()
        var genre: List<String>? = null
        var year = ""
        var background: String = image
        var description: String? = null

        val imdbUrl = doc.select("div span a[href*='imdb.com']").attr("href")
            .ifEmpty {
                val tmdbHref = doc.select("div span a[href*='themoviedb.org']").attr("href")
                val isTv = tmdbHref.contains("/tv/")
                val tmdbId = tmdbHref.substringAfterLast("/").substringBefore("-").substringBefore("?")

                if (tmdbId.isNotEmpty()) {
                    val type = if (isTv) "tv" else "movie"
                    val imdbId = app.get(
                        "$TMDBAPI/$type/$tmdbId/external_ids?api_key=$TMDBAPIKEY"
                    ).parsedSafe<IMDB>()?.imdbId
                    imdbId ?: ""
                } else {
                    ""
                }
            }

        var tmdbIdResolved = ""
        run {
            val tmdbHref = doc.select("div span a[href*='themoviedb.org']").attr("href")
            if (tmdbHref.isNotBlank()) {
                tmdbIdResolved = tmdbHref.substringAfterLast("/").substringBefore("-").substringBefore("?")
            }
        }

        if (tmdbIdResolved.isBlank() && imdbUrl.isNotBlank()) {
            val imdbIdOnly = imdbUrl.substringAfter("title/").substringBefore("/")

            try {
                val findJson = JSONObject(
                    app.get(
                        "$TMDBAPI/find/$imdbIdOnly" +
                                "?api_key=$TMDBAPIKEY&external_source=imdb_id"
                    ).text
                )

                tmdbIdResolved = if (isMovie) {
                    findJson
                        .optJSONArray("movie_results")
                        ?.optJSONObject(0)
                        ?.optInt("id")
                        ?.toString()
                        .orEmpty()
                } else {
                    findJson
                        .optJSONArray("tv_results")
                        ?.optJSONObject(0)
                        ?.optInt("id")
                        ?.toString()
                        .orEmpty()
                }
            } catch (_: Exception) {
                // ignore resolve errors
            }
        }


        val responseData: ResponseDataLocal? = if (tmdbIdResolved.isBlank()) null else runCatching {

            val type = if (tvtype == TvType.TvSeries) "tv" else "movie"
            val detailsText = app.get(
                "$TMDBAPI/$type/$tmdbIdResolved?api_key=$TMDBAPIKEY&append_to_response=credits,external_ids"
            ).text
            val detailsJson = if (detailsText.isNotBlank()) JSONObject(detailsText) else JSONObject()

            var metaName = detailsJson.optString("name")
                .takeIf { it.isNotBlank() }
                ?: detailsJson.optString("title").takeIf { it.isNotBlank() }
                ?: title

            if (seasonNumber != null && !metaName.contains("Season $seasonNumber", ignoreCase = true)) {
                metaName = "$metaName (Season $seasonNumber)"
            }

            val metaDesc = detailsJson.optString("overview").takeIf { it.isNotBlank() } ?: plot

            val yearRaw = detailsJson.optString("release_date").ifBlank { detailsJson.optString("first_air_date") }
            val metaYear = yearRaw.takeIf { it.isNotBlank() }?.take(4)
            val metaRating = detailsJson.optString("vote_average")

            val metaBackground = detailsJson.optString("backdrop_path")
                .takeIf { it.isNotBlank() }?.let { TMDBBASE + it } ?: image

            val imdbid = detailsJson
                .optJSONObject("external_ids")
                ?.optString("imdb_id")
                ?.takeIf { it.isNotBlank() }

            val logoPath = imdbid?.let {
                "https://live.metahub.space/logo/medium/$it/img"
            }
            val actorDataList = mutableListOf<ActorData>()

            //cast
            detailsJson.optJSONObject("credits")?.optJSONArray("cast")?.let { castArr ->
                for (i in 0 until castArr.length()) {
                    val c = castArr.optJSONObject(i) ?: continue
                    val name = c.optString("name").takeIf { it.isNotBlank() } ?: c.optString("original_name").orEmpty()
                    val profile = c.optString("profile_path").takeIf { it.isNotBlank() }?.let { TMDBBASE + it }
                    val character = c.optString("character").takeIf { it.isNotBlank() }
                    val actor = Actor(name, profile)
                    actorDataList += ActorData(actor = actor, roleString = character)
                }
            }

            // genres
            val metaGenres = mutableListOf<String>()
            detailsJson.optJSONArray("genres")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(metaGenres::add)
                }
            }

            // episodes for TV season -> videos
            val videos = mutableListOf<VideoLocal>()
            if (tvtype == TvType.TvSeries && seasonNumber != null) {
                try {
                    val seasonText = app.get("$TMDBAPI/tv/$tmdbIdResolved/season/$seasonNumber?api_key=$TMDBAPIKEY").text
                    if (seasonText.isNotBlank()) {
                        val seasonJson = JSONObject(seasonText)
                        seasonJson.optJSONArray("episodes")?.let { epArr ->
                            for (i in 0 until epArr.length()) {
                                val ep = epArr.optJSONObject(i) ?: continue
                                val epNum = ep.optInt("episode_number")
                                val epName = ep.optString("name")
                                val epDesc = ep.optString("overview")
                                val epThumb = ep.optString("still_path").takeIf { it.isNotBlank() }?.let { TMDBBASE + it }
                                val epAir = ep.optString("air_date")
                                val epRating = ep.optString("vote_average").let { Score.from10(it.toString()) }

                                videos.add(
                                    VideoLocal(
                                        title = epName,
                                        season = seasonNumber,
                                        episode = epNum,
                                        overview = epDesc,
                                        thumbnail = epThumb,
                                        released = epAir,
                                        rating = epRating,
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                    // ignore season fetch errors
                }
            }

            ResponseDataLocal(
                MetaLocal(
                    name = metaName,
                    description = metaDesc,
                    actorsData = actorDataList.ifEmpty { null },
                    year = metaYear,
                    background = metaBackground,
                    genres = metaGenres.ifEmpty { null },
                    videos = videos.ifEmpty { null },
                    rating = Score.from10(metaRating),
                    logo = logoPath
                )
            )
        }.getOrNull()

        if (responseData != null) {
            description = responseData.meta?.description ?: plot
            actorData = responseData.meta?.actorsData ?: emptyList()
            title = responseData.meta?.name ?: title
            year = responseData.meta?.year ?: ""
            background = responseData.meta?.background ?: image
            responseData.meta?.genres?.let { g ->
                genre = g
                for (gn in g) if (!tags.contains(gn)) tags.add(gn)
            }
            responseData.meta?.rating
        }

        if (tvtype == TvType.Movie) {
            val movieList = mutableListOf<String>()
            movieList.addAll(
                doc.select("h3 a:matches(480|720|1080|2160|4K), h4 a:matches(480|720|1080|2160|4K)")
                    .map { it.attr("href") } + extractLinksATags(doc.select(".page-body > div a"))
            )

            return newMovieLoadResponse(title, url, TvType.Movie, movieList) {
                this.backgroundPosterUrl = background
                try { this.logoUrl = responseData?.meta?.logo } catch(_:Throwable){}
                this.posterUrl = poster
                this.year = year.toIntOrNull()
                this.plot = description ?: plot
                this.tags = genre ?: tags
                this.actors = actorData
                this.score = responseData?.meta?.rating
                addTrailer(trailer)
                addImdbUrl(imdbUrl)
            }
        } else {
            val episodesData = mutableListOf<Episode>()
            val epLinksMap = mutableMapOf<Int, MutableList<String>>()
            val episodeRegex = Regex("EPiSODE\\s*(\\d+)", RegexOption.IGNORE_CASE)

            doc.select("h3, h4").forEach { element ->
                val episodeNumberFromTitle = episodeRegex.find(element.text())?.groupValues?.get(1)?.toIntOrNull()
                val baseLinks = element.select("a[href]").mapNotNull { it -> it.attr("href").takeIf { it.isNotBlank() } }

                val isDirectLinkBlock = element.select("a").any {
                    it.text().contains(Regex("1080|720|4K|2160", RegexOption.IGNORE_CASE))
                }
                val allEpisodeLinks = mutableSetOf<String>()

                if (isDirectLinkBlock) {
                    baseLinks.forEach { url ->
                        try {
                            val resolvedUrl = getRedirectLinks(url.trim())
                            val episodeDoc = app.get(resolvedUrl).documentLarge

                            episodeDoc.select("h5 a").forEach { linkElement ->
                                val text = linkElement.text()
                                val link = linkElement.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                                val epNum = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()

                                if (epNum != null) {
                                    epLinksMap.getOrPut(epNum) { mutableListOf() }.add(link)
                                } else {
                                    Log.w(TAG, "Could not parse episode number from: $text")
                                }
                            }
                        } catch (_: Exception) {
                            Log.e(TAG, "Error resolving direct link for URL: $url")
                        }
                    }
                } else if (episodeNumberFromTitle != null) {
                    if (element.tagName() == "h4") {
                        var nextElement = element.nextElementSibling()
                        while (nextElement != null && nextElement.tagName() != "hr") {
                            val siblingLinks = nextElement.select("a[href]").mapNotNull { it -> it.attr("href").takeIf { it.isNotBlank() } }
                            allEpisodeLinks.addAll(siblingLinks)
                            nextElement = nextElement.nextElementSibling()
                        }
                    }

                    if (baseLinks.isNotEmpty()) {
                        allEpisodeLinks.addAll(baseLinks)
                    }

                    if (allEpisodeLinks.isNotEmpty()) {
                        Log.d(TAG, "Adding links for episode $episodeNumberFromTitle: ${allEpisodeLinks.distinct()}")
                        epLinksMap.getOrPut(episodeNumberFromTitle) { mutableListOf() }.addAll(allEpisodeLinks.distinct())
                    }
                }
            }

            epLinksMap.forEach { (epNum, links) ->
                val info = responseData?.meta?.videos?.find { it.season == seasonNumber && it.episode == epNum }

                episodesData.add(
                    newEpisode(links) {
                        this.name = info?.title ?: "Episode $epNum"
                        this.season = seasonNumber
                        this.episode = epNum
                        this.posterUrl = info?.thumbnail
                        this.description = info?.overview
                        this.score = info?.rating
                        addDate(info?.released)
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                this.backgroundPosterUrl = background
                try { this.logoUrl = responseData?.meta?.logo } catch(_:Throwable){}
                this.posterUrl = poster
                this.year = year.toIntOrNull()
                this.plot = description ?: plot
                this.tags = genre ?: tags
                this.actors = actorData
                this.score = responseData?.meta?.rating
                addTrailer(trailer)
                addImdbUrl(imdbUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linksList: List<String> = data.removePrefix("[").removeSuffix("]").replace("\"", "").split(',', ' ').map { it.trim() }.filter { it.isNotBlank() }
        for (link in linksList) {
            try {
                val finalLink = if ("?id=" in link) {
                    getRedirectLinks(link)
                } else {
                    link
                }
                if (finalLink.contains("Hubdrive",ignoreCase = true))
                {
                    Hubdrive().getUrl(finalLink,"", subtitleCallback,callback)
                } else loadExtractor(finalLink, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("Phisher", "Failed to process $link: ${e.message}")
            }
        }
        return true
    }



    /**
     * Determines the search quality based on the presence of specific keywords in the input string.
     *
     * @param check The string to check for keywords.
     * @return The corresponding `SearchQuality` enum value, or `null` if no match is found.
     */
    fun getSearchQuality(check: String?): SearchQuality? {
        val s = check ?: return null
        val u = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val patterns = listOf(
            Regex("\\b(4k|ds4k|uhd|2160p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.FourK,

            // CAM / THEATRE SOURCES FIRST
            Regex("\\b(hdts|hdcam|hdtc)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HdCam,
            Regex("\\b(camrip|cam[- ]?rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip,
            Regex("\\b(cam)\\b", RegexOption.IGNORE_CASE) to SearchQuality.Cam,

            // WEB / RIP
            Regex("\\b(web[- ]?dl|webrip|webdl)\\b", RegexOption.IGNORE_CASE) to SearchQuality.WebRip,

            // BLURAY
            Regex("\\b(bluray|bdrip|blu[- ]?ray)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,

            // RESOLUTIONS
            Regex("\\b(1440p|qhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,
            Regex("\\b(1080p|fullhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,
            Regex("\\b(720p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.SD,

            // GENERIC HD LAST
            Regex("\\b(hdrip|hdtv)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,

            Regex("\\b(dvd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.DVD,
            Regex("\\b(hq)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HQ,
            Regex("\\b(rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip
        )


        for ((regex, quality) in patterns) if (regex.containsMatchIn(u)) return quality
        return null
    }
    
    /**
     * Clean title by removing quality tags, year, and other metadata
     */
    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""(?i)\s*\(?\d{4}\)?"""), "") // Remove year
            .replace(Regex("""(?i)\s*\[.*?]"""), "") // Remove brackets
            .replace(Regex("""(?i)\s*(480p|720p|1080p|2160p|4k|hdrip|webrip|bluray|web-dl|hdtv|dvdrip|brrip|hdcam|camrip).*"""), "")
            .replace(Regex("""(?i)\s*(hindi|english|dual audio|esub|esubs).*"""), "")
            .replace(Regex("""(?i)\s*download\s*(free)?.*"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
    
    /**
     * Extract links from anchor tags
     */
    private fun extractLinksATags(elements: org.jsoup.select.Elements): List<String> {
        return elements.mapNotNull { element ->
            val href = element.attr("href")
            if (href.isNotBlank() && (href.contains("hubdrive", true) || 
                href.contains("hubcloud", true) || 
                href.contains("gdflix", true) ||
                href.contains("hubcdn", true) ||
                href.contains("gdrive", true))) {
                href
            } else null
        }.distinct()
    }
    
    /**
     * Follow redirects and get final URL
     */
    private suspend fun getRedirectLinks(url: String): String {
        return try {
            if (url.contains("?id=")) {
                val response = app.get(url, allowRedirects = false)
                response.headers["location"] ?: response.headers["Location"] ?: url
            } else {
                url
            }
        } catch (e: Exception) {
            Log.e(TAG, "Redirect error: ${e.message}")
            url
        }
    }
}