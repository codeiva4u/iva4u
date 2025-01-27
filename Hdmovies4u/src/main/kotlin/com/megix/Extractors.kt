package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

/*=========================== PixelDra Extractor ===========================*/
class PixelDra : ExtractorApi() {
    override val name            = "PixelDra"
    override val mainUrl         = "https://pixeldra.in"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val mId = Regex("/u/(.*)").find(url)?.groupValues?.get(1)
        if (mId.isNullOrEmpty()) {
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    url,
                    url,
                    Qualities.Unknown.value,
                )
            )
        } else {
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    "$mainUrl/api/file/${mId}?download",
                    url,
                    Qualities.Unknown.value,
                )
            )
        }
    }
}

/*=========================== HubCloud Family Extractors ===========================*/
class HubCloudInk : HubCloud() {
    override val mainUrl: String = "https://hubcloud.ink"
}

class HubCloudArt : HubCloud() {
    override val mainUrl: String = "https://hubcloud.art"
}

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.dad"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Step 1: drivetot.zip रिडाइरेक्ट को बायपास करें
        val newUrl = if (url.contains("drivetot.zip")) {
            mainUrl + url.substringAfter("/scanjs") // URL रीराइटिंग
        } else {
            url.replace("ink", "dad").replace("art", "dad") // पुराने domains को dad में बदलें
        }

        // Step 2: वीडियो लिंक निकालें
        val doc = app.get(newUrl).document
        val link = if (newUrl.contains("drive")) {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
            Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
        } else {
            doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
        }

        // Step 3: सभी सर्वर लिंक्स प्रोसेस करें
        val document = app.get(link).document
        val div = document.selectFirst("div.card-body")
        val header = document.select("div.card-header").text() ?: ""
        div?.select("h2 a.btn")?.apmap {
            val serverLink = it.attr("href")
            val text = it.text()

            when {
                text.contains("Download [FSL Server]") -> {
                    callback.invoke(
                        ExtractorLink(
                            "$name[FSL]",
                            "$name[FSL] - $header",
                            serverLink,
                            "",
                            getQuality(header),
                        )
                    )
                }
                text.contains("Download File") -> {
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "$name - $header",
                            serverLink,
                            "",
                            getQuality(header),
                        )
                    )
                }
                text.contains("BuzzServer") -> {
                    val buzzLink = app.get("$serverLink/download", allowRedirects = false).headers["location"] ?: ""
                    callback.invoke(
                        ExtractorLink(
                            "$name[Buzz]",
                            "$name[Buzz] - $header",
                            buzzLink,
                            "",
                            getQuality(header),
                        )
                    )
                }
                serverLink.contains("pixeldra") -> {
                    callback.invoke(
                        ExtractorLink(
                            "PixelDra",
                            "PixelDra - $header",
                            serverLink,
                            "",
                            getQuality(header),
                        )
                    )
                }
                else -> {
                    loadExtractor(serverLink, "", subtitleCallback, callback)
                }
            }
        }
    }

    // वीडियो क्वालिटी निकालने के लिए (उदा. 720p, 1080p)
    private fun getQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}