package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URLDecoder

class PixelDrain : ExtractorApi() {
    override val name            = "PixelDrain"
    override val mainUrl         = "https://pixeldrain.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val mId = Regex("/u/(.*)").find(url)?.groupValues?.get(1)
        if (mId.isNullOrEmpty())
        {
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    url,
                    url,
                    Qualities.Unknown.value,
                )
            )
        }
        else {
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
        val sanitizedUrl = url.replace("ink|art".toRegex(), "dad")
        val doc = app.get(sanitizedUrl).document

        // 1. नए ब्लॉकिंग एलिमेंट्स हटाएं
        doc.select("""
            .adblock-detector, 
            .popup, 
            .ads-btns, 
            .alert, 
            script[src*='cleverwebserver'],
            iframe[src*='pixeldra.in']
        """.trimIndent()).remove()

        // 2. सभी प्रासंगिक डाउनलोड बटन्स पकड़ें (अपडेटेड सिलेक्टर्स)
        doc.select("""
            a[href*='r2.dev'], 
            a[href*='pixeldra.in/api/file'],
            a[href*='workers.dev'],
            a[class*='btn-success'], 
            a[class*='btn-zip'],
            a[href*='download'][target='_blank']
        """.trimIndent()).apmap { button ->
            var href = button.attr("abs:href") // पूर्ण URL सुनिश्चित करें
            href = URLDecoder.decode(href, "UTF-8") // एन्कोडिंग ठीक करें

            callback.invoke(
                ExtractorLink(
                    name,
                    "${name} - ${extractQuality(button.text())}p",
                    href,
                    sanitizedUrl,
                    extractQuality(button.text())
                )
            )
        }

        // 3. स्क्रिप्ट में छिपे लिंक्स निकालें (अपडेटेड रेगेक्स)
        doc.select("script:containsData(window.location)").forEach { script ->
            val regex = Regex("""(?i)(https?://[^'"]*?\.(?:mp4|mkv|m3u8|avi))""")
            regex.findAll(script.html()).forEach { match ->
                callback.invoke(
                    ExtractorLink(
                        name,
                        "${name} (Auto-Detected)",
                        match.value,
                        sanitizedUrl,
                        Qualities.P720.value
                    )
                )
            }
        }
    }

    // क्वालिटी डिटेक्शन (अपडेटेड)
    private fun extractQuality(text: String): Int {
        return when {
            Regex("720|HD|HEVC", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P720.value
            Regex("1080|FHD|BluRay", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P1080.value
            Regex("4K|UHD|2160|HDR", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
}