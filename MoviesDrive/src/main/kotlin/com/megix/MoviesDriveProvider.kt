package com.megix

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.util.ArrayList

class MoviesDriveProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://moviesdrive.click"
    override var name = "MoviesDrive"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Release",
        "$mainUrl/category/hollywood/page/" to "Hollywood Movies",
        "$mainUrl/hindi-dubbed/page/" to "Hindi Dubbed Movies",
        "$mainUrl/category/south/page/" to "South Movies",
        "$mainUrl/category/bollywood/page/" to "Bollywood Movies",
        "$mainUrl/category/amzn-prime-video/page/" to "Prime Video",
        "$mainUrl/category/netflix/page/" to "Netflix",
        "$mainUrl/category/hotstar/page/" to "Hotstar",
        "$mainUrl/category/web/page/" to "Web Series",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("${request.data}$page").document
        val home = document.select("li.thumb").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("figcaption a") ?: return null
        val title = (titleElement.selectFirst("p")?.text() ?: titleElement.text()).replace("Download ", "")
        val href = titleElement.attr("href")
        val posterUrl = this.selectFirst("figure img")?.attr("src")
        val quality = if(title.contains("HDCAM", ignoreCase = true) || title.contains("CAMRip", ignoreCase = true)) {
            SearchQuality.CamRip
        }
        else {
            null
        }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..3) {
            val document = app.get("$mainUrl/page/$i/?s=$query").document

            val results = document.select("li.thumb").mapNotNull { it.toSearchResult() }

            if (results.isEmpty()) {
                break
            }
            searchResponse.addAll(results)
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.select("meta[property=og:title]").attr("content").replace("Download ", "")

        val posterUrl = document.select("p > img.aligncenter").attr("src")
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val fullText = document.body().text()

        val plot = fullText.substringAfter("Storyline :-").substringBefore("Screen-Shots").trim()

        val rating = Regex("""iMDB Rating:\s*([0-9.]+)""").find(fullText)?.groupValues?.get(1)
            .toRatingInt()

        val tags =
            Regex("""Genre:\s*([^\n]+)""").find(fullText)?.groupValues?.get(1)?.split(", ")
        
        val director = Regex("""Director:\s*([^\n]+)""").find(fullText)?.groupValues?.get(1)
        val cast = if (director?.isNotBlank() == true) listOf(director) else null

        val recommendations = emptyList<SearchResponse>()
        
        val isMovie = document.select("div.dls > ul > li > a").text().contains("Movie", true)

        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.rating = rating
                this.tags = tags ?: emptyList()
                addActors(cast)
                this.recommendations = recommendations
            }
        } else {
            val episodes = ArrayList<Episode>()
            document.select("div.sbox > div.sbox").forEach {
                val season = it.select("div.title span").text().let { seasonText ->
                    Regex("""Season (\d+)""").find(seasonText)?.groupValues?.get(1)?.toIntOrNull()
                }
                it.select("div.links ul li").forEach { episodeElement ->
                    val epNum = episodeElement.select("a").text().let { episodeText ->
                        Regex("""Episode (\d+)""").find(episodeText)?.groupValues?.get(1)
                            ?.toIntOrNull()
                    }
                    val epHref = episodeElement.select("a").attr("href")
                    episodes.add(
                        newEpisode(epHref) {
                            this.name = "Episode $epNum"
                            this.season = season
                            this.episode = epNum
                        }
                    )
                }
            }
            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes.distinctBy { it.data }.sortedBy { it.episode }
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.rating = rating
                this.tags = tags ?: emptyList()
                addActors(cast)
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
        document.select("div.links > ul > li").mapNotNull {
            it.select("a").attr("href")
        }.amap {
            loadExtractor(it, data, subtitleCallback, callback)
        }
        return true
    }
}
