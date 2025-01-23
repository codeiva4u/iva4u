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

// Extractors.kt में HubCloud क्लास का अपडेटेड कोड

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

        // नए एलिमेंट्स हटाए गए
        doc.select(".loading, .ads-btns, .alert, .adblock-detector, .popup").remove()

        // केस 1: डायरेक्ट डाउनलोड लिंक
        val scriptTag = doc.selectFirst("script:containsData(window.location)")
        val urlRegex = Regex("""(https?://[^\s'"]*\/[^\s'"]*\.(?:mp4|m3u8|mkv|avi))""")
        val directLink = scriptTag?.let { urlRegex.find(it.html())?.value }

        if (!directLink.isNullOrEmpty()) {
            callback.invoke(
                ExtractorLink(
                    name,
                    "Hub-Cloud Direct",
                    directLink,
                    "",
                    Qualities.Unknown.value
                )
            )
            return
        }

        // केस 2: टेलीग्राम लिंक
        doc.select("a[href*='telegram'], a#tgbtn").mapNotNull {
            it.attr("href").takeIf { href -> href.isNotEmpty() }
        }.forEach { telegramLink ->
            callback.invoke(
                ExtractorLink(
                    "Telegram",
                    "Telegram Link",
                    telegramLink,
                    "",
                    Qualities.Unknown.value
                )
            )
        }

        // केस 3: अन्य लिंक (नए सिलेक्टर्स के साथ)
        doc.select("""
            a.btn[href*='download'],
            a.download-btn[href*='.mp4'],
            a[href*='streamtape'],
            a[href*='gdflix']
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
    }

    // गुणवत्ता निष्कर्षण में सुधार
    private fun extractQuality(text: String): Int {
        return when {
            Regex("""(720|HD)""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P720.value
            Regex("""(1080|FHD)""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P1080.value
            Regex("""(4K|UHD|2160)""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
}