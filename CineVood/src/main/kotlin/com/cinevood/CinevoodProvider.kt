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
import java.util.Locale.getDefault

class CinevoodProvider : MainAPI() {
    override var mainUrl: String = "https://cinevood.ai.in"

    init {
        runBlocking {
            baseMainUrl?.takeIf { it.isNotBlank() }?.let {
                mainUrl = it
            }
        }
    }

    companion object {
        private const val TAG = "CineVood"

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

        // Improved CloudflareKiller with auto-bypass
        val cfKiller by lazy { 
            CloudflareKiller()
        }
        
        // Retry count for Cloudflare bypass
        const val CF_RETRY_COUNT = 3
    }

    // Modern User-Agent to pass Cloudflare checks
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
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

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
        // Use CloudflareKiller with retry logic for bypass
        val document = try {
            app.get(url, headers = headers, interceptor = cfKiller, timeout = 60).document
        } catch (e: Exception) {
            Log.e(TAG, "CF bypass attempt 1 failed: ${e.message}")
            // Retry with fresh headers
            try {
                app.get(url, headers = headers, interceptor = cfKiller, timeout = 90).document
            } catch (e2: Exception) {
                Log.e(TAG, "CF bypass attempt 2 failed: ${e2.message}")
                // Final attempt without interceptor
                app.get(url, headers = headers, timeout = 120).document
            }
        }

        val home = document.select("div.thumb").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // For div.thumb structure: contains <a> with title and <img> for poster
        val linkElement = selectFirst("a")
        val title = linkElement?.text()?.trim() ?: return null
        if (title.isBlank() || title.equals("Download", ignoreCase = true)) return null

        // URL extraction
        val href = linkElement.attr("href")
        val fixedUrl = fixUrl(href)

        // Poster extraction from img inside div.thumb
        val posterUrl = selectFirst("img")?.let { img ->
            fixUrlNull(img.attr("src").ifBlank { img.attr("data-src").ifBlank { img.attr("data-lazy-src") } })
        }

        // Determine type based on title
        val isSeries = title.contains("Season", ignoreCase = true) ||
                title.contains("S0", ignoreCase = true) ||
                title.contains("Episode", ignoreCase = true)

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

        return document.select("div.thumb").mapNotNull { result ->
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

        // Extract title
        val rawTitle = document.selectFirst("h1.page-title, .entry-title, h1.post-title")?.text()?.trim() 
            ?: return null
        val title = cleanTitle(rawTitle)

        // Extract poster - TMDB images
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".entry-content img[src*='tmdb'], .entry-content img")?.attr("src")

        // Extract description
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        // Extract year from title
        val yearRegex = Regex("\\((\\d{4})\\)")
        val year = yearRegex.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        // Extract tags/genres
        val tags = document.select(".entry-categories a, .post-categories a, .cat-links a").map { it.text() }

        // Extract ALL download links and parse for Smart Logic
        val smartLinks = mutableListOf<SmartLink>()

        // Look for links in entry-content
        // Added more broad selectors
        document.select(".entry-content a[href], .post-content a[href], article a[href], .download-links a[href]")
            .forEach { element ->
                val href = element.attr("href")
                val text = element.text().trim() 
                val lowerText = text.lowercase(getDefault())

                // Filter for download-related links
                // Added checks to properly identify valid video links
                if (href.isNotBlank() && isValidLink(href, lowerText)) {
                    // Avoid duplicates
                    if (smartLinks.none { it.url == href }) {
                        val quality = extractQuality(text)
                        val size = parseFileSize(text)
                        val priority = getHostPriority(href)
                        
                        smartLinks.add(
                            SmartLink(
                                url = href,
                                quality = quality,
                                sizeMB = size,
                                hostPriority = priority,
                                originalText = text
                            )
                        )
                    }
                }
            }

        Log.d(TAG, "Total links found: ${smartLinks.size}")
        
        // --- SMART SORT LOGIC REINFORCED ---
        // Requirement: 1080p > Smallest Size > Fastest Host
        // 1. Quality Descending (1080p first)
        // 2. Size Ascending (Smallest first, but > 0. If 0 treat as max to de-prioritize unknown sizes?) 
        //    Actually, user said "System should select smallest size". Unknown size is risky, maybe put last?
        //    Let's put known sizes first (ascending), then unknown.
        // 3. Host Priority Ascending (1 is fastest/best)
        
        val sortedLinks = smartLinks.sortedWith(
            compareByDescending<SmartLink> { it.quality }
                .thenBy { if (it.sizeMB > 0) it.sizeMB else Double.MAX_VALUE } 
                .thenBy { it.hostPriority }
        )

        // Create data string (comma separated URLs)
        val data = sortedLinks.joinToString(",") { it.url }
        
        Log.d(TAG, "Sorted Links Top 5: ${sortedLinks.take(5).map { "${it.originalText} (${it.quality}p, ${it.sizeMB}MB)" }}")

        val isSeries = rawTitle.contains("Season", ignoreCase = true) ||
                rawTitle.contains("S0", ignoreCase = true) ||
                rawTitle.contains("Episode", ignoreCase = true)

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
    
