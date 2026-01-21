package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.runBlocking

/**
 * HDhub4u Provider for CloudStream
 * Flexible scraping with inline regex patterns for https://new2.hdhub4u.fo/
 */
class HDhub4uProvider : MainAPI() {
    
    companion object {
        private const val TAG = "HDhub4u"
        private const val DOMAIN_API_URL = "https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json"
    }
    
    // ===== Provider Configuration =====
    
    override var name = "HDHub4u"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    // Dynamic domain
    override var mainUrl = "https://new2.hdhub4u.fo"
    
    init {
        runBlocking { 
            try {
                val response = app.get(DOMAIN_API_URL, timeout = 10L).text
                val urlsJson = AppUtils.parseJson<Map<String, String>>(response)
                urlsJson["hdhub4u"]?.let { if (it.isNotBlank()) mainUrl = it }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch domain: ${e.message}")
            }
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
        val searchUrl = "$mainUrl/search.html?q=${query.replace(" ", "+")}"
        Log.d(TAG, "Searching: $searchUrl")
        
        val document = app.get(searchUrl, timeout = 15000).document
        val results = document.toSearchResults()
        
        if (results.isEmpty()) {
            return document.toSearchLinkResults()
        }
        
        return results
    }
    
    // ===== Parse Search Page Links (for search.html page format) =====
    
    private fun Document.toSearchLinkResults(): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val mainHost = mainUrl.substringAfter("://").substringBefore("/")
        
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
                    newTvSeriesSearchResponse(cleanedTitle, href, TvType.TvSeries) {
                        year?.let { this.year = it }
                    }
                } else {
                    newMovieSearchResponse(cleanedTitle, href, TvType.Movie) {
                        year?.let { this.year = it }
                    }
                }
                
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
        
        select("ul.recent-movies > li").forEach { element ->
            toResult(element)?.let { results.add(it) }
        }
        
        if (results.isEmpty()) {
            select("article, .post, .thumb, li.thumb").forEach { element ->
                toResult(element)?.let { results.add(it) }
            }
        }
        
        return results
    }
    
    // ===== Convert Element to SearchResponse =====
    
    private fun toResult(post: Element): SearchResponse? {
        val link = post.selectFirst("a")?.absUrl("href")
            ?.takeIf { it.isNotBlank() && it.contains(mainUrl.substringAfter("://").substringBefore("/")) }
            ?: return null
        
        val posterElement = post.selectFirst("img")
        val poster = posterElement?.absUrl("src")
            ?: posterElement?.attr("data-src")
            ?: posterElement?.absUrl("data-lazy-src")
        
        val rawTitle = post.selectFirst("figcaption p")?.text()
            ?: post.selectFirst("figcaption a")?.text()
            ?: post.selectFirst(".title")?.text()
            ?: post.selectFirst("h2, h3, h4")?.text()
            ?: posterElement?.attr("alt")
            ?: posterElement?.attr("title")
            ?: return null
        
        val cleanedTitle = extractCleanTitle(rawTitle)
        val year = extractYear(rawTitle)
        
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
            newTvSeriesSearchResponse(cleanedTitle, link, TvType.TvSeries) {
                this.posterUrl = poster
                year?.let { this.year = it }
            }
        } else {
            newMovieSearchResponse(cleanedTitle, link, TvType.Movie) {
                this.posterUrl = poster
                year?.let { this.year = it }
            }
        }
    }
    
    // ===== Load Movie/Series Detail =====
    
    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "Loading detail: $url")
        
        val document = app.get(url, timeout = 15000).document
        
        val rawTitle = document.selectFirst("h1, .entry-title, .post-title")?.text() ?: "Unknown"
        val cleanedTitle = extractCleanTitle(rawTitle)
        val year = extractYear(rawTitle)
        
        val poster = document.selectFirst(".entry-content img, .post-content img, article img")?.absUrl("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        
        val plot = document.selectFirst(".entry-content > p, .post-content > p")?.text()
            ?.takeIf { it.length > 20 && !it.contains("Download", ignoreCase = true) }
        
        val qualityRegex = """(?i)(480p|720p|1080p|2160p|4K|HDRip|WEB-?DL|WEBRip|BluRay|HDTC|HDCAM)""".toRegex()
        val quality = qualityRegex.find(rawTitle)?.value
        
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
            val episodes = extractEpisodes(document, url, rawTitle)
            
            newTvSeriesLoadResponse(cleanedTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                year?.let { this.year = it }
                this.tags = listOfNotNull(quality)
            }
        } else {
            newMovieLoadResponse(cleanedTitle, url, TvType.Movie, url) {
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
        
        val seasonRegex = """(?i)(?:Season|S)\s*0?(\d+)""".toRegex()
        val seasonNum = seasonRegex.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        
        val episodeRegex = """(?i)EP(?:isode)?\s*[-_]?\s*0?(\d+)""".toRegex()
        val downloadDomainsRegex = """(?i)(hubdrive|gadgetsweb|hubstream|hdstream4u|hubcloud|hblinks|4khdhub)""".toRegex()
        
        document.select("a").forEach { link ->
            val text = link.text().trim()
            val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return@forEach
            
            val episodeMatch = episodeRegex.find(text)
            if (episodeMatch != null) {
                val episodeNum = episodeMatch.groupValues[1].toIntOrNull() ?: return@forEach
                
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
    
    // ===== Load Links (using loadExtractor) =====
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "Loading links from: $data")
        
        val document = app.get(data, timeout = 15000).document
        var foundLinks = false
        
        val downloadDomainsRegex = """(?i)(hubdrive|gadgetsweb|hubstream|hdstream4u|hubcloud|hblinks|4khdhub)""".toRegex()
        
        document.select("a[href]").forEach { link ->
            val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return@forEach
            val text = link.text().lowercase()
            
            if (href.contains("disclaimer") || href.contains("how-to-download") ||
                href.contains("join-our-group") || href.contains("request-a-movie")) {
                return@forEach
            }
            
            if (downloadDomainsRegex.containsMatchIn(href)) {
                Log.d(TAG, "Found link: $text -> $href")
                
                try {
                    // Use loadExtractor to handle all extractors
                    loadExtractor(href, data, subtitleCallback, callback)
                    foundLinks = true
                } catch (e: Exception) {
                    Log.e(TAG, "Extractor error for $href: ${e.message}")
                }
            }
        }
        
        return foundLinks
    }
    
    // ===== Helper Functions =====
    
    private fun extractCleanTitle(rawTitle: String): String {
        val cleanTitleRegex = """^(.+?)\s*(?:\d{4})?\s*[\[\(]?(?:Hindi|English|Tamil|Telugu|WEB-?DL|WEBRip|BluRay|HDRip|HDTC|HDCAM|4K|1080p|720p|480p|HEVC|x264|x265|Dual\s*Audio|HQ|Studio\s*Dub|Full\s*Movie|Full\s*Series|Season|EP|Episode)""".toRegex(RegexOption.IGNORE_CASE)
        
        cleanTitleRegex.find(rawTitle)?.groupValues?.get(1)?.trim()?.let { 
            if (it.length > 2) return it 
        }
        
        val parts = rawTitle.split(Regex("""[\[\(]"""))
        return parts.firstOrNull()?.trim()?.takeIf { it.length > 2 } ?: rawTitle.trim()
    }
    
    private fun extractYear(text: String): Int? {
        val yearRegex = """\b(19|20)\d{2}\b""".toRegex()
        return yearRegex.find(text)?.value?.toIntOrNull()
    }
}
