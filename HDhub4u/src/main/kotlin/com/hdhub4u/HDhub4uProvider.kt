package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.newEpisode
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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class HDhub4uProvider : MainAPI() {
    override var mainUrl = "https://new2.hdhub4u.fo"
    override var name = "HDhub4u"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    companion object {
        private const val TAG = "HDhub4u"
    }

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/bollywood-movies/" to "Bollywood",
        "category/hollywood-movies/" to "Hollywood",
        "category/hindi-dubbed/" to "Hindi Dubbed",
        "category/south-hindi-movies/" to "South Hindi",
        "category/category/web-series/" to "Web Series",
        "category/dual-audio/" to "Dual Audio",
        "category/netflix/" to "Netflix",
        "category/amazon-prime-video/" to "Prime Video",
        "category/jiohotstar/" to "JioHotstar",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}page/$page/"
        }

        Log.d(TAG, "Loading main page: $url")
        val document = app.get(url, headers = headers, timeout = 60).document

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
        val linkElement = selectFirst("figure a[href]") 
            ?: selectFirst("figcaption a[href]")
            ?: selectFirst("a[href]")
        
        val href = linkElement?.attr("href") ?: return null
        if (href.isBlank() || href.contains("category") || href.contains("page/")) return null
        
        val fixedUrl = fixUrl(href)
        
        // Extract title from figcaption p, or img alt, or a title
        val titleText = selectFirst("figcaption p")?.text()
            ?: selectFirst("figcaption a")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: selectFirst("img")?.attr("title")
            ?: selectFirst("a")?.attr("title")
            ?: ""
            
        // Clean title using Regex
        val title = cleanTitle(titleText)
        if (title.isBlank()) return null

        // Extract poster from figure img
        val posterUrl = selectFirst("figure img, img")?.let { img ->
            val src = img.attr("src").ifBlank { 
                img.attr("data-src").ifBlank { 
                    img.attr("data-lazy-src") 
                } 
            }
            fixUrlNull(src)
        }

        // Determine type using Regex pattern
        val isSeries = Regex("(?i)(Season|S0\\d|Episode|E0\\d|Complete|All\\s+Episodes)").containsMatchIn(titleText)

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
        val searchTerms = query.lowercase().split(" ").filter { it.length > 2 }
        
        // Since Typesense API is unreliable and website uses JS search,
        // we fetch home page and filter results by title matching
        try {
            // Fetch first 3 pages of content to search through
            for (page in 1..3) {
                val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
                val document = app.get(url, headers = headers, timeout = 30).document
                
                document.select("li.thumb").forEach { element ->
                    val searchResult = element.toSearchResult()
                    if (searchResult != null) {
                        // Check if title matches search query
                        val title = searchResult.name.lowercase()
                        val matches = searchTerms.any { term -> title.contains(term) }
                        
                        if (matches && results.none { it.url == searchResult.url }) {
                            results.add(searchResult)
                        }
                    }
                }
                
                // If we found enough results, stop
                if (results.size >= 20) break
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search error: ${e.message}")
        }
        
        Log.d(TAG, "Found ${results.size} search results")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "Loading: $url")
        val document = app.get(url, headers = headers, timeout = 60).document

        // Extract title using Regex
        val rawTitle = document.selectFirst("h1.single-title, .entry-title, h1.post-title, h1")?.text()?.trim()
            ?: return null
        val title = cleanTitle(rawTitle)

        // Extract poster using Regex patterns
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".entry-content img[src*='tmdb'], .entry-content img, .post-content img")?.attr("src")

        // Extract description
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        // Extract year using Regex
        val yearRegex = Regex("""\((\d{4})\)""")
        val year = yearRegex.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        // Extract tags/genres
        val tags = document.select(".entry-categories a, .post-categories a, .cat-links a, a[rel=tag]").map { it.text() }

        // Extract ALL download links - use body html since content class varies
        val downloadLinks = mutableListOf<DownloadLink>()
        
        // Method 1: Use CSS selectors to find all links on page
        document.select("a[href]").forEach { element ->
            val href = element.attr("href")
            val text = element.text().trim()
            
            if (isValidDownloadLink(href) && downloadLinks.none { it.url == href }) {
                val quality = extractQuality(text.ifBlank { href })
                val size = parseFileSize(text)
                
                downloadLinks.add(
                    DownloadLink(
                        url = href,
                        quality = quality,
                        sizeMB = size,
                        originalText = text
                    )
                )
            }
        }
        
        // Method 2: Use Regex on full body HTML for any missed links
        val bodyHtml = document.body()?.html() ?: ""
        val urlPattern = Regex("""https?://(?:hubdrive\.space|gadgetsweb\.xyz|hdstream4u\.com|hubstream\.art)[^"'<\s>]+""", RegexOption.IGNORE_CASE)
        
        urlPattern.findAll(bodyHtml).forEach { match ->
            val linkUrl = match.value
            if (downloadLinks.none { it.url == linkUrl }) {
                val quality = extractQuality(linkUrl)
                downloadLinks.add(
                    DownloadLink(
                        url = linkUrl,
                        quality = quality,
                        sizeMB = 0.0,
                        originalText = ""
                    )
                )
            }
        }
        
        Log.d(TAG, "Total links found: ${downloadLinks.size}")
        
        // Smart sort: Quality DESC, Size ASC, then priority
        val sortedLinks = downloadLinks.sortedWith(
            compareByDescending<DownloadLink> { it.quality }
                .thenBy { if (it.sizeMB > 0) it.sizeMB else Double.MAX_VALUE }
        )

        // Create data string (comma separated URLs)
        val data = sortedLinks.joinToString(",") { it.url }
        
        Log.d(TAG, "Sorted Links: ${sortedLinks.take(5).map { "${it.originalText} (${it.quality}p, ${it.sizeMB}MB)" }}")

        // Determine if series using Regex
        val isSeries = Regex("(?i)(Season|S0\\d|Episode|E0\\d|Complete|All\\s+Episodes)").containsMatchIn(rawTitle)

        return if (isSeries) {
            // Extract episodes for series
            val episodes = parseEpisodes(document, sortedLinks)
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, data) {
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

    private fun parseEpisodes(document: org.jsoup.nodes.Document, links: List<DownloadLink>): List<com.lagradost.cloudstream3.Episode> {
        // Try to extract episode info using Regex
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        
        // Pattern for episode numbers in links
        val episodePattern = Regex("""(?:E|Episode|Ep)[\s.-]*(\d+)""", RegexOption.IGNORE_CASE)
        
        val groupedByEpisode = links.groupBy { link ->
            episodePattern.find(link.originalText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        
        if (groupedByEpisode.size > 1 || (groupedByEpisode.size == 1 && groupedByEpisode.keys.first() != 0)) {
            groupedByEpisode.forEach { (episodeNum, episodeLinks) ->
                if (episodeNum > 0) {
                    val data = episodeLinks.joinToString(",") { it.url }
                    episodes.add(
                        newEpisode(data) {
                            this.name = "Episode $episodeNum"
                            this.episode = episodeNum
                        }
                    )
                }
            }
        }
        
        // If no episodes found, treat all links as single episode
        if (episodes.isEmpty() && links.isNotEmpty()) {
            val data = links.joinToString(",") { it.url }
            episodes.add(
                newEpisode(data) {
                    this.name = "Full Season"
                    this.episode = 1
                }
            )
        }
        
        return episodes.sortedBy { it.episode }
    }

    private fun isValidDownloadLink(url: String): Boolean {
        val validHosts = listOf(
            "hubdrive", "gadgetsweb", "hdstream4u", "hubstream",
            "hubcloud", "hubcdn", "gamerxyt", "gamester"
        )
        return validHosts.any { url.contains(it, ignoreCase = true) }
    }

    private fun extractQuality(text: String): Int {
        // Regex pattern for quality extraction
        val qualityRegex = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
        val match = qualityRegex.find(text)
        
        return match?.groupValues?.get(1)?.toIntOrNull() ?: when {
            text.contains("4k", ignoreCase = true) || text.contains("2160", ignoreCase = true) -> 2160
            text.contains("1080", ignoreCase = true) -> 1080
            text.contains("720", ignoreCase = true) -> 720
            text.contains("480", ignoreCase = true) -> 480
            text.contains("360", ignoreCase = true) -> 360
            else -> 0
        }
    }

    private fun parseFileSize(text: String): Double {
        // Regex pattern for file size extraction
        val sizeRegex = Regex("""(\d+(?:\.\d+)?)\s*(GB|MB)""", RegexOption.IGNORE_CASE)
        val match = sizeRegex.find(text) ?: return 0.0
        
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0.0
        val unit = match.groupValues[2].uppercase()
        
        return if (unit == "GB") value * 1024 else value
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "Loading links from: $data")

        try {
            val links = if (data.isBlank()) {
                emptyList()
            } else if (data.startsWith("http")) {
                if (data.contains(",")) {
                    data.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    listOf(data)
                }
            } else {
                data.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }

            Log.d(TAG, "Processing ${links.size} links")

            links.forEach { link ->
                try {
                    when {
                        // HubDrive patterns
                        link.contains("hubdrive", ignoreCase = true) -> {
                            HubDrive().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // GadgetsWeb patterns
                        link.contains("gadgetsweb", ignoreCase = true) -> {
                            GadgetsWeb().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // HDStream4u patterns
                        link.contains("hdstream4u", ignoreCase = true) -> {
                            HDStream4u().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // HubStream patterns
                        link.contains("hubstream", ignoreCase = true) -> {
                            HubStream().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // HubCloud/Gamerxyt patterns
                        link.contains("hubcloud", ignoreCase = true) ||
                        link.contains("hubcdn", ignoreCase = true) ||
                        link.contains("gamester", ignoreCase = true) ||
                        link.contains("gamerxyt", ignoreCase = true) -> {
                            HubCloud().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // Generic extractor
                        else -> {
                            loadExtractor(link, mainUrl, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading link $link: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadLinks: ${e.message}")
            e.printStackTrace()
        }

        return true
    }

    private fun cleanTitle(title: String): String {
        // Regex patterns to clean title
        return title
            .replace(Regex("""\(\d{4}\)"""), "")  // Remove year
            .replace(Regex("""\[.*?\]"""), "")     // Remove brackets content
            .replace(Regex("""(?i)(Download|Free|Full|Movie|HD|Watch|WEB-DL|BluRay|HDRip|WEBRip|\d{3,4}p)"""), "")
            .replace(Regex("""\s+"""), " ")        // Normalize spaces
            .trim()
    }
}
