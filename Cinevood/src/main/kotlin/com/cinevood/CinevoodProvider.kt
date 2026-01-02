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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.nodes.Element

class CinevoodProvider : MainAPI() {
    override var mainUrl: String = "https://1cinevood.codes"

    init {
        runBlocking {
            baseMainUrl?.let {
                mainUrl = it
            }
        }
    }

    companion object {
        private const val TAG = "Cinevood"

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

        private val cfKiller by lazy { CloudflareKiller() }
    }

    override var name = "Cinevood"
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
        val document = app.get(url, interceptor = cfKiller).document

        val home = document.select("article.latestPost").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Title extraction
        val title = selectFirst("h2.title a, .front-view-title a")?.text()?.trim() ?: return null

        // URL extraction
        val href = selectFirst("h2.title a, .front-view-title a")?.attr("href") ?: return null
        val fixedUrl = fixUrl(href)

        // Poster extraction - prioritize TMDB images
        val posterUrl = selectFirst(".featured-thumbnail img")?.let { img ->
            fixUrlNull(img.attr("src").ifBlank { img.attr("data-src") })
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
        val document = app.get("$mainUrl/?s=$query", interceptor = cfKiller).document

        return document.select("article.latestPost").mapNotNull { result ->
            val title = result.selectFirst("h2.title a, .front-view-title a")?.text()?.trim()
                ?: return@mapNotNull null
            val href = result.selectFirst("h2.title a, .front-view-title a")?.attr("href")
                ?: return@mapNotNull null
            val posterUrl = result.selectFirst(".featured-thumbnail img")?.let { img ->
                fixUrlNull(img.attr("src").ifBlank { img.attr("data-src") })
            }

            val isSeries = title.contains("Season", ignoreCase = true) ||
                    title.contains("S0", ignoreCase = true)

            if (isSeries) {
                newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    // Data class for link information (Movierulzhd style)
    data class LinkData(
        val url: String,
        val type: String,  // "hubcloud", "filepress", "other"
        val name: String? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "Loading: $url")
        val document = app.get(url, interceptor = cfKiller).document

        // Extract title
        val rawTitle =
            document.selectFirst("h1.page-title, .entry-title")?.text()?.trim() ?: return null
        val title = cleanTitle(rawTitle)

        // Extract poster - TMDB images
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".entry-content img[src*='tmdb'], .entry-content img")
                ?.attr("src")

        // Extract description
        val description = document.selectFirst("meta[name=description]")?.attr("content")

        // Extract year from title
        val yearRegex = Regex("\\((\\d{4})\\)")
        val year = yearRegex.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        // Extract tags/genres
        val tags = document.select(".entry-categories a, .post-categories a").map { it.text() }

        // Extract download links with type classification (Movierulzhd style)
        val linkDataList = mutableListOf<LinkData>()
        
        // Find all download links
        document.select("a[href*='hubcloud'], a[href*='gamerxyt']").forEach { element ->
            val href = element.attr("href")
            val linkText = element.text().trim()
            if (href.isNotBlank()) {
                linkDataList.add(LinkData(href, "hubcloud", linkText.ifBlank { "Hubcloud" }))
            }
        }
        
        document.select("a[href*='filepress']").forEach { element ->
            val href = element.attr("href")
            val linkText = element.text().trim()
            if (href.isNotBlank()) {
                linkDataList.add(LinkData(href, "filepress", linkText.ifBlank { "Filepress" }))
            }
        }
        
        // Other extractable links
        document.select("a[href*='streamwish'], a[href*='doodstream'], a[href*='dood.'], a[href*='swhoi']").forEach { element ->
            val href = element.attr("href")
            val linkText = element.text().trim()
            if (href.isNotBlank()) {
                linkDataList.add(LinkData(href, "other", linkText.ifBlank { "Stream" }))
            }
        }

        Log.d(TAG, "Found ${linkDataList.size} download links")

        val isSeries = rawTitle.contains("Season", ignoreCase = true) ||
                rawTitle.contains("S0", ignoreCase = true) ||
                rawTitle.contains("Episode", ignoreCase = true)

        // Convert link data list to JSON for passing to loadLinks
        val dataJson = linkDataList.toJson()

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "Loading links from data: ${data.take(100)}...")
        
        try {
            // Try to parse as LinkData list (new format)
            val linkDataList = try {
                parseJson<List<LinkData>>(data)
            } catch (e: Exception) {
                // Fallback: Try old comma-separated format
                null
            }
            
            if (linkDataList != null && linkDataList.isNotEmpty()) {
                // New Movierulzhd-style processing
                Log.d(TAG, "Processing ${linkDataList.size} links (new format)")
                
                linkDataList.forEach { linkData ->
                    try {
                        when (linkData.type) {
                            "hubcloud" -> {
                                Log.d(TAG, "Processing Hubcloud: ${linkData.url}")
                                Hubcloud().getUrl(linkData.url, mainUrl, subtitleCallback, callback)
                            }
                            
                            "filepress" -> {
                                Log.d(TAG, "Processing Filepress: ${linkData.url}")
                                Filepress().getUrl(linkData.url, mainUrl, subtitleCallback, callback)
                            }
                            
                            "other" -> {
                                Log.d(TAG, "Processing other extractor: ${linkData.url}")
                                loadExtractor(linkData.url, mainUrl, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing link ${linkData.url}: ${e.message}")
                    }
                }
            } else {
                // Fallback: Old format - comma separated or direct URL
                val links = if (data.startsWith("[")) {
                    // Empty array or invalid JSON
                    emptyList()
                } else if (data.startsWith("http")) {
                    // Single URL
                    listOf(data)
                } else {
                    // Comma separated
                    data.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                }
                
                Log.d(TAG, "Processing ${links.size} links (legacy format)")
                
                links.forEach { link ->
                    try {
                        when {
                            link.contains("hubcloud", ignoreCase = true) || 
                            link.contains("gamerxyt", ignoreCase = true) -> {
                                Log.d(TAG, "Using Hubcloud extractor for: $link")
                                Hubcloud().getUrl(link, mainUrl, subtitleCallback, callback)
                            }
                            
                            link.contains("filepress", ignoreCase = true) -> {
                                Log.d(TAG, "Using Filepress extractor for: $link")
                                Filepress().getUrl(link, mainUrl, subtitleCallback, callback)
                            }
                            
                            else -> {
                                Log.d(TAG, "Using generic extractor for: $link")
                                loadExtractor(link, mainUrl, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading link $link: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadLinks: ${e.message}")
            e.printStackTrace()
        }
        
        return true
    }

    private fun cleanTitle(rawTitle: String): String {
        // Remove year in parentheses and extra metadata
        return rawTitle.replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\[.*?]"), "")
            .trim()
    }
}
