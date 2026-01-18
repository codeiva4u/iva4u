package com.cinevood

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
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.nodes.Element

class CinevoodProvider : MainAPI() {
    override var mainUrl: String = "https://1cinevood.biz"

    init {
        runBlocking {
            baseMainUrl?.takeIf { it.isNotBlank() }?.let {
                mainUrl = it
            }
        }
    }

    companion object {
        private const val TAG = "CineVood"

        // Regex patterns for extraction
        private val YEAR_REGEX = Regex("""\((\d{4})\)""")
        private val QUALITY_REGEX = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
        private val SIZE_REGEX = Regex("""(\d+(?:\.\d+)?)\s*(GB|MB)""", RegexOption.IGNORE_CASE)
        private val SERIES_REGEX = Regex("""(?i)(Season|S0\d|Episode|E0\d|Complete|All\s+Episodes)""")
        private val TITLE_CLEAN_REGEX = Regex("""(?i)\(?\d{4}\)?|Download|Free|Full|Movie|HD|Watch|WEB-DL|BluRay|HDRip|WEBRip|HDTC|HQ|\d{3,4}p|x264|x265|HEVC|AAC|ESub""")
        
        // Valid download host patterns
        private val VALID_HOSTS_REGEX = Regex("""(?i)(oxxfile|hubcloud|hubcdn|gamester|gamerxyt|filepress|filebee|streamwish|embedwish|wishembed|swhoi|wishfast|sfastwish|awish|dwish|streamvid|dood|doodstream|d0o0d|d000d|ds2play|do0od)""")

        val baseMainUrl: String? by lazy {
            runBlocking {
                try {
                    val response =
                        app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json")
                    val json = response.text
                    val jsonObject = JSONObject(json)
                    jsonObject.optString("cinevood").ifBlank { null }
                } catch (_: Exception) {
                    null
                }
            }
        }

        val cfKiller by lazy { CloudflareKiller() }
        const val CF_RETRY_COUNT = 3
    }

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to "$mainUrl/"
    )

    override var name = "CineVood"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "bollywood/" to "Bollywood",
        "hollywood/" to "Hollywood",
        "punjabi/" to "Punjabi",
        "hindi-dubbed/hollywood-dubbed/" to "Hollywood Dubbed",
        "hindi-dubbed/south-dubbed/" to "South Dubbed",
        "bengali/" to "Bengali",
        "gujarati/" to "Gujarati",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}page/$page/"
        }

        Log.d(TAG, "Loading main page: $url")
        val document = try {
            app.get(url, headers = headers, interceptor = cfKiller, timeout = 60).document
        } catch (e: Exception) {
            Log.e(TAG, "CF bypass attempt 1 failed: ${e.message}")
            try {
                app.get(url, headers = headers, interceptor = cfKiller, timeout = 90).document
            } catch (e2: Exception) {
                Log.e(TAG, "CF bypass attempt 2 failed: ${e2.message}")
                app.get(url, headers = headers, timeout = 120).document
            }
        }

        // Support multiple selectors: article.latestPost (new theme) and div.thumb (old theme)
        val home = document.select("article.latestPost, article.excerpt, div.thumb, .post-item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Multi-selector support for different theme structures
        
        // 1. Extract link - try multiple selectors
        val linkElement = selectFirst("a.post-image[href]")
            ?: selectFirst("h2.title a[href]")
            ?: selectFirst("a[href][title]")
            ?: selectFirst("a[href]")
        
        val href = linkElement?.attr("href") ?: return null
        if (href.isBlank() || href.contains("/category/") || href.contains("/page/")) return null
        
        val fixedUrl = fixUrl(href)

        // 2. Extract title using multiple methods with regex cleaning
        val rawTitle = linkElement?.attr("title")?.trim()?.ifBlank { null }
            ?: selectFirst("h2.title a, h2 a, .entry-title a")?.text()?.trim()
            ?: linkElement?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        
        if (rawTitle.isBlank() || rawTitle.equals("Download", ignoreCase = true)) return null
        
        // Clean title using regex
        val title = cleanTitle(rawTitle)
        if (title.isBlank()) return null

        // 3. Extract poster - check multiple sources with better fallbacks
        val imgElement = selectFirst(".featured-thumbnail img")
            ?: selectFirst(".post-thumbnail img")
            ?: selectFirst("a.post-image img")
            ?: selectFirst("img[src]")
            ?: selectFirst("img")
        
        val posterUrl = imgElement?.let { img ->
            val src = img.attr("src").ifBlank { 
                img.attr("data-src").ifBlank { 
                    img.attr("data-lazy-src").ifBlank {
                        img.attr("data-original")
                    }
                } 
            }
            if (src.isNotBlank()) fixUrlNull(src) else null
        }

        // 4. Determine type using regex
        val isSeries = SERIES_REGEX.containsMatchIn(rawTitle)

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
        val document = try {
            app.get("$mainUrl/?s=$query", headers = headers, interceptor = cfKiller, timeout = 60).document
        } catch (e: Exception) {
            Log.e(TAG, "Search CF bypass failed: ${e.message}")
            app.get("$mainUrl/?s=$query", headers = headers, timeout = 90).document
        }

        return document.select("article.latestPost, article.excerpt, div.thumb, .post-item").mapNotNull { result ->
            result.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "Loading: $url")
        val document = try {
            app.get(url, headers = headers, interceptor = cfKiller, timeout = 60).document
        } catch (e: Exception) {
            Log.e(TAG, "Load CF bypass failed: ${e.message}")
            app.get(url, headers = headers, timeout = 90).document
        }

        // Extract title using multiple selectors
        val rawTitle = document.selectFirst(".entry-title, h1.page-title, h1.post-title, h1")?.text()?.trim() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.replace(" - CineVood", "")?.trim()
            ?: return null
        val title = cleanTitle(rawTitle)

        // Extract poster using regex pattern matching for image sources
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img[src*='tmdb.org']")?.attr("src")
            ?: document.selectFirst("img[src*='amazon']")?.attr("src")
            ?: document.selectFirst(".entry-content img, .post-content img")?.attr("src")

        // Extract description
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        // Extract year using regex
        val year = YEAR_REGEX.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        // Extract tags/genres
        val tags = document.select(".entry-categories a, .post-categories a, .cat-links a, a[rel=tag]").map { it.text() }

        // Extract ALL download links using regex-based validation
        val smartLinks = mutableListOf<SmartLink>()
        val bodyHtml = document.body().html()

        // Method 1: CSS Selectors for links
        document.select(".entry-content a[href], .post-content a[href], article a[href]")
            .forEach { element ->
                val href = element.attr("href")
                val text = element.text().trim()
                
                if (href.isNotBlank() && isValidLink(href, text)) {
                    if (smartLinks.none { it.url == href }) {
                        smartLinks.add(
                            SmartLink(
                                url = href,
                                quality = extractQuality(text),
                                sizeMB = parseFileSize(text),
                                hostPriority = getHostPriority(href),
                                originalText = text
                            )
                        )
                    }
                }
            }

        // Method 2: Regex for links in HTML (catches JavaScript-generated or hidden links)
        val urlPattern = Regex("""https?://[^\s"'<>]+(?:oxxfile|hubcloud|hubcdn|streamwish|dood)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
        urlPattern.findAll(bodyHtml).forEach { match ->
            val linkUrl = match.value
            if (smartLinks.none { it.url == linkUrl }) {
                smartLinks.add(
                    SmartLink(
                        url = linkUrl,
                        quality = extractQuality(linkUrl),
                        sizeMB = 0.0,
                        hostPriority = getHostPriority(linkUrl),
                        originalText = ""
                    )
                )
            }
        }

        Log.d(TAG, "Total links found: ${smartLinks.size}")

        // Smart sort: Quality DESC -> Size ASC -> Host Priority ASC
        val sortedLinks = smartLinks.sortedWith(
            compareByDescending<SmartLink> { it.quality }
                .thenBy { if (it.sizeMB > 0) it.sizeMB else Double.MAX_VALUE }
                .thenBy { it.hostPriority }
        )

        val data = sortedLinks.joinToString(",") { it.url }

        Log.d(TAG, "Sorted Links Top 5: ${sortedLinks.take(5).map { "${it.originalText.take(30)} (${it.quality}p)" }}")

        val isSeries = SERIES_REGEX.containsMatchIn(rawTitle)

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
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

    private data class SmartLink(
        val url: String,
        val quality: Int,
        val sizeMB: Double,
        val hostPriority: Int,
        val originalText: String
    )

    // Regex-based link validation
    private fun isValidLink(url: String, text: String): Boolean {
        // Check if URL matches valid hosts pattern
        if (VALID_HOSTS_REGEX.containsMatchIn(url)) return true
        
        // Check text for download indicators
        val textLower = text.lowercase()
        return textLower.contains("download") || 
               textLower.contains("watch") ||
               QUALITY_REGEX.containsMatchIn(text)
    }

    // Regex-based quality extraction
    private fun extractQuality(text: String): Int {
        val match = QUALITY_REGEX.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: when {
            text.contains("4k", ignoreCase = true) || text.contains("2160", ignoreCase = true) -> 2160
            text.contains("1080", ignoreCase = true) -> 1080
            text.contains("720", ignoreCase = true) -> 720
            text.contains("480", ignoreCase = true) -> 480
            else -> 0
        }
    }

    // Regex-based file size parsing
    private fun parseFileSize(text: String): Double {
        val match = SIZE_REGEX.find(text) ?: return 0.0
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0.0
        val unit = match.groupValues[2].uppercase()
        return if (unit == "GB") value * 1024 else value
    }

    private fun getHostPriority(url: String): Int {
        return when {
            url.contains("hubcloud", true) || url.contains("hubcdn", true) || 
            url.contains("gamester", true) || url.contains("gamerxyt", true) -> 1
            url.contains("oxxfile", true) -> 2
            url.contains("streamwish", true) || url.contains("wish", true) || 
            url.contains("filepress", true) -> 3
            url.contains("dood", true) -> 4
            else -> 10
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

            Log.d(TAG, "Processing ${links.size} sorted links")

            links.forEach { link ->
                try {
                    when {
                        link.contains("oxxfile", ignoreCase = true) -> {
                            OxxFile().getUrl(link, mainUrl, subtitleCallback, callback)
                        }
                        link.contains("hubcloud", ignoreCase = true) ||
                        link.contains("hubcdn", ignoreCase = true) ||
                        link.contains("gamester", ignoreCase = true) ||
                        link.contains("gamerxyt", ignoreCase = true) -> {
                            HubCloud().getUrl(link, mainUrl, subtitleCallback, callback)
                        }
                        link.contains("filepress", ignoreCase = true) ||
                        link.contains("filebee", ignoreCase = true) -> {
                            FilePressExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                        }
                        Regex("""(?i)(streamwish|embedwish|wishembed|swhoi|wishfast|sfastwish|awish|dwish|streamvid)""").containsMatchIn(link) -> {
                            StreamWishExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                        }
                        Regex("""(?i)(dood|doodstream|d0o0d|d000d|ds2play|do0od)""").containsMatchIn(link) -> {
                            DoodLaExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                        }
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

    // Regex-based title cleaning
    private fun cleanTitle(title: String): String {
        return title
            .replace(TITLE_CLEAN_REGEX, "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
