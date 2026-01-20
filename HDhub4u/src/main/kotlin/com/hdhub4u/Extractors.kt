package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
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

// Extract file size from text like "[1.5GB]" or "[460MB]"
fun extractFileSize(text: String): String {
    return Regex("""\[(\d+(?:\.\d+)?(?:MB|GB))\]""", RegexOption.IGNORE_CASE)
        .find(text)?.groupValues?.getOrNull(1) ?: ""
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
// Pattern: Base64 -> ROT13 -> Base64 = Final URL
fun decodeGadgetsWebUrl(encodedId: String): String? {
    return try {
        val firstDecode = String(android.util.Base64.decode(encodedId, android.util.Base64.DEFAULT))
        val rot13Applied = firstDecode.rot13()
        val finalUrl = String(android.util.Base64.decode(rot13Applied, android.util.Base64.DEFAULT))
        if (finalUrl.startsWith("http")) finalUrl else null
    } catch (_: Exception) {
        null
    }
}

// ==================== UNIVERSAL LINK EXTRACTOR ====================
// Follows ALL redirect chains automatically - NO hardcoded domains
// Just uses allowRedirects = true to reach final download page

class UniversalExtractor : ExtractorApi() {
    override val name = "Universal"
    override val mainUrl = ""
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Step 1: Follow ALL redirects automatically to final page
            val response = app.get(url, allowRedirects = true, timeout = 30)
            val finalUrl = response.url
            val html = response.text
            val document = response.document
            
            // Extract quality and size from page
            val header = document.selectFirst("div.card-header, h1, .title")?.text().orEmpty()
            val sizeText = document.selectFirst("i#size, .size, .file-size")?.text().orEmpty()
            val quality = getIndexQuality(header)
            val sizeLabel = if (sizeText.isNotEmpty()) "[$sizeText]" else ""
            
            // Step 2: Find #download button if exists and follow it
            val downloadBtn = document.selectFirst("#download, a#download, a:contains(Download)")
            if (downloadBtn != null) {
                val downloadHref = downloadBtn.attr("href")
                if (downloadHref.isNotBlank() && downloadHref != "#") {
                    val downloadUrl = if (downloadHref.startsWith("http")) downloadHref
                    else getBaseUrl(finalUrl).trimEnd('/') + "/" + downloadHref.trimStart('/')
                    
                    // Follow the download link
                    extractFinalLinks(downloadUrl, quality, sizeLabel, callback)
                    return
                }
            }
            
            // Step 3: Extract links directly from current page
            extractFinalLinks(finalUrl, quality, sizeLabel, callback, html)
            
        } catch (e: Exception) {
            Log.e("Universal", "Error: ${e.message}")
        }
    }
    
    private suspend fun extractFinalLinks(
        url: String,
        quality: Int,
        sizeLabel: String,
        callback: (ExtractorLink) -> Unit,
        existingHtml: String? = null
    ) {
        try {
            val response = if (existingHtml != null) null else app.get(url, allowRedirects = true, timeout = 30)
            val html = existingHtml ?: response?.text ?: return
            val document = response?.document ?: app.get(url).document
            
            // Find all button links on the page
            document.select("a.btn, a[href]:has(button), div.card-body a[href]").forEach { element ->
                val link = element.attr("href")
                val text = element.text()
                
                if (link.isBlank() || link == "#" || link.contains("how-to")) return@forEach
                
                // Skip internal links
                if (link.contains("hdhub4u", ignoreCase = true)) return@forEach
                
                val serverName = when {
                    text.contains("FSL", ignoreCase = true) -> "FSL Server"
                    text.contains("10Gbps", ignoreCase = true) -> "10Gbps Server"
                    text.contains("Pixel", ignoreCase = true) -> "PixelDrain"
                    text.contains("Buzz", ignoreCase = true) -> "BuzzServer"
                    text.contains("Download", ignoreCase = true) -> "Direct"
                    else -> "Server"
                }
                
                // Special handling for BuzzServer
                if (text.contains("Buzz", ignoreCase = true)) {
                    try {
                        val buzzResp = app.get("$link/download", referer = link, allowRedirects = false)
                        val dlink = buzzResp.headers["hx-redirect"] ?: buzzResp.headers["Location"]
                        if (!dlink.isNullOrBlank()) {
                            callback(newExtractorLink("HDhub4u", "HDhub4u - $serverName $sizeLabel", dlink) { 
                                this.quality = quality 
                            })
                            return@forEach
                        }
                    } catch (_: Exception) { }
                }
                
                // For 10Gbps links, follow to get final Google server link
                if (text.contains("10Gbps", ignoreCase = true)) {
                    try {
                        val redirectResp = app.get(link, allowRedirects = true, timeout = 30)
                        val redirectHtml = redirectResp.text
                        
                        // Find Google server or CDN link
                        val directLink = findDirectVideoUrl(redirectHtml)
                        if (directLink != null) {
                            callback(newExtractorLink("HDhub4u", "HDhub4u - 10Gbps $sizeLabel", directLink) { 
                                this.quality = quality 
                            })
                            return@forEach
                        }
                        
                        // Find download button on redirect page
                        redirectResp.document.select("a:contains(Download), a[href*=download]").firstOrNull()?.let { a ->
                            val href = a.attr("href")
                            if (href.isNotBlank() && !href.contains("how-to")) {
                                callback(newExtractorLink("HDhub4u", "HDhub4u - 10Gbps $sizeLabel", href) { 
                                    this.quality = quality 
                                })
                            }
                        }
                    } catch (_: Exception) { }
                    return@forEach
                }
                
                // Add link directly for other servers
                callback(newExtractorLink("HDhub4u", "HDhub4u - $serverName $sizeLabel", link) { 
                    this.quality = quality 
                })
            }
            
            // Also search for direct video links in HTML
            findDirectVideoUrl(html)?.let { directUrl ->
                callback(newExtractorLink("HDhub4u", "HDhub4u - Direct $sizeLabel", directUrl) { 
                    this.quality = quality 
                })
            }
            
        } catch (_: Exception) { }
    }
    
    // Find direct video URL in HTML using regex - no domain hardcoding
    private fun findDirectVideoUrl(html: String): String? {
        // Priority 1: Google User Content (fastest)
        Regex("""https?://video-downloads\.googleusercontent\.com[^"'\s<>]+""").find(html)?.let {
            return it.value.replace("&amp;", "&")
        }
        
        // Priority 2: CDN download links with video extensions
        Regex("""https?://[^"'\s<>]+\.(mkv|mp4)\?[^"'\s<>]+""").find(html)?.let {
            return it.value.replace("&amp;", "&")
        }
        
        // Priority 3: Any CDN link pattern
        Regex("""https?://cdn[^"'\s<>]+\.(mkv|mp4)[^"'\s<>]*""").find(html)?.let {
            return it.value.replace("&amp;", "&")
        }
        
        return null
    }
}

// ==================== HUBDRIVE EXTRACTOR (Simplified) ====================

class HubDrive : ExtractorApi() {
    override val name = "HubDrive"
    override val mainUrl = ""  // No hardcoded URL
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Delegate to Universal Extractor
        UniversalExtractor().getUrl(url, referer, subtitleCallback, callback)
    }
}

// ==================== HUBCLOUD EXTRACTOR (Simplified) ====================

class HubCloud : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = ""  // No hardcoded URL
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Delegate to Universal Extractor
        UniversalExtractor().getUrl(url, referer, subtitleCallback, callback)
    }
}
