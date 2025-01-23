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

    private val pixeldrainRegex = Regex("""/u/([^/?]+)""")
    private val fslQualityRegex = Regex("""(\d{3,4}p|4K)""", RegexOption.IGNORE_CASE)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. Pixeldrain लिंक्स को हैंडल करें (बिना return के)
        val fileId = pixeldrainRegex.find(url)?.groupValues?.get(1)
        if (!fileId.isNullOrEmpty()) {
            callback.invoke(
                ExtractorLink(
                    "PixelDrain",
                    "PixelDrain",
                    "https://pixeldrain.com/api/file/$fileId?download", // डोमेन सही करें (pixeldrain.com)
                    url,
                    Qualities.Unknown.value
                )
            )
            // return नहीं लगाएं ताकि FSL लिंक्स भी प्रोसेस हों
        }

        // 2. FSL Server लिंक्स निकालें
        try {
            val doc = app.get(url, headers = mapOf("Referer" to mainUrl)).document

            // Pixeldrain के अलावा अन्य लिंक्स के लिए
            if (fileId == null) {
                doc.select("h2 > a.btn.btn-success.btn-lg.h6").forEach { link ->
                    if (link.text().contains("FSL Server", ignoreCase = true)) {
                        val href = link.attr("href")
                            .replace("[[ moviesdrives.com ]]", "moviesdrives.com")
                            .replace(" ", "%20")

                        val qualityMatch = fslQualityRegex.find(link.text())
                        val (qualityName, qualityValue) = when {
                            qualityMatch != null -> {
                                val q = qualityMatch.value
                                Pair(q, when (q.lowercase()) {
                                    "720p" -> Qualities.P720.value
                                    "1080p" -> Qualities.P1080.value
                                    "4k" -> Qualities.P2160.value
                                    else -> Qualities.Unknown.value
                                })
                            }
                            else -> Pair("Unknown", Qualities.Unknown.value)
                        }

                        callback.invoke(
                            ExtractorLink(
                                "FSL Server",
                                "FSL $qualityName",
                                href,
                                url,
                                qualityValue
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}