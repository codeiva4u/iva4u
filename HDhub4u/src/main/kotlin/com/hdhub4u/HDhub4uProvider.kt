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
                if (urlString.isNotBlank()) urlString else null
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
        val linkElement: org.jsoup.nodes.Element? = selectFirst("figure a[href]")
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

        // Determine type using enhanced Regex pattern for series detection
        // Patterns from website: "Season X", "S01", "Episode", "EP-XX", "Complete", "All Episodes", "EP Added"
        val isSeries = Regex("""(?i)(Season\s*\d*|S0?\d|Episode|EP[-\s]?\d+|Complete|All\s*Episodes|EP\s*Added)""").containsMatchIn(titleText)

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
            var text = element.text().trim()

            // Always get context from parent/siblings for better episode detection
            // Even links with text like "WATCH" need prevSibling context ("EPiSODE 1")
            val parentText = element.parent()?.text()?.trim() ?: ""
            val prevSiblingText = element.previousElementSibling()?.text()?.trim() ?: ""
            val nextSiblingText = element.nextElementSibling()?.text()?.trim() ?: ""

            // Combine all context for episode detection
            // Priority: link text, then sibling context, then parent
            val episodeContext: String = when {
                text.contains("episode", true) || text.contains("ep", true) -> text
                prevSiblingText.contains("episode", true) -> "$prevSiblingText | $text"
                parentText.contains("episode", true) -> parentText
                text.isNotBlank() -> text
                parentText.isNotBlank() -> parentText
                else -> href
            }

            if (isValidDownloadLink(href) && downloadLinks.none { it.url == href }) {
                val qualityText = if (episodeContext.isBlank()) href else episodeContext
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

        // Method 2: Use Regex on full body HTML for any missed links
        // Pattern updated based on Brave Browser analysis of actual website links
        val bodyHtml = document.body().html()
        val urlPattern = Regex(
            """https?://(?:hubdrive\.(?:space|art)|gadgetsweb\.xyz|hubcloud\.[a-z]+|hblinks\.[a-z]+|4khdhub\.(?:dad|fans))[^"'<\s>]*""",
            RegexOption.IGNORE_CASE
        )

        // Enhanced episode context pattern - matches: EPiSODE 1, Episode 2, EP-03, EP 4, E05
        val episodeContextPattern = Regex("""(?:EPiSODE|Episode|EP|E)[-\s.]*(\d+)""", RegexOption.IGNORE_CASE)

        urlPattern.findAll(bodyHtml).forEach { match ->
            val linkUrl = match.value
            if (downloadLinks.none { it.url == linkUrl }) {
                val quality = extractQuality(linkUrl)

                // Try to find episode context from surrounding HTML (100 chars before the URL)
                val startPos = maxOf(0, match.range.first - 100)
                val surroundingText = bodyHtml.substring(startPos, match.range.first)
                val episodeMatch = episodeContextPattern.findAll(surroundingText).lastOrNull()
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

        Log.d(TAG, "Total links found: ${downloadLinks.size}")

        // Smart sort: 1080p priority → H264 codec → Smallest Size → Fastest Server
        val sortedLinks = downloadLinks.sortedWith(
            compareByDescending<DownloadLink> {
                // Priority 1: 1080p gets highest score
                when (it.quality) {
                    1080 -> 100
                    2160 -> 90  // 4K is good but 1080p preferred
                    720 -> 70
                    480 -> 50
                    else -> 30
                }
            }.thenByDescending {
                // Priority 2: H264/x264 preferred over HEVC/x265 (less buffering)
                val text = it.originalText.lowercase() + it.url.lowercase()
                when {
                    text.contains("x264") || text.contains("h264") || text.contains("h.264") -> 100
                    text.contains("hevc") || text.contains("x265") || text.contains("h265") || text.contains("h.265") -> 10
                    else -> 50 // Unknown codec - middle priority
                }
            }.thenBy {
                // Priority 3: Smallest file size among same quality
                if (it.sizeMB > 0) it.sizeMB else Double.MAX_VALUE
            }.thenByDescending {
                // Priority 4: Fastest server based on name
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
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()

        Log.d("HDhub4uProvider", "=== parseEpisodes START ===")
        Log.d("HDhub4uProvider", "Total links: ${links.size}")

        // Pattern for episode numbers - multiple formats
        val episodePattern = Regex("""(?:EPiSODE|Episode|EP|E)[.\s-]*(\d+)""", RegexOption.IGNORE_CASE)

        // Group links by episode number
        val groupedByEpisode = links.groupBy { link ->
            // Try to find episode number in text first
            var episodeNum = episodePattern.find(link.originalText)?.groupValues?.get(1)?.toIntOrNull()

            // If not found in text, try URL (for hubstream.art links which have unique IDs per episode)
            if (episodeNum == null || episodeNum == 0) {
                // For web series, links are often in order - use index as fallback later
                episodeNum = 0
            }
            episodeNum
        }

        Log.d("HDhub4uProvider", "Episode grouping: ${groupedByEpisode.keys}")

        // Create episodes from grouped links
        if (groupedByEpisode.size > 1 || (groupedByEpisode.size == 1 && groupedByEpisode.keys.first() != 0)) {
            groupedByEpisode.forEach { (episodeNum, episodeLinks) ->
                if (episodeNum > 0) {
                    // Prioritize hubcloud/hblinks over gadgetsweb
                    val sortedLinks = episodeLinks.sortedByDescending { link ->
                        when {
                            link.url.contains("hubcloud", true) -> 100
                            link.url.contains("hblinks", true) -> 90
                            link.url.contains("hubdrive", true) -> 80
                            link.url.contains("gadgetsweb", true) -> 50
                            else -> 30
                        }
                    }
                    val data = sortedLinks.joinToString(",") { it.url }
                    Log.d("HDhub4uProvider", "Episode $episodeNum data order: ${sortedLinks.map { it.url.substringAfter("://").take(15) }}")
                    episodes.add(
                        newEpisode(data) {
                            this.name = "Episode $episodeNum"
                            this.episode = episodeNum
                        }
                    )
                }
            }
        }

        // If still no episodes but we have links, try position-based episode assignment
        if (episodes.isEmpty() && links.isNotEmpty()) {
            // Check if we have download links with episode context
            val downloadLinks = links.filter {
                it.url.contains("gadgetsweb", true) && it.originalText.contains("episode", true)
            }

            if (downloadLinks.isNotEmpty()) {
                downloadLinks.forEachIndexed { index, link ->
                    val episodeNum = index + 1
                    episodes.add(
                        newEpisode(link.url) {
                            this.name = "Episode $episodeNum"
                            this.episode = episodeNum
                        }
                    )
                }
                Log.d("HDhub4uProvider", "Created ${episodes.size} episodes from download links")
            } else {
                // Fallback: treat all links as one episode (Full Season)
                val data = links.joinToString(",") { it.url }
                episodes.add(
                    newEpisode(data) {
                        this.name = "Full Season"
                        this.episode = 1
                    }
                )
            }
        }

        Log.d("HDhub4uProvider", "=== parseEpisodes END === Total episodes: ${episodes.size}")
        return episodes.sortedBy { it.episode }
    }

    private fun isValidDownloadLink(url: String): Boolean {
        // Valid hosts based on Brave Browser website analysis
        val validHosts = listOf(
            "hubdrive", "gadgetsweb", "hubcloud", "hubcdn",
            "gamerxyt", "gamester", "hblinks", "4khdhub"
        )
        return validHosts.any { url.contains(it, ignoreCase = true) }
    }

    private fun extractQuality(text: String): Int {
        // Enhanced quality extraction based on website patterns
        // Website uses: 4K, 1080p, 720p, 480p, 2160p, HDR, SDR, HEVC, x264
        val qualityRegex = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
        val match = qualityRegex.find(text)

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
            serverName.contains("Download File", true) -> 70
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
            // Parse comma-separated URLs
            val links = data.split(",")
                .map { it.trim() }
                .filter { it.startsWith("http") }

            Log.d(TAG, "Processing ${links.size} links")

            // Take top 3 links (already sorted by quality in load())
            links.take(3).amap { link ->
                try {
                    when {
                        link.contains("hubdrive", true) ->
                            Hubdrive().getUrl(link, mainUrl, subtitleCallback, callback)

                        link.contains("hubcloud", true) ||
                                link.contains("gamerxyt", true) ||
                                link.contains("gamester", true) ->
                            HubCloud().getUrl(link, mainUrl, subtitleCallback, callback)

                        link.contains("gadgetsweb", true) ->
                            HUBCDN().getUrl(link, mainUrl, subtitleCallback, callback)

                        // hdstream4u and hubstream removed - using HubCloud for video extraction
                        link.contains("hubcdn", true) ->
                            HubCloud().getUrl(link, mainUrl, subtitleCallback, callback)

                        link.contains("hblinks", true) ||
                                link.contains("4khdhub", true) ->
                            Hblinks().getUrl(link, mainUrl, subtitleCallback, callback)

                        else -> Log.w(TAG, "No extractor for: $link")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting $link: ${e.message}")
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