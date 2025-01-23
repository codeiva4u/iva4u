package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

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

class HubCloudInk : HubCloud() {
    override val mainUrl: String = "https://hubcloud.ink"
}

class HubCloudArt : HubCloud() {
    override val mainUrl: String = "https://hubcloud.art"
}

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.dad"
    override val requiresReferer = true // Referer अब आवश्यक है

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sanitizedUrl = url.replace("ink|art".toRegex(), "dad")
        val response = app.get(
            sanitizedUrl,
            headers = mapOf("Referer" to mainUrl) // सर्वर को ब्लॉक करने से रोकें
        )
        val html = response.text

        // 1. सभी लिंक्स और iframes निकालने के लिए रेगेक्स (अपडेटेड)
        val linkRegex = Regex("""<(a|iframe)\s+[^>]*(href|src)=["'](https?://[^"']+)["'][^>]*>([\s\S]*?)</\1>""")
        val matches = linkRegex.findAll(html)

        val links = mutableListOf<ExtractorLink>()
        matches.forEach { match ->
            val linkType = match.groupValues[1] // 'a' या 'iframe'
            val href = match.groupValues[3].replace(" ", "%20")
            val text = match.groupValues[4]

            // 2. FSL, PixelDrain और अन्य लिंक्स फ़िल्टर करें
            when {
                // Case 1: FSL Server लिंक (R2.dev)
                href.contains("r2.dev") -> {
                    links.add(createLink(href, text, "FSL Server", sanitizedUrl))
                }
                // Case 2: PixelDrain Direct लिंक
                href.contains("pixeldra.in/api/file") -> {
                    links.add(createLink(href, text, "PixelDrain", sanitizedUrl))
                }
                // Case 3: PixelDrain Embed लिंक (iframe)
                linkType == "iframe" && href.contains("pixeldra.in/u/") -> {
                    val fileId = href.substringAfter("/u/").substringBefore("?")
                    val directUrl = "https://pixeldra.in/api/file/$fileId?download"
                    links.add(createLink(directUrl, text, "PixelDrain", sanitizedUrl))
                }
                // Case 4: M3U8/MP4 स्ट्रीमिंग लिंक
                href.contains(".m3u8|.mp4".toRegex()) -> {
                    links.add(createLink(href, text, "Direct Stream", sanitizedUrl))
                }
            }
        }

        // 3. सभी लिंक्स यूजर को दिखाएं (क्वालिटी और स्रोत के साथ)
        links.distinctBy { it.url }
            .sortedByDescending { it.quality }
            .forEach { callback.invoke(it) }
    }

    private fun createLink(
        href: String,
        text: String,
        source: String,
        referer: String
    ): ExtractorLink {
        val quality = extractQuality(text)
        return ExtractorLink(
            source,
            "${source} (${quality}p)", // उदाहरण: "FSL Server (720p)"
            href,
            referer,
            quality,
            type = if (href.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        )
    }

    // क्वालिटी डिटेक्शन (सभी वेरिएंट्स को कवर करें)
    private fun extractQuality(text: String): Int {
        return when {
            Regex("""720p?|HD|HEVC|480p""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P720.value
            Regex("""1080p?|FHD|Blu-?Ray""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P1080.value
            Regex("""4K|UHD|2160p?|HDR|1440p""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
}