package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class PixelDrain : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldra.in/"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val mId = Regex("/u/([^/?]+)").find(url)?.groupValues?.get(1)
        val directUrl = if (!mId.isNullOrEmpty()) {
            "$mainUrl/api/file/$mId?download"
        } else {
            url
        }
        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                directUrl,
                url,
                Qualities.Unknown.value
            )
        )
    }
}

open class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.ink"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // HTML डॉक्यूमेंट फ़ेच करें
            val doc = app.get(
                url,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            ).document

            // 1. FSL Server लिंक्स निकालें
            doc.select("a:contains(Download [FSL Server])").forEach { link ->
                // URL सैनिटाइज़ करें
                val href = link.attr("href")
                    .replace("[[%20moviesdrives.com%20]]", "moviesdrives.com")
                    .replace("%20", " ")
                    .trim()

                // क्वालिटी डिटेक्ट करें
                val quality = Regex("""(\d{3,4}p|4K)""", RegexOption.IGNORE_CASE)
                    .find(link.text())?.value ?: "Unknown"

                val qualityValue = when (quality.lowercase()) {
                    "720p" -> Qualities.P720.value
                    "1080p" -> Qualities.P1080.value
                    "4k" -> Qualities.P2160.value
                    else -> Qualities.Unknown.value
                }

                // लिंक पास करें
                callback.invoke(
                    ExtractorLink(
                        "FSL Server",
                        "FSL $quality",
                        href,
                        url,
                        qualityValue
                    )
                )
            }

        } catch (e: Exception) {
            println("HubCloud Error: ${e.message}")
        }
    }
}