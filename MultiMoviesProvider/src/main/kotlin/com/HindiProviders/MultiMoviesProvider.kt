package com.Phisher98

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import okhttp3.FormBody

class MultiMoviesProvider : MainAPI() {
    override var mainUrl = "https://multimovies.lat"
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
        // val headers= mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0", "X-Requested-With" to "XMLHttpRequest")
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "नवीनतम रिलीज मूवीज",
        "$mainUrl/trending/" to "ट्रेंडिंग मूवीज",
        "$mainUrl/genre/bollywood-movies/" to "बॉलीवुड मूवीज",
        "$mainUrl/genre/hollywood/" to "हॉलीवुड हिंदी मूवीज",
        "$mainUrl/genre/south-indian/" to "दक्षिण हिंदी मूवीज",
        "$mainUrl/genre/netflix/" to "नेटफ्लिक्स",
        "$mainUrl/genre/amazon-prime/" to "अमेज़ॅन प्राइम",
        "$mainUrl/genre/disney-hotstar/" to "डिज्नी हॉटस्टार",
        "$mainUrl/genre/sony-liv/" to "सोनी लाइव",
        "$mainUrl/genre/zee-5/" to "ज़ी5",
        "$mainUrl/genre/jio-ott/" to "जियो सिनेमा",
        "$mainUrl/tvchannels/" to "टीवी चैनल"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = if (page == 1) {
            app.get(request.data).document
        } else {
            app.get(request.data + "page/$page/").document
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
        val title = this.selectFirst("div.data > h3 > a")?.text()?.toString()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("div.data > h3 > a")?.attr("href").toString())
        val posterUrl = fixUrlNull(this.selectFirst("div.poster > img")?.attr("data-src"))
        val quality =
            getQualityFromString(this.select("div.poster > div.mepo > span").text().toString())

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
        val document = app.get("$mainUrl/?s=$query").document

        return document.select("div.result-item").mapNotNull {
            val title =
                it.selectFirst("article > div.details > div.title > a")?.text().toString().trim()
            val href = fixUrl(
                it.selectFirst("article > div.details > div.title > a")?.attr("href").toString()
            )
            val posterUrl = fixUrlNull(
                it.selectFirst("article > div.image > div.thumbnail > a > img")?.attr("src")
            )
            val quality =
                getQualityFromString(it.select("div.poster > div.mepo > span").text().toString())
            val type = it.select("article > div.image > div.thumbnail > a > span").text().toString()

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
            referer = referUrl
        )
    }

    data class TrailerUrl(
        @JsonProperty("embed_url") var embedUrl: String?,
        @JsonProperty("type") var type: String?
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val titleL = doc.selectFirst("div.sheader > div.data > h1")?.text()?.toString()?.trim()
            ?: return null
        val titleRegex = Regex("(^.*\\)\\d*)")
        val titleClean = titleRegex.find(titleL)?.groups?.get(1)?.value.toString()
        val title = if (titleClean == "null") titleL else titleClean
        val poster = fixUrlNull(
            doc.select("#contenedor").toString().substringAfter("background-image:url(")
                .substringBefore(");")
        )
        val tags = doc.select("div.sgeneros > a").map { it.text() }
        val year =
            doc.selectFirst("span.date")?.text()?.toString()?.substringAfter(",")?.trim()?.toIntOrNull()
        val description = doc.selectFirst("#info div.wp-content p")?.text()?.trim()
        val type = if (url.contains("tvshows")) TvType.TvSeries else TvType.Movie
        val trailerRegex = Regex("\"http.*\"")
        var trailer = if (type == TvType.Movie)
            fixUrlNull(
                getEmbed(
                    doc.select("#report-video-button-field > input[name~=postid]").attr("value")
                        .toString(),
                    "trailer",
                    url
                ).parsed<TrailerUrl>().embedUrl
            )
        else fixUrlNull(doc.select("iframe.rptss").attr("src").toString())
        trailer = trailerRegex.find(trailer.toString())?.value.toString()
        val rating = doc.select("span.dt_rating_vgs").text().toRatingInt()
        val duration =
            doc.selectFirst("span.runtime")?.text()?.toString()?.removeSuffix(" Min.")?.trim()
                ?.toIntOrNull()
        val actors =
            doc.select("div.person").map {
                ActorData(
                    Actor(
                        it.select("div.data > div.name > a").text().toString(),
                        fixUrlNull(it.select("div.img > a > img").attr("src").toString())
                    ),
                    roleString = it.select("div.data > div.caracter").text().toString(),
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
                        posterUrl = fixUrlNull(it.select("div.imagen > img").attr("src"))
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
        val document = app.get(data).document
        val videoUrl = document.selectFirst("iframe.rptss")?.attr("src")
        val videoUrl2 = document.selectFirst("div#videoPlayer > iframe")?.attr("src")
        val downloadUrl = document.selectFirst("ul#videoLinks > li > a.dlvideoLinks")?.attr("href")
        val otherSources = document.select("ul#videoLinks > li[data-link]")

        if (videoUrl != null) {
            safeApiCall {
                callback(
                    ExtractorLink(
                        name = "MultiMovies Player",
                        source = "MultiMovies Player",
                        url = videoUrl,
                        referer = "https://multimovies.lat/",
                        quality = getQualityFromName(videoUrl),
                        isM3u8 = videoUrl.contains("m3u8")
                    )
                )
            }
            return true
        } else if (videoUrl2 != null) {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/110.0",
                "referer" to "https://multimovies.lat/"
            )
            safeApiCall {
                val res = app.get(videoUrl2, headers = headers).text
                val videoUrl3 = Regex("source src=\"(.*?)\"").find(res)?.groupValues?.get(1)
                if (videoUrl3 != null) {
                    callback(
                        ExtractorLink(
                            name = "MultiMovies Player",
                            source = "MultiMovies Player",
                            url = videoUrl3,
                            referer = "https://multimovies.lat/",
                            quality = getQualityFromName(videoUrl3),
                            isM3u8 = videoUrl3.contains("m3u8")
                        )
                    )
                }
            }
            return true
        }  else if (downloadUrl != null) {
            safeApiCall {
                callback(
                    ExtractorLink(
                        name = "MultiMovies Download",
                        source = "MultiMovies Download",
                        url = downloadUrl,
                        referer = "https://multimovies.lat/",
                        quality = getQualityFromName(downloadUrl),
                        isM3u8 = false
                    )
                )
            }
            return true
        }else {
            otherSources.map {
                val link = it.attr("data-link")
                val sourceKey = it.attr("data-source-key")
                if (link.isNotBlank() && !link.contains("gdmirrorbot.nl")) {
                    safeApiCall {
                        callback(
                            ExtractorLink(
                                name = sourceKey,
                                source = sourceKey,
                                url = link,
                                referer = "https://multimovies.lat/",
                                quality = getQualityFromName(link),
                                isM3u8 = link.contains("m3u8")
                            )
                        )
                    }
                } else if (link.contains("gdmirrorbot.nl")) {

                    safeApiCall {
                        callback(
                            ExtractorLink(
                                name = "gdmirrorbot",
                                source = "gdmirrorbot",
                                url = link,
                                referer = "https://multimovies.lat/",
                                quality = getQualityFromName(link),
                                isM3u8 = false
                            )
                        )
                    }
                } else {

                }
            }
            return true
        }
    }
}