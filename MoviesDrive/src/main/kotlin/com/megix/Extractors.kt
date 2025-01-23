package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import okhttp3.Interceptor
import okhttp3.Response

// PixelDrain Extractor
class PixelDrain : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldra.in"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
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

// HubCloud Interceptor for handling redirects and potential blocks
object CloudstreamInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Handle potential redirects or blocks here if needed in the future
        // For example, if the site starts implementing IP blocking or redirects

        return response
    }
}

// HubCloud Extractor (Open Class for inheritance)
open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.dad" // Default to .dad for consistency
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Sanitize URL: Replace .ink or .art with .dad for consistent access
        val sanitizedUrl = url.replace(Regex("ink|art"), "dad")

        // Fetch the page with the custom interceptor
        val doc = app.get(sanitizedUrl, interceptor = CloudstreamInterceptor).document

        // 1. Remove Obstructive Elements
        doc.select(
            """
            .adblock-detector, 
            .popup, 
            .ads-btns, 
            .alert, 
            script:containsData(window.location = atob),
            script:containsData(window.location=atob),
            script[src*='cleverwebserver'],
            iframe[src*='pixeldra.in']
        """.trimIndent()
        ).remove()

        // 2. Extract Direct Download Links
        doc.select(
            """
            a[href*='r2.dev']:not([href*=' ']), 
            a[href*='pixeldra.in/api/file'],
            a[href*='workers.dev'],
            a.btn-success1, 
            a.btn-zip
        """.trimIndent()
        ).apmap { button ->
            var href = button.attr("abs:href").trim() // Get absolute URL
            href = href.replace(" ", "%20").replace("'", "%27")

            // MIME-TYPE and Quality Detection
            val mimeType = detectMimeType(href)
            val quality = detectQuality(button.text())

            callback.invoke(
                ExtractorLink(
                    name,
                    "$name - ${quality}p (${mimeType.split('/').lastOrNull()?.uppercase() ?: "UNK"})",
                    href,
                    sanitizedUrl,
                    quality,
                    type = if (mimeType == "application/x-mpegURL") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
        }

        // 3. Extract Script-Based Links
        doc.select("script:not([src])").forEach { script ->
            val scriptContent = script.html()

            // Unpack eval(function(p,a,c,k,e,d)) scripts
            if (scriptContent.contains("eval(function(p,a,c,k,e,d)")) {
                val unpacked = getAndUnpack(scriptContent)

                Regex("""(https?://[^\s'"]*\.(?:mp4|mkv|m3u8|avi|mov))\b""", RegexOption.IGNORE_CASE).findAll(unpacked).forEach { match ->
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "$name (Auto-Detected)",
                            match.value,
                            sanitizedUrl,
                            Qualities.Unknown.value
                        )
                    )
                }
            }

            // Find direct links within scripts with improved regex
            Regex("""(https?://[^\s'"]*\.(?:mp4|mkv|m3u8|avi|mov))\b""", RegexOption.IGNORE_CASE).findAll(scriptContent).forEach { match ->
                callback.invoke(
                    ExtractorLink(
                        name,
                        "$name (Auto-Detected)",
                        match.value,
                        sanitizedUrl,
                        Qualities.Unknown.value
                    )
                )
            }
        }
    }

    // Quality Detection (Improved)
    internal fun detectQuality(text: String): Int {
        return when {
            text.contains(Regex("720|HD|HEVC|h264", RegexOption.IGNORE_CASE)) -> Qualities.P720.value
            text.contains(Regex("1080|FHD|BluRay", RegexOption.IGNORE_CASE)) -> Qualities.P1080.value
            text.contains(Regex("4K|UHD|2160|HDR|Dolby", RegexOption.IGNORE_CASE)) -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }

    // MIME-TYPE Detection (Improved)
    internal fun detectMimeType(url: String): String {
        return when {
            url.contains(".mkv") -> "video/x-matroska"
            url.contains(".mp4") -> "video/mp4"
            url.contains(".m3u8") -> "application/x-mpegURL"
            url.contains(".avi") -> "video/x-msvideo"
            url.contains(".mov") -> "video/quicktime"
            else -> "video/*" // Default to a generic video type
        }
    }
}

// HubCloud.ink Extractor (Inherits from HubCloud)
class HubCloudInk : HubCloud() {
    override val mainUrl: String = "https://hubcloud.ink"
}

// HubCloud.art Extractor (Inherits from HubCloud)
class HubCloudArt : HubCloud() {
    override val mainUrl: String = "https://hubcloud.art"
}