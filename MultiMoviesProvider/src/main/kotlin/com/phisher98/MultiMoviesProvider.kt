package com.phisher98


import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class MultiMoviesProvider : MainAPI() { // all providers must be an instance of MainAPI
    // FIX 1: Changed the mainUrl to a working domain.
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
    
    // Helper function to handle lazy loaded images
    private fun Element.getImageUrl(): String? {
        return this.attr("data-src").ifBlank { this.attr("src") }.ifBlank { null }
    }

    override val mainPage = mainPageOf(
        "trending/" to "Trending",
        "genre/bollywood-movies/" to "Bollywood Movies",
        "genre/hollywood/" to "Hollywood Movies",
        "genre/south-indian/" to "South Indian Movies",
        "genre/punjabi/" to "Punjabi Movies",
        "genre/amazon-prime/" to "Amazon Prime",
        "genre/disney-hotstar/" to "Disney Hotstar",
        "genre/jio-ott/" to "Jio OTT",
        "genre/netflix/" to "Netflix",
        "genre/sony-liv/" to "Sony Live",
        "genre/k-drama/" to "KDrama",
        "genre/zee-5/" to "Zee5",
        "genre/anime-hindi/" to "Anime Series",
        "genre/anime-movies/" to "Anime Movies",
        "genre/cartoon-network/" to "Cartoon Network",
        "genre/disney-channel/" to "Disney Channel",
        "genre/hungama/" to "Hungama",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}" + "page/$page/"
        }
        val document = app.get(url).document
        
        // Updated selector based on actual HTML structure
        val home = document.select("#archive-content > article.item, div.items > article.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(HomePageList(request.name, home))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Get title from h3 inside data div
        val title = this.selectFirst("div.data h3 a")?.text()?.trim() 
            ?: this.selectFirst("div.data h3")?.text()?.trim() 
            ?: return null
        
        // Get href from the main article link or h3 link
        val href = this.selectFirst("div.data h3 a")?.attr("href") 
            ?: this.selectFirst("a")?.attr("href") 
            ?: return null
        val fixedHref = fixUrl(href)
        
        // Get poster image from div.poster img or div.image img
        val posterUrl = fixUrlNull(this.selectFirst("div.poster img, div.image img, div.thumbnail img")?.getImageUrl())
        
        // Get quality from mepo span
        val quality = getQualityFromString(this.select("div.mepo span").text())
        val type = if (fixedHref.contains("movie", true)) TvType.Movie else TvType.TvSeries

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, fixedHref, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newTvSeriesSearchResponse(title, fixedHref, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        
        // Search results can have different structures
        val searchResults = document.select("div.result-item article, article.item").mapNotNull {
            val title = it.selectFirst("div.title a")?.text()?.trim() 
                ?: it.selectFirst("div.data h3 a")?.text()?.trim() 
                ?: it.selectFirst("h3 a")?.text()?.trim()
                ?: return@mapNotNull null
            
            val href = it.selectFirst("div.title a")?.attr("href")
                ?: it.selectFirst("div.data h3 a")?.attr("href")
                ?: it.selectFirst("a")?.attr("href")
                ?: return@mapNotNull null
            val fixedHref = fixUrl(href)
            
            // Get poster from thumbnail or image div
            val posterUrl = fixUrlNull(
                it.selectFirst("div.poster img, div.image img, div.thumbnail img")?.getImageUrl()
            )
            
            val quality = getQualityFromString(it.select("div.mepo span, span.quality").text())
            val type = it.selectFirst("div.thumbnail span, span.type")?.text()
                ?: if (fixedHref.contains("movie", true)) "Movie" else "Series"
            
            if (type.contains("Movie", true) || fixedHref.contains("/movies/", true)) {
                newMovieSearchResponse(title, fixedHref, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.quality = quality
                }
            } else {
                newTvSeriesSearchResponse(title, fixedHref, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.quality = quality
                }
            }
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("div.sheader div.data > h1")?.text()?.trim() ?: return null
        
        // Get poster from div.poster img with proper src attribute handling
        val poster = fixUrlNull(
            doc.selectFirst("div.sheader div.poster img, div.poster img, div.thumbnail img")?.getImageUrl()
        )
        val tags = doc.select("div.sgeneros > a").map { it.text() }
        val year = doc.selectFirst("span.date")?.text()?.split(",")?.getOrNull(1)?.trim()?.toIntOrNull()
        val description = doc.selectFirst("#info div.wp-content p")?.text()?.trim()
        val type = if (doc.selectFirst("#seasons") != null) TvType.TvSeries else TvType.Movie
        
        val trailerUrl = doc.selectFirst("#player-option-trailer")?.attr("data-post")?.let { postId ->
            try {
                val embedResponse = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to postId,
                        "nume" to "trailer",
                        "type" to "movie"
                    ),
                    referer = url
                ).parsedSafe<TrailerUrl>()
                embedResponse?.embedUrl
            } catch (e: Exception) {
                null
            }
        }

        val rating = doc.select("span.dt_rating_vgs").text().toRatingInt()
        val duration = doc.selectFirst("span.runtime")?.text()?.removeSuffix(" Min.")?.trim()?.toIntOrNull()
        val actors = doc.select("div.person").map {
            ActorData(
                Actor(
                    it.select("div.data > div.name > a").text(),
                    it.selectFirst("div.img > a > img")?.getImageUrl()
                ),
                roleString = it.select("div.data > div.caracter").text(),
            )
        }
        val recommendations = doc.select("#single_relacionados article, #dtw_content_related-2 article, div.sbox.srelacionados article").mapNotNull {
            it.toSearchResult()
        }

        return if (type == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailerUrl)
            }
        } else {
            val episodes = doc.select("ul.episodios > li").mapNotNull {
                val epHref = it.selectFirst("div.episodiotitle > a")?.attr("href") ?: return@mapNotNull null
                newEpisode(epHref) {
                    this.name = it.selectFirst("div.episodiotitle > a")?.text()
                    // FIX 2: Use helper for episode poster
                    this.posterUrl = fixUrlNull(it.selectFirst("div.poster img, div.imagen > img, div.thumbnail img")?.getImageUrl())
                    this.season = it.parent()?.parent()?.selectFirst(".se-t")?.text()
                        ?.filter { c -> c.isDigit() }?.toIntOrNull()
                    this.episode = it.selectFirst(".numerando")?.text()?.split("x")?.getOrNull(1)
                        ?.trim()?.toIntOrNull()
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailerUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        doc.select("ul#playeroptionsul > li").not("[data-nume=trailer]").apmap {
            val postId = it.attr("data-post")
            val nume = it.attr("data-nume")
            val type = it.attr("data-type")

            val source = app.post(
                url = "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to postId,
                    "nume" to nume,
                    "type" to type
                ),
                referer = data,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<ResponseHash>()?.embed_url

            if (source != null) {
                // The embed_url is often a string containing an iframe, we need to extract the src
                val embedLink = Regex("src=\"(.*?)\"").find(source)?.groupValues?.get(1)
                if (embedLink != null) {
                    loadExtractor(embedLink, mainUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
    )

    data class TrailerUrl(
        @JsonProperty("embed_url") var embedUrl: String?,
        @JsonProperty("type") var type: String?
    )
    
    data class DomainsParser(
        @JsonProperty("MultiMovies")
        val multiMovies: String,
    )
}