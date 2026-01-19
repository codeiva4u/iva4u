package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
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
import java.text.Normalizer

class HDhub4uProvider : MainAPI() {
    companion object {
        private const val TAG = "HDhub4uProvider"
        
        // Regex patterns for title/content parsing
        private val SERIES_PATTERN = Regex("""(?i)(Season|S0\d|Episode|E0\d|Complete|Web\s*Series|All\s+Episodes)""")
        private val SEASON_PATTERN = Regex("""(?i)Season\s*(\d+)""")
        private val YEAR_PATTERN = Regex("""[\(\[]?(\d{4})[\)\]]?""")
        private val EPISODE_PATTERN = Regex("""(?:EPiSODE|Episode|EP|E)[.\s-]*(\d+)""", RegexOption.IGNORE_CASE)
        private val SIZE_PATTERN = Regex("""(\d+(?:\.\d+)?)\s*(GB|MB)""", RegexOption.IGNORE_CASE)
        
        // Title cleaning patterns
        private val CLEAN_YEAR = Regex("""(?i)\s*[\(\[]?\d{4}[\)\]]?""")
        private val CLEAN_BRACKETS = Regex("""(?i)\s*\[.*?]""")
        private val CLEAN_QUALITY = Regex("""(?i)\s*(480p|720p|1080p|2160p|4k|hdrip|webrip|bluray|web-dl|hdtv|hdcam).*""")
        private val CLEAN_LANG = Regex("""(?i)\s*(hindi|english|dual audio|esub|esubs).*""")
        private val CLEAN_DOWNLOAD = Regex("""(?i)\s*download\s*(free)?.*""")
        private val CLEAN_WHITESPACE = Regex("""\s+""")
        
        // Search quality patterns
        private val QUALITY_4K = Regex("""\b(4k|uhd|2160p)\b""", RegexOption.IGNORE_CASE)
        private val QUALITY_HDCAM = Regex("""\b(hdcam|hdtc)\b""", RegexOption.IGNORE_CASE)
        private val QUALITY_CAMRIP = Regex("""\b(camrip)\b""", RegexOption.IGNORE_CASE)
        private val QUALITY_CAM = Regex("""\b(cam)\b""", RegexOption.IGNORE_CASE)
        private val QUALITY_WEBRIP = Regex("""\b(webrip|webdl)\b""", RegexOption.IGNORE_CASE)
        private val QUALITY_BLURAY = Regex("""\b(bluray)\b""", RegexOption.IGNORE_CASE)
        private val QUALITY_1080 = Regex("""\b(1080p)\b""", RegexOption.IGNORE_CASE)
        private val QUALITY_720 = Regex("""\b(720p)\b""", RegexOption.IGNORE_CASE)
        private val QUALITY_DVD = Regex("""\b(dvd)\b""", RegexOption.IGNORE_CASE)
    }

    // Cached domain URL
    private var cachedMainUrl: String? = null
    private var urlsFetched = false
    
