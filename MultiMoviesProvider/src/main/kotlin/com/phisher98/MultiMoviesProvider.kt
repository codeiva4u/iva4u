package com.phisher98

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MultiMoviesProvider : MainAPI() {
    override var mainUrl = "https://multimovies.agency"
    override var name = "MultiMovies"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "/" to "Latest Movies",
        "/category/bollywood/" to "Bollywood",
        "/category/hollywood/" to "Hollywood",
        "/category/web-series/" to "Web Series",
        "/category/south-indian-movies/" to "South Indian Movies",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}page/$page/"
        }
        val document = app.get(url).document
        val home = document.select("article.post").mapNotNull {
            toSearchResult(it)
        }
        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val title = element.selectFirst("h2.entry-title a")?.text() ?: return null
        val href = element.selectFirst("h2.entry-title a")?.attr("href") ?: return null
        val posterUrl = element.selectFirst("div.post-thumbnail img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article.post").mapNotNull {
            toSearchResult(it)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text() ?: return null
        val posterUrl = document.selectFirst("div.post-thumbnail img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val plot = document.select("div.entry-content > p").joinToString("\n") { it.text() }
        val year = Regex("(\\d{4})").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val tags = document.select("span.cat-links a").map { it.text() }

        val isTvSeries = tags.any { it.contains("Series", ignoreCase = true) } ||
                url.contains("series", ignoreCase = true) ||
                url.contains("season", ignoreCase = true)

        if (isTvSeries) {
            val episodes = document.select("div.entry-content > p").mapNotNull { p ->
                val links = p.select("a")
                if (links.isEmpty()) return@mapNotNull null

                val episodeLinks = links.joinToString(",") { it.attr("href") }
                val episodeTitle = p.text()

                newEpisode(episodeLinks) {
                    name = episodeTitle
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains(",")) {
            // It's a list of episode links
            data.split(",").forEach { link ->
                loadExtractor(link, mainUrl, subtitleCallback, callback)
            }
        } else {
            // It's a single movie link
            val document = app.get(data).document
            val links = document.select("div.entry-content a[href*='.mkv'], div.entry-content a[href*='.mp4']")
                .mapNotNull { it.attr("href") }

            links.forEach { link ->
                loadExtractor(link, data, subtitleCallback, callback)
            }
            return links.isNotEmpty()
        }
        return true
    }
}