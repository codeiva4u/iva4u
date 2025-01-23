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

        // Case 1: Direct HubCloud link from input field
        doc.selectFirst("input#ilink")?.attr("value")?.let { directLink ->
            callback.invoke(
                ExtractorLink(
                    name,
                    "Hub-Cloud Direct",
                    directLink.replace("ink", "dad"),
                    "",
                    Qualities.Unknown.value
                )
            )
        }

        // Case 2: Process all download buttons
        doc.select("a.btn").apmap { button ->
            val href = button.attr("href")
            val text = button.text()
            val quality = extractQuality(text) // Extract quality from button text

            when {
                // FSL Server
                text.contains("FSL Server", ignoreCase = true) -> {
                    callback.invoke(
                        ExtractorLink(
                            "$name[FSL]",
                            "$name[FSL] - ${quality}p",
                            href,
                            "",
                            quality
                        )
                    )
                }

                // R2 Direct Link
                href.contains("r2.dev") -> {
                    callback.invoke(
                        ExtractorLink(
                            "$name[R2]",
                            "$name[R2] - ${quality}p",
                            href,
                            "",
                            quality
                        )
                    )
                }

                // 10Gbps Server (Redirect Handling)
                text.contains("10Gbps", ignoreCase = true) -> {
                    val redirectUrl = app.get(href, allowRedirects = false).headers["location"]
                    redirectUrl?.let { safeUrl ->
                        callback.invoke(
                            ExtractorLink(
                                "$name[10Gbps]",
                                "$name[10Gbps] - ${quality}p",
                                safeUrl.substringAfter("link="),
                                "",
                                quality
                            )
                        )
                    }
                }

                // PixelDrain Server
                href.contains("pixeldra.in") -> {
                    callback.invoke(
                        ExtractorLink(
                            "PixelDrain",
                            "PixelDrain - ${quality}p",
                            href,
                            "",
                            quality
                        )
                    )
                }

                // Default case for other links
                else -> loadExtractor(href, "", subtitleCallback, callback)
            }
        }
    }

    // Improved quality extraction logic
    private fun extractQuality(text: String): Int {
        return when {
            Regex("720p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P720.value
            Regex("1080p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P1080.value
            Regex("4K|2160p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
}