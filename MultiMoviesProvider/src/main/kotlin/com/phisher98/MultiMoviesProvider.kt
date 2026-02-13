package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.nodes.Element

class MultiMoviesProvider : MainAPI() {
    override var mainUrl: String = "https://multimovies.gripe/"

    companion object {
        private val cfKiller by lazy { CloudflareKiller() }

        private var cachedUrls: JSONObject? = null

        private val multimoviesshgBaseUrl: String by lazy {
            runBlocking { getLatestUrl("multimoviesshg") } ?: "https://multimoviesshg.com"
        }

        private val unsBioBaseUrl: String by lazy {
            runBlocking { getLatestUrl("server1.uns.bio") } ?: "https://server1.uns.bio"
        }

        suspend fun getLatestUrl(source: String): String? {
            return try {
                if (cachedUrls == null) {
                    cachedUrls = JSONObject(
                        app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json").text
                    )
                }
                cachedUrls?.optString(source)?.takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }

        fun getMultimoviesshgUrl(): String = multimoviesshgBaseUrl

        fun getUnsBioUrl(): String = unsBioBaseUrl
    }

    init {
        runBlocking {
            getLatestUrl("multimovies")?.let {
                mainUrl = it
            }
        }
    }

    override var name = "MultiMovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
    )

    override val mainPage = mainPageOf(
        "movies/" to "Latest Release",
        "genre/bollywood-movies/" to "Bollywood Movies",
        "genre/hollywood/" to "Hollywood Movies",
        "genre/south-indian/" to "South Indian Movies",
        "genre/amazon-prime/" to "Amazon Prime",
        "genre/disney-hotstar/" to "Jio Hotstar",
        "genre/netflix/" to "Netflix",
        "genre/sony-liv/" to "Sony Live",
        "genre/zee-5/" to "Zee5"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}page/$page/"
        val document = app.get(url, interceptor = cfKiller).document

        val home = when {
            request.data.contains("/movies") || request.data.contains("/genre/") -> {
                document.select("article.item, #archive-content > article, div.items > article").mapNotNull {
                    it.toSearchResult()
                }
            }
            request.data.isEmpty() || request.data == "/" -> {
                val featuredMovies = document.select("#featured-titles .item.movies, #featured-titles article.item").mapNotNull {
                    it.toSearchResult()
                }
                val regularMovies = document.select("#dt-movies .item, article.item").mapNotNull {
                    it.toSearchResult()
                }
                val archiveMovies = document.select("#archive-content > article").mapNotNull {
                    it.toSearchResult()
                }
                (featuredMovies + regularMovies + archiveMovies).distinctBy { it.name }
            }
            else -> {
                document.select("article.item, div.items > article, #archive-content > article").mapNotNull {
                    it.toSearchResult()
                }
            }
        }
        return newHomePageResponse(HomePageList(request.name, home))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = when {
            selectFirst("h3.title") != null -> selectFirst("h3.title")!!.text().trim()
            selectFirst(".data h3") != null -> selectFirst(".data h3")!!.text().trim()
            selectFirst(".data h3 a") != null -> selectFirst(".data h3 a")!!.text().trim()
            selectFirst("h3 a") != null -> selectFirst("h3 a")!!.text().trim()
            selectFirst("h3") != null -> selectFirst("h3")!!.text().trim()
            else -> selectFirst("a")?.text()?.trim() ?: ""
        }

        if (title.isBlank()) return null

        val href = when {
            selectFirst(".image a") != null -> fixUrl(selectFirst(".image a")!!.attr("href"))
            selectFirst(".poster a") != null -> fixUrl(selectFirst(".poster a")!!.attr("href"))
            selectFirst(".data h3 a") != null -> fixUrl(selectFirst(".data h3 a")!!.attr("href"))
            selectFirst("h3 a") != null -> fixUrl(selectFirst("h3 a")!!.attr("href"))
            else -> fixUrl(selectFirst("a")?.attr("href") ?: "")
        }

        if (href.isBlank() || href == mainUrl) return null

