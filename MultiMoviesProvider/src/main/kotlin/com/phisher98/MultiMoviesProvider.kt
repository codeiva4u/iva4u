package com.phisher98


import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import okhttp3.FormBody
import org.jsoup.nodes.Element

class MultiMoviesProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl: String = "https://multimovies.cheap"
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
        "movies/" to "Latest Release",
        "genre/bollywood-movies/" to "Bollywood Movies",
        "genre/hollywood/" to "Hollywood Movies",
        "genre/south-indian/" to "South Indian Movies",
        "genre/amazon-prime/" to "Amazon Prime",
        "genre/disney-hotstar/" to "Jio Hotstar",
        "genre/netflix/" to "Netfilx",
        "genre/sony-liv/" to "Sony Live",
        "genre/zee-5/" to "Zee5"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = if (page == 1) {
            app.get("$mainUrl/${request.data}").document
        } else {
            app.get("$mainUrl/${request.data}" + "page/$page/").document
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
        return newHomePageResponse(HomePageList(request.name, home))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.data > h3 > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("div.data > h3 > a")?.attr("href").toString())
        val posterUrl = fixUrlNull(this.selectFirst("div.poster > img")?.getImageAttr())
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
    
    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("type") val type: String? = null,
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val titleL = doc.selectFirst("div.sheader > div.data > h1")?.text()?.trim() ?: return null
        val titleRegex = Regex("(^.*\\)\\d*)")
        val titleClean = titleRegex.find(titleL)?.groups?.get(1)?.value.toString()
        val title = if (titleClean == "null") titleL else titleClean
        val poster = fixUrlNull(
            doc.select("div.g-item a").attr("href")
        )
        val tags = doc.select("div.sgeneros > a").map { it.text() }
        val year = doc.selectFirst("span.date")?.text()?.substringAfter(",")?.trim()?.toInt()
        val description = doc.selectFirst("#info div.wp-content p")?.text()?.trim()
        val type = if (url.contains("tvshows")) TvType.TvSeries else TvType.Movie
        val trailerRegex = Regex("\"http.*\"")

        var trailer: String? = if (type == TvType.Movie) {
            try {
                val postId = doc.select("#player-option-trailer").attr("data-post")
                val embedResponse = getEmbed(postId, "trailer", url)
                val parsed = embedResponse.parsed<TrailerUrl>()
                parsed.embedUrl?.let { fixUrlNull(it) }
            } catch (_: Exception) {
                null
            }
        } else {
            val iframeSrc = doc.select("iframe.rptss").attr("src")
            fixUrlNull(iframeSrc)
        }
        trailer = trailer?.let { trailerRegex.find(it)?.value?.trim('"') }
        val rating = doc.select("span.dt_rating_vgs").text()
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
                        this.posterUrl = it.selectFirst("div.imagen > img")?.getImageAttr()
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
                this.score = Score.from10(rating)
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
                this.score = Score.from10(rating)
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
        
        Log.d("MultiMovies", "========== loadLinks START ==========")
        Log.d("MultiMovies", "URL: $data")
        
        try {
            // Get the document from the data URL
            val document = app.get(data).document
            Log.d("MultiMovies", "Page fetched successfully")
            
            // Extract player options
            val allOptions = document.select("ul#playeroptionsul > li")
            Log.d("MultiMovies", "Total options found (including trailer): ${allOptions.size}")
            
            val playerOptions = allOptions.filterNot { it.attr("data-nume").equals("trailer", ignoreCase = true) }
            Log.d("MultiMovies", "Player options (excluding trailer): ${playerOptions.size}")
            
            if (playerOptions.isEmpty()) {
                Log.e("MultiMovies", "ERROR: No player options found!")
                Log.d("MultiMovies", "HTML snippet: ${document.select("ul#playeroptionsul").html().take(500)}")
                return false
            }
            
            playerOptions.forEachIndexed { index, element ->
                Log.d("MultiMovies", "--- Processing option ${index + 1} ---")
                
                val type = element.attr("data-type")
                val post = element.attr("data-post")
                val nume = element.attr("data-nume")
                val title = element.select("span.title").text()
                
                Log.d("MultiMovies", "Title: $title")
                Log.d("MultiMovies", "Type: '$type', Post: '$post', Nume: '$nume'")
                
                // Get iframe URL from player API
                val iframeUrl = getIframeUrl(type, post, nume)
                
                if (iframeUrl.isNullOrEmpty()) {
                    Log.e("MultiMovies", "ERROR: getIframeUrl returned null/empty")
                } else {
                    Log.d("MultiMovies", "Success! Iframe URL: $iframeUrl")
                    loadExtractorLink(iframeUrl, data, subtitleCallback, callback)
                }
            }
            
            Log.d("MultiMovies", "========== loadLinks END ==========")
            return true
            
        } catch (e: Exception) {
            Log.e("MultiMovies", "FATAL ERROR in loadLinks: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    private suspend fun getIframeUrl(type: String, post: String, nume: String): String? {
        return try {
            Log.d("MultiMovies", "Calling player API with type=$type, post=$post, nume=$nume")
            
            // Call player API
            val requestBody = FormBody.Builder()
                .addEncoded("action", "doo_player_ajax")
                .addEncoded("post", post)
                .addEncoded("nume", nume)
                .addEncoded("type", type)
                .build()
            
            val response = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                requestBody = requestBody,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to mainUrl
                )
            ).parsedSafe<ResponseHash>()
            
            val embedUrl = response?.embed_url
            Log.d("MultiMovies", "Got embed URL from API: $embedUrl")
            
            embedUrl
        } catch (e: Exception) {
            Log.e("MultiMovies", "Error getting iframe URL: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    private suspend fun loadExtractorLink(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("MultiMovies", "loadExtractorLink called for: $url")
        
        // GdMirror/GtxGamer domains
        if (url.contains("gdmirrorbot", ignoreCase = true) || 
            url.contains("gdmirror", ignoreCase = true) ||
            url.contains("gtxgamer", ignoreCase = true)) {
            Log.d("MultiMovies", "Using GdMirrorExtractor")
            GdMirrorExtractor().getUrl(url, referer, subtitleCallback, callback)
            return
        }
        
        // TechInMind domains
        if (url.contains("techinmind.space", ignoreCase = true) ||
            url.contains("ssn.techinmind", ignoreCase = true)) {
            Log.d("MultiMovies", "Using TechInMindExtractor")
            TechInMindExtractor().getUrl(url, referer, subtitleCallback, callback)
            return
        }
        
        // MultiMoviesShg - main video hoster
        if (url.contains("multimoviesshg", ignoreCase = true)) {
            Log.d("MultiMovies", "Using MultiMoviesShgExtractor")
            MultiMoviesShgExtractor().getUrl(url, referer, subtitleCallback, callback)
            return
        }
        
        // Use built-in CloudStream extractors for all other hosters
        Log.d("MultiMovies", "Using built-in loadExtractor")
        loadExtractor(url, referer, subtitleCallback, callback)
    }
    
    private fun Element.getImageAttr(): String? {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }
}
