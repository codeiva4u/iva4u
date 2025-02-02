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
        // Handle URL redirects for different domains
        val newUrl = url.replace("ink", "dad").replace("art", "dad")
        val doc = app.get(newUrl).document

        // Extract direct link from drive or regular pages
        val link = if(url.contains("drive")) {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
            Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
        } else {
            doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
        }

        // Get the video page document
        val document = app.get(link).document
        val div = document.selectFirst("div.card-body")
        val header = document.select("div.card-header").text() ?: ""

        // Extract links from all available servers
        div?.select("h2 a.btn, div.text-center a.btn")?.apmap { button ->
            val serverLink = button.attr("href")
            val buttonText = button.text()

            when {
                buttonText.contains("FSL Server", ignoreCase = true) -> {
                    callback.invoke(
                        ExtractorLink(
                            "$name[FSL Server]",
                            "$name[FSL Server] - $header",
                            serverLink,
                            "",
                            getIndexQuality(header),
                        )
                    )
                }
                buttonText.contains("PixelServer", ignoreCase = true) -> {
                    callback.invoke(
                        ExtractorLink(
                            "$name[PixelServer]",
                            "$name[PixelServer] - $header",
                            serverLink,
                            "",
                            getIndexQuality(header),
                        )
                    )
                }
                buttonText.contains("10Gbps Server", ignoreCase = true) -> {
                    callback.invoke(
                        ExtractorLink(
                            "$name[10Gbps Server]",
                            "$name[10Gbps Server] - $header",
                            serverLink,
                            "",
                            getIndexQuality(header),
                        )
                    )
                }
                buttonText.contains("Download File", ignoreCase = true) -> {
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "$name - $header",
                            serverLink,
                            "",
                            getIndexQuality(header),
                        )
                    )
                }
            }
        }
    }

    private fun getIndexQuality(str: String): Int {
        return when {
            str.contains("2160p", ignoreCase = true) -> Qualities.P2160.value
            str.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            str.contains("720p", ignoreCase = true) -> Qualities.P720.value
            str.contains("480p", ignoreCase = true) -> Qualities.P480.value
            str.contains("360p", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}