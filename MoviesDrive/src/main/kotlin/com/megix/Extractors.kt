package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class PixelDrain : ExtractorApi() {
    override val name            = "PixelDrain"
    override val mainUrl         = "https://pixeldrain.com"
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
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sanitizedUrl = url.replace("ink|art".toRegex(), "dad")
        val doc = app.get(sanitizedUrl, headers = mapOf("Referer" to mainUrl)).document

        // 1. FSL Server (R2.dev) लिंक्स
        doc.select("a[href*='pub-db4aad121b26409eb63bf48ceb693403.r2.dev']").apmap { link ->
            val href = link.attr("abs:href").replace(" ", "%20")
            callback.invoke(
                ExtractorLink(
                    "FSL Server",
                    "FSL ${extractQuality(link.text())}p",
                    href,
                    sanitizedUrl,
                    extractQuality(link.text())
                )
            )
        }

        // 2. PixelDrain Embed लिंक्स को डायरेक्ट में बदलें
        doc.select("iframe[src*='pixeldra.in/u/']").apmap { iframe ->
            val src = iframe.attr("abs:src")
            val fileId = src.substringAfter("/u/").substringBefore("?")
            val directUrl = "https://pixeldra.in/api/file/$fileId?download"
            callback.invoke(
                ExtractorLink(
                    "PixelDrain",
                    "PixelDrain ${extractQuality("720p")}p",
                    directUrl,
                    sanitizedUrl,
                    Qualities.P720.value
                )
            )
        }

        // 3. PixelDrain Direct डाउनलोड लिंक्स
        doc.select("a[href*='pixeldra.in/api/file']").apmap { link ->
            val href = link.attr("abs:href")
            callback.invoke(
                ExtractorLink(
                    "PixelDrain",
                    "PixelDrain ${extractQuality(link.text())}p",
                    href,
                    sanitizedUrl,
                    extractQuality(link.text())
                )
            )
        }
    }

    private fun extractQuality(text: String): Int {
        return when {
            Regex("720|HD|HEVC", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P720.value
            Regex("1080|FHD|BluRay", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P1080.value
            Regex("4K|UHD|2160|HDR", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
}