    override var mainUrl: String = "https://new2.hdhub4u.fo"

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
                jsonObject.optString("hdhub4u").takeIf { it.isNotBlank() }
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
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/bollywood-movies/" to "Bollywood",
        "category/hollywood-movies/" to "Hollywood",
        "category/hindi-dubbed/" to "Hindi Dubbed",
        "category/south-hindi-movies/" to "South Hindi Dubbed",
        "category/web-series/" to "Web Series",
    )
    
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Cookie" to "xla=s4t"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        fetchMainUrl()
        
        val url = if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}page/$page/"
        Log.d(TAG, "Loading main page: $url")
        
        val document = app.get(url, headers = headers, timeout = 20).document
        val home = document.select("li.thumb").mapNotNull { it.toSearchResult() }
        
        Log.d(TAG, "Found ${home.size} items")
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("figure a[href]")
            ?: selectFirst("figcaption a[href]")
            ?: selectFirst("a[href]")

        val href = linkElement?.attr("href") ?: return null
        if (href.isBlank() || href.contains("category") || href.contains("page/")) return null

        val fixedUrl = fixUrl(href)
        val titleText = selectFirst("figcaption p")?.text()
            ?: selectFirst("figcaption a")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: selectFirst("img")?.attr("title")
            ?: selectFirst("a")?.attr("title")
            ?: ""

        val title = cleanTitle(titleText)
        if (title.isBlank()) return null

        val posterUrl = selectFirst("figure img, img")?.let { img ->
            val src = img.attr("src").ifBlank {
                img.attr("data-src").ifBlank { img.attr("data-lazy-src") }
            }
            fixUrlNull(src)
        }

        val isSeries = SERIES_PATTERN.containsMatchIn(titleText)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, fixedUrl, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, fixedUrl, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "Searching for: $query")
        val results = mutableListOf<SearchResponse>()
        val searchTerms = query.lowercase().split(" ").filter { it.length > 2 }

        try {
            for (page in 1..3) {
                val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
                val document = app.get(url, headers = headers, timeout = 30).document

                document.select("li.thumb").forEach { element ->
                    val searchResult = element.toSearchResult()
                    if (searchResult != null) {
                        val title = searchResult.name.lowercase()
                        val matches = searchTerms.any { term -> title.contains(term) }
                        if (matches && results.none { it.url == searchResult.url }) {
                            results.add(searchResult)
                        }
                    }
                }
                if (results.size >= 20) break
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search error: ${e.message}")
        }

        Log.d(TAG, "Found ${results.size} search results")
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        fetchMainUrl()
        val doc = app.get(url, headers = headers, timeout = 20).document

        // Extract title
        val rawTitle = doc.selectFirst("h1.page-title span")?.text()
            ?: doc.selectFirst("h1.page-title")?.text()
            ?: doc.selectFirst("h2[data-ved]")?.text()
            ?: "Unknown"
        val title = cleanTitle(rawTitle)

        // Extract poster
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".page-body img.aligncenter")?.attr("src")

        // Extract description
        val description = doc.selectFirst(".page-body p:first-child")?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        // Extract year using regex
        val year = YEAR_PATTERN.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        // Extract IMDB URL
        val imdbUrl = doc.selectFirst("a[href*='imdb.com/title']")?.attr("href") ?: ""

        // Extract tags
        val tags = doc.select(".page-meta em, a[rel=tag]").eachText().distinct()

        // Determine if series
        val isSeries = SERIES_PATTERN.containsMatchIn(rawTitle)
        val seasonNumber = SEASON_PATTERN.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        // Extract download links using ExtractorPatterns
        val downloadLinks = mutableListOf<DownloadLink>()
        
        doc.select("a[href]").forEach { element ->
            val href = element.attr("href")
            val text = element.text().trim()
            
            if (ExtractorPatterns.isValidDownloadLink(href) && downloadLinks.none { it.url == href }) {
                val quality = ExtractorPatterns.extractQuality(text.ifBlank { href })
                val size = parseFileSize(text)
                downloadLinks.add(DownloadLink(href, quality, size, text))
            }
        }
        
        // Also extract from page HTML using regex
        val bodyHtml = doc.body().html()
        ExtractorPatterns.VALID_LINK.findAll(bodyHtml).forEach { match ->
            val linkUrl = match.value
            if (downloadLinks.none { it.url == linkUrl }) {
                downloadLinks.add(DownloadLink(linkUrl, ExtractorPatterns.extractQuality(linkUrl), 0.0, ""))
            }
        }

        Log.d(TAG, "Found ${downloadLinks.size} download links")

        // Sort links by quality and priority
        val sortedLinks = downloadLinks.sortedWith(
            compareByDescending<DownloadLink> {
                when (it.quality) {
                    1080 -> 100
                    2160 -> 90
                    720 -> 70
                    480 -> 50
                    else -> 30
                }
            }.thenByDescending { 
                ExtractorPatterns.getServerPriority(it.url) 
            }.thenBy { 
                if (it.sizeMB > 0) it.sizeMB else Double.MAX_VALUE 
            }
        )

        return if (isSeries) {
            val episodes = parseEpisodes(doc, sortedLinks, seasonNumber)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addImdbUrl(imdbUrl)
            }
        } else {
            val data = sortedLinks.joinToString(",") { it.url }
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addImdbUrl(imdbUrl)
            }
        }
    }

    private data class DownloadLink(
        val url: String,
        val quality: Int,
        val sizeMB: Double,
        val originalText: String
    )

    private fun parseEpisodes(
        doc: org.jsoup.nodes.Document,
        links: List<DownloadLink>,
        seasonNumber: Int?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // Group links by episode number
        val groupedByEpisode = links.groupBy { link ->
            EPISODE_PATTERN.find(link.originalText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }

        if (groupedByEpisode.size > 1 || (groupedByEpisode.size == 1 && groupedByEpisode.keys.first() != 0)) {
            groupedByEpisode.forEach { (epNum, epLinks) ->
                if (epNum > 0) {
                    val sortedLinks = epLinks.sortedByDescending { link ->
                        ExtractorPatterns.getServerPriority(link.url)
                    }
                    val data = sortedLinks.joinToString(",") { it.url }
                    episodes.add(newEpisode(data) {
                        this.name = "Episode $epNum"
                        this.season = seasonNumber
                        this.episode = epNum
                    })
                }
            }
        }

        // Fallback: use streaming links as episodes
        if (episodes.isEmpty() && links.isNotEmpty()) {
            val streamingLinks = links.filter {
                ExtractorPatterns.HUBSTREAM.containsMatchIn(it.url) || 
                ExtractorPatterns.HDSTREAM4U.containsMatchIn(it.url)
            }

            if (streamingLinks.size > 1) {
                streamingLinks.forEachIndexed { index, link ->
                    episodes.add(newEpisode(link.url) {
                        this.name = "Episode ${index + 1}"
                        this.season = seasonNumber
                        this.episode = index + 1
                    })
                }
            } else {
                val data = links.joinToString(",") { it.url }
                episodes.add(newEpisode(data) {
                    this.name = "Full Season"
                    this.season = seasonNumber
                    this.episode = 1
                })
            }
        }

        return episodes.sortedBy { it.episode }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linksList = data.removePrefix("[").removeSuffix("]")
            .replace("\"", "")
            .split(',', ' ')
            .map { it.trim() }
            .filter { it.startsWith("http") }

        Log.d(TAG, "Processing ${linksList.size} links")

        for (link in linksList) {
            try {
                val finalLink = if (ExtractorPatterns.hasRedirectId(link)) {
                    getRedirectLinks(link)
                } else link

                // Use ExtractorPatterns to match and call appropriate extractor
                when (ExtractorPatterns.matchExtractor(finalLink)) {
                    ExtractorType.HUBDRIVE -> 
                        Hubdrive().getUrl(finalLink, "", subtitleCallback, callback)
                    ExtractorType.HUBCLOUD -> 
                        HubCloud().getUrl(finalLink, "", subtitleCallback, callback)
                    ExtractorType.HUBCDN -> 
                        HUBCDN().getUrl(finalLink, "", subtitleCallback, callback)
                    ExtractorType.HDSTREAM4U -> 
                        HdStream4u().getUrl(finalLink, "", subtitleCallback, callback)
                    ExtractorType.HUBSTREAM -> 
                        Hubstream().getUrl(finalLink, "", subtitleCallback, callback)
                    ExtractorType.HBLINKS -> 
                        Hblinks().getUrl(finalLink, "", subtitleCallback, callback)
                    ExtractorType.PIXELDRAIN -> 
                        PixelDrainDev().getUrl(finalLink, "", subtitleCallback, callback)
                    ExtractorType.VIDSTACK -> 
                        VidStack().getUrl(finalLink, "", subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process $link: ${e.message}")
            }
        }
        return true
    }

    // Helper functions using companion object patterns
    private fun parseFileSize(text: String): Double {
        val match = SIZE_PATTERN.find(text) ?: return 0.0
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0.0
        val unit = match.groupValues[2].uppercase()
        return if (unit == "GB") value * 1024 else value
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(CLEAN_YEAR, "")
            .replace(CLEAN_BRACKETS, "")
            .replace(CLEAN_QUALITY, "")
            .replace(CLEAN_LANG, "")
            .replace(CLEAN_DOWNLOAD, "")
            .replace(CLEAN_WHITESPACE, " ")
            .trim()
    }

    private suspend fun getRedirectLinks(url: String): String {
        return try {
            if (ExtractorPatterns.hasRedirectId(url)) {
                val response = app.get(url, allowRedirects = false)
                response.headers["location"] ?: response.headers["Location"] ?: url
            } else url
        } catch (e: Exception) {
            Log.e(TAG, "Redirect error: ${e.message}")
            url
        }
    }

    fun getSearchQuality(check: String?): SearchQuality? {
        val s = check ?: return null
        val u = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        
        return when {
            QUALITY_4K.containsMatchIn(u) -> SearchQuality.FourK
            QUALITY_HDCAM.containsMatchIn(u) -> SearchQuality.HdCam
            QUALITY_CAMRIP.containsMatchIn(u) -> SearchQuality.CamRip
            QUALITY_CAM.containsMatchIn(u) -> SearchQuality.Cam
            QUALITY_WEBRIP.containsMatchIn(u) -> SearchQuality.WebRip
            QUALITY_BLURAY.containsMatchIn(u) -> SearchQuality.BlueRay
            QUALITY_1080.containsMatchIn(u) -> SearchQuality.HD
            QUALITY_720.containsMatchIn(u) -> SearchQuality.SD
            QUALITY_DVD.containsMatchIn(u) -> SearchQuality.DVD
            else -> null
        }
    }
}