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
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.FormBody
import org.jsoup.nodes.Element
import kotlin.math.abs
class MultiMoviesProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl: String = "https://multimovies.golf/"
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
                it.selectFirst("article > div.image > div.thumbnail.animation-2 > a > img")?.attr("src")
            )
            val quality = getQualityFromString(it.select("div.poster > div.mepo > span").text())
            val type = it.select("article > div.image > div.thumbnail.animation-2 > a > span").text()
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

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("type") val type: String? = null,
    )
    
    data class LinkData(
        val type: String,
        val post: String,
        val nume: String
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("MultiMovies", "========== LOAD START ==========")
        Log.d("MultiMovies", "Loading URL: $url")
        
        val doc = app.get(url).document
        val titleL = doc.selectFirst("div.sheader > div.data > h1")?.text()?.trim() ?: run {
            Log.e("MultiMovies", "ERROR: Title not found!")
            return null
        }
        val titleRegex = Regex("(^.*\\)\\d*)")
        val titleClean = titleRegex.find(titleL)?.groups?.get(1)?.value.toString()
        val title = if (titleClean == "null") titleL else titleClean
        val poster = fixUrlNull(
            doc.select("div.g-item a img").attr("abs:src")
        )
        val tags = doc.select("div.sgeneros > a").map { it.text() }
        val year = doc.selectFirst("span.date")?.text()?.substringAfter(",")?.trim()?.toInt()
        val description = doc.selectFirst("#info div.wp-content p")?.text()?.trim()
        val type = if (url.contains("tvshows")) TvType.TvSeries else TvType.Movie
        val trailer = doc.selectFirst("iframe.rptss")?.attr("src")
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
        doc.select("#seasons ul.episodios").mapIndexed { seasonNum, season ->
            season.select("li").mapIndexed { epNum, it ->
                val epUrl = it.select("div.episodiotitle > a").attr("href")
                val epDoc = app.get(epUrl).document
                val epName = it.select("div.episodiotitle > a").text()
                val epPoster = it.selectFirst("div.imagen > img")?.getImageAttr()
                
                // Extract player options for this episode
                val playerOptions = epDoc.select("ul#playeroptionsul > li")
                    .filter { !it.attr("data-nume").equals("trailer", ignoreCase = true) }
                    .map { option ->
                        LinkData(
                            type = option.attr("data-type"),
                            post = option.attr("data-post"),
                            nume = option.attr("data-nume")
                        )
                    }
                
                episodes.add(
                    newEpisode(playerOptions.toJson()) {
                        this.name = epName
                        this.season = seasonNum + 1
                        this.episode = epNum + 1
                        this.posterUrl = epPoster
                    }
                )
            }
        }

        Log.d("MultiMovies", "Type detected: $type")
        
        // Extract player options for movies
        val moviePlayerOptions = if (type == TvType.Movie) {
            doc.select("ul#playeroptionsul > li")
                .filter { !it.attr("data-nume").equals("trailer", ignoreCase = true) }
                .map { option ->
                    LinkData(
                        type = option.attr("data-type"),
                        post = option.attr("data-post"),
                        nume = option.attr("data-nume")
                    )
                }
        } else {
            emptyList()
        }
        
        Log.d("MultiMovies", "Movie player options count: ${moviePlayerOptions.size}")
        Log.d("MultiMovies", "========== LOAD END ==========")
        
        return if (type == TvType.Movie) {
            val movieData = moviePlayerOptions.toJson()
            Log.d("MultiMovies", "Returning Movie LoadResponse with data: $movieData")
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                movieData
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
        
        Log.d("MultiMovies", "loadLinks called with data: $data")
        
        // Check if data is JSON (for episodes) or URL (for movies)
        val linkDataList = try {
            parseJson<List<LinkData>>(data)
        } catch (e: Exception) {
            null
        }
        
        if (linkDataList != null) {
            // Handle episodes - data is JSON list of LinkData
            linkDataList.forEach { linkData ->
                val iframeUrl = getIframeUrl(linkData.type, linkData.post, linkData.nume)
                if (!iframeUrl.isNullOrEmpty()) {
                    loadExtractorLink(iframeUrl, data, subtitleCallback, callback)
                }
            }
        } else {
            // Handle movies - data is URL
            val document = app.get(data).document
            
            // Extract player options (excluding trailer)
            document.select("ul#playeroptionsul > li")
                .filter { !it.attr("data-nume").equals("trailer", ignoreCase = true) }
                .forEach { element ->
                    val type = element.attr("data-type")
                    val post = element.attr("data-post")
                    val nume = element.attr("data-nume")
                    
                    // Get iframe URL from player API
                    val iframeUrl = getIframeUrl(type, post, nume)
                    if (!iframeUrl.isNullOrEmpty()) {
                        loadExtractorLink(iframeUrl, data, subtitleCallback, callback)
                    }
                }
        }
        
        return true
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
        
        // Streamwish
        if (url.contains("streamwish", ignoreCase = true)) {
            Log.d("MultiMovies", "Using StreamwishExtractor")
            StreamwishExtractor().getUrl(url, referer, subtitleCallback, callback)
            return
        }
        
        // VidHide
        if (url.contains("vidhide", ignoreCase = true) ||
            url.contains("filelion", ignoreCase = true)) {
            Log.d("MultiMovies", "Using VidHideExtractor")
            VidHideExtractor().getUrl(url, referer, subtitleCallback, callback)
            return
        }
        
        // Filepress
        if (url.contains("filepress", ignoreCase = true)) {
            Log.d("MultiMovies", "Using FilepressExtractor")
            FilepressExtractor().getUrl(url, referer, subtitleCallback, callback)
            return
        }
        
        // Gofile
        if (url.contains("gofile", ignoreCase = true)) {
            Log.d("MultiMovies", "Using GofileExtractor")
            GofileExtractor().getUrl(url, referer, subtitleCallback, callback)
            return
        }
        
        // Buzzheavier
        if (url.contains("buzzheavier", ignoreCase = true)) {
            Log.d("MultiMovies", "Using BuzzheavierExtractor")
            BuzzheavierExtractor().getUrl(url, referer, subtitleCallback, callback)
            return
        }
        
        // GDtot
        if (url.contains("gdtot", ignoreCase = true)) {
            Log.d("MultiMovies", "Using GDtotExtractor")
            GDtotExtractor().getUrl(url, referer, subtitleCallback, callback)
            return
        }
        
        // RpmShare
        if (url.contains("rpmshare", ignoreCase = true) ||
            url.contains("rpmhub", ignoreCase = true)) {
            Log.d("MultiMovies", "Using RpmShareExtractor")
            RpmShareExtractor().getUrl(url, referer, subtitleCallback, callback)
            return
        }
        
        // StreamP2P
        if (url.contains("streamp2p", ignoreCase = true) ||
            url.contains("p2pplay", ignoreCase = true)) {
            Log.d("MultiMovies", "Using StreamP2PExtractor")
            StreamP2PExtractor().getUrl(url, referer, subtitleCallback, callback)
            return
        }
        
        // UpnShare
        if (url.contains("upnshare", ignoreCase = true) ||
            url.contains("uns.bio", ignoreCase = true)) {
            Log.d("MultiMovies", "Using UpnShareExtractor")
            UpnShareExtractor().getUrl(url, referer, subtitleCallback, callback)
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
