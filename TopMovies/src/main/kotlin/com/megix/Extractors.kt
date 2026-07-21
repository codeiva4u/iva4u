package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.api.Log

class LeechPro : ExtractorApi() {
    override val name = "LeechPro"
    override var mainUrl = "https://leechpro.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url).document
            doc.select("a[href*='gdflix'], a[href*='fastdlserver'], a[href*='hubcloud'], a[href*='pixeldrain']").forEach { element ->
                val link = element.attr("href")
                if (link.isNotBlank()) {
                    loadExtractor(link, url, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e("LeechPro", "Error: ${e.message}")
        }
    }
}

open class fastdlserver : ExtractorApi() {
    override val name = "fastdlserver"
    override var mainUrl = "https://fastdlserver.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val location = app.get(url, allowRedirects = false).headers["location"]
        if (location != null) {
            loadExtractor(location, "", subtitleCallback, callback)
        }
    }
}

class GDLink : GDFlix() {
    override var mainUrl = "https://gdlink.*"
}

class GDFlixApp : GDFlix() {
    override var mainUrl = "https://new.gdflix.*"
}

class GdFlix1 : GDFlix() {
    override var mainUrl = "https://new1.gdflix.*"
}

class GdFlix2 : GDFlix() {
    override var mainUrl = "https://*.gdflix.*"
}

class GDFlixNet : GDFlix() {
    override var mainUrl = "https://new15.gdflix.*"
}

open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var baseUrl = getBaseUrl(url)
        val latestBaseUrl = getLatestBaseUrl(baseUrl, "gdflix")
        var newUrl = url
        if (baseUrl != latestBaseUrl) {
            newUrl = url.replace(baseUrl, latestBaseUrl)
            baseUrl = latestBaseUrl
        }

        val document = app.get(newUrl).document
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name : ").orEmpty()
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size : ").orEmpty()
        val quality = getIndexQuality(fileName)

        suspend fun myCallback(link: String, server: String = "") {
            callback.invoke(
                newExtractorLink(
                    "${name}${server}",
                    "${name}${server} ${fileName}[${fileSize}]",
                    link,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                }
            )
        }

        document.select("div.text-center a").amap { anchor ->
            val text = anchor.select("a").text()
            val link = anchor.attr("href")

            when {
                text.contains("FSL V2") -> myCallback(link, "[FSL V2]")
                text.contains("DIRECT DL") -> myCallback(link, "[Direct]")
                text.contains("DIRECT SERVER") -> myCallback(link, "[Direct]")
                text.contains("CLOUD DOWNLOAD [R2]") -> myCallback(link, "[Cloud]")
                text.contains("GD Index") -> {
                    val cfLink = baseUrl + link
                    val cfTypes = listOf(1, 2)
                    cfTypes.amap { cfType ->
                        app.get(cfLink + "?type=$cfType")
                            .document
                            .select("a.btn-success")
                            .amap {
                                val source = it.attr("href")
                                myCallback(source, "[CF]")
                            }
                    }
                }
                text.contains("FAST CLOUD") -> {
                    val dlink = app.get(baseUrl + link)
                        .document
                        .select("div.card-body a")
                        .attr("href")
                    if (dlink == "") return@amap
                    myCallback(dlink, "[FAST CLOUD]")
                }
                link.contains("pixeldra") -> {
                    val baseUrlLink = getBaseUrl(link)
                    val finalURL = if (link.contains("download", true)) link
                    else "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"
                    myCallback(finalURL, "[Pixeldrain]")
                }
                text.contains("Instant DL") -> {
                    try {
                        val instantLink = app.get(link, allowRedirects = false)
                            .headers["location"]?.substringAfter("url=").orEmpty()
                        myCallback(instantLink, "[Instant Download]")
                    } catch (e: Exception) {
                        Log.d("Instant DL", e.toString())
                    }
                }
                text.contains("GoFile") -> {
                    try {
                        app.get(link).document
                            .select(".row .row a").amap { gofileAnchor ->
                                val gLink = gofileAnchor.attr("href")
                                if (gLink.contains("gofile")) {
                                    loadExtractor(gLink, "", subtitleCallback, callback)
                                }
                            }
                    } catch (e: Exception) {
                        Log.d("Gofile", e.toString())
                    }
                }
            }
        }
    }
}
