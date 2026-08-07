package com.moviesdrive

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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder

private val domainMutex = Mutex()

class MoviesDriveProvider : MainAPI() {

    companion object {
        private const val TAG = "MoviesDriveProvider"

        private val SERIES_DETECTION_REGEX = Regex(
            """(?i)(\bSeason\s*\d*|\bS0?\d+(?:E\d+)?\b(?!\s*K)|\bEpisode|\bEP[-\s]?\d+|\bComplete\b|\bAll\s*Episodes|\bEP\s*Added\b)"""
        )

        private val QUALITY_REGEX = Regex("""\b(\d{3,4})[pP]\b""")

        private val FILE_SIZE_REGEX = Regex(
            """(\d+(?:\.\d+)?)\s*(GB|MB)""", RegexOption.IGNORE_CASE
        )

        private val YEAR_REGEX = Regex("""\((\d{4})\)""")

        private val QUALITY_NUMBERS = setOf(360, 480, 540, 720, 1080, 2160)
    }

    override var mainUrl: String = "https://new1.moviesdrive.christmas"

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http")) return url
        val cleanUrl = url.trimStart('/')
        return "$mainUrl/$cleanUrl"
    }

    private fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val fullUrl = if (url.startsWith("//")) "https:$url" else url
        return fixUrl(fullUrl)
    }

    override var name = "MoviesDrive"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime
    )

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/bollywood/" to "Bollywood",
        "category/hollywood/" to "Hollywood",
        "category/hindi-dubbed/" to "Hindi Dubbed",
        "category/south/" to "South Hindi Dubbed",
        "category/web/" to "Web Series"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    )

    private var domainFetched = false

    private suspend fun checkAndFetchDomain() {
        if (domainFetched) return
        domainMutex.withLock {
            if (domainFetched) return
            try {
                withTimeoutOrNull(2_000L) {
                    val response = app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json")
                    val json = response.text
                    val jsonObject = JSONObject(json)
                    val urlString = jsonObject.optString("moviesdrive")
                    if (urlString.isNotBlank()) {
                        mainUrl = urlString.substringBefore("?").trimEnd('/')
                        Log.d(TAG, "Fetched domain from GitHub: $mainUrl")
                    }
                    domainFetched = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching domain from GitHub: ${e.message}")
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        checkAndFetchDomain()

        val url = if (page == 1) {
            if (request.data.isBlank()) "$mainUrl/" else "$mainUrl/${request.data}"
        } else {
            if (request.data.isBlank()) "$mainUrl/page/$page/" else "$mainUrl/${request.data}page/$page/"
        }

        Log.d(TAG, "Loading main page: $url")
        val document = app.get(url, headers = headers).document

        val cards = document.select("div.poster-card, article, div.movies-grid > a[href], div.post-item, li.post-item").ifEmpty {
            document.select("a[href*='moviesdrive']")
        }

        val home = cards.mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        Log.d(TAG, "Found ${home.size} items")
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement: Element? = if (tagName() == "a" && hasAttr("href")) this
        else selectFirst("a[href]")

        val href = linkElement?.attr("href") ?: return null
        if (href.isBlank() ||
            href == "/" ||
            href == mainUrl ||
            href == "$mainUrl/" ||
            href.contains("/category/") ||
            href.contains("/page/") ||
            href.contains("/tag/") ||
            href.contains("#")) return null

        val fixedUrl = fixUrl(href)

        val titleText: String = selectFirst("h2.post-title, h2.title, .poster-title, .entry-title, h2, h3, figcaption p, figcaption a, .title, .post-title")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: selectFirst("img")?.attr("title")
            ?: linkElement.attr("title")

        if (titleText.isBlank() || titleText.equals("logo", ignoreCase = true) || titleText.equals("menu", ignoreCase = true)) return null
        val title: String = cleanTitle(titleText)
        if (title.isBlank() || title.equals("logo", ignoreCase = true) || title.equals("home", ignoreCase = true) || title.length < 2) return null

        val imgElement = selectFirst("img")
        val posterUrl: String? = if (imgElement != null) {
            val dataSrcAttr = imgElement.attr("data-src")
            val lazyAttr = imgElement.attr("data-lazy-src")
            val srcAttr = imgElement.attr("src")
            val srcsetAttr = imgElement.attr("srcset")
            
            // Avoid extracting blank/dummy placeholders
            val src = when {
                dataSrcAttr.isNotBlank() && !dataSrcAttr.startsWith("data:") && !dataSrcAttr.contains("blank") && !dataSrcAttr.contains("default-poster") -> dataSrcAttr
                lazyAttr.isNotBlank() && !lazyAttr.startsWith("data:") && !lazyAttr.contains("blank") && !lazyAttr.contains("default-poster") -> lazyAttr
                srcAttr.isNotBlank() && !srcAttr.startsWith("data:") && !srcAttr.contains("blank") && !srcAttr.contains("default-poster") -> srcAttr
                srcsetAttr.isNotBlank() -> srcsetAttr.substringBefore(" ").substringBefore(",")
                else -> ""
            }
            fixUrlNull(src)
        } else null

        val isSeries = SERIES_DETECTION_REGEX.containsMatchIn(titleText)
        val finalTitle = title.replace("Download", "", true).trim()

        return if (isSeries) {
            newTvSeriesSearchResponse(finalTitle, fixedUrl, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(finalTitle, fixedUrl, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        checkAndFetchDomain()
        Log.d(TAG, "Searching for: $query")
        val results = mutableListOf<SearchResponse>()
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")

        // 1. Primary Search API: /search.php?q=query&page=1
        try {
            val searchApiUrl = "$mainUrl/search.php?q=$encodedQuery&page=1"
            val json = app.get(searchApiUrl, headers = headers).text

            val jsonObj = JSONObject(json)
            val hits = jsonObj.optJSONArray("hits")
            if (hits != null && hits.length() > 0) {
                for (i in 0 until hits.length()) {
                    val doc = hits.getJSONObject(i).optJSONObject("document") ?: continue
                    val rawTitle = doc.optString("post_title").trim()
                    val permalink = doc.optString("permalink").trim()
                    var thumb = doc.optString("post_thumbnail").trim()
                    
                    if (thumb.contains("default-poster", true)) thumb = ""

                    if (rawTitle.isBlank() || permalink.isBlank()) continue

                    val fixedUrl = if (permalink.startsWith("http")) permalink else fixUrl(permalink)
                    val cleanedTitle = cleanTitle(rawTitle)
                    if (cleanedTitle.isBlank()) continue

                    val posterUrl = fixUrlNull(thumb)
                    val isSeries = SERIES_DETECTION_REGEX.containsMatchIn(rawTitle)
                    val finalTitle = cleanedTitle.replace("Download", "", true).trim()

                    val result = if (isSeries) {
                        newTvSeriesSearchResponse(finalTitle, fixedUrl, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                        }
                    } else {
                        newMovieSearchResponse(finalTitle, fixedUrl, TvType.Movie) {
                            this.posterUrl = posterUrl
                        }
                    }
                    results.add(result)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Primary search.php error: ${e.message}")
        }

        if (results.isNotEmpty()) return results

        // 2. Fallback Search API: /wp-json/wp/v2/posts?search=query
        try {
            val wpApiUrl = "$mainUrl/wp-json/wp/v2/posts?search=$encodedQuery"
            val jsonText = app.get(wpApiUrl, headers = headers).text
            val jsonArray = org.json.JSONArray(jsonText)
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val titleObj = item.optJSONObject("title")
                val rawTitle = titleObj?.optString("rendered")?.trim() ?: ""
                val permalink = item.optString("link").trim()
                if (rawTitle.isBlank() || permalink.isBlank()) continue

                val cleanedTitle = cleanTitle(rawTitle)
                val finalTitle = cleanedTitle.replace("Download", "", true).trim()
                val isSeries = SERIES_DETECTION_REGEX.containsMatchIn(rawTitle)
                val result = if (isSeries) {
                    newTvSeriesSearchResponse(finalTitle, permalink, TvType.TvSeries)
                } else {
                    newMovieSearchResponse(finalTitle, permalink, TvType.Movie)
                }
                results.add(result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback WP REST search error: ${e.message}")
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        checkAndFetchDomain()
        Log.d(TAG, "Loading: $url")

        val document = app.get(url, headers = headers).document

        val rawTitle = document.selectFirst("h1.single-title, .entry-title, h1.post-title, h1, h2.title, h2.post-title")?.text()?.trim()
            ?: return null
        val title = cleanTitle(rawTitle).replace("Download", "", true).trim()

        val posterMeta = document.selectFirst("meta[property=og:image]")?.attr("content")
        val posterImg = document.selectFirst(".entry-content img, .post-content img")?.attr("src")
        var pUrl = posterMeta ?: posterImg
        if (pUrl?.contains("default-poster", true) == true) pUrl = null
        val poster: String? = fixUrlNull(pUrl)

        val descMeta = document.selectFirst("meta[name=description]")?.attr("content")
        val descOg = document.selectFirst("meta[property=og:description]")?.attr("content")
        val pDesc = document.selectFirst("div.post-content p, div.entry-content p")?.text()
        val description: String? = pDesc?.ifBlank { null } ?: descMeta ?: descOg

        val year = YEAR_REGEX.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val tags = document.select(".entry-categories a, .post-categories a, .cat-links a, a[rel=tag], .category-tag").map { it.text() }
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

    private fun extractSeasonFromText(text: String): Int? {
        if (text.isBlank()) return null
        val SEASON_REGEX = Regex("""(?i)\b(?:S|Season)\s*[-.:#]*\s*(\d{1,2})\b""")
        val match = SEASON_REGEX.find(text)
        val seasonNum = match?.groupValues?.get(1)?.toIntOrNull()
        return if (seasonNum != null && seasonNum in 1..50) seasonNum else null
    }

    private fun extractEpisodesFromText(text: String): Set<Int> {
        val result = mutableSetOf<Int>()
        if (text.isBlank()) return result

        val RANGE_REGEX = Regex("""(?i)\b(?:S\d+\s*)?(?:EP?|Episodes?)\s*[-.:#]*\s*(\d{1,3})\s*(?:TO|[-–~T])\s*(?:EP?)?\s*[-.:#]*\s*(\d{1,3})(?!\s*p|\d+p)""")
        val SINGLE_REGEX = Regex("""(?i)\b(?:S\d+\s*)?(?:EP|Episodes?|E)\s*[-.:#]*\s*(\d{1,3})(?!\s*p|\d+p)""")
        val EP_REGEX = Regex("""(?i)\b(\d{1,3})(?:st|nd|rd|th)?\s*Episode\b""")

        RANGE_REGEX.findAll(text).forEach { match ->
            val startEp = match.groupValues[1].toIntOrNull()
            val endEp = match.groupValues[2].toIntOrNull()
            if (startEp != null && endEp != null && startEp > 0 && endEp >= startEp && (endEp - startEp) <= 100) {
                for (ep in startEp..endEp) {
                    if (!QUALITY_NUMBERS.contains(ep)) {
                        result.add(ep)
                    }
                }
            }
        }

        SINGLE_REGEX.findAll(text).forEach { match ->
            val epNum = match.groupValues[1].toIntOrNull()
            if (epNum != null && epNum > 0 && epNum <= 1000 && !QUALITY_NUMBERS.contains(epNum)) {
                result.add(epNum)
            }
        }
        
        EP_REGEX.findAll(text).forEach { match ->
            val epNum = match.groupValues[1].toIntOrNull()
            if (epNum != null && epNum > 0 && epNum <= 1000 && !QUALITY_NUMBERS.contains(epNum)) {
                result.add(epNum)
            }
        }

        return result
    }

    private suspend fun detectEpisodesFromHtml(document: Document, pageUrl: String): List<com.lagradost.cloudstream3.Episode> {
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val episodesBySeason = mutableMapOf<Int, MutableSet<Int>>()

        Log.d(TAG, "=== detectEpisodesFromHtml START ===")

        val cleanDoc = document.clone()
        cleanDoc.select("aside, footer, header, nav, #sidebar, .ct-related-posts-items, .related-posts, #comments, #respond, .wp-block-latest-posts, .ct-widget, .widget, .ct-share-box").remove()

        val pageTitle = cleanDoc.selectFirst("title, h1.single-title, .entry-title, h1.post-title, h1, h2.title, h2.post-title")?.text() ?: ""
        val mainSeason = extractSeasonFromText(pageTitle) ?: 1

        fun parseDocForEpisodes(doc: Document, defaultSeason: Int = 1) {
            var currentSeason = extractSeasonFromText(doc.selectFirst("title, h1, h2, h3, .post-title")?.text() ?: "") ?: defaultSeason

            val downloadDivs = doc.select(".download-links-div, div[class*='download']")
            if (downloadDivs.isNotEmpty()) {
                downloadDivs.forEach { div ->
                    div.select(".downloads-btns-div, div, p, h3, h4, h5, a").forEach { element ->
                        val text = element.text().trim()
                        val s = extractSeasonFromText(text)
                        if (s != null) currentSeason = s

                        val eps = extractEpisodesFromText(text)
                        if (eps.isNotEmpty()) {
                            episodesBySeason.getOrPut(currentSeason) { mutableSetOf() }.addAll(eps)
                        }
                    }
                }
            } else {
                val contentRoot = doc.selectFirst(".entry-content, .post-content") ?: doc
                contentRoot.select("h3, h4, h5, h6, p, a, div").forEach { element ->
                    val text = element.text().trim()
                    if (QUALITY_REGEX.containsMatchIn(text) && !text.contains(Regex("""(?i)Ep|Episode|S\d|E\d"""))) {
                        return@forEach
                    }
                    if (text.matches(Regex("""^\d+(\.\d+)?\s*(MB|GB).*""", RegexOption.IGNORE_CASE))) {
                        return@forEach
                    }
                    val s = extractSeasonFromText(text)
                    if (s != null) currentSeason = s

                    val eps = extractEpisodesFromText(text)
                    if (eps.isNotEmpty()) {
                        episodesBySeason.getOrPut(currentSeason) { mutableSetOf() }.addAll(eps)
                    }
                }
            }
        }

        parseDocForEpisodes(cleanDoc, defaultSeason = mainSeason)

        val aggregatorLinks = cleanDoc.select("a[href*='hubdrive'], a[href*='hubcloud'], a[href*='gdflix'], a[href*='mdrive'], a[href*='m4ulinks']")
            .map { Pair(it.attr("href"), extractSeasonFromText(it.text()) ?: mainSeason) }
            .distinctBy { it.first }

        if (aggregatorLinks.isNotEmpty()) {
            withTimeoutOrNull(4000L) {
                aggregatorLinks.take(4).amap { (href, seasonContext) ->
                    try {
                        val aggDoc = app.get(href, headers = headers).document
                        aggDoc.select("aside, footer, header, nav, #sidebar, .ct-related-posts-items, .related-posts, #comments, #respond, .wp-block-latest-posts, .ct-widget, .widget, .ct-share-box").remove()
                        parseDocForEpisodes(aggDoc, defaultSeason = seasonContext)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching aggregator for episode detection: ${e.message}")
                    }
                }
            }
        }

        if (episodesBySeason.isNotEmpty()) {
            episodesBySeason.keys.sorted().forEach { sNum ->
                val set = (episodesBySeason[sNum] ?: emptySet()).minus(QUALITY_NUMBERS)
                val maxEp = set.maxOrNull() ?: 1
                for (epNum in 1..maxEp) {
                    val data = "$pageUrl|||$sNum|||$epNum"
                    episodes.add(
                        newEpisode(data) {
                            this.name = "Episode $epNum"
                            this.episode = epNum
                            this.season = sNum
                        }
                    )
                }
            }
        } else {
            val data = "$pageUrl|||1|||0"
            episodes.add(
                newEpisode(data) {
                    this.name = "Full Season"
                    this.episode = 1
                    this.season = 1
                }
            )
        }

        Log.d(TAG, "Total episodes: ${episodes.size}")
        return episodes.sortedWith(compareBy<com.lagradost.cloudstream3.Episode> { it.season }.thenBy { it.episode })
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

    private data class DownloadLink(
        val url: String,
        val quality: Int,
        val sizeMB: Double,
        val originalText: String,
        val seasonNum: Int? = null,
        val episodes: Set<Int> = emptySet()
    )

    private suspend fun extractDownloadLinks(document: Document): List<DownloadLink> {
        val downloadLinks = mutableListOf<DownloadLink>()
        val seenUrls = mutableSetOf<String>()
        var currentEpisodes = emptySet<Int>()
        var currentSeason: Int? = null

        val cleanDoc = document.clone()
        cleanDoc.select("aside, footer, header, nav, #sidebar, .ct-related-posts-items, .related-posts, #comments, #respond, .wp-block-latest-posts, .ct-widget, .widget, .ct-share-box").remove()

        val pageHeading = cleanDoc.selectFirst("title, h1.single-title, .entry-title, h1.post-title, h1, h2.title, h2.post-title")?.text() ?: ""
        currentSeason = extractSeasonFromText(pageHeading)

        val relevantSelector = "h3, h4, h5, h6, a[href*='hubdrive'], a[href*='hubcloud'], a[href*='gdflix'], a[href*='mdrive']"

        cleanDoc.select(relevantSelector).forEach { element ->
            val tagName = element.tagName().uppercase()
            val elementText = element.text().trim()

            val s = extractSeasonFromText(elementText)
            if (s != null) {
                currentSeason = s
            }

            if (tagName in listOf("H3", "H4", "H5", "H6")) {
                val eps = extractEpisodesFromText(elementText)
                if (eps.isNotEmpty()) {
                    currentEpisodes = eps
                }
                return@forEach
            }

            if (tagName == "A") {
                val url = element.attr("href")
                val linkText = elementText

                if (url.isBlank() || seenUrls.contains(url)) return@forEach
                if (shouldBlockUrl(url)) return@forEach

                if (linkText.contains("Zip", ignoreCase = true) ||
                    linkText.contains(".zip", ignoreCase = true) ||
                    url.endsWith(".zip", ignoreCase = true)) {
                    return@forEach
                }

                seenUrls.add(url)

                val linkEpisodes = extractEpisodesFromText(linkText).ifEmpty { currentEpisodes }
                val epStr = if (linkEpisodes.isNotEmpty()) linkEpisodes.joinToString("-") else ""

                val episodeContext = when {
                    linkEpisodes.isNotEmpty() && linkText.isNotBlank() -> "EPiSODE $epStr | $linkText"
                    linkText.isNotBlank() -> linkText
                    else -> "Download"
                }

                downloadLinks.add(
                    DownloadLink(
                        url = url,
                        quality = extractQuality(episodeContext),
                        sizeMB = parseFileSize(episodeContext),
                        originalText = episodeContext,
                        seasonNum = currentSeason,
                        episodes = linkEpisodes
                    )
                )
            }
        }

        val expandedLinks = mutableListOf<DownloadLink>()
        for (link in downloadLinks) {
            if (link.url.contains("hubdrive", ignoreCase = true) ||
                link.url.contains("hubcloud", ignoreCase = true) ||
                link.url.contains("mdrive", ignoreCase = true) ||
                link.url.contains("m4ulinks", ignoreCase = true)) {
                try {
                    val m4uDoc = app.get(link.url).document
                    var m4uEpisodes = link.episodes
                    var m4uSeason = link.seasonNum ?: extractSeasonFromText(m4uDoc.selectFirst("title, h1, h2, h3")?.text() ?: "")
                    
                    var currentContext = ""

                    m4uDoc.select("h1, h2, h3, h4, h5, h6, a[href*='hubcloud'], a[href*='gdflix'], a[href*='hubcdn'], a[href*='pixeldrain'], a[href*='fastdl'], a[href*='filebee'], a[href*='gofile']").forEach { elem ->
                        val tag = elem.tagName().uppercase()
                        val text = elem.text().trim()

                        if (tag.startsWith("H")) {
                             val s = extractSeasonFromText(text)
                             if (s != null) m4uSeason = s
                             val eps = extractEpisodesFromText(text)
                             if (eps.isNotEmpty()) m4uEpisodes = eps
                             if (text.isNotBlank() && !text.equals("HubCloud", true) && !text.equals("GDFliX", true) && elem.select("a").isEmpty()) {
                                 currentContext = text
                             }
                        } else if (tag == "A") {
                            val abs = elem.absUrl("href")
                            val innerUrl = if (abs.isNotBlank()) abs else elem.attr("href")
                            val innerText = text
                            
                            val parentText = elem.parent()?.previousElementSibling()?.text()?.trim() ?: ""
                            val linkContext = if (currentContext.isNotBlank()) currentContext else if (parentText.isNotBlank()) parentText else link.originalText
                            
                            val epFromContext = extractEpisodesFromText(linkContext)
                            val finalEps = if (epFromContext.isNotEmpty()) epFromContext else m4uEpisodes
                            
                            val finalSeason = extractSeasonFromText(linkContext) ?: m4uSeason
                            
                            if (innerUrl.isNotBlank() && !shouldBlockUrl(innerUrl) && !innerText.contains("Zip", true) && !linkContext.contains("Zip", true)) {
                                expandedLinks.add(
                                    DownloadLink(
                                        url = innerUrl,
                                        quality = extractQuality(linkContext).let { if (it == 0) link.quality else it },
                                        sizeMB = parseFileSize(linkContext),
                                        originalText = "$linkContext - $innerText",
                                        seasonNum = finalSeason,
                                        episodes = finalEps
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error expanding aggregator links: ${e.message}")
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
        checkAndFetchDomain()
        Log.d(TAG, "Loading links from: $data")

        try {
            val parts = data.split("|||")
            val pageUrl = parts[0]
            val targetSeason = if (parts.size > 2) parts[1].toIntOrNull() else null
            val targetEpisode = when {
                parts.size > 2 -> parts[2].toIntOrNull()
                parts.size > 1 -> parts[1].toIntOrNull()
                else -> null
            }

            val document = app.get(pageUrl, headers = headers).document
            val allLinks = extractDownloadLinks(document)

            val targetLinks = when {
                targetEpisode == null -> allLinks
                targetEpisode == 0 -> allLinks
                else -> {
                    val seasonFiltered = if (targetSeason != null) {
                        allLinks.filter { it.seasonNum == null || it.seasonNum == targetSeason }.ifEmpty { allLinks }
                    } else {
                        allLinks
                    }

                    val byField = seasonFiltered.filter { it.episodes.contains(targetEpisode) }
                    if (byField.isNotEmpty()) byField
                    else {
                        val byText = seasonFiltered.filter { link ->
                            val eps = extractEpisodesFromText(link.originalText)
                            eps.contains(targetEpisode)
                        }
                        if (byText.isNotEmpty()) byText else seasonFiltered
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

            val linksToProcess = if (targetEpisode != null) 3 else 5
            withTimeoutOrNull(6_000L) {
                sortedLinks.take(linksToProcess).amap { downloadLink ->
                    try {
                        val link = downloadLink.url
                        Log.d(TAG, "Extracting: $link")
                        processPluginExtractor(link, mainUrl, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error extracting ${downloadLink.url}: ${e.message}")
                    }
                }
            } ?: Log.w(TAG, "Timeout reached (6s)")
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadLinks: ${e.message}")
        }

        return true
    }

    private fun cleanTitle(title: String): String {
        val unescaped = Parser.unescapeEntities(title, false)
        val cleaned = unescaped
            .replace(Regex("""\(\d{4}\)"""), "")
            .replace(Regex("""\[.*?]"""), "")
            .replace(Regex("""\|.*$"""), "")
            .replace(Regex("""(?i)\bS\d{1,2}\s*E\d{1,3}(?:[T\-E]\d{1,3})?\b"""), "")
            .replace(Regex("""(?i)\bS\d{1,2}\b"""), "")
            .replace(Regex("""(?i)\bE\d{1,3}(?:[T\-E]\d{1,3})?\b"""), "")
            .replace(Regex("""(?i)\bE(?:PISODE|P|pisode)?\s*[-.:#]*\s*\d{1,3}(?:\s*[-~T]\s*E?\d{1,3})?\b"""), "")
            .replace(Regex("""(?i)\b(WEB-?DL|BluRay|HDRip|WEBRip|HDTV|DVDRip|BRRip|UNCUT|UNRATED|PROPER)\b"""), "")
            .replace(Regex("""(?i)\b(4K|UHD|1080p|720p|480p|360p|2160p|IMAX|HDTC|HQ-HDTC|CAM|Rip|V\d+)\b"""), "")
            .replace(Regex("""(?i)\b(HEVC|x264|x265|10Bit|H\.?264|H\.?265|AAC|DD5?\.?1?|LiNE)\b"""), "")
            .replace(Regex("""(?i)\b(Download|Free|Full|HD|Watch)\b"""), "")
            .replace(Regex("""(?i)\b(Hindi|English|Dual\s*Audio|ESubs?|Multi\s*Audio|Multi|Bengali|Punjabi|Tamil|Telugu|Malayalam|Kannada|Marathi|Gujarati|Bhojpuri|Urdu|Pakistani|Bangladeshi|Korean|Chinese|China|WWE|TV\s*Show|Hot|Short\s*Film|Web\s*Series|Series|Serial|Complete|All\s*Episodes|ORG)\b"""), "")
            .replace(Regex("""(?i)\b(Download|Free|Full|HD|Watch|MoviesDrive|Movie)\b"""), "")
            .replace(Regex("""[&+]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        return if (cleaned.length >= 2) cleaned else unescaped.substringBefore("|").trim()
    }
}



