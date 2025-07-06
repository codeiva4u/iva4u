package com.phisher98


import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import okhttp3.FormBody

class MultiMoviesProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://multimovies.agency"
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

    companion object {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept-Encoding" to "gzip, deflate",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1"
        )
        private const val DOMAINS_URL = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
        private var cachedDomains: DomainsParser? = null

        suspend fun getDomains(forceRefresh: Boolean = false): DomainsParser? {
            if (cachedDomains == null || forceRefresh) {
                try {
                    val response = app.get(DOMAINS_URL)
                    cachedDomains = response.parsedSafe<DomainsParser>()
                    println("MultiMovies: Successfully fetched domains - ${cachedDomains?.multiMovies}")
                    
                    // Override with working domain if fetched domain is different
                    if (cachedDomains?.multiMovies != "https://multimovies.agency") {
                        cachedDomains = DomainsParser("https://multimovies.agency")
                        println("MultiMovies: Using override domain - https://multimovies.agency")
                    }
                } catch (e: Exception) {
                    println("MultiMovies: Error fetching domains - ${e.message}")
                    e.printStackTrace()
                    // Use fallback domain
                    cachedDomains = DomainsParser("https://multimovies.agency")
                    println("MultiMovies: Using fallback domain - https://multimovies.agency")
                }
            }
            return cachedDomains
        }
    }

    override val mainPage = mainPageOf(
        "movies/" to "Latest Release",
        "genre/bollywood-movies/" to "Bollywood Movies",
        "genre/hollywood/" to "Hollywood Movies",
        "genre/south-indian/" to "South Indian Movies",
        "genre/netflix/" to "Netfilx",
        "genre/amazon-prime/" to "Amazon Prime",
        "genre/disney-hotstar/" to "Disney Hotstar",
        "genre/jio-ott/" to "Jio OTT",
        "genre/sony-liv/" to "Sony Live",
        "genre/zee-5/" to "Zee5",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Force use multimovies.agency directly
        val multiMoviesAPI = "https://multimovies.agency"
        val url = if (page == 1) {
            "$multiMoviesAPI/${request.data}"
        } else {
            "$multiMoviesAPI/${request.data}page/$page/"
        }
        println("MultiMovies: Fetching URL - $url")
        
        try {
            val document = app.get(url, headers = headers).document
            
            // Debug: Print document structure
            println("MultiMovies: Document title - ${document.title()}")
            println("MultiMovies: Body content length - ${document.body().text().length}")
            
            // Try multiple selectors based on actual HTML
            val selectors = listOf(
                "#archive-content article.item",
                "article.item", 
                ".items article",
                "article",
                ".content .items .item",
                ".module .content .items .item"
            )
            
            var home = emptyList<SearchResponse>()
            
            for (selector in selectors) {
                val elements = document.select(selector)
                println("MultiMovies: Selector '$selector' found ${elements.size} elements")
                
                if (elements.isNotEmpty()) {
                    home = elements.mapNotNull { it.toSearchResult() }
                    if (home.isNotEmpty()) {
                        println("MultiMovies: Successfully parsed ${home.size} items with selector: $selector")
                        break
                    }
                }
            }
            
            if (home.isEmpty()) {
                println("MultiMovies: NO ITEMS FOUND - Document structure:")
                document.select("div, section, article").take(10).forEach {
                    println("MultiMovies: Found element: ${it.tagName()} classes: ${it.classNames()}")
                }
            }
            
            return newHomePageResponse(HomePageList(request.name, home))
            
        } catch (e: Exception) {
            println("MultiMovies: Error fetching homepage - ${e.message}")
            e.printStackTrace()
            return newHomePageResponse(HomePageList(request.name, emptyList()))
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        try {
            // Debug element structure
            println("MultiMovies: Processing element - ${this.tagName()}")
            println("MultiMovies: Element classes - ${this.classNames()}")
            
            // Try multiple title selectors
            val titleSelectors = listOf(
                "div.data h3 a",
                ".data h3 a", 
                "h3 a",
                ".title a",
                "a[href*='/movies/']",
                "a[href*='/tvshows/']",
                "a"
            )
            
            var title: String? = null
            var href: String? = null
            
            for (selector in titleSelectors) {
                val element = this.selectFirst(selector)
                if (element != null) {
                    title = element.text()?.trim()
                    href = element.attr("href")
                    if (!title.isNullOrBlank() && !href.isNullOrBlank()) {
                        println("MultiMovies: Found title '$title' with selector '$selector'")
                        break
                    }
                }
            }
            
            if (title.isNullOrBlank() || href.isNullOrBlank()) {
                println("MultiMovies: No valid title/href found, skipping element")
                return null
            }
            
            href = fixUrl(href)
            println("MultiMovies: Title - $title, Href - $href")
            
            val posterUrl = fixUrlNull(
                this.selectFirst("div.poster img")?.attr("src")
                ?: this.selectFirst("div.image img")?.attr("src")
                ?: this.selectFirst(".poster img")?.attr("src")
                ?: this.selectFirst(".image img")?.attr("src")
                ?: this.selectFirst("img")?.attr("src")
            )
            println("MultiMovies: Poster URL - $posterUrl")
            
            val quality = getQualityFromString(this.select("div.mepo span.quality, span.quality, .quality").text())
            val type = this.select("span.item_type, .item_type").text()
            
            return if (href.contains("/movies/") || type.contains("Movie")) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.quality = quality
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.quality = quality
                }
            }
            
        } catch (e: Exception) {
            println("MultiMovies: Error processing element - ${e.message}")
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val multiMoviesAPI = "https://multimovies.agency"
        val url = "$multiMoviesAPI/?s=$query"
        println("MultiMovies: Search URL - $url")
        
        try {
            val document = app.get(url, headers = headers).document
            println("MultiMovies: Search document title - ${document.title()}")
            
            // Try multiple search result selectors
            val searchSelectors = listOf(
                "div.result-item",
                "article.item",
                ".search-results article",
                "article",
                ".search-item"
            )
            
            var results = emptyList<SearchResponse>()
            
            for (selector in searchSelectors) {
                val elements = document.select(selector)
                println("MultiMovies: Search selector '$selector' found ${elements.size} elements")
                
                if (elements.isNotEmpty()) {
                    results = elements.mapNotNull { it.toSearchResult() }
                    if (results.isNotEmpty()) {
                        println("MultiMovies: Successfully parsed ${results.size} search results")
                        break
                    }
                }
            }
            
            if (results.isEmpty()) {
                println("MultiMovies: NO SEARCH RESULTS FOUND for query: $query")
            }
            
            return results
            
        } catch (e: Exception) {
            println("MultiMovies: Error in search - ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }

    private suspend fun getEmbed(postid: String?, nume: String, referUrl: String?): NiceResponse {
        val body = FormBody.Builder()
            .addEncoded("action", "doo_player_ajax")
            .addEncoded("post", postid.toString())
            .addEncoded("nume", nume)
            .addEncoded("type", "movie")
            .build()
        
        val multiMoviesAPI = (getDomains()?.multiMovies ?: mainUrl).removeSuffix("/")
        return app.post(
            "$multiMoviesAPI/wp-admin/admin-ajax.php",
            requestBody = body,
            referer = referUrl
        )
    }

    data class TrailerUrl(
        @JsonProperty("embed_url") var embedUrl: String?,
        @JsonProperty("type") var type: String?
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers).document
        val titleL = doc.selectFirst("div.sheader > div.data > h1")?.text()?.trim() ?: return null
        val titleRegex = Regex("(^.*\\)\\d*)")
        val titleClean = titleRegex.find(titleL)?.groups?.get(1)?.value.toString()
        val title = if (titleClean == "null") titleL else titleClean
        val poster = fixUrlNull(
            doc.selectFirst("div.sheader div.poster img")?.attr("src")
            ?: doc.selectFirst("div.poster img")?.attr("src")
            ?: doc.selectFirst(".wp-post-image")?.attr("src")
            ?: doc.selectFirst("img")?.attr("src")
        )
        val tags = doc.select("div.sgeneros > a").map { it.text() }
        val year = doc.selectFirst("span.date")?.text()?.substringAfter(",")?.trim()?.toInt()
        val description = doc.selectFirst("#info div.wp-content p")?.text()?.trim()
        val type = if (url.contains("tvshows")) TvType.TvSeries else TvType.Movie
        val trailerRegex = Regex("\"http.*\"")

        var trailer: String? = if (type == TvType.Movie) {
            try {
                val postId = doc.select("#player-option-trailer").attr("data-post")
                val embedResponse = getEmbed(postId, "trailer", url)
                val parsed = embedResponse.parsed<TrailerUrl>()
                parsed.embedUrl?.let { fixUrlNull(it) }
            } catch (e: Exception) {
                null
            }
        } else {
            val iframeSrc = doc.select("iframe.rptss").attr("src")
            fixUrlNull(iframeSrc)
        }
        trailer = trailer?.let { trailerRegex.find(it)?.value?.trim('"') }
        val rating = doc.select("span.dt_rating_vgs").text().toRatingInt()
        val duration =
            doc.selectFirst("span.runtime")?.text()?.removeSuffix(" Min.")?.trim()
                ?.toInt()
        val actors =
            doc.select("div.person").map {
                ActorData(
                    Actor(
                        it.select("div.data > div.name > a").text(),
                        it.select("div.img > a > img").attr("src")
                    ),
                    roleString = it.select("div.data > div.caracter").text(),
                )
            }
        val recommendations = doc.select("#dtw_content_related-2 article").mapNotNull {
            it.toSearchResult()
        }

        val episodes = ArrayList<Episode>()
        doc.select("#seasons ul.episodios").mapIndexed { seasonNum, me ->
            me.select("li").mapIndexed { epNum, it ->
                episodes.add(
                    newEpisode(it.select("div.episodiotitle > a").attr("href"))
                    {
                        this.name = it.select("div.episodiotitle > a").text()
                        this.season = seasonNum + 1
                        this.episode = epNum + 1
                        this.posterUrl = it.select("div.imagen > img").attr("data-src") ?: it.select("div.imagen > img").attr("src")
                    }
                )
            }
        }

        return if (type == TvType.Movie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster?.trim()
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster?.trim()
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
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
        val req = app.get(data, headers = headers).document
        req.select("ul#playeroptionsul li").map {
            Triple(
                it.attr("data-post"),
                it.attr("data-nume"),
                it.attr("data-type")
            )
        }.amap { (id, nume, type) ->
            if (!nume.contains("trailer")) {
                val multiMoviesAPI = (getDomains()?.multiMovies ?: mainUrl).removeSuffix("/")
                val source = app.post(
                    url = "$multiMoviesAPI/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to id,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = multiMoviesAPI,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).parsed<ResponseHash>().embed_url
                val link = source.substringAfter("\"").substringBefore("\"").trim()
                when {
                    !link.contains("youtube") -> {
                        if (link.contains("deaddrive.xyz")) {
                            app.get(link).document.select("ul.list-server-items > li").map {
                                val server = it.attr("data-video")
                                loadExtractor(server, referer = multiMoviesAPI, subtitleCallback, callback)
                            }
                        } else
                            loadExtractor(link, referer = multiMoviesAPI, subtitleCallback, callback)
                    }

                    else -> return@amap
                }
            }
        }
        return true
    }

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("type") val type: String? = null,
    )


    data class DomainsParser(
        @JsonProperty("MultiMovies")
        val multiMovies: String,
    )
}