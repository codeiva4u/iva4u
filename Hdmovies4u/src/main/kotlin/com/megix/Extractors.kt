package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

class PixelDra : ExtractorApi() {
    override val name            = "PixelDra"
    override val mainUrl         = "https://pixeldra.in"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val mId = Regex("/u/(.*)").find(url)?.groupValues?.get(1)
        if (mId.isNullOrEmpty())
        {
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    url,
                    url,
                    Qualities.Unknown.value,
                )
            )
        }
        else {
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
        val doc = app.get(url).document
        val header = doc.selectFirst("div.card-header")?.text() ?: ""
        
        // Extract all download buttons using CSS selector
        doc.select("a.btn.btn-success.btn-lg").forEach { element ->
            val link = element.attr("href")
            val text = element.text()

            when {
                // Match PixelServer using regex pattern
                Regex("pixeldra\\.in/api/file/[^\\s\"'<>]+").containsMatchIn(link) -> {
                    callback.invoke(
                        ExtractorLink(
                            "$name[PixelServer]",
                            "$name[PixelServer] - $header",
                            link,
                            "",
                            getIndexQuality(header),
                        )
                    )
                }
                // Match FSL Server using regex pattern
                Regex("fsl\\.fastdl\\.lol/[^\\s\"'<>]+").containsMatchIn(link) -> {
                    callback.invoke(
                        ExtractorLink(
                            "$name[FSL Server]",
                            "$name[FSL Server] - $header",
                            link,
                            "",
                            getIndexQuality(header),
                        )
                    )
                }
                // Match 10Gbps Server and handle redirect
                Regex("gpdl2\\.technorozen\\.workers\\.dev/\\?id=[^\\s\"'<>]+").containsMatchIn(link) -> {
                    val redirectLink = app.get(link, allowRedirects = false).headers["location"] ?: ""
                    callback.invoke(
                        ExtractorLink(
                            "$name[10Gbps Server]",
                            "$name[10Gbps Server] - $header",
                            redirectLink,
                            "",
                            getIndexQuality(header),
                        )
                    )
                }
                // Fallback to generic extractor
                else -> {
                    loadExtractor(link, "", subtitleCallback, callback)
                }
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "") ?. groupValues ?. getOrNull(1) ?. toIntOrNull()
            ?: Qualities.Unknown.value
    }
}