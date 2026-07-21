package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class TopmoviesProvider : MainAPI() {
    override var mainUrl = "https://topmovies.pet"
    override var name = "TopMovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

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
                    jsonObject.optString("topmovies")
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Home",
        "$mainUrl/movies/south-movies/page/%d/" to "South Movies",
        "$mainUrl/movies/movies/bollywood/page/%d/" to "Bollywood Movies",
        "$mainUrl/web-series/tv-shows-by-network/netflix/page/%d/" to "Netflix Web Series",
        "$mainUrl/web-series/tv-shows-by-network/amazon-prime-video/page/%d/" to "Amazon Prime Web Series",
        "$mainUrl/web-series/tv-shows-by-network/zee5/page/%d/" to "Zee5 Web Series",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = fixUrl(request.data.format(page))
        val document = app.get(url).document
        val home = document.select("div.movies-list > div.movie-item, article.latestPost").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select("a").attr("title").ifBlank { this.select("a").text() }.replace("Download ", "")
        val rawHref = this.select("a").attr("href")
        if (rawHref.isBlank()) return null
        val href = fixUrl(rawHref)
        val posterUrl = fixUrlNull(this.select("img").attr("src").ifBlank { this.select("img").attr("data-src") })
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().replace(" ", "+")
        val document = app.get("$mainUrl/?s=$cleanQuery").document
        return document.select("div.movies-list > div.movie-item, article.latestPost, article.post-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fullUrl = fixUrl(url)
        val document = app.get(fullUrl).document
        val title = document.selectFirst("title")?.text()?.replace("Download ", "").toString()
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content").toString()
        val description = document.selectFirst("span#summary")?.text().toString()
        val imdbUrl = document.selectFirst("div.imdb_left > a")?.attr("href")

        val tvtype = if (title.contains("Series") || url.contains("web-series")) "series" else "movie"

        val episodes = mutableListOf<Episode>()
        val episodeLinks = document.select("a.maxbutton-download-links, a.dl, a.btnn")

        for (button in episodeLinks) {
            try {
                var link = button.attr("href")
                if (link.contains("?id=") && !link.contains("fastdlserver")) {
                    val id = link.substringAfterLast("id=")
                    link = bypass(id) ?: continue
                }
                episodes.add(newEpisode(link) {
                    this.name = button.text()
                })
            } catch (_: Exception) {}
        }

        return if (tvtype == "series") {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = description
                addImdbUrl(imdbUrl)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes) {
                this.posterUrl = posterUrl
                this.plot = description
                addImdbUrl(imdbUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = data
        if (link.contains("gdflix") || link.contains("gdlink")) {
            GDFlix().getUrl(link, "", subtitleCallback, callback)
        } else if (link.contains("fastdlserver")) {
            fastdlserver().getUrl(link, "", subtitleCallback, callback)
        } else {
            loadExtractor(link, "", subtitleCallback, callback)
        }
        return true
    }
}
