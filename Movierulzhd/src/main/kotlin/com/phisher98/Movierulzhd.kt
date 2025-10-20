package com.phisher98


import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixTitle
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.FormBody
import org.jsoup.nodes.Element
import java.net.URI

open class Movierulzhd : MainAPI() {

    override var mainUrl = "https://1movierulzhd.live/"
    var directUrl = ""
    override var name = "Movierulzhd"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "movies" to "Latest Release Movies",
        "genre/hindi-dubbed/" to "Hindi Dubbed Movies",
        "genre/hindi/" to "HollyWood Hindi",
        "genre/netflix" to "Netflix",
        "genre/amazon-prime" to "Amazon Prime",
        "genre/hotstar/" to "Hotstar",
        "genre/Zee5" to "Zee5",
        "genre/sony-liv/" to "Sony Live",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if(page == 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/page/$page/"
        val document = app.get(url, timeout = 20L).document
        val home =
            document.select("div.items.normal article, div#archive-content article, div.items.full article").mapNotNull {
                it.toSearchResult()
            }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperLink(uri: String): String {
        return when {
            uri.contains("/episodes/") -> {
                var title = uri.substringAfter("$mainUrl/episodes/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvshows/$title"
            }

            uri.contains("/seasons/") -> {
                var title = uri.substringAfter("$mainUrl/seasons/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvshows/$title"
            }

            else -> {
                uri
            }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3 > a")?.text() ?: return null
        val href = getProperLink(fixUrl(this.selectFirst("h3 > a")!!.attr("href")))
        var posterUrl = this.select("div.poster img").last()?.getImageAttr()
       
        if (posterUrl != null) {
            if (posterUrl.contains(".gif")) {
                posterUrl = fixUrlNull(this.select("div.poster img").attr("data-wpfc-original-src"))
            }
        }
        val quality = getQualityFromString(this.select("span.quality").text())
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/$query").document
        return document.select("div.result-item").map {
            val title =
                it.selectFirst("div.title > a")!!.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
            val href = getProperLink(it.selectFirst("div.title > a")!!.attr("href"))
            val posterUrl = it.selectFirst("img")!!.attr("src")
            newMovieSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        val document = request.document
        directUrl = getBaseUrl(request.url)
        val title =
            document.selectFirst("div.data > h1")?.text()?.trim().toString()
        val background = fixUrlNull(document.selectFirst(".playbox img.cover")?.attr("src"))
        val posterUrl = fixUrlNull(document.select("div.poster img").attr("src"))
        /*if (backgroud.isNullOrEmpty()) {
            if (background.contains("movierulzhd")) {
                background = fixUrlNull(document.select("div.poster img").attr("src"))
            }
        }*/
        val tags = document.select("div.sgeneros > a").map { it.text() }
        val year = Regex(",\\s?(\\d+)").find(
            document.select("span.date").text().trim()
        )?.groupValues?.get(1).toString().toIntOrNull()
        val tvType = if (document.select("ul#section > li:nth-child(1)").text()
                .contains("Episodes") || document.select("ul#playeroptionsul li span.title")
                .text().contains(
                    Regex("Episode\\s+\\d+|EP\\d+|PE\\d+|S\\d{2}|E\\d{2}")
                )
        ) TvType.TvSeries else TvType.Movie
        val description = document.select("div.wp-content > p").text().trim()
        val trailer = document.selectFirst("div.embed iframe")?.attr("src")
        val rating =
            document.selectFirst("span.dt_rating_vgs")?.text()?.toRatingInt()
        val actors = document.select("div.persons > div[itemprop=actor]").map {
            Actor(
                it.select("meta[itemprop=name]").attr("content"),
                it.select("img:last-child").attr("src")
            )
        }

        val recommendations = document.select("div.owl-item").map {
            val recName = it.selectFirst("a")!!.attr("href").removeSuffix("/").split("/").last()
            val recHref = it.selectFirst("a")!!.attr("href")
            val recPosterUrl = it.selectFirst("img")?.getImageAttr()
            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = if (document.select("ul.episodios > li").isNotEmpty()) {
                document.select("ul.episodios > li").map {
                    val href = it.select("a").attr("href")
                    val name = fixTitle(it.select("div.episodiotitle > a").text().trim())
                    val image = it.selectFirst("div.imagen > img")?.getImageAttr()
                    val episode =
                        it.select("div.numerando").text().replace(" ", "").split("-").last()
                            .toIntOrNull()
                    val season =
                        it.select("div.numerando").text().replace(" ", "").split("-").first()
                            .toIntOrNull()
                    newEpisode(href)
                    {
                        this.name=name
                        this.episode=episode
                        this.season=season
                        this.posterUrl=image
                    }
                }
            } else {
            val check = document.select("ul#playeroptionsul > li").toString().contains("Super")
				if (check) {
				    document.select("ul#playeroptionsul > li")
				        .filter { !it.attr("data-nume").equals("trailer", ignoreCase = true) }
				        .drop(1).map {
				        val name = it.selectFirst("span.title")?.text()
				        val type = it.attr("data-type")
				        val post = it.attr("data-post")
				        val nume = it.attr("data-nume")
                        newEpisode(LinkData(name, type, post, nume, directUrl).toJson())
                        {
                            this.name=name
                        }
				    }
				} else {
				    document.select("ul#playeroptionsul > li")
				        .filter { !it.attr("data-nume").equals("trailer", ignoreCase = true) }
				        .map {
				        val name = it.selectFirst("span.title")?.text()
				        val type = it.attr("data-type")
				        val post = it.attr("data-post")
				        val nume = it.attr("data-nume")
                        newEpisode(LinkData(name, type, post, nume, directUrl).toJson())
                        {
                            this.name=name
                        }
				    }
				}
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl= background
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.backgroundPosterUrl= background
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    data class LinkData(
        val name: String?,
        val type: String,
        val post: String,
        val nume: String,
        val url: String
    )

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("type") val type: String? = null,
    )

    private fun Element.getImageAttr(): String? {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = if (data.startsWith("http")) {
                app.get(data).document
            } else {
                val linkData = AppUtils.parseJson<LinkData>(data)
                val body = FormBody.Builder()
                    .addEncoded("action", "doo_player_ajax")
                    .addEncoded("post", linkData.post)
                    .addEncoded("nume", linkData.nume)
                    .addEncoded("type", linkData.type)
                    .build()

                val postResponse = app.post(
                    url = "${linkData.url}/wp-admin/admin-ajax.php",
                    requestBody = body,
                    referer = linkData.url
                ).parsed<ResponseHash>()

                app.get(
                    postResponse.embed_url,
                    referer = linkData.url
                ).document
            }

            var linksFound = 0

            // Extract iframe sources
            document.select("iframe").forEach { iframe ->
                var iframeSrc = iframe.attr("src")
                
                // Handle relative URLs
                if (iframeSrc.isNotEmpty() && !iframeSrc.startsWith("http")) {
                    iframeSrc = if (iframeSrc.startsWith("//")) {
                        "https:$iframeSrc"
                    } else if (iframeSrc.startsWith("/")) {
                        "$directUrl$iframeSrc"
                    } else {
                        "$directUrl/$iframeSrc"
                    }
                }
                
                if (iframeSrc.isNotEmpty()) {
                    try {
                        com.lagradost.api.Log.d("Movierulzhd", "Extracting from: $iframeSrc")
                        val extracted = loadExtractor(iframeSrc, directUrl, subtitleCallback, callback)
                        if (extracted) {
                            linksFound++
                        }
                    } catch (e: Exception) {
                        com.lagradost.api.Log.e("Movierulzhd", "Failed to extract from $iframeSrc: ${e.message}")
                    }
                }
            }

            // Also check for direct video links in source tags
            document.select("source").forEach { source ->
                val videoSrc = source.attr("src")
                if (videoSrc.isNotEmpty() && (videoSrc.contains(".m3u8") || videoSrc.contains(".mp4") || videoSrc.contains(".mkv"))) {
                    try {
                        callback.invoke(
                            com.lagradost.cloudstream3.utils.newExtractorLink(
                                "Direct",
                                "Direct",
                                videoSrc
                            ) {
                                this.referer = directUrl
                            }
                        )
                        linksFound++
                    } catch (e: Exception) {
                        com.lagradost.api.Log.e("Movierulzhd", "Failed to add direct link: ${e.message}")
                    }
                }
            }

            // Check for video URLs in script tags
            document.select("script").forEach { script ->
                val scriptData = script.data()
                
                // Look for m3u8 URLs
                if (scriptData.contains(".m3u8")) {
                    val m3u8Regex = """(https?://[^\s"']+\.m3u8[^\s"']*)""".toRegex()
                    m3u8Regex.findAll(scriptData).forEach { match ->
                        val m3u8Url = match.groupValues[1]
                        try {
                            com.lagradost.cloudstream3.utils.M3u8Helper.generateM3u8(
                                source = "Embedded",
                                streamUrl = m3u8Url,
                                referer = directUrl
                            ).forEach(callback)
                            linksFound++
                        } catch (e: Exception) {
                            com.lagradost.api.Log.e("Movierulzhd", "Failed to extract m3u8: ${e.message}")
                        }
                    }
                }
                
                // Look for mp4/mkv URLs
                if (scriptData.contains(".mp4") || scriptData.contains(".mkv")) {
                    val videoRegex = """(https?://[^\s"']+\.(mp4|mkv)[^\s"']*)""".toRegex()
                    videoRegex.findAll(scriptData).forEach { match ->
                        val videoUrl = match.groupValues[1]
                        try {
                            callback.invoke(
                                com.lagradost.cloudstream3.utils.newExtractorLink(
                                    "Embedded",
                                    "Embedded",
                                    videoUrl
                                ) {
                                    this.referer = directUrl
                                }
                            )
                            linksFound++
                        } catch (e: Exception) {
                            com.lagradost.api.Log.e("Movierulzhd", "Failed to add video link: ${e.message}")
                        }
                    }
                }
            }

            com.lagradost.api.Log.d("Movierulzhd", "Total links found: $linksFound")
            return linksFound > 0
        } catch (e: Exception) {
            com.lagradost.api.Log.e("Movierulzhd", "loadLinks error: ${e.message}")
            return false
        }
    }
}
