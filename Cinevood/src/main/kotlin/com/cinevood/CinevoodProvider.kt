package com.cinevood

import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.util.Locale.getDefault

class CinevoodProvider : MainAPI() {
    override var mainUrl: String = "https://1cinevood.codes"

    init {
        runBlocking {
            baseMainUrl?.let {
                mainUrl = it
            }
        }
    }

    companion object {
        private const val TAG = "CineVood"

        val baseMainUrl: String? by lazy {
            runBlocking {
                try {
                    val response =
                        app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json")
                    val json = response.text
                    val jsonObject = JSONObject(json)
                    jsonObject.optString("cinevood").ifBlank { null }
                } catch (_: Exception) {
                    null
                }
            }
        }

        // Enhanced CloudflareKiller with custom headers
        private val cfKiller by lazy { CloudflareKiller() }
        
        // Rotating user agents to avoid detection
        private val userAgents = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        
        // Custom headers for Cloudflare bypass
        private val customHeaders = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept-Encoding" to "gzip, deflate, br",
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Cache-Control" to "max-age=0"
        )
        
        private fun getRandomUserAgent() = userAgents.random()
        
        // Request delay management
        private var lastRequestTime: Long = 0
        private const val MIN_REQUEST_INTERVAL = 1000L // 1 second between requests
        
        /**
         * Retry logic with exponential backoff for Cloudflare bypass
         */
        private suspend fun <T> retryWithBackoff(
            maxRetries: Int = 3,
            initialDelay: Long = 1000,
            maxDelay: Long = 10000,
            factor: Double = 2.0,
            block: suspend () -> T
        ): T {
            var currentDelay = initialDelay
            repeat(maxRetries - 1) { attempt ->
                try {
                    return block()
                } catch (e: Exception) {
                    Log.w(TAG, "Cloudflare bypass attempt ${attempt + 1} failed: ${e.message}")
                    kotlinx.coroutines.delay(currentDelay)
                    currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
                }
            }
            return block() // Last attempt without catch
        }
        
        /**
         * Add delay between requests to avoid rate limiting
         */
        private suspend fun delayIfNeeded() {
            val now = System.currentTimeMillis()
            val timeSinceLastRequest = now - lastRequestTime
            if (timeSinceLastRequest < MIN_REQUEST_INTERVAL) {
                kotlinx.coroutines.delay(MIN_REQUEST_INTERVAL - timeSinceLastRequest)
            }
            lastRequestTime = System.currentTimeMillis()
        }

