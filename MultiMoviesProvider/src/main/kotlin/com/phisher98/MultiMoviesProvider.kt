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
        try {
            Log.d("MultiMovies", "loadLinks called for: $data")
            
            // Get the document from the data URL
            val document = app.get(data).document
            
            // Extract player options (excluding trailer)
            val playerOptions = document.select("ul#playeroptionsul > li")
                .filterNot { it.attr("data-nume").equals("trailer", ignoreCase = true) }
            
            Log.d("MultiMovies", "Found ${playerOptions.size} player options")
            
            if (playerOptions.isEmpty()) {
                Log.e("MultiMovies", "No player options found!")
                return false
            }
            
            playerOptions.forEach { element ->
                try {
                    val type = element.attr("data-type").ifBlank { 
                        if (data.contains("/episodes/") || data.contains("/tvshows/")) "tv" else "movie" 
                    }
                    val post = element.attr("data-post")
                    val nume = element.attr("data-nume")
                    val optionTitle = element.select("span.title").text()
                    
                    Log.d("MultiMovies", "Processing option: $optionTitle (type=$type, post=$post, nume=$nume)")
                    
                    if (post.isBlank() || nume.isBlank()) {
                        Log.e("MultiMovies", "Missing post or nume data")
                        return@forEach
                    }
                    
                    // Get iframe URL from player API
                    val embedResponse = getEmbed(post, nume, data)
                    val iframeUrl = embedResponse.parsed<TrailerUrl>()?.embedUrl
                    
                    if (iframeUrl.isNullOrEmpty()) {
                        Log.e("MultiMovies", "No iframe URL received from API")
                        return@forEach
                    }
                    
                    Log.d("MultiMovies", "Got iframe URL: $iframeUrl")
                    loadExtractorLink(iframeUrl, data, subtitleCallback, callback)
                    
                } catch (e: Exception) {
                    Log.e("MultiMovies", "Error processing player option: ${e.message}")
                    e.printStackTrace()
                }
            }
            
            return true
            
        } catch (e: Exception) {
            Log.e("MultiMovies", "Fatal error in loadLinks: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    private suspend fun loadExtractorLink(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Handle specific extractors based on URL patterns
            when {
                // GdMirror domains - redirects to other hosters
                url.contains("gdmirrorbot", ignoreCase = true) || 
                url.contains("gdmirror", ignoreCase = true) ||
                url.contains("gtxgamer", ignoreCase = true) -> {
                    Log.d("MultiMovies", "Using GdMirrorExtractor for: $url")
                    GdMirrorExtractor().getUrl(url, referer, subtitleCallback, callback)
                }
                
                // TechInMind domains (for TV shows)
                url.contains("techinmind.space", ignoreCase = true) ||
                url.contains("ssn.techinmind", ignoreCase = true) -> {
                    Log.d("MultiMovies", "Using TechInMindExtractor for: $url")
                    TechInMindExtractor().getUrl(url, referer, subtitleCallback, callback)
                }
                
                // MultiMoviesShg - main video hoster (HIGHEST PRIORITY)
                url.contains("multimoviesshg", ignoreCase = true) -> {
                    Log.d("MultiMovies", "Using MultiMoviesShgExtractor for: $url")
                    MultiMoviesShgExtractor().getUrl(url, referer, subtitleCallback, callback)
                }
                
                // Streamwish domains
                url.contains("streamwish", ignoreCase = true) ||
                url.contains("streamwish.to", ignoreCase = true) ||
                url.contains("streamwish.com", ignoreCase = true) -> {
                    Log.d("MultiMovies", "Using StreamwishExtractor for: $url")
                    StreamwishExtractor().getUrl(url, referer, subtitleCallback, callback)
                }
                
                // VidHide domains
                url.contains("vidhide", ignoreCase = true) ||
                url.contains("vidhide.com", ignoreCase = true) -> {
                    Log.d("MultiMovies", "Using VidHideExtractor for: $url")
                    VidHideExtractor().getUrl(url, referer, subtitleCallback, callback)
                }
                
                // Filepress domains
                url.contains("filepress", ignoreCase = true) ||
                url.contains("filepress.store", ignoreCase = true) -> {
                    Log.d("MultiMovies", "Using FilepressExtractor for: $url")
                    FilepressExtractor().getUrl(url, referer, subtitleCallback, callback)
                }
                
                // Gofile domains
                url.contains("gofile", ignoreCase = true) ||
                url.contains("gofile.io", ignoreCase = true) -> {
                    Log.d("MultiMovies", "Using GofileExtractor for: $url")
                    GofileExtractor().getUrl(url, referer, subtitleCallback, callback)
                }
                
                // If no custom extractor matches, try built-in CloudStream extractors
                else -> {
                    Log.d("MultiMovies", "Using built-in extractor for: $url")
                    loadExtractor(url, referer, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e("MultiMovies", "Error loading extractor for $url: ${e.message}")
            // Fallback to built-in extractors
            try {
                loadExtractor(url, referer, subtitleCallback, callback)
            } catch (e2: Exception) {
                Log.e("MultiMovies", "Built-in extractor also failed: ${e2.message}")
            }
        }
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
