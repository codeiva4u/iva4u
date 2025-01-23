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

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Pixeldrain लिंक्स को हैंडल करें
        val fileId = pixeldrainRegex.find(url)?.groupValues?.get(1)
        if (!fileId.isNullOrEmpty()) {
            val directUrl = "https://pixeldra.in/api/file/$fileId?download"
            callback.invoke(
                ExtractorLink(
                    "PixelDrain",
                    "PixelDrain",
                    directUrl,
                    url,
                    Qualities.Unknown.value
                )
            )
            return
        }

        // अन्य लिंक्स के लिए (यदि कोई हो)
        val doc = app.get(url, headers = mapOf("Referer" to mainUrl)).document
        // यहाँ अतिरिक्त लॉजिक जोड़ें
    }
}

class HubCloudInk : HubCloud() {
    override val mainUrl = "https://hubcloud.ink"
}

class HubCloudArt : HubCloud() {
    override val mainUrl = "https://hubcloud.art"
}