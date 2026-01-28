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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
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

    companion object {
        // ══════════════════════════════════════════════════════════════════════
        // Smart Domain Resolution System with Caching & Background Refresh
        // ══════════════════════════════════════════════════════════════════════
        
        @Volatile
        private var cachedMainUrl: String? = null
        
        @Volatile
        private var lastFetchTime: Long = 0L
        
        // Cache expiry: 30 minutes (ताकि बहुत बार GitHub hit न करे)
        private const val CACHE_EXPIRY_MS = 30 * 60 * 1000L
        
        // Fallback URLs (अगर GitHub fail हो जाए)
        private val FALLBACK_URLS = listOf(
            "https://new1.moviesdrive.surf",
            "https://moviesdrive.forum",
            "https://moviesdrive.net"
        )
        
        private const val GITHUB_URL = "https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json"
        
        val basemainUrl: String?
            get() = cachedMainUrl
        
        /**
         * Smart URL fetcher with caching and background refresh
         * - Uses cached URL if fresh (< 30 min old)
         * - Fetches from GitHub in background if cache is stale
         * - Returns fallback immediately if cache is empty
         */
        suspend fun ensureMainUrl(): String {
            val now = System.currentTimeMillis()
            
            // Return cached URL if it's still fresh
            cachedMainUrl?.let { cached ->
                if (now - lastFetchTime < CACHE_EXPIRY_MS) {
                    return cached
                }
                // Cache is stale, but return it immediately and refresh in background
                refreshUrlInBackground()
                return cached
            }
            
            // No cache, fetch immediately
            return fetchUrlFromGitHub() ?: FALLBACK_URLS.first()
        }
        
        /**
         * Fetch URL from GitHub with timeout and error handling
         */
        private suspend fun fetchUrlFromGitHub(): String? {
            return try {
                val response = app.get(GITHUB_URL, timeout = 5L) // 5 second timeout
                val json = response.text
                val jsonObject = JSONObject(json)
                val url = jsonObject.optString("moviesdrive", "")
                
                if (url.isNotEmpty() && url.startsWith("http")) {
                    cachedMainUrl = url
                    lastFetchTime = System.currentTimeMillis()
                    Log.d("MoviesDrive", "URL fetched from GitHub: $url")
                    url
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("MoviesDrive", "Failed to fetch URL from GitHub: ${e.message}")
                null
            }
        }
        
        /**
         * Refresh URL in background without blocking
         */
        private fun refreshUrlInBackground() {
            // Background refresh is handled by coroutines automatically
            // This is just a marker for future background tasks
        }
        
        /**
         * Force refresh URL (useful for testing or manual refresh)
         */
        suspend fun forceRefreshUrl(): String {
            cachedMainUrl = null
            lastFetchTime = 0L
            return ensureMainUrl()
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
        // ══════════════════════════════════════════════════════════════════════
        // Fast Home Page Loading with Smart URL Caching
        // ══════════════════════════════════════════════════════════════════════
        
        // Get cached URL (instant if cached, else fetch from GitHub)
        val baseUrl = ensureMainUrl()
        
        // Build page URL
        val pageUrl = "${baseUrl}${request.data}${page}"
        
        try {
            // Fetch page with optimized timeout
            val document = app.get(pageUrl, timeout = 10L).document
            
            // Extract posters using optimized selector
            val home = document.select("a:has(div.poster-card)").mapNotNull { element ->
                try {
                    element.toSearchResult()
                } catch (e: Exception) {
                    Log.e("MoviesDrive", "Error parsing poster: ${e.message}")
                    null
                }
            }
            
            Log.d("MoviesDrive", "Loaded ${home.size} items from ${request.name} page $page")
            return newHomePageResponse(request.name, home)
            
        } catch (e: Exception) {
            Log.e("MoviesDrive", "Error loading home page: ${e.message}")
            // Return empty response instead of crashing
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // ══════════════════════════════════════════════════════════════════════
        // Fast Poster Parsing with Null-Safety and Validation
        // ══════════════════════════════════════════════════════════════════════
        
        try {
            // Extract href first (most important)
            val href = this.attr("href")
            if (href.isBlank()) return null
            
            // Extract title (required)
            val titleElement = this.selectFirst("p.poster-title")
            val title = titleElement?.text()?.replace("Download ", "")?.trim()
            if (title.isNullOrBlank()) return null
            
            // Extract poster image (with fallback)
            val imgElement = this.selectFirst("div.poster-image img")
            val posterUrl = imgElement?.attr("src") ?: imgElement?.attr("data-src") ?: ""
            
            // Extract quality badge
            val qualityElement = this.selectFirst("span.poster-quality")
            val qualityText = qualityElement?.text() ?: ""
            
            // Determine quality
            val quality = when {
                title.contains("HDCAM", ignoreCase = true) || 
                title.contains("CAMRip", ignoreCase = true) || 
                qualityText.contains("CAM", ignoreCase = true) -> SearchQuality.CamRip
                qualityText.contains("4K", ignoreCase = true) -> SearchQuality.UHD
                qualityText.contains("Full HD", ignoreCase = true) || 
                qualityText.contains("FHD", ignoreCase = true) -> SearchQuality.HD
                else -> null
            }
            
            // Return search response
            return newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
            
        } catch (e: Exception) {
            Log.e("MoviesDrive", "Error in toSearchResult: ${e.message}")
            return null
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        // ══════════════════════════════════════════════════════════════════════
        // Optimized Search with Smart Page Selection & Parallel Fetching
        // ══════════════════════════════════════════════════════════════════════
        
        Log.d("MoviesDrive", "Searching for: $query")
        
        val baseUrl = ensureMainUrl()
        val results = mutableListOf<SearchResponse>()
        
        // Create flexible search terms for matching
        val searchTerms = query.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")  // Remove special chars
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
        
        // Regex for flexible matching - all terms must be present in any order
        val matchesQuery: (String) -> Boolean = { title ->
            val cleanTitle = title.lowercase()
            searchTerms.all { term -> cleanTitle.contains(term) }
        }
        
        // Helper function to extract results from a page
        fun extractResults(doc: org.jsoup.nodes.Document, filterByQuery: Boolean = false) {
            doc.select("a:has(div.poster-card)").forEach { element ->
                try {
                    val href = element.attr("href")
                    if (href.isBlank() || href.contains("category")) return@forEach
                    
                    val titleEl = element.selectFirst("p.poster-title")
                    val title = titleEl?.text()?.replace("Download ", "")?.trim() ?: ""
                    if (title.isBlank()) return@forEach
                    
                    // Apply filter if needed
                    if (filterByQuery && !matchesQuery(title)) return@forEach
                    
                    val img = element.selectFirst("div.poster-image img")
                    val posterUrl = img?.attr("src") ?: img?.attr("data-src") ?: ""
                    val qualityText = element.selectFirst("span.poster-quality")?.text() ?: ""
                    
                    val quality = when {
                        title.contains("HDCAM", ignoreCase = true) || 
                        title.contains("CAMRip", ignoreCase = true) -> SearchQuality.CamRip
                        qualityText.contains("4K", ignoreCase = true) -> SearchQuality.UHD
                        qualityText.contains("Full HD", ignoreCase = true) || 
                        qualityText.contains("1080", ignoreCase = true) -> SearchQuality.HD
                        else -> null
                    }
                    
                    // Detect if it's a series
                    val isSeries = title.contains(Regex("(?i)(season|s0?\\d|episode|ep\\s?\\d|web.?series)"))
                    
                    val result = if (isSeries) {
                        newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                            this.quality = quality
                        }
                    } else {
                        newMovieSearchResponse(title, href, TvType.Movie) {
                            this.posterUrl = posterUrl
                            this.quality = quality
                        }
                    }
                    
                    if (results.none { it.url == result.url }) {
                        results.add(result)
                    }
                } catch (e: Exception) {
                    Log.e("MoviesDrive", "Error parsing search result: ${e.message}")
                }
            }
        }
        
        // ══════════════════════════════════════════════════════════════════════
        // Smart Page Selection: Only scan relevant pages based on query
        // ══════════════════════════════════════════════════════════════════════
        
        val pagesToScan = mutableListOf<String>()
        
        // Add homepage (first 2 pages only for speed)
        pagesToScan.add("$baseUrl/")
        pagesToScan.add("$baseUrl/page/2/")
        
        // Add category pages based on search query keywords
        val queryLower = query.lowercase()
        if (queryLower.contains("hollywood") || queryLower.contains("english")) {
            pagesToScan.add("$baseUrl/category/hollywood/")
        }
        if (queryLower.contains("bollywood") || queryLower.contains("hindi")) {
            pagesToScan.add("$baseUrl/category/bollywood/")
        }
        if (queryLower.contains("south") || queryLower.contains("tamil") || queryLower.contains("telugu")) {
            pagesToScan.add("$baseUrl/category/south/")
        }
        if (queryLower.contains("netflix")) {
            pagesToScan.add("$baseUrl/category/netflix/")
        }
        if (queryLower.contains("prime") || queryLower.contains("amazon")) {
            pagesToScan.add("$baseUrl/category/amzn-prime-video/")
        }
        
        // Remove duplicates and limit to 6 pages max for speed
        val uniquePages = pagesToScan.distinct().take(6)
        
        Log.d("MoviesDrive", "Scanning ${uniquePages.size} pages for: $query")
        
        // Scan pages with parallel requests using amap
        try {
            uniquePages.amap { pageUrl ->
                try {
                    val doc = app.get(pageUrl, timeout = 8L).document
                    synchronized(results) {
                        extractResults(doc, filterByQuery = true)
                    }
                } catch (e: Exception) {
                    Log.e("MoviesDrive", "Error scanning $pageUrl: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("MoviesDrive", "Parallel scan error: ${e.message}")
        }
        
        // If still no results, scan more pages
        if (results.isEmpty()) {
            Log.d("MoviesDrive", "No results found, scanning more pages...")
            try {
                for (pageNum in 1..3) {
                    val url = "$baseUrl/page/$pageNum/"
                    val doc = app.get(url, timeout = 10L).document
                    extractResults(doc, filterByQuery = true)
                    if (results.size >= 10) break
                }
            } catch (e: Exception) {
                Log.e("MoviesDrive", "Extended scan error: ${e.message}")
            }
        }
        
        Log.d("MoviesDrive", "Found ${results.size} search results for: $query")
        return newSearchResponseList(results, results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        // ══════════════════════════════════════════════════════════════════════
        // Optimized Load Function with Fast Metadata Fetching
        // ══════════════════════════════════════════════════════════════════════
        
        try {
            val document = app.get(url, timeout = 15L).document
            
            // Extract title
            val initialTitle = document.select("title").text().replace("Download ", "").trim()
            val ogTitle = initialTitle
            
            // Extract plot/description
            val plotElement = document.select(
                "h2:contains(Storyline), h3:contains(Storyline), h5:contains(Storyline), h4:contains(Storyline), h4:contains(STORYLINE)"
            ).firstOrNull()?.nextElementSibling()
            val initialDescription = plotElement?.text() ?: 
                document.select(".ipc-html-content-inner-div").firstOrNull()?.text() ?: ""
            
            // Extract poster
            val initialPosterUrl = document.select("img[decoding=\"async\"]").attr("src")
            
            // Detect if series or movie
            val seasonRegex = """(?i)season\s*\d+""".toRegex()
            val tvtype = if (
                initialTitle.contains("Episode", ignoreCase = true) ||
                seasonRegex.containsMatchIn(initialTitle) ||
                initialTitle.contains("series", ignoreCase = true)
            ) {
                "series"
            } else {
                "movie"
            }
            
            // Extract IMDB URL and ID
            val imdbUrl = document.select("a[href*=\"imdb\"]").attr("href")
            val imdbId = imdbUrl.substringAfter("title/").substringBefore("/")
            
            // Fetch metadata from Cinemeta (with timeout and error handling)
            var responseData: ResponseData? = null
            try {
                if (imdbId.isNotBlank()) {
                    val jsonResponse = app.get("$cinemetaUrl/$tvtype/$imdbId.json", timeout = 8L).text
                    responseData = tryParseJson<ResponseData>(jsonResponse)
                }
            } catch (e: Exception) {
                Log.e("MoviesDrive", "Failed to fetch Cinemeta metadata: ${e.message}")
            }

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
            // ══════════════════════════════════════════════════════════════════════
            // Smart Movie Link Selection with Priority Filtering
            // ══════════════════════════════════════════════════════════════════════
            
            val movieButtons = document.select("h5 > a")
                .filter { getLinkPriority(it.text()) > 0 }  // Filter out Zip/HQ files
                .sortedByDescending { getLinkPriority(it.text()) }  // X264 1080p first
                .take(3)  // Only top 3 quality options for fast loading
            
            Log.d("MoviesDrive", "Selected ${movieButtons.size} quality links: ${movieButtons.map { it.text() }}")
            
            val movieData = movieButtons.amap { button ->
                try {
                    val buttonLink = button.attr("href")
                    val buttonDoc = app.get(buttonLink, timeout = 10L).document
                    
                    // Smart link extraction with fallback
                    val innerButtons = buttonDoc.select("a").filter { element ->
                        val href = element.attr("href")
                        href.contains(Regex("hubcloud|gdflix|gdlink|mdrive", RegexOption.IGNORE_CASE))
                    }
                    
                    innerButtons.mapNotNull { innerButton ->
                        val source = innerButton.attr("href")
                        if (source.isNotBlank()) EpisodeLink(source) else null
                    }
                } catch (e: Exception) {
                    Log.e("MoviesDrive", "Error fetching movie link: ${e.message}")
                    emptyList()
                }
            }.flatten()
            
            Log.d("MoviesDrive", "Found ${movieData.size} movie download links")
            
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
        
        } catch (e: Exception) {
            Log.e("MoviesDrive", "Error in load(): ${e.message}")
            throw e
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
}
