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
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.util.Locale.getDefault

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

        private val cfKiller by lazy { CloudflareKiller() }
    }

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

        // Extract ALL download links from entry-content area
        val downloadLinks = mutableListOf<String>()

        // Look for links in entry-content (main content area)
        document.select(".entry-content a[href], .post-content a[href], article a[href]")
            .forEach { element ->
                val href = element.attr("href")
                val text = element.text().lowercase(getDefault())

                // Filter for download-related links
                if (href.isNotBlank() && (
                    href.contains("oxxfile", ignoreCase = true) ||
                    href.contains("hubcloud", ignoreCase = true) ||
                    href.contains("hubcdn", ignoreCase = true) ||
                    href.contains("gamester", ignoreCase = true) ||
                    href.contains("gamerxyt", ignoreCase = true) ||
                    href.contains("filepress", ignoreCase = true) ||
                    href.contains("filebee", ignoreCase = true) ||
                    href.contains("streamwish", ignoreCase = true) ||
                    href.contains("embedwish", ignoreCase = true) ||
                    href.contains("wishembed", ignoreCase = true) ||
                    href.contains("swhoi", ignoreCase = true) ||
                    href.contains("wishfast", ignoreCase = true) ||
                    href.contains("sfastwish", ignoreCase = true) ||
                    href.contains("awish", ignoreCase = true) ||
                    href.contains("dwish", ignoreCase = true) ||
                    href.contains("streamvid", ignoreCase = true) ||
                    href.contains("dood", ignoreCase = true) ||
                    href.contains("doodstream", ignoreCase = true) ||
                    href.contains("d0o0d", ignoreCase = true) ||
                    href.contains("d000d", ignoreCase = true) ||
                    href.contains("ds2play", ignoreCase = true) ||
                    href.contains("do0od", ignoreCase = true) ||
                    text.contains("download", ignoreCase = true) ||
                    text.contains("watch", ignoreCase = true) ||
                    text.contains("stream", ignoreCase = true)
                )) {
                    if (!downloadLinks.contains(href)) {
                        downloadLinks.add(href)
                    }
                }
            }

        Log.d(TAG, "Total download links found: ${downloadLinks.size}")
        downloadLinks.forEach { Log.d(TAG, "Link: $it") }

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
            // Return as comma-separated string
            val data = downloadLinks.joinToString(",")
            newMovieLoadResponse(title, url, TvType.Movie, data) {
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

        try {
            // Parse comma-separated links
            val links = if (data.isBlank()) {
                emptyList()
            } else if (data.startsWith("http")) {
                // Single URL or comma separated
                if (data.contains(",")) {
                    data.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    listOf(data)
                }
            } else {
                // Comma separated
                data.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }

            Log.d(TAG, "Processing ${links.size} links")

            links.forEach { link ->
                try {
                    Log.d(TAG, "Processing link: $link")

                    when {
                        // OxxFile patterns (primary for Cinevood)
                        link.contains("oxxfile", ignoreCase = true) -> {
                            Log.d(TAG, "Using OxxFile extractor")
                            OxxFile().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // HubCloud patterns
                        link.contains("hubcloud", ignoreCase = true) ||
                                link.contains("hubcdn", ignoreCase = true) ||
                                link.contains("gamester", ignoreCase = true) ||
                                link.contains("gamerxyt", ignoreCase = true) -> {
                            Log.d(TAG, "Using HubCloud extractor")
                            HubCloud().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // FilePress patterns
                        link.contains("filepress", ignoreCase = true) ||
                                link.contains("filebee", ignoreCase = true) -> {
                            Log.d(TAG, "Using FilePress extractor")
                            FilePressExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // StreamWish patterns (incluiding embedwish)
                        link.contains("streamwish", ignoreCase = true) ||
                                link.contains("embedwish", ignoreCase = true) ||
                                link.contains("wishembed", ignoreCase = true) ||
                                link.contains("swhoi", ignoreCase = true) ||
                                link.contains("wishfast", ignoreCase = true) ||
                                link.contains("sfastwish", ignoreCase = true) ||
                                link.contains("awish", ignoreCase = true) ||
                                link.contains("dwish", ignoreCase = true) ||
                                link.contains("streamvid", ignoreCase = true) -> {
                            Log.d(TAG, "Using StreamWish extractor")
                            StreamWishExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // DoodStream patterns
                        link.contains("dood", ignoreCase = true) ||
                                link.contains("doodstream", ignoreCase = true) ||
                                link.contains("d0o0d", ignoreCase = true) ||
                                link.contains("d000d", ignoreCase = true) ||
                                link.contains("ds2play", ignoreCase = true) ||
                                link.contains("do0od", ignoreCase = true) -> {
                            Log.d(TAG, "Using DoodStream extractor")
                            DoodLaExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        else -> {
                            Log.d(TAG, "Using generic extractor for: $link")
                            Log.d("Phisher", "No local extractor found for:")
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