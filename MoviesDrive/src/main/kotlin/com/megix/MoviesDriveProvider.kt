package com.megix

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.nodes.Element

class MoviesDriveProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://moviesdrive.forum"
    override var name = "MoviesDrive"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    val cinemetaUrl = "https://aiometadata.elfhosted.com/stremio/9197a4a9-2f5b-4911-845e-8704c520bdf7/meta"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
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
                    val response = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json")
                    val json = response.text
                    val jsonObject = JSONObject(json)
                    jsonObject.optString("moviesdrive")
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    override val mainPage = mainPageOf(
        "/page/" to "Latest Release",
        "/category/hollywood/page/" to "Hollywood Movies",
        "/hindi-dubbed/page/" to "Hindi Dubbed Movies",
        "/category/south/page/" to "South Movies",
        "/category/bollywood/page/" to "Bollywood Movies",
        "/category/amzn-prime-video/page/" to "Prime Video",
        "/category/netflix/page/" to "Netflix",
        "/category/hotstar/page/" to "Hotstar",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("${mainUrl}${request.data}${page}").document
        val home = document.select("ul.recent-movies > li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("figure > img").attr("title").replace("Download ", "")
        val href = this.select("figure > a").attr("href")
        val posterUrl = this.select("figure > img").attr("src")
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

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = app.get("$mainUrl/page/$page/?s=$query").document
        val results = document.select("ul.recent-movies > li").mapNotNull { it.toSearchResult() }
        val hasNext = results.isNotEmpty()
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val initialTitle = document.select("title").text().replace("Download ", "")
        val ogTitle = initialTitle
        val plotElement = document.select(
            "h2:contains(Storyline), h3:contains(Storyline), h5:contains(Storyline), h4:contains(Storyline), h4:contains(STORYLINE)"
        ).firstOrNull()?.nextElementSibling()

        val initialDescription = plotElement?.text() ?: document.select(".ipc-html-content-inner-div").firstOrNull()?.text().toString()

        val initialPosterUrl = document.select("img[decoding=\"async\"]").attr("src")
        val seasonRegex = """(?i)season\s*\d+""".toRegex()
        val imdbUrl = document.select("a[href*=\"imdb\"]").attr("href")

        val tvtype = if (
            initialTitle.contains("Episode", ignoreCase = true) ||
            seasonRegex.containsMatchIn(initialTitle) ||
            initialTitle.contains("series", ignoreCase = true)
        ) {
            "series"
        } else {
            "movie"
        }

        val imdbId = imdbUrl.substringAfter("title/").substringBefore("/")
        val jsonResponse = app.get("$cinemetaUrl/$tvtype/$imdbId.json").text
        val responseData = tryParseJson<ResponseData>(jsonResponse)

        val description: String
        val cast: List<String>
        var title: String
        val genre: List<String>
        val imdbRating: String
        val year: String
        val posterUrl: String
        val background: String

        if (responseData != null) {
            description = responseData.meta.description ?: initialDescription
            cast = responseData.meta.cast ?: emptyList()
            title = responseData.meta.name ?: initialTitle
            genre = responseData.meta.genre ?: emptyList()
            imdbRating = responseData.meta.imdbRating ?: ""
            year = responseData.meta.year ?: ""
            posterUrl = responseData.meta.poster ?: initialPosterUrl
            background = responseData.meta.background ?: initialPosterUrl
        } else {
            description = initialDescription
            cast = emptyList()
            title = initialTitle
            genre = emptyList()
            imdbRating = ""
            year = ""
            posterUrl = initialPosterUrl
            background = initialPosterUrl
        }

        if (tvtype == "series") {
            if (title != ogTitle) {
                val checkSeason = Regex("""Season\s*\d*1|S\s*\d*1""").find(ogTitle)
                if (checkSeason == null) {
                    val seasonText = Regex("""Season\s*\d+|S\s*\d+""").find(ogTitle)?.value
                    if (seasonText != null) {
                        title = "$title $seasonText"
                    }
                }
            }
            val tvSeriesEpisodes = mutableListOf<Episode>()
            val episodesMap: MutableMap<Pair<Int, Int>, List<String>> = mutableMapOf()
            val seriesButtons = document.select("h5 > a")
                .filter { element -> !element.text().contains("Zip", true) }


            seriesButtons.forEach { button ->
                val titleElement = button.parent()?.previousElementSibling()
                val mainTitle = titleElement?.text() ?: ""
                val realSeasonRegex = Regex("""(?:Season |S)(\d+)""")
                val realSeason = realSeasonRegex.find(mainTitle)?.groupValues?.get(1)?.toInt() ?: 0
                val episodeLink = button.attr("href")

                val doc = app.get(episodeLink).document
                var elements = doc.select("span:matches((?i)(Ep))")
                if (elements.isEmpty()) {
                    elements = doc.select("a:matches((?i)(HubCloud|GDFlix))")
                }
                var episodeNum = 1

                elements.forEach { element ->
                    if (element.tagName() == "span") {
                        val titleTag = element.parent()
                        var hTag = titleTag?.nextElementSibling()
                        episodeNum = Regex("""Ep(\d{2})""").find(element.toString())?.groups?.get(1)?.value?.toIntOrNull() ?: episodeNum
                        while (
                            hTag != null &&
                            (
                                    hTag.text().contains("HubCloud", ignoreCase = true) ||
                                            hTag.text().contains("gdflix", ignoreCase = true) ||
                                            hTag.text().contains("gdlink", ignoreCase = true)
                                    )
                        ) {
                            val aTag = hTag.selectFirst("a")
                            val epUrl = aTag?.attr("href").toString()
                            val key = Pair(realSeason, episodeNum)
                            if (episodesMap.containsKey(key)) {
                                val currentList = episodesMap[key] ?: emptyList()
                                val newList = currentList.toMutableList()
                                newList.add(epUrl)
                                episodesMap[key] = newList
                            } else {
                                episodesMap[key] = mutableListOf(epUrl)
                            }
                            hTag = hTag.nextElementSibling()
                        }
                        episodeNum++
                    }
                    else {
                        val epUrl = element.attr("href")
                        val key = Pair(realSeason, episodeNum)
                        if (episodesMap.containsKey(key)) {
                            val currentList = episodesMap[key] ?: emptyList()
                            val newList = currentList.toMutableList()
                            newList.add(epUrl)
                            episodesMap[key] = newList
                        } else {
                            episodesMap[key] = mutableListOf(epUrl)
                        }
                        episodeNum++
                    }
                }
            }

            for ((key, value) in episodesMap) {
                val episodeInfo = responseData?.meta?.videos?.find { it.season == key.first && it.episode == key.second }
                val episodeData = value.map { source ->
                    EpisodeLink(source)
                }
                tvSeriesEpisodes.add(
                    newEpisode(episodeData) {
                        this.name = episodeInfo?.name ?: episodeInfo?.title
                        this.season = key.first
                        this.episode = key.second
                        this.posterUrl = episodeInfo?.thumbnail
                        this.description = episodeInfo?.overview
                    }
                )
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, tvSeriesEpisodes) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background
                addActors(cast)
                addImdbUrl(imdbUrl)
            }
        } else {
            val movieButtons = document.select("h5 > a")
            val movieData = movieButtons.flatMap { button ->
                val buttonLink = button.attr("href")
                val buttonDoc = app.get(buttonLink).document
                val innerButtons = buttonDoc.select("a").filter { element ->
                    element.attr("href").contains(Regex("hubcloud|gdflix|gdlink", RegexOption.IGNORE_CASE))
                }
                innerButtons.mapNotNull { innerButton ->
                    val source = innerButton.attr("href")
                    EpisodeLink(source)
                }
            }
            return newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background
                addActors(cast)
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
        val sources = parseJson<ArrayList<EpisodeLink>>(data)
        sources.amap {
            val source = it.source
            loadExtractor(source, subtitleCallback, callback)
        }
        return true
    }

    data class Meta(
        val id: String?,
        @Suppress("PropertyName") val imdb_id: String?,
        val type: String?,
        val poster: String?,
        val logo: String?,
        val background: String?,
        @Suppress("PropertyName") val moviedb_id: Int?,
        val name: String?,
        val description: String?,
        val genre: List<String>?,
        val releaseInfo: String?,
        val status: String?,
        val runtime: String?,
        val cast: List<String>?,
        val language: String?,
        val country: String?,
        val imdbRating: String?,
        val slug: String?,
        val year: String?,
        val videos: List<EpisodeDetails>?
    )

    data class EpisodeDetails(
        val id: String?,
        val name: String?,
        val title: String?,
        val season: Int?,
        val episode: Int?,
        val released: String?,
        val overview: String?,
        val thumbnail: String?,
        @Suppress("PropertyName") val moviedb_id: Int?
    )

    data class ResponseData(
        val meta: Meta
    )

    data class EpisodeLink(
        val source: String
    )
}
