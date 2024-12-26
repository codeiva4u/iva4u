package com.Phisher98

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class HDMovies4uProvider : MainAPI() {
    override var mainUrl = "https://hdmovies4u.cx"
    override var name = "HDMovies4u"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/1080p-movies/" to "1080p Movies",
        "$mainUrl/category/720p-movies/" to "720p Movies",
        "$mainUrl/category/bollywood-720p/" to "Bollywood 720p",
        "$mainUrl/category/hollywood-movies-720p/" to "Hollywood Movies 720p",
        "$mainUrl/category/dual-audio-720p/" to "Dual Audio 720p",
        "$mainUrl/category/tv-shows/" to "TV Shows"
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
        val quality = getQualityFromString(this.select("span.absolute").text().toString())

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
        val trailer: String? = null // Explicitly set trailer to null
        val rating = document.selectFirst("a[href*=imdb]")?.text()?.substringAfter("Ratings: ")?.toRatingInt()
        val duration = null
        val actors = null
        val recommendations = document.select("div.pt-4 > div.w-40").mapNotNull {
            it.toSearchResult()
        }

        return if (type == TvType.Movie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
                trailer?.let { addTrailer(it, null) } // Explicitly call addTrailer with String?
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, arrayListOf()) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
                trailer?.let { addTrailer(it, null) } // Explicitly call addTrailer with String?
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

        document.select("main.page-body p a[href*=drivetot]").map {
            val link = it.attr("href")
            loadExtractor(link, data, subtitleCallback, callback)
        }
        document.select("main.page-body iframe[src*=vanoe]").map {
            val link = it.attr("src")
            loadExtractor(link, data, subtitleCallback, callback)
        }
        return true
    }

    private fun getSearchQualityFromString(text: String?): SearchQuality? {
        return when {
            text.isNullOrBlank() -> null
            text.contains("2160", ignoreCase = true) -> SearchQuality.UHD
            text.contains("1080", ignoreCase = true) -> SearchQuality.HD
            text.contains("720", ignoreCase = true) -> SearchQuality.HD
            text.contains("480", ignoreCase = true) -> SearchQuality.SD
            else -> null
        }
    }
    private fun getQualityFromString(text: String?): SearchQuality? {
        return when {
            text.isNullOrBlank() -> null
            text.contains("2160", ignoreCase = true) -> SearchQuality.UHD
            text.contains("1080", ignoreCase = true) -> SearchQuality.HD
            text.contains("720", ignoreCase = true) -> SearchQuality.HD
            text.contains("480", ignoreCase = true) -> SearchQuality.SD
            else -> null
        }
    }

    fun fixUrl(url: String?): String {
        if (url == null) return ""
        return if (url.startsWith("http")) {
            url
        } else {
            val regex = Regex("^(//)(.*)")
            val matchResult = regex.find(url)
            if (matchResult != null) {
                "https:" + matchResult.groupValues[0]
            } else {
                "https://$url"
            }
        }
    }

    fun fixUrlNull(url: String?): String? {
        if (url == null) return null
        return if (url.startsWith("http")) {
            url
        } else {
            val regex = Regex("^(//)(.*)")
            val matchResult = regex.find(url)
            if (matchResult != null) {
                "https:" + matchResult.groupValues[0]
            } else {
                "https://$url"
            }
        }
    }
}

data class TrailerUrl(
    @JsonProperty("embed_url") val embed_url: String?,
    @JsonProperty("type") val type: String?
)