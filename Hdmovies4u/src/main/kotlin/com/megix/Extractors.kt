package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

/*=========================== PixelDra Extractor ===========================*/
class PixelDra : ExtractorApi() {
    override val name            = "PixelDra"
    override val mainUrl         = "https://pixeldra.in"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val mId = Regex("/u/(.*)").find(url)?.groupValues?.get(1)
        if (mId.isNullOrEmpty()) {
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    url,
                    url,
                    Qualities.Unknown.value,
                )
            )
        } else {
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    "$mainUrl/api/file/${mId}?download",
                    url,
                    Qualities.Unknown.value,
                )
            )
        }
    }
}

/*=========================== HubCloud Family Extractors ===========================*/
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
        val newUrl = url.replace("ink", "dad").replace("art", "dad")
        val doc = app.get(newUrl).document
        val header = doc.select("div.card-header").text() ?: ""

        // **डायरेक्ट डाउनलोड लिंक निकालें (FSL Server):**
        doc.select("a.btn.btn-success.btn-lg.h6[href*=fastdl]")?.apmap { fslLink ->
            val link = fslLink.attr("href")
            callback.invoke(
                ExtractorLink(
                    "$name[FSL Server]",
                    "$name[FSL Server] - $header",
                    link,
                    "",
                    Qualities.P720.value, // गुणवत्ता को 720p पर सेट करें (उदाहरण के लिए)
                )
            )
        }

        // **Pixeldra डाउनलोड लिंक निकालें:**
        doc.select("a.btn.btn-success.btn-lg.h6[href*=pixeldra]")?.apmap { pixeldraLink ->
            val link = pixeldraLink.attr("href")
            callback.invoke(
                ExtractorLink(
                    "Pixeldra", // नाम को "Pixeldra" पर सेट करें
                    "Pixeldra - $header",
                    link,
                    "",
                    Qualities.P720.value, // गुणवत्ता को 720p पर सेट करें (उदाहरण के लिए)
                )
            )
        }

        // **"Download [Server : 10Gbps]" लिंक निकालें:**
        doc.select("a.btn.btn-danger.btn-lg.h6[href*=technorozen]")?.apmap { downloadLink ->
            val link = downloadLink.attr("href")
            callback.invoke(
                ExtractorLink(
                    "$name[Download]", // नाम को "Download" पर सेट करें
                    "$name[Download] - $header",
                    link,
                    "",
                    Qualities.P720.value, // गुणवत्ता को 720p पर सेट करें (उदाहरण के लिए)
                )
            )
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "") ?. groupValues ?. getOrNull(1) ?. toIntOrNull()
            ?: Qualities.Unknown.value
    }
}