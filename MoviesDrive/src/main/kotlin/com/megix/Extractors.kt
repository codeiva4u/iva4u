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
            val finalUrl = app.get(url, allowRedirects = true).url
            val doc = app.get(finalUrl).document

            // FSL Server लिंक
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
            }

            // Pixeldrain लिंक
            doc.select("meta[property='og:video:secure_url']").forEach { element ->
                val link = element.attr("content")
                callback.invoke(
                    ExtractorLink(
                        source = url,
                        name = "Pixeldrain",
                        url = link,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = false
                    )
                )
            }

        } catch (e: Exception) {
            println("HubCloud Error: ${e.message}")
        }
    }

    private fun extractQuality(text: String): Int {
        return Regex("(\\d{3,4})[pP]").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}