        // TMDB Configuration
        const val TMDBAPIKEY = "1865f43a0549ca50d341dd9ab8b29f49"
        const val TMDBBASE = "https://image.tmdb.org/t/p/original"
        const val TMDBAPI = "https://wild-surf-4a0d.phisher1.workers.dev"
    }

    override var name = "CineVood"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "" to "Latest",
        "bollywood/" to "Bollywood",
        "hollywood/" to "Hollywood",
        "punjabi/" to "Punjabi",
        "hindi-dubbed/hollywood-dubbed/" to "Hollywood Dubbed",
        "hindi-dubbed/south-dubbed/" to "South Dubbed",
        "bengali/" to "Bengali",
        "gujarati/" to "Gujarati",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}page/$page/"
        }

        Log.d(TAG, "Loading main page: $url")
        
        // Add request delay to avoid rate limiting
        delayIfNeeded()
        
        // Use retry logic with custom headers and rotating user agent
        val document = retryWithBackoff {
            app.get(
                url,
                interceptor = cfKiller,
                headers = customHeaders + mapOf("User-Agent" to getRandomUserAgent())
            ).document
        }

        val home = document.select("article.latestPost").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Title extraction
        val title = selectFirst("h2.title a, .front-view-title a")?.text()?.trim() ?: return null

        // URL extraction
        val href = selectFirst("h2.title a, .front-view-title a")?.attr("href") ?: return null
        val fixedUrl = fixUrl(href)

        // Poster extraction - prioritize TMDB images
        val posterUrl = selectFirst(".featured-thumbnail img")?.let { img ->
            fixUrlNull(img.attr("src").ifBlank { img.attr("data-src") })
        }

        // Determine type based on title
        val isSeries = title.contains("Season", ignoreCase = true) ||
                title.contains("S0", ignoreCase = true) ||
                title.contains("Episode", ignoreCase = true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, fixedUrl, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, fixedUrl, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }


    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "Searching for: $query")
        
        // Add request delay
        delayIfNeeded()
        
        // Use retry logic with custom headers
        val document = retryWithBackoff {
            app.get(
                "$mainUrl/?s=$query",
                interceptor = cfKiller,
                headers = customHeaders + mapOf("User-Agent" to getRandomUserAgent())
            ).document
        }

        return document.select("article.latestPost").mapNotNull {
            it.toSearchResult()
        }
    }



    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "Loading: $url")
        
        // Add request delay
        delayIfNeeded()
        
        // Use retry logic with custom headers
        val document = retryWithBackoff {
            app.get(
                url,
                interceptor = cfKiller,
                headers = customHeaders + mapOf("User-Agent" to getRandomUserAgent())
            ).document
        }

        // Extract title
        val rawTitle =
            document.selectFirst("h1.page-title, .entry-title")?.text()?.trim() ?: return null
        val title = cleanTitle(rawTitle)

        // Extract poster - TMDB images
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".entry-content img[src*='tmdb'], .entry-content img")
                ?.attr("src")

        // Extract description
        val description = document.selectFirst("meta[name=description]")?.attr("content")

        // Extract year from title
        val yearRegex = Regex("\\((\\d{4})\\)")
        val year = yearRegex.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        // Extract tags/genres
        val tags = document.select(".entry-categories a, .post-categories a").map { it.text() }

        // Extract ALL download links from entry-content area with metadata
        val downloadLinks = mutableListOf<DownloadLink>()

        // Look for links in entry-content (main content area)
        document.select(".entry-content a[href], .post-content a[href], article a[href]")
            .forEach { element ->
                val href = element.attr("href")
                val text = element.text()

                // Filter for download-related links
                if (href.isNotBlank() && (
                    href.contains("oxxfile", ignoreCase = true) ||
                    href.contains("hubcloud", ignoreCase = true) ||
                    href.contains("hubcdn", ignoreCase = true) ||
                    href.contains("gamester", ignoreCase = true) ||
                    href.contains("gamerxyt", ignoreCase = true) ||
                    href.contains("filepress", ignoreCase = true) ||
                    href.contains("filebee", ignoreCase = true) ||
                    href.contains("streamwish", ignoreCase = true) ||
                    href.contains("embedwish", ignoreCase = true) ||
                    href.contains("wishembed", ignoreCase = true) ||
                    href.contains("swhoi", ignoreCase = true) ||
                    href.contains("wishfast", ignoreCase = true) ||
                    href.contains("sfastwish", ignoreCase = true) ||
                    href.contains("awish", ignoreCase = true) ||
                    href.contains("dwish", ignoreCase = true) ||
                    href.contains("streamvid", ignoreCase = true) ||
                    href.contains("dood", ignoreCase = true) ||
                    href.contains("doodstream", ignoreCase = true) ||
                    href.contains("d0o0d", ignoreCase = true) ||
                    href.contains("d000d", ignoreCase = true) ||
                    href.contains("ds2play", ignoreCase = true) ||
                    href.contains("do0od", ignoreCase = true) ||
                    text.contains("download", ignoreCase = true) ||
                    text.contains("watch", ignoreCase = true) ||
                    text.contains("stream", ignoreCase = true)
                )) {
                    // Parse metadata with enhanced quality detection
                    val qualityInfo = parseQualityEnhanced(text)
                    val size = parseSize(text)
                    val serverName = parseServerName(text)

                    downloadLinks.add(
                        DownloadLink(
                            url = href,
                            qualityInfo = qualityInfo,
                            sizeInMB = size,
                            serverName = serverName
                        )
                    )
                }
            }

        // Smart sorting: 1080p priority -> smallest size -> fastest server
        val sortedLinks = downloadLinks.sortedWith(
            compareByDescending<DownloadLink> { it.qualityInfo.resolution== 1080 }  // 1080p first
                .thenBy { if (it.qualityInfo.resolution == 1080) it.sizeInMB else Double.MAX_VALUE }  // Smallest 1080p
                .thenByDescending { getServerPriority(it.serverName) }  // Fastest server
                .thenByDescending { it.qualityInfo.resolution }  // Then by quality (720p, 480p, etc.)
                .thenBy { it.sizeInMB }  // Then by size
        )

        Log.d(TAG, "Total download links found: ${sortedLinks.size}")
        sortedLinks.take(10).forEach { 
            val qualityLabel = formatQualityLabel(it.qualityInfo)
            Log.d(TAG, "Link: $qualityLabel, ${it.sizeInMB}MB, ${it.serverName} -> ${it.url}") 
        }

        // Convert sorted links back to URLs for serialization
        val finalLinks = sortedLinks.map { it.url }

        // Extract season number for TV series
        val seasonNumber = Regex("(?i)\\bSeason\\s*(\\d+)\\b")
            .find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        val isSeries = rawTitle.contains("Season", ignoreCase = true) ||
                rawTitle.contains("S0", ignoreCase = true) ||
                rawTitle.contains("Episode", ignoreCase = true)

        // ========== TMDB Metadata Integration ==========
        var tmdbData: ResponseDataLocal? = null
        
        try {
            // Extract IMDB or TMDB URL from page
            val imdbUrl = document.select("div span a[href*='imdb.com'], a[href*='imdb.com']").attr("href")
            val tmdbHref = document.select("div span a[href*='themoviedb.org'], a[href*='themoviedb.org']").attr("href")
            
            var tmdbId = ""
            
            // Try to get TMDB ID directly from URL
            if (tmdbHref.isNotBlank()) {
                tmdbId = tmdbHref.substringAfterLast("/").substringBefore("-").substringBefore("?")
                Log.d(TAG, "Found TMDB ID from page: $tmdbId")
            }
            
            // If no TMDB ID but IMDB URL exists, resolve via TMDB API
            if (tmdbId.isBlank() && imdbUrl.isNotBlank()) {
                val imdbId = imdbUrl.substringAfter("title/").substringBefore("/")
                Log.d(TAG, "Resolving TMDB ID from IMDB: $imdbId")
                
                try {
                    val findResponse = app.get("$TMDBAPI/find/$imdbId?api_key=$TMDBAPIKEY&external_source=imdb_id")
                    val findJson = JSONObject(findResponse.text)
                    val tvArr = findJson.optJSONArray("tv_results")
                    val movieArr = findJson.optJSONArray("movie_results")
                    
                    tmdbId = when {
                        tvArr != null && tvArr.length() > 0 -> tvArr.optJSONObject(0)?.optInt("id")?.toString().orEmpty()
                        movieArr != null && movieArr.length() > 0 -> movieArr.optJSONObject(0)?.optInt("id")?.toString().orEmpty()
                        else -> ""
                    }
                    Log.d(TAG, "Resolved TMDB ID: $tmdbId")
                } catch (e: Exception) {
                    Log.e(TAG, "Error resolving TMDB ID from IM DB: ${e.message}")
                }
            }
            
            // Fetch TMDB metadata if ID found
            if (tmdbId.isNotBlank()) {
                val type = if (isSeries) "tv" else "movie"
                val detailsResponse = app.get("$TMDBAPI/$type/$tmdbId?api_key=$TMDBAPIKEY&append_to_response=credits")
                val detailsJson = JSONObject(detailsResponse.text)
                
                // Extract metadata
                var metaName = detailsJson.optString("name")
                    .takeIf { it.isNotBlank() }
                    ?: detailsJson.optString("title").takeIf { it.isNotBlank() }
                    ?: title
                
                if (seasonNumber != null && !metaName.contains("Season $seasonNumber", ignoreCase = true)) {
                    metaName = "$metaName (Season $seasonNumber)"
                }
                
                val metaDesc = detailsJson.optString("overview").takeIf { it.isNotBlank() } ?: description
                
                val yearRaw = detailsJson.optString("release_date").ifBlank { detailsJson.optString("first_air_date") }
                val metaYear = yearRaw.takeIf { it.isNotBlank() }?.take(4)
                
                val metaRating = detailsJson.optString("vote_average")
                
                val metaBackground = detailsJson.optString("backdrop_path")
                    .takeIf { it.isNotBlank() }?.let { TMDBBASE + it } ?: poster
                
                // Extract cast
                val actorDataList = mutableListOf<ActorData>()
                detailsJson.optJSONObject("credits")?.optJSONArray("cast")?.let { castArr ->
                    for (i in 0 until minOf(castArr.length(), 20)) {  // Limit to 20 cast members
                        val c = castArr.optJSONObject(i) ?: continue
                        val name = c.optString("name").takeIf { it.isNotBlank() } 
                            ?: c.optString("original_name").orEmpty()
                        val profile = c.optString("profile_path").takeIf { it.isNotBlank() }
                            ?.let { TMDBBASE + it }
                        val character = c.optString("character").takeIf { it.isNotBlank() }
                        val actor = Actor(name, profile)
                        actorDataList += ActorData(actor = actor, roleString = character)
                    }
                }
                
                // Extract genres
                val metaGenres = mutableListOf<String>()
                detailsJson.optJSONArray("genres")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
                            ?.let(metaGenres::add)
                    }
                }
                
                tmdbData = ResponseDataLocal(
                    MetaLocal(
                        name = metaName,
                        description = metaDesc,
                        actorsData = actorDataList.ifEmpty { null },
                        year = metaYear,
                        background = metaBackground,
                        genres = metaGenres.ifEmpty { null },
                        videos = null,  // Will add episodes later
                        rating = Score.from10(metaRating)
                    )
                )
                
                Log.d(TAG, "TMDB metadata loaded: $metaName, Year: $metaYear, Rating: $metaRating")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching TMDB metadata: ${e.message}")
        }
        // ========== End TMDB Integration ==========

        return if (isSeries) {
            newTvSeriesLoadResponse(
                tmdbData?.meta?.name ?: title, 
                url, 
                TvType.TvSeries, 
                emptyList()
            ) {
                this.posterUrl = tmdbData?.meta?.background ?: poster
                this.year = tmdbData?.meta?.year?.toIntOrNull() ?: year
                this.plot = tmdbData?.meta?.description ?: description
                this.tags = tmdbData?.meta?.genres ?: tags
                tmdbData?.meta?.rating?.let { this.score = it }
                tmdbData?.meta?.actorsData?.let { actorList ->
                    addActors(actorList.map { Pair(it.actor, it.roleString) })
                }
            }
        } else {
            // Return as comma-separated string
            val data = finalLinks.joinToString(",")
            newMovieLoadResponse(
                tmdbData?.meta?.name ?: title,
                url, 
                TvType.Movie, 
                data
            ) {
                this.posterUrl = tmdbData?.meta?.background ?: poster
                this.year = tmdbData?.meta?.year?.toIntOrNull() ?: year
                this.plot = tmdbData?.meta?.description ?: description
                this.tags = tmdbData?.meta?.genres ?: tags
                tmdbData?.meta?.rating?.let { this.score = it }
                tmdbData?.meta?.actorsData?.let { actorList ->
                    addActors(actorList.map { Pair(it.actor, it.roleString) })
                }
            }
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "Loading links from: $data")

        try {
            // Parse comma-separated links
            val links = if (data.isBlank()) {
                emptyList()
            } else if (data.startsWith("http")) {
                // Single URL or comma separated
                if (data.contains(",")) {
                    data.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    listOf(data)
                }
            } else {
                // Comma separated
                data.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }

            Log.d(TAG, "Processing ${links.size} links")

            links.forEach { link ->
                try {
                    Log.d(TAG, "Processing link: $link")

                    when {
                        // OxxFile patterns (primary for Cinevood)
                        link.contains("oxxfile", ignoreCase = true) -> {
                            Log.d(TAG, "Using OxxFile extractor")
                            OxxFile().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // HubCloud patterns
                        link.contains("hubcloud", ignoreCase = true) ||
                                link.contains("hubcdn", ignoreCase = true) ||
                                link.contains("gamester", ignoreCase = true) ||
                                link.contains("gamerxyt", ignoreCase = true) -> {
                            Log.d(TAG, "Using HubCloud extractor")
                            HubCloud().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // FilePress patterns
                        link.contains("filepress", ignoreCase = true) ||
                                link.contains("filebee", ignoreCase = true) -> {
                            Log.d(TAG, "Using FilePress extractor")
                            FilePressExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // StreamWish patterns (incluiding embedwish)
                        link.contains("streamwish", ignoreCase = true) ||
                                link.contains("embedwish", ignoreCase = true) ||
                                link.contains("wishembed", ignoreCase = true) ||
                                link.contains("swhoi", ignoreCase = true) ||
                                link.contains("wishfast", ignoreCase = true) ||
                                link.contains("sfastwish", ignoreCase = true) ||
                                link.contains("awish", ignoreCase = true) ||
                                link.contains("dwish", ignoreCase = true) ||
                                link.contains("streamvid", ignoreCase = true) -> {
                            Log.d(TAG, "Using StreamWish extractor")
                            StreamWishExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        // DoodStream patterns
                        link.contains("dood", ignoreCase = true) ||
                                link.contains("doodstream", ignoreCase = true) ||
                                link.contains("d0o0d", ignoreCase = true) ||
                                link.contains("d000d", ignoreCase = true) ||
                                link.contains("ds2play", ignoreCase = true) ||
                                link.contains("do0od", ignoreCase = true) -> {
                            Log.d(TAG, "Using DoodStream extractor")
                            DoodLaExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                        }

                        else -> {
                            Log.d(TAG, "Using generic extractor for: $link")
                            Log.d("Phisher", "No local extractor found for:")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading link $link: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadLinks: ${e.message}")
            e.printStackTrace()
        }

        return true
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("Download|Free|Full|Movie|HD|Watch", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    /**
     * Enhanced data class for structured download link with advanced quality info
     */
    private data class DownloadLink(
        val url: String,
        val qualityInfo: QualityInfo,  // Enhanced quality info
        val sizeInMB: Double,
        val serverName: String
    )

    /**
     * Enhanced quality information with 4K/HDR/codec support
     */
    data class QualityInfo(
        val resolution: Int,              // 2160, 1080, 720, 480
        val isHDR: Boolean = false,
        val isDolbyVision: Boolean = false,
        val codec: String? = null,         // HEVC, H.265, AVC, H.264
        val source: String? = null         // BluRay, WEB-DL, WEBRip, CAM
    )

    /**
     * Parse enhanced quality from link text (supports 4K, HDR, HEVC, BluRay, etc.)
     */
    private fun parseQualityEnhanced(text: String): QualityInfo {
        // Parse resolution
        val resolution = when {
            text.contains("4K", ignoreCase = true) || 
            text.contains("2160p", ignoreCase = true) -> 2160
            text.contains("2K", ignoreCase = true) -> 1440
            text.contains("1080p", ignoreCase = true) -> 1080
            text.contains("720p", ignoreCase = true) -> 720
            text.contains("480p", ignoreCase = true) -> 480
            else -> {
                val match = Regex("(\\d{3,4})p").find(text)
                match?.groupValues?.get(1)?.toIntOrNull() ?: 1080
            }
        }
        
        // Parse HDR
        val isHDR = text.contains("HDR", ignoreCase = true) ||
                    text.contains("HDR10", ignoreCase = true) ||
                    text.contains("HDR10+", ignoreCase = true)
        
        val isDolbyVision = text.contains("Dolby Vision", ignoreCase = true) ||
                             text.contains(" DV ", ignoreCase = true) ||
                             text.contains("DV]", ignoreCase = true)
        
        // Parse codec
        val codec = when {
            text.contains("HEVC", ignoreCase = true) || 
            text.contains("H.265", ignoreCase = true) || 
            text.contains("x265", ignoreCase = true) -> "HEVC"
            text.contains("AVC", ignoreCase = true) || 
            text.contains("H.264", ignoreCase = true) || 
            text.contains("x264", ignoreCase = true) -> "H.264"
            else -> null
        }
        
        // Parse source
        val source = when {
            text.contains("BluRay", ignoreCase = true) || 
            text.contains("BDRip", ignoreCase = true) || 
            text.contains("BRRip", ignoreCase = true) || 
            text.contains("Blu-Ray", ignoreCase = true) -> "BluRay"
            text.contains("WEB-DL", ignoreCase = true) || 
            text.contains("WEBDL", ignoreCase = true) -> "WEB-DL"
            text.contains("WEBRip", ignoreCase = true) || 
            text.contains("Web-Rip", ignoreCase = true) -> "WEBRip"
            text.contains("HDRip", ignoreCase = true) -> "HDRip"
            text.contains("HDTS", ignoreCase = true) || 
            text.contains("HDCAM", ignoreCase = true) || 
            text.contains("HD-CAM", ignoreCase = true) -> "HDCAM"
            text.contains("CAMRip", ignoreCase = true) || 
            text.contains("CAM Rip", ignoreCase = true) -> "CAMRip"
            text.contains("CAM", ignoreCase = true) -> "CAM"
            text.contains("DVDRip", ignoreCase = true) || 
            text.contains("DVD-Rip", ignoreCase = true) -> "DVDRip"
            else -> null
        }
        
        return QualityInfo(resolution, isHDR, isDolbyVision, codec, source)
    }

    /**
     * Format quality info into readable label
     */
    private fun formatQualityLabel(info: QualityInfo): String {
        return buildString {
            append("${info.resolution}p")
            if (info.isHDR) append(" HDR")
            if (info.isDolbyVision) append(" DV")
            info.codec?.let { append(" $it") }
            info.source?.let { append(" $it") }
        }
    }

    /**
     * Parse quality from link text (legacy function for backward compatibility)
     */
    private fun parseQuality(text: String): Int {
        return parseQualityEnhanced(text).resolution
    }

    /**
     * Parse file size from text (e.g., "1.8GB" -> 1843.2 MB, "500MB" -> 500.0)
     */
    private fun parseSize(text: String): Double {
        val sizeMatch = Regex("([\\d.]+)\\s*(GB|MB)", RegexOption.IGNORE_CASE).find(text)
        if (sizeMatch != null) {
            val value = sizeMatch.groupValues[1].toDoubleOrNull() ?: return Double.MAX_VALUE
            val unit = sizeMatch.groupValues[2].uppercase()
            return when (unit) {
                "GB" -> value * 1024
                "MB" -> value
                else -> Double.MAX_VALUE
            }
        }
        return Double.MAX_VALUE
    }

    /**
     * Extract server name from link text
     */
    private fun parseServerName(text: String): String {
        return when {
            text.contains("Instant", ignoreCase = true) -> "Instant DL"
            text.contains("Direct", ignoreCase = true) -> "Direct"
            text.contains("FSL", ignoreCase = true) -> "FSL"
            text.contains("Download", ignoreCase = true) -> "Download"
            else -> "Standard"
        }
    }

    /**
     * Get server priority score (higher = faster/preferred)
     */
    private fun getServerPriority(serverName: String): Int {
        return when {
            serverName.contains("Instant", ignoreCase = true) -> 100  // Instant DL = fastest
            serverName.contains("Direct", ignoreCase = true) -> 90
            serverName.contains("FSLv2", ignoreCase = true) -> 85
            serverName.contains("FSL", ignoreCase = true) -> 80
            serverName.contains("10Gbps", ignoreCase = true) -> 88
            serverName.contains("Download", ignoreCase = true) -> 70
            serverName.contains("Pixel", ignoreCase = true) -> 60
            serverName.contains("Buzz", ignoreCase = true) -> 55
            else -> 50
        }
    }
}

// TMDB Data Classes
data class IMDB(
    @SerializedName("imdb_id")
    val imdbId: String? = null
)

data class ResponseDataLocal(val meta: MetaLocal?)

data class MetaLocal(
    val name: String? = null,
    val description: String? = null,
    val actorsData: List<ActorData>? = null,
    val year: String? = null,
    val background: String? = null,
    val genres: List<String>? = null,
    val videos: List<VideoLocal>? = null,
    val rating: Score?
)

data class VideoLocal(
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val overview: String? = null,
    val thumbnail: String? = null,
    val released: String? = null,
    val rating: Score?
)