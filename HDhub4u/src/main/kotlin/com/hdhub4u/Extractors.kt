package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

/**
 * ═══════════════════════════════════════════════════════════════════════════════════
 * HDhub4u Extractors - DOWNLOAD ONLY (No Streaming)
 * ═══════════════════════════════════════════════════════════════════════════════════
 * 
 * PRIORITY ORDER:
 * 1. X264 1080p quality
 * 2. Smallest file size
 * 3. Fastest server (Instant > FSL > Pixeldrain)
 * 
 * ACTIVE EXTRACTORS (Direct Downloads):
 * - HubCloud: Main extractor → gamerxyt.com CDN links
 * - Hubdrive: Redirects to HubCloud
 * - Hblinks/4KHDHub: Download aggregator pages
 * - HUBCDN: hubcdn.fans instant downloads
 * - PixelDrainDev: pixeldrain.dev direct downloads
 * 
 * REMOVED EXTRACTORS (Streaming Only - Causes Buffering):
 * - Hubstream: hubstream.art (M3U8 only)
 * - HDStream4u: hdstream4u.com (M3U8 + reCAPTCHA)
 * ═══════════════════════════════════════════════════════════════════════════════════
 */

// ═══════════════════════════════════════════════════════════════════════════════════
// UTILITY FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════════════

fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (_: Exception) { "" }
}

// Parse file size to MB (e.g., "1.8GB" → 1843, "500MB" → 500)
fun parseSizeToMB(sizeStr: String): Double {
    val cleanSize = sizeStr.replace("[", "").replace("]", "").replace("⚡", "").trim()
    val regex = Regex("""([\d.]+)\s*(GB|MB)""", RegexOption.IGNORE_CASE)
    val match = regex.find(cleanSize) ?: return Double.MAX_VALUE
    val value = match.groupValues[1].toDoubleOrNull() ?: return Double.MAX_VALUE
    val unit = match.groupValues[2].uppercase()
    return when (unit) {
        "GB" -> value * 1024
        "MB" -> value
        else -> Double.MAX_VALUE
    }
}

// Server speed priority (higher = faster)
fun getServerPriority(serverName: String): Int = when {
    serverName.contains("Instant", true) -> 100
    serverName.contains("Direct", true) -> 90
    serverName.contains("FSLv2", true) -> 85
    serverName.contains("10Gbps", true) -> 88
    serverName.contains("FSL", true) -> 80
    serverName.contains("Download", true) -> 70
    serverName.contains("Pixel", true) -> 60
    serverName.contains("Buzz", true) -> 55
    else -> 50
}

// Calculate quality score: 1080p X264 > 1080p HEVC > 720p > 480p
// Also considers file size (smaller = better for same quality)
fun calculateQualityScore(quality: Int, sizeStr: String, serverName: String, codec: String = ""): Int {
    var score = when (quality) {
        1080 -> 1000
        2160 -> 900  // 4K lower priority (larger files)
        720 -> 700
        480 -> 500
        else -> 300
    }
    
    // X264 codec bonus (better compatibility)
    if (codec.contains("x264", true) || codec.contains("h264", true) || codec.contains("h.264", true)) {
        score += 100
    } else if (codec.contains("hevc", true) || codec.contains("x265", true) || codec.contains("h265", true)) {
        score += 10  // HEVC is smaller but less compatible
    }
    
    // Size bonus for 1080p (smaller = higher priority)
    if (quality == 1080) {
        val sizeMB = parseSizeToMB(sizeStr)
        score += when {
            sizeMB <= 800 -> 80   // Ultra compressed
            sizeMB <= 1200 -> 60  // Highly compressed
            sizeMB <= 1800 -> 40
            sizeMB <= 2500 -> 20
            else -> 0
        }
    }
    
    // Server speed bonus
    score += getServerPriority(serverName)
    
    return score
}

