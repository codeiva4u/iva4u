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

// GadgetsWeb URL decoder: Base64 -> ROT13 -> Base64 = Final URL
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

// ==================== HUBDRIVE EXTRACTOR ====================
// Flow: hubdrive.space -> find [HubCloud Server] button -> follow to HubCloud

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
        try {
            // Step 1: Get HubDrive page
            val response = app.get(url, allowRedirects = true)
            val document = response.document
            
            // Step 2: Find HubCloud/download buttons
            document.select("a.btn").amap { button ->
                val href = button.attr("href")
                val text = button.text()
                
                if (href.isBlank()) return@amap
                
                // Route to HubCloud extractor
                if (text.contains("HubCloud", ignoreCase = true) ||
                    href.contains("hubcloud", ignoreCase = true)) {
                    HubCloud().getUrl(href, url, subtitleCallback, callback)
                }
            }
            
        } catch (e: Exception) {
            Log.e("HubDrive", "Error: ${e.message}")
        }
    }
}

// ==================== HUBCLOUD EXTRACTOR ====================
// Flow: hubcloud.foo/drive/... -> #download button -> gamerxyt.com -> Final servers

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
        try {
            // Step 1: Get HubCloud page (follow any redirects)
            val response = app.get(url, allowRedirects = true, timeout = 30)
            val document = response.document
            
            // Get file info
            val size = document.selectFirst("i#size, #size")?.text()?.trim() ?: ""
            val header = document.selectFirst("div.card-header, h1")?.text()?.trim() ?: ""
            val quality = getIndexQuality(header)
            val sizeLabel = if (size.isNotEmpty()) "[$size]" else ""
            
            // Step 2: Find #download button
            val downloadBtn = document.selectFirst("#download, a#download")
            val downloadHref = downloadBtn?.attr("href") ?: ""
            
            if (downloadHref.isNotBlank() && downloadHref.startsWith("http")) {
                // Step 3: Follow to final page (gamerxyt.com/hubcloud.php or similar)
                extractFinalServers(downloadHref, quality, sizeLabel, callback)
            } else {
                // Try to find servers on current page
                extractServersFromPage(document, quality, sizeLabel, callback)
            }
            
        } catch (e: Exception) {
            Log.e("HubCloud", "Error: ${e.message}")
        }
    }
    
    private suspend fun extractFinalServers(
        url: String,
        quality: Int,
        sizeLabel: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Follow redirects to final server page
            val response = app.get(url, allowRedirects = true, timeout = 30)
            extractServersFromPage(response.document, quality, sizeLabel, callback)
            
            // Also search HTML for direct video URLs
            findDirectVideoLinks(response.text, quality, sizeLabel, callback)
            
        } catch (_: Exception) { }
    }
    
    private suspend fun extractServersFromPage(
        document: org.jsoup.nodes.Document,
        quality: Int,
        sizeLabel: String,
        callback: (ExtractorLink) -> Unit
    ) {
        // Find all download buttons with server names
        document.select("a.btn[href^=http]").amap { button ->
            val href = button.attr("href")
            val text = button.text()
            
            if (href.isBlank() || href.contains("google.com/search")) return@amap
            
            when {
                text.contains("FSLv2", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$name[FSLv2]",
                            "$name[FSLv2] $sizeLabel",
                            href
                        ) {
                            this.quality = quality
                        }
                    )
                }
                text.contains("FSL", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$name[FSL]",
                            "$name[FSL] $sizeLabel",
                            href
                        ) {
                            this.quality = quality
                        }
                    )
                }
                text.contains("10Gbps", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$name[10Gbps]",
                            "$name[10Gbps] $sizeLabel",
                            href
                        ) {
                            this.quality = quality
                        }
                    )
                }
                text.contains("Pixel", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$name[PixelDrain]",
                            "$name[PixelDrain] $sizeLabel",
                            href
                        ) {
                            this.quality = quality
                        }
                    )
                }
                text.contains("Buzz", ignoreCase = true) -> {
                    try {
                        val dlink = app.get("$href/download", referer = href, allowRedirects = false)
                            .headers["hx-redirect"] ?: ""
                        if (dlink.isNotEmpty()) {
                            callback.invoke(
                                newExtractorLink(
                                    "$name[BuzzServer]",
                                    "$name[BuzzServer] $sizeLabel",
                                    getBaseUrl(href) + dlink
                                ) {
                                    this.quality = quality
                                }
                            )
                        }
                    } catch (_: Exception) { }
                }
                text.contains("Download", ignoreCase = true) && !text.contains("IDM") -> {
                    callback.invoke(
                        newExtractorLink(
                            "$name[Direct]",
                            "$name[Direct] $sizeLabel",
                            href
                        ) {
                            this.quality = quality
                        }
                    )
                }
            }
        }
    }
    
    private suspend fun findDirectVideoLinks(
        html: String,
        quality: Int,
        sizeLabel: String,
        callback: (ExtractorLink) -> Unit
    ) {
        // Pattern 1: CDN links with video extension and token
        Regex("""https?://cdn\.[^"'\s<>]+\.(mkv|mp4)\?[^"'\s<>]+""").findAll(html).forEach { match ->
            val url = match.value.replace("&amp;", "&")
            callback.invoke(
                newExtractorLink(
                    "$name[CDN]",
                    "$name[CDN] $sizeLabel",
                    url
                ) {
                    this.quality = quality
                }
            )
        }
        
        // Pattern 2: FSL server links
        Regex("""https?://fsl\.[^"'\s<>]+\?token=\d+""").findAll(html).forEach { match ->
            val url = match.value.replace("&amp;", "&")
            callback.invoke(
                newExtractorLink(
                    "$name[FSL]",
                    "$name[FSL] $sizeLabel",
                    url
                ) {
                    this.quality = quality
                }
            )
        }
        
        // Pattern 3: Google server links
        Regex("""https?://video-downloads\.googleusercontent\.com[^"'\s<>]+""").findAll(html).forEach { match ->
            val url = match.value.replace("&amp;", "&")
            callback.invoke(
                newExtractorLink(
                    "$name[Google]",
                    "$name[Google Server] $sizeLabel",
                    url
                ) {
                    this.quality = quality
                }
            )
        }
        
        // Pattern 4: GPDL/HubCDN links
        Regex("""https?://gpdl\.[^"'\s<>]+\?id=[^"'\s<>]+""").findAll(html).forEach { match ->
            val url = match.value.replace("&amp;", "&")
            callback.invoke(
                newExtractorLink(
                    "$name[10Gbps]",
                    "$name[10Gbps] $sizeLabel",
                    url
                ) {
                    this.quality = quality
                }
            )
        }
        
        // Pattern 5: PixelDrain links
        Regex("""https?://pixeldrain\.[^"'\s<>]+/u/[^"'\s<>]+""").findAll(html).forEach { match ->
            val url = match.value.replace("&amp;", "&")
            callback.invoke(
                newExtractorLink(
                    "$name[PixelDrain]",
                    "$name[PixelDrain] $sizeLabel",
                    url
                ) {
                    this.quality = quality
                }
            )
        }
    }
}
