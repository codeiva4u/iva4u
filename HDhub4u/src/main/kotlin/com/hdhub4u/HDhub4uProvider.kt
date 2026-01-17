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
import org.jsoup.nodes.Element


class HDhub4uProvider : MainAPI() {
    companion object {
        private const val TAG = "HDhub4uProvider"
        private const val DEFAULT_URL = "https://new2.hdhub4u.fo"
    }

    // Dynamic mainUrl - fetched async from urls.json
    override var mainUrl: String = DEFAULT_URL
    private var mainUrlInitialized = false

    // Async init mainUrl (non-blocking)
    private suspend fun ensureMainUrl() {
        if (!mainUrlInitialized) {
            mainUrl = getLatestUrl(mainUrl, "hdhub4u")
            mainUrlInitialized = true
            Log.d(TAG, "Initialized mainUrl: $mainUrl")
        }
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
        // Ensure mainUrl is fetched (async, non-blocking)
        ensureMainUrl()

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
                val document = app.get(url, headers = headers, timeout = 20).document

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
        // Ensure mainUrl is initialized
        ensureMainUrl()

        val document = app.get(url, headers = headers, timeout = 20).document

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
        val bodyHtml = document.body().html()
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

        // Smart sort: X264 1080p priority -> Smallest Size -> Fastest Server
        val sortedLinks = downloadLinks.sortedWith(
            compareByDescending<DownloadLink> {
                // Priority 1: X264 1080p gets highest score
                when {
                    it.quality == 1080 && it.originalText.contains("x264", true) -> 200
                    it.quality == 1080 -> 150
                    it.quality == 2160 -> 140  // 4K
                    it.quality == 720 -> 100
                    it.quality == 480 -> 70
                    else -> 50
                }
            }.thenBy {
                // Priority 2: Smallest file size
                if (it.sizeMB > 0) it.sizeMB else Double.MAX_VALUE
            }.thenByDescending {
                // Priority 3: Fastest server
                getServerPriority(it.originalText)
            }
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

    // Server speed priority (higher = faster/preferred)
    private fun getServerPriority(serverName: String): Int {
        return when {
            serverName.contains("Instant", true) -> 100  // Instant DL = fastest
            serverName.contains("Direct", true) -> 90
            serverName.contains("FSLv2", true) -> 85
            serverName.contains("FSL", true) -> 80
            serverName.contains("10Gbps", true) -> 88
            serverName.contains("r2.dev", true) -> 85
            serverName.contains("gdboka", true) -> 82
            serverName.contains("Download", true) -> 70
            serverName.contains("Pixel", true) -> 60
            serverName.contains("Buzz", true) -> 55
            else -> 50
        }
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

            Log.d(TAG, "Processing ${links.size} links in parallel")

            // Limit to 5 best links for fast loading (10 sec target)
            val limitedLinks = links.take(5)
            Log.d(TAG, "Limited to ${limitedLinks.size} best links")

            // Process links in parallel using amap for 10 sec loading
            limitedLinks.amap { link ->
                try {
                    when {
                        // Hubdrive patterns
                        link.contains("hubdrive", ignoreCase = true) -> {
                            Hubdrive().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // HubCloud/Gamerxyt/hubstream patterns
                        link.contains("hubcloud", ignoreCase = true) ||
                                link.contains("hubcdn", ignoreCase = true) ||
                                link.contains("hubstream", ignoreCase = true) ||
                                link.contains("gamester", ignoreCase = true) ||
                                link.contains("gamerxyt", ignoreCase = true) -> {
                            HubCloud().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // HUBCDN patterns (gadgetsweb, hdstream4u)
                        link.contains("gadgetsweb", ignoreCase = true) ||
                                link.contains("hdstream4u", ignoreCase = true) -> {
                            HUBCDN().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // Hblinks patterns
                        link.contains("hblinks", ignoreCase = true) ||
                                link.contains("4khdhub", ignoreCase = true) -> {
                            Hblinks().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // Skip unknown links - only use project extractors
                        else -> {
                            Log.d(TAG, "Skipping unknown link (no extractor): $link")
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
            .replace(Regex("""\[.*?]"""), "")     // Remove brackets content
            .replace(Regex("""(?i)(Download|Free|Full|Movie|HD|Watch|WEB-DL|BluRay|HDRip|WEBRip|\d{3,4}p)"""), "")
            .replace(Regex("""\s+"""), " ")        // Normalize spaces
            .trim()
    }
}