    // Helper to validate if a link is worth keeping
    private fun isValidLink(url: String, text: String): Boolean {
        return url.contains("oxxfile", ignoreCase = true) ||
                url.contains("hubcloud", ignoreCase = true) ||
                url.contains("hubcdn", ignoreCase = true) ||
                url.contains("gamester", ignoreCase = true) ||
                url.contains("gamerxyt", ignoreCase = true) ||
                url.contains("filepress", ignoreCase = true) ||
                url.contains("filebee", ignoreCase = true) ||
                url.contains("streamwish", ignoreCase = true) ||
                url.contains("embedwish", ignoreCase = true) ||
                url.contains("wishembed", ignoreCase = true) ||
                url.contains("swhoi", ignoreCase = true) ||
                url.contains("wishfast", ignoreCase = true) ||
                url.contains("sfastwish", ignoreCase = true) ||
                url.contains("awish", ignoreCase = true) ||
                url.contains("dwish", ignoreCase = true) ||
                url.contains("streamvid", ignoreCase = true) ||
                url.contains("dood", ignoreCase = true) ||
                url.contains("doodstream", ignoreCase = true) ||
                url.contains("d0o0d", ignoreCase = true) ||
                url.contains("d000d", ignoreCase = true) ||
                url.contains("ds2play", ignoreCase = true) ||
                url.contains("do0od", ignoreCase = true) ||
                // Redirect hosts that may contain download links
                url.contains("uptobhai", ignoreCase = true) ||
                url.contains("shortlinkto", ignoreCase = true) ||
                url.contains("linksly", ignoreCase = true) ||
                // Text-based detection
                text.contains("download", ignoreCase = true) ||
                text.contains("watch", ignoreCase = true) ||
                text.contains("stream", ignoreCase = true) ||
                text.contains("1080p", ignoreCase = true) ||
                text.contains("720p", ignoreCase = true) ||
                text.contains("480p", ignoreCase = true)
    }

    private fun extractQuality(text: String): Int {
        return when {
            text.contains("2160p", ignoreCase = true) || text.contains("4k", ignoreCase = true) -> 2160
            text.contains("1080p", ignoreCase = true) -> 1080
            text.contains("720p", ignoreCase = true) -> 720
            text.contains("480p", ignoreCase = true) -> 480
            else -> 0
        }
    }

    private fun parseFileSize(text: String): Double {
        val regex = Regex("(\\d+(?:\\.\\d+)?)\\s*(GB|MB)", RegexOption.IGNORE_CASE)
        val match = regex.find(text) ?: return 0.0
        
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0.0
        val unit = match.groupValues[2].uppercase()
        
        return if (unit == "GB") {
            value * 1024
        } else {
            value
        }
    }

    private fun getHostPriority(url: String): Int {
        return when {
            // Priority 1: Direct/Fast G-Drive types (HubCloud/Gamerx are usually fastest)
            url.contains("hubcloud", ignoreCase = true) || 
            url.contains("hubcdn", ignoreCase = true) || 
            url.contains("gamester", ignoreCase = true) ||
            url.contains("gamerxyt", ignoreCase = true) -> 1
            
            // Priority 2: Good streaming hosts
            url.contains("streamwish", ignoreCase = true) ||
            url.contains("wish", ignoreCase = true) ||
            url.contains("filepress", ignoreCase = true) -> 2
            
            // Priority 3: Dood (often slow but reliable)
            url.contains("dood", ignoreCase = true) -> 3
            
            // Others
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
            // Split comma-separated links. 
            // The list is ALREADY sorted by preference from load(), so we iterate sequentially.
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
                        // OxxFile patterns
                        link.contains("oxxfile", ignoreCase = true) -> {
                            OxxFile().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // HubCloud patterns
                        link.contains("hubcloud", ignoreCase = true) ||
                        link.contains("hubcdn", ignoreCase = true) ||
                        link.contains("gamester", ignoreCase = true) ||
                        link.contains("gamerxyt", ignoreCase = true) -> {
                            HubCloud().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // FilePress patterns
                        link.contains("filepress", ignoreCase = true) ||
                        link.contains("filebee", ignoreCase = true) -> {
                            FilePressExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // StreamWish patterns
                        link.contains("streamwish", ignoreCase = true) ||
                        link.contains("embedwish", ignoreCase = true) ||
                        link.contains("wishembed", ignoreCase = true) ||
                        link.contains("swhoi", ignoreCase = true) ||
                        link.contains("wishfast", ignoreCase = true) ||
                        link.contains("sfastwish", ignoreCase = true) ||
                        link.contains("awish", ignoreCase = true) ||
                        link.contains("dwish", ignoreCase = true) ||
                        link.contains("streamvid", ignoreCase = true) -> {
                            StreamWishExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // DoodStream patterns
                        link.contains("dood", ignoreCase = true) ||
                        link.contains("doodstream", ignoreCase = true) ||
                        link.contains("d0o0d", ignoreCase = true) ||
                        link.contains("d000d", ignoreCase = true) ||
                        link.contains("ds2play", ignoreCase = true) ||
                        link.contains("do0od", ignoreCase = true) -> {
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

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("Download|Free|Full|Movie|HD|Watch", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
