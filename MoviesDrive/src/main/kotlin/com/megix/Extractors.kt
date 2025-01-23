package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URLDecoder

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
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sanitizedUrl = url.replace("ink|art".toRegex(), "dad")
        val doc = app.get(sanitizedUrl).document

        // सभी डाउनलोड लिंक्स को कलेक्ट करें
        val links = mutableListOf<ExtractorLink>()

        // 1. FSL सर्वर (R2.dev) लिंक्स
        doc.select("a[href*='pub-db4aad121b26409eb63bf48ceb693403.r2.dev']").forEach { link ->
            val href = link.attr("abs:href")
            val quality = extractQuality(link.text())
            links.add(
                ExtractorLink(
                    "FSL Server",
                    "FSL ${quality}p",
                    href,
                    sanitizedUrl,
                    quality
                )
            )
        }

        // 2. PixelDrain एम्बेड लिंक्स को डायरेक्ट में बदलें
        doc.select("iframe[src*='pixeldra.in/u/']").forEach { iframe ->
            val fileId = iframe.attr("src").substringAfter("/u/").substringBefore("?")
            val directUrl = "https://pixeldra.in/api/file/$fileId?download"
            val quality = extractQuality("720p") // Default to 720p if not specified
            links.add(
                ExtractorLink(
                    "PixelDrain",
                    "PixelDrain ${quality}p",
                    directUrl,
                    sanitizedUrl,
                    quality
                )
            )
        }

        // 3. डायरेक्ट PixelDrain डाउनलोड लिंक्स
        doc.select("a[href*='pixeldra.in/api/file']").forEach { link ->
            val href = link.attr("abs:href")
            val quality = extractQuality(link.text())
            links.add(
                ExtractorLink(
                    "PixelDrain",
                    "PixelDrain ${quality}p",
                    href,
                    sanitizedUrl,
                    quality
                )
            )
        }

        // 4. यूजर को सभी क्वालिटीज़ दिखाएं (HD, FHD, 4K)
        links
            .distinctBy { it.url } // डुप्लीकेट हटाएं
            .sortedByDescending { it.quality } // क्वालिटी के अनुसार सॉर्ट करें
            .forEach { callback.invoke(it) }
    }

    // क्वालिटी पहचानने के लिए अपडेटेड रेगेक्स
    private fun extractQuality(text: String): Int {
        return when {
            Regex("720|HD|HEVC", RegexOption.IGNORE_CASE).find(text) != null -> Qualities.P720.value
            Regex("1080|FHD|BluRay", RegexOption.IGNORE_CASE).find(text) != null -> Qualities.P1080.value
            Regex("4K|UHD|2160|HDR", RegexOption.IGNORE_CASE).find(text) != null -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
}