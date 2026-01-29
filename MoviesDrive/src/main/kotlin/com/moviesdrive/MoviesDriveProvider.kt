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
import com.lagradost.cloudstream3.amap
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
        // Matches: "S01 E01", "Ep01", "Episode 1", "E01", "Ep 1"
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
        
        // Image URL cleaning regex - removes placeholder/data URLs
        private val VALID_IMAGE_URL_REGEX = Regex(
            """^https?://[^/]+/.*\.(jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE
        )
        
        // Title cleaning comprehensive regex
        private val TITLE_CLEAN_REGEX = Regex(
            """(?i)(\(?\d{4}\)?|\[.*?]|\|.*$|WEB-?DL|BluRay|HDRip|WEBRip|HDTV|DVDRip|BRRip|NF|AMZN|4K|UHD|\d{3,4}p|HEVC|x264|x265|10Bit|H\.?264|H\.?265|AAC|DD5?\.?1?|ESubs|Download|Free|Full|Movie|HD|Watch|Hindi|English|Dual\s*Audio|Tamil|Telugu|Multi|[&+])"""
        )
    }

    // Cached domain URL - fetched once per session (async, no blocking)
    private var cachedMainUrl: String? = null
    private var urlsFetched = false

    override var mainUrl: String = "https://new1.moviesdrive.surf"

    // Fast async domain fetch with 2s timeout - non-blocking
    private suspend fun fetchMainUrl(): String {
        if (cachedMainUrl != null) return cachedMainUrl!!
        if (urlsFetched) return mainUrl

        urlsFetched = true
        try {
            val result = withTimeoutOrNull(2_000L) {  // Reduced from 3s to 2s
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
                Log.d(TAG, "✅ Fetched mainUrl: $result")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to fetch mainUrl: ${e.message}")
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
        "category/netflix/" to "Netflix",
        "category/amzn-prime-video/" to "Amazon Prime",
		"category/search.html?q=jiohotstar/" to "JioHotStar",
		"category/web/" to "Web Series"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
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

        val document = app.get(url, headers = headers).document

        // MoviesDrive structure: <a href="..."><div class="poster-card">...</div></a>
        // Optimized selector: directly select <a> tags with .poster-card children
        val home = document.select("a[href]:has(.poster-card)").mapNotNull {
            it.toSearchResult()
        }

        Log.d(TAG, "📥 ${request.name}: ${home.size} items")

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Extract href (this element is <a> tag)
        val href = attr("href") ?: return null
        
        // Filter invalid URLs using regex
        if (href.isBlank() || 
            href.contains("/category/", ignoreCase = true) || 
            href.contains("/page/", ignoreCase = true) ||
            href.contains("/tag/", ignoreCase = true)) return null
        
        val fixedUrl = fixUrl(href)

        // Extract title with fallback chain - optimized order
        val titleText: String = selectFirst(".poster-title")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: selectFirst("img")?.attr("title")
            ?: return null

        if (titleText.isBlank()) return null
        val title: String = cleanTitle(titleText)
        if (title.isBlank()) return null

        // Extract poster URL with instant validation - CRITICAL FOR SPEED
        val imgElement = selectFirst("img")
        val posterUrl: String? = imgElement?.let { img ->
            // Priority chain: src > data-src > data-lazy-src
            sequenceOf(
                img.attr("src"),
                img.attr("data-src"),
                img.attr("data-lazy-src")
            ).firstOrNull { url ->
                url.isNotBlank() && (
                    url.startsWith("http") || 
                    url.startsWith("//") ||
                    url.startsWith("/wp-content/")
                )
            }?.let { imageUrl ->
                // Normalize URL instantly
                when {
                    imageUrl.startsWith("http") -> imageUrl
                    imageUrl.startsWith("//") -> "https:$imageUrl"
                    else -> fixUrlNull(imageUrl)
                }
            }
        }

        // Determine type using regex pattern
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
        val results = mutableListOf<SearchResponse>()

        try {
            // MoviesDrive uses /?s=query format for search
            val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
            
            val document = app.get(searchUrl, headers = headers).document

            // Optimized selector
            document.select("a[href]:has(.poster-card)").mapNotNullTo(results) { 
                it.toSearchResult()
            }
            
            // Fallback: search through homepage if no results
            if (results.isEmpty()) {
                Log.d(TAG, "🔍 Fallback search for: $query")
                val searchTerms = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
                
                for (page in 1..2) {  // Reduced from 3 to 2 pages for speed
                    val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
                    val doc = app.get(url, headers = headers).document

                    doc.select("a[href]:has(.poster-card)").forEach { element ->
                        element.toSearchResult()?.let { searchResult ->
                            val titleLower = searchResult.name.lowercase()
                            if (searchTerms.any { titleLower.contains(it) } && 
                                results.none { it.url == searchResult.url }) {
                                results.add(searchResult)
                            }
                        }
                    }

                    if (results.size >= 15) break  // Reduced from 20 to 15 for speed
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Search error: ${e.message}")
        }

        Log.d(TAG, "🔍 Search '$query': ${results.size} results")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        // Fetch latest mainUrl (async, cached)
        fetchMainUrl()

        val document = app.get(url, headers = headers).document

        // Extract title using regex-optimized selector
        val rawTitle = document.selectFirst(".post-title, h1.post-title, h1")?.text()?.trim()
            ?: return null
        val title = cleanTitle(rawTitle)

        // Extract poster with instant validation
        val posterUrl: String? = sequenceOf(
            document.selectFirst(".post-content img, .page-body img, img.aligncenter")?.attr("src"),
            document.selectFirst("meta[property=og:image]")?.attr("content")
        ).firstOrNull { url ->
            !url.isNullOrBlank() && url.startsWith("http")
        }

        // Extract description using regex
        val description: String? = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        // Extract year using companion regex
        val year = YEAR_REGEX.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        // Extract tags using optimized selector
        val tags = document.select(".post-categories a, .category-tag").map { it.text() }

        // Determine type using companion regex
        val isSeries = SERIES_DETECTION_REGEX.containsMatchIn(rawTitle)

        return if (isSeries) {
            val episodes = detectEpisodesFromDownloadLinks(document, url)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
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
        val episodeNum: Int? = null
    )

    // Detect actual episode count from mdrive.lol page (SMART & ACCURATE)
    private suspend fun detectEpisodesFromDownloadLinks(
        document: org.jsoup.nodes.Document, 
        pageUrl: String
    ): List<com.lagradost.cloudstream3.Episode> {
        val detectedEpisodes = mutableSetOf<Int>()
        var singleEpisodeLink: String? = null
        
        // Extract from headings using regex
        document.select("h5, h4").forEach { element ->
            EPISODE_NUMBER_REGEX.find(element.text())?.let { match ->
                match.groupValues[1].toIntOrNull()?.let { epNum ->
                    if (epNum in 1..499) detectedEpisodes.add(epNum)
                }
            }
        }
        
        // Find "Single Episode" link to detect actual count
        document.select("a[href*='mdrive.lol']").forEach { element ->
            val linkText = element.text()
            val href = element.attr("href")
            
            // Find first "Single Episode" link (prefer highest quality - 1080p)
            if (linkText.contains("Single Episode", ignoreCase = true) && 
                singleEpisodeLink == null &&
                (linkText.contains("1080p") || linkText.contains("720p") || linkText.contains("480p"))) {
                singleEpisodeLink = href
            }
            
            // Regular episode detection from link text
            EPISODE_NUMBER_REGEX.find(linkText)?.let { match ->
                match.groupValues[1].toIntOrNull()?.let { epNum ->
                    if (epNum in 1..499) detectedEpisodes.add(epNum)
                }
            }
        }
        
        // If "Single Episode" link found, fetch mdrive page to get actual count
        if (singleEpisodeLink != null && detectedEpisodes.isEmpty()) {
            try {
                Log.d(TAG, "📡 Fetching mdrive page to detect actual episode count...")
                val mdriveDoc = app.get(singleEpisodeLink!!).document
                
                // Detect episodes from mdrive page (Ep01, Ep02, etc.)
                mdriveDoc.select("h5, h4").forEach { elem ->
                    EPISODE_NUMBER_REGEX.find(elem.text())?.let { match ->
                        match.groupValues[1].toIntOrNull()?.let { epNum ->
                            if (epNum in 1..499) detectedEpisodes.add(epNum)
                        }
                    }
                }
                Log.d(TAG, "✅ Detected ${detectedEpisodes.size} episodes from mdrive.lol")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to fetch mdrive page: ${e.message}")
                // Fallback: assume 8 episodes (standard)
                for (i in 1..8) detectedEpisodes.add(i)
            }
        }
        
        Log.d(TAG, "📺 Final Episodes: $detectedEpisodes")
        
        return if (detectedEpisodes.isNotEmpty()) {
            detectedEpisodes.sorted().map { episodeNum ->
                newEpisode("$pageUrl|||$episodeNum") {
                    this.name = "Episode $episodeNum"
                    this.episode = episodeNum
                }
            }
        } else {
            listOf(
                newEpisode("$pageUrl|||0") {
                    this.name = "Full Season"
                    this.episode = 1
                }
            )
        }
    }

    private fun extractQuality(text: String): Int {
        // Use companion regex for extraction
        return QUALITY_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: when {
            text.contains("4K", ignoreCase = true) -> 2160
            text.contains("2160", ignoreCase = true) -> 2160
            text.contains("1080", ignoreCase = true) -> 1080
            text.contains("720", ignoreCase = true) -> 720
            text.contains("480", ignoreCase = true) -> 480
            text.contains("360", ignoreCase = true) -> 360
            else -> 0
        }
    }

    private fun parseFileSize(text: String): Double {
        // Use companion regex for extraction
        return FILE_SIZE_REGEX.find(text)?.let { match ->
            val value = match.groupValues[1].toDoubleOrNull() ?: 0.0
            val unit = match.groupValues[2].uppercase()
            if (unit == "GB") value * 1024 else value
        } ?: 0.0
    }

    // Extract download links using regex patterns (EXCLUDE ZIP FILES)
    private fun extractDownloadLinks(document: org.jsoup.nodes.Document): List<DownloadLink> {
        val downloadLinks = mutableListOf<DownloadLink>()
        val seenUrls = mutableSetOf<String>()
        var currentEpisode: Int? = null
        
        // Process all headers and links in order
        document.select("h5, h4, a[href*='mdrive.lol']").forEach { element ->
            val tagName = element.tagName().uppercase()
            
            if (tagName in setOf("H4", "H5")) {
                // Check for episode number in header using regex
                EPISODE_NUMBER_REGEX.find(element.text())?.let { match ->
                    match.groupValues[1].toIntOrNull()?.let { epNum ->
                        if (epNum in 1..499) currentEpisode = epNum
                    }
                }
            } else if (tagName == "A") {
                val url = element.attr("href")
                val linkText = element.text()
                
                // Skip if blank, duplicate, or not mdrive.lol
                if (url.isBlank() || seenUrls.contains(url) || !url.contains("mdrive.lol")) return@forEach
                
                // ❌ SKIP ZIP LINKS - they are compressed archives, not playable!
                if (linkText.contains("Zip", ignoreCase = true) || 
                    linkText.contains(".zip", ignoreCase = true)) {
                    Log.d(TAG, "⏭️ Skipping Zip link: $linkText")
                    return@forEach
                }
                
                seenUrls.add(url)
                
                // Determine episode from link text or current context using regex
                val linkEpisode = EPISODE_NUMBER_REGEX.find(linkText)?.groupValues?.get(1)?.toIntOrNull() 
                    ?: currentEpisode
                
                val contextText = "${element.parent()?.text() ?: ""} | $linkText"
                
                downloadLinks.add(
                    DownloadLink(
                        url = url,
                        quality = extractQuality(contextText),
                        sizeMB = parseFileSize(contextText),
                        originalText = contextText.take(100),
                        episodeNum = linkEpisode
                    )
                )
            }
        }
        
        Log.d(TAG, "🔗 Found ${downloadLinks.size} links (Zip files excluded)")
        
        // Smart sort using quality and codec preferences
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
                val text = it.originalText.lowercase()
                when {
                    text.contains("hevc") || text.contains("x265") -> 150
                    text.contains("x264") -> 100
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
        try {
            val parts = data.split("|||")
            val pageUrl = parts[0]
            val episodeNum = parts.getOrNull(1)?.toIntOrNull()

            val document = app.get(pageUrl, headers = headers).document
            val allLinks = extractDownloadLinks(document)
            
            // Filter links by episode using regex-based matching
            val targetLinks = when {
                episodeNum == null -> allLinks
                episodeNum == 0 -> allLinks.filter { it.episodeNum == null }.ifEmpty { allLinks }
                else -> allLinks.filter { it.episodeNum == episodeNum }.ifEmpty { allLinks }
            }

            Log.d(TAG, "🎬 EP$episodeNum: ${targetLinks.size} links found")
            
            // ═══════════════════════════════════════════════════════════════════
            // PRIORITY LINK SELECTION (strict order per user requirements):
            // 1st: X264 1080p
            // 2nd: X264 720p  
            // 3rd: x265/HEVC 1080p
            // 4th: Smallest file size within quality group
            // 5th: Fastest direct download/streaming link
            // ═══════════════════════════════════════════════════════════════════
            
            val sortedLinks = targetLinks.sortedWith(
                compareByDescending<DownloadLink> {
                    val text = it.originalText.lowercase()
                    val isX264 = text.contains("x264") || text.contains("h264") || text.contains("h.264")
                    val isHEVC = text.contains("hevc") || text.contains("x265") || text.contains("h265") || text.contains("h.265")
                    
                    // Priority scoring (higher = better)
                    when {
                        isX264 && it.quality >= 1080 -> 30000  // 1st priority: X264 1080p
                        isX264 && it.quality >= 720 -> 20000   // 2nd priority: X264 720p
                        isHEVC && it.quality >= 1080 -> 10000  // 3rd priority: HEVC 1080p
                        isHEVC && it.quality >= 720 -> 9000
                        it.quality >= 1080 -> 8000
                        it.quality >= 720 -> 7000
                        else -> 5000
                    }
                }.thenBy {
                    // 4th priority: Smaller file = higher priority (ascending order)
                    if (it.sizeMB > 0) it.sizeMB else Double.MAX_VALUE
                }.thenByDescending {
                    // 5th priority: Server speed
                    val serverName = it.originalText
                    when {
                        serverName.contains("Instant", true) -> 100
                        serverName.contains("Direct", true) -> 90
                        serverName.contains("10Gbps", true) -> 85
                        serverName.contains("FSL", true) -> 80
                        else -> 50
                    }
                }
            )

            Log.d(TAG, "✅ Sorted links (priority order):")
            sortedLinks.take(5).forEachIndexed { index, link ->
                val codec = when {
                    link.originalText.contains("x264", true) -> "X264"
                    link.originalText.contains("hevc", true) || link.originalText.contains("x265", true) -> "HEVC"
                    else -> "Unknown"
                }
                Log.d(TAG, "  #${index + 1}: $codec ${link.quality}p ${link.sizeMB}MB")
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // EXTRACT LINKS USING EXTRACTORS (PARALLEL + 10s TIMEOUT)
            // Strategy: Process only TOP 1 link for episodes, TOP 3 for movies
            // Reason: Episodes = faster (single video), Movies = backup links needed
            // amap = Async Map = सभी links parallel में process होते हैं
            // ═══════════════════════════════════════════════════════════════════
            
            // Optimize: For series, process only 1 link; for movies, process 3
            val linksToProcess = if (episodeNum != null) 1 else 3
            Log.d(TAG, "⚡ Processing top $linksToProcess link(s) for ${if (episodeNum != null) "Episode $episodeNum" else "Movie"}")
            
            // Process only top links for instant results (10s max)
            try {
                withTimeoutOrNull(10_000L) {  // 10 second timeout
                    sortedLinks.take(linksToProcess).amap { downloadLink ->
                        try {
                            val link = downloadLink.url
                            Log.d(TAG, "🔄 Processing: ${link.take(50)}...")
                            
                            when {
                                // mdrive.lol is just an INDEX/MEDIATOR page
                                // Fetch it directly and extract HubCloud/GDFlix links
                                link.contains("mdrive.lol", ignoreCase = true) -> {
                                    try {
                                        Log.d(TAG, "📄 Fetching mdrive.lol mediator page: $link")
                                        val mdriveDoc = app.get(link).document
                                        
                                        // Episode filtering (if needed)
                                        val episodeRegex = Regex("(?i)(?:EP|Episode)[\\s-]*(\\d+)")
                                        var targetLinks = mutableListOf<String>()
                                        
                                        if (episodeNum != null) {
                                            // Find target episode header
                                            val headers = mdriveDoc.select("h5, h4, h3")
                                            var targetHeader: org.jsoup.nodes.Element? = null
                                            
                                            for (header in headers) {
                                                val match = episodeRegex.find(header.text())
                                                if (match?.groupValues?.get(1)?.toIntOrNull() == episodeNum) {
                                                    targetHeader = header
                                                    Log.d(TAG, "✅ Found Ep${episodeNum}: ${header.text()}")
                                                    break
                                                }
                                            }
                                            
                                            // Extract links from target episode section
                                            if (targetHeader != null) {
                                                var elem = targetHeader.nextElementSibling()
                                                while (elem != null) {
                                                    // Stop at next episode header
                                                    if (elem.tagName() in listOf("h5", "h4", "h3") && 
                                                        episodeRegex.find(elem.text()) != null) {
                                                        break
                                                    }
                                                    
                                                    // Collect HubCloud/GDFlix links
                                                    elem.select("a[href]").forEach { a ->
                                                        val href = a.attr("href")
                                                        if (href.contains("hubcloud", true) || 
                                                            href.contains("gdflix", true) ||
                                                            href.contains("gdlink", true)) {
                                                            targetLinks.add(href)
                                                        }
                                                    }
                                                    elem = elem.nextElementSibling()
                                                }
                                            }
                                        } else {
                                            // Movie: Get all HubCloud/GDFlix links
                                            mdriveDoc.select("a[href]").forEach { a ->
                                                val href = a.attr("href")
                                                if (href.contains("hubcloud", true) || 
                                                    href.contains("gdflix", true) ||
                                                    href.contains("gdlink", true)) {
                                                    targetLinks.add(href)
                                                }
                                            }
                                        }
                                        
                                        Log.d(TAG, "🔗 Extracted ${targetLinks.size} direct links from mdrive.lol")
                                        
                                        // Process extracted links with proper extractors
                                        targetLinks.take(3).amap { extractorLink ->
                                            try {
                                                when {
                                                    extractorLink.contains("hubcloud", true) -> {
                                                        HubCloud().getUrl(extractorLink, pageUrl, subtitleCallback, callback)
                                                    }
                                                    extractorLink.contains("gdflix", true) || 
                                                    extractorLink.contains("gdlink", true) -> {
                                                        GDFlix().getUrl(extractorLink, pageUrl, subtitleCallback, callback)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.e(TAG, "❌ Error processing extracted link: ${e.message}")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "❌ Error fetching mdrive.lol: ${e.message}")
                                    }
                                }
                                
                                // Direct extractors if other hosts found
                                link.contains("hubcloud", ignoreCase = true) || 
                                link.contains("gamerxyt", ignoreCase = true) -> {
                                    HubCloud().getUrl(link, pageUrl, subtitleCallback, callback)
                                }
                                
                                link.contains("gdflix", ignoreCase = true) || 
                                link.contains("gdlink", ignoreCase = true) -> {
                                    GDFlix().getUrl(link, pageUrl, subtitleCallback, callback)
                                }
                                
                                else -> {
                                    Log.w(TAG, "⚠️ No extractor for: ${link.take(50)}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error extracting ${downloadLink.url.take(50)}: ${e.message}")
                        }
                    }
                } ?: Log.w(TAG, "⏱️ Timeout reached (10s), showing available links")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Link extraction error: ${e.message}")
            }
            
            Log.d(TAG, "✅ LoadLinks completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ loadLinks error: ${e.message}")
        }

        return true
    }

    private fun cleanTitle(title: String): String {
        // Comprehensive regex-based title cleaning
        return title
            .replace(TITLE_CLEAN_REGEX, "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
