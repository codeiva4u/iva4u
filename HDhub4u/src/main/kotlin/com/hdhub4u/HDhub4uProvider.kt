package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * HDhub4u Provider for CloudStream
 * Flexible scraping with inline regex patterns for https://new2.hdhub4u.fo/
 * Uses dynamic domain fetching from urls.json API
 */
class HDhub4uProvider : MainAPI() {
    
    companion object {
        private const val TAG = "HDhub4u"
        
        // ===== Inline Regex Patterns for Flexible Scraping =====
        
        // Series detection patterns
        private val SERIES_PATTERNS = listOf(
            """(?i)Season\s*\d+""".toRegex(),
            """(?i)\bS\d+\b""".toRegex(),
            """(?i)EP\s*[-]?\s*\d+""".toRegex(),
            """(?i)Episode\s*\d+""".toRegex(),
            """(?i)All\s*Episodes?""".toRegex(),
            """(?i)Web\s*Series""".toRegex(),
            """(?i)\bSeries\b""".toRegex(),
            """(?i)EP[-_]?\d+\s*Added""".toRegex(),
            """(?i)VOL[-_]?\d+""".toRegex()
        )
        
        // Quality extraction pattern
        private val QUALITY_REGEX = """(?i)(480p|720p|1080p|2160p|4K|HDRip|WEB-?DL|WEBRip|BluRay|HDTC|HDCAM|DS4K|HQ)""".toRegex()
        
        // Year extraction pattern
        private val YEAR_REGEX = """\b(19|20)\d{2}\b""".toRegex()
        
        // Title cleaning pattern
        private val TITLE_CLEAN_REGEX = """^(.+?)\s*(?:\d{4})?\s*[\[\(]?(?:Hindi|English|Tamil|Telugu|WEB-?DL|WEBRip|BluRay|HDRip|HDTC|HDCAM|4K|1080p|720p|480p|HEVC|x264|x265|Dual\s*Audio|HQ|Studio\s*Dub|Full\s*Movie|Full\s*Series|Season|EP|Episode)""".toRegex(RegexOption.IGNORE_CASE)
        
        // Season/Episode extraction
        private val SEASON_REGEX = """(?i)(?:Season|S)\s*0?(\d+)""".toRegex()
        private val EPISODE_REGEX = """(?i)EP(?:i?sode)?\s*[-_]?\s*0?(\d+)""".toRegex()
        
        // Download domain pattern
        private val DOWNLOAD_DOMAINS_REGEX = """(?i)(hubdrive|gadgetsweb|hubstream|hdstream4u|hubcloud|hblinks|4khdhub|gamerxyt)""".toRegex()
    }
    
    // ===== Provider Configuration =====
    
    override var name = "HDHub4u"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    // Dynamic domain from DomainConfig
    override var mainUrl = "https://new2.hdhub4u.fo"
    
    init {
        runBlocking { 
            DomainConfig.fetchDomains() 
            val domain = DomainConfig.get("hdhub4u")
            if (domain.isNotBlank()) mainUrl = domain
        }
    }
    
    // ===== Categories (Main Page) - Updated from website scraping =====
    
    override val mainPage = mainPageOf(
        "/" to "Latest",
        "/category/bollywood-movies/" to "Bollywood",
        "/category/hollywood-movies/" to "Hollywood",
        "/category/hindi-dubbed/" to "Hindi Dubbed",
        "/category/south-hindi-movies/" to "South Hindi",
        "/category/web-series/" to "Web Series",
        "/category/animated-movies/" to "Animation",
        "/category/action-movies/" to "Action",
        "/category/comedy-movies/" to "Comedy",
        "/category/drama/" to "Drama",
        "/category/thriller/" to "Thriller",
        "/category/horror-movies/" to "Horror",
        "/category/romantic-movies/" to "Romance",
        "/category/sci-fi/" to "Sci-Fi",
        "/category/adventure/" to "Adventure",
        "/category/family/" to "Family",
        "/category/fantasy/" to "Fantasy",
        "/category/crime/" to "Crime",
        "/category/mystery/" to "Mystery"
    )
    
