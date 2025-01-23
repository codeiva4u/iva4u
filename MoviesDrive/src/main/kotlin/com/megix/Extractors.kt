package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

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
        val sanitizedUrl = url.replace("ink", "dad").replace("art", "dad")
        val doc = app.get(sanitizedUrl).document

        // Remove unnecessary elements
        doc.select(".loading, .ads-btns, .alert").remove()

        // Case 1: Direct Download Link
        val downloadButton = doc.selectFirst("a#download")
        val scriptTag = doc.selectFirst("script:containsData(window.location)")
        val urlRegex = Regex("""https://[^\s'"]+""")
        val directLink = scriptTag?.let { urlRegex.find(it.html())?.value }

        if (!directLink.isNullOrEmpty()) {
            callback.invoke(
                ExtractorLink(
                    name,
                    "Hub-Cloud Direct Download",
                    directLink,
                    "",
                    Qualities.Unknown.value
                )
            )
        }

        // Case 2: Telegram Link
        val telegramButton = doc.selectFirst("a#tgbtn")
        val telegramLink = telegramButton?.attr("href")

        if (!telegramLink.isNullOrEmpty()) {
            callback.invoke(
                ExtractorLink(
                    "Telegram",
                    "Download From Telegram",
                    telegramLink,
                    "",
                    Qualities.Unknown.value
                )
            )
        }

        // Case 3: Other Links
        doc.select("a.btn, a.download-btn").apmap { button ->
            val href = button.attr("href")
            val text = button.text()
            val quality = extractQuality(text)

            callback.invoke(
                ExtractorLink(
                    name,
                    "$name - ${quality}p",
                    href,
                    "",
                    quality
                )
            )
        }
    }

    private fun extractQuality(text: String): Int {
        return when {
            Regex("720p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P720.value
            Regex("1080p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P1080.value
            Regex("4K|2160p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
}