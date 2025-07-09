package com.phisher98

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MultiMoviesProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://multimovies.agency"
    override var name = "MultiMovies"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/trending/" to "Trending",
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/series/" to "TV Shows",
        "$mainUrl/genre/bollywood-movies/" to "Bollywood Movies",
        "$mainUrl/genre/hollywood/" to "Hollywood Movies",
        "$mainUrl/genre/south-indian/" to "South Indian Movies",
        "$mainUrl/genre/punjabi/" to "Punjabi Movies",
        "$mainUrl/genre/netflix/" to "Netflix",
        "$mainUrl/genre/disney-hotstar/" to "Disney Hotstar",
        "$mainUrl/genre/sony-liv/" to "Sony Liv",
        "$mainUrl/genre/amazon-prime/" to "Amazon Prime",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = if (page == 1) {
            app.get(request.data).document
        } else {
            app.get(request.data + "/page/$page/").document
        }

        val home = document.select("div.items > article, #archive-content > article").mapNotNull {
            it.toSearchResult()
        }
        // FIX: Replaced deprecated HomePageResponse constructor with newHomePageResponse builder
        return newHomePageResponse(HomePageList(request.name, home), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.data > h3 > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("div.data > h3 > a")?.attr("href").toString())
        val posterUrl = fixUrlNull(this.selectFirst("div.poster > img")?.attr("src"))
        val quality = getQualityFromString(this.select("div.poster > div.mepo > span").text())

        return if (href.contains("/movies/")) {
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
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document

        return document.select("div.result-item").mapNotNull {
            val title = it.selectFirst("div.details > .title > a")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl(it.selectFirst("div.details > .title > a")?.attr("href").toString())
            val posterUrl = fixUrlNull(it.selectFirst("div.image img")?.attr("src"))
            val typeText = it.selectFirst("div.image span.type")?.text() ?: ""

            if (typeText.contains("Movie", ignoreCase = true)) {
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

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("div.sheader div.data > h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(doc.selectFirst("div.poster > img")?.attr("src"))
        val bgposter = fixUrlNull(doc.selectFirst("div.g-item img")?.attr("data-src"))
        val tags = doc.select("div.sgeneros > a").map { it.text() }
        val year = doc.selectFirst("span.date")?.text()?.substringAfterLast(',')?.trim()?.toIntOrNull()
        val description = doc.selectFirst("#info div.wp-content p")?.text()?.trim()
        val type = if (url.contains("/tvshows/") || url.contains("/series/")) TvType.TvSeries else TvType.Movie
        val trailerUrl = fixUrlNull(doc.selectFirst("iframe.rptss")?.attr("src"))
        val rating = doc.select("span.dt_rating_vgs").text().toRatingInt()
        val duration = doc.selectFirst("span.runtime")?.text()?.removeSuffix(" Min.")?.trim()?.toIntOrNull()
        val actors = doc.select("div.person").map {
            ActorData(
                Actor(
                    it.select("div.data .name a").text(),
                    it.select("div.img img").attr("src")
                ),
                roleString = it.select("div.data .caracter").text(),
            )
        }
        val recommendations = doc.select("#dtw_content_related-2 article").mapNotNull {
            it.toSearchResult()
        }

        return if (type == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgposter
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
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgposter
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailerUrl)
                // FIX: Replaced deprecated Episode constructor with newEpisode builder
                episodes = doc.select("ul.episodios > li").mapNotNull {
                    val href = it.selectFirst("div.episodiotitle > a")?.attr("href") ?: return@mapNotNull null
                    newEpisode(href) {
                        name = it.selectFirst("div.episodiotitle > a")?.text()
                        season = it.selectFirst(".numerando")?.text()?.filter { c -> c.isDigit() }?.toIntOrNull()
                        episode = it.selectFirst(".numerando")?.text()?.substringAfter("x")?.filter { c -> c.isDigit() }?.toIntOrNull()
                        posterUrl = it.selectFirst("div.imagen > img")?.attr("src")
                    }
                }
            }
        }
    }

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("type") val type: String? = null,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val playerItems = doc.select("ul#playeroptionsul li")

        // FIX: Replaced deprecated `apmap` with `amap` for non-blocking concurrency
        playerItems.amap { item ->
            val postId = item.attr("data-post")
            val nume = item.attr("data-nume")
            val type = item.attr("data-type")

            if (!nume.contains("trailer", ignoreCase = true)) {
                try {
                    val sourceResponse = app.post(
                        url = "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to postId,
                            "nume" to nume,
                            "type" to type
                        ),
                        referer = data,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).parsed<ResponseHash>()

                    val embedUrl = sourceResponse.embed_url
                    val link = Regex("""src=["'](.*?)["']""").find(embedUrl)?.groupValues?.get(1) ?: embedUrl

                    if (!link.contains("youtube.com")) {
                        loadExtractor(link, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    Log.e("MultiMoviesProvider", "Error loading links", e)
                }
            }
        }
        return true
    }
}