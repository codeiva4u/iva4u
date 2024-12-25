package com.redowan

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class HDhub4uProvider : MainAPI() {
    override var mainUrl = "https://hdhub4u.cat/"
    override var name = "HDhub4u"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries
    )
    override val mainPage = mainPageOf(
        "/" to "Latest",
        "/category/Bollywood-movies/" to "Bollywood",
        "/category/Hollywood-hindi-dubbed-movies/" to "Hollywood Hindi Movies",
        "/category/South-indian-hindi-movies/" to "South Indian Hindi Movies",
        "/category/Hindi-Web-Series/" to "Hindi Web Series",
        "/category/Hollywood-Hindi-Dubbed-Web-Series/" to "Hollywood Web Series"
    )
    private val headers = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
    )

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(
            "$mainUrl${request.data}page/$page/",
            cacheTime = 60,
            headers = headers,
            allowRedirects = true
        ).document
        val home = doc.select("article.post").mapNotNull { toResult(it) }
        return newHomePageResponse(request.name, home, true)
    }

    private fun toResult(post: Element): SearchResponse? {
        val title = post.select(".entry-title > a").text()
        val url = post.select(".entry-title > a").attr("href")
        val posterUrl = post.select(".post-thumbnail > img").attr("src")

        if (title.isNullOrEmpty() || url.isNullOrEmpty()) return null

        return newAnimeSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getSearchQuality(post.select(".video-label").text())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/search.php?q=$query", cacheTime = 60, headers = headers
        ).document
        return doc.select("article.post").mapNotNull { toResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, cacheTime = 60, headers = headers).document
        val title = doc.select(".entry-title").text()
        val image = doc.select(".post-thumbnail > img").attr("src")
        val plot = doc.selectFirst(".entry-meta > p")?.text()
        val year = doc.select(".entry-meta").text().toIntOrNull()

        val links = doc.select(".downloads-btns-div a").mapNotNull {
            val quality = it.text()
            val link = it.attr("href")
            if (link.isNotEmpty()) "$quality ## $link" else null
        }.joinToString(" ; ")

        return newMovieLoadResponse(title, url, TvType.Movie, links) {
            this.posterUrl = image
            this.year = year
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        data.split(" ; ").forEach {
            val parts = it.split(" ## ")
            if (parts.size == 2) {
                val (quality, link) = parts
                callback.invoke(
                    ExtractorLink(
                        mainUrl,
                        quality,
                        url = link,
                        mainUrl,
                        quality = getVideoQuality(quality),
                        isM3u8 = false,
                        isDash = false
                    )
                )
            }
        }
        return true
    }

    private fun getSearchQuality(check: String?): SearchQuality? {
        val lowercaseCheck = check?.lowercase()
        return when {
            lowercaseCheck == null -> null
            "webrip" in lowercaseCheck || "web-dl" in lowercaseCheck -> SearchQuality.WebRip
            "bluray" in lowercaseCheck -> SearchQuality.BlueRay
            "hdcam" in lowercaseCheck || "hdts" in lowercaseCheck -> SearchQuality.HdCam
            "dvd" in lowercaseCheck -> SearchQuality.DVD
            "cam" in lowercaseCheck -> SearchQuality.Cam
            "hdrip" in lowercaseCheck || "hd" in lowercaseCheck -> SearchQuality.HD
            else -> null
        }
    }

    private fun getVideoQuality(string: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(string ?: "")?.groupValues?.get(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}
