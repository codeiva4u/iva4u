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

    // PixelDrain लिंक निकालने के लिए रेगेक्स
    private val pixeldrainRegex = Regex("""(https://pixeldra?in/api/file/[^\s"']+)""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, headers = mapOf("Referer" to mainUrl)).document

            // 1. <meta> टैग से PixelDrain लिंक निकालें
            doc.select("meta[property=og:video], meta[property=og:video:secure_url]").forEach { meta ->
                val videoUrl = meta.attr("content")
                if (videoUrl.contains("pixeldra.in/api/file")) {
                    // डाउनलोड लिंक बनाएँ (?download जोड़ें)
                    val downloadUrl = videoUrl.replace("/api/file/", "/api/file/") + "?download"
                    callback.invoke(
                        ExtractorLink(
                            "PixelDrain",
                            "PixelDrain",
                            downloadUrl,
                            url,
                            Qualities.Unknown.value
                        )
                    )
                }
            }

            // 2. FSL Server लिंक्स निकालें (पिछला लॉजिक)
            doc.select("h2 > a.btn.btn-success.btn-lg.h6").forEach { link ->
                if (link.text().contains("FSL Server", ignoreCase = true)) {
                    val href = link.attr("href")
                        .replace("[[ moviesdrives.com ]]", "moviesdrives.com")
                        .replace(" ", "%20")
                    // ... (क्वालिटी डिटेक्शन और कॉलबैक)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}