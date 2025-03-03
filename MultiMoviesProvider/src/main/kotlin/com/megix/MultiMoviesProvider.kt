package com.megix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import okhttp3.FormBody

class MultiMoviesProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://multimovies.world/"
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
        "$mainUrl/genre/netflix/" to "Netflix",
        "$mainUrl/genre/amazon-prime/" to "Amazon Prime",
        "$mainUrl/genre/disney-hotstar/" to "Disney Hotstar",
        "$mainUrl/genre/jio-ott/" to "Jio OTT",
        "$mainUrl/genre/sony-liv/" to "Sony Live",
        "$mainUrl/genre/zee-5/" to "Zee5",
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

        val home = if (request.data.contains("/movies")) {
            document.select("#archive-content > article").mapNotNull { it.toSearchResult() }
        } else {
            document.select("div.items > article").mapNotNull { it.toSearchResult() }
        }

        return HomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.data > h3 > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("div.data > h3 > a")?.attr("href").toString())
        val posterUrl = fixUrlNull(this.selectFirst("div.poster > img")?.attr("src"))
        val quality = getQualityFromString(this.select("div.poster > div.mepo > span").text().trim())
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
        return document.select("div.result-item").mapNotNull {
            val title = it.selectFirst("article > div.details > div.title > a")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl(it.selectFirst("article > div.details > div.title > a")?.attr("href").toString())
            val posterUrl = fixUrlNull(it.selectFirst("article > div.image > div.thumbnail > a > img")?.attr("src"))
            val quality = getQualityFromString(it.select("div.poster > div.mepo > span").text().trim())
            val type = it.select("article > div.image > div.thumbnail > a > span").text().trim()
            if (type.contains("TV", ignoreCase = true)) {
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
    }

    private suspend fun getEmbed(postid: String?, nume: String, referUrl: String?): NiceResponse {
        val body = FormBody.Builder()
            .addEncoded("action", "doo_player_ajax")
            .addEncoded("post", postid.toString())
            .addEncoded("nume", nume)
            .addEncoded("type", "movie")
            .build()

        return app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
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
        val doc = app.get(url).document
        val title = doc.selectFirst("div.sheader > div.data > h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(doc.select("#contenedor").toString().substringAfter("background-image:url(").substringBefore(");"))
        val tags = doc.select("div.sgeneros > a").map { it.text() }
        val year = doc.selectFirst("span.date")?.text()?.substringAfter(",")?.trim()?.toIntOrNull()
        val description = doc.selectFirst("#info div.wp-content p")?.text()?.trim()
        val type = if (url.contains("tvshows", ignoreCase = true)) TvType.TvSeries else TvType.Movie
        val trailer = fixUrlNull(
            getEmbed(
                doc.select("#report-video-button-field > input[name~=postid]").attr("value"),
                "trailer",
                url
            ).parsed<TrailerUrl>().embedUrl
        )
        val rating = doc.select("span.dt_rating_vgs").text().toRatingInt()
        val duration = doc.selectFirst("span.runtime")?.text()?.removeSuffix(" Min.")?.trim()?.toIntOrNull()
        val actors = doc.select("div.person").map {
            ActorData(
                Actor(
                    it.select("div.data > div.name > a").text().trim(),
                    it.select("div.img > a > img").attr("src").trim()
                ),
                roleString = it.select("div.data > div.caracter").text().trim()
            )
        }
        val recommendations = doc.select("#dtw_content_related-2 article").mapNotNull { it.toSearchResult() }

        val episodes = ArrayList<Episode>()
        doc.select("#seasons ul.episodios").mapIndexed { seasonNum, me ->
            me.select("li").mapIndexed { epNum, it ->
                episodes.add(
                    Episode(
                        data = it.select("div.episodiotitle > a").attr("href"),
                        name = it.select("div.episodiotitle > a").text().trim(),
                        season = seasonNum + 1,
                        episode = epNum + 1,
                        posterUrl = it.select("div.imagen > img").attr("src").trim()
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
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val req = app.get(data).document
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
                ).parsed<ResponseHash>().embed_url
                val link = source.substringAfter("\"").substringBefore("\"")
                when {
                    !link.contains("youtube", ignoreCase = true) -> {
                        if (link.contains("gdmirrorbot.nl")) {
                            Log.d("Phisher", link)
                            loadExtractor(link, referer = mainUrl, subtitleCallback, callback)
                        } else if (link.contains("deaddrive.xyz")) {
                            app.get(link).document.select("ul.list-server-items > li").map {
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
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("type") val type: String? = null,
    )
}
