package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

class HDhub4uProvider : MainAPI() {
    override var mainUrl = "https://new2.hdhub4u.fo"
    override var name = "HDhub4u"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        private const val DOMAIN_API_URL = "https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json"
        private const val TAG = "HDhub4uProvider"
        
        // Quality regex patterns
        private val qualityRegex = Regex("""(\d{3,4})[pP]""")
        private val yearRegex = Regex("""\((\d{4})\)""")
        private val tvShowRegex = Regex("""season-?\d+|all-episodes|web-?series""", RegexOption.IGNORE_CASE)
        private val episodeRegex = Regex("""EPiSODE\s*(\d+)|EP[-\s]*(\d+)|Episode\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
    }

    // Dynamic domain resolver
    private suspend fun fetchDynamicDomain(): String {
        return try {
            val response = app.get(DOMAIN_API_URL, timeout = 10L).text
            val urlsJson = AppUtils.parseJson<Map<String, String>>(response)
            urlsJson["hdhub4u"] ?: mainUrl
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch domain: ${e.message}")
            mainUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Fetch dynamic domain
        val domain = fetchDynamicDomain()
        mainUrl = domain
        
        val pageUrl = if (page > 1) "$mainUrl/page/$page/" else mainUrl
        val doc = app.get(pageUrl).document

        val homePageList = mutableListOf<HomePageList>()

        // Main content - all movies/series from homepage
        val mainItems = doc.select("li.thumb").mapNotNull { it.toSearchResult() }
        if (mainItems.isNotEmpty()) {
            homePageList.add(HomePageList("Latest Updates", mainItems))
        }

        // Categories from navigation
        val categories = listOf(
            "Bollywood" to "/category/bollywood-movies/",
            "Hollywood" to "/category/hollywood-movies/",
            "Hindi Dubbed" to "/category/hindi-dubbed/",
            "South Hindi" to "/category/south-hindi-movies/",
            "Web Series" to "/category/category/web-series/",
            "300MB Movies" to "/category/300mb-movies/"
        )

        categories.forEach { (name, path) ->
            try {
                val categoryDoc = app.get("$mainUrl$path").document
                val items = categoryDoc.select("li.thumb").take(12).mapNotNull { it.toSearchResult() }
                if (items.isNotEmpty()) {
                    homePageList.add(HomePageList(name, items))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load category $name: ${e.message}")
            }
        }

        return HomePageResponse(homePageList)
    }

    // Convert HTML element to SearchResponse
    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("figure a") ?: this.selectFirst("figcaption a") ?: return null
        val href = linkElement.attr("href").takeIf { it.isNotBlank() } ?: return null
        
        val imgElement = this.selectFirst("figure img")
        val poster = imgElement?.attr("src") ?: imgElement?.attr("data-src")
        
        val title = imgElement?.attr("alt")?.trim()
            ?: imgElement?.attr("title")?.trim()
            ?: this.selectFirst("figcaption p")?.text()?.trim()
            ?: return null
        
        // Clean title - remove quality tags for display
        val cleanTitle = cleanTitle(title)
        
        // Determine type based on URL patterns
        val isTvShow = tvShowRegex.containsMatchIn(href) || title.contains("Season", ignoreCase = true)
        
        return if (isTvShow) {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Update domain
        val domain = fetchDynamicDomain()
        mainUrl = domain
        
        // WordPress style search URL - scraping method
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        
        return try {
            val doc = app.get(searchUrl).document
            
            // Try multiple selectors for search results
            val results = doc.select("li.thumb").mapNotNull { it.toSearchResult() }
            
            if (results.isEmpty()) {
                // Fallback: try alternative search result selectors
                doc.select(".search-result, .post, article").mapNotNull { element ->
                    val link = element.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
                    val img = element.selectFirst("img")
                    val title = img?.attr("alt") 
                        ?: element.selectFirst("h2, h3, .title, p")?.text()?.trim()
                        ?: return@mapNotNull null
                    
                    val poster = img?.attr("src") ?: img?.attr("data-src")
                    val isTvShow = tvShowRegex.containsMatchIn(link) || title.contains("Season", ignoreCase = true)
                    
                    if (isTvShow) {
                        newTvSeriesSearchResponse(cleanTitle(title), link, TvType.TvSeries) {
                            this.posterUrl = poster
                        }
                    } else {
                        newMovieSearchResponse(cleanTitle(title), link, TvType.Movie) {
                            this.posterUrl = poster
                        }
                    }
                }
            } else {
                results
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        // Extract title
        val rawTitle = doc.selectFirst("h1")?.text()?.trim() 
            ?: doc.selectFirst(".entry-title, .post-title")?.text()?.trim()
            ?: return null
        
        val title = cleanTitle(rawTitle)
        
        // Extract poster - prefer TMDB images
        val poster = doc.select("img").firstOrNull { 
            it.attr("src").contains("tmdb") || it.attr("src").contains("image.tmdb.org")
        }?.attr("src") ?: doc.selectFirst(".entry-content img, article img")?.attr("src")
        
        // Extract year
        val year = yearRegex.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        
        // Extract plot/description
        val plot = doc.select("p").firstOrNull { 
            val text = it.text().trim()
            text.length > 100 && !text.contains("download", ignoreCase = true) 
                && !text.contains("click here", ignoreCase = true)
        }?.text()?.trim() ?: rawTitle
        
        // Determine if TV Show or Movie
        val isTvShow = tvShowRegex.containsMatchIn(url) || rawTitle.contains("Season", ignoreCase = true)
        
        return if (isTvShow) {
            // Extract episodes
            val episodes = mutableListOf<Episode>()
            
            // Find episode links
            doc.select("a").forEach { link ->
                val href = link.attr("href")
                val text = link.text().trim()
                
                // Check if this is an episode link
                val episodeMatch = episodeRegex.find(text)
                if (episodeMatch != null && (href.contains("gadgetsweb") || href.contains("hubdrive") || href.contains("hubcloud"))) {
                    val epNum = episodeMatch.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: episodes.size + 1
                    
                    episodes.add(
                        Episode(
                            data = href,
                            name = "Episode $epNum",
                            season = 1,
                            episode = epNum
                        )
                    )
                }
            }
            
            // If no individual episode links found, use the page URL as single episode container
            if (episodes.isEmpty()) {
                episodes.add(
                    Episode(
                        data = url,
                        name = "All Episodes",
                        season = 1,
                        episode = 1
                    )
                )
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Placeholder - Download link extraction
        // बाद में implement होगा
        
        val doc = if (data.startsWith("http")) {
            app.get(data).document
        } else {
            return false
        }
        
        // Extract all download server links
        val serverLinks = doc.select("a").mapNotNull { link ->
            val href = link.attr("href").lowercase()
            when {
                href.contains("hubdrive") -> link.attr("href")
                href.contains("hubcloud") -> link.attr("href")
                href.contains("gdflix") -> link.attr("href")
                href.contains("gadgetsweb") -> link.attr("href")
                href.contains("4khdhub") -> link.attr("href")
                href.contains("hblinks") -> link.attr("href")
                href.contains("gofile") -> link.attr("href")
                else -> null
            }
        }.distinct()
        
        // Pass to extractors
        serverLinks.forEach { serverUrl ->
            try {
                loadExtractor(serverUrl, data, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(TAG, "Extractor failed for $serverUrl: ${e.message}")
            }
        }
        
        return serverLinks.isNotEmpty()
    }

    // Helper function to clean title
    private fun cleanTitle(title: String): String {
        // Remove quality tags, year, codec info from display title
        return title
            .replace(Regex("""\s*\([^)]*\d{4}[^)]*\)"""), "") // Remove (2024) etc
            .replace(Regex("""\s*\[[^\]]*\]"""), "") // Remove [720p] etc
            .replace(Regex("""\s*(WEB-?DL|WEBRip|BluRay|HDTC|HDRip|DVDRip)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*(480p|720p|1080p|2160p|4K)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*(HEVC|x264|x265|10Bit)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*(Hindi|English|Dual Audio|DD\d\.\d)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*(ESub|ESubs|Full Movie|Full Series)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*\|\s*.*$"""), "") // Remove everything after |
            .replace(Regex("""\s+"""), " ") // Multiple spaces to single
            .trim()
    }
}
