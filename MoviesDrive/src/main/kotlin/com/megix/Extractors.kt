package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URLDecoder

// ✅ PixelDrain Extractor (नए/पुराने URL सपोर्ट)
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
        val regex = Regex("""/(api/file|u)/([^/?]+)""")
        val mId = regex.find(url)?.groupValues?.get(2)
        val directUrl = mId?.let { "$mainUrl/api/file/$it?download" } ?: url

        callback.invoke(
            ExtractorLink(
                name,
                name,
                directUrl,
                url,
                Qualities.Unknown.value
            )
        )
    }
}

// ✅ HubCloud Extractor (FSL + GoogleVideo सपोर्ट)
class HubCloud : ExtractorApi() {
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
            val doc = app.get(url, headers = mapOf("Referer" to mainUrl)).document

            // 🔍 सभी प्रासंगिक लिंक्स पकड़ें
            doc.select("a:contains(Download [FSL Server]), a#vd").forEach { link ->
                var href = link.attr("href")
                    .replace("[[ moviesdrives.com ]]", "moviesdrives.com")
                    .trim()

                // 🔗 URL डीकोडिंग
                href = URLDecoder.decode(href, "UTF-8")

                // 🎚️ क्वालिटी डिटेक्शन
                val quality = when {
                    href.contains("720p", true) -> Qualities.P720.value
                    href.contains("1080p", true) -> Qualities.P1080.value
                    href.contains("4k", true) -> Qualities.P2160.value
                    else -> Qualities.Unknown.value
                }

                // 🏷️ Extractor प्रकार चुनें
                val sourceName = when {
                    href.contains("pixeldra.in") -> "PixelDrain"
                    href.contains("googleusercontent.com") -> "GoogleVideo"
                    else -> "FSL Server"
                }

                callback.invoke(
                    ExtractorLink(
                        sourceName,
                        "$sourceName ${quality}p",
                        href,
                        url,
                        quality
                    )
                )
            }
        } catch (e: Exception) {
            println("HubCloud Error: ${e.message}")
        }
    }
}

// ✅ नया GoogleVideo Extractor
class GoogleVideo : ExtractorApi() {
    override val name = "GoogleVideo"
    override val mainUrl = "https://video-downloads.googleusercontent.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val decodedUrl = URLDecoder.decode(url, "UTF-8")
        callback.invoke(
            ExtractorLink(
                name,
                "Google Video (Direct)",
                decodedUrl,
                url,
                Qualities.P720.value
            )
        )
    }
}