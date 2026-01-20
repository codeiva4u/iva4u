package com.hdhub4u

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.amap

class HDhub4uProvider : MainAPI() {
    
    // Dynamic domain from GitHub urls.json
    override var mainUrl: String = "https://new2.hdhub4u.fo"
    
    init {
        runBlocking {
            basemainUrl?.let {
                mainUrl = it
            }
        }
    }
    
    companion object {
        // Smart domain resolver - fetches real-time domain from GitHub
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
    
    override var name = "HDhub4u"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )
    
    // Main page categories from website analysis
    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/bollywood-movies/" to "Bollywood",
        "category/hollywood-movies/" to "Hollywood",
        "category/hindi-dubbed/" to "Hindi Dubbed",
        "category/south-hindi-movies/" to "South Hindi",
        "category/category/web-series/" to "Web Series"
    )
    
    // ==================== HOME PAGE FUNCTION ====================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}page/$page/"
        }
        
        val document = app.get(url).document
        
        // Select figure elements which contain movie cards (img + link)
        val home = document.select("figure").mapNotNull { figure ->
            // Get link from figure
            val link = figure.selectFirst("a[href*=\"$mainUrl\"]") ?: return@mapNotNull null
            val href = link.attr("href")
            
            // Filter: only content pages, not category/static pages
            if (href.isBlank() ||
                href == mainUrl ||
                href == "$mainUrl/" ||
                href.contains("/category/") ||
                href.contains("/page/") ||
                href.contains("/disclaimer") ||
                href.contains("/how-to") ||
                href.contains("/join-") ||
                href.contains("/request-") ||
                href.contains(".apk")
            ) {
                return@mapNotNull null
            }
            
            // Get title from img alt or title attribute
            val img = figure.selectFirst("img")
            val title = img?.attr("alt")?.trim()
                ?: img?.attr("title")?.trim()
                ?: return@mapNotNull null
            
            if (title.isBlank() || title.length < 5) return@mapNotNull null
            
            // Get poster URL from img src
            val posterUrl = img?.getImageAttr()
            
            // Determine type from URL/title
            val isTvShow = href.contains("season", ignoreCase = true) ||
                          href.contains("series", ignoreCase = true) ||
                          href.contains("episode", ignoreCase = true) ||
                          title.contains("Season", ignoreCase = true)
            
            if (isTvShow) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
        }.distinctBy { it.url }
        
        return newHomePageResponse(HomePageList(request.name, home))
    }
    
    // ==================== SEARCH FUNCTION ====================
    override suspend fun search(query: String): List<SearchResponse> {
        // HDhub4u uses Typesense API for search (JavaScript-based)
        // JSoup cannot see dynamic content, so we call API directly
        val searchApiUrl = "https://search.pingora.fyi/collections/post/documents/search" +
            "?q=${query.encodeUri()}" +
            "&query_by=post_title,category,stars,director,imdb_id" +
            "&query_by_weights=4,2,2,2,4" +
            "&sort_by=sort_by_date:desc" +
            "&limit=30" +
            "&page=1" +
            "&highlight_fields=none" +
            "&use_cache=true"
        
        return try {
            val response = app.get(searchApiUrl).text
            val jsonObject = JSONObject(response)
            val hits = jsonObject.optJSONArray("hits") ?: return emptyList()
            
            (0 until hits.length()).mapNotNull { i ->
                val hit = hits.optJSONObject(i) ?: return@mapNotNull null
                val doc = hit.optJSONObject("document") ?: return@mapNotNull null
                
                val title = doc.optString("post_title").takeIf { it.isNotBlank() } 
                    ?: return@mapNotNull null
                val permalink = doc.optString("permalink").takeIf { it.isNotBlank() } 
                    ?: return@mapNotNull null
                val posterUrl = doc.optString("post_thumbnail").takeIf { it.isNotBlank() }
                
                // Build full URL
                val href = if (permalink.startsWith("http")) permalink else "$mainUrl$permalink"
                
                // Determine type from title/URL
                val isTvShow = title.contains("Season", ignoreCase = true) ||
                              permalink.contains("season", ignoreCase = true) ||
                              permalink.contains("series", ignoreCase = true) ||
                              permalink.contains("episode", ignoreCase = true)
                
                if (isTvShow) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    }
                } else {
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = posterUrl
                    }
                }
            }.distinctBy { it.url }
        } catch (e: Exception) {
            // Fallback to HTML scraping if API fails
            searchFallback(query)
        }
    }
    
    // Fallback search using HTML scraping
    private suspend fun searchFallback(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        
        return document.select("figure").mapNotNull { figure ->
            val link = figure.selectFirst("a[href]") ?: return@mapNotNull null
            var href = link.attr("href")
            
            if (href.startsWith("/")) href = mainUrl + href
            if (href.isBlank() || href.contains("/category/") || href.contains("/page/")) {
                return@mapNotNull null
            }
            
            val img = figure.selectFirst("img")
            val title = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: img?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            
            if (title.length < 3) return@mapNotNull null
            
            val posterUrl = img?.getImageAttr()
            val isTvShow = href.contains("season", ignoreCase = true) ||
                          title.contains("Season", ignoreCase = true)
            
            if (isTvShow) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
        }.distinctBy { it.url }
    }
    
    // URL encoding helper
    private fun String.encodeUri(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }
    
    // ==================== UTILITY FUNCTIONS ====================
    
    // Extension function to get image URL from various attributes
    private fun Element.getImageAttr(): String? {
        return this.attr("data-src")
            .takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: this.attr("data-lazy-src")
                .takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: this.attr("src")
                .takeIf { it.isNotBlank() && it.startsWith("http") }
    }
    
    // ==================== LOAD FUNCTION (Movie/TV Detail) ====================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Extract title from h1
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        
        // Extract poster from TMDB or other sources
        val posterUrl = document.select("img").firstOrNull { img ->
            val src = img.getImageAttr() ?: ""
            src.contains("tmdb.org") || src.contains("imgshare") ||
                    (src.contains("http") && (img.attr("width").toIntOrNull() ?: 0) > 150)
        }?.getImageAttr()
        
        // Extract year using regex
        val yearMatch = Regex("""\((\d{4})\)""").find(title)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
        
        // Get page text for regex extraction
        val pageText = document.body().text()
        
        // Extract IMDB Rating (pattern: "iMDB Rating: 6.5/10")
        val imdbMatch = Regex("""iMDB\s*Rating[\s:]*([0-9.]+)/10""", RegexOption.IGNORE_CASE).find(pageText)
        val imdbRating = imdbMatch?.groupValues?.get(1)
        
        // Extract Genre (pattern: "Genre: Comedy")
        val genreMatch = Regex("""Genre[\s:]*([A-Za-z,\s]+)""", RegexOption.IGNORE_CASE).find(pageText)
        val genre = genreMatch?.groupValues?.get(1)?.trim()?.replace(Regex("""\s+"""), " ")
        val tags = genre?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        
        // Extract Stars (pattern: "Stars: Dakota Johnson, Adria Arjona, Kyle Marvin")
        val starsMatch = Regex("""Stars[\s:]*([A-Za-z,\s]+)""", RegexOption.IGNORE_CASE).find(pageText)
        val stars = starsMatch?.groupValues?.get(1)?.trim()?.replace(Regex("""\s+"""), " ")
        
        // Extract Director (pattern: "Director: Michael Angelo Covino")
        val directorMatch = Regex("""Director[\s:]*([A-Za-z\s]+)""", RegexOption.IGNORE_CASE).find(pageText)
        val director = directorMatch?.groupValues?.get(1)?.trim()
        
        // Extract Creator (pattern: "Creator: Name")
        val creatorMatch = Regex("""Creator[\s:]*([A-Za-z\s]+)""", RegexOption.IGNORE_CASE).find(pageText)
        val creator = creatorMatch?.groupValues?.get(1)?.trim()
        
        // Extract Language (pattern: "Language: Dual Audio [Hindi (DD5.1) & English]")
        val languageMatch = Regex("""Language[\s:]*([^\n]+)""", RegexOption.IGNORE_CASE).find(pageText)
        val language = languageMatch?.groupValues?.get(1)?.trim()
        
        // Extract Quality (pattern: "Quality: BluRay 4K | 1080p | 720p | 480p")
        val qualityMatch = Regex("""Quality[\s:]*([^\n]+)""", RegexOption.IGNORE_CASE).find(pageText)
        val quality = qualityMatch?.groupValues?.get(1)?.trim()
        
        // Build plot/description with all extracted info
        val plot = buildString {
            imdbRating?.let { append("⭐ IMDB Rating: $it/10\n") }
            genre?.let { append("🎭 Genre: $it\n") }
            director?.let { append("🎬 Director: $it\n") }
            creator?.let { append("✍️ Creator: $it\n") }
            stars?.let { append("🌟 Stars: $it\n") }
            language?.let { append("🗣️ Language: $it\n") }
            quality?.let { append("📺 Quality: $it") }
        }.takeIf { it.isNotBlank() }
        
        // Determine if TV Series
        val isTvShow = url.contains("season", ignoreCase = true) ||
                      url.contains("series", ignoreCase = true) ||
                      title.contains("Season", ignoreCase = true)
        
        return if (isTvShow) {
            // Extract episodes for TV Shows
            val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
            
            // Find episode links using regex pattern
            val episodePattern = Regex("""EP[-iSODE\s]*(\d+)""", RegexOption.IGNORE_CASE)
            
            document.select("a").forEach { link ->
                val text = link.text().trim()
                val href = link.attr("href")
                
                val epMatch = episodePattern.find(text)
                if (epMatch != null && href.isNotBlank()) {
                    val epNum = epMatch.groupValues[1].toIntOrNull() ?: 0
                    
                    // Extract season from title
                    val seasonMatch = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(title)
                    val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    
                    episodes.add(
                        newEpisode(href) {
                            this.name = "Episode $epNum"
                            this.episode = epNum
                            this.season = season
                        }
                    )
                }
            }
            
            // Remove duplicate episodes
            val uniqueEpisodes = episodes.distinctBy { it.episode }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, uniqueEpisodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        } else {
            // Movie response
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }
    }
    
    // ==================== LOAD LINKS FUNCTION ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var linksFound = false
        
        // Find all download links - using amap for async
        document.select("a[href]").amap { link ->
            val href = link.attr("href")
            val text = link.text()
            
            // Skip invalid/internal/trailer links
            if (href.isBlank() || 
                href.startsWith("#") ||
                href == data ||
                !href.startsWith("http") ||
                href.contains("hdhub4u", ignoreCase = true) ||
                href.contains("/category/") ||
                href.contains("/page/") ||
                href.contains("how-to-download") ||
                href.contains("hubstream", ignoreCase = true)) {
                return@amap
            }
            
            // Check if link indicates download/quality
            val isDownloadLink = text.contains("download", ignoreCase = true) ||
                                text.contains("1080p", ignoreCase = true) ||
                                text.contains("720p", ignoreCase = true) ||
                                text.contains("480p", ignoreCase = true) ||
                                text.contains("x264", ignoreCase = true) ||
                                text.contains("HEVC", ignoreCase = true) ||
                                text.contains("MB]", ignoreCase = true) ||
                                text.contains("GB]", ignoreCase = true) ||
                                href.contains("hubdrive", ignoreCase = true) ||
                                href.contains("hubcloud", ignoreCase = true) ||
                                href.contains("hubcdn", ignoreCase = true) ||
                                href.contains("gadgetsweb", ignoreCase = true)
            
            if (isDownloadLink) {
            
            try {
                // Route to appropriate extractor
                when {
                    // GadgetsWeb mediator - bypass and decode
                    href.contains("gadgetsweb", ignoreCase = true) -> {
                        val encodedId = Regex("""[?&]id=([^&]+)""").find(href)?.groupValues?.get(1)
                        if (encodedId != null) {
                            val decodedUrl = decodeGadgetsWebUrl(encodedId)
                            if (decodedUrl != null) {
                                HubCloud().getUrl(decodedUrl, data, subtitleCallback, callback)
                                linksFound = true
                            }
                        }
                    }
                    
                    // HubDrive links
                    href.contains("hubdrive", ignoreCase = true) -> {
                        HubDrive().getUrl(href, data, subtitleCallback, callback)
                        linksFound = true
                    }
                    
                    // HubCloud/HubCDN links
                    href.contains("hubcloud", ignoreCase = true) ||
                    href.contains("hubcdn", ignoreCase = true) -> {
                        HubCloud().getUrl(href, data, subtitleCallback, callback)
                        linksFound = true
                    }
                }
            } catch (e: Exception) {
                // Continue with other links if one fails
            }
            } // end if(isDownloadLink)
        } // end amap
        
        return linksFound
    }
}

