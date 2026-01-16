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
import com.lagradost.cloudstream3.amap
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
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
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
        // Updated selectors for new website structure (Jan 2026)
        // HTML: article.item > .image > a > .data > h3.title
        
        val title = when {
            selectFirst("h3.title") != null -> selectFirst("h3.title")!!.text().trim()
            selectFirst(".data h3") != null -> selectFirst(".data h3")!!.text().trim()
            selectFirst(".data h3 a") != null -> selectFirst(".data h3 a")!!.text().trim()
            selectFirst("h3 a") != null -> selectFirst("h3 a")!!.text().trim()
            selectFirst("h3") != null -> selectFirst("h3")!!.text().trim()
            else -> selectFirst("a")?.text()?.trim() ?: ""
        }
        
        if (title.isBlank()) return null
        
        // Updated href extraction - .image a contains the link
        val href = when {
            selectFirst(".image a") != null -> fixUrl(selectFirst(".image a")!!.attr("href"))
            selectFirst(".poster a") != null -> fixUrl(selectFirst(".poster a")!!.attr("href"))
            selectFirst(".data h3 a") != null -> fixUrl(selectFirst(".data h3 a")!!.attr("href"))
            selectFirst("h3 a") != null -> fixUrl(selectFirst("h3 a")!!.attr("href"))
            else -> fixUrl(selectFirst("a")?.attr("href") ?: "")
        }
        
        if (href.isBlank() || href == mainUrl) return null
        
        // Poster from .image img
        val posterUrl = when {
            selectFirst(".image img") != null -> fixUrlNull(selectFirst(".image img")?.getImageAttr())
            selectFirst(".poster img") != null -> fixUrlNull(selectFirst(".poster img")?.getImageAttr())
            selectFirst("img") != null -> fixUrlNull(selectFirst("img")?.getImageAttr())
            else -> null
        }
        
        val isMovie = href.contains("/movies/")
        
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
                        
                        // Robust Regex for 100% matching of Season/Episode formats
                        // Handles: "1 - 1", "1-1", "S1 E1", "Season 1 Episode 1", "19 - 72"
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
        Log.d("MultiMovies", "loadLinks called with: $data")
        val req = app.get(data, interceptor = cfKiller).document
        req.select("ul#playeroptionsul li").map {
            Triple(
                it.attr("data-post"),
                it.attr("data-nume"),
                it.attr("data-type")
            )
        }.amap { (id, nume, type) ->
            if (!nume.contains("trailer")) {
                try {
                    val source = app.post(
                        url = "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to id,
                            "nume" to nume,
                            "type" to type
                        ),
                        referer = mainUrl,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                        interceptor = cfKiller
                    ).parsed<ResponseHash>().embed_url
                    val link = source.substringAfter("\"").substringBefore("\"").trim()
                    Log.d("MultiMovies", "Extracted embed URL: $link")
                    
                    when {
                        link.contains("youtube") -> return@amap
                        
                        // Direct routing for known hosters
                        link.contains("multimoviesshg.com", ignoreCase = true) -> {
                            Log.d("MultiMovies", "Using Multiprocessing for: $link")
                            Multiprocessing().getUrl(link, mainUrl, subtitleCallback, callback)
                        }
                        link.contains("streamhg", ignoreCase = true) || 
                        link.contains("vidhide", ignoreCase = true) || 
                        link.contains("earnvid", ignoreCase = true) -> {
                            Log.d("MultiMovies", "Using VidHidePro for: $link")
                            VidHidePro().getUrl(link, mainUrl, subtitleCallback, callback)
                        }
                        link.contains("vidstack", ignoreCase = true) ||
                        link.contains("server1.uns", ignoreCase = true) -> {
                            Log.d("MultiMovies", "Using VidStack for: $link")
                            VidStack().getUrl(link, mainUrl, subtitleCallback, callback)
                        }
                        link.contains("streamcasthub", ignoreCase = true) -> {
                            Log.d("MultiMovies", "Using Streamcasthub for: $link")
                            Streamcasthub().getUrl(link, mainUrl, subtitleCallback, callback)
                        }
                        link.contains("gdmirrorbot", ignoreCase = true) ||
                        link.contains("techinmind", ignoreCase = true) ||
                        link.contains("iqsmartgames", ignoreCase = true) -> {
                            Log.d("MultiMovies", "Using GDMirror for: $link")
                            GDMirror().getUrl(link, mainUrl, subtitleCallback, callback)
                        }
                        link.contains("deaddrive.xyz") -> {
                            app.get(link).document.select("ul.list-server-items > li").map {
                                val server = it.attr("data-video")
                                loadExtractorByDomain(server, mainUrl, subtitleCallback, callback)
                            }
                        }
                        else -> {
                            // Fallback: try GDMirror which handles multiple hosters
                            Log.d("MultiMovies", "Using GDMirror fallback for: $link")
                            GDMirror().getUrl(link, mainUrl, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MultiMovies", "Error processing link: ${e.message}")
                }
            }
        }
        return true
    }
    
    private suspend fun loadExtractorByDomain(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            url.contains("multimoviesshg.com", ignoreCase = true) -> 
                Multiprocessing().getUrl(url, referer, subtitleCallback, callback)
            url.contains("vidhide", ignoreCase = true) || 
            url.contains("streamhg", ignoreCase = true) -> 
                VidHidePro().getUrl(url, referer, subtitleCallback, callback)
            url.contains("vidstack", ignoreCase = true) -> 
                VidStack().getUrl(url, referer, subtitleCallback, callback)
            else -> 
                GDMirror().getUrl(url, referer, subtitleCallback, callback)
        }
    }

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("type") val type: String? = null,
    )


    private fun Element.getImageAttr(): String? {
        return this.attr("data-src")
            .takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: this.attr("src").takeIf { it.isNotBlank() && it.startsWith("http") }
    }
}