package com.megix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import okhttp3.FormBody

class MultiMoviesProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://multimovies.life/"
    override var name = "MultiMovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
    )

    companion object {
        // Default headers for requests
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "X-Requested-With" to "XMLHttpRequest"
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Latest Movies",
        "$mainUrl/genre/hollywood/" to "Hollywood Movies",
        "$mainUrl/genre/south-indian/" to "South Indian Movies",
        "$mainUrl/genre/bollywood-movies/" to "Bollywood Movies",
        "$mainUrl/genre/amazon-prime/" to "Amazon Prime",
        "$mainUrl/genre/disney-hotstar/" to "Disney Hotstar",
        "$mainUrl/genre/jio-ott/" to "Jio OTT",
        "$mainUrl/genre/netflix/" to "Netflix",
        "$mainUrl/genre/sony-liv/" to "Sony Live",
        "$mainUrl/genre/zee-5/" to "Zee5",
        "$mainUrl/genre/hungama/" to "Hungama",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = if (page == 1) {
            app.get(request.data, headers = headers).document
        } else {
            app.get("${request.data}page/$page/", headers = headers).document
        }

        val home = if (request.data.contains("/movies")) {
            document.select("#archive-content > article").mapNotNull {
                it.toSearchResult()
            }
        } else {
            document.select("div.items > article").mapNotNull {
                it.toSearchResult()
            }
        }

        return HomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.data > h3 > a")?.text()?.trim().orEmpty()
        val href = fixUrl(this.selectFirst("div.data > h3 > a")?.attr("href").orEmpty())
        val posterUrl = fixUrlNull(this.selectFirst("div.poster > img")?.attr("src"))
        val quality = getQualityFromString(this.select("div.poster > div.mepo > span").text().trim())

        return if (href.contains("movie", ignoreCase = true)) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = headers).document

        return document.select("div.result-item").mapNotNull {
            val title = it.selectFirst("article > div.details > div.title > a")?.text()?.trim().orEmpty()
            val href = fixUrl(it.selectFirst("article > div.details > div.title > a")?.attr("href").orEmpty())
            val posterUrl = fixUrlNull(it.selectFirst("article > div.image > div.thumbnail > a > img")?.attr("src"))
            val quality = getQualityFromString(it.select("div.poster > div.mepo > span").text().trim())
            val type = it.select("article > div.image > div.thumbnail > a > span").text().trim()

            if (type.contains("movie", ignoreCase = true)) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.quality = quality
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.quality = quality
                }
            }
        }
    }

    private suspend fun getEmbed(postid: String?, nume: String, referUrl: String?): NiceResponse {
        val body = FormBody.Builder()
            .addEncoded("action", "doo_player_ajax")
            .addEncoded("post", postid.orEmpty())
            .addEncoded("nume", nume)
            .addEncoded("type", "movie")
            .build()

        return app.post(
            url = "$mainUrl/wp-admin/admin-ajax.php",
            requestBody = body,
            referer = referUrl,
            headers = headers
        )
    }

    data class TrailerUrl(
        @JsonProperty("embed_url") var embedUrl: String?,
        @JsonProperty("type") var type: String?
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers).document
        val titleL = doc.selectFirst("div.sheader > div.data > h1")?.text()?.trim().orEmpty()
        val titleRegex = Regex("(^.*\\)\\d*)")
        val titleClean = titleRegex.find(titleL)?.groups?.get(1)?.value.orEmpty()
        val title = if (titleClean.isEmpty()) titleL else titleClean
        val poster = fixUrlNull(doc.select("div.poster > img").attr("src"))
        val tags = doc.select("div.sgeneros > a").map { it.text().trim() }
        val year = doc.selectFirst("span.date")?.text()?.substringAfter(",")?.trim()?.toIntOrNull()
        val description = doc.selectFirst("#info div.wp-content p")?.text()?.trim()
        val type = if (url.contains("tvshows", ignoreCase = true)) TvType.TvSeries else TvType.Movie
        val trailerRegex = Regex("\"http.*\"")

        val trailer = if (type == TvType.Movie) {
            fixUrlNull(
                getEmbed(
                    doc.select("#report-video-button-field > input[name~=postid]").attr("value"),
                    "trailer",
                    url
                ).parsed<ResponseHash>().embedUrl
            )
        } else {
            fixUrlNull(doc.select("iframe.rptss").attr("src"))
        }

        val trailerFinal = trailerRegex.find(trailer.orEmpty())?.value.orEmpty()
        val rating = doc.select("span.dt_rating_vgs").text().toRatingInt()
        val duration = doc.selectFirst("span.runtime")?.text()?.removeSuffix(" Min.")?.trim()?.toIntOrNull()
        val actors = doc.select("div.person").map {
            ActorData(
                Actor(
                    it.select("div.data > div.name > a").text().trim(),
                    it.select("div.img > a > img").attr("src").trim()
                ),
                roleString = it.select("div.data > div.caracter").text().trim(),
            )
        }
        val recommendations = doc.select("#dtw_content_related-2 article").mapNotNull {
            it.toSearchResult()
        }

        val episodes = ArrayList<Episode>()
        doc.select("#seasons ul.episodios").mapIndexed { seasonNum, me ->
            me.select("li").mapIndexed { epNum, it ->
                episodes.add(
                    Episode(
                        data = it.select("div.episodiotitle > a").attr("href"),
                        name = it.select("div.episodiotitle > a").text(),
                        season = seasonNum + 1,
                        episode = epNum + 1,
                        posterUrl = it.select("div.imagen > img").attr("src")
                    )
                )
            }
        }

        return if (type == TvType.Movie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster?.trim()
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailerFinal)
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster?.trim()
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailerFinal)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val req = app.get(data, headers = headers).document
        req.select("ul#playeroptionsul li").map {
            Triple(
                it.attr("data-post"),
                it.attr("data-nume"),
                it.attr("data-type")
            )
        }.apmap { (id, nume, type) ->
            if (!nume.contains("trailer", ignoreCase = true)) {
                val source = app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to id,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = mainUrl,
                    headers = headers
                ).parsed<ResponseHash>().embedUrl

                val link = source?.substringAfter("\"")?.substringBefore("\"").orEmpty()
                when {
                    !link.contains("youtube", ignoreCase = true) -> {
                        if (link.contains("gdmirrorbot.nl", ignoreCase = true)) {
                            Log.d("Phisher", link)
                            loadExtractor(link, referer = mainUrl, subtitleCallback, callback)
                        } else if (link.contains("deaddrive.xyz", ignoreCase = true)) {
                            app.get(link, headers = headers).document.select("ul.list-server-items > li").map {
                                val server = it.attr("data-video")
                                loadExtractor(server, referer = mainUrl, subtitleCallback, callback)
                            }
                        } else {
                            loadExtractor(link, referer = mainUrl, subtitleCallback, callback)
                        }
                    }
                    else -> return@apmap
                }
            }
        }
        return true
    }

    data class ResponseHash(
        @JsonProperty("embed_url") val embedUrl: String?,
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("type") val type: String? = null,
    )
}