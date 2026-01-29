package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element

class MoviesDriveProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://moviesdrive.forum"
    override var name = "MoviesDrive"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    val cinemetaUrl = "https://aiometadata.elfhosted.com/stremio/9197a4a9-2f5b-4911-845e-8704c520bdf7/meta"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    // ═══════════════════════════════════════════════════════════════════
    // Link Priority Helper: Score links based on codec + quality
    // Priority: X264 1080p > X264 720p > HEVC 1080p > HEVC 720p > Others
    // ═══════════════════════════════════════════════════════════════════
    private fun getLinkPriority(linkText: String): Int {
        val text = linkText.lowercase()
        
        // Skip HQ files (usually very large 5GB+)
        if (text.contains("hq ") || text.contains("hq-") || text.startsWith("hq")) return -100
        // Skip Zip files
        if (text.contains("zip")) return -200
        
        // Detect codec
        val isX264 = text.contains("x264") || text.contains("h264") || text.contains("h.264")
        val isHEVC = text.contains("hevc") || text.contains("x265") || text.contains("h265") || text.contains("h.265")
        
        // Detect quality
        val is1080p = text.contains("1080p")
        val is720p = text.contains("720p")
        val is480p = text.contains("480p")
        
        // Priority scoring: X264 1080p = 1000, X264 720p = 900, HEVC 1080p = 800...
        return when {
            isX264 && is1080p -> 1000  // 1st Priority: X264 1080p
            isX264 && is720p -> 900    // 2nd Priority: X264 720p
            isHEVC && is1080p -> 800   // 3rd Priority: HEVC 1080p
            isHEVC && is720p -> 700    // 4th Priority: HEVC 720p
            is1080p -> 600             // Unknown codec 1080p
            is720p -> 500              // Unknown codec 720p
            is480p -> 400              // 480p
            else -> 300                // Unknown quality
        }
    }

    // URL is fetched lazily in companion object - no blocking init needed

    companion object {
        // Cached URL - fetched on first use, non-blocking
        @Volatile
        private var cachedMainUrl: String? = null
        
        val basemainUrl: String?
            get() = cachedMainUrl
        
        // Call this in getMainPage to ensure URL is fetched
        suspend fun ensureMainUrl(): String {
            cachedMainUrl?.let { return it }
            return try {
                val response = app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json")
                val json = response.text
                val jsonObject = JSONObject(json)
                val url = jsonObject.optString("moviesdrive")
                cachedMainUrl = url.ifEmpty { null }
                url.ifEmpty { "https://moviesdrive.forum" }
            } catch (_: Exception) {
                "https://moviesdrive.forum"
            }
        }
    }

    override val mainPage = mainPageOf(
        "/page/" to "Latest Release",
        "/category/hollywood/page/" to "Hollywood Movies",
        "/hindi-dubbed/page/" to "Hindi Dubbed Movies",
        "/category/south/page/" to "South Movies",
        "/category/bollywood/page/" to "Bollywood Movies",
        "/category/amzn-prime-video/page/" to "Prime Video",
        "/category/netflix/page/" to "Netflix",
        "/category/hotstar/page/" to "Hotstar",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Ensure mainUrl is fetched (non-blocking, cached after first call)
        val baseUrl = ensureMainUrl()
        val document = app.get("${baseUrl}${request.data}${page}").document
        val home = document.select("a:has(div.poster-card)").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst("p.poster-title")?.text()?.replace("Download ", "") ?: ""
        val href = this.attr("href")
        val imgElement = this.selectFirst("div.poster-image img")
        val posterUrl = fixUrlNull(imgElement?.getImageAttr())
        val qualityText = this.selectFirst("span.poster-quality")?.text() ?: ""
        val quality = when {
            title.contains("HDCAM", ignoreCase = true) || title.contains("CAMRip", ignoreCase = true) -> SearchQuality.CamRip
            qualityText.contains("4K", ignoreCase = true) -> SearchQuality.UHD
            qualityText.contains("Full HD", ignoreCase = true) -> SearchQuality.HD
            else -> null
        }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        // Website uses search.html?q= endpoint, not /?s=
        val searchUrl = if (page <= 1) {
            "$mainUrl/search.html?q=$query"
        } else {
            "$mainUrl/search.html?q=$query&page=$page"
        }
        val document = app.get(searchUrl).document
        val results = document.select("a:has(div.poster-card)").mapNotNull { it.toSearchResult() }
        val hasNext = results.isNotEmpty()
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val initialTitle = document.select("title").text().replace("Download ", "")
        val ogTitle = initialTitle
        val plotElement = document.select(
            "h2:contains(Storyline), h3:contains(Storyline), h5:contains(Storyline), h4:contains(Storyline), h4:contains(STORYLINE)"
        ).firstOrNull()?.nextElementSibling()

        val initialDescription = plotElement?.text() ?: document.select(".ipc-html-content-inner-div").firstOrNull()?.text().toString()

        val initialPosterUrl = document.select("img[decoding=\"async\"]").attr("src")
        val seasonRegex = """(?i)season\s*\d+""".toRegex()
        val imdbUrl = document.select("a[href*=\"imdb\"]").attr("href")

        val tvtype = if (
            initialTitle.contains("Episode", ignoreCase = true) ||
            seasonRegex.containsMatchIn(initialTitle) ||
            initialTitle.contains("series", ignoreCase = true)
        ) {
            "series"
        } else {
            "movie"
        }

        val imdbId = imdbUrl.substringAfter("title/").substringBefore("/")
        val jsonResponse = app.get("$cinemetaUrl/$tvtype/$imdbId.json").text
        val responseData = tryParseJson<ResponseData>(jsonResponse)

        val description: String
        val cast: List<String>
        var title: String
        val genre: List<String>
        val imdbRating: String
        val year: String
        val posterUrl: String
        val background: String

        if (responseData != null) {
            description = responseData.meta.description ?: initialDescription
            cast = responseData.meta.cast ?: emptyList()
            title = responseData.meta.name ?: initialTitle
            genre = responseData.meta.genre ?: emptyList()
            imdbRating = responseData.meta.imdbRating ?: ""
            year = responseData.meta.year ?: ""
            posterUrl = responseData.meta.poster ?: initialPosterUrl
            background = responseData.meta.background ?: initialPosterUrl
        } else {
            description = initialDescription
            cast = emptyList()
            title = initialTitle
            genre = emptyList()
            imdbRating = ""
            year = ""
            posterUrl = initialPosterUrl
            background = initialPosterUrl
        }

        if (tvtype == "series") {
            if (title != ogTitle) {
                val checkSeason = Regex("""Season\s*\d*1|S\s*\d*1""").find(ogTitle)
                if (checkSeason == null) {
                    val seasonText = Regex("""Season\s*\d+|S\s*\d+""").find(ogTitle)?.value
                    if (seasonText != null) {
                        title = "$title $seasonText"
                    }
                }
            }
            val tvSeriesEpisodes = mutableListOf<Episode>()
            val episodesMap: MutableMap<Pair<Int, Int>, List<String>> = mutableMapOf()
            val seriesButtons = document.select("h5 > a")
                .filter { element -> !element.text().contains("Zip", true) }


            seriesButtons.forEach { button ->
                val titleElement = button.parent()?.previousElementSibling()
                val mainTitle = titleElement?.text() ?: ""
                val realSeasonRegex = Regex("""(?:Season |S)(\d+)""")
                val realSeason = realSeasonRegex.find(mainTitle)?.groupValues?.get(1)?.toInt() ?: 0
                val episodeLink = button.attr("href")

                val doc = app.get(episodeLink).document
                var elements = doc.select("span:matches((?i)(Ep))")
                if (elements.isEmpty()) {
                    elements = doc.select("a:matches((?i)(HubCloud|GDFlix))")
                }
                var episodeNum = 1

                elements.forEach { element ->
                    if (element.tagName() == "span") {
                        val titleTag = element.parent()
                        var hTag = titleTag?.nextElementSibling()
                        episodeNum = Regex("""Ep(\d{2})""").find(element.toString())?.groups?.get(1)?.value?.toIntOrNull() ?: episodeNum
                        while (
                            hTag != null &&
                            (
                                    hTag.text().contains("HubCloud", ignoreCase = true) ||
                                            hTag.text().contains("gdflix", ignoreCase = true) ||
                                            hTag.text().contains("gdlink", ignoreCase = true)
                                    )
                        ) {
                            val aTag = hTag.selectFirst("a")
                            val epUrl = aTag?.attr("href").toString()
                            val key = Pair(realSeason, episodeNum)
                            if (episodesMap.containsKey(key)) {
                                val currentList = episodesMap[key] ?: emptyList()
                                val newList = currentList.toMutableList()
                                newList.add(epUrl)
                                episodesMap[key] = newList
                            } else {
                                episodesMap[key] = mutableListOf(epUrl)
                            }
                            hTag = hTag.nextElementSibling()
                        }
                        episodeNum++
                    }
                    else {
                        val epUrl = element.attr("href")
                        val key = Pair(realSeason, episodeNum)
                        if (episodesMap.containsKey(key)) {
                            val currentList = episodesMap[key] ?: emptyList()
                            val newList = currentList.toMutableList()
                            newList.add(epUrl)
                            episodesMap[key] = newList
                        } else {
                            episodesMap[key] = mutableListOf(epUrl)
                        }
                        episodeNum++
                    }
                }
            }

            for ((key, value) in episodesMap) {
                val episodeInfo = responseData?.meta?.videos?.find { it.season == key.first && it.episode == key.second }
                val episodeData = value.map { source ->
                    EpisodeLink(source)
                }
                tvSeriesEpisodes.add(
                    newEpisode(episodeData) {
                        this.name = episodeInfo?.name ?: episodeInfo?.title
                        this.season = key.first
                        this.episode = key.second
                        this.posterUrl = episodeInfo?.thumbnail
                        this.description = episodeInfo?.overview
                    }
                )
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, tvSeriesEpisodes) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background
                addActors(cast)
                addImdbUrl(imdbUrl)
            }
        } else {
            // Get all h5 > a links and sort by priority (X264 1080p first)
            val movieButtons = document.select("h5 > a")
                .filter { getLinkPriority(it.text()) > 0 }  // Filter out Zip/HQ files
                .sortedByDescending { getLinkPriority(it.text()) }  // X264 1080p first
                .take(3)  // Only top 3 quality options for fast loading
            
            Log.d("MoviesDrive", "Selected quality links: ${movieButtons.map { it.text() }}")
            
            val movieData = movieButtons.flatMap { button ->
                val buttonLink = button.attr("href")
                val buttonDoc = app.get(buttonLink).document
                val innerButtons = buttonDoc.select("a").filter { element ->
                    element.attr("href").contains(Regex("hubcloud|gdflix|gdlink|mdrive", RegexOption.IGNORE_CASE))
                }
                innerButtons.mapNotNull { innerButton ->
                    val source = innerButton.attr("href")
                    EpisodeLink(source)
                }
            }
            return newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background
                addActors(cast)
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
        val sources = parseJson<ArrayList<EpisodeLink>>(data)
        sources.amap { episodeLink ->
            val url = episodeLink.source
            try {
                when {
                    url.contains("hubcloud", true) -> HubCloud().getUrl(url, null, subtitleCallback, callback)
                    url.contains("gdflix", true) || url.contains("gdlink", true) -> GDFlix().getUrl(url, null, subtitleCallback, callback)
                    url.contains("mdrive", true) -> {
                        val doc = app.get(url).document
                        doc.select("a").filter { 
                            it.attr("href").contains(Regex("hubcloud|gdflix|gdlink", RegexOption.IGNORE_CASE))
                        }.amap { link ->
                            val linkUrl = link.attr("href")
                            when {
                                linkUrl.contains("hubcloud", true) -> HubCloud().getUrl(linkUrl, null, subtitleCallback, callback)
                                linkUrl.contains("gdflix", true) || linkUrl.contains("gdlink", true) -> GDFlix().getUrl(linkUrl, null, subtitleCallback, callback)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("MoviesDrive", "Error extracting: ${e.message}")
            }
        }
        return true
    }

    data class Meta(
        val id: String?,
        @Suppress("PropertyName") val imdb_id: String?,
        val type: String?,
        val poster: String?,
        val logo: String?,
        val background: String?,
        @Suppress("PropertyName") val moviedb_id: Int?,
        val name: String?,
        val description: String?,
        val genre: List<String>?,
        val releaseInfo: String?,
        val status: String?,
        val runtime: String?,
        val cast: List<String>?,
        val language: String?,
        val country: String?,
        val imdbRating: String?,
        val slug: String?,
        val year: String?,
        val videos: List<EpisodeDetails>?
    )

    data class EpisodeDetails(
        val id: String?,
        val name: String?,
        val title: String?,
        val season: Int?,
        val episode: Int?,
        val released: String?,
        val overview: String?,
        val thumbnail: String?,
        @Suppress("PropertyName") val moviedb_id: Int?
    )

    data class ResponseData(
        val meta: Meta
    )

    data class EpisodeLink(
        val source: String
    )

    // Helper function to extract image URLs from lazy-loaded images
    // Checks data-src, data-lazy-src, and src attributes in priority order
    // Returns absolute URLs using abs: prefix (like HDhub4u, Movierulzhd, MultiMovies)
    private fun Element.getImageAttr(): String? {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }
}
