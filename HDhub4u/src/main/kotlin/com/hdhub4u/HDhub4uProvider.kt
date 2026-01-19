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
 */
class HDhub4uProvider : MainAPI() {
    
    companion object {
        private const val TAG = "HDhub4u"
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
    
    // ===== Categories (Main Page) =====
    
    override val mainPage = mainPageOf(
        "/" to "Latest",
        "/category/bollywood-movies/" to "Bollywood",
        "/category/hollywood-movies/" to "Hollywood",
        "/category/hindi-dubbed/" to "Hindi Dubbed",
        "/category/south-hindi-movies/" to "South Hindi",
        "/category/category/web-series/" to "Web Series",
        "/category/animated-movies/" to "Animation",
        "/category/action-movies/" to "Action",
        "/category/comedy-movies/" to "Comedy",
        "/category/drama/" to "Drama",
        "/category/thriller/" to "Thriller"
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
    
    // ===== Search =====
    
    override suspend fun search(query: String): List<SearchResponse> {
        // HDhub4u uses search.html?q= format for search
        val searchUrl = "$mainUrl/search.html?q=${query.replace(" ", "+")}"
        Log.d(TAG, "Searching: $searchUrl")
        
        val document = app.get(searchUrl, timeout = 15000).document
        val results = document.toSearchResults()
        
        // Fallback: If no results from standard selectors, extract from search page links
        if (results.isEmpty()) {
            return document.toSearchLinkResults()
        }
        
        return results
    }
    
    // ===== Parse Search Page Links (for search.html page format) =====
    
    private fun Document.toSearchLinkResults(): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val mainHost = mainUrl.substringAfter("://").substringBefore("/")
        
        // Series detection patterns
        val seriesPatterns = listOf(
            """(?i)Season\s*\d+""".toRegex(),
            """(?i)\bS\d+\b""".toRegex(),
            """(?i)EP\s*[-]?\s*\d+""".toRegex(),
            """(?i)Episode\s*\d+""".toRegex(),
            """(?i)All\s*Episodes?""".toRegex(),
            """(?i)Web\s*Series""".toRegex(),
            """(?i)\bSeries\b""".toRegex()
        )
        
        select("a[href]").forEach { link ->
            val href = link.absUrl("href")
            val text = link.text().trim()
            
            // Filter: Only movie/show pages (length > 20 chars, contains main domain, not category/search pages)
            if (href.contains(mainHost) && 
                text.length > 20 && 
                !href.contains("/category/") && 
                !href.contains("/search") &&
                !href.contains("/page/") &&
                (href.contains("-movie") || href.contains("-full-") || href.contains("-season") || 
                 href.contains("-episode") || href.contains("-webrip") || href.contains("-hindi"))) {
                
                val cleanedTitle = extractCleanTitle(text)
                val year = extractYear(text)
                val isSeries = seriesPatterns.any { it.containsMatchIn(text) } || 
                               href.contains("season") || href.contains("series")
                
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
        
        // Primary selector: ul.recent-movies > li
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
        // Extract link
        val link = post.selectFirst("a")?.absUrl("href")
            ?.takeIf { it.isNotBlank() && it.contains(mainUrl.substringAfter("://").substringBefore("/")) }
            ?: return null
        
        // Extract poster
        val posterElement = post.selectFirst("img")
        val poster = posterElement?.absUrl("src")
            ?: posterElement?.attr("data-src")
            ?: posterElement?.absUrl("data-lazy-src")
        
        // Extract title from multiple sources
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
        
        // Detect if series using inline regex
        val seriesPatterns = listOf(
            """(?i)Season\s*\d+""".toRegex(),
            """(?i)\bS\d+\b""".toRegex(),
            """(?i)EP\s*[-]?\s*\d+""".toRegex(),
            """(?i)Episode\s*\d+""".toRegex(),
            """(?i)All\s*Episodes?""".toRegex(),
            """(?i)Web\s*Series""".toRegex(),
            """(?i)\bSeries\b""".toRegex()
        )
        val isSeries = seriesPatterns.any { it.containsMatchIn(rawTitle) } || 
                       link.contains("season", ignoreCase = true) || 
                       link.contains("episode", ignoreCase = true) || 
                       link.contains("series", ignoreCase = true)
        
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
        
        // Extract poster
        val poster = document.selectFirst(".entry-content img, .post-content img, article img")?.absUrl("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        
        // Extract description/plot
        val plot = document.selectFirst(".entry-content > p, .post-content > p")?.text()
            ?.takeIf { it.length > 20 && !it.contains("Download", ignoreCase = true) }
        
        // Detect quality from title - inline regex
        val qualityRegex = """(?i)(480p|720p|1080p|2160p|4K|HDRip|WEB-?DL|WEBRip|BluRay|HDTC|HDCAM)""".toRegex()
        val quality = qualityRegex.find(rawTitle)?.value
        
        // Check if series using inline regex patterns
        val seriesPatterns = listOf(
            """(?i)Season\s*\d+""".toRegex(),
            """(?i)\bS\d+\b""".toRegex(),
            """(?i)EP\s*[-]?\s*\d+""".toRegex(),
            """(?i)Episode\s*\d+""".toRegex(),
            """(?i)All\s*Episodes?""".toRegex(),
            """(?i)Web\s*Series""".toRegex(),
            """(?i)\bSeries\b""".toRegex()
        )
        val isSeries = seriesPatterns.any { it.containsMatchIn(rawTitle) } || 
                       url.contains("season", ignoreCase = true) ||
                       url.contains("episode", ignoreCase = true) || 
                       url.contains("series", ignoreCase = true)
        
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
        
        // Season regex - inline
        val seasonRegex = """(?i)(?:Season|S)\s*0?(\d+)""".toRegex()
        val seasonNum = seasonRegex.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        
        // Episode regex - inline
        val episodeRegex = """(?i)EP(?:isode)?\s*[-_]?\s*0?(\d+)""".toRegex()
        
        // Download domains regex - inline
        val downloadDomainsRegex = """(?i)(hubdrive|gadgetsweb|hubstream|hdstream4u|hubcloud|hblinks|4khdhub)""".toRegex()
        
        // Find all episode links
        document.select("a").forEach { link ->
            val text = link.text().trim()
            val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return@forEach
            
            // Match episode pattern
            val episodeMatch = episodeRegex.find(text)
            if (episodeMatch != null) {
                val episodeNum = episodeMatch.groupValues[1].toIntOrNull() ?: return@forEach
                
                // Skip if it's a link to another series (unless it's a download domain)
                val baseSlug = baseUrl.substringAfterLast("/").substringBefore("-season")
                if (!href.contains(baseSlug, ignoreCase = true) && !downloadDomainsRegex.containsMatchIn(href)) {
                    return@forEach
                }
                
                episodes.add(
                    newEpisode(href) {
                        this.name = "Episode $episodeNum"
                        this.season = seasonNum
                        this.episode = episodeNum
                    }
                )
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
        
        return episodes.distinctBy { it.episode }.sortedBy { it.episode }
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
        
        // Download domains regex - inline for this function
        val downloadDomainsRegex = """(?i)(hubdrive|gadgetsweb|hubstream|hdstream4u|hubcloud|hblinks|4khdhub)""".toRegex()
        
        // Extract all download/stream links
        document.select("a[href]").forEach { link ->
            val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return@forEach
            val text = link.text().lowercase()
            
            // Skip navigation and irrelevant links
            if (href.contains("disclaimer") || href.contains("how-to-download") ||
                href.contains("join-our-group") || href.contains("request-a-movie")) {
                return@forEach
            }
            
            // Match download domains using inline regex
            if (downloadDomainsRegex.containsMatchIn(href)) {
                Log.d(TAG, "Found link: $text -> $href")
                
                // Determine quality from link text
                val quality = ExtractorPatterns.extractQuality(text)
                
                try {
                    when (ExtractorPatterns.matchExtractor(href)) {
                        ExtractorType.HUBDRIVE -> {
                            Hubdrive().getUrl(href, name, subtitleCallback, callback)
                            foundLinks = true
                        }
                        ExtractorType.HUBCLOUD -> {
                            HubCloud().getUrl(href, name, subtitleCallback, callback)
                            foundLinks = true
                        }
                        ExtractorType.HUBCDN -> {
                            HUBCDN().getUrl(href, name, subtitleCallback, callback)
                            foundLinks = true
                        }
                        ExtractorType.HDSTREAM4U -> {
                            HdStream4u().getUrl(href, name, subtitleCallback, callback)
                            foundLinks = true
                        }
                        ExtractorType.HUBSTREAM -> {
                            Hubstream().getUrl(href, name, subtitleCallback, callback)
                            foundLinks = true
                        }
                        ExtractorType.HBLINKS -> {
                            Hblinks().getUrl(href, name, subtitleCallback, callback)
                            foundLinks = true
                        }
                        else -> {
                            loadSourceNameExtractor(name, href, "", quality, subtitleCallback, callback)
                            foundLinks = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Extractor error for $href: ${e.message}")
                }
            }
        }
        
        return foundLinks
    }
    
    // ===== Helper Functions (with inline regex) =====
    
    /**
     * Extract clean title removing quality/language tags
     */
    private fun extractCleanTitle(rawTitle: String): String {
        // Inline regex for cleaning title
        val cleanTitleRegex = """^(.+?)\s*(?:\d{4})?\s*[\[\(]?(?:Hindi|English|Tamil|Telugu|WEB-?DL|WEBRip|BluRay|HDRip|HDTC|HDCAM|4K|1080p|720p|480p|HEVC|x264|x265|Dual\s*Audio|HQ|Studio\s*Dub|Full\s*Movie|Full\s*Series|Season|EP|Episode)""".toRegex(RegexOption.IGNORE_CASE)
        
        // Try regex first
        cleanTitleRegex.find(rawTitle)?.groupValues?.get(1)?.trim()?.let { 
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
        // Inline regex for year extraction
        val yearRegex = """\b(19|20)\d{2}\b""".toRegex()
        return yearRegex.find(text)?.value?.toIntOrNull()
    }
}
