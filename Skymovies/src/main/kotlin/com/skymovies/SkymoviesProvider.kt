package com.skymovies

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

class SkymoviesProvider : MainAPI() {
    companion object {
        private const val TAG = "SkymoviesProvider"

        private val SERIES_DETECTION_REGEX = Regex(
            """(?i)(\bSeason\s*\d*|\bS0?\d+(?:E\d+)?\b(?!\s*K)|\bEpisode|\bEP[-\s]?\d+|\bComplete\b|\bAll\s*Episodes|\bWEB[-\s]?Series|\bSerial\b)"""
        )

        private val QUALITY_NUMBERS = setOf(360, 480, 540, 720, 1080, 2160)

        private val EPISODE_NUMBER_REGEX = Regex(
            """(?i)(?:Episodes?|EPiSODES?|EP)\s*[-.:#]*\s*(\d{1,4})(?!\s*p|\d+p)"""
        )

        private val SEASON_EPISODE_REGEX = Regex(
            """(?i)(?:S\d+\s*)?E(?:PISODE|P|pisode)?\s*[-.:#]*\s*(\d{1,3})(?:\s*[-~T]\s*E?(\d{1,3}))?(?!\s*p|\d+p)"""
        )

        private val QUALITY_REGEX = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)

        private val FILE_SIZE_REGEX = Regex(
            """(\d+(?:\.\d+)?)\s*(GB|MB)""", RegexOption.IGNORE_CASE
        )

        private val YEAR_REGEX = Regex("""\((\d{4})\)""")

