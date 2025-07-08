package com.phisher98

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromString
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MultiMoviesProvider : MainAPI() {
    override var mainUrl = "https://multimovies.agency/" // Fallback URL
    override var name = "MultiMovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    // This companion object is great for fetching the domain dynamically.
    companion object {
        private const val DOMAINS_URL = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
        private var currentDomain: String? = null

        suspend fun getDomain(): String {
            if (currentDomain == null) {
                try {
                    val domains = app.get(DOMAINS_URL).parsedSafe<DomainsParser>()
                    currentDomain = domains?.multiMovies
                } catch (e: Exception) {
                    // In case of error, use the fallback
                    currentDomain = "https://multimovies.boutique"
                }
            }
            return currentDomain!!
        }
    }

    override val mainPage = mainPageOf(
        "movies/" to "Latest Release",
        "genre/bollywood-movies/" to "Bollywood Movies",
        "genre/hollywood/" to "Hollywood Movies",
        "genre/south-indian/" to "South Indian Movies",
        "genre/punjabi/" to "Punjabi Movies",
        "genre/amazon-prime/" to "Amazon Prime",
        "genre/disney-hotstar/" to "Disney Hotstar",
        "genre/jio-ott/" to "Jio OTT",
        "genre/netflix/" to "Netflix",
        "genre/sony-liv/" to "Sony Live",
        "genre/zee-5/" to "Zee5",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val domain = getDomain()
        val url = if (page == 1) {
            "$domain/${request.data}"
        } else {
            "$domain/${request.data}page/$page/"
        }
        val document = app.get(url).document

        // Correct, robust selector that works on homepage and archive pages
        val home = document.select("div#archive-content article, div.items article").mapNotNull {
            it.toSearchResult()
        }
        
        // Correct return statement
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("div.data > h3 > a") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        val posterUrl = this.selectFirst("div.poster > img")?.attr("src")
        
        // Correct, case-insensitive check for movie/tvshow
        val isMovie = href.contains("/movies/", ignoreCase = true)

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
        val domain = getDomain()
        // Using the more efficient JSON search API
        val response = app.get("$domain/wp-json/dooplay/search/?s=$query").parsed<List<SearchAPIResponse>>()
        return response.mapNotNull {
            // The API doesn't specify type, so we guess from URL
            val isMovie = it.url.contains("/movies/", ignoreCase = true)
            if (isMovie) {
                newMovieSearchResponse(it.title, it.url, TvType.Movie) {
                    posterUrl = it.img
                }
            } else {
                 newTvSeriesSearchResponse(it.title, it.url, TvType.TvSeries) {
                    posterUrl = it.img
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("div.data h1")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.wp-content p")?.text()?.trim()
        val tags = document.select("div.sgeneros a").map { it.text() }
        val year = document.selectFirst("span.date")?.text()?.split(".")?.last()?.trim()?.toIntOrNull()
        
        val actors = document.select("div.person[itemprop=actor]").map {
            Actor(
                it.select("meta[itemprop=name]").attr("content"),
                it.select("img").attr("src")
            )
        }

        val recommendations = document.select("div#single_relacionados article a").mapNotNull {
            val recTitle = it.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
            val recHref = it.attr("href")
            val recPosterUrl = it.selectFirst("img")?.attr("src")
            newMovieSearchResponse(recTitle, recHref, TvType.Movie) {
                this.posterUrl = recPosterUrl
            }
        }

        val tvType = if (url.contains("/tvshows/", ignoreCase = true)) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("ul.episodios li").mapNotNull {
                val href = it.selectFirst("a")!!.attr("href")
                val name = it.selectFirst(".episodiotitle a")?.text()
                val season = it.selectFirst(".numerando")?.text()?.trim()?.substringBefore(" -")?.toIntOrNull()
                val episode = it.selectFirst(".numerando")?.text()?.trim()?.substringAfter("- ")?.toIntOrNull()
                val image = it.selectFirst("img")?.attr("src")

                newEpisode(href) {
                    this.name = name
                    this.season = season
                    this.episode = episode
                    this.posterUrl = image
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = actors
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = actors
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val domain = getDomain()
        val document = app.get(data).document
        document.select("ul#playeroptionsul li.dooplay_player_option").amap {
            if (it.id() != "player-option-trailer") {
                val post = it.attr("data-post")
                val nume = it.attr("data-nume")
                val type = it.attr("data-type")
                
                val ajaxUrl = "$domain/wp-admin/admin-ajax.php"
                val response = app.post(
                    ajaxUrl,
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).parsed<PlayerAjaxResponse>()
                
                if (response.embed_url.isNotBlank()) {
                    loadExtractor(response.embed_url, domain, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    data class DomainsParser(
        @JsonProperty("MultiMovies") val multiMovies: String,
    )

    data class PlayerAjaxResponse(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("type") val type: String?,
    )
    
     data class SearchAPIResponse(
        @JsonProperty("title") val title: String,
        @JsonProperty("url") val url: String,
        @JsonProperty("img") val img: String?
    )
}