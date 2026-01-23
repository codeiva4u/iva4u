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
            """https?://(?:hubdrive\.(?:space|art)|gadgetsweb\.xyz|hubcloud\.[a-z]+|hblinks\.[a-z]+|4khdhub\.(?:dad|fans)|hubcdn\.[a-z]+|gamerxyt\.com)[^"'<\s>]*""",
            RegexOption.IGNORE_CASE
        )
        
        // Valid download hosts list
        private val VALID_HOSTS = listOf(
            "hubdrive", "gadgetsweb", "hubcloud", "hubcdn",
            "gamerxyt", "gamester", "hblinks", "4khdhub"
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
            
            val document = app.get(searchUrl, headers = headers, timeout = 30).document

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
                    val doc = app.get(url, headers = headers, timeout = 30).document

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

        val document = app.get(url, headers = headers, timeout = 20).document

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
            // Extract episodes for series - need to parse links for episode grouping
            val sortedLinks = extractDownloadLinks(document)
            val episodes = parseEpisodes(document, sortedLinks, url)

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

    private fun parseEpisodes(@Suppress("UNUSED_PARAMETER") document: org.jsoup.nodes.Document, links: List<DownloadLink>, pageUrl: String): List<com.lagradost.cloudstream3.Episode> {
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()

        Log.d("HDhub4uProvider", "=== parseEpisodes START ===")
        Log.d("HDhub4uProvider", "Total links: ${links.size}")

        // ═══════════════════════════════════════════════════════════════════
        // STEP 1: Separate batch download links from individual episode links
        // Batch links: "4K | SDR | HDR | HEVC", "WEB-DL | HEVC" (ZIP downloads)
        // ═══════════════════════════════════════════════════════════════════
        val batchLinks = links.filter { link ->
            BATCH_DOWNLOAD_REGEX.containsMatchIn(link.originalText) &&
            !EPISODE_NUMBER_REGEX.containsMatchIn(link.originalText)
        }
        val episodeLinks = links.filter { link ->
            !BATCH_DOWNLOAD_REGEX.containsMatchIn(link.originalText) ||
            EPISODE_NUMBER_REGEX.containsMatchIn(link.originalText)
        }

        Log.d("HDhub4uProvider", "Batch links: ${batchLinks.size}, Episode links: ${episodeLinks.size}")

        // ═══════════════════════════════════════════════════════════════════
        // STEP 2: Group individual episode links by episode number
        // ═══════════════════════════════════════════════════════════════════
        val groupedByEpisode = episodeLinks.groupBy { link ->
            val episodeNum = EPISODE_NUMBER_REGEX.find(link.originalText)?.groupValues?.get(1)?.toIntOrNull()
            if (episodeNum == null || episodeNum == 0) 0 else episodeNum
        }

        Log.d("HDhub4uProvider", "Episode grouping: ${groupedByEpisode.keys}")

        // Create episodes from grouped links - pass pageUrl + episodeNum as data
        if (groupedByEpisode.size > 1 || (groupedByEpisode.size == 1 && groupedByEpisode.keys.first() != 0)) {
            groupedByEpisode.forEach { (episodeNum, _) ->
                if (episodeNum > 0) {
                    // Data format: "pageUrl|||episodeNum" - links will be extracted in loadLinks
                    val data = "$pageUrl|||$episodeNum"
                    episodes.add(
                        newEpisode(data) {
                            this.name = "Episode $episodeNum"
                            this.episode = episodeNum
                        }
                    )
                }
            }
        }

        // If still no individual episodes, try position-based assignment
        if (episodes.isEmpty() && episodeLinks.isNotEmpty()) {
            val downloadLinks = episodeLinks.filter {
                it.url.contains("gadgetsweb", true) && it.originalText.contains("episode", true)
            }

            if (downloadLinks.isNotEmpty()) {
                downloadLinks.forEachIndexed { index, _ ->
                    val episodeNum = index + 1
                    val data = "$pageUrl|||$episodeNum"
                    episodes.add(
                        newEpisode(data) {
                            this.name = "Episode $episodeNum"
                            this.episode = episodeNum
                        }
                    )
                }
                Log.d("HDhub4uProvider", "Created ${episodes.size} episodes from download links")
            } else if (batchLinks.isEmpty()) {
                // Fallback only if no batch links: treat as full season
                val data = "$pageUrl|||0"
                episodes.add(
                    newEpisode(data) {
                        this.name = "Full Season"
                        this.episode = 1
                    }
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // STEP 3: Add batch download links as special episodes at the end
        // These are ZIP/batch downloads for all episodes (4K HEVC, WEB-DL, etc.)
        // ═══════════════════════════════════════════════════════════════════
        if (batchLinks.isNotEmpty()) {
            val maxEpisode = episodes.maxOfOrNull { it.episode ?: 0 } ?: 0
            var batchEpisodeNum = maxOf(maxEpisode + 100, 900) // Start batch eps at 900+

            // Group batch links by quality/type for cleaner episode names
            val batchByQuality = batchLinks.groupBy { link ->
                val text = link.originalText.uppercase()
                when {
                    text.contains("4K") && text.contains("HDR") -> "4K HDR"
                    text.contains("4K") && text.contains("SDR") -> "4K SDR"
                    text.contains("4K") -> "4K HEVC"
                    text.contains("2160") -> "4K"
                    text.contains("1080") && text.contains("HEVC") -> "1080p HEVC"
                    text.contains("1080") -> "1080p"
                    text.contains("WEB-DL") && text.contains("HEVC") -> "WEB-DL HEVC"
                    text.contains("WEB-DL") -> "WEB-DL"
                    text.contains("HEVC") && text.contains("X264") -> "HEVC + x264"
                    text.contains("HEVC") -> "HEVC"
                    text.contains("720") -> "720p"
                    text.contains("480") -> "480p"
                    else -> "Batch"
                }
            }

            batchByQuality.forEach { (qualityName, _) ->
                // Data format: "pageUrl|||batch_qualityName" - links will be extracted in loadLinks
                val data = "$pageUrl|||batch_$qualityName"
                
                episodes.add(
                    newEpisode(data) {
                        this.name = "All Episodes ($qualityName)"
                        this.episode = batchEpisodeNum
                    }
                )
                Log.d("HDhub4uProvider", "Batch episode added: All Episodes ($qualityName) - EP#$batchEpisodeNum")
                batchEpisodeNum++
            }
        }

        Log.d("HDhub4uProvider", "=== parseEpisodes END === Total episodes: ${episodes.size}")
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
            serverName.contains("FSLv2", true) -> 85
            serverName.contains("FSL", true) -> 80
            serverName.contains("10Gbps", true) -> 88
            serverName.contains("Download File", true) -> 70
            serverName.contains("Pixel", true) -> 60
            serverName.contains("Buzz", true) -> 55
            else -> 50
        }
    }

    // Helper function to extract download links from document
    private fun extractDownloadLinks(document: org.jsoup.nodes.Document): List<DownloadLink> {
        val downloadLinks = mutableListOf<DownloadLink>()
        
        // Method 1: Use CSS selectors to find all links on page
        document.select("a[href]").forEach { element ->
            val href = element.attr("href")
            val text = element.text().trim()

            val parentText = element.parent()?.text()?.trim() ?: ""
            val prevSiblingText = element.previousElementSibling()?.text()?.trim() ?: ""

            val episodeContext: String = when {
                text.contains("episode", true) || text.contains("ep", true) -> text
                prevSiblingText.contains("episode", true) -> "$prevSiblingText | $text"
                parentText.contains("episode", true) -> parentText
                text.isNotBlank() -> text
                parentText.isNotBlank() -> parentText
                else -> href
            }

            if (isValidDownloadLink(href) && downloadLinks.none { it.url == href }) {
                val qualityText = episodeContext.ifBlank { href }
                val quality = extractQuality(qualityText)
                val size = parseFileSize(episodeContext)

                downloadLinks.add(
                    DownloadLink(
                        url = href,
                        quality = quality,
                        sizeMB = size,
                        originalText = episodeContext
                    )
                )
            }
        }

        // Method 2: Use companion object DOWNLOAD_URL_REGEX for any missed links
        val bodyHtml = document.body().html()

        DOWNLOAD_URL_REGEX.findAll(bodyHtml).forEach { match ->
            val linkUrl = match.value
            if (downloadLinks.none { it.url == linkUrl }) {
                val quality = extractQuality(linkUrl)

                val startPos = maxOf(0, match.range.first - 100)
                val surroundingText = bodyHtml.substring(startPos, match.range.first)
                val episodeMatch = EPISODE_NUMBER_REGEX.findAll(surroundingText).lastOrNull()
                val episodeContext = if (episodeMatch != null) {
                    "EPiSODE ${episodeMatch.groupValues[1]}"
                } else {
                    ""
                }

                downloadLinks.add(
                    DownloadLink(
                        url = linkUrl,
                        quality = quality,
                        sizeMB = 0.0,
                        originalText = episodeContext
                    )
                )
            }
        }

        // Smart sort: 1080p priority → H264 codec → Smallest Size → Fastest Server
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
                val text = it.originalText.lowercase() + it.url.lowercase()
                when {
                    text.contains("x264") || text.contains("h264") || text.contains("h.264") -> 100
                    text.contains("hevc") || text.contains("x265") || text.contains("h265") || text.contains("h.265") -> 10
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
            val episodeIdentifier = if (parts.size > 1) parts[1] else null

            Log.d(TAG, "Page URL: $pageUrl, Episode: $episodeIdentifier")

            // Fetch page and extract all download links
            val document = app.get(pageUrl, headers = headers, timeout = 20).document
            val allLinks = extractDownloadLinks(document)

            Log.d(TAG, "Total links extracted: ${allLinks.size}")

            // Filter links based on episode number or batch type
            val linksToProcess = when {
                // Movie (no episode identifier)
                episodeIdentifier == null -> allLinks

                // Batch download (e.g., "batch_4K HDR")
                episodeIdentifier.startsWith("batch_") -> {
                    val qualityName = episodeIdentifier.removePrefix("batch_")
                    allLinks.filter { link ->
                        val text = link.originalText.uppercase()
                        BATCH_DOWNLOAD_REGEX.containsMatchIn(link.originalText) &&
                        !EPISODE_NUMBER_REGEX.containsMatchIn(link.originalText) &&
                        when (qualityName) {
                            "4K HDR" -> text.contains("4K") && text.contains("HDR")
                            "4K SDR" -> text.contains("4K") && text.contains("SDR")
                            "4K HEVC" -> text.contains("4K") && !text.contains("HDR") && !text.contains("SDR")
                            "4K" -> text.contains("2160")
                            "1080p HEVC" -> text.contains("1080") && text.contains("HEVC")
                            "1080p" -> text.contains("1080") && !text.contains("HEVC")
                            "WEB-DL HEVC" -> text.contains("WEB-DL") && text.contains("HEVC")
                            "WEB-DL" -> text.contains("WEB-DL") && !text.contains("HEVC")
                            "HEVC + x264" -> text.contains("HEVC") && text.contains("X264")
                            "HEVC" -> text.contains("HEVC")
                            "720p" -> text.contains("720")
                            "480p" -> text.contains("480")
                            else -> true
                        }
                    }
                }

                // Full season (episode 0)
                episodeIdentifier == "0" -> {
                    allLinks.filter { link ->
                        !EPISODE_NUMBER_REGEX.containsMatchIn(link.originalText)
                    }
                }

                // Specific episode number
                else -> {
                    val targetEpisode = episodeIdentifier.toIntOrNull() ?: 0
                    allLinks.filter { link ->
                        val epNum = EPISODE_NUMBER_REGEX.find(link.originalText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        epNum == targetEpisode
                    }
                }
            }

            Log.d(TAG, "Filtered links count: ${linksToProcess.size}")

            // Sort links with priority: hubcloud > hblinks > hubdrive > gadgetsweb
            val sortedLinks = linksToProcess.sortedByDescending { link ->
                when {
                    link.url.contains("hubcloud", true) -> 100
                    link.url.contains("hblinks", true) -> 90
                    link.url.contains("hubdrive", true) -> 80
                    link.url.contains("gadgetsweb", true) -> 50
                    else -> 30
                }
            }

            // Take top 3 links for extraction
            sortedLinks.take(3).amap { downloadLink ->
                try {
                    val link = downloadLink.url
                    Log.d(TAG, "Extracting: $link")
                    
                    when {
                        link.contains("hubdrive", true) ->
                            Hubdrive().getUrl(link, mainUrl, subtitleCallback, callback)

                        link.contains("hubcloud", true) ||
                                link.contains("gamerxyt", true) ||
                                link.contains("gamester", true) ->
                            HubCloud().getUrl(link, mainUrl, subtitleCallback, callback)

                        link.contains("gadgetsweb", true) ->
                            HUBCDN().getUrl(link, mainUrl, subtitleCallback, callback)

                        link.contains("hubcdn", true) ->
                            HubCloud().getUrl(link, mainUrl, subtitleCallback, callback)

                        link.contains("hblinks", true) ||
                                link.contains("4khdhub", true) ->
                            Hblinks().getUrl(link, mainUrl, subtitleCallback, callback)

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