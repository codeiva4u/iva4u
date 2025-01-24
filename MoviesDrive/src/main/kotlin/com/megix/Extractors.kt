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

// ... (पहले का कोड)

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
            val response = app.get(url, allowRedirects = true)
            val doc = response.document

            // FSL Server लिंक निकालें
            doc.select("a.btn.btn-success:contains([FSL Server])").forEach { element ->
                val link = element.attr("abs:href")
                val fileName = element.attr("download") ?: ""
                val quality = extractQuality(fileName)

                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name [FSL]",
                        url = link,
                        referer = mainUrl,
                        quality = quality, // अब Int प्रकार
                        isM3u8 = false
                    )
                )
                println("Debug: FSL लिंक - $link (${quality}p)")
            }

            // Pixeldra लिंक निकालें
            doc.select("a.btn.btn-success:contains([PixelServer)").forEach { element ->
                val link = element.attr("abs:href")
                val fileName = element.attr("download") ?: ""
                val quality = extractQuality(fileName)

                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "Pixeldra.in",
                        url = link,
                        referer = mainUrl,
                        quality = quality,
                        isM3u8 = false
                    )
                )
                println("Debug: Pixeldra लिंक - $link (${quality}p)")
            }

        } catch (e: Exception) {
            println("Error: ${e.message}")
        }
    }

    // गुणवत्ता निकालने का सही फ़ंक्शन
    private fun extractQuality(text: String): Int {
        return when {
            text.contains("720p", true) -> Qualities.P720.value // Enum के value का सही उपयोग
            text.contains("1080p", true) -> Qualities.P1080.value
            text.contains("2160p", true) -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
}