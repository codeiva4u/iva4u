package com.phisher98


import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import org.jsoup.nodes.Element
import java.net.URI

open class Movierulzhd : MainAPI() {

    override var mainUrl = "https://1movierulzhd.cv/"
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
        
        // Detect if this is a TV series based on URL or title
        val tvType = if (href.contains("/tvshows/") || href.contains("/episodes/") || href.contains("/seasons/") ||
            title.contains("season", ignoreCase = true) || title.contains("episode", ignoreCase = true) ||
            title.contains("series", ignoreCase = true) || title.contains("web series", ignoreCase = true)) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        
        return if (tvType == TvType.TvSeries) {
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
                    Regex("Episode\\s+\\d+|EP\\d+|PE\\d+")
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
            document.select("ul#playeroptionsul > li").mapNotNull {
                val name = it.selectFirst("span.title")?.text()
                val type = it.attr("data-type")
                val post = it.attr("data-post")
                val nume = it.attr("data-nume")
                
                // Skip if name is null or empty or contains "trailer" or "Super"
                if (name.isNullOrBlank() || name.contains("Trailer", ignoreCase = true) || name.contains("Super", ignoreCase = true)) {
                    return@mapNotNull null
                }
                
                // Extract episode and season numbers from the name
                // Handle formats like "S02 (E01-E04) Combined" or "Episode 1" or "EP01"
                val seasonRegex = Regex("(?:Season|S)\\s*(\\d+)", RegexOption.IGNORE_CASE)
                val episodeRegex = Regex("(?:Episode|EP|E)\\s*(\\d+)(?:-E?(\\d+))?", RegexOption.IGNORE_CASE)
                val batchRegex = Regex("\\(E(\\d+)-E?(\\d+)\\)", RegexOption.IGNORE_CASE)
                
                val seasonMatch = seasonRegex.find(name)
                val episodeMatch = episodeRegex.find(name)
                val batchMatch = batchRegex.find(name)
                
                val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = when {
                    batchMatch != null -> batchMatch.groupValues[1].toIntOrNull() // First episode in batch
                    episodeMatch != null -> episodeMatch.groupValues[1].toIntOrNull()
                    else -> null
                }
                
                newEpisode(LinkData(name, type, post, nume).toJson()) {
                    this.name = name
                    this.episode = episode
                    this.season = season
                }
            }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl= background
                this.year = year
                this.plot = description
                this.tags = tags
                @Suppress("DEPRECATION")
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
                @Suppress("DEPRECATION")
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Phisher repolink",data)
        if (data.startsWith("{")) {
            val loadData = AppUtils.tryParseJson<LinkData>(data)
            val source = app.post(
                url = "$directUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to "${loadData?.post}",
                    "nume" to "${loadData?.nume}",
                    "type" to "${loadData?.type}"
                ),
                referer = data,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsed<ResponseHash>().embed_url
            Log.d("Phisher repolink", source)
            if (!source.contains("youtube")) loadCustomExtractor(
                source,
                "$directUrl/",
                subtitleCallback,
                callback
            )
        } else {
            val document = app.get(data).document
            document.select("ul#playeroptionsul > li").mapNotNull {
                val title = it.selectFirst("span.title")?.text()
                // Skip trailers and other unwanted content
                if (title != null && (title.contains("Trailer", ignoreCase = true) || title.contains("Super", ignoreCase = true))) {
                    return@mapNotNull null
                }
                Triple(
                    it.attr("data-post"),
                    it.attr("data-nume"),
                    it.attr("data-type")
                )
            }.amap { (id, nume, type) ->
                if (id.isNotBlank() && nume.isNotBlank() && type.isNotBlank()) {
                    val source = app.post(
                        url = "$directUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to id,
                            "nume" to nume,
                            "type" to type
                        ),
                        referer = data, headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).parsed<ResponseHash>().embed_url
                    when {
                        !source.contains("youtube") -> {
                            loadExtractor(source, subtitleCallback, callback)
                        }
                        else -> return@amap
                    }
                }
            }
        }
        return true
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }
    private suspend fun loadCustomExtractor(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        quality: Int? = null,
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            CoroutineScope(Dispatchers.IO).launch {
                callback.invoke(link)
            }
        }
    }






    data class LinkData(
        val tag: String? = null,
        val type: String? = null,
        val post: String? = null,
        val nume: String? = null,
    )



    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("type") val type: String?,
    )
}
