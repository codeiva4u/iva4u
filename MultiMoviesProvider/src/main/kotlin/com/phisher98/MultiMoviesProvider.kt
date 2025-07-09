package com.phisher98

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MultiMoviesProvider : MainAPI() {
    override var name = "MultiMovies"
    override var mainUrl = "https://multimovies.agency"
    override var lang = "hi"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )
    override val mainPage = mainPageOf(
        "movies/" to "Latest Release",
        "genre/bollywood-movies/" to "Bollywood Movies",
        "genre/hollywood/" to "Hollywood Movies",
        "genre/south-indian/" to "South Indian Movies",
        "genre/punjabi/" to "Punjabi Movies",
        "genre/amazon-prime/" to "Amazon Prime",
        "genre/disney-hotstar/" to "Disney Hotstar",
        "genre/jio-ott/" to "Jio OTT",
        "genre/netflix/" to "Netfilx",
        "genre/sony-liv/" to "Sony Live",
        "genre/zee-5/" to "Zee5",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val allLists = ArrayList<HomePageList>()

        val movies = document.select("div#dt-movies article.item").mapNotNull {
            it.toSearchResponse()
        }
        if (movies.isNotEmpty()) {
            allLists.add(HomePageList("Movies", movies))
        }

        val series = document.select("div#dt-tvshows article.item").mapNotNull {
            it.toSearchResponse()
        }
        if (series.isNotEmpty()) {
            allLists.add(HomePageList("Web Series", series))
        }

        if (allLists.isEmpty()) throw ErrorLoadingException("No data found on homepage")
        return newHomePageResponse(allLists)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        val href = linkTag.attr("href")
        val title = linkTag.attr("title").ifBlank { this.selectFirst("h3 a")?.text() } ?: return null
        val poster = this.selectFirst("img")?.attr("src")
        val yearText = this.selectFirst("div.data > span")?.text()
        val year = yearText?.split(". ")?.last()?.trim()?.toIntOrNull()

        val isMovie = this.className().contains("movies") || this.selectFirst("span.movies") != null

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("div.result-item article").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("div.data h1")?.text() ?: return null
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.wp-content")?.text()
        val year = document.selectFirst("span.date")?.text()?.split(". ")?.last()?.toIntOrNull()
        val tags = document.select("div.sgeneros a").map { it.text() }
        val rating = document.selectFirst("span.dt_rating_vgs")?.text()?.toRatingInt()
        val recommendations = document.select("div#single_relacionados article a").mapNotNull {
            it.toSearchResponse()
        }
        val isMovie = document.selectFirst("div.pag_episodes") == null

        return if (isMovie) {
            newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = url
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating
                this.recommendations = recommendations
            }
        } else {
            val episodes = document.select("ul.episodios li").mapNotNull { episodeElement ->
                val href = episodeElement.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epName = episodeElement.selectFirst("a")?.text()
                val epPoster = episodeElement.selectFirst("img")?.attr("src")
                val seasonEpisode = episodeElement.selectFirst(".numerando")?.text()?.split("-")
                val season = seasonEpisode?.getOrNull(0)?.trim()?.toIntOrNull()
                val episode = seasonEpisode?.getOrNull(1)?.trim()?.toIntOrNull()

                newEpisode(href) {
                    this.name = epName
                    this.season = season
                    this.episode = episode
                    this.posterUrl = epPoster
                }
            }.reversed()

            newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating
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
        val document = app.get(data).document
        val embedUrl = document.selectFirst("iframe.metaframe")?.attr("src") ?: return false

        return loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
    }
}