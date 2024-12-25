package com.redowan

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
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
    private val headers = mapOf("user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36")

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

    private fun toResult(post: Element): SearchResponse {
        val title = post.select(".entry-title > a").text()
        val check = post.select(".video-label").text()
        val url = post.select(".entry-title > a").attr("href")
        val posterUrl = post.select(".post-thumbnail img").attr("src")
        return newAnimeSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getSearchQuality(check)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/search.php?q=$query", cacheTime = 60, headers = headers
        ).document
        return doc.select("article.post").mapNotNull { toResult(it) }
    }

    private val regex = Regex("(?<=\\)\\s).*")
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(
            url, cacheTime = 60, headers = headers
        ).document
        val title = doc.select(".entry-title").text()
        val image = doc.select(".post-thumbnail img").attr("src")
        val plot = doc.selectFirst(".entry-content p")?.text()
        val year = doc.select(".entry-meta > div:nth-child(9) > div:nth-child(2)")
            .text().toIntOrNull()

        if (doc.selectFirst("div.download-links-div > div:nth-child(2) > a[href*=allset.lol/archive/]") == null) {
            val links = doc.select(".downloads-btns-div").joinToString(" ; ") { link ->
                val quality = link.previousElementSibling()?.text() ?: ""
                val matchResult = regex.find(quality)
                val extractedText = matchResult?.value
                "$extractedText ## ${link.selectFirst("a")?.attr("href") ?: ""}"
            }
            return newMovieLoadResponse(title, url, TvType.Movie, links) {
                this.posterUrl = image
                this.year = year
                this.plot = plot
            }
        } else {
            val episodesData = mutableListOf<Episode>()
            var seasonNum = 1
            doc.select(".download-links-div").map { element ->
                val episodeLinksMap = mutableMapOf<String, String>()
                element.select("div.downloads-btns-div > a").forEach { link ->
                    val quality = link.text()
                    app.get(link.attr("href"), cacheTime = 60, headers = headers)
                        .document.select(".entry-content > a").forEach { episodeLinkElement ->
                            val episodeName = episodeLinkElement.previousElementSibling()?.text()
                            if (episodeName != null) {
                                if (!episodeLinksMap.containsKey(episodeName)) {
                                    episodeLinksMap[episodeName] = ""
                                }
                                episodeLinksMap[episodeName] =
                                    episodeLinksMap[episodeName] + "$quality ## " + "https://allset.lol" +
                                            episodeLinkElement.attr("href") + " ; "
                            }
                        }
                }
                episodeLinksMap.map { (episodeName, episodeLinks) ->
                    episodesData.add(
                        Episode(
                            episodeLinks,
                            episodeName,
                            seasonNum
                        )
                    )
                }
                seasonNum++
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                this.posterUrl = image
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
        data.split(" ; ").forEach {
            val (quality, link) = it.split(" ## ")
            callback.invoke(
                ExtractorLink(
                    mainUrl,
                    "$quality 1",
                    url = "$link?download=main",
                    mainUrl,
                    quality = getVideoQuality(quality),
                    isM3u8 = false,
                    isDash = false
                )
            )
            callback.invoke(
                ExtractorLink(
                    mainUrl,
                    "$quality 2",
                    url = "$link?download=main",
                    mainUrl,
                    quality = getVideoQuality(quality),
                    isM3u8 = false,
                    isDash = false
                )
            )
        }
        return true
    }

    private fun getSearchQuality(check: String?): SearchQuality? {
        val lowercaseCheck = check?.lowercase()
        if (lowercaseCheck != null) {
            return when {
                lowercaseCheck.contains("webrip") || lowercaseCheck.contains("web-dl") -> SearchQuality.WebRip
                lowercaseCheck.contains("bluray") -> SearchQuality.BlueRay
                lowercaseCheck.contains("hdts") || lowercaseCheck.contains("hdcam") || lowercaseCheck.contains(
                    "hdtc"
                ) -> SearchQuality.HdCam

                lowercaseCheck.contains("dvd") -> SearchQuality.DVD
                lowercaseCheck.contains("cam") -> SearchQuality.Cam
                lowercaseCheck.contains("camrip") || lowercaseCheck.contains("rip") -> SearchQuality.CamRip
                lowercaseCheck.contains("hdrip") || lowercaseCheck.contains("hd") || lowercaseCheck.contains(
                    "hdtv"
                ) -> SearchQuality.HD

                else -> null
            }
        }
        return null
    }

    private fun getVideoQuality(string: String?): Int {
        return getVideoQualityRegex.find(string ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private val getVideoQualityRegex = Regex("(\\d{3,4})[pP]")
}
