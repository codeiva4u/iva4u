package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import java.io.Serializable

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

class DriveTot : HubCloud() {
    override val mainUrl: String = "https://drivetot.zip"
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
                loadExtractor(link,"",subtitleCallback, callback)
            }
        }

        class DriveTot : ExtractorApi() {
            override val name = "DriveTot"
            override val mainUrl = "https://drivetot.zip"
            override val requiresReferer = true

            override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
                // Handle scanjs redirect
                val initialDoc = app.get(url).document
                val scanjsUrl = initialDoc.selectFirst("form#download-form")?.attr("action") ?: url

                // Submit the download form
                val formResponse = app.post(scanjsUrl, data = mapOf(
                    ("_token" to initialDoc.selectFirst("input[name=_token]")?.attr("value")) ?: "",
                    "_method" to "POST"
                ))

                // Follow redirect to final hubcloud page
                val hubcloudUrl = formResponse.headers["location"] ?: throw Error("No redirect location found")
                val finalDoc = app.get(hubcloudUrl).document

                // Extract final download link
                val downloadLink = finalDoc.selectFirst("a[href*='hubcloud'][style*='background-color: #2ec7ee']")?.attr("href")
                    ?: throw Error("No HubCloud download link found")

                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        downloadLink,
                        url,
                        Qualities.Unknown.value,
                    )
                )
            }

            private fun mapOf(
                pairs: Serializable,
                pairs1: Pair<String, String>
            ): Map<String, String> {
                TODO("Not yet implemented")
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "") ?. groupValues ?. getOrNull(1) ?. toIntOrNull()
            ?: Qualities.Unknown.value
    }
}