package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
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

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val newUrl = url.replace("ink", "dad").replace("art", "dad")
        val doc = app.get(newUrl).document
        val link = if(url.contains("drive")) {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
            Regex("var url = '([^']*)'").find(scriptTag) ?. groupValues ?. get(1) ?: ""
        }
        else {
            doc.selectFirst("div.vd > center > a") ?. attr("href") ?: ""
        }

        val document = app.get(link).document
        extractHubCloudLinks(document, name, callback)
    }

    companion object {
        fun extractHubCloudLinks(document: Document, name: String, callback: (ExtractorLink) -> Unit) {
            val div = document.selectFirst("div.card-body")
            val header = document.select("div.card-header").text() ?: ""
            div?.select("h2 a.btn")?.apmap {
                val link = it.attr("href")
                val text = it.text()

                if (text.contains("Download [FSL Server]"))
                {
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
                else if (text.contains("Download File")) {
                    callback.invoke(
                        ExtractorLink(
                            "$name",
                            "$name - $header",
                            link,
                            "",
                            getIndexQuality(header),
                        )
                    )
                }
                else if(text.contains("BuzzServer")) {
                    val dlink = app.get("$link/download", allowRedirects = false).headers["location"] ?: ""
                    callback.invoke(
                        ExtractorLink(
                            "$name[BuzzServer]",
                            "$name[BuzzServer] - $header",
                            link.substringBeforeLast("/") + dlink,
                            "",
                            getIndexQuality(header),
                        )
                    )
                }

                else if (link.contains("pixeldra")) {
                    callback.invoke(
                        ExtractorLink(
                            "Pixeldra",
                            "Pixeldra - $header",
                            link,
                            "",
                            getIndexQuality(header),
                        )
                    )
                }
                else if (text.contains("Download [Server : 10Gbps]")) {
                    val dlink = app.get(link, allowRedirects = false).headers["location"] ?: ""
                    callback.invoke(
                        ExtractorLink(
                            "$name[Download]",
                            "$name[Download] - $header",
                            dlink.substringAfter("link="),
                            "",
                            getIndexQuality(header),
                        )
                    )
                }
                else
                {
                    loadExtractor(link,"",{}, callback)
                }
            }
        }

        private fun getIndexQuality(str: String?): Int {
            return Regex("(\\d{3,4})[pP]").find(str ?: "") ?. groupValues ?. getOrNull(1) ?. toIntOrNull()
                ?: Qualities.Unknown.value
        }
    }
}

class HDMovies4uHubCloudExtractor : ExtractorApi() {
    override val name = "HDMovies4uHubCloud"
    override val mainUrl = "https://hubcloud.ink" // Placeholder, actual main URL might be different
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Unit {
        val doc = app.get(url).document
        HubCloud.extractHubCloudLinks(doc, name, callback)

        // Extract PixelServer Link
        doc.select("a.btn.btn-success.btn-lg.h6[href*='pixeldra.in']").firstOrNull()?.let {
            val pixelServerLink = it.attr("href")
            callback(ExtractorLink(
                "PixelServer",
                "PixelServer - ${doc.select("div.card-header.text-white.bg-primary.mb-3").text()}",
                pixelServerLink,
                url,
                Qualities.P1080.value // Assuming 1080p quality, adjust if needed
            ))
        }

        // Extract FSL Server Link
        doc.select("a.btn.btn-success.btn-lg.h6[href*='fastdl.lol']").firstOrNull()?.let {
            val fslServerLink = it.attr("href")
            callback(ExtractorLink(
                "FSL Server",
                "FSL Server - ${doc.select("div.card-header.text-white.bg-primary.mb-3").text()}",
                fslServerLink,
                url,
                Qualities.P1080.value // Assuming 1080p quality, adjust if needed
            ))
        }

        // Extract 10Gbps Server Link
        doc.select("a.btn.btn-danger.btn-lg.h6[href*='technorozen.workers.dev']").firstOrNull()?.let {
            val server10GbpsLink = it.attr("href")
            callback(ExtractorLink(
                "10Gbps Server",
                "10Gbps Server - ${doc.select("div.card-header.text-white.bg-primary.mb-3").text()}",
                server10GbpsLink,
                url,
                Qualities.P1080.value // Assuming 1080p quality, adjust if needed
            ))
        }
    }
}