// Check if URL is a direct download (not streaming)
fun isDirectDownloadUrl(url: String): Boolean {
    val downloadIndicators = listOf(
        "gamerxyt.com", "pixeldrain", "r2.dev", "gdboka.buzz", 
        "fukggl.buzz", "carnewz.site", "fsl.gigabytes", "fsl-lover.buzz",
        "gigabytes.icu", "bloggingvector.shop", "acek-cdn.com",
        "/download", ".mkv", ".mp4", ".avi"
    )
    return downloadIndicators.any { url.contains(it, ignoreCase = true) }
}

// Check if URL should be blocked (streaming only)
fun shouldBlockUrl(url: String): Boolean {
    val blockedDomains = listOf(
        "hubstream.art", "hubstream.com",
        "hdstream4u.com", "hdstream4u.net"
    )
    val isBlocked = blockedDomains.any { url.contains(it, ignoreCase = true) }
    val isM3u8 = url.contains(".m3u8", true) || url.contains("/hls/", true)
    return isBlocked || isM3u8
}

// ═══════════════════════════════════════════════════════════════════════════════════
// EXTRACTOR CLASSES
// ═══════════════════════════════════════════════════════════════════════════════════

/**
 * Hblinks Extractor - Download Aggregator Page
 * 
 * Structure: hblinks.dad/archives/XXXXX
 * Contains links to: hubdrive, hubcloud, hubcdn.fans
 */
open class Hblinks : ExtractorApi() {
    override val name = "Hblinks"
    override val mainUrl = "https://hblinks.*"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "Hblinks"
        Log.d(tag, "Processing: $url")
        
