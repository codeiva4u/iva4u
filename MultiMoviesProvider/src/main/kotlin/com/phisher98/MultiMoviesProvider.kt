package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
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
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import org.json.JSONObject
import org.jsoup.nodes.Element


import com.fasterxml.jackson.annotation.JsonProperty

class MultiMoviesProvider : MainAPI() {
    override var mainUrl: String = "https://multimovies.gripe/"

    companion object {
        private val cfKiller by lazy { CloudflareKiller() }

        private var cachedUrls: JSONObject? = null

        private val multimoviesshgBaseUrl: String by lazy {
            runBlocking { getLatestUrl("multimoviesshg") } ?: "https://multimoviesshg.com"
        }

        private val unsBioBaseUrl: String by lazy {
            runBlocking { getLatestUrl("server1.uns.bio") } ?: "https://server1.uns.bio"
        }

        suspend fun getLatestUrl(source: String): String? {
            return try {
                if (cachedUrls == null) {
                    cachedUrls = JSONObject(
                        app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json").text
                    )
                }
                cachedUrls?.optString(source)?.takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }

        fun getMultimoviesshgUrl(): String = multimoviesshgBaseUrl

        fun getUnsBioUrl(): String = unsBioBaseUrl
    }

    init {
        runBlocking {
            getLatestUrl("multimovies")?.let {
                mainUrl = it
            }
        }
    }

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
        "genre/netflix/" to "Netflix",
        "genre/sony-liv/" to "Sony Live",
        "genre/zee-5/" to "Zee5"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}page/$page/"
        val document = app.get(url, interceptor = cfKiller).document

        val home = when {
            request.data.contains("/movies") || request.data.contains("/genre/") -> {
                document.select("article.item, #archive-content > article, div.items > article").mapNotNull {
                    it.toSearchResult()
                }
            }
            request.data.isEmpty() || request.data == "/" -> {
                val featuredMovies = document.select("#featured-titles .item.movies, #featured-titles article.item").mapNotNull {
                    it.toSearchResult()
                }
                val regularMovies = document.select("#dt-movies .item, article.item").mapNotNull {
                    it.toSearchResult()
                }
                val archiveMovies = document.select("#archive-content > article").mapNotNull {
                    it.toSearchResult()
                }
                (featuredMovies + regularMovies + archiveMovies).distinctBy { it.name }
            }
            else -> {
                document.select("article.item, div.items > article, #archive-content > article").mapNotNull {
                    it.toSearchResult()
                }
            }
        }
        return newHomePageResponse(HomePageList(request.name, home))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = when {
            selectFirst("h3.title") != null -> selectFirst("h3.title")!!.text().trim()
            selectFirst(".data h3") != null -> selectFirst(".data h3")!!.text().trim()
            selectFirst(".data h3 a") != null -> selectFirst(".data h3 a")!!.text().trim()
            selectFirst("h3 a") != null -> selectFirst("h3 a")!!.text().trim()
            selectFirst("h3") != null -> selectFirst("h3")!!.text().trim()
            else -> selectFirst("a")?.text()?.trim() ?: ""
        }

        if (title.isBlank()) return null

        val href = when {
            selectFirst(".image a") != null -> fixUrl(selectFirst(".image a")!!.attr("href"))
            selectFirst(".poster a") != null -> fixUrl(selectFirst(".poster a")!!.attr("href"))
            selectFirst(".data h3 a") != null -> fixUrl(selectFirst(".data h3 a")!!.attr("href"))
            selectFirst("h3 a") != null -> fixUrl(selectFirst("h3 a")!!.attr("href"))
            else -> fixUrl(selectFirst("a")?.attr("href") ?: "")
        }

        if (href.isBlank() || href == mainUrl) return null

        val posterUrl = when {
            selectFirst(".image img") != null -> fixUrlNull(selectFirst(".image img")?.getImageAttr())
            selectFirst(".poster img") != null -> fixUrlNull(selectFirst(".poster img")?.getImageAttr())
            selectFirst("img") != null -> fixUrlNull(selectFirst("img")?.getImageAttr())
            else -> null
        }

