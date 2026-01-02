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

        // Extract download links (oxxfile, Hubcloud, Filepress)
        val downloadLinks =
            document.select("a[href*='oxxfile'], a[href*='hubcloud'], a[href*='filepress']")
                .map { it.attr("href") }.distinct()

        Log.d(TAG, "Found ${downloadLinks.size} download links")

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
            newMovieLoadResponse(title, url, TvType.Movie, downloadLinks) {
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
        Log.d(TAG, "Loading links from: $data")
        
        // data contains comma-separated download links
        val links = data.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        
        Log.d(TAG, "Found ${links.size} links to process")
        
        links.forEach { link ->
            Log.d(TAG, "Processing link: $link")
            
            try {
                when {
                    link.contains("hubcloud", ignoreCase = true) -> {
                        Log.d(TAG, "Using Hubcloud extractor")
                        Hubcloud().getUrl(link, link, subtitleCallback, callback)
                    }
                    
                    link.contains("filepress", ignoreCase = true) -> {
                        Log.d(TAG, "Using Filepress extractor")
                        Filepress().getUrl(link, link, subtitleCallback, callback)
                    }
                    
                    link.contains("oxxfile", ignoreCase = true) -> {
                        Log.d(TAG, "Using generic extractor for oxxfile")
                        loadExtractor(link, link, subtitleCallback, callback)
                    }
                    
                    else -> {
                        Log.d(TAG, "Using generic extractor")
                        loadExtractor(link, link, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading link $link: ${e.message}")
            }
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


