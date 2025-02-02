package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document

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

    suspend fun extract(document: Document): List<ExtractorLink> {
        val extractedLinks = mutableListOf<ExtractorLink>()
        val header = document.selectFirst("div.card-header.text-white")?.text() ?: ""

        document.select("a.btn.btn-success.btn-lg.h6[href], a.btn.btn-danger.btn-lg.h6[href]").apmap { element ->
            val link = element.attr("href")
            val serverName = element.text().replace("Download \\[", "").replace("]", "")

            when {
                serverName.contains("PixelServer") -> {
                    extractedLinks.add(
                        ExtractorLink(
                            "PixelServer",
                            "PixelServer - $header",
                            link,
                            "",
                            getIndexQuality(header),
                        )
                    )
                }
                serverName.contains("FSL Server") -> {
                    extractedLinks.add(
                        ExtractorLink(
                            "FSL Server",
                            "FSL Server - $header",
                            link,
                            "",
                            getIndexQuality(header),
                        )
                    )
                }
                serverName.contains("10Gbps Server") -> {
                    val redirectUrl = app.get(link, allowRedirects = false).headers["location"] ?: ""
                    extractedLinks.add(
                        ExtractorLink(
                            "10Gbps Server",
                            "10Gbps Server - $header",
                            redirectUrl.substringAfter("link="),
                            "",
                            getIndexQuality(header),
                        )
                    )
                }

                else -> {}
            }
        }
        return extractedLinks
    }


    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val newUrl = url.replace("ink", "dad").replace("art", "dad")
        val doc = app.get(newUrl).document
        val links = extract(doc)
        links.forEach { callback.invoke(it) }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}