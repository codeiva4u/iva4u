package com.movies4u

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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Movies4uProvider : MainAPI() {
    companion object {
        private const val TAG = "Movies4uProvider"

        private val SERIES_DETECTION_REGEX = Regex(
            """(?i)(\bSeason\s*\d*|\bS0?\d+(?:E\d+)?\b(?!\s*K)|\bEpisode|\bEP[-\s]?\d+|\bComplete\b|\bAll\s*Episodes|\bWEB[-\s]?Series)"""
        )

        private val EPISODE_NUMBER_REGEX = Regex(
            """(?i)(?:EPiSODE|EPISODE|Episode|EP|E|Ep)[-.\s]*(\d{1,3})(?!\d+p)"""
        )

        private val QUALITY_REGEX = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)

        private val FILE_SIZE_REGEX = Regex(
            """(\d+(?:\.\d+)?)\s*(GB|MB)""", RegexOption.IGNORE_CASE
        )

        private val YEAR_REGEX = Regex("""\((\d{4})\)""")

        private val VALID_HOSTS = listOf(
            "hubdrive", "hubcloud", "hubcdn", "gdflix", "m4ulinks", "hblinks", "pixeldrain", "gofile"
        )
    }

    override var mainUrl: String = "https://new1.movies4u.clinic"

    init {
        runBlocking {
            try {
                withTimeoutOrNull(5_000L) {
                    val response = app.get(
                        "https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json"
                    )
                    val json = response.text
                    val jsonObject = JSONObject(json)
                    val urlString = jsonObject.optString("movies4u")
                    if (urlString.isNotBlank()) {
                        mainUrl = urlString.substringBefore("?").trimEnd('/')
                        Log.d(TAG, "Fetched mainUrl: $urlString")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch mainUrl: ${e.message}")
            }
        }
    }

    override var name = "Movies4u"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime
    )

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/bollywood-movies/" to "Bollywood",
        "category/hollywood-movies/" to "Hollywood",
        "category/hindi-dubbed-movies/" to "Hindi Dubbed",
        "category/south-hindi-movies/" to "South Hindi Dubbed",
        "category/web-series/" to "Web Series"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
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
        val document = app.get(url, headers = headers).document

        val home = document.select("article, li.thumb, div.post-card, .poster-card, a[href]:has(img)").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        Log.d(TAG, "Found ${home.size} items")
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement: Element? = if (tagName() == "a" && hasAttr("href")) this
        else selectFirst("figure a[href], .entry-title a[href], h2 a[href], h3 a[href], a[href]")

        val href = linkElement?.attr("href") ?: return null
        if (href.isBlank() || href.contains("/category/") || href.contains("/page/") || href.contains("/tag/")) return null

        val fixedUrl = fixUrl(href)

        val titleText: String = selectFirst(".entry-title, h2, h3, figcaption p, figcaption a")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: selectFirst("img")?.attr("title")
            ?: linkElement.attr("title")

        if (titleText.isBlank()) return null
        val title: String = cleanTitle(titleText)
        if (title.isBlank()) return null

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

        val isSeries = SERIES_DETECTION_REGEX.containsMatchIn(titleText)

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

        try {
            val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
            Log.d(TAG, "Search URL: $searchUrl")

            val document = app.get(searchUrl, headers = headers).document

            document.select("article, li.thumb, div.post-card, .poster-card, a[href]:has(img)").forEach { card ->
                val searchResult = card.toSearchResult()
                if (searchResult != null && results.none { it.url == searchResult.url }) {
                    results.add(searchResult)
                }
            }

            if (results.isEmpty()) {
                Log.d(TAG, "Search page returned no results, trying fallback homepage search")
                val searchTerms = query.lowercase().split(" ").filter { it.length > 2 }

                for (page in 1..3) {
                    val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
                    val doc = app.get(url, headers = headers).document

                    doc.select("article, li.thumb, a[href]:has(img)").forEach { element ->
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search error: ${e.message}")
        }

        Log.d(TAG, "Found ${results.size} search results")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "Loading: $url")
        val document = app.get(url, headers = headers).document

        val rawTitle = document.selectFirst("h1.single-title, .entry-title, h1.post-title, h1")?.text()?.trim()
            ?: return null
        val title = cleanTitle(rawTitle)

        val posterMeta = document.selectFirst("meta[property=og:image]")?.attr("content")
        val posterImg = document.selectFirst(".entry-content img[src*='tmdb'], .entry-content img, .post-content img")?.attr("src")
        val poster: String? = posterMeta ?: posterImg

        val descMeta = document.selectFirst("meta[name=description]")?.attr("content")
        val descOg = document.selectFirst("meta[property=og:description]")?.attr("content")
        val description: String? = descMeta ?: descOg

        val year = YEAR_REGEX.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val tags = document.select(".entry-categories a, .post-categories a, .cat-links a, a[rel=tag]").map { it.text() }

        val isSeries = SERIES_DETECTION_REGEX.containsMatchIn(rawTitle)

        return if (isSeries) {
            val episodes = detectEpisodesFromHtml(document, url)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        val originalText: String,
        val episodeNum: Int? = null
    )

    private suspend fun detectEpisodesFromHtml(document: Document, pageUrl: String): List<com.lagradost.cloudstream3.Episode> {
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val detectedEpisodes = mutableSetOf<Int>()

        Log.d(TAG, "=== detectEpisodesFromHtml START ===")

        document.select("h3, h4, h5").forEach { element ->
            val text = element.text().trim()
            val match = EPISODE_NUMBER_REGEX.find(text)
            val epNum = match?.groupValues?.get(1)?.toIntOrNull()
            if (epNum != null && epNum > 0 && epNum < 500) {
                detectedEpisodes.add(epNum)
            }
        }

        document.select("a[href]").forEach { element ->
            val linkText = element.text().trim()
            if (linkText.matches(Regex("""(?i)^EP(?:i|I)?SODE\s*\d+$""")) ||
                linkText.matches(Regex("""(?i)^EP[-.\s]?\d+$"""))) {
                val match = EPISODE_NUMBER_REGEX.find(linkText)
                val epNum = match?.groupValues?.get(1)?.toIntOrNull()
                if (epNum != null && epNum > 0 && epNum < 500) {
                    detectedEpisodes.add(epNum)
                }
            }
        }

        // If no episodes detected from post HTML directly, inspect m4ulinks aggregator page if present
        if (detectedEpisodes.isEmpty()) {
            val m4uAggregatorUrl = document.selectFirst("a[href*='m4ulinks']")?.attr("href")
            if (!m4uAggregatorUrl.isNullOrBlank()) {
                try {
                    val m4uDoc = app.get(m4uAggregatorUrl).document
                    m4uDoc.select("h3, h4, h5").forEach { element ->
                        val text = element.text().trim()
                        val match = EPISODE_NUMBER_REGEX.find(text)
                        val epNum = match?.groupValues?.get(1)?.toIntOrNull()
                        if (epNum != null && epNum > 0 && epNum < 500) {
                            detectedEpisodes.add(epNum)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching m4ulinks aggregator for episode detection: ${e.message}")
                }
            }
        }

        if (detectedEpisodes.isNotEmpty()) {
            detectedEpisodes.sorted().forEach { episodeNum ->
                val data = "$pageUrl|||$episodeNum"
                episodes.add(
                    newEpisode(data) {
                        this.name = "Episode $episodeNum"
                        this.episode = episodeNum
                    }
                )
            }
        } else {
            val data = "$pageUrl|||0"
            episodes.add(
                newEpisode(data) {
                    this.name = "Full Season"
                    this.episode = 1
                }
            )
        }

        Log.d(TAG, "Total episodes: ${episodes.size}")
        return episodes.sortedBy { it.episode }
    }

    private fun extractQuality(text: String): Int {
        val match = QUALITY_REGEX.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: when {
            text.contains("4K", ignoreCase = true) -> 2160
            text.contains("2160", ignoreCase = true) -> 2160
            text.contains("1080", ignoreCase = true) -> 1080
            text.contains("720", ignoreCase = true) -> 720
            text.contains("480", ignoreCase = true) -> 480
            else -> 0
        }
    }

    private fun parseFileSize(text: String): Double {
        val match = FILE_SIZE_REGEX.find(text) ?: return 0.0
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0.0
        val unit = match.groupValues[2].uppercase()
        return if (unit == "GB") value * 1024 else value
    }

    private suspend fun extractDownloadLinks(document: Document): List<DownloadLink> {
        val downloadLinks = mutableListOf<DownloadLink>()
        val seenUrls = mutableSetOf<String>()
        var currentEpisode: Int? = null

        val relevantSelector = "h3, h4, h5, a[href*='m4ulinks'], a[href*='hubcloud'], a[href*='gdflix'], a[href*='hubcdn']"

        document.select(relevantSelector).forEach { element ->
            val tagName = element.tagName().uppercase()

            if (tagName in listOf("H3", "H4", "H5")) {
                val headerText = element.text().trim()
                val epMatch = EPISODE_NUMBER_REGEX.find(headerText)
                val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull()
                if (epNum != null && epNum > 0 && epNum < 500) {
                    currentEpisode = epNum
                }
                return@forEach
            }

            if (tagName == "A") {
                val url = element.attr("href")
                val linkText = element.text().trim()

                if (url.isBlank() || seenUrls.contains(url)) return@forEach
                if (shouldBlockUrl(url)) return@forEach

                if (linkText.contains("Zip", ignoreCase = true) ||
                    linkText.contains(".zip", ignoreCase = true) ||
                    url.endsWith(".zip", ignoreCase = true)) {
                    return@forEach
                }

                seenUrls.add(url)

                val linkEpMatch = EPISODE_NUMBER_REGEX.find(linkText)
                val linkEpisode = linkEpMatch?.groupValues?.get(1)?.toIntOrNull() ?: currentEpisode

                val episodeContext = when {
                    linkEpMatch != null -> "EPiSODE ${linkEpMatch.groupValues[1]} | $linkText"
                    currentEpisode != null && linkText.isNotBlank() -> "EPiSODE $currentEpisode | $linkText"
                    linkText.isNotBlank() -> linkText
                    else -> "Download"
                }

                downloadLinks.add(
                    DownloadLink(
                        url = url,
                        quality = extractQuality(episodeContext),
                        sizeMB = parseFileSize(episodeContext),
                        originalText = episodeContext,
                        episodeNum = linkEpisode
                    )
                )
            }
        }

        // If links contain m4ulinks aggregator pages, fetch m4ulinks page directly to expand inner download links
        val expandedLinks = mutableListOf<DownloadLink>()
        for (link in downloadLinks) {
            if (link.url.contains("m4ulinks", ignoreCase = true)) {
                try {
                    val m4uDoc = app.get(link.url).document
                    var m4uEpisode: Int? = link.episodeNum

                    m4uDoc.select("h3, h4, h5, a[href*='hubcloud'], a[href*='gdflix'], a[href*='hubcdn'], a[href*='pixeldrain']").forEach { elem ->
                        val tag = elem.tagName().uppercase()
                        if (tag in listOf("H3", "H4", "H5")) {
                            val epMatch = EPISODE_NUMBER_REGEX.find(elem.text())
                            val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull()
                            if (epNum != null && epNum > 0 && epNum < 500) {
                                m4uEpisode = epNum
                            }
                        } else if (tag == "A") {
                            val innerUrl = elem.attr("href")
                            val innerText = elem.text().trim()
                            if (innerUrl.isNotBlank() && !shouldBlockUrl(innerUrl) && !innerText.contains("Zip", true)) {
                                expandedLinks.add(
                                    DownloadLink(
                                        url = innerUrl,
                                        quality = extractQuality(innerText),
                                        sizeMB = parseFileSize(innerText),
                                        originalText = innerText,
                                        episodeNum = m4uEpisode
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error expanding m4ulinks: ${e.message}")
                }
            } else {
                expandedLinks.add(link)
            }
        }

        return (if (expandedLinks.isNotEmpty()) expandedLinks else downloadLinks).sortedWith(
            compareByDescending<DownloadLink> {
                when (it.quality) {
                    1080 -> 100
                    2160 -> 90
                    720 -> 70
                    480 -> 50
                    else -> 30
                }
            }.thenByDescending {
                val text = it.originalText.lowercase() + it.url.lowercase()
                when {
                    text.contains("hevc") || text.contains("x265") -> 150
                    text.contains("x264") -> 100
                    else -> 50
                }
            }.thenBy {
                if (it.sizeMB > 0) it.sizeMB else Double.MAX_VALUE
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "Loading links from: $data")

        try {
            val parts = data.split("|||")
            val pageUrl = parts[0]
            val episodeNum = if (parts.size > 1) parts[1].toIntOrNull() else null

            val document = app.get(pageUrl, headers = headers).document
            val allLinks = extractDownloadLinks(document)

            val targetLinks = when {
                episodeNum == null -> allLinks
                episodeNum == 0 -> allLinks.filter { it.episodeNum == null }.ifEmpty { allLinks }
                else -> {
                    val byField = allLinks.filter { it.episodeNum == episodeNum }
                    if (byField.isNotEmpty()) byField
                    else {
                        allLinks.filter { link ->
                            val epNum = EPISODE_NUMBER_REGEX.find(link.originalText)?.groupValues?.get(1)?.toIntOrNull()
                            epNum == episodeNum
                        }.ifEmpty { allLinks }
                    }
                }
            }

            val sortedLinks = targetLinks
                .filter { !shouldBlockUrl(it.url) }
                .sortedWith(
                    compareByDescending<DownloadLink> {
                        val text = it.originalText.lowercase()
                        val isX264 = text.contains("x264") || text.contains("h264") || text.contains("h.264")
                        val isHEVC = text.contains("hevc") || text.contains("x265") || text.contains("h265") || text.contains("h.265")
                        when {
                            isX264 && it.quality >= 1080 -> 30000
                            isX264 && it.quality >= 720 -> 20000
                            isHEVC && it.quality >= 1080 -> 10000
                            isHEVC && it.quality >= 720 -> 9000
                            it.quality >= 1080 -> 8000
                            it.quality >= 720 -> 7000
                            else -> 5000
                        }
                    }.thenBy {
                        if (it.sizeMB > 0) it.sizeMB else Double.MAX_VALUE
                    }.thenByDescending {
                        val serverName = it.originalText
                        when {
                            serverName.contains("Instant", true) -> 100
                            serverName.contains("Direct", true) -> 90
                            serverName.contains("10Gbps", true) -> 85
                            serverName.contains("FSL", true) -> 80
                            else -> 50
                        }
                    }
                )

            val linksToProcess = if (episodeNum != null) 1 else 3
            withTimeoutOrNull(10_000L) {
                sortedLinks.take(linksToProcess).amap { downloadLink ->
                    try {
                        val link = downloadLink.url
                        Log.d(TAG, "Extracting: $link")

                        when {
                            link.contains("m4ulinks", true) ->
                                M4uLinks().getUrl(link, mainUrl, subtitleCallback, callback)

                            link.contains("hubcloud", true) ->
                                HubCloud().getUrl(link, mainUrl, subtitleCallback, callback)

                            link.contains("gdflix", true) || link.contains("gdlink", true) ->
                                GDFlix().getUrl(link, mainUrl, subtitleCallback, callback)

                            link.contains("hubcdn", true) ->
                                HUBCDN().getUrl(link, mainUrl, subtitleCallback, callback)

                            link.contains("gofile.io", true) || link.contains("pixeldrain", true) ->
                                com.lagradost.cloudstream3.utils.loadExtractor(link, mainUrl, subtitleCallback, callback)

                            else -> {
                                com.lagradost.cloudstream3.utils.loadExtractor(link, mainUrl, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error extracting ${downloadLink.url}: ${e.message}")
                    }
                }
            } ?: Log.w(TAG, "Timeout reached (10s)")
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadLinks: ${e.message}")
        }

        return true
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""\(\d{4}\)"""), "")
            .replace(Regex("""\[.*?]"""), "")
            .replace(Regex("""\|.*$"""), "")
            .replace(Regex("""(?i)(WEB-?DL|BluRay|HDRip|WEBRip|HDTV|DVDRip|BRRip)"""), "")
            .replace(Regex("""(?i)(4K|UHD|1080p|720p|480p|360p|2160p)"""), "")
            .replace(Regex("""(?i)(HEVC|x264|x265|10Bit|H\.?264|H\.?265|AAC|DD5?\.?1?)"""), "")
            .replace(Regex("""(?i)(Download|Free|Full|Movie|HD|Watch)"""), "")
            .replace(Regex("""(?i)(Hindi|English|Dual\s*Audio|ESub|Multi)"""), "")
            .replace(Regex("""[&+]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
