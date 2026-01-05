package com.phisher98


import com.fasterxml.jackson.annotation.JsonProperty
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
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import org.json.JSONObject
import org.jsoup.nodes.Element

class MultiMoviesProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl: String = "https://multimovies.golf/"

    init {
        runBlocking {
            basemainUrl?.let {
                mainUrl = it
            }
        }
    }

    companion object {
        val basemainUrl: String? by lazy {
            runBlocking {
                try {
                    val response = app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json")
                    val json = response.text
                    val jsonObject = JSONObject(json)
                    jsonObject.optString("multimovies")
                } catch (_: Exception) {
                    null
                }
            }
        }
        
        // Cloudflare bypass interceptor
        private val cfKiller by lazy { CloudflareKiller() }
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
        "genre/netflix/" to "Netfilx",
        "genre/sony-liv/" to "Sony Live",
        "genre/zee-5/" to "Zee5"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        Log.d("MultiMovies", "Loading main page: ${request.name}, data: ${request.data}, page: $page")

        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}" + "page/$page/"
        }
        
        // multimovies.golf always has Cloudflare, so use CloudflareKiller directly
        val document = app.get(url, interceptor = cfKiller).document

        val home = when {
            // For movies listing page
            request.data.contains("/movies") -> {
                document.select("article.item, #archive-content > article").mapNotNull {
                    it.toSearchResult()
                }
            }

            // For genre/category pages
            request.data.contains("/genre/") || request.data.contains("/category/") -> {
                document.select("article.item, div.items > article, #archive-content > article").mapNotNull {
                    it.toSearchResult()
                }
            }

            // For main page (homepage)
            request.data.isEmpty() || request.data == "/" -> {
                // Get movies from both featured and regular movie sections
                val featuredMovies = document.select("#featured-titles .item.movies, #featured-titles .item.tvshows, #featured-titles article.item").mapNotNull {
                    it.toSearchResult()
                }
                val regularMovies = document.select("#dt-movies .item, article.item").mapNotNull {
                    it.toSearchResult()
                }
                val archiveMovies = document.select("#archive-content > article").mapNotNull {
                    it.toSearchResult()
                }

                // Combine all movie results, removing duplicates
                (featuredMovies + regularMovies + archiveMovies).distinctBy { it.name }
            }

            // Default fallback
            else -> {
                document.select("article.item, div.items > article, #archive-content > article").mapNotNull {
                    it.toSearchResult()
                }
            }
        }

        Log.d("MultiMovies", "Found ${home.size} items for ${request.name}")
        return newHomePageResponse(HomePageList(request.name, home))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Try multiple selectors for title - handle different page structures
        // Website structure: article.item > .data > h3 > a (for homepage/category)
        val title = when {
            selectFirst(".data h3 a") != null -> selectFirst(".data h3 a")!!.text().trim()
            selectFirst("h3 a") != null -> selectFirst("h3 a")!!.text().trim()
            selectFirst(".data h3") != null -> selectFirst(".data h3")!!.text().trim()
            selectFirst("h3") != null -> selectFirst("h3")!!.text().trim()
            else -> selectFirst("a")?.text()?.trim() ?: ""
        }
        
        if (title.isBlank()) return null
        
        // Try multiple selectors for href
        // Website structure: .poster a or .data h3 a
        val href = when {
            selectFirst(".data h3 a") != null -> fixUrl(selectFirst(".data h3 a")!!.attr("href"))
            selectFirst("h3 a") != null -> fixUrl(selectFirst("h3 a")!!.attr("href"))
            selectFirst(".poster a") != null -> fixUrl(selectFirst(".poster a")!!.attr("href"))
            selectFirst(".image a") != null -> fixUrl(selectFirst(".image a")!!.attr("href"))
            else -> fixUrl(selectFirst("a")?.attr("href") ?: "")
        }
        
        if (href.isBlank() || href == mainUrl) return null
        
        // Try multiple selectors for poster using getImageAttr() for lazy loading support
        // Website structure: article.item > .poster > img
        val posterUrl = when {
            selectFirst(".poster img") != null -> fixUrlNull(selectFirst(".poster img")?.getImageAttr())
            selectFirst(".image img") != null -> fixUrlNull(selectFirst(".image img")?.getImageAttr())
            selectFirst("img") != null -> fixUrlNull(selectFirst("img")?.getImageAttr())
            else -> null
        }
        
        val isMovie = href.contains("/movies/")
        
        // Don't skip items without poster - just show them without image
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("MultiMovies", "Searching for: $query")
        val document = app.get("$mainUrl/?s=$query", interceptor = cfKiller).document

        return document.select("div.result-item article").mapNotNull { article ->
            val titleElement = article.selectFirst("div.details > div.title > a") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = fixUrl(titleElement.attr("href"))
            
            val imageDiv = article.selectFirst("div.image > div.thumbnail > a")
            val posterUrl = fixUrlNull(imageDiv?.selectFirst("img")?.getImageAttr())
            
            val typeText = imageDiv?.selectFirst("span.movies, span.tvshows")?.text()
            val isMovie = typeText?.contains("Movie", ignoreCase = true) == true || 
                          !href.contains("tvshows", ignoreCase = true)

            if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("type") val type: String? = null,
    )

    data class LinkData(
        val name: String?,
        val type: String,
        val post: String,
        val nume: String,
        val url: String
    )

        override suspend fun load(url: String): LoadResponse? {
            // multimovies.golf always has Cloudflare
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
            val duration =
                doc.selectFirst("span.runtime")?.text()?.removeSuffix(" Min.")?.trim()
                    ?.toIntOrNull()
            val actors =
                doc.select("div.person").map {
                    ActorData(
                        Actor(
                            it.select("div.data > div.name > a").text(),
                            it.selectFirst("div.img > a > img")?.getImageAttr()
                        ),
                        roleString = it.select("div.data > div.caracter").text(),
                    )
                }
            val recommendations = doc.select("#dtw_content_related-2 article").mapNotNull {
                it.toSearchResult()
            }

            return if (type == TvType.TvSeries) {
                val episodes = if (doc.select("#seasons ul.episodios > li").isNotEmpty()) {
                    doc.select("#seasons ul.episodios > li").mapNotNull {
                        val name = it.select("div.episodiotitle > a").text()
                        val href = it.select("div.episodiotitle > a").attr("href")
                        val posterUrl = it.select("div.imagen > img").attr("src")
                        val season = it.select("div.numerando").text().substringBefore(" -").toIntOrNull()
                        val episode = it.select("div.numerando").text().substringAfter("- ").toIntOrNull()
                        newEpisode(href) {
                            this.name = name
                            this.posterUrl = posterUrl
                            this.season = season
                            this.episode = episode
                        }
                    }
                } else {
                    // Fallback: create episodes from player options (like Movierulzhd)
                    doc.select("ul#playeroptionsul > li")
                        .filter { !it.attr("data-nume").equals("trailer", ignoreCase = true) }
                        .mapIndexed { index, it ->
                            val name = it.selectFirst("span.title")?.text()
                            val type = it.attr("data-type")
                            val post = it.attr("data-post")
                            val nume = it.attr("data-nume")
                            
                            newEpisode(LinkData(name, type, post, nume, url).toJson()) {
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
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // Check if data is a URL or LinkData JSON
        val isUrl = data.startsWith("http")
        
        if (isUrl) {
            // For movies, data is direct URL
            val document = app.get(data, interceptor = cfKiller).document
            
            // Extract player options
            document.select("ul#playeroptionsul > li")
                .filter { !it.attr("data-nume").equals("trailer", ignoreCase = true) }
                .forEach { element ->
                    val type = element.attr("data-type")
                    val post = element.attr("data-post")
                    val nume = element.attr("data-nume")
                    
                    // Get iframe URL from player API
                    val iframeUrl = getIframeUrl(type, post, nume)
                    if (!iframeUrl.isNullOrEmpty()) {
                        loadExtractorLink(iframeUrl, data, subtitleCallback, callback)
                    }
                }
        } else {
            // For episodes, data is LinkData JSON
            try {
                val linkData = parseJson<LinkData>(data)
                val iframeUrl = getIframeUrl(linkData.type, linkData.post, linkData.nume)
                if (!iframeUrl.isNullOrEmpty()) {
                    loadExtractorLink(iframeUrl, linkData.url, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e("MultiMovies", "Error parsing link data: ${e.message}")
                e.printStackTrace()
            }
        }
        
        return true
    }

    private suspend fun getIframeUrl(type: String, post: String, nume: String): String? {
        return try {
            Log.d("MultiMovies", "Calling player API with type=$type, post=$post, nume=$nume")

            // Call player API
            val requestBody = FormBody.Builder()
                .addEncoded("action", "doo_player_ajax")
                .addEncoded("post", post)
                .addEncoded("nume", nume)
                .addEncoded("type", type)
                .build()

            // Direct call with CloudflareKiller as multimovies always has protections
            val response = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                requestBody = requestBody,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to mainUrl
                ),
                interceptor = cfKiller
            ).parsedSafe<ResponseHash>()

            val embedUrl = response?.embed_url
            Log.d("MultiMovies", "Got embed URL from API: $embedUrl")

            embedUrl
        } catch (e: Exception) {
            Log.e("MultiMovies", "Error getting iframe URL: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Enhanced image attribute extraction with comprehensive lazy loading support
     * and URL validation
     */
        private fun Element.getImageAttr(): String? {
            val imageUrl = when {
                this.hasAttr("data-src") -> this.attr("abs:data-src")
                this.hasAttr("src") -> this.attr("abs:src")
                this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
                this.hasAttr("data-original") -> this.attr("abs:data-original")
                this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
                else -> null
            }
            return validateAndCleanUrl(imageUrl)
        }
    /**
     * Validate and clean image URL with comprehensive error handling
     */
    private fun validateAndCleanUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null

        var cleanUrl = url.trim()

        // Remove common prefixes that might cause issues
        cleanUrl = cleanUrl.replace("url(", "").replace(")", "").replace("'", "").replace("\"", "")

        // Skip invalid URLs
        if (cleanUrl.isBlank() ||
            cleanUrl.startsWith("data:") ||
            cleanUrl.startsWith("javascript:") ||
            cleanUrl.contains("<script") ||
            cleanUrl.contains("</script>")) {
            return null
        }

        // If the image is from TMDB, try to use a higher quality version
        if (cleanUrl.contains("image.tmdb.org")) {
            cleanUrl = cleanUrl.replace("/w300/", "/w780/")
                                .replace("/w185/", "/w780/")
                                .replace("/w500/", "/w780/")
        }

        // Remove thumbnail suffix for WordPress uploads (e.g., -185x278.jpg -> .jpg)
        if (cleanUrl.contains("/wp-content/uploads/") && cleanUrl.matches(Regex(".*-\\d+x\\d+\\.[a-zA-Z]{3,4}$"))) {
            cleanUrl = cleanUrl.replace(Regex("-\\d+x\\d+(\\.\\w+)$"), "$1")
        }

        // If the image is from WordPress upload, ensure it's a valid URL
        if (cleanUrl.contains("/wp-content/uploads/") && !cleanUrl.startsWith("http")) {
            cleanUrl = mainUrl.trimEnd('/') + cleanUrl
        }

        // If relative URL, make it absolute
        if (cleanUrl.startsWith("//")) {
            cleanUrl = "https:$cleanUrl"
        } else if (cleanUrl.startsWith("/")) {
            cleanUrl = mainUrl.trimEnd('/') + cleanUrl
        }

        // Validate final URL format
        if (cleanUrl.startsWith("http")) {
            // Additional URL validation
            try {
                val uri = java.net.URI(cleanUrl)
                if (uri.scheme in listOf("http", "https") && uri.host != null) {
                    return cleanUrl
                }
            } catch (e: Exception) {
                Log.d("MultiMovies", "Invalid URL format: $cleanUrl, error: ${e.message}")
            }
        }

        return null
    }

    private suspend fun loadExtractorLink(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("MultiMovies", "loadExtractorLink called for: $url")

        // GdMirror/GtxGamer domains - handles 5GDL menu
        if (url.contains("gdmirrorbot", ignoreCase = true) ||
            url.contains("gdmirror", ignoreCase = true) ||
            url.contains("gtxgamer", ignoreCase = true)) {
            Log.d("MultiMovies", "Using GdMirrorExtractor")
            GdMirrorExtractor().getUrl(url, referer, subtitleCallback, callback)
            return
        }

        // TechInMind - stream.techinmind.space and ssn.techinmind.space
        if (url.contains("techinmind", ignoreCase = true)) {
            Log.d("MultiMovies", "Using TechInMindExtractor")
            TechInMindExtractor().getUrl(url, referer, subtitleCallback, callback)
            return
        }

        // RpmShare
        if (url.contains("rpmshare", ignoreCase = true) ||
            url.contains("rpmhub", ignoreCase = true)) {
            Log.d("MultiMovies", "Using RpmShareExtractor")
            RpmShareExtractor().getUrl(url, referer, subtitleCallback, callback)
            return
        }

        // StreamP2P
        if (url.contains("streamp2p", ignoreCase = true) ||
            url.contains("p2pplay", ignoreCase = true)) {
            Log.d("MultiMovies", "Using StreamP2PExtractor")
            StreamP2PExtractor().getUrl(url, referer, subtitleCallback, callback)
            return
        }

        // UpnShare
        if (url.contains("upnshare", ignoreCase = true) ||
            url.contains("uns.bio", ignoreCase = true)) {
            Log.d("MultiMovies", "Using UpnShareExtractor")
            UpnShareExtractor().getUrl(url, referer, subtitleCallback, callback)
            return
        }

        // StreamHG - multimoviesshg.com
        if (url.contains("multimoviesshg", ignoreCase = true)) {
            Log.d("MultiMovies", "Using StreamHGExtractor")
            StreamHGExtractor().getUrl(url, referer, subtitleCallback, callback)
            return
        }

        // EarnVids - smoothpre.com
        if (url.contains("smoothpre", ignoreCase = true)) {
            Log.d("MultiMovies", "Using EarnVidsExtractor")
            EarnVidsExtractor().getUrl(url, referer, subtitleCallback, callback)
            return
        }

        // Use built-in CloudStream extractors for all other hosters
        Log.d("MultiMovies", "Using built-in loadExtractor")
        loadExtractor(url, referer, subtitleCallback, callback)
    }
}
