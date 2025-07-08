package com.phisher98 // You can change this to your package name

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Multimovies : MainAPI() {

    override var mainUrl = "https://multimovies.agency"
    override var name = "Multimovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "movies" to "Movies",
        "tvshows" to "TV Shows",
        "genre/bollywood-movies" to "Bollywood Movies",
        "genre/hollywood" to "Hollywood",
        "genre/south-indian" to "South Indian",
        "genre/anime-hindi" to "Anime (Hindi)"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}/"
        } else {
            "$mainUrl/${request.data}/page/$page/"
        }
        val document = app.get(url).document
        val home = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3 a")?.text() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val posterUrl = this.selectFirst("img")?.attr("src")
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
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article.item, div.result-item").mapNotNull {
            val title = it.selectFirst(".title a, h3 a")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("a")!!.attr("href")
            val posterUrl = it.selectFirst("img")?.attr("src")
            val isMovie = href.contains("/movies/")

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

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("div.data h1")?.text()?.trim() ?: "No Title"
        val posterUrl = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.wp-content p")?.text()?.trim()
        val tags = document.select("div.sgeneros a").map { it.text() }
        val year = document.selectFirst("span.date")?.text()?.split(".")?.last()?.trim()?.toIntOrNull()
        val trailer = document.selectFirst("li#player-option-trailer")?.attr("data-nume")

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

        val tvType = if (url.contains("/tvshows/")) TvType.TvSeries else TvType.Movie

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
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                addActors(actors)
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
        // If data is a full URL, we are loading links for a movie or an episode
        if (data.startsWith("http")) {
            val doc = app.get(data).document
            doc.select("ul#playeroptionsul li.dooplay_player_option").amap {
                // Skip trailer
                if (it.id() != "player-option-trailer") {
                    val post = it.attr("data-post")
                    val nume = it.attr("data-nume")
                    val type = it.attr("data-type")
                    
                    val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
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
                        loadExtractor(response.embed_url, mainUrl, subtitleCallback, callback)
                    }
                }
            }
        }
        return true
    }

    data class PlayerAjaxResponse(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("type") val type: String?,
    )
}