        val posterUrl = when {
            selectFirst(".image img") != null -> fixUrlNull(selectFirst(".image img")?.getImageAttr())
            selectFirst(".poster img") != null -> fixUrlNull(selectFirst(".poster img")?.getImageAttr())
            selectFirst("img") != null -> fixUrlNull(selectFirst("img")?.getImageAttr())
            else -> null
        }

        val isMovie = href.contains("/movies/")
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", interceptor = cfKiller).document
        return document.select("div.result-item article").mapNotNull { article ->
            val titleElement = article.selectFirst("div.details > div.title > a") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = fixUrl(titleElement.attr("href"))
            val imageDiv = article.selectFirst("div.image > div.thumbnail > a")
            val posterUrl = fixUrlNull(imageDiv?.selectFirst("img")?.getImageAttr())
            val typeText = imageDiv?.selectFirst("span.movies, span.tvshows")?.text()
            val isMovie = typeText?.contains("Movie", ignoreCase = true) == true || !href.contains("tvshows", ignoreCase = true)

            if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
            }
        }
    }

    data class LinkData(
        val name: String?,
        val type: String,
        val post: String,
        val nume: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, interceptor = cfKiller).document
        val title = doc.selectFirst("div.sheader > div.data > h1")?.text()?.trim() ?: ""
        var poster = fixUrlNull(doc.selectFirst("div.sheader div.poster img")?.getImageAttr())
        if (poster.isNullOrBlank()) {
            poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        }
        if (poster.isNullOrBlank()) {
            poster = fixUrlNull(doc.selectFirst("meta[name=twitter:image]")?.attr("content"))
        }
        val tags = doc.select("div.sgeneros > a").map { it.text() }
        val year = doc.selectFirst("span.date")?.text()?.substringAfter(",")?.trim()?.toIntOrNull()
        val description = doc.selectFirst("#info div.wp-content p")?.text()?.trim()
        val type = if (url.contains("/tvshows/")) TvType.TvSeries else TvType.Movie
        val trailer = doc.selectFirst("iframe.rptss")?.attr("src")
        val rating = doc.select("span.dt_rating_vgs").text()
        val duration = doc.selectFirst("span.runtime")?.text()?.removeSuffix(" Min.")?.trim()?.toIntOrNull()
        val actors = doc.select("div.person").map {
            ActorData(
                Actor(
                    it.select("div.data > div.name > a").text(),
                    it.selectFirst("div.img > a > img")?.getImageAttr()
                ),
                roleString = it.select("div.data > div.caracter").text()
            )
        }
        val recommendations = doc.select("#dtw_content_related-2 article").mapNotNull { it.toSearchResult() }

        return if (type == TvType.TvSeries) {
            val episodes = if (doc.select("#seasons ul.episodios > li").isNotEmpty()) {
                doc.select("#seasons ul.episodios > li").mapNotNull {
                    val name = it.select("div.episodiotitle > a").text()
                    val href = it.select("div.episodiotitle > a").attr("href")
                    val posterUrl = it.select("div.imagen > img").attr("src")
                    val numerandoText = it.select("div.numerando").text().trim()
                    val match = Regex("""(?i)(?:s(?:eason)?\s*)?(\d+)(?:\s*[-–]\s*|\s*e(?:pisode)?\s*)(\d+)""").find(numerandoText)
                    val season = match?.groupValues?.get(1)?.toIntOrNull()
                    val episode = match?.groupValues?.get(2)?.toIntOrNull()

                    newEpisode(href) {
                        this.name = name
                        this.posterUrl = posterUrl
                        this.season = season
                        this.episode = episode
                    }
                }
            } else {
                doc.select("ul#playeroptionsul > li")
                    .filter { !it.attr("data-nume").equals("trailer", ignoreCase = true) }
                    .mapIndexed { index, it ->
                        val name = it.selectFirst("span.title")?.text()
                        val typeAttr = it.attr("data-type")
                        val post = it.attr("data-post")
                        val nume = it.attr("data-nume")
                        newEpisode(LinkData(name, typeAttr, post, nume, url).toJson()) {
                            this.name = name
                            this.episode = index + 1
                            this.season = 1
                        }
                    }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    /**
     * loadLinks — simplified flow:
     * 1. Get iframe URL (direct from page HTML, or via AJAX for LinkData)
     * 2. Parse embed page for JS variables (FinalID, idType, myKey, season, episode)
     * 3. Call mymovieapi to get quality items (fileslug, filename, fsize)
     * 4. Sort by quality priority (X264 1080p > X264 720p > X265 1080p > ...)
     * 5. For each quality item, fetch SVID server links via fetchSvidServerLinks()
     * 6. For each server link, delegate to registered extractors via loadExtractor()
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            var iframeSrc: String? = null
            val pageUrl: String

            if (data.startsWith("{")) {
                // LinkData from player options (TV show without season structure)
                val linkData = parseJson<LinkData>(data)
                pageUrl = linkData.url
                iframeSrc = getIframeViaAjax(linkData.post, linkData.type, linkData.nume, pageUrl)
            } else {
                pageUrl = data
                val doc = app.get(pageUrl, interceptor = cfKiller).document

                // Try to find iframe directly in page HTML
                iframeSrc = doc.selectFirst("#dooplay_player_response iframe")?.attr("src")
                    ?: doc.selectFirst("iframe.metaframe")?.attr("src")
                    ?: doc.selectFirst(".pframe iframe")?.attr("src")

                // If no iframe found, try AJAX with first non-trailer player option
                if (iframeSrc.isNullOrBlank()) {
                    val playerOption = doc.select("ul#playeroptionsul > li")
                        .firstOrNull { !it.attr("data-nume").equals("trailer", ignoreCase = true) }

                    if (playerOption != null) {
                        val post = playerOption.attr("data-post")
                        val type = playerOption.attr("data-type")
                        val nume = playerOption.attr("data-nume")
                        iframeSrc = getIframeViaAjax(post, type, nume, pageUrl)
                    }
                }
            }

            if (iframeSrc.isNullOrBlank()) {
                Log.e("MultiMovies", "No iframe found for: $pageUrl")
                return false
            }

            Log.d("MultiMovies", "Iframe src: $iframeSrc")

            // Load embed page and extract JS variables
            val embedHtml = app.get(iframeSrc, referer = pageUrl).text

            val finalId = Regex("""let\s+FinalID\s*=\s*["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1)
            val idType = Regex("""let\s+idType\s*=\s*["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1)
            val myKey = Regex("""let\s+myKey\s*=\s*["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1)

            if (finalId == null || idType == null || myKey == null) {
                Log.e("MultiMovies", "Could not extract JS variables from embed page")
                return false
            }

            Log.d("MultiMovies", "FinalID=$finalId, idType=$idType")

            // Extract API URL template and base evid URL
            val apiUrlTemplate = Regex("""let\s+apiUrl\s*=\s*`([^`]+)`""").find(embedHtml)?.groupValues?.get(1)
            val baseEvidUrl = Regex("""let\s+baseUrl\s*=\s*["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1)
                ?: "https://ssn.techinmind.space/evid/"

            // Extract season/episode for TV show API calls
            val seasonVar = Regex("""let\s+season\s*=\s*["']?(\d+)["']?""").find(embedHtml)?.groupValues?.get(1)
            val episodeVar = Regex("""let\s+episode\s*=\s*["']?(\d+)["']?""").find(embedHtml)?.groupValues?.get(1)

            // Build the actual API URL by replacing template variables
            val apiUrl = apiUrlTemplate
                ?.replace("\${idType}", idType)
                ?.replace("\${FinalID}", finalId)
                ?.replace("\${myKey}", myKey)
                ?.let { url ->
                    var result = url
                    if (seasonVar != null) result = result.replace("\${season}", seasonVar)
                    if (episodeVar != null) result = result.replace("\${episode}", episodeVar)
                    result
                }

            // Data class for quality tracking
            data class QualityItem(
                val fileslug: String,
                val filename: String,
                val priority: Int,
                val sizeMB: Double = Double.MAX_VALUE
            )

            val qualityItems = mutableListOf<QualityItem>()

            // Call mymovieapi to get file data
            if (!apiUrl.isNullOrBlank()) {
                try {
                    val apiResponseText = app.get(apiUrl, referer = iframeSrc).text
                    val json = JSONObject(apiResponseText)

                    if (json.optBoolean("success", false)) {
                        val dataArray = json.getJSONArray("data")
                        for (i in 0 until dataArray.length()) {
                            val item = dataArray.getJSONObject(i)
                            val fileslug = item.optString("fileslug", "")
                            val filename = item.optString("filename", "")
                            val sizeStr = item.optString("fsize", "")
                            val sizeMB = parseFileSizeMB(sizeStr)

                            if (fileslug.isNotEmpty()) {
                                qualityItems.add(
                                    QualityItem(fileslug, filename, getQualityPriority(filename), sizeMB)
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MultiMovies", "API call failed: ${e.message}")
                }
            }

            // Fallback: try data-link attributes from embed page HTML
            if (qualityItems.isEmpty()) {
                val qualityLinks = Regex("""data-link=["']([^"']+)["'][^>]*>([^<]+)""").findAll(embedHtml)
                for (match in qualityLinks) {
                    val link = match.groupValues[1].trim()
                    val filename = match.groupValues[2].trim()
                    if (link.isNotEmpty()) {
                        qualityItems.add(
                            QualityItem(link, filename, getQualityPriority(filename))
                        )
                    }
                }
            }

            if (qualityItems.isEmpty()) {
                Log.e("MultiMovies", "No quality items found")
                return false
            }

            // Sort: quality priority first, then file size (smaller preferred)
            qualityItems.sortWith(compareBy({ it.priority }, { it.sizeMB }))
            Log.d("MultiMovies", "Found ${qualityItems.size} quality items, sorted by priority")

            val processedLinks = mutableSetOf<String>()

            // Process each quality item — fetch SVID server links, delegate to extractors
            for (qualityItem in qualityItems) {
                try {
                    val qualityValue = getQualityFromName(qualityItem.filename)

                    Log.d("MultiMovies", "Processing: ${qualityItem.filename} (priority: ${qualityItem.priority})")

                    // Fetch all server links from SVID page for this fileslug
                    val serverLinks = fetchSvidServerLinks(qualityItem.fileslug, baseEvidUrl)

                    for ((serverUrl, sourceKey) in serverLinks) {
                        if (serverUrl in processedLinks) continue
                        if (isStreamingUrl(serverUrl)) continue

                        processedLinks.add(serverUrl)

                        // Try registered extractors first (match by URL domain)
                        val extractorHandled = loadExtractor(
                            serverUrl, pageUrl, subtitleCallback, callback
                        )

                        if (!extractorHandled) {
                            // Fallback: emit as direct link with quality info
                            callback.invoke(
                                newExtractorLink(
                                    "MultiMovies",
                                    "MultiMovies ${qualityItem.filename} [$sourceKey]",
                                    serverUrl,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = pageUrl
                                    this.quality = qualityValue
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MultiMovies", "Quality ${qualityItem.filename} error: ${e.message}")
                }
            }

            return processedLinks.isNotEmpty() || qualityItems.isNotEmpty()
        } catch (e: Exception) {
            Log.e("MultiMovies", "loadLinks failed: ${e.message}")
            return false
        }
    }

    /**
     * Make AJAX call to WordPress doo_player_ajax to get iframe embed URL.
     * DooPlay theme loads player iframes via AJAX with post ID, type, and nume.
     *
     * Response format: {"type":"iframe","embed_url":"<iframe src='...' ...>"}
     * or: {"embed_url":"https://..."}
     */
    private suspend fun getIframeViaAjax(post: String, type: String, nume: String, referer: String): String? {
        return try {
            val response = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to post,
                    "type" to type,
                    "nume" to nume
                ),
                referer = referer,
                interceptor = cfKiller
            )
            val json = JSONObject(response.text)
            val embedUrl = json.optString("embed_url", "")

            // embed_url might be HTML iframe tag or just a URL
            Regex("""src=["']([^"']+)["']""").find(embedUrl)?.groupValues?.get(1)
                ?: embedUrl.takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            Log.e("MultiMovies", "AJAX call failed: ${e.message}")
            null
        }
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("data-src")
            hasAttr("data-lazy-src") -> attr("data-lazy-src")
            hasAttr("srcset") -> attr("srcset").substringBefore(" ")
            else -> attr("src")
        }
    }
}
