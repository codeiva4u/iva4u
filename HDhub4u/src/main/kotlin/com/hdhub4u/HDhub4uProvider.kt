package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.nodes.Element


class HDhub4uProvider : MainAPI() {
    companion object {
        private const val TAG = "HDhub4uProvider"
        
        // ═══════════════════════════════════════════════════════════════════
        // REGEX PATTERNS - Based on HDhub4u Website Analysis (Jan 2026)
        // ═══════════════════════════════════════════════════════════════════
        
        // Series Detection Pattern
        // Matches: "Season 1", "S01", "Episode", "EP-01", "Complete", "All Episodes", "EP Added"
        private val SERIES_DETECTION_REGEX = Regex(
            """(?i)(Season\s*\d*|S0?\d|Episode|EP[-\s]?\d+|Complete|All\s*Episodes|EP\s*Added)"""
        )
        
        // Episode Number Extraction Pattern
        // Website uses: "EPiSODE 1", "Episode 2", "EP-03", "EP 4", "E05", "ep05", "ep 06"
        private val EPISODE_NUMBER_REGEX = Regex(
            """(?i)(?:EPiSODE|EPISODE|Episode|EP|E)[-.\s]*(\d+)"""
        )
        
        // Quality Extraction Pattern
        // Matches: 480p, 720p, 1080p, 2160p, 4K
        private val QUALITY_REGEX = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
        
        // File Size Extraction Pattern
        // Matches: [420MB], [1.6GB], 500MB, 2.1GB
        private val FILE_SIZE_REGEX = Regex(
            """(\d+(?:\.\d+)?)\s*(GB|MB)""", RegexOption.IGNORE_CASE
        )
        
        // Year Extraction Pattern
        // Matches: (2024), (2025), (2026)
        private val YEAR_REGEX = Regex("""\((\d{4})\)""")
        
        // Download URL Pattern - Valid hosts for HDhub4u
        // Based on Brave Browser analysis: hubdrive.space, gadgetsweb.xyz, etc.
        private val DOWNLOAD_URL_REGEX = Regex(
            """https?://(?:hubdrive\.(?:space|art)|gadgetsweb\.xyz|hubcloud\.[a-z]+|hblinks\.[a-z]+|hubcdn\.[a-z]+|gamerxyt\.com)[^"'<\s>]*""",
            RegexOption.IGNORE_CASE
        )
        
        // Valid download hosts list
        private val VALID_HOSTS = listOf(
            "hubdrive", "gadgetsweb", "hubcloud", "hubcdn",
            "gamerxyt", "gamester", "hblinks"
        )
        
        // Batch Download Pattern - Detects quality batch links
        // Website uses: "4K | SDR | HDR | HEVC", "WEB-DL | HEVC" for batch downloads
        private val BATCH_DOWNLOAD_REGEX = Regex(
            """(?i)(4K\s*\|\s*SDR|WEB-DL\s*\|\s*HEVC|HEVC\s*\|\s*x264|Complete\s*Pack|ZIP\s*Download|All\s*Episodes?\s*\|)"""
        )
    }

    // Cached domain URL - fetched once per session (async, no blocking)
    private var cachedMainUrl: String? = null
    private var urlsFetched = false

    override var mainUrl: String = "https://new2.hdhub4u.fo"

