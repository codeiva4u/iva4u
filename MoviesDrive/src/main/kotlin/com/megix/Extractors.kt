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
        val sanitizedUrl = url.replace("ink|art".toRegex(), "dad")
        val html = app.get(sanitizedUrl).text // पूरा HTML टेक्स्ट प्राप्त करें

        // 1. सभी लिंक्स रेगेक्स से निकालें
        val linkRegex = Regex("""<a\s+[^>]*href=["'](https?://[^"']+)["'][^>]*>([\s\S]*?)</a>""")
        val matches = linkRegex.findAll(html)

        val links = mutableListOf<ExtractorLink>()
        matches.forEach { match ->
            val href = match.groupValues[1]
            val text = match.groupValues[2]

            // 2. FSL, PixelDrain और अन्य लिंक्स फ़िल्टर करें
            when {
                // FSL Server लिंक
                href.contains("pub-db4aad121b26409eb63bf48ceb693403.r2.dev") -> {
                    links.add(createLink(href, text, "FSL Server", sanitizedUrl))
                }
                // PixelDrain Direct लिंक
                href.contains("pixeldra.in/api/file") -> {
                    links.add(createLink(href, text, "PixelDrain", sanitizedUrl))
                }
                // PixelDrain Embed लिंक
                href.contains("pixeldra.in/u/") -> {
                    val fileId = href.substringAfter("/u/").substringBefore("?")
                    val directUrl = "https://pixeldra.in/api/file/$fileId?download"
                    links.add(createLink(directUrl, text, "PixelDrain", sanitizedUrl))
                }
            }
        }

        // 3. यूजर को सभी क्वालिटीज़ दिखाएं
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
            "$source ${quality}p",
            href,
            referer,
            quality
        )
    }

    // क्वालिटी डिटेक्शन (अपडेटेड रेगेक्स)
    private fun extractQuality(text: String): Int {
        return when {
            Regex("""720p?|HD|HEVC""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P720.value
            Regex("""1080p?|FHD|Blu-?Ray""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P1080.value
            Regex("""4K|UHD|2160p?|HDR""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
}