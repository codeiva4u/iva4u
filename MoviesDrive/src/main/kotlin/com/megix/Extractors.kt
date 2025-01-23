package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sanitizedUrl = url.replace("ink|art".toRegex(), "dad")
        val doc = app.get(sanitizedUrl).document

        // 1. Remove blocking elements
        doc.select(".adblock-detector, .popup, .ads-btns, .alert, script[src*='cleverwebserver']").remove()

        // 2. Extract direct links from buttons
        doc.select("""
            a[href*='r2.dev'], 
            a[href*='pixeldra.in/api/file'],
            a[href*='workers.dev'],
            a.btn-success.btn-lg.h6
        """.trimIndent()).apmap { button ->
            var href = button.attr("href")
            // Fix URL encoding
            href = href.replace(" ", "%20")

            callback.invoke(
                ExtractorLink(
                    name,
                    "${name} - ${extractQuality(button.text())}p",
                    href,
                    "",
                    extractQuality(button.text()),
                    type = ExtractorLinkType.VIDEO,
                    headers = mapOf("Content-Type" to getMimeType(href))
                )
            )
        }

        // 3. Handle Telegram links
        doc.select("a[href*='t.me'], a[href*='telegram']").apmap { link ->
            callback.invoke(
                ExtractorLink(
                    "Telegram",
                    "Download From Telegram",
                    link.attr("href"),
                    "",
                    Qualities.Unknown.value
                )
            )
        }

        // 4. Extract from JavaScript redirects
        doc.select("script:containsData(window.location)").forEach { script ->
            val regex = Regex("""(https?:\/\/[^\s'"]*\/[^\s'"]*\.(?:mp4|mkv|m3u8))""")
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

    private fun getMimeType(url: String): String {
        return when {
            url.contains(".mkv") -> "video/x-matroska"
            url.contains(".mp4") -> "video/mp4"
            url.contains(".m3u8") -> "application/x-mpegURL"
            else -> "application/octet-stream"
        }
    }
}