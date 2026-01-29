package com.moviesdrive

import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.nodes.Element

class MoviesDriveProvider : MainAPI() {
    companion object {
        private const val TAG = "MoviesDriveProvider"
        
        // ═══════════════════════════════════════════════════════════════════
        // REGEX PATTERNS - Based on MoviesDrive Website Analysis (Jan 2026)
        // ═══════════════════════════════════════════════════════════════════
        
        // Series Detection Pattern
        // Matches: "Season 1", "S01", "Episode", "EP", "Complete", "All Episodes"
        private val SERIES_DETECTION_REGEX = Regex(
            """(?i)(Season\s*\d+|S\d+|Episode|EP\s*\d+|Complete|All\s*Episodes|Web[-\s]?Series)"""
        )
        
        // Episode Number Extraction Pattern
        // Matches: "S01 E01", "Ep01", "Episode 1"
        private val EPISODE_NUMBER_REGEX = Regex(
            """(?i)(?:S\d+\s*)?(?:EP?|Episode)[\s-]*(\d+)"""
        )
        
        // Quality Extraction Pattern
        // Matches: 480p, 720p, 1080p, 2160p, 4K
        private val QUALITY_REGEX = Regex("""(\d{3,4})p|4K|2160p""", RegexOption.IGNORE_CASE)
        
        // Year Extraction Pattern
        // Matches: (2024), (2025), (2026)
        private val YEAR_REGEX = Regex("""\((\d{4})\)""")
        
        // File Size Extraction Pattern
        // Matches: [420MB], [1.6GB], 568.12 MB, 2.24 GB
        private val FILE_SIZE_REGEX = Regex(
            """(?:\[)?(\d+(?:\.\d+)?)\s*(GB|MB)(?:\])?""", RegexOption.IGNORE_CASE
        )
        
        // Download URL Pattern - Valid hosts for MoviesDrive
        // Based on analysis: mdrive.lol
        private val DOWNLOAD_URL_REGEX = Regex(
            """https?://(?:mdrive\.lol)[^\s"'<>]*""",
            RegexOption.IGNORE_CASE
        )
        
        // Valid download hosts list
        private val VALID_HOSTS = listOf(
            "mdrive.lol"
        )
    }

    // Cached domain URL - fetched once per session (async, no blocking)
    private var cachedMainUrl: String? = null
    private var urlsFetched = false

    override var mainUrl: String = "https://new1.moviesdrive.surf"

