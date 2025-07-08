package com.phisher98


import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import okhttp3.FormBody

class MultiMoviesProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://multimovies.agency"
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

    override val mainPage = mainPageOf(
        "trending/" to "Trending",
        "genre/bollywood-movies/" to "Bollywood Movies",
        "genre/hollywood/" to "Hollywood Movies",
        "genre/south-indian/" to "South Indian Movies",
        "genre/punjabi/" to "Punjabi Movies",
        "genre/amazon-prime/" to "Amazon Prime",
        "genre/disney-hotstar/" to "Disney Hotstar",
        "genre/jio-ott/" to "Jio OTT",
        "genre/netflix/" to "Netfilx",
        "genre/sony-liv/" to "Sony Live",
        "genre/k-drama/" to "KDrama",
        "genre/zee-5/" to "Zee5",
        "genre/anime-hindi/" to "Anime Series",
        "genre/anime-movies/" to "Anime Movies",
        "genre/cartoon-network/" to "Cartoon Network",
        "genre/disney-channel/" to "Disney Channel",
        "genre/hungama/" to "Hungama",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
            "referer" to mainUrl
        )
        val document = if (page == 1) {
            app.get("$mainUrl/${request.data}", headers = headers).document
        } else {
            app.get("$mainUrl/${request.data}" + "page/$page/", headers = headers).document
        }
        val home = document.select("div.items > article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(HomePageList(request.name, home))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.data > h3 > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("div.data > h3 > a")?.attr("href").toString())
        val posterUrl = fixUrlNull(this.selectFirst("div.poster > img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        })
        val quality = getQualityFromString(this.select("div.poster > div.mepo > span").text())
        return if (href.contains("Movie")) {
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
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
            "referer" to mainUrl
        )
        val document = app.get("$mainUrl/?s=$query", headers = headers).document
        return document.select("div.result-item").mapNotNull {
            val title =
                it.selectFirst("article > div.details > div.title > a")?.text().toString().trim()
            val href = fixUrl(
                it.selectFirst("article > div.details > div.title > a")?.attr("href").toString()
            )
            val posterUrl = fixUrlNull(it.selectFirst("article > div.image > div.thumbnail > a > img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            })
            val quality = getQualityFromString(it.select("div.poster > div.mepo > span").text())
            val type = it.select("article > div.image > div.thumbnail > a > span").text()
            if (type.contains("Movie")) {
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

    private suspend fun getEmbed(postid: String?, nume: String, referUrl: String?): NiceResponse? {
        return try {
            val body = FormBody.Builder()
                .addEncoded("action", "doo_player_ajax")
                .addEncoded("post", postid.toString())
                .addEncoded("nume", nume)
                .addEncoded("type", "movie")
                .build()

            app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                requestBody = body,
                referer = referUrl
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    data class TrailerUrl(
        @JsonProperty("embed_url") var embedUrl: String?,
        @JsonProperty("type") var type: String?
    )

    override suspend fun load(url: String): LoadResponse? {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
            "referer" to mainUrl
        )
        val doc = app.get(url, headers = headers).document
        val titleL = doc.selectFirst("div.sheader > div.data > h1")?.text()?.trim() ?: return null
        val titleRegex = Regex("(^.*\\)\\d*)")
        val titleClean = titleRegex.find(titleL)?.groups?.get(1)?.value.toString()
        val title = if (titleClean == "null") titleL else titleClean
        val poster = fixUrlNull(doc.selectFirst("div.poster > img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        })
        val tags = doc.select("div.sgeneros > a").map { it.text() }
        val year = doc.selectFirst("span.date")?.text()?.substringAfter(",")?.trim()?.toInt()
        val description = doc.selectFirst("#info div.wp-content p")?.text()?.trim()
        val type = if (url.contains("tvshows")) TvType.TvSeries else TvType.Movie
        val trailerRegex = Regex("\"http.*\"")

        var trailer: String? = if (type == TvType.Movie) {
            val postId = doc.select("#player-option-trailer").attr("data-post")
            getEmbed(postId, "trailer", url)?.parsed<TrailerUrl>()?.embedUrl?.let { fixUrlNull(it) }
        } else {
            val iframeSrc = doc.select("iframe.rptss").attr("src")
            fixUrlNull(iframeSrc)
        }
        trailer = trailer?.let { trailerRegex.find(it)?.value?.trim('"') }
        val rating = doc.select("span.dt_rating_vgs").text().toRatingInt()
        val duration =
            doc.selectFirst("span.runtime")?.text()?.removeSuffix(" Min.")?.trim()
                ?.toInt()
        val actors =
            doc.select("div.person").map {
                ActorData(
                    Actor(
                        it.select("div.data > div.name > a").text(),
                        it.select("div.img > a > img").attr("src")
                    ),
                    roleString = it.select("div.data > div.caracter").text(),
                )
            }
        val recommendations = doc.select("#dtw_content_related-2 article").mapNotNull {
            it.toSearchResult()
        }

        val episodes = ArrayList<Episode>()
        doc.select("#seasons ul.episodios").mapIndexed { seasonNum, me ->
            me.select("li").mapIndexed { epNum, it ->
                episodes.add(
                    newEpisode(it.select("div.episodiotitle > a").attr("href"))
                    {
                        this.name = it.select("div.episodiotitle > a").text()
                        this.season = seasonNum + 1
                        this.episode = epNum + 1
                        this.posterUrl = it.select("div.imagen > img").attr("data-src")
                    }
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
                addTrailer(trailer)
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
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
            "referer" to mainUrl
        )
        val req = app.get(data, headers = headers).document
        req.select("ul#playeroptionsul li").map {
                Triple(
                    it.attr("data-post"),
                    it.attr("data-nume"),
                    it.attr("data-type")
                )
            }.amap { (id, nume, type) ->
            if (!nume.contains("trailer")) {
                val source = app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to id,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = mainUrl,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).parsed<ResponseHash>().embed_url
                val link = source.substringAfter("\"").substringBefore("\"").trim()
                when {
                    !link.contains("youtube") -> {
                        if (link.contains("deaddrive.xyz")) {
                            app.get(link).document.select("ul.list-server-items > li").map {
                                val server = it.attr("data-video")
                                loadExtractor(server, referer = mainUrl, subtitleCallback, callback)
                            }
                        } else
                            loadExtractor(link, referer = mainUrl, subtitleCallback, callback)
                    }

                    else -> return@amap
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
