package com.megix

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MoviesDriveProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://moviesdrive.zip"
    override var name = "MoviesDrive"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    val cinemeta_url = "https://v3-cinemeta.strem.io/meta"
    override val supportedTypes =
            setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Anime)

    override val mainPage =
            mainPageOf(
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}$page").document
        val home = document.select("li.thumb").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("figcaption a") ?: return null
        val title =
                (titleElement.selectFirst("p")?.text() ?: titleElement.text()).replace(
                        "Download ",
                        ""
                )
        val href = titleElement.attr("href")
        val posterUrl = this.selectFirst("figure img")?.attr("src")
        val quality =
                if (title.contains("HDCAM", ignoreCase = true) ||
                                title.contains("CAMRip", ignoreCase = true)
                ) {
                    SearchQuality.CamRip
                } else {
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
        var title =
                document.select("meta[property=og:title]").attr("content").replace("Download ", "")
        val ogTitle = title
        val plotElement =
                document.select(
                                "h2:contains(Storyline), h3:contains(Storyline), h5:contains(Storyline), h4:contains(Storyline), h4:contains(STORYLINE)"
                        )
                        .firstOrNull()
                        ?.nextElementSibling()

        var description =
                plotElement?.text()
                        ?: document.select(".ipc-html-content-inner-div")
                                .firstOrNull()
                                ?.text()
                                .toString()

        var posterUrl = document.select("img[decoding=\"async\"]").attr("src")
        val seasonRegex = """(?i)season\s*\d+""".toRegex()
        val imdbUrl = document.select("a[href*=\"imdb\"]").attr("href")

        val tvtype =
                if (title.contains("Episode", ignoreCase = true) == true ||
                                seasonRegex.containsMatchIn(title) ||
                                title.contains("series", ignoreCase = true) == true
                ) {
                    "series"
                } else {
                    "movie"
                }

        val imdbId = imdbUrl.substringAfter("title/").substringBefore("/")
        val jsonResponse = app.get("$cinemeta_url/$tvtype/$imdbId.json").text
        val responseData = tryParseJson<ResponseData>(jsonResponse)

        var cast: List<String> = emptyList()
        var genre: List<String> = emptyList()
        var imdbRating: String = ""
        var year: String = ""
        var background: String = posterUrl

        if (responseData != null) {
            description = responseData.meta.description ?: description
            cast = responseData.meta.cast ?: emptyList()
            title = responseData.meta.name ?: title
            genre = responseData.meta.genre ?: emptyList()
            imdbRating = responseData.meta.imdbRating ?: ""
            year = responseData.meta.year ?: ""
            posterUrl = responseData.meta.poster ?: posterUrl
            background = responseData.meta.background ?: background
        }

        if (tvtype == "series") {
            if (title != ogTitle) {
                val checkSeason = Regex("""Season\s*\d*1|S\s*\d*1""").find(ogTitle)
                if (checkSeason == null) {
                    val seasonText = Regex("""Season\s*\d+|S\s*\d+""").find(ogTitle)?.value
                    if (seasonText != null) {
                        title = title + " " + seasonText.toString()
                    }
                }
            }
            val tvSeriesEpisodes = mutableListOf<Episode>()
            val episodesMap: MutableMap<Pair<Int, Int>, List<String>> = mutableMapOf()
            var buttons =
                    document.select("h5 > a").filter { element ->
                        !element.text().contains("Zip", true)
                    }

            buttons.forEach { button ->
                val titleElement = button.parent()?.previousElementSibling()
                val mainTitle = titleElement?.text() ?: ""
                val realSeasonRegex = Regex("""(?:Season |S)(\d+)""")
                val realSeason =
                        realSeasonRegex.find(mainTitle.toString())?.groupValues?.get(1)?.toInt()
                                ?: 0
                val episodeLink = button.attr("href") ?: ""

                val doc = app.get(episodeLink).document
                var elements = doc.select("span:matches((?i)(Ep))")
                if (elements.isEmpty()) {
                    elements = doc.select("a:matches((?i)(HubCloud|GDFlix))")
                }
                var e = 1

                elements.forEach { element ->
                    if (element.tagName() == "span") {
                        val titleTag = element.parent()
                        var hTag = titleTag?.nextElementSibling()
                        e =
                                Regex("""Ep(\d{2})""")
                                        .find(element.toString())
                                        ?.groups
                                        ?.get(1)
                                        ?.value
                                        ?.toIntOrNull()
                                        ?: e
                        while (hTag != null &&
                                (hTag.text().contains("HubCloud", ignoreCase = true) ||
                                        hTag.text().contains("gdflix", ignoreCase = true) ||
                                        hTag.text().contains("gdflix.ink", ignoreCase = true) ||
                                        hTag.text().contains("gdlink", ignoreCase = true))) {
                            val aTag = hTag.selectFirst("a")
                            val epUrl = aTag?.attr("href").toString()
                            val key = Pair(realSeason, e)
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
                        e++
                    } else {
                        val epUrl = element.attr("href")
                        val key = Pair(realSeason, e)
                        if (episodesMap.containsKey(key)) {
                            val currentList = episodesMap[key] ?: emptyList()
                            val newList = currentList.toMutableList()
                            newList.add(epUrl)
                            episodesMap[key] = newList
                        } else {
                            episodesMap[key] = mutableListOf(epUrl)
                        }
                        e++
                    }
                }
                e = 1
            }

            for ((key, value) in episodesMap) {
                val episodeInfo =
                        responseData?.meta?.videos?.find {
                            it.season == key.first && it.episode == key.second
                        }
                val data = value.map { source -> EpisodeLink(source) }
                tvSeriesEpisodes.add(
                        newEpisode(data) {
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
                this.rating = imdbRating.toRatingInt()
                this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background
                addActors(cast)
                addImdbUrl(imdbUrl)
            }
        } else {
            val buttons = document.select("h5 > a")
            val data =
                    buttons.flatMap { button ->
                        val link = button.attr("href")
                        val doc = app.get(link).document
                        val innerButtons =
                                doc.select("a").filter { element ->
                                    element.attr("href")
                                            .contains(
                                                    Regex(
                                                            "hubcloud|gdflix|gdlink|gdflix.ink",
                                                            RegexOption.IGNORE_CASE
                                                    )
                                            )
                                }
                        innerButtons.mapNotNull { innerButton ->
                            val source = innerButton.attr("href")
                            EpisodeLink(source)
                        }
                    }
            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.rating = imdbRating.toRatingInt()
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
            val imdb_id: String?,
            val type: String?,
            val poster: String?,
            val logo: String?,
            val background: String?,
            val moviedb_id: Int?,
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
            val moviedb_id: Int?
    )

    data class ResponseData(val meta: Meta)

    data class EpisodeLink(val source: String)
}