    // ===== Main Page Loading =====
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            "$mainUrl${request.data}page/$page/"
        } else {
            "$mainUrl${request.data}"
        }
        
        Log.d(TAG, "Loading: $url")
        
        val document = app.get(url, timeout = 15000).document
        val items = document.toSearchResults()
        
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = items.isNotEmpty()
        )
    }
    
    // ===== Search - Using search.html?q= format =====
    
    // ===== Search - Using Typesense API =====
    
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        try {
            // Try API first
            val apiResults = fetchSearchApi(query)
            if (apiResults.isNotEmpty()) {
                Log.d(TAG, "API returned ${apiResults.size} results")
                return apiResults
            }
        } catch (e: Exception) {
            Log.e(TAG, "API search failed: ${e.message}")
        }
        
        // Fallback to standard request
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        Log.d(TAG, "Searching fallback: $searchUrl")
        
        val document = app.get(searchUrl, timeout = 15000).document
        val htmlResults = document.toSearchResults()
        
        if (htmlResults.isNotEmpty()) return htmlResults
        
        return document.toSearchLinkResults()
    }

    private suspend fun fetchSearchApi(query: String): List<SearchResponse> {
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val today = formatter.format(java.util.Date())
        
        val apiUrl = "https://search.pingora.fyi/collections/post/documents/search" +
                "?q=${query}" +
                "&query_by=post_title,category,stars,director,imdb_id" +
                "&query_by_weights=4,2,2,2,4" +
                "&sort_by=sort_by_date:desc" +
                "&limit=15" +
                "&highlight_fields=none" +
                "&use_cache=true" +
                "&page=1" +
                "&analytics_tag=$today"

        Log.d(TAG, "API Search: $apiUrl")
        
        val response = app.get(apiUrl, headers = mapOf("Referer" to "$mainUrl/")).text
        val json = org.json.JSONObject(response)
        val hits = json.optJSONArray("hits") ?: return emptyList()
        
        val results = mutableListOf<SearchResponse>()
        
        for (i in 0 until hits.length()) {
            val hit = hits.getJSONObject(i)
            val doc = hit.getJSONObject("document")
            
            val rawTitle = doc.getString("post_title")
            val permalink = doc.getString("permalink")
            val poster = doc.optString("post_thumbnail")
            
            // Construct full URL
            val url = if (permalink.startsWith("http")) permalink else "$mainUrl$permalink"
            
            val cleanedTitle = extractCleanTitle(rawTitle)
            val year = extractYear(rawTitle)
            val isSeries = isTvSeries(rawTitle, url)
            
            val searchResponse = if (isSeries) {
                newTvSeriesSearchResponse(cleanedTitle, url, TvType.TvSeries) {
                    this.posterUrl = poster
                    year?.let { this.year = it }
                }
            } else {
                newMovieSearchResponse(cleanedTitle, url, TvType.Movie) {
                    this.posterUrl = poster
                    year?.let { this.year = it }
                }
            }
            results.add(searchResponse)
        }
        
        return results
    }
    
    // ===== Parse Search Page Links (for search.html page format) =====
    
    private fun Document.toSearchLinkResults(): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val mainHost = mainUrl.substringAfter("://").substringBefore("/")
        
        select("a[href]").forEach { link ->
            val href = link.absUrl("href")
            val text = link.text().trim()
            
            // Filter: Only movie/show pages (length > 20 chars, contains main domain, not category/search pages)
            if (href.contains(mainHost) && 
                text.length > 20 && 
                !href.contains("/category/") && 
                !href.contains("/search") &&
                !href.contains("/page/") &&
                !href.contains("/tag/") &&
                (href.contains("-movie") || href.contains("-full-") || href.contains("-season") || 
                 href.contains("-episode") || href.contains("-webrip") || href.contains("-hindi") ||
                 href.contains("-dubbed") || href.contains("-hdtc") || href.contains("-hdcam"))) {
                
                val cleanedTitle = extractCleanTitle(text)
                val year = extractYear(text)
                val isSeries = isTvSeries(text, href)
                
                val response = if (isSeries) {
                    newTvSeriesSearchResponse(
                        name = cleanedTitle,
                        url = href,
                        type = TvType.TvSeries
                    ) {
                        year?.let { this.year = it }
                    }
                } else {
                    newMovieSearchResponse(
                        name = cleanedTitle,
                        url = href,
                        type = TvType.Movie
                    ) {
                        year?.let { this.year = it }
                    }
                }
                
                // Avoid duplicates
                if (results.none { it.url == href }) {
                    results.add(response)
                }
            }
        }
        
        return results
    }
    
    // ===== Parse Search/Category Results =====
    
    private fun Document.toSearchResults(): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        // Primary selector: ul.recent-movies > li (from website scraping)
        select("ul.recent-movies > li").forEach { element ->
            toResult(element)?.let { results.add(it) }
        }
        
        // Fallback: article elements
        if (results.isEmpty()) {
            select("article, .post, .thumb").forEach { element ->
                toResult(element)?.let { results.add(it) }
            }
        }
        
        return results
    }
    
    // ===== Convert Element to SearchResponse =====
    
    private fun toResult(post: Element): SearchResponse? {
        // Extract link from figure > a or direct a
        val link = post.selectFirst("figure a, a")?.absUrl("href")
            ?.takeIf { it.isNotBlank() && it.contains(mainUrl.substringAfter("://").substringBefore("/")) }
            ?: return null
        
        // Extract poster - check multiple sources
        val posterElement = post.selectFirst("img")
        val poster = posterElement?.absUrl("src")?.takeIf { it.isNotBlank() }
            ?: posterElement?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: posterElement?.absUrl("data-lazy-src")
        
        // Extract title from multiple sources (priority order)
        val rawTitle = post.selectFirst("figcaption p")?.text()
            ?: post.selectFirst("figcaption a")?.text()
            ?: post.selectFirst(".title")?.text()
            ?: post.selectFirst("h2, h3, h4")?.text()
            ?: posterElement?.attr("alt")
            ?: posterElement?.attr("title")
            ?: return null
        
        // Clean title and extract year
        val cleanedTitle = extractCleanTitle(rawTitle)
        val year = extractYear(rawTitle)
        
        // Detect if series
        val isSeries = isTvSeries(rawTitle, link)
        
        return if (isSeries) {
            newTvSeriesSearchResponse(
                name = cleanedTitle,
                url = link,
                type = TvType.TvSeries
            ) {
                this.posterUrl = poster
                year?.let { this.year = it }
            }
        } else {
            newMovieSearchResponse(
                name = cleanedTitle,
                url = link,
                type = TvType.Movie
            ) {
                this.posterUrl = poster
                year?.let { this.year = it }
            }
        }
    }
    
    // ===== Load Movie/Series Detail =====
    
    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "Loading detail: $url")
        
        val document = app.get(url, timeout = 15000).document
        
        // Extract title
        val rawTitle = document.selectFirst("h1, .entry-title, .post-title")?.text() ?: "Unknown"
        val cleanedTitle = extractCleanTitle(rawTitle)
        val year = extractYear(rawTitle)
        
        // Extract poster - check og:image first, then entry-content img
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".entry-content img, .post-content img, article img")?.absUrl("src")
        
        // Extract description/plot
        val plot = document.select(".entry-content > p, .post-content > p").firstOrNull { p ->
            val text = p.text()
            text.length > 50 && 
            !text.contains("Download", ignoreCase = true) &&
            !text.contains("Click Here", ignoreCase = true) &&
            !text.contains("Join", ignoreCase = true)
        }?.text()
        
        // Detect quality from title
        val quality = QUALITY_REGEX.find(rawTitle)?.value
        
        // Check if series
        val isSeries = isTvSeries(rawTitle, url)
        
        return if (isSeries) {
            // Extract episodes
            val episodes = extractEpisodes(document, url, rawTitle)
            
            newTvSeriesLoadResponse(
                name = cleanedTitle,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = poster
                this.plot = plot
                year?.let { this.year = it }
                this.tags = listOfNotNull(quality)
            }
        } else {
            newMovieLoadResponse(
                name = cleanedTitle,
                url = url,
                type = TvType.Movie,
                dataUrl = url
            ) {
                this.posterUrl = poster
                this.plot = plot
                year?.let { this.year = it }
                this.tags = listOfNotNull(quality)
            }
        }
    }
    
    // ===== Extract Episodes =====
    
    private fun extractEpisodes(document: Document, baseUrl: String, rawTitle: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Extract season number
        val seasonNum = SEASON_REGEX.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        
        // Extract base slug for filtering (e.g., "knight-of-seven-kingdoms")
        val baseSlug = baseUrl.substringAfterLast("/")
            .substringBefore("-season")
            .substringBefore("-hindi")
            .take(30)
        
        // Collect episode links
        document.select("a").forEach { link ->
            val text = link.text().trim()
            val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return@forEach
            
            // Match episode pattern: EP1, EP-01, EPiSODE 1, etc.
            val episodeMatch = EPISODE_REGEX.find(text)
            if (episodeMatch != null) {
                val episodeNum = episodeMatch.groupValues[1].toIntOrNull() ?: return@forEach
                
                // Filter: Only links that are download links or same series
                val isDownloadLink = DOWNLOAD_DOMAINS_REGEX.containsMatchIn(href)
                val isSameSeries = href.contains(baseSlug, ignoreCase = true)
                
                if (!isDownloadLink && !isSameSeries) {
                    return@forEach
                }
                
                // Prefer download links over series page links
                val episodeUrl = if (isDownloadLink) href else baseUrl
                
                // Extract quality from text
                val quality = QUALITY_REGEX.find(text)?.value ?: ""
                val episodeName = "Episode $episodeNum${if (quality.isNotBlank()) " [$quality]" else ""}"
                
                // Add if not duplicate
                if (episodes.none { it.episode == episodeNum && it.data == episodeUrl }) {
                    episodes.add(
                        newEpisode(episodeUrl) {
                            this.name = episodeName
                            this.season = seasonNum
                            this.episode = episodeNum
                        }
                    )
                }
            }
        }
        
        // If no episodes found, create single episode with page URL
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(baseUrl) {
                    this.name = "Episode 1"
                    this.season = seasonNum
                    this.episode = 1
                }
            )
        }
        
        return episodes.distinctBy { "${it.season}-${it.episode}" }.sortedBy { it.episode }
    }
    
    // ===== Load Links (Extractors) =====
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "Loading links from: $data")
        
        val document = app.get(data, timeout = 15000).document
        var foundLinks = false
        
        // Collect all potential download links with metadata
        val downloadLinks = mutableListOf<DownloadLink>()
        
        document.select("a[href]").forEach { link ->
            val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return@forEach
            val text = link.text().trim()
            val parentText = link.parent()?.text()?.trim() ?: text
            
            // Skip navigation and irrelevant links
            if (href.contains("disclaimer") || href.contains("how-to-download") ||
                href.contains("join-our-group") || href.contains("request-a-movie") ||
                href.contains("/category/") || href.contains("#comment")) {
                return@forEach
            }
            
            // Match download domains
            if (DOWNLOAD_DOMAINS_REGEX.containsMatchIn(href)) {
                val quality = ExtractorPatterns.extractQuality(parentText)
                val size = ExtractorPatterns.extractSize(parentText)
                val priority = ExtractorPatterns.getServerPriority(href)
                val isX264 = ExtractorPatterns.isX264(parentText)
                
                downloadLinks.add(DownloadLink(
                    url = href,
                    text = text,
                    quality = quality,
                    size = size,
                    priority = priority,
                    isX264 = isX264
                ))
            }
        }
        
        // Sort links: higher quality, x264 codec preferred, then by priority
        val sortedLinks = downloadLinks.sortedWith(
            compareByDescending<DownloadLink> { it.quality }
                .thenByDescending { it.isX264 }
                .thenByDescending { it.priority }
        )
        
        Log.d(TAG, "Found ${sortedLinks.size} download links")
        
        // Process each link
        for (dl in sortedLinks) {
            try {
                val quality = if (dl.quality > 0) dl.quality else Qualities.Unknown.value
                
                when (ExtractorPatterns.matchExtractor(dl.url)) {
                    ExtractorType.HUBDRIVE -> {
                        Hubdrive().getUrl(dl.url, name, subtitleCallback, callback)
                        foundLinks = true
                    }
                    ExtractorType.HUBCLOUD -> {
                        HubCloud().getUrl(dl.url, name, subtitleCallback, callback)
                        foundLinks = true
                    }
                    ExtractorType.HUBCDN -> {
                        HUBCDN().getUrl(dl.url, name, subtitleCallback, callback)
                        foundLinks = true
                    }
                    ExtractorType.HDSTREAM4U -> {
                        HdStream4u().getUrl(dl.url, name, subtitleCallback, callback)
                        foundLinks = true
                    }
                    ExtractorType.HUBSTREAM -> {
                        Hubstream().getUrl(dl.url, name, subtitleCallback, callback)
                        foundLinks = true
                    }
                    ExtractorType.HBLINKS -> {
                        Hblinks().getUrl(dl.url, name, subtitleCallback, callback)
                        foundLinks = true
                    }
                    ExtractorType.GADGETSWEB -> {
                        GadgetsWeb().getUrl(dl.url, name, subtitleCallback, callback)
                        foundLinks = true
                    }
                    else -> {
                        loadSourceNameExtractor(name, dl.url, "", quality, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Extractor error for ${dl.url}: ${e.message}")
            }
        }
        
        return foundLinks
    }
    
    // ===== Helper Functions =====
    
    /**
     * Check if content is a TV series based on title and URL patterns
     */
    private fun isTvSeries(text: String, url: String): Boolean {
        return SERIES_PATTERNS.any { it.containsMatchIn(text) } || 
               url.contains("season", ignoreCase = true) ||
               url.contains("episode", ignoreCase = true) || 
               url.contains("series", ignoreCase = true) ||
               url.contains("-all-episodes", ignoreCase = true)
    }
    
    /**
     * Extract clean title removing quality/language tags
     */
    private fun extractCleanTitle(rawTitle: String): String {
        // Try regex first
        TITLE_CLEAN_REGEX.find(rawTitle)?.groupValues?.get(1)?.trim()?.let { 
            if (it.length > 2) return it 
        }
        
        // Fallback: split on common delimiters
        val parts = rawTitle.split(Regex("""[\[\(]"""))
        return parts.firstOrNull()?.trim()?.takeIf { it.length > 2 } ?: rawTitle.trim()
    }
    
    /**
     * Extract year from title
     */
    private fun extractYear(text: String): Int? {
        return YEAR_REGEX.find(text)?.value?.toIntOrNull()
    }
    
    /**
     * Data class for download link metadata
     */
    private data class DownloadLink(
        val url: String,
        val text: String,
        val quality: Int,
        val size: Double,
        val priority: Int,
        val isX264: Boolean
    )
}
