package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.text.Normalizer


class HDhub4uProvider : MainAPI() {
    override var mainUrl: String = "https://new2.hdhub4u.fo"

    // Fallback domains in case primary fails
    private val fallbackDomains = listOf(
        "https://new2.hdhub4u.fo",
        "https://hdhub4u.foo",
        "https://hdhub4u.life"
    )

    init {
        runBlocking {
            basemainUrl?.takeIf { it.isNotBlank() }?.let {
                mainUrl = it
            }
        }
    }

    override var name = "HDHub4U"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries ,TvType.Anime
    )
    companion object
    {
        const val TMDBAPIKEY = "1865f43a0549ca50d341dd9ab8b29f49"
        const val TMDBBASE = "https://image.tmdb.org/t/p/original"
        const val TMDBAPI = "https://wild-surf-4a0d.phisher1.workers.dev"
        const val TAG = "EpisodeParser"

        // CloudflareKiller for bypassing Cloudflare protection on hubdrive/hubcloud
        val cfKiller by lazy { CloudflareKiller() }

        val basemainUrl: String? by lazy {
            runBlocking {
                try {
                    val response = app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json")
                    val json = response.text
                    val jsonObject = JSONObject(json)
                    jsonObject.optString("hdhub4u").takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/bollywood-movies/" to "Bollywood",
        "category/hollywood-movies/" to "Hollywood",
        "category/hindi-dubbed/" to "Hindi Dubbed",
        "category/south-hindi-movies/" to "South Hindi Dubbed",
        "category/web-series/" to "Web Series",
    )
    private val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0","Cookie" to "xla=s4t")

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(
            "$mainUrl/${request.data}page/$page/",
            cacheTime = 60,
            headers = headers,
            timeout = 30  // 30 second timeout to prevent slow loading
        ).documentLarge
        val home = doc.select("li.thumb").mapNotNull { toHomeResult(it) }
        return newHomePageResponse(request.name, home, true)
    }

    // Homepage uses li.thumb with figcaption/figure structure
    private fun toHomeResult(post: Element): SearchResponse {
        val titleText = post.select("figcaption p").text().trim()
        val title = cleanTitle(titleText)
        val url = post.select("figure a").attr("href").let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        }
        val posterUrl = post.select("figure img").attr("src")
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getSearchQuality(titleText)
        }
    }

    // Search page uses li.movie-card with h3.movie-title structure
    private fun toSearchResult(post: Element): SearchResponse {
        val titleText = post.select("h3.movie-title").text().trim()
        val title = cleanTitle(titleText)
        val url = post.select("a").attr("href").let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        }
        val posterUrl = post.select(".poster-wrapper img").attr("src")
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getSearchQuality(titleText)
        }
    }

    override suspend fun search(query: String,page: Int): SearchResponseList {
        val doc = app.get(
            "$mainUrl/page/$page/?s=$query",
            cacheTime = 60,
            headers = headers,
            timeout = 30
        ).documentLarge
        return doc.select("li.movie-card").mapNotNull { toSearchResult(it) }.toNewSearchResponseList()
    }

    private fun extractLinksATags(aTags: Elements): List<String> {
        val links = mutableListOf<String>()
        val baseUrl: List<String> = listOf("https://hdstream4u.com", "https://hubstream.art")
        baseUrl.forEachIndexed { index, _ ->
            var count = 0
            for (aTag in aTags) {
                val href = aTag.attr("href")
                if (href.contains(baseUrl[index])) {
                    try {
                        links[count] = links[count] + " , " + href
                    } catch (_: Exception) {
                        links.add(href)
                        count++
                    }
                }
            }
        }
        return links
    }


    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, cacheTime = 60, headers = headers, timeout = 30).documentLarge
        var title = doc.select("header.entry-header h1.entry-title").text().trim()
            .ifEmpty { doc.select("h1.page-title").text().trim() }

        val seasontitle = title
        val seasonNumber = Regex("(?i)\\bSeason\\s*(\\d+)\\b").find(seasontitle)?.groupValues?.get(1)?.toIntOrNull()

        val image = doc.select("meta[property=og:image]").attr("content")
        val plot = doc.selectFirst("div.entry-content p")?.text()?.trim()
        val tags = doc.select(".cat-links a").eachText().toMutableList()
        // Poster: Prefer TMDB poster (aligncenter class), fallback to first content image
        val poster = doc.selectFirst("div.entry-content img.aligncenter[src*=tmdb]")?.attr("src")
            ?: doc.selectFirst("div.entry-content img.aligncenter")?.attr("src")
            ?: doc.selectFirst("div.entry-content img[src*=tmdb]")?.attr("src")
            ?: doc.selectFirst("div.entry-content img")?.attr("src")
            ?: ""
        val trailer = doc.selectFirst(".responsive-embed-container > iframe:nth-child(1)")?.attr("src")
            ?.replace("/embed/", "/watch?v=")
        
        // Removed extractLinksATags call as it's not effectively used and we implement smart logic below

        val tvtype = if (title.contains("movie", ignoreCase = true) || title.contains("download", ignoreCase = true)) TvType.Movie else TvType.TvSeries

        var actorData: List<ActorData> = emptyList()
        var genre: List<String>? = null
        var year = ""
        var background: String = image
        var description: String? = null

        val imdbUrl = doc.select("a[href*='imdb.com']").attr("href")
        val tmdbHref = doc.select("a[href*='themoviedb.org']").attr("href")
        
        var tmdbIdResolved = ""
        if (tmdbHref.isNotBlank()) {
            tmdbIdResolved = tmdbHref.substringAfterLast("/").substringBefore("-").substringBefore("?")
        }

        if (tmdbIdResolved.isBlank() && imdbUrl.isNotBlank()) {
            val imdbIdOnly = imdbUrl.substringAfter("title/").substringBefore("/")
             try {
                val findText = app.get("$TMDBAPI/find/$imdbIdOnly?api_key=$TMDBAPIKEY&external_source=imdb_id").text
                if (findText.isNotBlank()) {
                    val findJson = JSONObject(findText)
                    val tvArr = findJson.optJSONArray("tv_results")
                    val movieArr = findJson.optJSONArray("movie_results")

                    when {
                        tvArr != null && tvArr.length() > 0 -> tmdbIdResolved = tvArr.optJSONObject(0)?.optInt("id")?.toString().orEmpty()
                        movieArr != null && movieArr.length() > 0 -> tmdbIdResolved = movieArr.optJSONObject(0)?.optInt("id")?.toString().orEmpty()
                    }
                }
            } catch (_: Exception) {
            }
        }

        val responseData: ResponseDataLocal? = if (tmdbIdResolved.isBlank()) null else kotlin.runCatching {
            val type = if (tvtype == TvType.TvSeries) "tv" else "movie"
            val detailsText = app.get(
                "$TMDBAPI/$type/$tmdbIdResolved?api_key=$TMDBAPIKEY&append_to_response=credits"
            ).text
            val detailsJson = if (detailsText.isNotBlank()) JSONObject(detailsText) else JSONObject()

            var metaName = detailsJson.optString("name")
                .takeIf { it.isNotBlank() }
                ?: detailsJson.optString("title").takeIf { it.isNotBlank() }
                ?: title

            if (seasonNumber != null && !metaName.contains("Season $seasonNumber", ignoreCase = true)) {
                metaName = "$metaName (Season $seasonNumber)"
            }

            val metaDesc = detailsJson.optString("overview").takeIf { it.isNotBlank() } ?: plot

            val yearRaw = detailsJson.optString("release_date").ifBlank { detailsJson.optString("first_air_date") }
            val metaYear = yearRaw.takeIf { it.isNotBlank() }?.take(4)
            val metaRating = detailsJson.optString("vote_average")

            val metaBackground = detailsJson.optString("backdrop_path")
                .takeIf { it.isNotBlank() }?.let { TMDBBASE + it } ?: image

            val actorDataList = mutableListOf<ActorData>()

            //cast
            detailsJson.optJSONObject("credits")?.optJSONArray("cast")?.let { castArr ->
                for (i in 0 until castArr.length()) {
                    val c = castArr.optJSONObject(i) ?: continue
                    val name = c.optString("name").takeIf { it.isNotBlank() } ?: c.optString("original_name").orEmpty()
                    val profile = c.optString("profile_path").takeIf { it.isNotBlank() }?.let { TMDBBASE + it }
                    val character = c.optString("character").takeIf { it.isNotBlank() }
                    val actor = Actor(name, profile)
                    actorDataList += ActorData(actor = actor, roleString = character)
                }
            }

            // genres
            val metaGenres = mutableListOf<String>()
            detailsJson.optJSONArray("genres")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(metaGenres::add)
                }
            }

            // episodes for TV season -> videos
            val videos = mutableListOf<VideoLocal>()
            if (tvtype == TvType.TvSeries && seasonNumber != null) {
                try {
                    val seasonText = app.get("$TMDBAPI/tv/$tmdbIdResolved/season/$seasonNumber?api_key=$TMDBAPIKEY").text
                    if (seasonText.isNotBlank()) {
                        val seasonJson = JSONObject(seasonText)
                        seasonJson.optJSONArray("episodes")?.let { epArr ->
                            for (i in 0 until epArr.length()) {
                                val ep = epArr.optJSONObject(i) ?: continue
                                val epNum = ep.optInt("episode_number")
                                val epName = ep.optString("name")
                                val epDesc = ep.optString("overview")
                                val epThumb = ep.optString("still_path").takeIf { it.isNotBlank() }?.let { TMDBBASE + it }
                                val epAir = ep.optString("air_date")
                                val epRating = ep.optString("vote_average").let { Score.from10(it.toString()) }

                                videos.add(
                                    VideoLocal(
                                        title = epName,
                                        season = seasonNumber,
                                        episode = epNum,
                                        overview = epDesc,
                                        thumbnail = epThumb,
                                        released = epAir,
                                        rating = epRating,
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            ResponseDataLocal(
                MetaLocal(
                    name = metaName,
                    description = metaDesc,
                    actorsData = actorDataList.ifEmpty { null },
                    year = metaYear,
                    background = metaBackground,
                    genres = metaGenres.ifEmpty { null },
                    videos = videos.ifEmpty { null },
                    rating = Score.from10(metaRating)
                )
            )
        }.getOrNull()

        if (responseData != null) {
            description = responseData.meta?.description ?: plot
            actorData = responseData.meta?.actorsData ?: emptyList()
            title = responseData.meta?.name ?: title
            year = responseData.meta?.year ?: ""
            background = responseData.meta?.background ?: image
            responseData.meta?.genres?.let { g ->
                genre = g
                for (gn in g) if (!tags.contains(gn)) tags.add(gn)
            }
        }

        if (tvtype == TvType.Movie) {
            val movieJsonArray = org.json.JSONArray()
            // Capture Link + Text for Sorting (including parent h3/h4/h5 text for quality/size info)
            doc.select("h3, h4, h5").forEach { headerElement ->
                val headerText = headerElement.text()
                headerElement.select("a[href]").forEach { link ->
                    val href = link.attr("href")
                    val linkText = link.text()
                    // Use header text if link text is empty, or combine both for better parsing
                    val fullText = if (linkText.isNotBlank() && headerText.contains(linkText)) headerText else "$headerText $linkText"
                    
                    // Include Download, quality markers (480p, 720p, 1080p, 4K, 2160p) or hub links
                    if(href.urlOrNull() != null && 
                       (fullText.contains("Download", true) || 
                        fullText.contains("480p", true) || 
                        fullText.contains("720p", true) || 
                        fullText.contains("1080p", true) ||
                        fullText.contains("4K", true) ||
                        fullText.contains("2160p", true) ||
                        href.contains("hubdrive", true) ||
                        href.contains("hubcloud", true) ||
                        href.contains("gadgetsweb", true))) {
                         val json = JSONObject()
                         json.put("url", href)
                         json.put("text", fullText.trim())
                         movieJsonArray.put(json)
                    }
                }
            }
            
            // Also check direct p a links (some sites use this)
            doc.select("p a[href]").forEach { link ->
                val href = link.attr("href")
                val text = link.text()
                // Check if already added
                var alreadyAdded = false
                for (i in 0 until movieJsonArray.length()) {
                    if (movieJsonArray.optJSONObject(i)?.optString("url") == href) {
                        alreadyAdded = true
                        break
                    }
                }
                if(!alreadyAdded && href.urlOrNull() != null && 
                   (text.contains("Download", true) || 
                    text.contains("480p", true) || 
                    text.contains("720p", true) || 
                    text.contains("1080p", true) ||
                    href.contains("hubdrive", true) ||
                    href.contains("hubcloud", true))) {
                     val json = JSONObject()
                     json.put("url", href)
                     json.put("text", text.trim())
                     movieJsonArray.put(json)
                }
            }

            Log.d("HDhub4u", "Movie links found: ${movieJsonArray.length()}")
            Log.d("HDhub4u", "POSTER DEBUG: poster=$poster, background=$background, image=$image")
            
            // Convert to proper JSON string for serialization
            val movieData = movieJsonArray.toString()

            return newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.backgroundPosterUrl = background
                this.posterUrl = poster
                this.year = year.toIntOrNull()
                this.plot = description ?: plot
                this.tags = genre ?: tags
                this.actors = actorData
                this.score = responseData?.meta?.rating
                addTrailer(trailer)
                addImdbUrl(imdbUrl)
            }
        } else {
            val episodesData = mutableListOf<Episode>()
            val epLinksMap = mutableMapOf<Int, MutableList<String>>()
            val episodeRegex = Regex("EPiSODE\\s*(\\d+)", RegexOption.IGNORE_CASE)

            doc.select("h3, h4").forEach { element ->
                val episodeNumberFromTitle = episodeRegex.find(element.text())?.groupValues?.get(1)?.toIntOrNull()
                val baseLinks = element.select("a[href]").mapNotNull { it -> it.attr("href").takeIf { it.isNotBlank() } }

                val isDirectLinkBlock = element.select("a").any {
                    it.text().contains(Regex("1080|720|4K|2160", RegexOption.IGNORE_CASE))
                }
                val allEpisodeLinks = mutableSetOf<String>()

                if (isDirectLinkBlock) {
                    baseLinks.forEach { url ->
                        try {
                            val resolvedUrl = getRedirectLinks(url.trim())
                            val episodeDoc = app.get(resolvedUrl).documentLarge

                            episodeDoc.select("h5 a").forEach { linkElement ->
                                val text = linkElement.text()
                                val link = linkElement.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                                val epNum = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()

                                if (epNum != null) {
                                    epLinksMap.getOrPut(epNum) { mutableListOf() }.add(link)
                                } else {
                                    Log.w(TAG, "Could not parse episode number from: $text")
                                }
                            }
                        } catch (_: Exception) {
                            Log.e(TAG, "Error resolving direct link for URL: $url")
                        }
                    }
                } else if (episodeNumberFromTitle != null) {
                    if (element.tagName() == "h4") {
                        var nextElement = element.nextElementSibling()
                        while (nextElement != null && nextElement.tagName() != "hr") {
                            val siblingLinks = nextElement.select("a[href]").mapNotNull { it -> it.attr("href").takeIf { it.isNotBlank() } }
                            allEpisodeLinks.addAll(siblingLinks)
                            nextElement = nextElement.nextElementSibling()
                        }
                    }

                    if (baseLinks.isNotEmpty()) {
                        allEpisodeLinks.addAll(baseLinks)
                    }

                    if (allEpisodeLinks.isNotEmpty()) {
                        Log.d(TAG, "Adding links for episode $episodeNumberFromTitle: ${allEpisodeLinks.distinct()}")
                        epLinksMap.getOrPut(episodeNumberFromTitle) { mutableListOf() }.addAll(allEpisodeLinks.distinct())
                    }
                }
            }

            epLinksMap.forEach { (epNum, links) ->
                val info = responseData?.meta?.videos?.find { it.season == seasonNumber && it.episode == epNum }

                episodesData.add(
                    newEpisode(links) {
                        this.name = info?.title ?: "Episode $epNum"
                        this.season = seasonNumber
                        this.episode = epNum
                        this.posterUrl = info?.thumbnail
                        this.description = info?.overview
                        this.score = info?.rating
                        addDate(info?.released)
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                this.backgroundPosterUrl = background
                this.posterUrl = poster
                this.year = year.toIntOrNull()
                this.plot = description ?: plot
                this.tags = genre ?: tags
                this.actors = actorData
                this.score = responseData?.meta?.rating
                addTrailer(trailer)
                addImdbUrl(imdbUrl)
            }
        }
    }

    private fun String.urlOrNull() : String? {
        return if(this.startsWith("http")) this else null
    }

    private fun getSize(text: String): Long {
         val sizeRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(MB|GB)", RegexOption.IGNORE_CASE)
         val match = sizeRegex.find(text) ?: return Long.MAX_VALUE
         
         val value = match.groupValues[1].toDoubleOrNull() ?: return Long.MAX_VALUE
         val unit = match.groupValues[2].uppercase()
         
         return when(unit) {
             "GB" -> (value * 1024 * 1024 * 1024).toLong()
             "MB" -> (value * 1024 * 1024).toLong()
             else -> Long.MAX_VALUE
         }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Parse JSON data from load() function
        val linksList = try {
            // Better JSON parsing
            val cleanData = data.trim()
            if (cleanData.startsWith("[")) {
                // Parse as JSON array
                val jsonArray = org.json.JSONArray(cleanData)
                (0 until jsonArray.length()).map { i ->
                    val item = jsonArray.optJSONObject(i)
                    if (item != null) {
                        Pair(item.optString("url", ""), item.optString("text", ""))
                    } else {
                        // Fallback for plain strings in array
                        val str = jsonArray.optString(i, "")
                        Pair(str, "")
                    }
                }
            } else {
                // Single URL or comma-separated
                data.split(",").map { Pair(it.trim().replace("\"", ""), "") }
            }
        } catch (e: Exception) {
            Log.e("HDhub4u", "JSON parse error: ${e.message}")
            // Fallback parsing
            data.removePrefix("[").removeSuffix("]").split(",").map { 
                Pair(it.trim().replace("\"", ""), "") 
            }
        }

        // Smart Sorting Logic:
        // 1. Quality (4K/2160p > 1080p > 720p > 480p)
        // 2. Codec (HEVC/x265 preferred - smaller files)
        // 3. Size (Smallest first within same quality)
        // 4. Server (Fastest first)

        data class LinkInfo(
            val url: String,
            val text: String,
            val qualityScore: Int,  // Higher = better quality
            val size: Long,         // In bytes, smaller = better
            val serverPriority: Int // Higher = faster server
        )

        fun getQualityScore(text: String): Int {
            val isHEVC = text.contains("HEVC", true) || text.contains("x265", true) || text.contains("10bit", true)
            val hevcBonus = if (isHEVC) 20 else 0  // HEVC bonus increased (smaller + better quality)
            
            // USER PREFERENCE: 1080p with smallest size = HIGHEST priority
            return when {
                text.contains("1080p", true) -> 500 + hevcBonus  // 1080p = TOP PRIORITY!
                text.contains("720p", true) -> 300 + hevcBonus   // 720p = fallback
                text.contains("4K", true) || text.contains("2160p", true) -> 200 + hevcBonus  // 4K = low (too big)
                text.contains("480p", true) -> 100 + hevcBonus
                else -> 50 + hevcBonus
            }
        }

        fun getServerPriority(url: String): Int {
            return when {
                url.contains("hubdrive", true) -> 100   // HubDrive = direct, reliable
                url.contains("hubcloud", true) -> 90    // HubCloud = direct
                url.contains("pixeldrain", true) -> 80  // Direct download
                url.contains("hubcdn", true) -> 60      // CDN direct
                url.contains("gadgetsweb", true) -> 5   // SLOW: Redirect - very low priority
                url.contains("?id=", true) -> 5         // SLOW: Redirect
                else -> 50
            }
        }

        val parsedLinks = linksList.mapNotNull { (url, text) ->
            if (url.isBlank() || !url.startsWith("http")) return@mapNotNull null
            
            // Note: Direct links (hubdrive, hubcloud) have higher priority
            // Redirect links (gadgetsweb) have lower priority but still included as fallback
            
            LinkInfo(
                url = url,
                text = text,
                qualityScore = getQualityScore(text),
                size = getSize(text),
                serverPriority = getServerPriority(url)
            )
        }

        // Sort: Quality (1080p first) -> Size (smallest) -> Server (direct first)
        val sortedLinks = parsedLinks.sortedWith(
            compareByDescending<LinkInfo> { it.qualityScore }
                .thenBy { it.size }  // Smallest file first!
                .thenByDescending { it.serverPriority }
        )

        // SPEED FIX: Only process top 3 best links for fastest loading!
        val topLinks = sortedLinks.take(3)
        
        Log.d("HDhub4u", "Processing TOP ${topLinks.size} links (out of ${sortedLinks.size})")
        topLinks.forEachIndexed { i, it -> Log.d("HDhub4u", "Link $i: ${it.text.take(50)} -> ${it.url.take(60)}") }

        // Process top links in parallel using amap for speed
        topLinks.amap { info ->
            val link = info.url
            if (link.isBlank()) return@amap
             
            try {
                // Resolve redirect links first
                val finalLink = when {
                    link.contains("gadgetsweb", true) || link.contains("?id=", true) -> {
                        try { getRedirectLinks(link) } catch (e: Exception) { link }
                    }
                    else -> link
                }
                
                if (finalLink.isBlank()) return@amap
                
                // Route to appropriate extractor based on URL
                when {
                    finalLink.contains("hubdrive", true) -> {
                        Hubdrive().getUrl(finalLink, name, subtitleCallback, callback)
                    }
                    finalLink.contains("hubcloud", true) -> {
                        HubCloud().getUrl(finalLink, name, subtitleCallback, callback)
                    }
                    finalLink.contains("hubcdn", true) -> {
                        HUBCDN().getUrl(finalLink, name, subtitleCallback, callback)
                    }
                    finalLink.contains("hblinks", true) || finalLink.contains("4khdhub", true) -> {
                        Hblinks().getUrl(finalLink, name, subtitleCallback, callback)
                    }
                    else -> {
                        // Generic callback for unknown links
                        val quality = when {
                            info.text.contains("4K", true) || info.text.contains("2160p", true) -> 2160
                            info.text.contains("1080p", true) -> 1080
                            info.text.contains("720p", true) -> 720
                            info.text.contains("480p", true) -> 480
                            else -> 720
                        }
                        
                        callback.invoke(
                            newExtractorLink(
                                "$name [${info.text.take(30)}]",
                                "$name [${info.text.take(40)}]",
                                finalLink,
                                INFER_TYPE
                            ) {
                                this.quality = quality
                                this.referer = mainUrl
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("HDhub4u", "Failed to process $link: ${e.message}")
            }
        }
        return true
    }



    /**
     * Determines the search quality based on the presence of specific keywords in the input string.
     *
     * @param check The string to check for keywords.
     * @return The corresponding `SearchQuality` enum value, or `null` if no match is found.
     */
    private fun getSearchQuality(check: String?): SearchQuality? {
        val s = check ?: return null
        val u = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val patterns = listOf(
            Regex("\\b(4k|ds4k|uhd|2160p)\\b") to SearchQuality.UHD,
            Regex("\\b(1440p|qhd)\\b") to SearchQuality.BlueRay,
            Regex("\\b(bluray|bdrip|blu[- ]?ray)\\b") to SearchQuality.BlueRay,
            Regex("\\b(1080p|fullhd)\\b") to SearchQuality.HD,
            Regex("\\b(720p)\\b") to SearchQuality.SD,
            Regex("\\b(web[- ]?dl|webrip|webdl)\\b") to SearchQuality.WebRip,
            Regex("\\b(hdrip|hdtv)\\b") to SearchQuality.HD,
            Regex("\\b(camrip|cam[- ]?rip)\\b") to SearchQuality.CamRip,
            Regex("\\b(hdts|hdcam|hdtc)\\b") to SearchQuality.HdCam,
            Regex("\\b(cam)\\b") to SearchQuality.Cam,
            Regex("\\b(dvd)\\b") to SearchQuality.DVD,
            Regex("\\b(hq)\\b") to SearchQuality.HQ,
            Regex("\\b(rip)\\b") to SearchQuality.CamRip
        )

        for ((regex, quality) in patterns) if (regex.containsMatchIn(u)) return quality
        return null
    }
}