        try {
            val doc = app.get(url, timeout = 30).document
            val links = doc.select("h3 a[href], h5 a[href], div.entry-content a[href]")
            
            Log.d(tag, "Found ${links.size} links")
            
            links.amap { element ->
                val href = element.absUrl("href").ifBlank { element.attr("href") }
                if (href.isBlank() || href.startsWith("#") || href.contains("t.me")) return@amap
                if (shouldBlockUrl(href)) {
                    Log.d(tag, "BLOCKED: $href")
                    return@amap
                }
                
                Log.d(tag, "Processing: $href")
                
                try {
                    when {
                        href.contains("hubdrive", true) -> 
                            Hubdrive().getUrl(href, name, subtitleCallback, callback)
                        href.contains("hubcloud", true) -> 
                            HubCloud().getUrl(href, name, subtitleCallback, callback)
                        href.contains("hubcdn.fans", true) -> 
                            HUBCDN().getUrl(href, name, subtitleCallback, callback)
                        href.contains("pixeldrain", true) -> 
                            loadExtractor(href, referer, subtitleCallback, callback)
                        href.startsWith("http") && isDirectDownloadUrl(href) ->
                            loadExtractor(href, referer, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
}

// 4khdhub uses same structure as hblinks
class FourKHDHub : Hblinks() {
    override var mainUrl = "https://4khdhub.*"
    override val name = "4KHDHub"
}

// Hubstreamdad extends Hblinks
class Hubstreamdad : Hblinks() {
    override var mainUrl = "https://hblinks.*"
}

/**
 * PixelDrain Dev - pixeldrain.dev direct downloads
 */
class PixelDrainDev : PixelDrain() {
    override var mainUrl = "https://pixeldrain.dev"
}

/**
 * Hubdrive Extractor - Redirects to HubCloud
 */
class Hubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.*"
    override val requiresReferer = false

    private val cfKiller by lazy { CloudflareKiller() }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "Hubdrive"
        Log.d(tag, "Processing: $url")
        
        try {
            val doc = app.get(url, timeout = 30000, interceptor = cfKiller).documentLarge
            
            // Find hubcloud link
            var href = doc.select(".btn.btn-primary.btn-user.btn-success1.m-1").attr("href")
            if (href.isBlank() || !href.contains("hubcloud", true)) {
                href = doc.selectFirst("a.btn[href*=hubcloud]")?.attr("href") ?: ""
            }
            if (href.isBlank() || !href.contains("hubcloud", true)) {
                href = doc.selectFirst("a[href*=hubcloud]")?.attr("href") ?: ""
            }
            
            Log.d(tag, "Found HubCloud: $href")
            
            if (href.contains("hubcloud", true)) {
                HubCloud().getUrl(href, "Hubdrive", subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
}

/**
 * HubCloud Extractor - Main Download Extractor
 * 
 * Flow: hubcloud.fyi/drive/XXX → gamerxyt.com/hubcloud.php → CDN download links
 * 
 * CDN Servers (Priority Order):
 * 1. Instant DL (fastest)
 * 2. FSL/FSLv2 (fast)
 * 3. Pixeldrain (reliable)
 * 4. BuzzServer (medium)
 */
class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.*"
    override val requiresReferer = false

    private val cfKiller by lazy { CloudflareKiller() }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HubCloud"
        
        // Block streaming URLs
        if (shouldBlockUrl(url)) {
            Log.d(tag, "BLOCKED streaming URL: $url")
            return
        }
        
        // Validate URL
        val isValidUrl = try { 
            URI(url).toURL()
            true 
        } catch (_: Exception) { false }
        if (!isValidUrl) return
        
        Log.d(tag, "Processing: $url")

        // Get hubcloud.php page URL
        val phpUrl = try {
            when {
                "hubcloud.php" in url || "gamerxyt.com" in url -> url
                "/drive/" in url -> {
                    val driveDoc = app.get(url, interceptor = cfKiller, timeout = 30).document
                    driveDoc.selectFirst("a.btn.btn-primary.h6")?.attr("href")
                        ?: driveDoc.selectFirst("a.btn[href*=gamerxyt.com/hubcloud.php]")?.attr("href")
                        ?: driveDoc.selectFirst("a.btn[href*=hubcloud.php]")?.attr("href")
                        ?: driveDoc.selectFirst("a#download")?.attr("href")
                        ?: driveDoc.select("a.btn").firstOrNull { 
                            it.attr("href").contains("gamerxyt", true) 
                        }?.attr("href")
                        ?: ""
                }
                else -> {
                    val rawHref = app.get(url, interceptor = cfKiller, timeout = 30)
                        .document.select("#download").attr("href")
                    if (rawHref.startsWith("http")) rawHref
                    else getBaseUrl(url).trimEnd('/') + "/" + rawHref.trimStart('/')
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to get PHP URL: ${e.message}")
            ""
        }

        if (phpUrl.isBlank()) {
            Log.w(tag, "No valid PHP URL for: $url")
            return
        }

        Log.d(tag, "PHP URL: $phpUrl")

        // Parse gamerxyt.com download page
        val document = app.get(phpUrl, interceptor = cfKiller, timeout = 30).document
        val size = document.selectFirst("i#size")?.text() ?: ""
        val header = document.selectFirst("div.card-header")?.text() ?: ""
        
        // Extract quality and codec from header
        val qualityMatch = Regex("""(\d{3,4})p""").find(header)
        val quality = qualityMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1080
        val codec = when {
            header.contains("x264", true) || header.contains("h264", true) -> "x264"
            header.contains("hevc", true) || header.contains("x265", true) -> "hevc"
            else -> ""
        }
        
        val labelExtras = buildString {
            if (header.isNotEmpty()) append("[${cleanTitle(header)}]")
            if (size.isNotEmpty()) append("[$size]")
        }

        Log.d(tag, "Quality: ${quality}p, Codec: $codec, Size: $size")

        // Process each download button
        document.select("div.card-body a.btn").amap { element ->
            val link = element.attr("href")
            val text = element.text()
            
            // Skip streaming/blocked URLs
            if (shouldBlockUrl(link)) {
                Log.d(tag, "SKIPPED streaming: $link")
                return@amap
            }

            val score = calculateQualityScore(quality, size, text, codec)
            
            try {
                when {
                    // Instant DL - Fastest
                    text.contains("Instant", true) || text.contains("🚀") -> {
                        callback(newExtractorLink(
                            "$referer [Instant DL]",
                            "$referer [Instant DL] $labelExtras",
                            link
                        ) { this.quality = score + 50 })
                    }

                    // FSL Server
                    link.contains("fsl.gigabytes", true) || 
                    link.contains("fsl-lover.buzz", true) ||
                    (link.contains("gigabytes.icu", true) && !link.contains("gdboka")) -> {
                        callback(newExtractorLink(
                            "$referer [FSL]",
                            "$referer [FSL] $labelExtras",
                            link
                        ) { this.quality = score + 15 })
                    }

                    // FSLv2 - R2/CDN
                    link.contains("r2.dev", true) ||
                    link.contains("gdboka.buzz", true) ||
                    link.contains("fukggl.buzz", true) ||
                    link.contains("carnewz.site", true) -> {
                        callback(newExtractorLink(
                            "$referer [FSLv2]",
                            "$referer [FSLv2] $labelExtras",
                            link
                        ) { this.quality = score + 20 })
                    }

                    // Download File button
                    text.contains("Download", true) -> {
                        callback(newExtractorLink(
                            "$referer",
                            "$referer $labelExtras",
                            link
                        ) { this.quality = score })
                    }

                    // BuzzServer
                    link.contains("bloggingvector", true) ||
                    text.contains("Buzz", true) -> {
                        try {
                            val buzzResp = app.get("$link/download", referer = link, allowRedirects = false)
                            val dlink = buzzResp.headers["hx-redirect"] ?: link
                            callback(newExtractorLink(
                                "$referer [BuzzServer]",
                                "$referer [BuzzServer] $labelExtras",
                                dlink
                            ) { this.quality = score })
                        } catch (_: Exception) { }
                    }

                    // PixelDrain
                    link.contains("pixeldrain", true) ||
                    link.contains("hubcdn.fans", true) -> {
                        val finalURL = when {
                            link.contains("pixeldrain.dev/u/") || link.contains("pixeldrain.com/u/") ->
                                "${getBaseUrl(link)}/api/file/${link.substringAfterLast("/")}?download"
                            link.contains("hubcdn.fans") -> {
                                try {
                                    val redirectResp = app.get(link, allowRedirects = false)
                                    val loc = redirectResp.headers["location"]
                                    if (!loc.isNullOrBlank() && loc.contains("pixeldrain")) {
                                        "${getBaseUrl(loc)}/api/file/${loc.substringAfterLast("/")}?download"
                                    } else link
                                } catch (_: Exception) { link }
                            }
                            else -> link
                        }
                        callback(newExtractorLink(
                            "Pixeldrain",
                            "Pixeldrain $labelExtras",
                            finalURL
                        ) { this.quality = score })
                    }

                    // 10Gbps Server
                    text.contains("10Gbps", true) -> {
                        var currentLink = link
                        repeat(3) {
                            val response = app.get(currentLink, allowRedirects = false)
                            val redirectUrl = response.headers["location"] ?: return@amap
                            if ("link=" in redirectUrl) {
                                callback(newExtractorLink(
                                    "10Gbps",
                                    "10Gbps $labelExtras",
                                    redirectUrl.substringAfter("link=")
                                ) { this.quality = score })
                                return@amap
                            }
                            currentLink = redirectUrl
                        }
                    }

                    // Other direct download links
                    link.startsWith("http") && isDirectDownloadUrl(link) -> {
                        callback(newExtractorLink(
                            "$referer [Direct]",
                            "$referer [Direct] $labelExtras",
                            link
                        ) { this.quality = score })
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error processing link: ${e.message}")
            }
        }
    }

    private fun cleanTitle(title: String): String {
        val parts = title.split(".", "-", "_")
        val qualityTags = listOf("WEBRip", "WEB-DL", "WEB", "BluRay", "HDRip", "DVDRip", "HDTV", "HD")
        val codecTags = listOf("x264", "x265", "H264", "HEVC", "AVC", "AAC", "DD5", "EAC3")
        
        val startIndex = parts.indexOfFirst { part ->
            qualityTags.any { part.contains(it, true) }
        }
        
        return if (startIndex != -1) {
            parts.subList(startIndex, minOf(startIndex + 4, parts.size)).joinToString(".")
        } else {
            parts.takeLast(3).joinToString(".")
        }
    }
}

/**
 * HUBCDN Extractor - hubcdn.fans instant downloads + gadgetsweb.xyz
 */
class HUBCDN : ExtractorApi() {
    override val name = "HUBCDN"
    override val mainUrl = "https://hubcdn.fans"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HUBCDN"
        Log.d(tag, "Processing: $url")

        try {
            when {
                // gadgetsweb.xyz/?id=BASE64 - Find hblinks URL
                url.contains("gadgetsweb.xyz") && url.contains("?id=") -> {
                    Log.d(tag, "Gadgetsweb mediator")
                    
                    var hblinksUrl: String? = null
                    
                    // Try base64 decode
                    val encodedId = url.substringAfter("?id=").substringBefore("&")
                    try {
                        val decoded = base64Decode(encodedId)
                        hblinksUrl = Regex("""https?://(?:hblinks|4khdhub)\.[a-z]+/archives/\d+""")
                            .find(decoded)?.value
                    } catch (_: Exception) { }
                    
                    // Try page scraping
                    if (hblinksUrl == null) {
                        try {
                            val doc = app.get(url, timeout = 30).document
                            doc.select("script").forEach { script ->
                                val match = Regex("""https?://(?:hblinks|4khdhub)\.[a-z]+/archives/\d+""")
                                    .find(script.data())
                                if (match != null) hblinksUrl = match.value
                            }
                            if (hblinksUrl == null) {
                                hblinksUrl = doc.selectFirst("a#verify_btn, a[href*=hblinks], a[href*=4khdhub]")
                                    ?.attr("href")
                            }
                        } catch (_: Exception) { }
                    }
                    
                    if (!hblinksUrl.isNullOrBlank()) {
                        Log.d(tag, "Found hblinks: $hblinksUrl")
                        Hblinks().getUrl(hblinksUrl, referer, subtitleCallback, callback)
                    }
                }
                
                // hubcdn.fans/file/XXX - Instant download
                url.contains("hubcdn.fans/file/") -> {
                    Log.d(tag, "Instant download")
                    val doc = app.get(url, timeout = 30).document
                    
                    val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()
                    val encodedUrl = Regex("""reurl\s*=\s*"([^"]+)"""")
                        .find(scriptText ?: "")?.groupValues?.get(1)?.substringAfter("?r=")
                    
                    val decodedUrl = encodedUrl?.let { base64Decode(it) }?.substringAfterLast("link=")
                    
                    val finalUrl = decodedUrl ?: url
                    callback(newExtractorLink(
                        "Instant DL",
                        "Instant DL [hubcdn.fans]",
                        finalUrl,
                        INFER_TYPE
                    ) { this.quality = Qualities.Unknown.value })
                }
                
                // Legacy hubcdn format
                url.contains("hubcdn") -> {
                    val doc = app.get(url).document
                    val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()
                    val encodedUrl = Regex("""reurl\s*=\s*"([^"]+)"""")
                        .find(scriptText ?: "")?.groupValues?.get(1)?.substringAfter("?r=")
                    
                    val decodedUrl = encodedUrl?.let { base64Decode(it) }?.substringAfterLast("link=")
                    
                    if (decodedUrl != null) {
                        callback(newExtractorLink(
                            this.name,
                            this.name,
                            decodedUrl,
                            INFER_TYPE
                        ) { this.quality = Qualities.Unknown.value })
                    } else {
                        // Try hubcloud fallback
                        val hubcloudLink = doc.select("a[href*=hubcloud]").attr("href")
                        if (hubcloudLink.isNotBlank()) {
                            HubCloud().getUrl(hubcloudLink, referer, subtitleCallback, callback)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
}
