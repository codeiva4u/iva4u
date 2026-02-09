package com.multimovies

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MultimoviesProvider : MainAPI() {
    override var mainUrl = "https://multimovies.gripe"
    override var name = "Multimovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "Movies",
        "$mainUrl/tvshows/page/" to "TV Shows",
        "$mainUrl/genre/action/page/" to "Action",
        "$mainUrl/genre/comedy/page/" to "Comedy",
        "$mainUrl/genre/drama/page/" to "Drama",
        "$mainUrl/genre/thriller/page/" to "Thriller"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("article.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.wp-content p")?.text()?.trim()
        val year = document.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
        
        // Check if it's a TV series based on URL pattern
        val isTvSeries = url.contains("/episodes/") || 
                        url.contains("/tvshows/") ||
                        document.select("div.seasons").isNotEmpty()
        
        return if (isTvSeries) {
            val episodes = arrayListOf<Episode>()
            
            // Try to get all episodes from the TV show page
            val episodeElements = document.select("div.episodios li, ul.episodios li")
            
            if (episodeElements.isNotEmpty()) {
                episodeElements.forEach { ep ->
                    val epTitle = ep.selectFirst("a")?.text()?.trim() ?: ""
                    val epUrl = ep.selectFirst("a")?.attr("href") ?: ""
                    val epNum = Regex("""(\d+)x(\d+)|[Ss](\d+)[Ee](\d+)""")
                        .find(epUrl)?.let { match ->
                            val groups = match.groupValues
                            Pair(
                                groups[1].toIntOrNull() ?: groups[3].toIntOrNull() ?: 1,
                                groups[2].toIntOrNull() ?: groups[4].toIntOrNull() ?: 1
                            )
                        } ?: Pair(1, 1)
                    
                    if (epUrl.isNotEmpty()) {
                        episodes.add(
                            Episode(
                                fixUrl(epUrl),
                                epTitle,
                                epNum.first,
                                epNum.second
                            )
                        )
                    }
                }
            } else {
                // Single episode page - extract from URL
                val episodeRegex = Regex("""(\d+)x(\d+)|[Ss](\d+)[Ee](\d+)""", RegexOption.IGNORE_CASE)
                val match = episodeRegex.find(url)
                
                val season = match?.let {
                    it.groupValues[1].toIntOrNull() ?: it.groupValues[3].toIntOrNull() ?: 1
                } ?: 1
                
                val episode = match?.let {
                    it.groupValues[2].toIntOrNull() ?: it.groupValues[4].toIntOrNull() ?: 1
                } ?: 1
                
                episodes.add(
                    Episode(
                        url,
                        title,
                        season,
                        episode
                    )
                )
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    /**
     * loadLinks - Main function to extract video links
     * 
     * Flow for Movies:
     * 1. Main page → iframe (stream.techinmind.space/embed/movie/{imdb})
     * 2. → ssn.techinmind.space/svid/{id}
     * 3. → multimoviesshg.com/e/{id}
     * 4. → multimoviesshg.com/f/{id} (download page)
     * 5. → Quality selection (_h=1080p, _n=720p, _l=480p)
     * 6. → Direct download link (*.premilkyway.com)
     * 
     * Flow for TV Shows:
     * 1. Main page → iframe (stream.techinmind.space/embed/tv/{id}/{s}/{e})
     * 2. → ssn.techinmind.space/svid/{id}
     * 3. → server1.uns.bio/#{hash}
     * 4. → Direct download link
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            Log.d("Multimovies", "Loading links for: $data")
            
            val document = app.get(data).document
            
            // Find all iframes on the page
            val iframes = document.select(
                "iframe.metaframe, " +
                "iframe.rptss, " +
                "iframe[src*='stream.techinmind.space'], " +
                "iframe[src*='ssn.techinmind.space'], " +
                "iframe[src*='multimoviesshg.com'], " +
                "iframe[src*='uns.bio'], " +
                "iframe#dooload"
            )
            
            if (iframes.isEmpty()) {
                Log.w("Multimovies", "No iframes found on page, trying alternative selectors")
                
                // Try to find player div with data attributes
                val playerDiv = document.selectFirst("div#dooplay_player_response, div.dooplay_player")
                val playerData = playerDiv?.attr("data-source") ?: ""
                
                if (playerData.isNotEmpty()) {
                    processIframeUrl(playerData, data, subtitleCallback, callback)
                }
                
                return true
            }
            
            Log.d("Multimovies", "Found ${iframes.size} iframes")
            
            // Process each iframe
            for (iframe in iframes) {
                val iframeSrc = iframe.attr("src")
                
                if (iframeSrc.isEmpty() || iframeSrc == "about:blank") {
                    continue
                }
                
                Log.d("Multimovies", "Processing iframe: $iframeSrc")
                
                processIframeUrl(iframeSrc, data, subtitleCallback, callback)
            }
            
            Log.d("Multimovies", "LoadLinks completed")
            return true
            
        } catch (e: Exception) {
            Log.e("Multimovies", "Error in loadLinks: ${e.message}")
            return false
        }
    }
    
    /**
     * Process iframe URL and route to appropriate extractor
     */
    private suspend fun processIframeUrl(
        iframeSrc: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            when {
                // Direct routing to Multimoviesshg
                iframeSrc.contains("multimoviesshg.com", ignoreCase = true) -> {
                    Log.d("Multimovies", "Routing to Multimoviesshg extractor")
                    Multimoviesshg().getUrl(iframeSrc, referer, subtitleCallback, callback)
                }
                
                // Direct routing to Server1UnsBio
                iframeSrc.contains("uns.bio", ignoreCase = true) -> {
                    Log.d("Multimovies", "Routing to Server1UnsBio extractor")
                    Server1UnsBio().getUrl(iframeSrc, referer, subtitleCallback, callback)
                }
                
                // Route through TechInMindSpace (intermediate player)
                iframeSrc.contains("ssn.techinmind.space", ignoreCase = true) -> {
                    Log.d("Multimovies", "Routing to TechInMindSpace extractor")
                    TechInMindSpace().getUrl(iframeSrc, referer, subtitleCallback, callback)
                }
                
                // Route through StreamTechInMind (main embed)
                iframeSrc.contains("stream.techinmind.space", ignoreCase = true) -> {
                    Log.d("Multimovies", "Routing to StreamTechInMind extractor")
                    StreamTechInMind().getUrl(iframeSrc, referer, subtitleCallback, callback)
                }
                
                // Fallback - try built-in extractors
                else -> {
                    Log.d("Multimovies", "Trying built-in extractor for: $iframeSrc")
                    loadExtractor(iframeSrc, referer, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e("Multimovies", "Error processing iframe: ${e.message}")
        }
    }
}
