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

// ROT13 decoder for GadgetsWeb bypass
fun String.rot13(): String {
    return this.map { char ->
        when (char) {
            in 'A'..'Z' -> ((char - 'A' + 13) % 26 + 'A'.code).toChar()
            in 'a'..'z' -> ((char - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> char
        }
    }.joinToString("")
}

// GadgetsWeb URL decoder - decodes the encrypted ID parameter
// Format: Base64 encoded string that when decoded and ROT13'd gives another Base64 of final URL
fun decodeGadgetsWebUrl(encodedId: String): String? {
    return try {
        // Step 1: Base64 decode
        val firstDecode = String(android.util.Base64.decode(encodedId, android.util.Base64.DEFAULT))
        // Step 2: Apply ROT13
        val rot13Applied = firstDecode.rot13()
        // Step 3: Base64 decode again to get final URL
        val finalUrl = String(android.util.Base64.decode(rot13Applied, android.util.Base64.DEFAULT))
        if (finalUrl.startsWith("http")) finalUrl else null
    } catch (_: Exception) {
        null
    }
}

// ==================== HUBDRIVE EXTRACTOR ====================
// Follows any redirect chain automatically using allowRedirects

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
            // Step 1: Follow ALL redirects automatically to reach final page
            val response = app.get(url, allowRedirects = true)
            val document = response.document
            
            // Step 2: Find #download button and follow it
            val downloadHref = document.select("#download").attr("href")
            if (downloadHref.isNotBlank()) {
                val downloadUrl = if (downloadHref.startsWith("http")) downloadHref
                else getBaseUrl(response.url).trimEnd('/') + "/" + downloadHref.trimStart('/')
                
                // Follow to final download page (with all redirects)
                extractFinalLinks(downloadUrl, callback)
            } else {
                // Try to extract links from current page
                extractFinalLinks(response.url, callback, response.text)
            }
            
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
    
    private suspend fun extractFinalLinks(url: String, callback: (ExtractorLink) -> Unit, existingHtml: String? = null) {
        try {
            val html = existingHtml ?: app.get(url, allowRedirects = true).text
            val document = app.get(url, allowRedirects = true).document
            val size = document.selectFirst("i#size")?.text().orEmpty()
            val header = document.selectFirst("div.card-header")?.text().orEmpty()
            val quality = getIndexQuality(header)
            val labelExtras = if (size.isNotEmpty()) "[$size]" else ""
            
            // Extract all download server links from buttons
            document.select("div.card-body h2 a.btn, a.btn").amap { element ->
                val link = element.attr("href")
                val text = element.text()
                
                if (link.isBlank()) return@amap
                
                // Check link content for server type (not domain names)
                when {
                    text.contains("FSLv2", ignoreCase = true) -> {
                        callback(newExtractorLink("HubDrive", "HubDrive - FSLv2 $labelExtras", link) { this.quality = quality })
                    }
                    text.contains("FSL Server", ignoreCase = true) -> {
                        callback(newExtractorLink("HubDrive", "HubDrive - FSL Server $labelExtras", link) { this.quality = quality })
                    }
                    text.contains("10Gbps", ignoreCase = true) -> {
                        // Follow redirect chain to get final link
                        followRedirectChain(link, quality, labelExtras, callback)
                    }
                    text.contains("pixeldra", ignoreCase = true) || text.contains("Pixel", ignoreCase = true) -> {
                        callback(newExtractorLink("HubDrive", "HubDrive - PixelDrain $labelExtras", link) { this.quality = quality })
                    }
                    text.contains("Download", ignoreCase = true) -> {
                        callback(newExtractorLink("HubDrive", "HubDrive - Direct $labelExtras", link) { this.quality = quality })
                    }
                    text.contains("BuzzServer", ignoreCase = true) -> {
                        val buzzResp = app.get("$link/download", referer = link, allowRedirects = false)
                        val dlink = buzzResp.headers["hx-redirect"].orEmpty()
                        if (dlink.isNotBlank()) {
                            callback(newExtractorLink("HubDrive", "HubDrive - BuzzServer $labelExtras", dlink) { this.quality = quality })
                        }
                    }
                }
            }
            
            // Also find direct video links in page HTML
            findDirectVideoLinks(html, callback)
            
        } catch (_: Exception) { }
    }
    
    private suspend fun followRedirectChain(startUrl: String, quality: Int, labelExtras: String, callback: (ExtractorLink) -> Unit) {
        try {
            // Follow redirect chain until we get final page
            val response = app.get(startUrl, allowRedirects = true)
            val html = response.text
            
            // Find Google User Content link (final playable)
            Regex("""https?://video-downloads\.googleusercontent\.com[^"'\s]+""").find(html)?.let {
                callback(newExtractorLink("HubDrive", "HubDrive - Google Server (10Gbps) $labelExtras", it.value) { this.quality = quality })
            }
            
            // Find any download link in anchor tags
            response.document.select("a[href*=download], a:contains(Download)").amap { a ->
                val href = a.attr("href")
                if (href.isNotBlank() && !href.contains("how-to")) {
                    callback(newExtractorLink("HubDrive", "HubDrive - 10Gbps $labelExtras", href) { this.quality = quality })
                }
            }
        } catch (_: Exception) { }
    }
    
    private suspend fun findDirectVideoLinks(html: String, callback: (ExtractorLink) -> Unit) {
        // Find FSL CDN links
        for (match in Regex("""https?://cdn\.[^"'\s]+\.work[^"'\s]+\.(mkv|mp4)[^"'\s]*""").findAll(html)) {
            callback(newExtractorLink("HubDrive", "HubDrive - FSL CDN", match.value) { this.quality = Qualities.Unknown.value })
        }
        
        // Find Google server links
        for (match in Regex("""https?://video-downloads\.googleusercontent\.com[^"'\s]+""").findAll(html)) {
            callback(newExtractorLink("HubDrive", "HubDrive - Google Server", match.value) { this.quality = Qualities.Unknown.value })
        }
        
        // Find direct video file URLs
        for (match in Regex("""https?://[^"'\s]+\.(mkv|mp4)\?[^"'\s]+""").findAll(html)) {
            callback(newExtractorLink("HubDrive", "HubDrive - Direct", match.value) { this.quality = Qualities.Unknown.value })
        }
    }
}

// ==================== HUBCLOUD EXTRACTOR ====================
// Follows any redirect chain automatically using allowRedirects

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
            // Step 1: Follow ALL redirects automatically
            val response = app.get(url, allowRedirects = true)
            val document = response.document
            
            // Step 2: Find #download button if exists
            val downloadHref = document.select("#download").attr("href")
            val finalUrl = if (downloadHref.isNotBlank()) {
                if (downloadHref.startsWith("http")) downloadHref
                else getBaseUrl(response.url).trimEnd('/') + "/" + downloadHref.trimStart('/')
            } else {
                response.url
            }
            
            // Step 3: Get final page with all download options
            val finalResponse = app.get(finalUrl, allowRedirects = true)
            val finalDoc = finalResponse.document
            val size = finalDoc.selectFirst("i#size")?.text().orEmpty()
            val header = finalDoc.selectFirst("div.card-header")?.text().orEmpty()
            val quality = getIndexQuality(header)
            val labelExtras = if (size.isNotEmpty()) "[$size]" else ""
            
            // Step 4: Extract all download server links (by button text, not domain)
            finalDoc.select("div.card-body h2 a.btn, a.btn").amap { element ->
                val link = element.attr("href")
                val text = element.text()
                
                if (link.isBlank()) return@amap
                
                when {
                    text.contains("FSLv2", ignoreCase = true) -> {
                        callback(newExtractorLink("HubCloud", "HubCloud - FSLv2 $labelExtras", link) { this.quality = quality })
                    }
                    text.contains("FSL Server", ignoreCase = true) -> {
                        callback(newExtractorLink("HubCloud", "HubCloud - FSL Server $labelExtras", link) { this.quality = quality })
                    }
                    text.contains("10Gbps", ignoreCase = true) -> {
                        followRedirectChain(link, quality, labelExtras, callback)
                    }
                    text.contains("pixeldra", ignoreCase = true) || text.contains("Pixel", ignoreCase = true) -> {
                        callback(newExtractorLink("HubCloud", "HubCloud - PixelDrain $labelExtras", link) { this.quality = quality })
                    }
                    text.contains("Download File", ignoreCase = true) -> {
                        callback(newExtractorLink("HubCloud", "HubCloud - Direct $labelExtras", link) { this.quality = quality })
                    }
                    text.contains("BuzzServer", ignoreCase = true) -> {
                        val buzzResp = app.get("$link/download", referer = link, allowRedirects = false)
                        val dlink = buzzResp.headers["hx-redirect"].orEmpty()
                        if (dlink.isNotBlank()) {
                            callback(newExtractorLink("HubCloud", "HubCloud - BuzzServer $labelExtras", dlink) { this.quality = quality })
                        }
                    }
                }
            }
            
            // Also find direct video links in HTML
            findDirectVideoLinks(finalResponse.text, callback)
            
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
    
    private suspend fun followRedirectChain(startUrl: String, quality: Int, labelExtras: String, callback: (ExtractorLink) -> Unit) {
        try {
            val response = app.get(startUrl, allowRedirects = true)
            val html = response.text
            
            // Find Google server link
            Regex("""https?://video-downloads\.googleusercontent\.com[^"'\s]+""").find(html)?.let {
                callback(newExtractorLink("HubCloud", "HubCloud - Google Server (10Gbps) $labelExtras", it.value) { this.quality = quality })
            }
            
            // Find download links
            for (a in response.document.select("a[href*=download], a:contains(Download)")) {
                val href = a.attr("href")
                if (href.isNotBlank() && !href.contains("how-to")) {
                    callback(newExtractorLink("HubCloud", "HubCloud - 10Gbps $labelExtras", href) { this.quality = quality })
                }
            }
        } catch (_: Exception) { }
    }
    
    private suspend fun findDirectVideoLinks(html: String, callback: (ExtractorLink) -> Unit) {
        // FSL CDN
        for (match in Regex("""https?://cdn\.[^"'\s]+\.work[^"'\s]+\.(mkv|mp4)[^"'\s]*""").findAll(html)) {
            callback(newExtractorLink("HubCloud", "HubCloud - FSL CDN", match.value) { this.quality = Qualities.Unknown.value })
        }
        
        // Google server
        for (match in Regex("""https?://video-downloads\.googleusercontent\.com[^"'\s]+""").findAll(html)) {
            callback(newExtractorLink("HubCloud", "HubCloud - Google Server", match.value) { this.quality = Qualities.Unknown.value })
        }
        
        // Direct video files
        for (match in Regex("""https?://[^"'\s]+\.(mkv|mp4)\?[^"'\s]+""").findAll(html)) {
            callback(newExtractorLink("HubCloud", "HubCloud - Direct", match.value) { this.quality = Qualities.Unknown.value })
        }
    }
}
