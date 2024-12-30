package com.redowan

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Hdmovies4u : MainAPI() {
    override var mainUrl = "https://hdmovies4u.cx"
    override var name = "Hdmovies4u"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/hollywood-movies-1080p/" to "Hollywood Movies",
        "$mainUrl/category/south-hindi-dubbed-720p/" to "South Hindi Dubbed Movies",
        "$mainUrl/category/bollywood-1080p/" to "Bollywood",
        "$mainUrl/category/netflix/" to "Netflix",
        "$mainUrl/category/amazon-prime-video/" to "Amazon Prime Video",
        "$mainUrl/category/disney-plus-hotstar/" to "Disney+ Hotstar",
        "$mainUrl/category/jio-cinema/" to " Jio Cinema",
        "$mainUrl/category/zee5/" to "Zee5",
        "$mainUrl/category/sonyliv/" to "SonyLIV",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = if (page == 1) {
            app.get(request.data).document
        } else {
            app.get("${request.data}page/$page/").document
        }

        val home = document.select("section.text-center > div.gridxw").mapNotNull {
            it.toSearchResult()
        }

        return HomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.mt-2 a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("div.mt-2 a")?.attr("href").toString())
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val quality = this.select("span.absolute").text().trim().let { getQualityFromString(it) }

        return if (href.contains("tvshows", ignoreCase = true)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document

        return document.select("div.gridxw").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun extractEpisodeLinks(document: org.jsoup.nodes.Document): List<String> {
        // Extract episode links from the document
        return document.select("a.episode-link").map { it.attr("abs:href") }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.text-gray-500")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("p.poster img")?.attr("src"))
        val tags = document.select("div.page-meta a").map { it.text() }
        val year = document.select("span.text-blue-600")
            .firstOrNull()
            ?.text()
            ?.trim()
            ?.substringAfter(",")
            ?.trim()
            ?.toIntOrNull()
        val description = document.selectFirst("main.page-body p.seoone")?.text()?.trim()
        val type = if (url.contains("tvshows", ignoreCase = true)) TvType.TvSeries else TvType.Movie
        val trailer: String? = null
        val rating = document.selectFirst("a[href*=imdb]")?.text()?.substringAfter("Ratings: ")
            ?.toRatingInt()
        val duration = null
        val actors = null
        val recommendations = document.select("div.pt-4 > div.w-40").mapNotNull {
            it.toSearchResult()
        }

        if (type == TvType.Movie) {
            // Extract movie links
            val movieLinks = extractEpisodeLinks(document)

            // Create EpisodeLink objects
            val movieData = movieLinks.map { link ->
                EpisodeLink(source = link)
            }

            // Encode data to JSON
            val movieJsonData = movieData.toJson()

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                movieJsonData
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
                trailer?.let { addTrailer(it, null) }
            }
        } else {
            // Extract episode links
            val episodeLinks = extractEpisodeLinks(document)

            // Create EpisodeLink objects
            val data = episodeLinks.map { link ->
                EpisodeLink(source = link)
            }

            // Encode data to JSON
            data.toJson()

            // Extract episode details
            val episodes = episodeLinks.mapIndexed { index, link ->
                Episode(
                    link,
                    "Episode ${index + 1}",
                    season = 1,
                    episode = index + 1,
                    posterUrl = null
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
                trailer?.let { addTrailer(it, null) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = parseJson<ArrayList<EpisodeLink>>(data)
        sources.amap {
            val source = it.source
            loadExtractor(source, subtitleCallback, callback)
        }
        return true
    }

    // Define the EpisodeLink class
    data class EpisodeLink(
        val source: String
    )
}