        val isMovie = href.contains("/movies/")
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", interceptor = cfKiller).document
        return document.select("div.result-item article").mapNotNull { article ->
            val titleElement = article.selectFirst("div.details > div.title > a") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = fixUrl(titleElement.attr("href"))
            val imageDiv = article.selectFirst("div.image > div.thumbnail > a")
            val posterUrl = fixUrlNull(imageDiv?.selectFirst("img")?.getImageAttr())
            val typeText = imageDiv?.selectFirst("span.movies, span.tvshows")?.text()
            val isMovie = typeText?.contains("Movie", ignoreCase = true) == true || !href.contains("tvshows", ignoreCase = true)

            if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
            }
        }
    }

    data class LinkData(
        val name: String?,
        val type: String,
        val post: String,
        val nume: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, interceptor = cfKiller).document
        val title = doc.selectFirst("div.sheader > div.data > h1")?.text()?.trim() ?: ""
        var poster = fixUrlNull(doc.selectFirst("div.sheader div.poster img")?.getImageAttr())
        if (poster.isNullOrBlank()) {
            poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        }
        if (poster.isNullOrBlank()) {
            poster = fixUrlNull(doc.selectFirst("meta[name=twitter:image]")?.attr("content"))
        }
        val tags = doc.select("div.sgeneros > a").map { it.text() }
        val year = doc.selectFirst("span.date")?.text()?.substringAfter(",")?.trim()?.toIntOrNull()
        val description = doc.selectFirst("#info div.wp-content p")?.text()?.trim()
        val type = if (url.contains("/tvshows/")) TvType.TvSeries else TvType.Movie
        val trailer = doc.selectFirst("iframe.rptss")?.attr("src")
        val rating = doc.select("span.dt_rating_vgs").text()
        val duration = doc.selectFirst("span.runtime")?.text()?.removeSuffix(" Min.")?.trim()?.toIntOrNull()
        val actors = doc.select("div.person").map {
            ActorData(
                Actor(
                    it.select("div.data > div.name > a").text(),
                    it.selectFirst("div.img > a > img")?.getImageAttr()
                ),
                roleString = it.select("div.data > div.caracter").text()
            )
        }
        val recommendations = doc.select("#dtw_content_related-2 article").mapNotNull { it.toSearchResult() }

        return if (type == TvType.TvSeries) {
            val episodes = if (doc.select("#seasons ul.episodios > li").isNotEmpty()) {
                doc.select("#seasons ul.episodios > li").mapNotNull {
                    val name = it.select("div.episodiotitle > a").text()
                    val href = it.select("div.episodiotitle > a").attr("href")
                    val posterUrl = it.select("div.imagen > img").attr("src")
                    val numerandoText = it.select("div.numerando").text().trim()
                    val match = Regex("""(?i)(?:s(?:eason)?\s*)?(\d+)(?:\s*[-–]\s*|\s*e(?:pisode)?\s*)(\d+)""").find(numerandoText)
                    val season = match?.groupValues?.get(1)?.toIntOrNull()
                    val episode = match?.groupValues?.get(2)?.toIntOrNull()

                    newEpisode(href) {
                        this.name = name
                        this.posterUrl = posterUrl
                        this.season = season
                        this.episode = episode
                    }
                }
            } else {
                doc.select("ul#playeroptionsul > li")
                    .filter { !it.attr("data-nume").equals("trailer", ignoreCase = true) }
                    .mapIndexed { index, it ->
                        val name = it.selectFirst("span.title")?.text()
                        val typeAttr = it.attr("data-type")
                        val post = it.attr("data-post")
                        val nume = it.attr("data-nume")
                        newEpisode(LinkData(name, typeAttr, post, nume, url).toJson()) {
                            this.name = name
                            this.episode = index + 1
                            this.season = 1
                        }
                    }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
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
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
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

    // ════════════════════════════════════════════════════════════════════════
    // AJAX रिस्पॉन्स डेटा क्लास — DooPlay थीम iframe URL लौटाती है
    // ════════════════════════════════════════════════════════════════════════
    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("type") val type: String? = null,
    )

    // ════════════════════════════════════════════════════════════════════════
    // loadLinks — वीडियो स्ट्रीम URL निकालने का मुख्य फ़ंक्शन
    // ════════════════════════════════════════════════════════════════════════
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tag = "MultiMoviesLinks"

        // जाँच: data URL है या LinkData JSON
        val isUrl = data.startsWith("http")

        if (isUrl) {
            // ═══ मूवी या एपिसोड href URL ═══
            // पेज फ़ेच करके playeroptionsul से सभी प्लेयर ऑप्शन्स निकालना
            Log.d(tag, "URL-आधारित डेटा: $data")
            try {
                val document = app.get(data, interceptor = cfKiller).document

                // प्लेयर ऑप्शन्स पार्स करना (trailer छोड़कर)
                val playerOptions = document.select("ul#playeroptionsul > li")
                    .filter { !it.attr("data-nume").equals("trailer", ignoreCase = true) }

                Log.d(tag, "${playerOptions.size} प्लेयर ऑप्शन्स मिले")

                // प्रत्येक ऑप्शन के लिए AJAX कॉल करना
                playerOptions.amap { element ->
                    val type = element.attr("data-type")
                    val post = element.attr("data-post")
                    val nume = element.attr("data-nume")
                    val serverName = element.selectFirst("span.title")?.text() ?: "Unknown"

                    Log.d(tag, "प्लेयर ऑप्शन: $serverName (type=$type, post=$post, nume=$nume)")

                    try {
                        val iframeUrl = getIframeUrl(type, post, nume)
                        if (!iframeUrl.isNullOrEmpty()) {
                            Log.d(tag, "iframe URL प्राप्त: $iframeUrl")
                            loadExtractorLink(iframeUrl, data, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "ऑप्शन '$serverName' विफल: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "पेज लोड विफल: ${e.message}")
            }
        } else {
            // ═══ LinkData JSON — playeroptionsul से बने एपिसोड ═══
            Log.d(tag, "LinkData JSON आधारित डेटा")
            try {
                val linkData = AppUtils.parseJson<LinkData>(data)
                Log.d(tag, "LinkData: name=${linkData.name}, type=${linkData.type}, post=${linkData.post}, nume=${linkData.nume}")

                val iframeUrl = getIframeUrl(linkData.type, linkData.post, linkData.nume)
                if (!iframeUrl.isNullOrEmpty()) {
                    Log.d(tag, "iframe URL प्राप्त: $iframeUrl")
                    loadExtractorLink(iframeUrl, linkData.url, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e(tag, "LinkData पार्सिंग विफल: ${e.message}")
            }
        }

        return true
    }

    // ════════════════════════════════════════════════════════════════════════
    // DooPlay AJAX कॉल — admin-ajax.php से iframe URL प्राप्त करता है
    // ════════════════════════════════════════════════════════════════════════
    private suspend fun getIframeUrl(type: String, post: String, nume: String): String? {
        return try {
            // FormBody बनाना — DooPlay थीम का मानक AJAX अनुरोध
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
                ),
                interceptor = cfKiller
            ).parsedSafe<ResponseHash>()

            response?.embed_url
        } catch (e: Exception) {
            Log.e("AjaxHelper", "AJAX कॉल विफल: ${e.message}")
            null
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // एक्सट्रैक्टर डिस्पैचर — iframe URL को सही एक्सट्रैक्टर को भेजता है
    // ════════════════════════════════════════════════════════════════════════
    private suspend fun loadExtractorLink(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "ExtractorDispatch"
        Log.d(tag, "डिस्पैच: $url")

        try {
            when {
                // GDMirrorBot → pro.iqsmartgames.com रीडायरेक्ट चेन
                url.contains("gdmirrorbot", true) || url.contains("iqsmartgames", true) ->
                    GDMirrorBot().getUrl(url, referer, subtitleCallback, callback)

                // MultiMoviesSHG — JWPlayer m3u8
                url.contains("multimoviesshg", true) ->
                    MultiMoviesSHG().getUrl(url, referer, subtitleCallback, callback)

                // RpmShare — rpmhub.site
                url.contains("rpmhub", true) ->
                    RpmShare().getUrl(url, referer, subtitleCallback, callback)

                // SmoothPre — smoothpre.com (EarnVids)
                url.contains("smoothpre", true) ->
                    SmoothPre().getUrl(url, referer, subtitleCallback, callback)

                // TechInMind — stream.techinmind.space (TV शो)
                url.contains("techinmind", true) ->
                    TechInMind().getUrl(url, referer, subtitleCallback, callback)

                // अज्ञात डोमेन — GDMirrorBot से ट्राई करना (अक्सर रीडायरेक्ट होता है)
                else -> {
                    Log.d(tag, "अज्ञात डोमेन, GDMirrorBot फ़ॉलबैक: $url")
                    GDMirrorBot().getUrl(url, referer, subtitleCallback, callback)
                }
            }


        } catch (e: Exception) {
            Log.e(tag, "एक्सट्रैक्टर विफल: ${e.message}")
        }
    }
}