    // Fast async domain fetch with 3s timeout - non-blocking
    private suspend fun fetchMainUrl(): String {
        if (cachedMainUrl != null) return cachedMainUrl!!
        if (urlsFetched) return mainUrl

        urlsFetched = true
        try {
            val result = withTimeoutOrNull(3_000L) {
                val response = app.get(
                    "https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json"
                )
                val json = response.text
                val jsonObject = JSONObject(json)
                val urlString = jsonObject.optString("moviesdrive")
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

    override var name = "MoviesDrive"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/bollywood/" to "Bollywood",
        "category/hollywood/" to "Hollywood",
        "category/south/" to "South Indian",
        "category/web/" to "Web Series",
        "category/netflix/" to "Netflix",
        "category/amzn-prime-video/" to "Amazon Prime",
        "category/2160p-4k/" to "4K Movies"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    )

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

        // MoviesDrive uses .poster-card structure for movie items
        val home = document.select(".poster-card").mapNotNull {
            it.toSearchResult()
        }

        Log.d(TAG, "Found ${home.size} items")

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Structure: .poster-card > a[href] for link
        // .poster-title for title
        // .poster-image img for poster
        // .poster-quality for quality badge

        val linkElement: Element? = selectFirst("a[href]")
        val href = linkElement?.attr("href") ?: return null
        if (href.isBlank() || href.contains("category") || href.contains("page/")) return null

        val fixedUrl = fixUrl(href)

        // Extract title from .poster-title or img alt
        val titleText: String = selectFirst(".poster-title")?.text()
            ?: (selectFirst("img")?.attr("alt")
            ?: (selectFirst("img")?.attr("title")
            ?: ""))

        if (titleText.isBlank()) return null
        val title: String = cleanTitle(titleText)
        if (title.isBlank()) return null

        // Extract poster from .poster-image img
        val imgElement = selectFirst(".poster-image img, img")
        val posterUrl: String? = if (imgElement != null) {
            val srcAttr = imgElement.attr("src")
            val dataSrcAttr = imgElement.attr("data-src")
            val lazyAttr = imgElement.attr("data-lazy-src")
            val src = when {
                srcAttr.isNotBlank() && srcAttr.startsWith("http") -> srcAttr
                dataSrcAttr.isNotBlank() && dataSrcAttr.startsWith("http") -> dataSrcAttr
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
            // MoviesDrive uses /?s=query format for search
            val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
            Log.d(TAG, "Search URL: $searchUrl")
            
            val document = app.get(searchUrl, headers = headers).document

            // Search results also use .poster-card structure
            document.select(".poster-card").forEach { card ->
                val searchResult = card.toSearchResult()
                if (searchResult != null && results.none { it.url == searchResult.url }) {
                    results.add(searchResult)
                }
            }
            
            // Fallback: If no results from search page, search through homepage
            if (results.isEmpty()) {
                Log.d(TAG, "Search page returned no results, using fallback method")
                val searchTerms = query.lowercase().split(" ").filter { it.length > 2 }
                
                for (page in 1..3) {
                    val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
                    val doc = app.get(url, headers = headers).document

                    doc.select(".poster-card").forEach { element ->
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

        // Extract title from .post-title or h1
        val rawTitle = document.selectFirst(".post-title, h1.post-title, h1")?.text()?.trim()
            ?: return null
        val title = cleanTitle(rawTitle)

        // Extract poster - MoviesDrive uses images in post content
        val posterMeta = document.selectFirst("meta[property=og:image]")?.attr("content")
        val posterImg = document.selectFirst(".post-content img, .page-body img, img.aligncenter")?.attr("src")
        val poster: String? = when {
            !posterImg.isNullOrBlank() && posterImg.startsWith("http") -> posterImg
            !posterMeta.isNullOrBlank() && posterMeta.startsWith("http") -> posterMeta
            else -> null
        }

        // Extract description
        val descMeta = document.selectFirst("meta[name=description]")?.attr("content")
        val descOg = document.selectFirst("meta[property=og:description]")?.attr("content")
        val description: String? = descMeta ?: descOg

        // Extract year using companion object YEAR_REGEX
        val year = YEAR_REGEX.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        // Extract tags/genres from .post-categories
        val tags = document.select(".post-categories a, .category-tag").map { it.text() }

        // Determine if series using companion object SERIES_DETECTION_REGEX
        val isSeries = SERIES_DETECTION_REGEX.containsMatchIn(rawTitle)

        return if (isSeries) {
            // Detect episodes from download links
            val episodes = detectEpisodesFromDownloadLinks(document, url)

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

    // Detect episode numbers from download links
    private fun detectEpisodesFromDownloadLinks(
        document: org.jsoup.nodes.Document, 
        pageUrl: String
    ): List<com.lagradost.cloudstream3.Episode> {
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val detectedEpisodes = mutableSetOf<Int>()
        
        Log.d(TAG, "=== detectEpisodesFromDownloadLinks START ===")
        
        // Extract from download link headings (h5, h4 elements before download links)
        document.select("h5, h4").forEach { element ->
            val text = element.text().trim()
            // Look for episode patterns in headings
            val match = EPISODE_NUMBER_REGEX.find(text)
            val epNum = match?.groupValues?.get(1)?.toIntOrNull()
            if (epNum != null && epNum > 0 && epNum < 500) {
                detectedEpisodes.add(epNum)
                Log.d(TAG, "Found episode from heading: $epNum (text: $text)")
            }
        }
        
        // Also check download links themselves
        document.select("a[href*='mdrive.lol']").forEach { element ->
            val linkText = element.text().trim()
            val match = EPISODE_NUMBER_REGEX.find(linkText)
            val epNum = match?.groupValues?.get(1)?.toIntOrNull()
            if (epNum != null && epNum > 0 && epNum < 500) {
                detectedEpisodes.add(epNum)
                Log.d(TAG, "Found episode from link: $epNum (text: $linkText)")
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
        } else {
            // Fallback: Add a single "Full Season" episode if no episodes detected
            val data = "$pageUrl|||0"
            episodes.add(
                newEpisode(data) {
                    this.name = "Full Season"
                    this.episode = 1
                }
            )
            Log.d(TAG, "Added Full Season episode (no episodes detected)")
        }
        
        Log.d(TAG, "=== detectEpisodesFromDownloadLinks END === Total episodes: ${episodes.size}")
        return episodes.sortedBy { it.episode }
    }

    private fun extractQuality(text: String): Int {
        // Use companion object QUALITY_REGEX for primary extraction
        val match = QUALITY_REGEX.find(text)

        return match?.groupValues?.get(1)?.toIntOrNull() ?: when {
            text.contains("4K", ignoreCase = true) -> 2160
            text.contains("2160", ignoreCase = true) -> 2160
            text.contains("1080", ignoreCase = true) -> 1080
            text.contains("720", ignoreCase = true) -> 720
            text.contains("480", ignoreCase = true) -> 480
            text.contains("360", ignoreCase = true) -> 360
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

    // Helper function to extract download links from document
    private fun extractDownloadLinks(document: org.jsoup.nodes.Document): List<DownloadLink> {
        val downloadLinks = mutableListOf<DownloadLink>()
        val seenUrls = mutableSetOf<String>()
        
        Log.d(TAG, "=== extractDownloadLinks START ===")
        
        var currentEpisode: Int? = null
        
        // MoviesDrive structure: h5 headings followed by download links
        // Example: <h5>Movie Name (2025) WEB-DL [Hindi] 480p [568.12 MB]</h5>
        //          <h5><a href="https://mdrive.lol/archives/75796">480p [568.12 MB]</a></h5>
        
        document.select("h5, h4, a[href*='mdrive.lol']").forEach { element ->
            val tagName = element.tagName().uppercase()
            
            // Check if this is an episode header
            if (tagName in listOf("H4", "H5")) {
                val headerText = element.text().trim()
                val epMatch = EPISODE_NUMBER_REGEX.find(headerText)
                val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull()
                if (epNum != null && epNum > 0 && epNum < 500) {
                    currentEpisode = epNum
                    Log.d(TAG, "Episode section detected: $currentEpisode (from: $headerText)")
                }
            }
            
            // This is a link element
            if (tagName == "A") {
                val url = element.attr("href")
                val linkText = element.text().trim()
                
                // Skip if blank, already seen
                if (url.isBlank() || seenUrls.contains(url)) return@forEach
                if (!url.contains("mdrive.lol")) return@forEach
                
                seenUrls.add(url)
                
                // Determine episode context from link text or current section
                val linkEpMatch = EPISODE_NUMBER_REGEX.find(linkText)
                val linkEpisode = linkEpMatch?.groupValues?.get(1)?.toIntOrNull() ?: currentEpisode
                
                // Build context text for quality detection
                // Also check parent h5 for full context
                val parentText = element.parent()?.text() ?: ""
                val contextText = "$parentText | $linkText"
                
                downloadLinks.add(
                    DownloadLink(
                        url = url,
                        quality = extractQuality(contextText),
                        sizeMB = parseFileSize(contextText),
                        originalText = contextText,
                        episodeNum = linkEpisode
                    )
                )
                Log.d(TAG, "Found link: EP=$linkEpisode, quality=${extractQuality(contextText)}, text=$linkText")
            }
        }
        
        Log.d(TAG, "Total download links: ${downloadLinks.size}")
        
        // Log episode distribution for debugging
        val episodeDistribution = downloadLinks.groupBy { it.episodeNum }.mapValues { it.value.size }
        Log.d(TAG, "Episode distribution: $episodeDistribution")

        // Smart sort: 1080p priority → HEVC/X265 → X264 → Smallest Size
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

            // Filter by episode if needed (using episodeNum field)
            val targetLinks = when {
                episodeNum == null -> allLinks  // Movie - return all
                episodeNum == 0 -> allLinks.filter { 
                    it.episodeNum == null  // Full season - only batch/non-episode links
                }.ifEmpty { allLinks }
                else -> {
                    // Specific episode - use episodeNum field
                    val byField = allLinks.filter { it.episodeNum == episodeNum }
                    if (byField.isNotEmpty()) {
                        Log.d(TAG, "Matched ${byField.size} links by episodeNum field for EP$episodeNum")
                        byField
                    } else {
                        Log.d(TAG, "No links matched for EP$episodeNum, using all links")
                        allLinks
                    }
                }
            }

            Log.d(TAG, "Filtered links: ${targetLinks.size}")
            
            // MoviesDrive links are direct download links from mdrive.lol
            // We'll create ExtractorLink objects directly without extractors
            // Since loadLinks expects extractors to be called, we'll need to implement
            // a basic pass-through or use the links as-is
            
            // For now, we'll log the links (extractors will be added later)
            targetLinks.take(5).forEach { downloadLink ->
                try {
                    Log.d(TAG, "Available download: ${downloadLink.originalText}")
                    Log.d(TAG, "  URL: ${downloadLink.url}")
                    Log.d(TAG, "  Quality: ${downloadLink.quality}p")
                    Log.d(TAG, "  Size: ${downloadLink.sizeMB}MB")
                    
                    // TODO: Implement extractors for mdrive.lol in loadLinks
                    // For now, links are logged but not passed to callback
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing ${downloadLink.url}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadLinks: ${e.message}")
        }

        return true
    }

    private fun cleanTitle(title: String): String {
        // Enhanced Regex patterns based on website title formats
        // Example: "Tere Ishk Mein (2025) NF WEB-DL [Hindi (DD5.1)] 480p | 720p | 1080p | 4K[2160p] [x264/ESubs] | Full Movie"
        return title
            .replace(Regex("""\(\d{4}\)"""), "")  // Remove year (2024)
            .replace(Regex("""\[.*?]"""), "")     // Remove bracket content [Hindi...]
            .replace(Regex("""\|.*$"""), "")      // Remove pipe and after | Full Movie...
            .replace(Regex("""(?i)(WEB-?DL|BluRay|HDRip|WEBRip|HDTV|DVDRip|BRRip|NF|AMZN)"""), "")  // Source tags
            .replace(Regex("""(?i)(4K|UHD|1080p|720p|480p|360p|2160p)"""), "")  // Quality tags
            .replace(Regex("""(?i)(HEVC|x264|x265|10Bit|H\.?264|H\.?265|AAC|DD5?\.?1?|ESubs)"""), "")  // Codec tags
            .replace(Regex("""(?i)(Download|Free|Full|Movie|HD|Watch)"""), "")  // Generic words
            .replace(Regex("""(?i)(Hindi|English|Dual\s*Audio|Tamil|Telugu|Multi)"""), "")  // Language tags
            .replace(Regex("""[&+]"""), " ")       // Replace & + with space
            .replace(Regex("""\s+"""), " ")        // Normalize multiple spaces
            .trim()
    }
}