    // Fast async domain fetch with 3s timeout - non-blocking
    private suspend fun fetchMainUrl(): String {
        if (cachedMainUrl != null) return cachedMainUrl!!
        if (urlsFetched) return mainUrl

        urlsFetched = true
        try {
            val result = withTimeoutOrNull(3_000L) {  // Reduced from 10s to 3s
                val response = app.get(
                    "https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json"
                )
                val json = response.text
                val jsonObject = JSONObject(json)
                val urlString = jsonObject.optString("hdhub4u")
                urlString.ifBlank { null }
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
        // Fetch latest mainUrl (async, cached) - only first time
        fetchMainUrl()

        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}page/$page/"
        }

        Log.d(TAG, "Loading main page (Regex): $url")
        // Get raw text instead of Jsoup document for speed
        val responseText = app.get(url, headers = headers).text

        val home = mutableListOf<SearchResponse>()
        
        // Regex extraction - much faster than Jsoup for lists
        // Split by list item to process each movie block
        val blocks = responseText.split("<li class=\"thumb").drop(1)
        
        blocks.forEach { block ->
            // Extract Link
            val linkMatch = Regex("""href="([^"]+)"""").find(block)
            val href = linkMatch?.groupValues?.get(1) ?: return@forEach
            
            if (href.isBlank() || href.contains("category") || href.contains("page/")) return@forEach
            val fixedUrl = fixUrl(href)

            // Extract Poster
            // Look for src, data-src, or data-lazy-src
            val imgBlock = block.substringBefore("</a>", "") // Limit search to image area
            val srcMatch = Regex("""src="([^"]+)"""").find(imgBlock)
            val dataSrcMatch = Regex("""data-src="([^"]+)"""").find(imgBlock)
            val lazySrcMatch = Regex("""data-lazy-src="([^"]+)"""").find(imgBlock)
            
            val posterUrl = when {
                srcMatch != null && !srcMatch.groupValues[1].contains("data:image") -> srcMatch.groupValues[1]
                dataSrcMatch != null -> dataSrcMatch.groupValues[1]
                lazySrcMatch != null -> lazySrcMatch.groupValues[1]
                else -> null
            }?.let { fixUrlNull(it) }

            // Extract Title
            val titleMatch = Regex("""alt="([^"]+)"""").find(imgBlock)
            val titleText = titleMatch?.groupValues?.get(1) 
                ?: Regex("""<p>(.*?)</p>""").find(block)?.groupValues?.get(1)
                ?: ""
            
            val title = cleanTitle(titleText)
            if (title.isBlank()) return@forEach

            // Determine type
            val isSeries = SERIES_DETECTION_REGEX.containsMatchIn(titleText)

            val searchResponse = if (isSeries) {
                newTvSeriesSearchResponse(title, fixedUrl, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, fixedUrl, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
            home.add(searchResponse)
        }

        Log.d(TAG, "Found ${home.size} items (Regex)")

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Structure: li.thumb > figure > a[href] for link, img for poster
        // figcaption > a > p for title

        // Extract link from figure > a or figcaption > a
        val linkElement: Element? = selectFirst("figure a[href]")
            ?: (selectFirst("figcaption a[href]")
            ?: selectFirst("a[href]"))

        val href = linkElement?.attr("href") ?: return null
        if (href.isBlank() || href.contains("category") || href.contains("page/")) return null

        val fixedUrl = fixUrl(href)

        // Extract title from figcaption p, or img alt, or a title
        val titleText: String = selectFirst("figcaption p")?.text()
            ?: (selectFirst("figcaption a")?.text()
            ?: (selectFirst("img")?.attr("alt")
            ?: (selectFirst("img")?.attr("title")
            ?: (selectFirst("a")?.attr("title")
            ?: ""))))

        // Clean title using Regex
        if (titleText.isBlank()) return null
        val title: String = cleanTitle(titleText)
        if (title.isBlank()) return null

        // Extract poster from figure img
        val imgElement = selectFirst("figure img, img")
        val posterUrl: String? = if (imgElement != null) {
            val srcAttr = imgElement.attr("src")
            val dataSrcAttr = imgElement.attr("data-src")
            val lazyAttr = imgElement.attr("data-lazy-src")
            val src = when {
                srcAttr.isNotBlank() -> srcAttr
                dataSrcAttr.isNotBlank() -> dataSrcAttr
                else -> lazyAttr
            }
            fixUrlNull(src)
        } else null

        // Determine type using companion object Regex pattern for series detection
        val isSeries = SERIES_DETECTION_REGEX.containsMatchIn(titleText)

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

        try {
            // Method 1: Use website's search page (search.html?q=query)
            // Website uses JavaScript-based search with li.movie-card structure
            val searchUrl = "$mainUrl/search.html?q=${query.replace(" ", "+")}"
            Log.d(TAG, "Search URL: $searchUrl")
            
            val responseText = app.get(searchUrl, headers = headers).text

            // Search page uses different structure: li.movie-card
            // Use Regex for speed
            val blocks = responseText.split("<li class=\"movie-card").drop(1)
            
            blocks.forEach { block ->
                val linkMatch = Regex("""href="([^"]+)"""").find(block)
                val href = linkMatch?.groupValues?.get(1) ?: return@forEach
                
                if (href.isBlank() || href.contains("category")) return@forEach
                val fixedUrl = fixUrl(href)
                
                val imgMatch = Regex("""src="([^"]+)"""").find(block)
                val posterUrl = fixUrlNull(imgMatch?.groupValues?.get(1))
                
                val titleMatch = Regex("""alt="([^"]+)"""").find(block) 
                               ?: Regex("""title="([^"]+)"""").find(block)
                val titleText = titleMatch?.groupValues?.get(1) ?: ""
                
                if (titleText.isBlank()) return@forEach
                val title = cleanTitle(titleText)
                
                val isSeries = SERIES_DETECTION_REGEX.containsMatchIn(titleText)
                
                val searchResult = if (isSeries) {
                    newTvSeriesSearchResponse(title, fixedUrl, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    }
                } else {
                    newMovieSearchResponse(title, fixedUrl, TvType.Movie) {
                        this.posterUrl = posterUrl
                    }
                }
                
                if (results.none { it.url == searchResult.url }) {
                    results.add(searchResult)
                }
            }
            
            // Method 2: Fallback - search through homepage if no results from search page
            if (results.isEmpty()) {
                Log.d(TAG, "Search page returned no results, using fallback method")
                val searchTerms = query.lowercase().split(" ").filter { it.length > 2 }
                
                for (page in 1..3) {
                    val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
                    // Use Regex logic here too by calling getMainPage logic manually or extracting
                    val html = app.get(url, headers = headers).text
                    val fallbackBlocks = html.split("<li class=\"thumb").drop(1)
                    
                    fallbackBlocks.forEach { block ->
                        // ... simplified regex extraction for fallback ...
                        val link = Regex("""href="([^"]+)"""").find(block)?.groupValues?.get(1) ?: return@forEach
                        val titleText = Regex("""alt="([^"]+)"""").find(block)?.groupValues?.get(1) ?: ""
                        val title = cleanTitle(titleText)
                        
                        if (title.isBlank()) return@forEach
                        
                        // Check match
                        val matches = searchTerms.any { term -> title.lowercase().contains(term) }
                        if (matches && results.none { it.url == link }) {
                             val poster = Regex("""src="([^"]+)"""").find(block)?.groupValues?.get(1)
                             val isSeries = SERIES_DETECTION_REGEX.containsMatchIn(titleText)
                             
                             val res = if(isSeries) newTvSeriesSearchResponse(title, link, TvType.TvSeries){ this.posterUrl = poster }
                                       else newMovieSearchResponse(title, link, TvType.Movie){ this.posterUrl = poster }
                             results.add(res)
                        }
                    }

                    if (results.size >= 20) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search error: ${e.message}")
        }

        Log.d(TAG, "Found ${results.size} search results")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "Loading: $url")
        // Fetch latest mainUrl (async, cached)
        fetchMainUrl()

        val document = app.get(url, headers = headers).document

        // Extract title using Regex
        val rawTitle = document.selectFirst("h1.single-title, .entry-title, h1.post-title, h1")?.text()?.trim()
            ?: return null
        val title = cleanTitle(rawTitle)

        // Extract poster using Regex patterns
        val posterMeta = document.selectFirst("meta[property=og:image]")?.attr("content")
        val posterImg = document.selectFirst(".entry-content img[src*='tmdb'], .entry-content img, .post-content img")?.attr("src")
        val poster: String? = posterMeta ?: posterImg

        // Extract description
        val descMeta = document.selectFirst("meta[name=description]")?.attr("content")
        val descOg = document.selectFirst("meta[property=og:description]")?.attr("content")
        val description: String? = descMeta ?: descOg

        // Extract year using companion object YEAR_REGEX
        val year = YEAR_REGEX.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        // Extract tags/genres
        val tags = document.select(".entry-categories a, .post-categories a, .cat-links a, a[rel=tag]").map { it.text() }

        // Determine if series using companion object SERIES_DETECTION_REGEX
        val isSeries = SERIES_DETECTION_REGEX.containsMatchIn(rawTitle)

        return if (isSeries) {
            // Detect episodes from HTML text (no link extraction needed)
            val episodes = detectEpisodesFromHtml(document, url)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            // For movies: pass URL as data, link extraction happens in loadLinks
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }
    }

    private data class DownloadLink(
        val url: String,
        val quality: Int,
        val sizeMB: Double,
        val originalText: String
    )

    // ═══════════════════════════════════════════════════════════════════
    // Detect episode numbers from HTML text (without extracting download links)
    // This allows load() to create episode list without parsing links
    // ═══════════════════════════════════════════════════════════════════
    private fun detectEpisodesFromHtml(document: org.jsoup.nodes.Document, pageUrl: String): List<com.lagradost.cloudstream3.Episode> {
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val detectedEpisodes = mutableSetOf<Int>()
        
        Log.d(TAG, "=== detectEpisodesFromHtml START ===")
        
        // Get full page text for episode detection
        val bodyText = document.body().text()
        val bodyHtml = document.body().html()
        
        // Method 1: Find all episode numbers from text using EPISODE_NUMBER_REGEX
        // Matches: "EPiSODE 1", "Episode 2", "EP-03", "EP 4", "E05"
        EPISODE_NUMBER_REGEX.findAll(bodyText).forEach { match ->
            val epNum = match.groupValues[1].toIntOrNull()
            if (epNum != null && epNum > 0 && epNum < 500) { // Reasonable episode range
                detectedEpisodes.add(epNum)
            }
        }
        
        // Method 2: Also check HTML for episode patterns (in case text extraction missed some)
        EPISODE_NUMBER_REGEX.findAll(bodyHtml).forEach { match ->
            val epNum = match.groupValues[1].toIntOrNull()
            if (epNum != null && epNum > 0 && epNum < 500) {
                detectedEpisodes.add(epNum)
            }
        }
        
        Log.d(TAG, "Detected episode numbers: $detectedEpisodes")
        
        // Create episodes from detected numbers
        if (detectedEpisodes.isNotEmpty()) {
            detectedEpisodes.sorted().forEach { episodeNum ->
                val data = "$pageUrl|||$episodeNum"
                episodes.add(
                    newEpisode(data) {
                        this.name = "Episode $episodeNum"
                        this.episode = episodeNum
                    }
                )
            }
        }
        
        // Fallback: If no episodes detected, check for batch/complete season indicators
        if (episodes.isEmpty()) {
            val hasBatchIndicator = BATCH_DOWNLOAD_REGEX.containsMatchIn(bodyText) || 
                                    bodyText.contains("Complete", true) ||
                                    bodyText.contains("All Episodes", true)
            
            if (hasBatchIndicator) {
                // Add a single "Full Season" episode
                val data = "$pageUrl|||0"
                episodes.add(
                    newEpisode(data) {
                        this.name = "Full Season"
                        this.episode = 1
                    }
                )
                Log.d(TAG, "Added Full Season episode (batch detected)")
            }
        }
        
        Log.d(TAG, "=== detectEpisodesFromHtml END === Total episodes: ${episodes.size}")
        return episodes.sortedBy { it.episode }
    }

    private fun isValidDownloadLink(url: String): Boolean {
        // Use companion object VALID_HOSTS list
        return VALID_HOSTS.any { url.contains(it, ignoreCase = true) }
    }

    private fun extractQuality(text: String): Int {
        // Use companion object QUALITY_REGEX for primary extraction
        val match = QUALITY_REGEX.find(text)

        return match?.groupValues?.get(1)?.toIntOrNull() ?: when {
            text.contains("4K", ignoreCase = true) -> 2160
            text.contains("UHD", ignoreCase = true) -> 2160
            text.contains("2160", ignoreCase = true) -> 2160
            text.contains("1080", ignoreCase = true) -> 1080
            text.contains("720", ignoreCase = true) -> 720
            text.contains("480", ignoreCase = true) -> 480
            text.contains("360", ignoreCase = true) -> 360
            text.contains("HDR", ignoreCase = true) -> 2160  // HDR usually 4K
            text.contains("HEVC", ignoreCase = true) -> 1080 // HEVC default to 1080p
            else -> 0
        }
    }

    private fun parseFileSize(text: String): Double {
        // Use companion object FILE_SIZE_REGEX for extraction
        val match = FILE_SIZE_REGEX.find(text) ?: return 0.0

        val value = match.groupValues[1].toDoubleOrNull() ?: return 0.0
        val unit = match.groupValues[2].uppercase()

        return if (unit == "GB") value * 1024 else value
    }

    // Server speed priority (higher = faster/preferred)
    private fun getServerPriority(serverName: String): Int {
        return when {
            serverName.contains("Instant", true) -> 100  // Instant DL = fastest
            serverName.contains("Direct", true) -> 90
            serverName.contains("10Gbps", true) -> 88
            serverName.contains("FSLv2", true) -> 85
            serverName.contains("FSL", true) -> 80
            serverName.contains("Download File", true) -> 70
            serverName.contains("Pixel", true) -> 60
            else -> 50
        }
    }

    // Helper function to extract download links from document
    // UPDATED: Using Jsoup selectors (more reliable than regex)
    private fun extractDownloadLinks(document: org.jsoup.nodes.Document): List<DownloadLink> {
        val downloadLinks = mutableListOf<DownloadLink>()
        
        // ═══════════════════════════════════════════════════════════════════
        
        Log.d(TAG, "=== extractDownloadLinks START ===")

        document.select("a[href*='gadgetsweb.xyz']").forEach { element ->
            val url = element.attr("href")
            val linkText = element.text().trim()
            
            if (url.isBlank() || downloadLinks.any { it.url == url }) return@forEach
            
            // Check if this is an episode link
            val epMatch = EPISODE_NUMBER_REGEX.find(linkText)
            val episodeNum = epMatch?.groupValues?.get(1)?.toIntOrNull()
            
            val episodeContext = when {
                episodeNum != null -> "EPiSODE $episodeNum"
                linkText.contains("HEVC", true) || linkText.contains("4K", true) || 
                linkText.contains("1080p", true) || linkText.contains("720p", true) ||
                linkText.contains("480p", true) || linkText.contains("2160p", true) -> "BATCH | $linkText"
                linkText.isNotBlank() -> linkText
                else -> return@forEach
            }
            
            downloadLinks.add(
                DownloadLink(
                    url = url,
                    quality = extractQuality(episodeContext),
                    sizeMB = parseFileSize(episodeContext),
                    originalText = episodeContext
                )
            )
            Log.d(TAG, "Found: $episodeContext -> $url")
        }
        
        // Method 2: Find hblinks/4khdhub links
        document.select("a[href*='hblinks'], a[href*='4khdhub']").forEach { element ->
            val url = element.attr("href")
            val linkText = element.text().trim()
            
            if (url.isBlank() || downloadLinks.any { it.url == url }) return@forEach
            if (shouldBlockUrl(url)) return@forEach
            
            val epMatch = EPISODE_NUMBER_REGEX.find(linkText)
            val episodeContext = if (epMatch != null) {
                "EPiSODE ${epMatch.groupValues[1]}"
            } else {
                linkText.ifBlank { "Download" }
            }
            
            downloadLinks.add(
                DownloadLink(
                    url = url,
                    quality = extractQuality(episodeContext),
                    sizeMB = parseFileSize(episodeContext),
                    originalText = episodeContext
                )
            )
            Log.d(TAG, "Found hblinks: $episodeContext -> $url")
        }
        
        // Method 3: Find hubdrive/hubcloud links (for movies/batch)
        document.select("a[href*='hubdrive'], a[href*='hubcloud']").forEach { element ->
            val url = element.attr("href")
            val linkText = element.text().trim()
            
            if (url.isBlank() || downloadLinks.any { it.url == url }) return@forEach
            if (shouldBlockUrl(url)) return@forEach
            
            val epMatch = EPISODE_NUMBER_REGEX.find(linkText)
            val episodeContext = when {
                epMatch != null -> "EPiSODE ${epMatch.groupValues[1]}"
                linkText.isNotBlank() -> linkText
                else -> "Download"
            }
            
            downloadLinks.add(
                DownloadLink(
                    url = url,
                    quality = extractQuality(episodeContext),
                    sizeMB = parseFileSize(episodeContext),
                    originalText = episodeContext
                )
            )
        }
        
        Log.d(TAG, "Total download links: ${downloadLinks.size}")

        // Smart sort: 1080p priority → HEVC/X265 → X264 → Smallest Size → Fastest Server
        return downloadLinks.sortedWith(
            compareByDescending<DownloadLink> {
                when (it.quality) {
                    1080 -> 100
                    2160 -> 90
                    720 -> 70
                    480 -> 50
                    else -> 30
                }
            }.thenByDescending {
                // PRIORITY: HEVC/X265 > X264 (HEVC = smaller files, better compression)
                val text = it.originalText.lowercase() + it.url.lowercase()
                when {
                    text.contains("hevc") || text.contains("x265") || text.contains("h265") || text.contains("h.265") -> 150
                    text.contains("x264") || text.contains("h264") || text.contains("h.264") -> 100
                    else -> 50
                }
            }.thenBy {
                if (it.sizeMB > 0) it.sizeMB else Double.MAX_VALUE
            }.thenByDescending {
                getServerPriority(it.originalText)
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "Loading links from: $data")

        try {
            // Parse data format: "pageUrl|||episodeNum" or just "pageUrl" (for movies)
            val parts = data.split("|||")
            val pageUrl = parts[0]
            val episodeNum = if (parts.size > 1) parts[1].toIntOrNull() else null

            Log.d(TAG, "Page URL: $pageUrl, Episode: $episodeNum")

            // Fetch page and extract all download links
            val document = app.get(pageUrl, headers = headers).document
            val allLinks = extractDownloadLinks(document)

            Log.d(TAG, "Total links: ${allLinks.size}")
            
            // Debug: Log all found links
            allLinks.forEachIndexed { index, link ->
                Log.d(TAG, "Link[$index]: ${link.originalText} -> ${link.url.take(60)}...")
            }

            // Filter by episode if needed
            val targetLinks = when {
                episodeNum == null -> allLinks  // Movie
                episodeNum == 0 -> allLinks.filter { 
                    !EPISODE_NUMBER_REGEX.containsMatchIn(it.originalText) 
                }.ifEmpty { allLinks }  // Full season
                else -> allLinks.filter { link ->
                    val epNum = EPISODE_NUMBER_REGEX.find(link.originalText)?.groupValues?.get(1)?.toIntOrNull()
                    epNum == episodeNum
                }.ifEmpty { allLinks }  // Specific episode
            }

            Log.d(TAG, "Filtered links: ${targetLinks.size}")
            
            // Debug: Log filtered links
            targetLinks.forEachIndexed { index, link ->
                Log.d(TAG, "Target[$index]: ${link.originalText}")
            }

            // Sort by priority: HEVC 1080p (small) > X264 1080p (small) > 720p
            // STRICT ORDER: Prefer smaller files, avoid HQ/large files
            val sortedLinks = targetLinks
                .filter { !shouldBlockUrl(it.url) }  // Block streaming URLs
                .filter { 
                    val text = it.originalText.lowercase()
                    
                    // 1. REMOVE SAMPLES & TRAILERS
                    val isSample = text.contains("sample") || text.contains("trailer")
                    
                    // 2. REMOVE SMALL FILES (Prevents playing 67MB clips instead of full movies)
                    // Threshold: 150MB for movies. Episodes might be smaller but usually >100MB for 720p
                    val isTooSmall = it.sizeMB > 0 && it.sizeMB < 150
                    
                    // 3. REMOVE HQ/LARGE FILES (> 4GB)
                    val isHQ = text.contains("hq ") || text.contains("hq-") || 
                               text.contains("[hq]") || text.contains(" hq") ||
                               text.startsWith("hq") || text.contains("hq:") ||
                               Regex("""hq\s*1080""").containsMatchIn(text) ||
                               Regex("""hq\s*2160""").containsMatchIn(text)
                    val isTooLarge = it.sizeMB > 4000
                    
                    if (isSample) Log.d(TAG, "SKIPPED Sample: ${it.originalText}")
                    if (isTooSmall) Log.d(TAG, "SKIPPED Too Small (${it.sizeMB}MB): ${it.originalText}")
                    
                    !isSample && !isTooSmall && !isHQ && !isTooLarge
                }
                .sortedWith(
                    compareByDescending<DownloadLink> {
                        // PRIORITY 1, 2, 3: Quality + Codec Combination
                        val text = it.originalText.lowercase()
                        val isHevc = text.contains("hevc") || text.contains("x265") || text.contains("h265") || text.contains("10bit")
                        val isX264 = text.contains("x264") || text.contains("h264") || text.contains("avc") || !isHevc // Assume x264 if not HEVC
                        
                        when {
                            // 1st Priority: 1080p x264
                            it.quality == 1080 && isX264 -> 400
                            // 2nd Priority: 720p x264
                            it.quality == 720 && isX264 -> 300
                            // 3rd Priority: 1080p HEVC (x265)
                            it.quality == 1080 && isHevc -> 200
                            // 4th Priority: 720p HEVC
                            it.quality == 720 && isHevc -> 100
                            // Lower priority: 480p etc
                            else -> 50
                        }
                    }.thenBy {
                        // PRIORITY 4 & 5: Smallest Size within that quality
                        // Smallest size = Ascending order (it.sizeMB)
                        if (it.sizeMB > 0) it.sizeMB else Double.MAX_VALUE
                    }.thenByDescending {
                        // PRIORITY 6: Fastest Server
                        getServerPriority(it.originalText)
                    }
                )

            // Process top 8 links (extractors do the heavy work)
            // Increased from 5 to 8 to handle more episode links
            sortedLinks.take(8).amap { downloadLink ->
                withTimeoutOrNull(9000L) { // Force timeout after 9s to prevent delays
                    try {
                        val link = downloadLink.url
                        Log.d(TAG, "Extracting: $link")
                        
                        when {
                            // HubCloud direct links
                            link.contains("hubcloud", true) || link.contains("gamerxyt", true) ->
                                HubCloud().getUrl(link, mainUrl, subtitleCallback, callback)

                            // Hblinks download pages (archives)
                            link.contains("hblinks", true) && link.contains("/archives/", true) ->
                                Hblinks().getUrl(link, mainUrl, subtitleCallback, callback)

                            // Hubdrive links
                            link.contains("hubdrive", true) ->
                                Hubdrive().getUrl(link, mainUrl, subtitleCallback, callback)

                            // gadgetsweb mediator and hubcdn instant download
                            link.contains("gadgetsweb", true) || link.contains("hubcdn", true) ->
                                HUBCDN().getUrl(link, mainUrl, subtitleCallback, callback)

                            else -> Log.w(TAG, "No extractor for: $link")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error extracting ${downloadLink.url}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadLinks: ${e.message}")
        }

        return true
    }

    private fun cleanTitle(title: String): String {
        // Enhanced Regex patterns based on website title formats
        // Example: "Fallout (Season 2) WEB-DL [Hindi (DD5.1) & English] 4K 1080p 720p & 480p [x264/10Bit-HEVC] | PrimeVideo Series | [EP-06 Added]"
        return title
            .replace(Regex("""\(\d{4}\)"""), "")  // Remove year (2024)
            .replace(Regex("""\[.*?]"""), "")     // Remove bracket content [Hindi...]
            .replace(Regex("""\|.*$"""), "")      // Remove pipe and after | PrimeVideo...
            .replace(Regex("""(?i)(WEB-?DL|BluRay|HDRip|WEBRip|HDTV|DVDRip|BRRip)"""), "")  // Source tags
            .replace(Regex("""(?i)(4K|UHD|1080p|720p|480p|360p|2160p)"""), "")  // Quality tags
            .replace(Regex("""(?i)(HEVC|x264|x265|10Bit|H\.?264|H\.?265|AAC|DD5?\.?1?)"""), "")  // Codec tags
            .replace(Regex("""(?i)(Download|Free|Full|Movie|HD|Watch)"""), "")  // Generic words
            .replace(Regex("""(?i)(Hindi|English|Dual\s*Audio|ESub|Multi)"""), "")  // Language tags
            .replace(Regex("""[&+]"""), " ")       // Replace & + with space
            .replace(Regex("""\s+"""), " ")        // Normalize multiple spaces
            .trim()
    }
}