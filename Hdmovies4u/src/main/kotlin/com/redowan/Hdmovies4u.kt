package com.redowan

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Wishonly
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Hdmovies4u : MainAPI() {
    override var mainUrl = "https://hdmovies4u.cx"
    override var name = "Hdmovies4u"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/hollywood-movies-1080p/" to "Hollywood Movies",
        "$mainUrl/category/south-hindi-dubbed-720p/" to "South Hindi Dubbed Movies",
        "$mainUrl/category/bollywood-1080p/" to "Bollywood",
        "$mainUrl/category/netflix/" to "Netflix",
        "$mainUrl/category/amazon-prime-video/" to "Amazon Prime Video",
        "$mainUrl/category/disney-plus-hotstar/" to "Disney+ Hotstar",
        "$mainUrl/category/jio-cinema/" to " Jio Cinema",
        "$mainUrl/category/zee5/" to "Zee5",
        "$mainUrl/category/category/sonyliv/" to "SonyLIV",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = if (page == 1) {
            app.get(request.data).document
        } else {
            app.get("${request.data}page/$page/").document
        }

        val home = document.select("section.text-center > div.gridxw").mapNotNull {
            it.toSearchResult()
        }

        return HomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.mt-2 a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("div.mt-2 a")?.attr("href").toString())
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val quality = getQualityFromString(this.select("span.absolute").text().toString())

        return if (href.contains("tvshows", ignoreCase = true)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document

        return document.select("div.gridxw").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.text-gray-500")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("p.poster img")?.attr("src"))
        val tags = document.select("div.page-meta a").map { it.text() }
        val year = document.select("span.text-blue-600")
            .firstOrNull()
            ?.text()
            ?.trim()
            ?.substringAfter(",")
            ?.trim()
            ?.toIntOrNull()
        val description = document.selectFirst("main.page-body p.seoone")?.text()?.trim()
        val type = if (url.contains("tvshows", ignoreCase = true)) TvType.TvSeries else TvType.Movie
        val trailer: String? = null
        val rating = document.selectFirst("a[href*=imdb]")?.text()?.substringAfter("Ratings: ")
            ?.toRatingInt()
        val duration = null
        val actors = null
        val recommendations = document.select("div.pt-4 > div.w-40").mapNotNull {
            it.toSearchResult()
        }

        return if (type == TvType.Movie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
                trailer?.let { addTrailer(it, null) }
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, arrayListOf()) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
                trailer?.let { addTrailer(it, null) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. Direct Download Links (FSL, PixelDrain, 10Gbps)
        document.select("main.page-body a[href*='fsl.fastdl.lol'], main.page-body a[href*='pixeldra.in/api/file/'], main.page-body a[href*='technorozen.workers.dev']")
            .map {
                safeApiCall {
                    val link = it.attr("href")
                    println("Direct Download Link found: $link")
                    when {
                        link.contains("fsl.fastdl.lol") -> {
                            callback.invoke(
                                ExtractorLink(
                                    "FSL Server",
                                    "FSL Server",
                                    link,
                                    "",
                                    Qualities.Unknown.value,
                                    false
                                )
                            )
                        }

                        link.contains("pixeldra.in/api/file/") -> {
                            // Extract ID from the link
                            val id =
                                link.substringAfter("pixeldra.in/api/file/").substringBefore("?")

                            callback.invoke(
                                ExtractorLink(
                                    "PixelDrain",
                                    "PixelServer:2",
                                    "https://pixeldrain.com/api/file/$id?download",
                                    "",
                                    Qualities.Unknown.value,
                                    false
                                )
                            )
                        }

                        link.contains("technorozen.workers.dev") -> {
                            callback.invoke(
                                ExtractorLink(
                                    "10Gbps Server",
                                    "10Gbps Server",
                                    link,
                                    "",
                                    Qualities.Unknown.value,
                                    false
                                )
                            )
                        }
                    }
                }
            }

        // 2. Drivetot Links
        document.select("div.mt-3.mb-3.d-flex.flex-column a[href*='drivetot.dad/scanjs/']").map {
            safeApiCall {
                val link = it.attr("href")
                println("Drivetot ScanJS link detected: $link")
                val encodedUrl = link.substringAfter("drivetot.dad/scanjs/")
                val decodedUrl =
                    String(android.util.Base64.decode(encodedUrl, android.util.Base64.DEFAULT))
                println("Decoded URL: $decodedUrl")

                when {
                    decodedUrl.contains("hubcloud.club") -> {
                        // Handle HubCloud link
                        // You might need to adjust the logic here based on how HubCloud links are handled
                        val hubCloudLink = fixUrl(
                            app.get(decodedUrl).document.selectFirst("center a.btn")?.attr("href")
                                .toString()
                        )
                        if (hubCloudLink.isNullOrBlank()) {
                            println("HubCloud link is null")
                        } else {
                            callback.invoke(
                                ExtractorLink(
                                    "HubCloud",
                                    "HubCloud",
                                    hubCloudLink,
                                    data,
                                    Qualities.Unknown.value,
                                    false
                                )
                            )
                        }
                    }

                    decodedUrl.contains("filebee.xyz") -> {
                        // Handle FilePress link
                        callback.invoke(
                            ExtractorLink(
                                "FilePress",
                                "FilePress",
                                decodedUrl,
                                data,
                                Qualities.Unknown.value,
                                false
                            )
                        )
                    }

                    decodedUrl.contains("telegram.me") -> {
                        // Handle Telegram link
                        callback.invoke(
                            ExtractorLink(
                                "Telegram",
                                "Telegram",
                                decodedUrl,
                                data,
                                Qualities.Unknown.value,
                                false
                            )
                        )
                    }

                    else -> {
                        println("Unknown decoded URL: $decodedUrl")
                    }
                }
            }
        }

        // 3. Other Extractors (Wishonly, PixelDrain as is)
        document.select("main.page-body a[href*=drivetot], main.page-body a[href*=wishonly], main.page-body a[href*=pixeldra.in], main.page-body a[href*='token=']")
            .map {
                safeApiCall {
                    val link = it.attr("href")
                    println("Link found: $link")

                    when {
                        link.contains("drivetot") -> {
                            println("Drivetot link detected: $link")
                            Drivetot().getUrl(link, data, subtitleCallback, callback)
                        }

                        link.contains("wishonly") -> {
                            println("Wishonly link detected: $link")
                            loadExtractor(link, data, subtitleCallback, callback)
                        }

                        link.contains("pixeldra.in") -> {
                            println("PixelDrain link detected: $link")
                            PixelDrain().getUrl(link, data, subtitleCallback, callback)
                        }

                        link.contains("token=") -> {
                            println("HubCloud Direct link Detected: $link")
                            // Extract the token value from the URL
                            val tokenUrl = it.attr("href")
                            val hubCloudDirectLink = fixUrl(
                                app.get(tokenUrl).document.selectFirst("center a.btn")?.attr("href")
                                    .toString()
                            )

                            if (hubCloudDirectLink.isNullOrBlank()) {
                                println("HubCloud Direct link is null $hubCloudDirectLink")
                            } else {
                                callback.invoke(
                                    ExtractorLink(
                                        "HubCloud Direct",
                                        "HubCloud Direct",
                                        hubCloudDirectLink,
                                        data,
                                        Qualities.Unknown.value,
                                        false
                                    )
                                )
                            }

                        }

                        else -> {
                            println("No Extractor Matched ")
                        }
                    }
                }
            }

        return true
    }
}