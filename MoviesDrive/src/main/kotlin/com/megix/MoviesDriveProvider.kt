package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.Score
import com.google.gson.Gson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class MoviesDriveProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://moviesdrive.lat/"
    override var name = "MoviesDrive"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    val cinemeta_url = "https://v3-cinemeta.strem.io/meta"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    init {
        // Use direct domain instead of fetching from GitHub
        mainUrl = "https://moviesdrive.mom"
    }

    // Helper function to ensure URL is properly formatted
    private fun getValidUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> {
                // If url doesn't start with /, add it
                if (url.isNotEmpty() && !mainUrl.endsWith("/") && !url.startsWith("/")) {
                    "$mainUrl/$url"
                } else {
                    "$mainUrl$url"
                }
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Release",
        "$mainUrl/category/hollywood/page/" to "Hollywood Movies",
        "$mainUrl/hindi-dubbed/page/" to "Hindi Dubbed Movies",
        "$mainUrl/category/south/page/" to "South Movies",
        "$mainUrl/category/bollywood/page/" to "Bollywood Movies",
        "$mainUrl/category/amzn-prime-video/page/" to "Prime Video",
        "$mainUrl/category/netflix/page/" to "Netflix",
        "$mainUrl/category/hotstar/page/" to "Hotstar",
        "$mainUrl/category/web/page/" to "Web Series",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Debug: Check what request.data contains
        val requestData = request.data
        
        // If request.data already contains full URL, use it directly
        val url = if (requestData.startsWith("http")) {
            "${requestData}${page}"
        } else {
            getValidUrl("${requestData}${page}")
        }
        
        val document = app.get(url).document
        val home = document.select("ul.recent-movies > li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select("figure > img").attr("title").replace("Download ", "")
        val href = this.select("figure > a").attr("href")
        val posterUrl = this.select("figure > img").attr("src")
        
        // Validate and fix href if needed
        val validHref = getValidUrl(href)
        
        val quality = if(title.contains("HDCAM", ignoreCase = true) || title.contains("CAMRip", ignoreCase = true)) {
            SearchQuality.CamRip
        }
        else {
            null
        }
        return newMovieSearchResponse(title, validHref, TvType.Movie) {
            this.posterUrl = getValidUrl(posterUrl)
            this.quality = quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val maxPages = 7
        
        // Process first 3 pages concurrently for faster results
        val initialPages = (1..minOf(maxPages, 3)).toList().amap { pageNum ->
            try {
                val searchUrl = getValidUrl("/page/$pageNum/?s=$query")
                val document = app.get(searchUrl, timeout = 10L).document
                document.select("ul.recent-movies > li").mapNotNull { it.toSearchResult() }
            } catch (e: Exception) {
                emptyList<SearchResponse>()
            }
        }.flatten()
        
        searchResponse.addAll(initialPages)
        
        // If we got results and need more, process remaining pages
        if (initialPages.isNotEmpty() && maxPages > 3) {
            val remainingPages = (4..maxPages).toList().amap { pageNum ->
                try {
                    val searchUrl = getValidUrl("/page/$pageNum/?s=$query")
                    val document = app.get(searchUrl, timeout = 8L).document
                    val results = document.select("ul.recent-movies > li").mapNotNull { it.toSearchResult() }
                    if (results.isEmpty()) null else results
                } catch (e: Exception) {
                    null
                }
            }.filterNotNull().flatten()
            
            searchResponse.addAll(remainingPages)
        }

        return searchResponse.take(50) // Limit to 50 results for performance
    }

    override suspend fun load(url: String): LoadResponse? {
        val validUrl = getValidUrl(url)
        val document = app.get(validUrl).document
        var title = document.select("meta[property=og:title]").attr("content").replace("Download ", "")
        val ogTitle = title
        val plotElement = document.select(
            "h2:contains(Storyline), h3:contains(Storyline), h5:contains(Storyline), h4:contains(Storyline), h4:contains(STORYLINE)"
        ).firstOrNull() ?. nextElementSibling()

        var description = plotElement ?. text() ?: document.select(".ipc-html-content-inner-div").firstOrNull() ?. text().toString()

        var posterUrl = document.select("img[decoding=\"async\"]").attr("src")
        val seasonRegex = """(?i)season\s*\d+""".toRegex()
        val imdbUrl = document.select("a[href*=\"imdb\"]").attr("href")

        val tvtype = if (
            title.contains("Episode", ignoreCase = true) == true ||
            seasonRegex.containsMatchIn(title) ||
            title.contains("series", ignoreCase = true) == true
        ) {
            "series"
        } else {
            "movie"
        }

        val imdbId = imdbUrl.substringAfter("title/").substringBefore("/")
        
        // Make IMDB API call optional with timeout for better performance
        val responseData = try {
            if (imdbId.isNotEmpty() && imdbId.length > 5) {
                val jsonResponse = app.get("$cinemeta_url/$tvtype/$imdbId.json", timeout = 5L).text
                tryParseJson<ResponseData>(jsonResponse)
            } else {
                null
            }
        } catch (e: Exception) {
            null // Continue without IMDB data if API fails
        }

        var cast: List<String> = emptyList()
        var genre: List<String> = emptyList()
        var imdbRating: String = ""
        var year: String = ""
        var background: String = posterUrl

        if(responseData != null) {
            description = responseData.meta.description ?: description
            cast = responseData.meta.cast ?: emptyList()
            title = responseData.meta.name ?: title
            genre = responseData.meta.genre ?: emptyList()
            imdbRating = responseData.meta.imdbRating ?: ""
            year = responseData.meta.year ?: ""
            posterUrl = responseData.meta.poster ?: posterUrl
            background = responseData.meta.background ?: background
        }

        if(tvtype == "series") {
            if(title != ogTitle) {
                val checkSeason = Regex("""Season\s*\d*1|S\s*\d*1""").find(ogTitle)
                if (checkSeason == null) {
                    val seasonText = Regex("""Season\s*\d+|S\s*\d+""").find(ogTitle)?.value
                    if(seasonText != null) {
                        title = title + " " + seasonText.toString()
                    }
                }
            }
            
            val tvSeriesEpisodes = mutableListOf<Episode>()
            val episodesMap = mutableMapOf<Pair<Int, Int>, MutableList<String>>()
            val buttons = document.select("h5 > a")
                .filter { !it.text().contains("Zip", true) }

            // Limit concurrent processing to avoid overwhelming the server
            val maxConcurrent = minOf(buttons.size, 5)
            
            // Process episodes concurrently with timeout and error handling
            buttons.chunked(maxConcurrent).forEach { buttonChunk ->
                buttonChunk.amap { button ->
                    try {
                        val titleElement = button.parent()?.previousElementSibling()
                        val mainTitle = titleElement?.text() ?: ""
                        val realSeasonRegex = Regex("""(?:Season |S)(\d+)""")
                        val realSeason = realSeasonRegex.find(mainTitle)?.groupValues?.get(1)?.toInt() ?: 0
                        val episodeLink = button.attr("href") ?: ""
                        
                        if (episodeLink.isNotEmpty()) {
                            val validEpisodeLink = getValidUrl(episodeLink)
                            val doc = app.get(validEpisodeLink, timeout = 8L).document
                            
                            var elements = doc.select("span:matches((?i)(Ep))")
                            if(elements.isEmpty()) {
                                elements = doc.select("a:matches((?i)(HubCloud|GDFlix))")
                            }
                            
                            var episodeNum = 1
                            elements.forEachIndexed { index, element ->
                                try {
                                    if(element.tagName() == "span") {
                                        val titleTag = element.parent()
                                        var hTag = titleTag?.nextElementSibling()
                                        episodeNum = Regex("""Ep(\d{2})""").find(element.toString())?.groups?.get(1)?.value?.toIntOrNull() ?: (index + 1)
                                        
                                        while (hTag != null && hTag.text().contains(Regex("HubCloud|gdflix|gdlink", RegexOption.IGNORE_CASE))) {
                                            val aTag = hTag.selectFirst("a")
                                            val epUrl = aTag?.attr("href")?.takeIf { it.isNotEmpty() }
                                            if (epUrl != null) {
                                                val key = Pair(realSeason, episodeNum)
                                                synchronized(episodesMap) {
                                                    episodesMap.getOrPut(key) { mutableListOf() }.add(epUrl)
                                                }
                                            }
                                            hTag = hTag.nextElementSibling()
                                        }
                                    } else {
                                        val epUrl = element.attr("href")?.takeIf { it.isNotEmpty() }
                                        if (epUrl != null) {
                                            val key = Pair(realSeason, index + 1)
                                            synchronized(episodesMap) {
                                                episodesMap.getOrPut(key) { mutableListOf() }.add(epUrl)
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Skip problematic episodes
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Skip problematic buttons - continue processing others
                    }
                }
            }

            // Build episode list from collected data
            for ((key, sources) in episodesMap) {
                if (sources.isNotEmpty()) {
                    val episodeInfo = responseData?.meta?.videos?.find { 
                        it.season == key.first && it.episode == key.second 
                    }
                    val episodeData = sources.map { EpisodeLink(it) }
                    
                    tvSeriesEpisodes.add(
                        newEpisode(episodeData) {
                            this.name = episodeInfo?.name ?: episodeInfo?.title ?: "Episode ${key.second}"
                            this.season = key.first
                            this.episode = key.second
                            this.posterUrl = episodeInfo?.thumbnail
                            this.description = episodeInfo?.overview
                        }
                    )
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, tvSeriesEpisodes) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.score = try { 
                    imdbRating.toDoubleOrNull()?.let { Score.from10(it) }
                } catch (e: Exception) { 
                    null 
                }
                this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background
                addActors(cast)
                addImdbUrl(imdbUrl)
            }
        }
        else {
            val buttons = document.select("h5 > a")
            val data = buttons.amap { button ->
                try {
                    val link = button.attr("href")
                    val validLink = getValidUrl(link)
                    val doc = app.get(validLink, timeout = 8L).document
                    val innerButtons = doc.select("a").filter { element ->
                        element.attr("href").contains(Regex("hubcloud|gdflix|gdlink", RegexOption.IGNORE_CASE))
                    }
                    innerButtons.mapNotNull { innerButton ->
                        val source = innerButton.attr("href")
                        if (source.isNotEmpty()) {
                            val validSource = getValidUrl(source)
                            EpisodeLink(validSource)
                        } else null
                    }
                } catch (e: Exception) {
                    emptyList<EpisodeLink>() // Return empty list for failed buttons
                }
            }.flatten()
            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.score = try { 
                    imdbRating.toDoubleOrNull()?.let { Score.from10(it) }
                } catch (e: Exception) { 
                    null 
                }
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
        sources.amap {
            val source = it.source
            // Validate URL before extracting
            if (source.startsWith("http://") || source.startsWith("https://")) {
                try {
                    loadExtractor(source, subtitleCallback, callback)
                } catch (e: Exception) {
                    // Log error but continue with other sources
                    println("Error loading source $source: ${e.message}")
                }
            }
        }
        return true   
    }

    data class Meta(
        val id: String?,
        val imdb_id: String?,
        val type: String?,
        val poster: String?,
        val logo: String?,
        val background: String?,
        val moviedb_id: Int?,
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
        val moviedb_id: Int?
    )

    data class ResponseData(
        val meta: Meta
    )

    data class EpisodeLink(
        val source: String
    )
}

