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
        Log.d("MultiMovies", "Loading main page: ${request.name}, data: ${request.data}, page: $page")
        
        val document = if (page == 1) {
            app.get("$mainUrl/${request.data}").document
        } else {
            app.get("$mainUrl/${request.data}" + "page/$page/").document
        }
        
        val home = when {
            // For movies listing page
            request.data.contains("/movies") -> {
                document.select("#archive-content > article").mapNotNull {
                    it.toSearchResult()
                }
            }
            
            // For genre/category pages
            request.data.contains("/genre/") || request.data.contains("/category/") -> {
                document.select("div.items > article, #archive-content > article").mapNotNull {
                    it.toSearchResult()
                }
            }
            
            // For main page (homepage)
            request.data.isEmpty() || request.data == "/" -> {
                // Get movies from both featured and regular movie sections
                val featuredMovies = document.select("#featured-titles .item.movies, #featured-titles .item.tvshows").mapNotNull {
                    it.toSearchResult()
                }
                val regularMovies = document.select("#dt-movies .item").mapNotNull {
                    it.toSearchResult()
                }
                val archiveMovies = document.select("#archive-content > article").mapNotNull {
                    it.toSearchResult()
                }
                
                // Combine all movie results, removing duplicates
                (featuredMovies + regularMovies + archiveMovies).distinctBy { it.name }
            }
            
            // Default fallback
            else -> {
                document.select("div.items > article, #archive-content > article").mapNotNull {
                    it.toSearchResult()
                }
            }
        }
        
        Log.d("MultiMovies", "Found ${home.size} items for ${request.name}")
        return newHomePageResponse(HomePageList(request.name, home))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Extract title with multiple fallback selectors
        val title = when {
            this.selectFirst("div.data > h3 > a") != null ->
                this.selectFirst("div.data > h3 > a")?.text()?.trim()
            this.selectFirst("h3.title > a") != null ->
                this.selectFirst("h3.title > a")?.text()?.trim()
            this.selectFirst("h3 > a") != null ->
                this.selectFirst("h3 > a")?.text()?.trim()
            this.selectFirst(".title a") != null ->
                this.selectFirst(".title a")?.text()?.trim()
            else -> null
        } ?: return null
        
        // Extract href with multiple fallback selectors
        val href = when {
            this.selectFirst("div.data > h3 > a") != null ->
                fixUrl(this.selectFirst("div.data > h3 > a")?.attr("href").toString())
            this.selectFirst("h3.title > a") != null ->
                fixUrl(this.selectFirst("h3.title > a")?.attr("href").toString())
            this.selectFirst("h3 > a") != null ->
                fixUrl(this.selectFirst("h3 > a")?.attr("href").toString())
            this.selectFirst(".title a") != null ->
                fixUrl(this.selectFirst(".title a")?.attr("href").toString())
            else -> return null
        }
        
        // Extract poster with multiple fallback selectors
        // ✅ FIXED: Reordered based on actual website DOM structure from browser inspection
        var posterUrl = when {
            // Priority 1: Genre/Category pages structure (Most Common)
            // Structure: article.item.movies > div.poster > img[src]
            this.selectFirst("div.poster > img") != null ->
                fixUrlNull(this.selectFirst("div.poster > img")?.attr("src"))
            
            // Priority 2: Homepage structure  
            // Structure: article.item > div.image > a > img[src]
            this.selectFirst("div.image > a > img") != null ->
                fixUrlNull(this.selectFirst("div.image > a > img")?.attr("src"))
            
            // Priority 3: Direct img in image div (fallback)
            this.selectFirst("div.image > img") != null ->
                fixUrlNull(this.selectFirst("div.image > img")?.attr("src"))
            
            // Priority 4: Other common patterns
            this.selectFirst("div.thumbnail > img") != null ->
                fixUrlNull(this.selectFirst("div.thumbnail > img")?.attr("src"))
            this.selectFirst("div.imagen > img") != null ->
                fixUrlNull(this.selectFirst("div.imagen > img")?.attr("src"))
            
            // Priority 5: Generic class-based selectors
            this.selectFirst(".poster img") != null ->
                fixUrlNull(this.selectFirst(".poster img")?.attr("src"))
            this.selectFirst(".image img") != null ->
                fixUrlNull(this.selectFirst(".image img")?.attr("src"))
            this.selectFirst(".thumbnail img") != null ->
                fixUrlNull(this.selectFirst(".thumbnail img")?.attr("src"))
            
            // Priority 6: Generic img with attributes (lowest priority)
            this.selectFirst("img[src]") != null ->
                fixUrlNull(this.selectFirst("img[src]")?.attr("src"))
            this.selectFirst("img[data-src]") != null ->
                fixUrlNull(this.selectFirst("img[data-src]")?.attr("data-src"))
            
            else -> null
        }
        
        Log.d("MultiMovies", "Title: $title, Poster: $posterUrl")
        
        // Enhanced fallback: Try to get poster from parent elements if no direct img found
        if (posterUrl.isNullOrBlank()) {
            val posterDiv = this.selectFirst("div.poster, .poster, .image, div.thumbnail, div.imagen")
            if (posterDiv != null) {
                posterUrl = posterDiv.getImageAttr()
                Log.d("MultiMovies", "Fallback poster from div: $posterUrl")
            }
            
            // Additional fallback: Try style attribute background image
            if (posterUrl.isNullOrBlank()) {
                val styleBg = this.selectFirst("[style*='background-image']")
                if (styleBg != null) {
                    posterUrl = styleBg.getImageAttr()
                    Log.d("MultiMovies", "Style background poster: $posterUrl")
                }
            }
            
            // Enhanced fallback: Try data-bg, data-background attributes
            if (posterUrl.isNullOrBlank()) {
                val dataBg = this.selectFirst("[data-bg], [data-background]")
                if (dataBg != null) {
                    posterUrl = dataBg.getImageAttr()
                    Log.d("MultiMovies", "Data-bg poster: $posterUrl")
                }
            }
        }
        
        // Extract quality
        val quality = when {
            this.select("div.poster > div.mepo > span").isNotEmpty() ->
                getQualityFromString(this.select("div.poster > div.mepo > span").text())
            this.select(".mepo .quality").isNotEmpty() ->
                getQualityFromString(this.select(".mepo .quality").text())
            this.select(".rating").isNotEmpty() ->
                getQualityFromString(this.select(".rating").text())
            else -> null
        }
        
        // Determine content type
        val isMovie = when {
            href.contains("movie", ignoreCase = true) -> true
            href.contains("tvshow", ignoreCase = true) -> false
            this.hasClass("movies") -> true
            this.hasClass("tvshows") -> false
            this.select(".item_type").text().contains("Movie", ignoreCase = true) -> true
            this.select(".item_type").text().contains("TV", ignoreCase = true) -> false
            else -> true // Default to movie
        }
        
        return if (isMovie) {
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
        Log.d("MultiMovies", "Searching for: $query")
        val document = app.get("$mainUrl/?s=$query").document
        
        return document.select("div.result-item").mapNotNull { result ->
            // Extract title with multiple fallback selectors
            val title = when {
                result.selectFirst("article > div.details > div.title > a") != null ->
                    result.selectFirst("article > div.details > div.title > a")?.text()?.trim()
                result.selectFirst("div.title > a") != null ->
                    result.selectFirst("div.title > a")?.text()?.trim()
                result.selectFirst("h3 > a") != null ->
                    result.selectFirst("h3 > a")?.text()?.trim()
                result.selectFirst(".title a") != null ->
                    result.selectFirst(".title a")?.text()?.trim()
                else -> null
            }
            
            if (title.isNullOrBlank()) return@mapNotNull null
            
            // Extract href with multiple fallback selectors
            val href = when {
                result.selectFirst("article > div.details > div.title > a") != null ->
                    fixUrl(result.selectFirst("article > div.details > div.title > a")?.attr("href").toString())
                result.selectFirst("div.title > a") != null ->
                    fixUrl(result.selectFirst("div.title > a")?.attr("href").toString())
                result.selectFirst("h3 > a") != null ->
                    fixUrl(result.selectFirst("h3 > a")?.attr("href").toString())
                result.selectFirst(".title a") != null ->
                    fixUrl(result.selectFirst(".title a")?.attr("href").toString())
                else -> return@mapNotNull null
            }
            
            // ✅ FIXED: Enhanced poster extraction for search results
            // Reordered based on actual DOM structure from browser inspection
            var posterUrl = when {
                // Priority 1: Search result article structure (Most common)
                // Structure: article > div.image > div.thumbnail.animation-2 > a > img
                result.selectFirst("div.image > div.thumbnail.animation-2 > a > img") != null ->
                    fixUrlNull(result.selectFirst("div.image > div.thumbnail.animation-2 > a > img")?.attr("src"))
                
                //Priority 2: Direct poster structure
                result.selectFirst("div.poster > img") != null ->
                    fixUrlNull(result.selectFirst("div.poster > img")?.attr("src"))
                
                // Priority 3: Simple image in div
                result.selectFirst("div.image > a > img") != null ->
                    fixUrlNull(result.selectFirst("div.image > a > img")?.attr("src"))
                result.selectFirst("div.image > img") != null ->
                    fixUrlNull(result.selectFirst("div.image > img")?.attr("src"))
                result.selectFirst("div.thumbnail > img") != null ->
                    fixUrlNull(result.selectFirst("div.thumbnail > img")?.attr("src"))
                result.selectFirst("div.imagen > img") != null ->
                    fixUrlNull(result.selectFirst("div.imagen > img")?.attr("src"))
                
                // Priority 4: Generic class selectors
                result.selectFirst(".poster img") != null ->
                    fixUrlNull(result.selectFirst(".poster img")?.attr("src"))
                result.selectFirst(".image img") != null ->
                    fixUrlNull(result.selectFirst(".image img")?.attr("src"))
                result.selectFirst(".thumbnail img") != null ->
                    fixUrlNull(result.selectFirst(".thumbnail img")?.attr("src"))
                
                // Priority 5: Generic img tags (lowest priority)
                result.selectFirst("img[src]") != null ->
                    fixUrlNull(result.selectFirst("img[src]")?.attr("src"))
                result.selectFirst("img[data-src]") != null ->
                    fixUrlNull(result.selectFirst("img[data-src]")?.attr("data-src"))
                result.selectFirst("img[data-lazy-src]") != null ->
                    fixUrlNull(result.selectFirst("img[data-lazy-src]")?.attr("data-lazy-src"))
                
                else -> null
            }
            
            // Enhanced fallback poster extraction
            if (posterUrl.isNullOrBlank()) {
                // Try direct img tag in article
                posterUrl = fixUrlNull(result.selectFirst("img")?.getImageAttr())
            }
            
            // Enhanced fallback: Try background image and style attributes
            if (posterUrl.isNullOrBlank()) {
                val articleImg = result.selectFirst("article > div.image > div.thumbnail.animation-2, div.thumbnail, .image, div.imagen")
                if (articleImg != null) {
                    posterUrl = fixUrlNull(articleImg.getImageAttr())
                }
            }
            
            // Additional fallback: Try style background-image
            if (posterUrl.isNullOrBlank()) {
                val styleBg = result.selectFirst("[style*='background-image']")
                if (styleBg != null) {
                    posterUrl = fixUrlNull(styleBg.getImageAttr())
                }
            }
            
            // Final fallback: Try data-bg attributes
            if (posterUrl.isNullOrBlank()) {
                val dataBg = result.selectFirst("[data-bg], [data-background]")
                if (dataBg != null) {
                    posterUrl = fixUrlNull(dataBg.getImageAttr())
                }
            }
            
            Log.d("MultiMovies", "Search - Title: $title, Poster: $posterUrl")
            
            val quality = when {
                result.select("div.poster > div.mepo > span").isNotEmpty() ->
                    getQualityFromString(result.select("div.poster > div.mepo > span").text())
                result.select(".mepo .quality").isNotEmpty() ->
                    getQualityFromString(result.select(".mepo .quality").text())
                else -> null
            }
            
            // Determine content type
            val isMovie = when {
                href.contains("movie", ignoreCase = true) -> true
                href.contains("tvshow", ignoreCase = true) -> false
                result.select("article > div.image > div.thumbnail.animation-2 > a > span").text().contains("Movie", ignoreCase = true) -> true
                result.select("article > div.image > div.thumbnail.animation-2 > a > span").text().contains("TV", ignoreCase = true) -> false
                result.hasClass("movies") -> true
                result.hasClass("tvshows") -> false
                else -> true // Default to movie
            }
            
            if (isMovie) {
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
        // ✅ FIXED: Enhanced poster extraction with multiple fallback selectors
        // Reordered based on actual website DOM structure from browser inspection
        var poster = when {
            // Priority 1: Detail page - sheader poster structure
            // Structure: div.sheader > div.poster > img[src]
            doc.selectFirst("div.sheader div.poster img") != null ->
                fixUrlNull(doc.selectFirst("div.sheader div.poster img")?.attr("src"))
            
            // Priority 2: General poster/image structures
            doc.selectFirst("div.poster > img") != null ->
                fixUrlNull(doc.selectFirst("div.poster > img")?.attr("src"))
            doc.selectFirst("div.image > a > img") != null ->
                fixUrlNull(doc.selectFirst("div.image > a > img")?.attr("src"))
            doc.selectFirst("div.image > img") != null ->
                fixUrlNull(doc.selectFirst("div.image > img")?.attr("src"))
            doc.selectFirst("div.thumbnail > img") != null ->
                fixUrlNull(doc.selectFirst("div.thumbnail > img")?.attr("src"))
            doc.selectFirst("div.imagen > img") != null ->
                fixUrlNull(doc.selectFirst("div.imagen > img")?.attr("src"))
            
            // Priority 3: Generic class selectors
            doc.selectFirst(".poster img") != null ->
                fixUrlNull(doc.selectFirst(".poster img")?.attr("src"))
            doc.selectFirst(".image img") != null ->
                fixUrlNull(doc.selectFirst(".image img")?.attr("src"))
            doc.selectFirst(".thumbnail img") != null ->
                fixUrlNull(doc.selectFirst(".thumbnail img")?.attr("src"))
            
            // Priority 4: Generic img with standard and data attributes
            doc.selectFirst("img[src]") != null ->
                fixUrlNull(doc.selectFirst("img[src]")?.attr("src"))
            doc.selectFirst("img[data-src]") != null ->
                fixUrlNull(doc.selectFirst("img[data-src]")?.attr("data-src"))
            doc.selectFirst("img[data-lazy-src]") != null ->
                fixUrlNull(doc.selectFirst("img[data-lazy-src]")?.attr("data-lazy-src"))
            
            else -> null
        }
        
        Log.d("MultiMovies", "Load poster URL: $poster")
        
        // Enhanced fallback: Try to get poster from other sources if main poster is null
        var finalPoster = poster
        if (poster.isNullOrBlank()) {
            // Try to get poster from meta tags
            val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
            if (!ogImage.isNullOrBlank()) {
                finalPoster = fixUrlNull(ogImage)
                Log.d("MultiMovies", "Fallback: Using OG image: $finalPoster")
            }
            
            // Try to get poster from Twitter meta tags
            if (finalPoster.isNullOrBlank()) {
                val twitterImage = doc.selectFirst("meta[name=twitter:image]")?.attr("content")
                if (!twitterImage.isNullOrBlank()) {
                    finalPoster = fixUrlNull(twitterImage)
                    Log.d("MultiMovies", "Fallback: Using Twitter image: $finalPoster")
                }
            }
            
            // Try to get from JSON-LD structured data
            if (finalPoster.isNullOrBlank()) {
                val jsonLd = doc.select("script[type=application/ld+json]").firstOrNull()?.text()
                if (!jsonLd.isNullOrBlank()) {
                    try {
                        val imageMatch = Regex("\"image\"\\s*:\\s*\"([^\"]+)\"").find(jsonLd)
                        if (imageMatch != null) {
                            finalPoster = fixUrlNull(imageMatch.groupValues[1])
                            Log.d("MultiMovies", "Fallback: Using JSON-LD image: $finalPoster")
                        }
                    } catch (e: Exception) {
                        Log.d("MultiMovies", "Error parsing JSON-LD: ${e.message}")
                    }
                }
            }
            
            // Try to get from style background-image
            if (finalPoster.isNullOrBlank()) {
                val styleBg = doc.selectFirst("[style*='background-image']")
                if (styleBg != null) {
                    finalPoster = fixUrlNull(styleBg.getImageAttr())
                    Log.d("MultiMovies", "Fallback: Using style background: $finalPoster")
                }
            }
            
            // Try data-bg attributes
            if (finalPoster.isNullOrBlank()) {
                val dataBg = doc.selectFirst("[data-bg], [data-background]")
                if (dataBg != null) {
                    finalPoster = fixUrlNull(dataBg.getImageAttr())
                    Log.d("MultiMovies", "Fallback: Using data-bg: $finalPoster")
                }
            }
        }
        
        // URL validation and cleanup
        finalPoster = finalPoster?.let { validateAndCleanUrl(it) }
        
        Log.d("MultiMovies", "Final validated poster: $finalPoster")
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
                        fixUrlNull(it.selectFirst("div.img > a > img")?.getImageAttr() ?:
                            it.selectFirst("div.img > img")?.getImageAttr() ?:
                            it.selectFirst("img[data-src]")?.getImageAttr() ?:
                            it.selectFirst("img")?.getImageAttr())
                    ),
                    roleString = it.select("div.data > div.caracter").text(),
                )
            }
        val recommendations = doc.select("#dtw_content_related-2 article").mapNotNull {
            try {
                it.toSearchResult()
            } catch (e: Exception) {
                null
            }
        }

        val episodes = ArrayList<Episode>()
        doc.select("#seasons ul.episodios").mapIndexed { seasonNum, season ->
            season.select("li").mapIndexed { epNum, it ->
                val epUrl = it.select("div.episodiotitle > a").attr("href")
                val epDoc = app.get(epUrl).document
                val epName = it.select("div.episodiotitle > a").text()
                val epPoster = fixUrlNull(it.selectFirst("div.imagen > img")?.getImageAttr() ?:
                    it.selectFirst("div.thumbnail > img")?.getImageAttr() ?:
                    it.selectFirst("div.image > img")?.getImageAttr() ?:
                    it.selectFirst("img[data-src]")?.getImageAttr() ?:
                    it.selectFirst("img[data-lazy-src]")?.getImageAttr() ?:
                    it.selectFirst("img")?.getImageAttr())
                
                Log.d("MultiMovies", "Episode: $epName, Poster: $epPoster")
                
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
                this.posterUrl = finalPoster?.trim()
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
                this.posterUrl = finalPoster?.trim()
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

    /**
     * Enhanced image attribute extraction with comprehensive lazy loading support
     * and URL validation
     */
    private fun Element.getImageAttr(): String? {
        // Try multiple image source attributes in priority order for better poster detection
        val imageUrl = when {
            // Priority 1: Standard src attribute
            this.hasAttr("src") -> this.attr("abs:src")
            
            // Priority 2: Lazy loading attributes (most common)
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("data-original") -> this.attr("abs:data-original")
            this.hasAttr("data-thumb") -> this.attr("abs:data-thumb")
            this.hasAttr("data-image") -> this.attr("abs:data-image")
            this.hasAttr("data-img") -> this.attr("abs:data-img")
            this.hasAttr("data-lazysrc") -> this.attr("abs:data-lazysrc")
            this.hasAttr("data-sources") -> this.attr("abs:data-sources")
            this.hasAttr("data-original-src") -> this.attr("abs:data-original-src")
            this.hasAttr("data-echo") -> this.attr("abs:data-echo")
            this.hasAttr("data-lazy") -> this.attr("abs:data-lazy")
            this.hasAttr("data-src-webp") -> this.attr("abs:data-src-webp")
            
            // Priority 3: Background image attributes
            this.hasAttr("data-background") -> this.attr("abs:data-background")
            this.hasAttr("data-bg") -> this.attr("abs:data-bg")
            this.hasAttr("data-style") -> {
                val style = this.attr("abs:data-style")
                // Extract URL from background-image style
                Regex("background-image:\\s*url\\(['\\\"]?([^'\\\"\\)]+)['\\\"]?\\)")
                    .find(style)?.groups?.get(1)?.value
            }
            this.hasAttr("style") -> {
                val style = this.attr("abs:style")
                // Extract URL from background-image style
                Regex("background-image:\\s*url\\(['\\\"]?([^'\\\"\\)]+)['\\\"]?\\)")
                    .find(style)?.groups?.get(1)?.value
            }
            
            // Priority 4: Srcset attributes
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            this.hasAttr("data-srcset") -> this.attr("abs:data-srcset").substringBefore(" ")
            this.hasAttr("data-original-srcset") -> this.attr("abs:data-original-srcset").substringBefore(" ")
            
            // Priority 5: Other src attributes
            this.hasAttr("abs:src") -> this.attr("abs:src")
            
            else -> null
        }
        
        // Clean and validate the URL
        val cleanUrl = validateAndCleanUrl(imageUrl?.trim())
        
        if (cleanUrl != null) {
            Log.d("MultiMovies", "Found image URL: $cleanUrl")
            return cleanUrl
        }
        
        Log.d("MultiMovies", "No valid image URL found in current element, trying child img elements...")
        
        // If no image URL found in current element, try to find img element inside
        val imgElement = this.selectFirst("img")
        if (imgElement != null) {
            // Try standard src first
            val imgSrc = imgElement.attr("abs:src")
            if (imgSrc.isNotBlank()) {
                val cleanImgSrc = validateAndCleanUrl(imgSrc)
                if (cleanImgSrc != null) {
                    Log.d("MultiMovies", "Found image in child img element: $cleanImgSrc")
                    return cleanImgSrc
                }
            }
            
            // Try lazy loading attributes for img element
            val imgLazySrc = when {
                imgElement.hasAttr("data-src") -> imgElement.attr("abs:data-src")
                imgElement.hasAttr("data-lazy-src") -> imgElement.attr("abs:data-lazy-src")
                imgElement.hasAttr("data-original") -> imgElement.attr("abs:data-original")
                imgElement.hasAttr("data-thumb") -> imgElement.attr("abs:data-thumb")
                imgElement.hasAttr("data-src-webp") -> imgElement.attr("abs:data-src-webp")
                else -> null
            }
            
            if (!imgLazySrc.isNullOrBlank()) {
                val cleanImgLazySrc = validateAndCleanUrl(imgLazySrc)
                if (cleanImgLazySrc != null) {
                    Log.d("MultiMovies", "Found image in child img lazy attribute: $cleanImgLazySrc")
                    return cleanImgLazySrc
                }
            }
        }
        
        Log.d("MultiMovies", "No valid image URL found")
        return null
    }
    
    /**
     * Validate and clean image URL with comprehensive error handling
     */
    private fun validateAndCleanUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        
        var cleanUrl = url.trim()
        
        // Remove common prefixes that might cause issues
        cleanUrl = cleanUrl.replace("url(", "").replace(")", "").replace("'", "").replace("\"", "")
        
        // Skip invalid URLs
        if (cleanUrl.isBlank() ||
            cleanUrl.startsWith("data:") ||
            cleanUrl.startsWith("javascript:") ||
            cleanUrl.contains("<script") ||
            cleanUrl.contains("</script>")) {
            return null
        }
        
        // If the image is from TMDB, try to use a higher quality version
        if (cleanUrl.contains("image.tmdb.org")) {
            cleanUrl = cleanUrl.replace("/w300/", "/w780/")
                               .replace("/w185/", "/w780/")
                               .replace("/w500/", "/w780/")
        }
        
        // If the image is from WordPress upload, ensure it's a valid URL
        if (cleanUrl.contains("/wp-content/uploads/") && !cleanUrl.startsWith("http")) {
            cleanUrl = mainUrl.trimEnd('/') + cleanUrl
        }
        
        // If relative URL, make it absolute
        if (cleanUrl.startsWith("//")) {
            cleanUrl = "https:$cleanUrl"
        } else if (cleanUrl.startsWith("/")) {
            cleanUrl = mainUrl.trimEnd('/') + cleanUrl
        }
        
        // Validate final URL format
        if (cleanUrl.startsWith("http")) {
            // Additional URL validation
            try {
                val uri = java.net.URI(cleanUrl)
                if (uri.scheme in listOf("http", "https") && uri.host != null) {
                    return cleanUrl
                }
            } catch (e: Exception) {
                Log.d("MultiMovies", "Invalid URL format: $cleanUrl, error: ${e.message}")
            }
        }
        
        return null
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
}
