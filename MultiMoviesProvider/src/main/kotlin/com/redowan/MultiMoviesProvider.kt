package com.redowan

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MultimoviesProvider : MainAPI() {
    override var mainUrl = "https://multimovies.lat"
    override var name = "Multimovies"
    override val hasMainPage = true
    override var lang = "hi" // हिन्दी
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/genero/accion/" to "Acción",
        "$mainUrl/genero/comedia/" to "Comedia",
        "$mainUrl/genero/drama/" to "Drama",
        "$mainUrl/genero/ciencia-ficcion/" to "Ciencia Ficción",
        "$mainUrl/genero/terror/" to "Terror",
        "$mainUrl/genero/animacion/" to "Animación",
        "$mainUrl/genero/suspenso/" to "Suspenso",
        "$mainUrl/genero/romance/" to "Romance",
        "$mainUrl/genero/aventura/" to "Aventura",
        "$mainUrl/genero/fantasia/" to "Fantasia"

        // ... add more categories if needed
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}page/$page/").document // Add pagination to URL
        val items = doc.select("article.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.poster img")?.attr("alt") ?: return null
        val href = this.selectFirst("div.poster a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("div.poster img")?.attr("src")
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }


    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=$query").document // Updated search URL
        return doc.select("article.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.name-film")?.text() ?: ""
        val poster = doc.selectFirst("div.poster-film img")?.attr("src")
        val year = doc.select("ul.list-description li:contains(Año) span").text().toIntOrNull()
        val description = doc.selectFirst("blockquote.sinopsis-film")?.text()
        val actors = doc.select("ul.list-casts li a").map { it.text().trim() }
        doc.select("ul.list-description li:contains(Director) span").text()
        val isMovie = doc.select("ul.list-episode li").isEmpty()

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.actors = actors.map { ActorData(Actor(it)) }
                addActors(actors.map { Actor(it) })
                this.recommendations = doc.select("article.item").mapNotNull { it.toSearchResult() }
            }
        } else {
            val episodes = doc.select("ul.list-episode li").mapNotNull {
                val href = it.select("a").attr("href")
                val name = it.select("a").text()
                Episode(
                    data = href,
                    name = name
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.actors = actors.map { ActorData(Actor(it)) }
                addActors(actors.map { Actor(it) })
                this.recommendations = doc.select("article.item").mapNotNull { it.toSearchResult() }
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

        // Find the ul#videoLinks element
        val videoLinks = doc.selectFirst("ul#videoLinks")

        // Extract direct download links and streaming links
        videoLinks?.select("li")?.forEach { li ->
            // Check for direct download links
            li.select("a.dlvideoLinks").firstOrNull()?.attr("href")?.let { link ->
                callback.invoke(
                    ExtractorLink(
                        name,
                        name,
                        link,
                        mainUrl,
                        quality = getQualityFromName(link),
                        isM3u8 = link.contains(".m3u8") || link.contains(".mp4"),
                        headers = mapOf("Referer" to mainUrl)
                    )
                )
            }

            // Check for streaming links using data-link attribute
            li.attr("data-link").let { link ->
                if (link.isNotBlank()) {
                    // Use loadExtractor to process the streaming link
                    loadExtractor(link, data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}