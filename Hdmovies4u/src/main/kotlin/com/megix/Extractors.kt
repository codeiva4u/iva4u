package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

class PixelDra : ExtractorApi() {
    override val name = "PixelDra"
    override val mainUrl = "https://pixeldra.in"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
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
        val newUrl = url.replace("ink", "dad").replace("art", "dad")
        val doc = app.get(newUrl).document
        val link = if (url.contains("drive")) {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
            Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
        } else {
            doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
        }

        if (link.isNullOrEmpty()) {
            return
        }
        val document = app.get(link).document
        val div = document.selectFirst("div.card-body")
        val header = document.select("div.card-header").text() ?: ""
        div?.select("h2 a.btn")?.apmap {
            val downloadLink = it.attr("href")
            val text = it.text()

            when {
                text.contains("Download [FSL Server]") -> {
                    callback.invoke(
                        ExtractorLink(
                            "$name[FSL Server]",
                            "$name[FSL Server] - $header",
                            downloadLink,
                            "",
                            getIndexQuality(header),
                        )
                    )
                }
                text.contains("Download [PixelServer") -> { // Modified to handle PixelServer links
                    callback.invoke(
                        ExtractorLink(
                            "$name[PixelServer]", // Changed name for clarity
                            "$name[PixelServer] - $header", // Changed name for clarity
                            downloadLink,
                            "",
                            getIndexQuality(header),
                        )
                    )
                }
                text.contains("Download [Server : 10Gbps]") -> {
                    val dlink = app.get(downloadLink, allowRedirects = false).headers["location"] ?: ""
                    callback.invoke(
                        ExtractorLink(
                            "$name[Server 10Gbps]", // Changed name for clarity
                            "$name[Server 10Gbps] - $header", // Changed name for clarity
                            dlink.substringAfter("link="),
                            "",
                            getIndexQuality(header),
                        )
                    )
                }
                text.contains("Downoad From Telegram") -> { // Corrected typo "Downoad"
                    callback.invoke(
                        ExtractorLink(
                            "$name[Telegram]",
                            "$name[Telegram] - $header",
                            downloadLink,
                            "",
                            Qualities.Unknown.value, // Telegram links often don't have quality info in header
                        )
                    )
                }
                text.contains("Download File") -> { // Keep "Download File" for other generic links if needed
                    callback.invoke(
                        ExtractorLink(
                            "$name",
                            "$name - $header",
                            downloadLink,
                            "",
                            getIndexQuality(header),
                        )
                    )
                }

                else -> {
                    loadExtractor(downloadLink, "", subtitleCallback, callback) // Fallback for other extractors
                }
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}


class DriveTot : ExtractorApi() {
    override val name = "DriveTot"
    override val mainUrl = "https://drivetot.zip"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Just pass the URL to HubCloud for processing
        HubCloud().getUrl(url, referer, subtitleCallback, callback)
    }
}