        private val VALID_HOSTS = listOf(
            "howblogs", "linkstaker", "hubdrive", "hubcloud", "hubcdn", "gdflix", "hblinks", "pixeldrain", "gofile"
        )
    }

    override var mainUrl: String = "https://skymovieshd.ceo"

    init {
        runBlocking {
            try {
                withTimeoutOrNull(5_000L) {
                    val response = app.get(
                        "https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json"
                    )
                    val json = response.text
                    val jsonObject = JSONObject(json)
                    val urlString = jsonObject.optString("skymovies")
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

    override var name = "Skymovies"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime
    )

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/Bollywood-Movies.html" to "Bollywood",
        "category/South-Indian-Hindi-Dubbed-Movies.html" to "South Hindi Dubbed",
        "category/Hollywood-Hindi-Dubbed-Movies.html" to "Hollywood Hindi Dubbed",
        "category/All-Web-Series.html" to "Web Series"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            if (request.data.isBlank()) mainUrl else "$mainUrl/${request.data}"
        } else {
            val baseCat = request.data.removeSuffix(".html")
            "$mainUrl/$baseCat/$page.html"
        }

        Log.d(TAG, "Loading main page: $url")
        val document = app.get(url, headers = headers).document

        val anchors = document.select("a[href*='movie/'], a[href^='movie/']").toList()
        val home = anchors.toGroupedSearchResults()

        Log.d(TAG, "Found ${home.size} grouped items")
        return newHomePageResponse(request.name, home)
    }

    private suspend fun List<Element>.toGroupedSearchResults(): List<SearchResponse> {
        val groupedMap = mutableMapOf<String, MutableList<String>>()
        val seriesFlags = mutableMapOf<String, Boolean>()

        forEach { element ->
            val href = element.attr("href")
            if (href.isBlank() || href.contains("/category/") || href.contains("index.php")) return@forEach

            val fixedUrl = fixUrl(href)
            val titleText = element.text().ifBlank {
                element.selectFirst("img")?.attr("alt") ?: element.selectFirst("img")?.attr("title") ?: ""
            }.trim()

            if (titleText.isBlank()) return@forEach
            val title = cleanTitle(titleText)
            if (title.isBlank()) return@forEach

            val isSeries = SERIES_DETECTION_REGEX.containsMatchIn(titleText)

            val list = groupedMap.getOrPut(title) { mutableListOf() }
            list.add(fixedUrl)
            if (isSeries) {
                seriesFlags[title] = true
            }
        }

        // Fetch poster from detail page for each group (parallel, 4s timeout per request)
        return groupedMap.entries.toList().amap { (title, urls) ->
            val combinedUrl = urls.distinct().joinToString("|||")
            val isSeries = seriesFlags[title] ?: false

            var posterUrl: String? = null
            try {
                withTimeoutOrNull(4000L) {
                    val doc = app.get(urls.first(), headers = headers, timeout = 4000L).document
                    val posterMeta = doc.selectFirst("meta[property=og:image]")?.attr("content")
                    val posterImgElement = doc.selectFirst(
                        "div.movielist img, img[src*='media-amazon'], img[src*='bmscdn'], img[src*='tmdb'], img[src*='poster']"
                    )
                    val posterImgSrc = posterImgElement?.attr("src") ?: ""
                    val posterImg: String? = if (
                        posterImgSrc.startsWith("http") &&
                        !posterImgSrc.contains("/images/icon") &&
                        !posterImgSrc.contains("/images/arw") &&
                        !posterImgSrc.contains("logo")
                    ) posterImgSrc else null

                    val foundPoster = posterMeta ?: posterImg
                    if (!foundPoster.isNullOrBlank() && foundPoster.startsWith("http")) {
                        posterUrl = foundPoster
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching poster for $title: ${e.message}")
            }

            if (isSeries) {
                newTvSeriesSearchResponse(title, combinedUrl, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, combinedUrl, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "Searching for: $query")
        val results = mutableListOf<SearchResponse>()

        try {
            val searchUrl = "$mainUrl/search.php?search=${query.replace(" ", "+")}&cat=All"
            Log.d(TAG, "Search URL: $searchUrl")

            val document = app.get(searchUrl, headers = headers).document

            val anchors = document.select("a[href*='movie/'], a[href^='movie/']").toList()
            results.addAll(anchors.toGroupedSearchResults())

            if (results.isEmpty()) {
                Log.d(TAG, "Search page returned no results, trying fallback search")
                val searchTerms = query.lowercase().split(" ").filter { it.length > 2 }

                val doc = app.get(mainUrl, headers = headers).document
                val fallbackAnchors = doc.select("a[href*='movie/'], a[href^='movie/']").toList()
                val fallbackResults = fallbackAnchors.toGroupedSearchResults()

                fallbackResults.forEach { res ->
                    val title = res.name.lowercase()
                    val matches = searchTerms.any { term -> title.contains(term) }
                    if (matches && results.none { it.url == res.url }) {
                        results.add(res)
                    }
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
        val urls = url.split("|||")
        val firstUrl = urls.first()
        val document = app.get(firstUrl, headers = headers).document

        val rawTitle = document.selectFirst("h1, h2, h3, .post-title")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore(" Full Movie")?.trim()
            ?: return null
        val title = cleanTitle(rawTitle)

        var poster: String? = null
        for (u in urls) {
            try {
                val doc = if (u == firstUrl) document else app.get(u, headers = headers).document
                val posterMeta = doc.selectFirst("meta[property=og:image]")?.attr("content")
                // Target the dedicated movie poster div specifically
                val posterImgElement = doc.selectFirst(
                    "div.movielist img, img[src*='media-amazon'], img[src*='bmscdn'], img[src*='tmdb'], img[src*='poster']"
                )
                val posterImgSrc = posterImgElement?.attr("src") ?: ""
                val posterImg: String? = if (posterImgSrc.startsWith("http") &&
                    !posterImgSrc.contains("/images/icon") &&
                    !posterImgSrc.contains("/images/arw") &&
                    !posterImgSrc.contains("logo")
                ) posterImgSrc else null
                val foundPoster = posterMeta ?: posterImg
                if (!foundPoster.isNullOrBlank() && foundPoster.startsWith("http")) {
                    poster = foundPoster
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching poster from sub-url: ${e.message}")
            }
        }

        val descMeta = document.selectFirst("meta[name=description]")?.attr("content")
        val description: String? = descMeta

        val year = YEAR_REGEX.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val tags = document.select("a[href*='search.php']").map { it.text() }.filter { it.isNotBlank() }

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

    private fun extractEpisodesFromText(text: String): Set<Int> {
        val result = mutableSetOf<Int>()
        if (text.isBlank()) return result

        SEASON_EPISODE_REGEX.findAll(text).forEach { match ->
            val startEp = match.groupValues[1].toIntOrNull()
            val endEp = match.groupValues[2].toIntOrNull()
            if (startEp != null && startEp > 0 && startEp <= 500 && !QUALITY_NUMBERS.contains(startEp)) {
                result.add(startEp)
                if (endEp != null && endEp > startEp && endEp <= 500 && (endEp - startEp) <= 30) {
                    for (ep in (startEp + 1)..endEp) {
                        if (!QUALITY_NUMBERS.contains(ep)) {
                            result.add(ep)
                        }
                    }
                }
            }
        }
        return result
    }

    private suspend fun detectEpisodesFromHtml(document: Document, pageUrl: String): List<com.lagradost.cloudstream3.Episode> {
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val detectedEpisodes = mutableSetOf<Int>()

        Log.d(TAG, "=== detectEpisodesFromHtml START ===")

        val urls = pageUrl.split("|||")
        for (u in urls) {
            try {
                // 1. Extract from URL itself
                detectedEpisodes.addAll(extractEpisodesFromText(u))

                val doc = if (u == urls.first()) document else app.get(u, headers = headers).document

                // 2. Extract from page title & heading tags
                val pageHeading = doc.select("title, h1, h2, h3, .post-title, div.Robiul, .Mati").text()
                detectedEpisodes.addAll(extractEpisodesFromText(pageHeading))

                // 3. Extract from h-tags
                doc.select("h3, h4, h5, h6").forEach { element ->
                    val text = element.text().trim()
                    if (!QUALITY_REGEX.containsMatchIn(text) || text.contains("Episode", true) || text.contains("Ep", true) || text.contains("S0", true) || text.contains("S1", true)) {
                        detectedEpisodes.addAll(extractEpisodesFromText(text))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing episodes for sub-url: ${e.message}")
            }
        }

        detectedEpisodes.removeAll(QUALITY_NUMBERS)

        // 4. Scan aggregator pages if no episodes detected
        if (detectedEpisodes.isEmpty()) {
            val aggregatorUrls = mutableListOf<String>()
            for (u in urls) {
                try {
                    val doc = if (u == urls.first()) document else app.get(u, headers = headers).document
                    aggregatorUrls.addAll(doc.select("a[href*='howblogs'], a[href*='linkstaker']").map { it.attr("href") })
                } catch (_: Exception) {}
            }
            val distinctAggregators = aggregatorUrls.distinct()
            for (aggUrl in distinctAggregators.take(5)) {
                try {
                    val aggDoc = app.get(aggUrl).document
                    aggDoc.select("h2, h3, h4, h5, a, p").forEach { elem ->
                        val text = elem.text().trim()
                        detectedEpisodes.addAll(extractEpisodesFromText(text))
                    }
                    detectedEpisodes.removeAll(QUALITY_NUMBERS)
                    if (detectedEpisodes.isNotEmpty()) break
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching aggregator for episode detection: ${e.message}")
                }
            }
        }

        // 5. Fallback for grouped episode sub-URLs (e.g., 4 episode pages grouped under one series title)
        if (detectedEpisodes.isEmpty() && urls.size > 1) {
            for (i in urls.indices) {
                detectedEpisodes.add(i + 1)
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

    private suspend fun extractDownloadLinks(document: Document, pageUrl: String = ""): List<DownloadLink> {
        val downloadLinks = mutableListOf<DownloadLink>()
        val seenUrls = mutableSetOf<String>()
        var currentEpisode: Int? = extractEpisodesFromText(pageUrl).firstOrNull()
            ?: extractEpisodesFromText(document.selectFirst("title, h1, h2, h3, .post-title, div.Robiul, .Mati")?.text() ?: "").firstOrNull()

        val relevantSelector = "h3, h4, h5, a[href*='howblogs'], a[href*='linkstaker'], a[href*='hubcloud'], a[href*='gdflix'], a[href*='hubcdn']"

        document.select(relevantSelector).forEach { element ->
            val tagName = element.tagName().uppercase()

            if (tagName in listOf("H3", "H4", "H5")) {
                val headerText = element.text().trim()
                val epNum = extractEpisodesFromText(headerText).firstOrNull()
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

                val linkEpisode = extractEpisodesFromText(linkText).firstOrNull() ?: currentEpisode

                val episodeContext = when {
                    linkEpisode != null -> "EPiSODE $linkEpisode | $linkText"
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

        val expandedLinks = mutableListOf<DownloadLink>()
        for (link in downloadLinks) {
            if (link.url.contains("howblogs", ignoreCase = true) || link.url.contains("linkstaker", ignoreCase = true)) {
                try {
                    val hbDoc = app.get(link.url).document
                    var hbEpisode: Int? = link.episodeNum

                    hbDoc.select("h3, h4, h5, a[href*='hubcloud'], a[href*='gdflix'], a[href*='hubcdn'], a[href*='pixeldrain'], a[href*='gofile.io']").forEach { elem ->
                        val tag = elem.tagName().uppercase()
                        if (tag in listOf("H3", "H4", "H5")) {
                            val epMatch = EPISODE_NUMBER_REGEX.find(elem.text())
                            val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull()
                            if (epNum != null && epNum > 0 && epNum < 500) {
                                hbEpisode = epNum
                            }
                        } else if (tag == "A") {
                            val abs = elem.absUrl("href")
                            val innerUrl = if (abs.isNotBlank()) abs else elem.attr("href")
                            val innerText = elem.text().trim()
                            if (innerUrl.isNotBlank() && !shouldBlockUrl(innerUrl) && !innerText.contains("Zip", true)) {
                                expandedLinks.add(
                                    DownloadLink(
                                        url = innerUrl,
                                        quality = extractQuality(innerText),
                                        sizeMB = parseFileSize(innerText),
                                        originalText = innerText,
                                        episodeNum = hbEpisode
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error expanding howblogs: ${e.message}")
                }
            } else {
                expandedLinks.add(link)
            }
        }

        return (if (expandedLinks.isNotEmpty()) expandedLinks else downloadLinks).sortedWith(
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
            val lastPartIsEpisodeNum = parts.last().toIntOrNull() != null
            val episodeNum = if (lastPartIsEpisodeNum) parts.last().toIntOrNull() else null
            val urls = if (lastPartIsEpisodeNum) parts.dropLast(1) else parts

            val allLinks = mutableListOf<DownloadLink>()
            for (u in urls) {
                try {
                    val document = app.get(u, headers = headers).document
                    allLinks.addAll(extractDownloadLinks(document, u))
                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting download links from sub-url: ${e.message}")
                }
            }

            val targetLinks = when {
                episodeNum == null -> allLinks
                episodeNum == 0 -> allLinks.filter { it.episodeNum == null }.ifEmpty { allLinks }
                else -> {
                    val byField = allLinks.filter { it.episodeNum == episodeNum }
                    if (byField.isNotEmpty()) byField
                    else {
                        allLinks.filter { link ->
                            val eps = extractEpisodesFromText(link.originalText)
                            eps.contains(episodeNum)
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
                            link.contains("howblogs", true) || link.contains("linkstaker", true) ->
                                Howblogs().getUrl(link, mainUrl, subtitleCallback, callback)

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
            .replace(Regex("""(?i)\bS\d{1,2}\s*E\d{1,3}(?:[T\-E]\d{1,3})?\b"""), "")
            .replace(Regex("""(?i)\bS\d{1,2}\b"""), "")
            .replace(Regex("""(?i)\bE\d{1,3}(?:[T\-E]\d{1,3})?\b"""), "")
            .replace(Regex("""(?i)\bE(?:PISODE|P|pisode)?\s*[-.:#]*\s*\d{1,3}(?:\s*[-~T]\s*E?\d{1,3})?\b"""), "")
            .replace(Regex("""(?i)\b(WEB-?DL|BluRay|HDRip|WEBRip|HDTV|DVDRip|BRRip|UNCUT|UNRATED|PROPER)\b"""), "")
            .replace(Regex("""(?i)\b(4K|UHD|1080p|720p|480p|360p|2160p|IMAX|HDTC)\b"""), "")
            .replace(Regex("""(?i)\b(HEVC|x264|x265|10Bit|H\.?264|H\.?265|AAC|DD5?\.?1?)\b"""), "")
            .replace(Regex("""(?i)\b(Download|Free|Full|Movie|HD|Watch)\b"""), "")
            .replace(Regex("""(?i)\b(Hindi|English|Dual\s*Audio|ESubs?|Multi\s*Audio|Multi|Bengali|Punjabi|Tamil|Telugu|Malayalam|Kannada|Marathi|Gujarati|Bhojpuri|Urdu|Pakistani|Bangladeshi|Korean|Chinese|China|WWE|TV\s*Show|Hot|Short\s*Film|Web\s*Series|Series|Serial|Complete|All\s*Episodes|Ratri|Fliz|Dzyreplay|LubeSeries|9RedMovies|ORG)\b"""), "")
            .replace(Regex("""[&+]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
