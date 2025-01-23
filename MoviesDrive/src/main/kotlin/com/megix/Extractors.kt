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

        // 1. ब्लॉकिंग एलिमेंट्स हटाएं
        doc.select("iframe[src*='pixeldra.in'], script[src*='cleverwebserver']").remove()

        // 2. सभी डाउनलोड बटन्स पकड़ें (FSL, PixelServer, आदि)
        doc.select("""
            a[href*='r2.dev'], 
            a[href*='pixeldra.in/api/file'],
            a.btn-success.btn-lg.h6,
            a[style*='background-color: #6f42c1']
        """.trimIndent()).apmap { button ->
            val href = button.attr("abs:href")
            val quality = extractQuality(button.text())

            callback.invoke(
                ExtractorLink(
                    name,
                    "${name} ${quality}p",
                    href,
                    sanitizedUrl,
                    quality
                )
            )
        }

        // 3. PixelDrain लिंक्स सीधे निकालें
        doc.select("a[href^='https://pixeldra.in/api/file']").apmap { link ->
            callback.invoke(
                ExtractorLink(
                    "PixelDrain",
                    "PixelDrain Direct",
                    link.attr("href"),
                    sanitizedUrl,
                    Qualities.P720.value
                )
            )
        }
    }

    private fun extractQuality(text: String): Int {
        return when {
            Regex("720p|HEVC", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P720.value
            Regex("1080p|FHD", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P1080.value
            else -> Qualities.Unknown.value
        }
    }
}