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
            // 1. Redirects फॉलो करें
            val response = app.get(url, allowRedirects = true)
            val finalUrl = response.url
            val doc = response.document

            // 2. FSL Server लिंक निकालें (नया CSS Selector)
            doc.select("a.btn.btn-success.btn-lg.h6:contains([FSL Server])").forEach { element -> // :contains([FSL Server]) का उपयोग करें
                val link = element.attr("abs:href")
                val qualityText = element.text() // गुणवत्ता टेक्स्ट अब बटन टेक्स्ट में ही है
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
                println("Debug: FSL लिंक मिला - $link (${quality}p)")
            }

            // 3. Pixeldra.in लिंक निकालें (नया CSS Selector)
            doc.select("a.btn.btn-success.btn-lg.h6[style*='background-color: #6f42c1']:contains([PixelServer)").forEach { element -> // :contains([PixelServer) का उपयोग करें
                val link = element.attr("abs:href")
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

            // 4. 10Gbps Server लिंक निकालें (नया CSS Selector)
            doc.select("a.btn.btn-danger.btn-lg.h6:contains([Server : 10Gbps])").forEach { element -> // :contains([Server : 10Gbps]) का उपयोग करें और btn-danger क्लास का उपयोग करें
                val link = element.attr("abs:href")
                callback.invoke(
                    ExtractorLink(
                        source = url,
                        name = "Server 10Gbps",
                        url = link,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value, // गुणवत्ता अज्ञात क्योंकि टेक्स्ट में गुणवत्ता जानकारी नहीं है
                        isM3u8 = false
                    )
                )
                println("Debug: 10Gbps Server लिंक मिला - $link")
            }


        } catch (e: Exception) {
            println("HubCloud Error: ${e.message}")
        }
    }

    // गुणवत्ता निकालने का फ़ंक्शन (720p, 1080p, आदि) - अपरिवर्तित
    private fun extractQuality(text: String): Int {
        return Regex("(\\d{3,4})[pP]").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}