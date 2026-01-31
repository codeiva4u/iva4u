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
        // FIXED: Added negative lookahead (?!\d+p) to prevent matching quality patterns like "1720p"
        // When HTML text is extracted, "EPiSODE 1" + "720p" becomes "EPiSODE 1720p" without space
        private val EPISODE_NUMBER_REGEX = Regex(
            """(?i)(?:EPiSODE|EPISODE|Episode|EP|E)[-.\s]*(\d{1,3})(?!\d+p)"""
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

    override var mainUrl: String = "https://new2.hdhub4u.*"

    // Fast async domain fetch with 3s timeout - non-blocking
    private suspend fun fetchMainUrl(): String {
        if (cachedMainUrl != null) return cachedMainUrl!!
        if (urlsFetched) return mainUrl

        urlsFetched = true
        try {
            val result = withTimeoutOrNull(10_000L) {  // Reduced from 3s to s10s
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

        Log.d(TAG, "Loading main page: $url")
        val document = app.get(url, headers = headers).document

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
            
            val document = app.get(searchUrl, headers = headers).document

            // Search page uses different structure: li.movie-card
            document.select("li.movie-card").forEach { card ->
                val link = card.selectFirst("a[href]")
                val img = card.selectFirst("img")
                
                val href = link?.attr("href") ?: return@forEach
                if (href.isBlank() || href.contains("category")) return@forEach
                
                val fixedUrl = fixUrl(href)
                val titleText = img?.attr("alt") ?: img?.attr("title") ?: ""
                if (titleText.isBlank()) return@forEach
                
                val title = cleanTitle(titleText)
                if (title.isBlank()) return@forEach
                
                val posterUrl = fixUrlNull(img?.attr("src"))
                
                // Determine type using Regex pattern for series detection
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
                    val doc = app.get(url, headers = headers).document

                    doc.select("li.thumb").forEach { element ->
                        val searchResult = element.toSearchResult()
                        if (searchResult != null) {
                            val title = searchResult.name.lowercase()
                            val matches = searchTerms.any { term -> title.contains(term) }

                            if (matches && results.none { it.url == searchResult.url }) {
                                results.add(searchResult)
                            }
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
        val originalText: String,
        val episodeNum: Int? = null  // Track which episode this link belongs to
    )

    // ═══════════════════════════════════════════════════════════════════
    // Detect episode numbers from HTML text (without extracting download links)
    // This allows load() to create episode list without parsing links
    // ═══════════════════════════════════════════════════════════════════
    private fun detectEpisodesFromHtml(document: org.jsoup.nodes.Document, pageUrl: String): List<com.lagradost.cloudstream3.Episode> {
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val detectedEpisodes = mutableSetOf<Int>()
        
        Log.d(TAG, "=== detectEpisodesFromHtml START ===")
        
        // ═══════════════════════════════════════════════════════════════════
        // FIXED: Use HTML structure-based extraction instead of text-based
        // HDhub4u format: <h4><strong>EPiSODE 1</strong></h4> followed by <h4>720p...</h4>
        // Text extraction concatenates as "EPiSODE 1720p" causing false matches
        // ═══════════════════════════════════════════════════════════════════
        
        // Method 1: Extract from HTML structure (h4/h3 > strong with EPiSODE pattern)
        // This is the PRIMARY and most reliable method for HDhub4u
        document.select("h4 strong, h3 strong, h4 span strong, h3 span strong, h5 strong").forEach { element ->
            val text = element.text().trim()
            val match = EPISODE_NUMBER_REGEX.find(text)
            val epNum = match?.groupValues?.get(1)?.toIntOrNull()
            if (epNum != null && epNum > 0 && epNum < 500) {
                detectedEpisodes.add(epNum)
                Log.d(TAG, "Found episode from HTML structure: $epNum (text: $text)")
            }
        }
        
        // Method 2: Extract from anchor tags with EPiSODE text
        // Some pages (like Beast Games) have: <a href="...">EPiSODE 1</a> pattern
        // Run always to catch different page structures (hubstream.art, gadgetsweb, etc.)
        document.select("a[href]").forEach { element ->
            val linkText = element.text().trim()
            // Only match explicit episode link text patterns (EPiSODE 1, EP-1, Episode 1)
            if (linkText.matches(Regex("(?i)^EP(?:i|I)?SODE\\s*\\d+$")) ||
                linkText.matches(Regex("(?i)^EP[-.\\s]?\\d+$"))) {
                val match = EPISODE_NUMBER_REGEX.find(linkText)
                val epNum = match?.groupValues?.get(1)?.toIntOrNull()
                if (epNum != null && epNum > 0 && epNum < 500) {
                    detectedEpisodes.add(epNum)
                    Log.d(TAG, "Found episode from link text: $epNum (text: $linkText)")
                }
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
            val bodyText = document.body().text()
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
    // FIXED: Track episode context by parsing elements in document order
    private fun extractDownloadLinks(document: org.jsoup.nodes.Document): List<DownloadLink> {
        val downloadLinks = mutableListOf<DownloadLink>()
        val seenUrls = mutableSetOf<String>()
        
        Log.d(TAG, "=== extractDownloadLinks START ===")
        
        // ═══════════════════════════════════════════════════════════════════
        // FIXED: Parse all elements in document order to track episode context
        // This ensures links are correctly associated with their episode section
        // ═══════════════════════════════════════════════════════════════════
        var currentEpisode: Int? = null
        
        // Select all relevant elements: headers (for episode detection) and links
        val relevantSelector = "h3, h4, h5, a[href*='gadgetsweb'], a[href*='hblinks'], " +
                               "a[href*='4khdhub'], a[href*='hubdrive'], a[href*='hubcloud'], a[href*='hubstream']"
        
        document.select(relevantSelector).forEach { element ->
            val tagName = element.tagName().uppercase()
            
            // Check if this is an episode header
            if (tagName in listOf("H3", "H4", "H5")) {
                val headerText = element.text().trim()
                val epMatch = EPISODE_NUMBER_REGEX.find(headerText)
                val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull()
                if (epNum != null && epNum > 0 && epNum < 500) {
                    currentEpisode = epNum
                    Log.d(TAG, "Episode section detected: $currentEpisode (from: $headerText)")
                }
                return@forEach
            }
            
            // This is a link element
            if (tagName == "A") {
                val url = element.attr("href")
                val linkText = element.text().trim()
                
                // Skip if blank, already seen, or blocked
                if (url.isBlank() || seenUrls.contains(url)) return@forEach
                if (shouldBlockUrl(url)) return@forEach
                
                seenUrls.add(url)
                
                // Determine episode context from link text or current section
                val linkEpMatch = EPISODE_NUMBER_REGEX.find(linkText)
                val linkEpisode = linkEpMatch?.groupValues?.get(1)?.toIntOrNull() ?: currentEpisode
                
                // Build context text for quality detection
                val episodeContext = when {
                    linkEpMatch != null -> "EPiSODE ${linkEpMatch.groupValues[1]} | $linkText"
                    currentEpisode != null && linkText.isNotBlank() -> "EPiSODE $currentEpisode | $linkText"
                    linkText.isNotBlank() -> linkText
                    else -> "Download"
                }
                
                downloadLinks.add(
                    DownloadLink(
                        url = url,
                        quality = extractQuality(episodeContext),
                        sizeMB = parseFileSize(episodeContext),
                        originalText = episodeContext,
                        episodeNum = linkEpisode
                    )
                )
                Log.d(TAG, "Found link: EP=$linkEpisode, text=$linkText, url=${url.take(50)}")
            }
        }
        
        Log.d(TAG, "Total download links: ${downloadLinks.size}")
        
        // Log episode distribution for debugging
        val episodeDistribution = downloadLinks.groupBy { it.episodeNum }.mapValues { it.value.size }
        Log.d(TAG, "Episode distribution: $episodeDistribution")

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

            // Filter by episode if needed (using new episodeNum field)
            val targetLinks = when {
                episodeNum == null -> allLinks  // Movie - return all
                episodeNum == 0 -> allLinks.filter { 
                    it.episodeNum == null  // Full season - only batch/non-episode links
                }.ifEmpty { allLinks }
                else -> {
                    // Specific episode - use episodeNum field (primary) then fallback to text
                    val byField = allLinks.filter { it.episodeNum == episodeNum }
                    if (byField.isNotEmpty()) {
                        Log.d(TAG, "Matched ${byField.size} links by episodeNum field for EP$episodeNum")
                        byField
                    } else {
                        // Fallback: try text-based matching
                        val byText = allLinks.filter { link ->
                            val epNum = EPISODE_NUMBER_REGEX.find(link.originalText)?.groupValues?.get(1)?.toIntOrNull()
                            epNum == episodeNum
                        }
                        Log.d(TAG, "Matched ${byText.size} links by text for EP$episodeNum")
                        byText.ifEmpty { allLinks }  // Last resort: all links
                    }
                }
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
                    // Skip HQ files (usually 5GB+) - prefer standard quality
                    val text = it.originalText.lowercase()
                    // Comprehensive HQ detection patterns
                    val isHQ = text.contains("hq ") || text.contains("hq-") || 
                               text.contains("[hq]") || text.contains(" hq") ||
                               text.startsWith("hq") || text.contains("hq:") ||
                               text.contains("hq|") || text.contains("(hq)") ||
                               Regex("""hq\s*1080""").containsMatchIn(text) ||
                               Regex("""hq\s*2160""").containsMatchIn(text) ||
                               Regex("""hq\s*4k""").containsMatchIn(text)
                    // Skip files > 4GB (HQ files are usually 5-8GB)
                    val isTooLarge = it.sizeMB > 4000
                    
                    if (isHQ) Log.d(TAG, "SKIPPED HQ: ${it.originalText}")
                    if (isTooLarge) Log.d(TAG, "SKIPPED large file (${it.sizeMB}MB): ${it.originalText}")
                    
                    !isHQ && !isTooLarge
                }
                .ifEmpty { 
                    // Fallback: still exclude HQ but allow larger files
                    Log.w(TAG, "All standard links filtered, trying larger files (excluding HQ)")
                    targetLinks.filter { link ->
                        val text = link.originalText.lowercase()
                        val isHQ = text.contains("hq") && (text.contains("1080") || text.contains("2160") || text.contains("4k"))
                        !shouldBlockUrl(link.url) && !isHQ
                    }
                }
                .sortedWith(
                    compareByDescending<DownloadLink> {
                        // PRIORITY 1: HEVC/X265 > X264 (HEVC = smaller files, better compression)
                        val text = it.originalText.lowercase() + it.url.lowercase()
                        when {
                            text.contains("hevc") || text.contains("x265") || text.contains("h265") -> 200
                            text.contains("x264") || text.contains("h264") -> 150
                            else -> 50
                        }
                    }.thenByDescending {
                        // PRIORITY 2: Quality priority: 1080p > 720p > 480p
                        when (it.quality) {
                            1080 -> 100
                            720 -> 70
                            480 -> 50
                            else -> 30
                        }
                    }.thenBy {
                        // PRIORITY 3: Smaller size = higher priority (CRITICAL)
                        if (it.sizeMB > 0) it.sizeMB else Double.MAX_VALUE
                    }.thenByDescending {
                        // PRIORITY 4: Server preference
                        when {
                            it.url.contains("hubcloud", true) -> 100
                            it.url.contains("hblinks", true) -> 90
                            it.url.contains("hubdrive", true) -> 80
                            it.url.contains("gadgetsweb", true) -> 50
                            else -> 30
                        }
                    }
                )

            // Process top 8 links (extractors do the heavy work)
            // Increased from 5 to 8 to handle more episode links
            sortedLinks.take(8).amap { downloadLink ->
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