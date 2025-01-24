package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

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
        try {
            // 1. रीडायरेक्ट्स को फॉलो करें
            val finalUrl = app.get(url, allowRedirects = true).url
            println("Debug: अंतिम URL - $finalUrl")

            val doc = app.get(finalUrl).document

            // 2. FSL सर्वर के सभी लिंक निकालें
            doc.select("a.btn.btn-success.btn-lg[href]").forEach { element ->
                val link = element.attr("href")
                val qualityText = element.text()
                val quality = extractQuality(qualityText)
                callback.invoke(
                    ExtractorLink(
                        source = url,
                        name = "$name [FSL]",
                        url = link,
                        referer = mainUrl,
                        quality = quality,
                        isM3u8 = false
                    )
                )
                println("Debug: FSL लिंक मिला - गुणवत्ता: $quality, URL: $link")
            }

            // 3. Pixeldra.in के लिंक निकालें
            doc.select("meta[property='og:video:secure_url']").forEach { element ->
                val link = element.attr("content")
                callback.invoke(
                    ExtractorLink(
                        source = url,
                        name = "Pixeldra.in",
                        url = link,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = false
                    )
                )
                println("Debug: Pixeldra.in लिंक मिला - URL: $link")
            }

        } catch (e: Exception) {
            println("HubCloud त्रुटि: ${e.message}")
        }
    }

    // गुणवत्ता निकालने का हेल्पर फ़ंक्शन (उदा. "720p" → 720)
    private fun extractQuality(text: String): Int {
        return Regex("(\\d{3,4})[pP]").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}