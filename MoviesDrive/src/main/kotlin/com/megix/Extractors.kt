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

class Pixeldra : HubCloud() {
    override val mainUrl: String = "https://pixeldra.in"
}

class fastdlserver : HubCloud() {
    override val mainUrl: String = "https://pub-db4aad121b26409eb63bf48ceb693403.r2.dev/"
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
            // 1. हेडर्स और कुकीज़ के साथ रिक्वेस्ट
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36...",
                "Referer" to mainUrl
            )
            val cookies = mapOf("session_id" to "12345") // आवश्यक कुकीज़

            // 2. रीडायरेक्ट्स फॉलो करें
            val response = app.get(url, headers = headers, cookies = cookies, allowRedirects = true)
            val finalUrl = response.url
            println("Debug: अंतिम URL - $finalUrl")

            // 3. डायनामिक HTML पार्स करें
            val doc = response.document
            println("Debug: HTML Content - ${doc.outerHtml()}") // पूरा HTML लॉग

            // 4. FSL लिंक निकालें (अपडेटेड CSS Selector)
            doc.select("a.btn.btn-success[href]").forEach { element ->
                val link = element.attr("abs:href") // पूर्ण URL सुनिश्चित करें
                val quality = extractQuality(element.text())
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
                println("Debug: FSL लिंक मिला - $link")
            }

            // 5. Pixeldra.in लिंक
            doc.select("meta[property='og:video:url']").forEach { element ->
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
                println("Debug: Pixeldra.in लिंक मिला - $link")
            }

        } catch (e: Exception) {
            println("HubCloud त्रुटि: ${e.message}")
        }
    }

    private fun extractQuality(text: String): Int {
        return Regex("(\\d{3,4})[pP]").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}