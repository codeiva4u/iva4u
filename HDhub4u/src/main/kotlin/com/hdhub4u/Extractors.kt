package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

// ==================== UTILITY FUNCTIONS ====================

fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (_: Exception) { "" }
}

fun getIndexQuality(str: String?): Int {
    return Regex("""(\d{3,4})[pP]""").find(str.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

// ==================== HUBDRIVE EXTRACTOR (Actual Hoster with redirect chain) ====================

class HubDrive : ExtractorApi() {
    override val name = "HubDrive"
    override val mainUrl = "https://hubdrive.space"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HubDrive"
        try {
            // Step 1: Follow redirects to get HubDrive page
            val response = app.get(url, allowRedirects = true)
            val html = response.text
            
            // Step 2: Find HubCloud link in page (hubcloud.xxx/drive/ID pattern)
            val hubcloudRegex = Regex("""https?://hubcloud\.[a-z]+/drive/([a-zA-Z0-9]+)""")
            val hubcloudMatch = hubcloudRegex.find(html)
            
            if (hubcloudMatch != null) {
                // Pass to HubCloud extractor to continue the chain
                HubCloud().getUrl(hubcloudMatch.value, url, subtitleCallback, callback)
                return
            }
            
            // Alternative: Find gamerxyt link directly
            val gamerxytRegex = Regex("""https?://gamerxyt\.com/hubcloud\.php[^"'\s]+""")
            gamerxytRegex.find(html)?.let {
                // Follow gamerxyt to get final links
                extractFinalLinks(it.value, callback)
            }
            
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
    
    private suspend fun extractFinalLinks(gamerxytUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            val response = app.get(gamerxytUrl, allowRedirects = true)
            val document = response.document
            val size = document.selectFirst("i#size")?.text().orEmpty()
            val header = document.selectFirst("div.card-header")?.text().orEmpty()
            val quality = getIndexQuality(header)
            val labelExtras = if (size.isNotEmpty()) "[$size]" else ""
            
            // Extract all download server links
            document.select("div.card-body h2 a.btn").forEach { element ->
                val link = element.attr("href")
                val text = element.text()
                
                when {
                    text.contains("FSLv2", ignoreCase = true) || link.contains("cdn.fsl-buckets") -> {
                        callback(
                            newExtractorLink(
                                "FSLv2 CDN",
                                "FSLv2 CDN $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }
                    text.contains("FSL Server", ignoreCase = true) || link.contains("fsl.gdboka") -> {
                        callback(
                            newExtractorLink(
                                "FSL Server",
                                "FSL Server $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }
                    text.contains("10Gbps", ignoreCase = true) || link.contains("pixel.hubcdn") -> {
                        // Follow pixel.hubcdn to get Google server link
                        extractPixelLink(link, quality, labelExtras, callback)
                    }
                    text.contains("pixeldra", ignoreCase = true) -> {
                        val baseUrl = getBaseUrl(link)
                        val finalURL = if (link.contains("download", true)) link
                        else "$baseUrl/api/file/${link.substringAfterLast("/")}"
                        callback(
                            newExtractorLink(
                                "PixelDrain",
                                "PixelDrain $labelExtras",
                                finalURL
                            ) { this.quality = quality }
                        )
                    }
                    text.contains("Download File", ignoreCase = true) -> {
                        callback(
                            newExtractorLink(
                                "HubDrive",
                                "HubDrive $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }
                }
            }
        } catch (_: Exception) { }
    }
    
    private suspend fun extractPixelLink(pixelUrl: String, quality: Int, labelExtras: String, callback: (ExtractorLink) -> Unit) {
        try {
            val response = app.get(pixelUrl, allowRedirects = true)
            val html = response.text
            
            // Find Google User Content link
            val googleRegex = Regex("""https?://video-downloads\.googleusercontent\.com[^"'\s]+""")
            googleRegex.find(html)?.let {
                callback(
                    newExtractorLink(
                        "Google Server",
                        "Google Server (10Gbps) $labelExtras",
                        it.value
                    ) { this.quality = quality }
                )
            }
        } catch (_: Exception) { }
    }
}

// ==================== HUBCLOUD EXTRACTOR (Actual Hoster with redirect chain) ====================

class HubCloud : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.foo"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HubCloud"
        try {
            val baseUrl = getBaseUrl(url)
            
            // Step 1: Get the download page
            val gamerxytUrl = if ("hubcloud.php" in url || "gamerxyt" in url) {
                url
            } else {
                // Find #download button link
                val rawHref = app.get(url, allowRedirects = true).document.select("#download").attr("href")
                if (rawHref.startsWith("http")) rawHref
                else baseUrl.trimEnd('/') + "/" + rawHref.trimStart('/')
            }
            
            if (gamerxytUrl.isBlank()) return
            
            // Step 2: Get gamerxyt page with all download options
            val response = app.get(gamerxytUrl, allowRedirects = true)
            val document = response.document
            val size = document.selectFirst("i#size")?.text().orEmpty()
            val header = document.selectFirst("div.card-header")?.text().orEmpty()
            val quality = getIndexQuality(header)
            val labelExtras = if (size.isNotEmpty()) "[$size]" else ""
            
            // Step 3: Extract all download server links
            document.select("div.card-body h2 a.btn").amap { element ->
                val link = element.attr("href")
                val text = element.text()
                
                when {
                    text.contains("FSLv2", ignoreCase = true) || link.contains("cdn.fsl-buckets") -> {
                        callback(
                            newExtractorLink(
                                "FSLv2 CDN",
                                "FSLv2 CDN $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }
                    text.contains("FSL Server", ignoreCase = true) || link.contains("fsl.gdboka") -> {
                        callback(
                            newExtractorLink(
                                "FSL Server",
                                "FSL Server $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }
                    text.contains("10Gbps", ignoreCase = true) || link.contains("pixel.hubcdn") -> {
                        // Follow redirect chain to get Google server link
                        extractPixelLink(link, quality, labelExtras, callback)
                    }
                    text.contains("pixeldra", ignoreCase = true) -> {
                        val pixelBase = getBaseUrl(link)
                        val finalURL = if (link.contains("download", true)) link
                        else "$pixelBase/api/file/${link.substringAfterLast("/")}"
                        callback(
                            newExtractorLink(
                                "PixelDrain",
                                "PixelDrain $labelExtras",
                                finalURL
                            ) { this.quality = quality }
                        )
                    }
                    text.contains("Download File", ignoreCase = true) -> {
                        callback(
                            newExtractorLink(
                                "HubCloud",
                                "HubCloud $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }
                    text.contains("BuzzServer", ignoreCase = true) -> {
                        val buzzResp = app.get("$link/download", referer = link, allowRedirects = false)
                        val dlink = buzzResp.headers["hx-redirect"].orEmpty()
                        if (dlink.isNotBlank()) {
                            callback(
                                newExtractorLink(
                                    "BuzzServer",
                                    "BuzzServer $labelExtras",
                                    dlink
                                ) { this.quality = quality }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
    
    private suspend fun extractPixelLink(pixelUrl: String, quality: Int, labelExtras: String, callback: (ExtractorLink) -> Unit) {
        try {
            // Follow redirect chain for 10Gbps server
            var currentLink = pixelUrl
            var redirectCount = 0
            
            while (redirectCount < 5) {
                val response = app.get(currentLink, allowRedirects = false)
                val redirectUrl = response.headers["location"]
                
                if (redirectUrl == null) {
                    // No more redirects, extract from page
                    val html = app.get(currentLink, allowRedirects = true).text
                    
                    // Find Google User Content link
                    val googleRegex = Regex("""https?://video-downloads\.googleusercontent\.com[^"'\s]+""")
                    googleRegex.find(html)?.let {
                        callback(
                            newExtractorLink(
                                "Google Server",
                                "Google Server (10Gbps) $labelExtras",
                                it.value
                            ) { this.quality = quality }
                        )
                    }
                    break
                }
                
                // Check if redirect has final link parameter
                if ("link=" in redirectUrl) {
                    val finalLink = redirectUrl.substringAfter("link=")
                    callback(
                        newExtractorLink(
                            "10Gbps Server",
                            "10Gbps Server $labelExtras",
                            finalLink
                        ) { this.quality = quality }
                    )
                    break
                }
                
                currentLink = redirectUrl
                redirectCount++
            }
        } catch (_: Exception) { }
    }
}
