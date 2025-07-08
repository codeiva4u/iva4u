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
        "@homepage" to "Home",
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

        if (request.data == "@homepage") {
            if (page > 1) return HomePageResponse(emptyList())

            val document = app.get(mainUrl, headers = headers).document
            val home = ArrayList<HomePageList>()
            
            document.select("div.items.full, div.items.featured, #slider-movies-tvshows").forEach { section ->
                val titleElement = section.previousElementSibling()
                val title = titleElement?.selectFirst("span.title")?.text()?.ifEmpty {
                    titleElement.text()
                } ?: "Featured"

                val items = section.select("article.item").mapNotNull { it.toSearchResult() }

                if (items.isNotEmpty()) {
                    home.add(HomePageList(title, items))
                }
            }
            return HomePageResponse(home)
        }

        val document = if (page == 1) {
            app.get("$mainUrl/${request.data}", headers = headers).document
        } else {
            app.get("$mainUrl/${request.data}" + "page/$page/", headers = headers).document
        }
        
        val home = document.select("div.items article.item, #archive-content article.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(HomePageList(request.name, home))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.data > h3 > a, div.data h3 a, .title > a, h3 > a, h2 > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("div.data > h3 > a, div.data h3 a, .title > a, h3 > a, h2 > a")?.attr("href").toString())
        
        val posterUrl = fixUrlNull(this.selectFirst("div.poster > img, div.image > a > img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        })
        
        val quality = getQualityFromString(this.select("div.poster div.mepo span.quality, span.quality").text())
        val typeText = this.select("div.poster > a > span, .type, .dtyear").text()

        return if (typeText.contains("Movie", ignoreCase = true) || href.contains("movies", ignoreCase = true)) {
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
        return document.select(".search-page .result-item article, .results-post article").mapNotNull {
            it.toSearchResult()
        }
    }

    private suspend fun getEmbed(postid: String?, nume: String, referUrl: String?): NiceResponse? {
        return try {
            val body = FormBody.Builder()
                .addEncoded("action", "doo_player_ajax")
                .addEncoded("post", postid.toString())
                .addEncoded("nume", nume)
                .addEncoded("type", if (referUrl?.contains("tvshows") == true) "tv" else "movie")
                .build()

            app.post(
                url = "$mainUrl/wp-admin/admin-ajax.php",
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
        val doc = app.get(url).document
        
        val title = doc.selectFirst("div.sheader div.data h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(doc.selectFirst("div.poster > img")?.attr("src"))
        val tags = doc.select("div.sgeneros > a").map { it.text() }
        val yearElement = doc.selectFirst("span.date, .extra .date")
        val year = yearElement?.text()?.let {
            Regex("(\\d{4})").find(it)?.value?.toIntOrNull()
        }

        val description = doc.selectFirst("#info .wp-content p, .single_contenido p")?.text()?.trim()
        val type = if (doc.selectFirst("#episodes") != null || url.contains("tvshows")) TvType.TvSeries else TvType.Movie
        
        val trailer = doc.selectFirst("#player-option-trailer")?.attr("data-post")?.let { postId ->
            getEmbed(postId, "trailer", url)?.parsed<TrailerUrl>()?.embedUrl?.let { fixUrlNull(it) }
        }

        val rating = doc.select("span.dt_rating_vgs").text().toRatingInt()
        val duration = doc.selectFirst("span.runtime")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        
        val actors = doc.select("div.person").mapNotNull {
            val name = it.selectFirst(".name a")?.text() ?: return@mapNotNull null
            val image = it.selectFirst("div.img img")?.attr("src")
            val role = it.selectFirst(".caracter")?.text()
            ActorData(Actor(name, image), roleString = role)
        }

        val recommendations = doc.select("#dtw_content_related-2 article, #related-movies article").mapNotNull {
            it.toSearchResult()
        }

        val episodes = doc.select("#seasons .se-c")
            .flatMapIndexed { seasonNum, seasonElement ->
                seasonElement.select(".episodios li").mapNotNull { epElement ->
                    val href = epElement.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val name = epElement.selectFirst(".episodiotitle a")?.text()
                    val posterEp = fixUrlNull(epElement.selectFirst(".imagen img")?.attr("src"))
                    val episode = epElement.selectFirst(".numerando")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                    
                    newEpisode(href) {
                        this.name = name
                        this.season = seasonNum + 1
                        this.episode = episode
                        this.posterUrl = posterEp
                    }
                }
            }

        return if (type == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
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
