package com.phisher98

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import okhttp3.FormBody

class MultiMoviesProvider : MainAPI() {
    // FIX 1: मुख्य यूआरएल को HTML में पाए गए एक अधिक विश्वसनीय डोमेन पर अपडेट किया गया।
    override var mainUrl = "https://multimovies.agency"
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

    companion object {
        private const val DOMAINS_URL = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
        private var cachedDomains: DomainsParser? = null

        suspend fun getDomains(forceRefresh: Boolean = false): DomainsParser? {
            if (cachedDomains == null || forceRefresh) {
                try {
                    cachedDomains = app.get(DOMAINS_URL).parsedSafe<DomainsParser>()
                } catch (e: Exception) {
                    e.printStackTrace()
                    return null
                }
            }
            return cachedDomains
        }
    }

    // आलसी लोड की गई छवियों को संभालने के लिए सहायक फ़ंक्शन
    private fun Element.getImageUrl(): String? {
        // पहले डेटा-src की जाँच करता है, फिर src पर वापस आता है।
        return this.attr("data-src").ifBlank { this.attr("src") }.ifBlank { null }
    }

    override val mainPage = mainPageOf(
        "trending/" to "Trending",
        "movies/" to "Movies",
        "tvshows/" to "TV Shows",
        "genre/bollywood-movies/" to "Bollywood Movies",
        "genre/hollywood/" to "Hollywood Movies",
        "genre/south-indian/" to "South Indian Movies",
        "genre/punjabi/" to "Punjabi Movies",
        "genre/amazon-prime/" to "Amazon Prime",
        "genre/disney-hotstar/" to "Disney Hotstar",
        "genre/jio-ott/" to "Jio OTT",
        "genre/netflix/" to "Netflix",
        "genre/sony-liv/" to "Sony Liv",
        "genre/k-drama/" to "KDrama",
        "genre/zee-5/" to "Zee5",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val multiMoviesAPI = getDomains()?.multiMovies ?: mainUrl
        val url = if (page == 1) {
            "$multiMoviesAPI/${request.data}"
        } else {
            "$multiMoviesAPI/${request.data}page/$page/"
        }
        val document = app.get(url).document
        
        // FIX: मूवी और टीवी-शो दोनों के आर्काइव पेजों को संभालने के लिए एक अधिक सामान्य चयनकर्ता का उपयोग करें।
        val home = document.select("div.items > article, #archive-content > article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(HomePageList(request.name, home))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.data > h3 > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("div.data > h3 > a")?.attr("href").toString())
        
        // FIX 2: सही img टैग से आलसी-लोड किए गए पोस्टर URL प्राप्त करने के लिए सहायक फ़ंक्शन का उपयोग करें।
        val posterUrl = fixUrlNull(this.selectFirst("div.poster > img")?.getImageUrl())
        val quality = getQualityFromString(this.select("div.poster > div.mepo > span").text())
        
        // अविश्वसनीय टेक्स्ट के बजाय URL के आधार पर प्रकार निर्धारित करें।
        val type = if (href.contains("/movies/")) TvType.Movie else TvType.TvSeries

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val multiMoviesAPI = getDomains()?.multiMovies ?: mainUrl
        val document = app.get("$multiMoviesAPI/?s=$query").document
        // खोज परिणाम संरचना अलग है, इसलिए हम इसे यहाँ अनुकूलित करते हैं।
        return document.select("div.result-item").mapNotNull {
            val title = it.selectFirst("div.title > a")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl(it.selectFirst("div.title > a")?.attr("href").toString())
            
            // FIX 2: आलसी लोड किए गए खोज परिणाम पोस्टर के लिए सहायक का उपयोग करें।
            val posterUrl = fixUrlNull(it.selectFirst("div.poster > img")?.getImageUrl())
            val typeText = it.selectFirst("div.meta span.item-type")?.text() ?: ""
            val type = if (typeText.contains("Movie", true)) TvType.Movie else TvType.TvSeries

            if (type == TvType.Movie) {
                newMovieSearchResponse(title, href, type) { this.posterUrl = posterUrl }
            } else {
                newTvSeriesSearchResponse(title, href, type) { this.posterUrl = posterUrl }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("div.sheader div.data > h1")?.text()?.trim() ?: return null

        // FIX 3: विवरण पृष्ठ के लिए पोस्टर चयनकर्ता को सही किया गया।
        val poster = fixUrlNull(doc.selectFirst("div.sheader div.poster > img")?.getImageUrl())
        val tags = doc.select("div.sgeneros > a").map { it.text() }
        val year = doc.selectFirst("span.date")?.text()?.split(",")?.getOrNull(1)?.trim()?.toIntOrNull()
        val description = doc.selectFirst("#info div.wp-content p")?.text()?.trim()
        val type = if (doc.selectFirst("#seasons") != null) TvType.TvSeries else TvType.Movie
        
        val trailerUrl = doc.selectFirst("#player-option-trailer")?.attr("data-post")?.let { postId ->
             try {
                 val embedResponse = app.post(
                     "$mainUrl/wp-admin/admin-ajax.php",
                     data = mapOf("action" to "doo_player_ajax", "post" to postId, "nume" to "trailer", "type" to "movie"),
                     referer = url
                 ).parsedSafe<ResponseHash>()
                 embedResponse?.embed_url
             } catch (e: Exception) { null }
        }

        val rating = doc.select("span.dt_rating_vgs").text().toRatingInt()
        val duration = doc.selectFirst("span.runtime")?.text()?.removeSuffix(" Min.")?.trim()?.toIntOrNull()
        val actors = doc.select("div.persons > div.person").map {
            ActorData(
                Actor(
                    it.select("div.data > div.name > a").text(),
                    it.selectFirst("div.img > a > img")?.getImageUrl()
                ),
                roleString = it.select("div.data > div.caracter").text(),
            )
        }
        val recommendations = doc.select("#single_relacionados article").mapNotNull { it.toSearchResult() }

        return if (type == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailerUrl)
            }
        } else {
            val episodes = doc.select("#seasons > .se-c").mapNotNull { seasonElement ->
                val seasonName = seasonElement.selectFirst(".se-q > .title")?.text()
                val seasonNum = seasonName?.filter { it.isDigit() }?.toIntOrNull()

                seasonElement.select("ul.episodios > li").mapNotNull { epElement ->
                    val epHref = epElement.selectFirst(".episodiotitle > a")?.attr("href") ?: return@mapNotNull null
                    val epName = epElement.selectFirst(".episodiotitle > a")?.text()
                    val epThumb = epElement.selectFirst("div.poster > img")?.getImageUrl()
                    val epNum = epElement.selectFirst(".numerando")?.text()?.split("x")?.getOrNull(1)?.trim()?.toIntOrNull()

                    newEpisode(epHref) {
                        name = epName
                        posterUrl = epThumb
                        season = seasonNum
                        episode = epNum
                    }
                }
            }.flatten()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailerUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val multiMoviesAPI = getDomains()?.multiMovies ?: mainUrl

        doc.select("ul#playeroptionsul > li").not("[data-nume=trailer]").apmap {
            val postId = it.attr("data-post")
            val nume = it.attr("data-nume")
            val type = it.attr("data-type")

            val source = app.post(
                url = "$multiMoviesAPI/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to postId,
                    "nume" to nume,
                    "type" to type
                ),
                referer = data,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<ResponseHash>()?.embed_url

            if (source != null) {
                // embed_url में अक्सर एक iframe वाला स्ट्रिंग होता है, हमें src निकालने की आवश्यकता है
                val embedLink = Regex("""src=["'](.*?)["']""").find(source)?.groupValues?.get(1)
                if (embedLink != null) {
                    loadExtractor(embedLink, multiMoviesAPI, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
    )

    data class TrailerUrl(
        @JsonProperty("embed_url") var embedUrl: String?,
    )
    
    data class DomainsParser(
        @JsonProperty("MultiMovies")
        val multiMovies: String,
    )
}