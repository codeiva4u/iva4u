package com.phisher98

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller

class MultiMoviesProvider : MainAPI() {
    override var mainUrl = "https://multimovies.bond"
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
        val document = app.get(url, interceptor = CloudflareKiller()).document
        val home = document.select("div.items article.item").mapNotNull {
            toSearchResult(it)
        }
        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val title = element.selectFirst("div.data h3 a")?.text() ?: return null
        val href = element.selectFirst("div.poster a")?.attr("href") ?: return null
        val posterUrl = element.selectFirst("div.poster img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, interceptor = CloudflareKiller()).document
        return document.select("div.items article.item").mapNotNull {
            toSearchResult(it)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = CloudflareKiller()).document

        val title = document.selectFirst("h1.entry-title")?.text() ?: return null
        val posterUrl = document.selectFirst("div.sheader div.poster img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
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
            for (link in data.split(",")) {
                loadExtractor(link, mainUrl, subtitleCallback, callback)
            }
        } else {
            // It's a single movie link
            val document = app.get(data, interceptor = CloudflareKiller()).document
            val links = document.select("div.entry-content a[href*='.mkv'], div.entry-content a[href*='.mp4']")
                .mapNotNull { it.attr("href") }

            for (link in links) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
            return links.isNotEmpty()
        }
        return true
    }
}