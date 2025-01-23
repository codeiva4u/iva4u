package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

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

        // अवांछित एलिमेंट्स हटाएं
        doc.select("""
            .adblock-detector, 
            .popup, 
            .ads-btns, 
            .alert, 
            script[src*='cleverwebserver']
        """.trimIndent()).remove()

        // डायरेक्ट डाउनलोड लिंक (FSL, PixelServer, 10Gbps)
        doc.select("""
            a[href*='pub-db4aad121b26409eb63bf48ceb693403.r2.dev'], 
            a[href*='pixeldra.in/api/file'], 
            a[href*='gpdl.technorozen.workers.dev']
        """.trimIndent()).apmap { button ->
            val href = button.attr("href")
            val quality = extractQuality(button.text())
            callback.invoke(
                ExtractorLink(
                    name,
                    "$name ${quality}p",
                    href,
                    "",
                    quality
                )
            )
        }

        // टेलीग्राम लिंक
        doc.select("a[href*='t.me'], a[href*='telegram']").apmap { link ->
            callback.invoke(
                ExtractorLink(
                    "Telegram",
                    "Telegram Link",
                    link.attr("href"),
                    "",
                    Qualities.Unknown.value
                )
            )
        }
    }

    private fun extractQuality(text: String): Int {
        return when {
            Regex("720p|HD", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P720.value
            Regex("1080p|FHD", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P1080.value
            Regex("4K|UHD|2160p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
}