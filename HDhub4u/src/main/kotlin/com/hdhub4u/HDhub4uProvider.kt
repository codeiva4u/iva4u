package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class HDhub4uProvider : MainAPI() {
    override var mainUrl = "https://new2.hdhub4u.fo"
    override var name = "HDhub4u"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private const val DOMAIN_API_URL = "https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json"
        private const val TAG = "HDhub4uProvider"
        private val qualityRegex = Regex("""(\d{3,4})[pP]""")
        private val yearRegex = Regex("""\((\d{4})\)""")
        private val tvShowRegex = Regex("""season-?\d+|all-episodes|web-?series""", RegexOption.IGNORE_CASE)
        private val episodeRegex = Regex("""EPiSODE\s*(\d+)|EP[-\s]*(\d+)|Episode\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
    }

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
        val domain = fetchDynamicDomain()
        mainUrl = domain
        
        val pageUrl = if (page > 1) "$mainUrl/page/$page/" else mainUrl
        val doc = app.get(pageUrl).document
        val homePageList = mutableListOf<HomePageList>()

        val mainItems = doc.select("li.thumb").mapNotNull { it.toSearchResult() }
        if (mainItems.isNotEmpty()) {
            homePageList.add(HomePageList("Latest Updates", mainItems))
        }

        val categories = listOf(
            "Bollywood" to "/category/bollywood-movies/",
            "Hollywood" to "/category/hollywood-movies/",
            "Hindi Dubbed" to "/category/hindi-dubbed/",
            "South Hindi" to "/category/south-hindi-movies/",
            "Web Series" to "/category/category/web-series/"
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

        return newHomePageResponse(homePageList)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("figure a") ?: this.selectFirst("figcaption a") ?: return null
        val href = linkElement.attr("href").takeIf { it.isNotBlank() } ?: return null
        
        val imgElement = this.selectFirst("figure img")
        val poster = imgElement?.attr("src") ?: imgElement?.attr("data-src")
        
        val title = imgElement?.attr("alt")?.trim()
            ?: imgElement?.attr("title")?.trim()
            ?: this.selectFirst("figcaption p")?.text()?.trim()
            ?: return null
        
        val cleanTitle = cleanTitle(title)
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
        val domain = fetchDynamicDomain()
        mainUrl = domain
        
        // Website uses search.html?q= pattern (JavaScript-based search)
        val searchUrl = "$mainUrl/search.html?q=${query.replace(" ", "+")}"
        
        return try {
            val doc = app.get(searchUrl).document
            
            // Primary selector: .movie-card (used by search results)
            val results = doc.select("li.movie-card, .movie-card").mapNotNull { element ->
                val linkElement = element.selectFirst("a[href]") ?: return@mapNotNull null
                val href = linkElement.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                
                val img = element.selectFirst("img")
                val poster = img?.attr("src") ?: img?.attr("data-src")
                
                val title = img?.attr("alt")?.trim()
                    ?: element.selectFirst(".title, h3, h2, p")?.text()?.trim()
                    ?: return@mapNotNull null
                
                val isTvShow = tvShowRegex.containsMatchIn(fullHref) || title.contains("Season", ignoreCase = true)
                
                if (isTvShow) {
                    newTvSeriesSearchResponse(cleanTitle(title), fullHref, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                } else {
                    newMovieSearchResponse(cleanTitle(title), fullHref, TvType.Movie) {
                        this.posterUrl = poster
                    }
                }
            }
            
            // Fallback: try li.thumb selector (homepage style)
            results.ifEmpty {
                doc.select("li.thumb").mapNotNull { it.toSearchResult() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        val rawTitle = doc.selectFirst("h1")?.text()?.trim() 
            ?: doc.selectFirst(".entry-title, .post-title")?.text()?.trim()
            ?: return null
        
        val title = cleanTitle(rawTitle)
        
        val poster = doc.select("img").firstOrNull { 
            it.attr("src").contains("tmdb") || it.attr("src").contains("image.tmdb.org")
        }?.attr("src") ?: doc.selectFirst(".entry-content img, article img")?.attr("src")
        
        val year = yearRegex.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        
        val plot = doc.select("p").firstOrNull { 
            val text = it.text().trim()
            text.length > 100 && !text.contains("download", ignoreCase = true) 
                && !text.contains("click here", ignoreCase = true)
        }?.text()?.trim() ?: rawTitle
        
        val isTvShow = tvShowRegex.containsMatchIn(url) || rawTitle.contains("Season", ignoreCase = true)
        
        return if (isTvShow) {
            val episodes = mutableListOf<Episode>()
            
            doc.select("a").forEach { link ->
                val href = link.attr("href")
                val text = link.text().trim()
                
                val episodeMatch = episodeRegex.find(text)
                if (episodeMatch != null && (href.contains("gadgetsweb") || href.contains("hubdrive") || href.contains("hubcloud"))) {
                    val epNum = episodeMatch.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: (episodes.size + 1)
                    episodes.add(newEpisode(href) {
                        this.name = "Episode $epNum"
                        this.season = 1
                        this.episode = epNum
                    })
                }
            }
            
            if (episodes.isEmpty()) {
                episodes.add(newEpisode(url) {
                    this.name = "All Episodes"
                    this.season = 1
                    this.episode = 1
                })
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
        val doc = if (data.startsWith("http")) {
            app.get(data).document
        } else {
            return false
        }
        
        val serverLinks = doc.select("a").mapNotNull { link ->
            val href = link.attr("href").lowercase()
            when {
                href.contains("hubdrive") -> link.attr("href")
                href.contains("hubcloud") -> link.attr("href")
                href.contains("gadgetsweb") -> link.attr("href")
                href.contains("4khdhub") -> link.attr("href")
                else -> null
            }
        }.distinct()
        
        serverLinks.forEach { serverUrl ->
            try {
                loadExtractor(serverUrl, data, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(TAG, "Extractor failed for $serverUrl: ${e.message}")
            }
        }
        
        return serverLinks.isNotEmpty()
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""\s*\([^)]*\d{4}[^)]*\)"""), "")
            .replace(Regex("""\s*\[[^\]]*\]"""), "")
            .replace(Regex("""\s*(WEB-?DL|WEBRip|BluRay|HDTC|HDRip|DVDRip)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*(480p|720p|1080p|2160p|4K)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*(HEVC|x264|x265|10Bit)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*(Hindi|English|Dual Audio|DD\d\.\d)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*(ESub|ESubs|Full Movie|Full Series)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*\|\s*.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
