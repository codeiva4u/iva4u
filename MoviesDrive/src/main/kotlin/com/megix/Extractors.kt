package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URLEncoder

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
        doc.select("""
            .adblock-detector, 
            .popup, 
            .ads-btns, 
            .alert, 
            script[src*='cleverwebserver'],
            iframe[src*='pixeldra.in']
        """.trimIndent()).remove()

        // 2. डायरेक्ट डाउनलोड लिंक्स
        doc.select("""
            a[href*='r2.dev'], 
            a[href*='pixeldra.in/api/file'],
            a[href*='workers.dev'],
            a.btn-success.btn-lg.h6
        """.trimIndent()).apmap { button ->
            var href = button.attr("href")
            href = URLEncoder.encode(href, "UTF-8").replace("%3A", ":")

            callback.invoke(
                ExtractorLink(
                    name,
                    "${name} - ${extractQuality(button.text())}p",
                    href,
                    "",
                    extractQuality(button.text())
                )
            )
        }

        // 3. टेलीग्राम लिंक्स
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

        // 4. स्क्रिप्ट से लिंक निकालें
        doc.select("script:containsData(window.location)").forEach { script ->
            val regex = Regex("""(https?:\/\/[^\s'"]*\.(?:mp4|mkv|m3u8))""")
            regex.findAll(script.html()).forEach { match ->
                callback.invoke(
                    ExtractorLink(
                        name,
                        "${name} (Direct)",
                        match.value,
                        "",
                        Qualities.P720.value
                    )
                )
            }
        }
    }

    private fun extractQuality(text: String): Int {
        return when {
            Regex("720|HD", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P720.value
            Regex("1080|FHD", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P1080.value
            Regex("4K|UHD|2160", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
}