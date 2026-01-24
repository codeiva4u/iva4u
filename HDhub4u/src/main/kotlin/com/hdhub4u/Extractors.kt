package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * STREAMING URLs BLOCKER - Key Configuration
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * IMPORTANT: Streaming URLs (M3U8/HLS) से buffering issues होते हैं।
 * इसलिए हम ONLY direct download links को prefer करते हैं।
 * 
 * BLOCKED DOMAINS (Streaming Only - No Downloads):
 * - hubstream.art (only provides M3U8 streams, high buffering)
 * - hdstream4u.com (streaming player, download requires reCAPTCHA bypass)
 * 
 * PREFERRED DOMAINS (Direct Downloads):
 * - hubcloud.* (gamerxyt.com final links)
 * - hubdrive.* (redirects to hubcloud)
 * - pixeldrain.dev/com (direct downloads)
 * - hubcdn.fans (instant downloads)
 * - hblinks.* (download aggregator)
 * - 4khdhub.* (download aggregator)
 */

// List of streaming-only domains to BLOCK/SKIP
private val STREAMING_ONLY_DOMAINS = listOf(
    "hubstream.art",
    "hubstream.com", 
    "hdstream4u.com",
    "hdstream4u.net"
)

// Check if URL is from a streaming-only domain (should be blocked)
fun isStreamingOnlyUrl(url: String): Boolean {
    return STREAMING_ONLY_DOMAINS.any { domain -> 
        url.contains(domain, ignoreCase = true) 
    }
}

// Check if URL is a streaming format (M3U8/HLS)
fun isStreamingFormat(url: String): Boolean {
    return url.contains(".m3u8", ignoreCase = true) ||
           url.contains(".txt", ignoreCase = true) && url.contains("master", ignoreCase = true) ||
           url.contains("/hls/", ignoreCase = true)
}

// Check if URL is a direct download link (preferred)
fun isDirectDownloadUrl(url: String): Boolean {
    val downloadDomains = listOf(
        "gamerxyt.com",
        "pixeldrain.dev", "pixeldrain.com",
        "r2.dev", "gdboka.buzz", "fukggl.buzz", "carnewz.site",
        "fsl.gigabytes", "fsl-lover.buzz", "gigabytes.icu",
        "bloggingvector.shop",
        "acek-cdn.com"
    )
    return downloadDomains.any { domain -> url.contains(domain, ignoreCase = true) } ||
           url.contains("/download", ignoreCase = true) ||
           url.endsWith(".mkv", ignoreCase = true) ||
           url.endsWith(".mp4", ignoreCase = true) ||
           url.endsWith(".avi", ignoreCase = true)
}

// Helper function to load source with extractor name
suspend fun loadSourceNameExtractor(
    name: String,
    url: String,
    referer: String,
    quality: Int,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    // BLOCK streaming-only URLs
    if (isStreamingOnlyUrl(url)) {
        Log.d("Extractor", "BLOCKED streaming-only URL: $url")
        return
    }
    loadExtractor(url, referer, subtitleCallback, callback)
}

// Utility functions for dynamic URL management
fun getBaseUrl(url: String): String {
    return URI(url).let {
        "${it.scheme}://${it.host}"
    }
}

// Cached URLs to avoid fetching urls.json on every call
private var cachedUrlsJson: org.json.JSONObject? = null

suspend fun getLatestUrl(url: String, source: String): String {
    // Use cached JSON if available (fetch only once per session)
    if (cachedUrlsJson == null) {
        try {
            cachedUrlsJson = org.json.JSONObject(
                app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json").text
            )
        } catch (e: Exception) {
            return getBaseUrl(url)
        }
    }

    val link = cachedUrlsJson?.optString(source)
    if (link.isNullOrEmpty()) {
        return getBaseUrl(url)
    }
    return link
}

// Parse file size string to MB (e.g., "1.8GB" -> 1843, "500MB" -> 500)
fun parseSizeToMB(sizeStr: String): Double {
    val cleanSize = sizeStr.replace("[", "").replace("]", "").replace("⚡", "").trim()
    val regex = Regex("""([\d.]+)\s*(GB|MB|gb|mb)""", RegexOption.IGNORE_CASE)
    val match = regex.find(cleanSize) ?: return Double.MAX_VALUE
    val value = match.groupValues[1].toDoubleOrNull() ?: return Double.MAX_VALUE
    val unit = match.groupValues[2].uppercase()
    return when (unit) {
        "GB" -> value * 1024
        "MB" -> value
        else -> Double.MAX_VALUE
    }
}

// Server speed priority (higher = faster/preferred)
fun getServerPriority(serverName: String): Int {
    return when {
        serverName.contains("Instant", true) -> 100  // Instant DL = fastest
        serverName.contains("Direct", true) -> 90
        serverName.contains("FSLv2", true) -> 85
        serverName.contains("FSL", true) -> 80
        serverName.contains("10Gbps", true) -> 88
        serverName.contains("Download File", true) -> 70
        serverName.contains("Pixel", true) -> 60
        serverName.contains("Buzz", true) -> 55
        else -> 50
    }
}

// Adjust quality to prioritize 1080p with smallest size and fastest server
fun getAdjustedQuality(quality: Int, sizeStr: String, serverName: String = ""): Int {
    var adjustedQuality = quality

    // 1080p gets size bonus (smaller = higher bonus)
    if (quality == 1080) {
        val sizeMB = parseSizeToMB(sizeStr)
        val sizeBonus = when {
            sizeMB <= 1000 -> 50   // HEVC compressed
            sizeMB <= 1500 -> 40
            sizeMB <= 2000 -> 30
            sizeMB <= 3000 -> 20
            else -> 10
        }
        adjustedQuality += sizeBonus
    }

    // Add server speed bonus
    adjustedQuality += getServerPriority(serverName)

    return adjustedQuality
}



class Hubstreamdad : Hblinks() {
    override var mainUrl = "https://hblinks.*"
}

// 4khdhub uses same structure as hblinks
class FourKHDHub : Hblinks() {
    override var mainUrl = "https://4khdhub.*"
    override val name = "4KHDHub"
}

/**
 * Hblinks / 4khdhub Extractor
 * 
 * Final download page structure (Jan 2026):
 * hblinks.dad/archives/XXXXX
 * 
 * Contains links to:
 * - hubdrive.space/file/XXX (Drive) → redirects to hubcloud
 * - hubcdn.fans/file/XXX (Instant) → direct download
 * - hubcloud.foo/drive/XXX (Direct) → gamerxyt.com final links
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
        Log.d(tag, "Processing URL: $url")
        
        try {
            val doc = app.get(url, timeout = 30).document
            
            // Select all download links from hblinks page
            // Structure: h3/h5 > a[href] with text like "Drive", "Instant", "Direct"
            val links = doc.select("h3 a[href], h5 a[href], div.entry-content p a[href], div.entry-content a[href]")
            
            Log.d(tag, "Found ${links.size} links")
            
            links.amap { element ->
                val href = element.absUrl("href").ifBlank { element.attr("href") }
                val linkText = element.text().trim()
                val parentText = element.parent()?.text()?.trim() ?: ""
                
                // Skip invalid links
                if (href.isBlank() || href.startsWith("#") || href.contains("t.me")) return@amap
                
                Log.d(tag, "Processing link: $linkText -> $href")
                
                // Extract quality from parent text (e.g., "480p – Drive | Instant | Direct")
                val qualityMatch = Regex("""(\d{3,4})p""").find(parentText)
                val quality = qualityMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                
                when {
                    // hubdrive.space links → use Hubdrive extractor
                    href.contains("hubdrive", ignoreCase = true) -> {
                        try {
                            Hubdrive().getUrl(href, name, subtitleCallback, callback)
                        } catch (e: Exception) {
                            Log.e(tag, "Hubdrive failed: ${e.message}")
                        }
                    }
                    
                    // hubcloud links → use HubCloud extractor
                    href.contains("hubcloud", ignoreCase = true) -> {
                        try {
                            HubCloud().getUrl(href, name, subtitleCallback, callback)
                        } catch (e: Exception) {
                            Log.e(tag, "HubCloud failed: ${e.message}")
                        }
                    }
                    
                    // hubcdn.fans (Instant) → direct file or HUBCDN extractor
                    href.contains("hubcdn.fans", ignoreCase = true) -> {
                        try {
                            // hubcdn.fans often provides direct download
                            HUBCDN().getUrl(href, name, subtitleCallback, callback)
                        } catch (e: Exception) {
                            Log.e(tag, "HUBCDN failed: ${e.message}")
                        }
                    }
                    
                    // pixeldrain links
                    href.contains("pixeldrain", ignoreCase = true) -> {
                        try {
                            loadExtractor(href, referer, subtitleCallback, callback)
                        } catch (e: Exception) {
                            Log.e(tag, "Pixeldrain failed: ${e.message}")
                        }
                    }
                    
                    // Other links - try generic extractor
                    href.startsWith("http") -> {
                        try {
                            loadSourceNameExtractor(name, href, "", quality, subtitleCallback, callback)
                        } catch (e: Exception) {
                            Log.w(tag, "Generic extractor failed for: $href")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing hblinks page: ${e.message}")
        }
    }
}

// REMOVED: Hubcdnn class - duplicate of HUBCDN, returned M3U8 streaming URLs
// Use HUBCDN class instead which provides direct download links

class PixelDrainDev : PixelDrain(){
    override var mainUrl = "https://pixeldrain.dev"
}

class Hubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.*"
    override val requiresReferer = false

    // Cloudflare bypass
    private val cfKiller by lazy { CloudflareKiller() }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Use CloudflareKiller interceptor to bypass Cloudflare 403
        val doc = app.get(url, timeout = 30000, interceptor = cfKiller).documentLarge

        // Primary selector from Brave inspection
        var href = doc.select(".btn.btn-primary.btn-user.btn-success1.m-1").attr("href")

        // Fallback selectors if primary fails
        if (href.isBlank() || !href.contains("hubcloud", true)) {
            href = doc.selectFirst("a.btn[href*=hubcloud]")?.attr("href") ?: ""
        }
        if (href.isBlank() || !href.contains("hubcloud", true)) {
            href = doc.selectFirst("a[href*=hubcloud.fyi]")?.attr("href") ?: ""
        }

        Log.d("Hubdrive", "Found href: $href")

        if (href.contains("hubcloud", ignoreCase = true)) {
            HubCloud().getUrl(href, "HubDrive", subtitleCallback, callback)
        } else {
            Log.d("Hubdrive", "No HubCloud link found for: $url")
        }
    }
}

class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.*"
    override val requiresReferer = false

    // Cloudflare bypass
    private val cfKiller by lazy { CloudflareKiller() }
    
    /**
     * ═══════════════════════════════════════════════════════════════════════
     * SMART LINK FILTERING - Download Links Only
     * ═══════════════════════════════════════════════════════════════════════
     * 
     * This extractor now ONLY returns direct download links, NOT streaming URLs.
     * Streaming URLs (M3U8/HLS) are filtered out to prevent buffering issues.
     */

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HubCloud"
        
        // FILTER: Skip streaming-only domains
        if (isStreamingOnlyUrl(url)) {
            Log.d(tag, "SKIPPED streaming-only URL: $url")
            return
        }
        
        val isValidUrl = try { 
            URI(url).toURL()
            true 
        } catch (e: Exception) { 
            Log.e(tag, "Invalid URL: ${e.message}")
            false 
        }
        if (!isValidUrl) return
        val realUrl = url
        Log.d(tag, "Processing URL: $url")

        // Use original URL directly first (most reliable)
        // Only use URL replacement if original fails
        val urlToUse = realUrl
        Log.d(tag, "Using URL: $urlToUse")

        val href = try {
            when {
                "hubcloud.php" in urlToUse || "gamerxyt.com" in urlToUse -> urlToUse
                "/drive/" in urlToUse -> {
                    // hubcloud.fyi/drive/ URLs - find gamerxyt.com hubcloud.php link
                    // Use CloudflareKiller to bypass protection
                    val driveDoc = app.get(urlToUse, interceptor = cfKiller, timeout = 30).document

                    // Primary selectors based on Brave Browser inspection:
                    // Button class: "btn btn-primary h6 p-2" links to gamerxyt.com/hubcloud.php
                    val generateBtn: String? = driveDoc.selectFirst("a.btn.btn-primary.h6")?.attr("href")
                        ?: driveDoc.selectFirst("a.btn[href*=gamerxyt.com/hubcloud.php]")?.attr("href")
                        ?: driveDoc.selectFirst("a.btn[href*=hubcloud.php]")?.attr("href")
                        ?: driveDoc.selectFirst("a.btn.btn-primary[href*=gamerxyt]")?.attr("href")
                        ?: driveDoc.selectFirst("a#download")?.attr("href")
                        ?: driveDoc.selectFirst("a.btn-primary")?.attr("href")

                    Log.d(tag, "Drive page generate button: $generateBtn")

                    // If generate button found, use it
                    if (!generateBtn.isNullOrBlank() && generateBtn.startsWith("http")) {
                        generateBtn
                    } else {
                        // Fallback: check all buttons for gamerxyt or direct download links
                        val allBtns = driveDoc.select("a.btn")
                        val gamerxytLink = allBtns.firstOrNull {
                            it.attr("href").contains("gamerxyt", true) ||
                                    it.attr("href").contains("hubcloud.php", true)
                        }?.attr("href")

                        if (!gamerxytLink.isNullOrBlank()) {
                            gamerxytLink
                        } else {
                            Log.w(tag, "No gamerxyt link found, trying direct CDN links")
                            // Last resort: try to find any download CDN links
                            val cdnLink = allBtns.firstOrNull {
                                val h = it.attr("href")
                                h.contains("fsl", true) || h.contains("pixel", true) || h.contains("r2.dev", true)
                            }?.attr("href")
                            cdnLink ?: ""
                        }
                    }
                }
                else -> {
                    val rawHref = app.get(urlToUse, interceptor = cfKiller, timeout = 30).document.select("#download").attr("href")
                    if (rawHref.startsWith("http", ignoreCase = true)) rawHref
                    else getBaseUrl(urlToUse).trimEnd('/') + "/" + rawHref.trimStart('/')
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to extract href: ${e.message}")
            ""
        }

        if (href.isBlank()) {
            Log.w(tag, "No valid href found for: $url")
            return
        }

        Log.d(tag, "Fetching download page: $href")

        // Use CloudflareKiller for gamerxyt.com final download page
        val document = app.get(href, interceptor = cfKiller).document
        val sizeText = document.selectFirst("i#size")?.text()
        val size = sizeText ?: ""
        val headerText = document.selectFirst("div.card-header")?.text()
        val header = headerText ?: ""

        val headerDetails = cleanTitle(header)

        val labelExtras = buildString {
            if (headerDetails.isNotEmpty()) append("[$headerDetails]")
            if (size.isNotEmpty()) append("[$size]")
        }
        val baseQuality = getIndexQuality(header)

        // Helper function to invoke callback only for download links (not streaming)
        val safeCallback: (ExtractorLink) -> Unit = { extractorLink ->
            val linkUrl = extractorLink.url
            // FILTER: Skip streaming URLs (M3U8/HLS cause buffering)
            if (isStreamingFormat(linkUrl)) {
                Log.d(tag, "FILTERED streaming URL: $linkUrl")
            } else if (isStreamingOnlyUrl(linkUrl)) {
                Log.d(tag, "FILTERED streaming-only domain: $linkUrl")
            } else {
                // Only invoke callback for direct download links
                callback.invoke(extractorLink)
                Log.d(tag, "ACCEPTED download link: $linkUrl")
            }
        }

        document.select("div.card-body a.btn").amap { element ->
            val link = element.attr("href")
            val text = element.text()
            val serverQuality = getAdjustedQuality(baseQuality, size, text)
            Log.d("Phisher", "Link: $link, Text: $text")
            
            // FILTER: Skip streaming URLs before processing
            if (isStreamingFormat(link) || isStreamingOnlyUrl(link)) {
                Log.d(tag, "SKIPPED streaming link: $link")
                return@amap
            }

            // URL-based server detection (since button text is often empty)
            when {
                // Instant DL - Fastest server, highest priority
                text.contains("Instant", ignoreCase = true) || text.contains("🚀", ignoreCase = true) -> {
                    safeCallback.invoke(
                        newExtractorLink(
                            "$referer [Instant DL]",
                            "$referer [Instant DL] $labelExtras",
                            link,
                        ) { this.quality = serverQuality + 50 }
                    )
                }

                // FSL Server - fsl.gigabytes.icu, hub.fsl-lover.buzz
                link.contains("fsl.gigabytes", ignoreCase = true) ||
                        link.contains("fsl-lover.buzz", ignoreCase = true) ||
                        (link.contains("gigabytes.icu", ignoreCase = true) && !link.contains("gdboka")) -> {
                    safeCallback.invoke(
                        newExtractorLink(
                            "$referer [FSL Server]",
                            "$referer [FSL Server] $labelExtras",
                            link,
                        ) { this.quality = serverQuality + 15 }
                    )
                }

                // FSLv2 - r2.dev, gdboka.buzz, cdn.fukggl.buzz, carnewz.site
                link.contains("r2.dev", ignoreCase = true) ||
                        link.contains("gdboka.buzz", ignoreCase = true) ||
                        link.contains("fukggl.buzz", ignoreCase = true) ||
                        link.contains("carnewz.site", ignoreCase = true) -> {
                    safeCallback.invoke(
                        newExtractorLink(
                            "$referer [FSLv2]",
                            "$referer [FSLv2] $labelExtras",
                            link,
                        ) { this.quality = serverQuality + 20 }
                    )
                }

                // Old text-based FSL Server detection (fallback)
                text.contains("FSL Server", ignoreCase = true) -> {
                    safeCallback.invoke(
                        newExtractorLink(
                            "$referer [FSL Server]",
                            "$referer [FSL Server] $labelExtras",
                            link,
                        ) { this.quality = serverQuality }
                    )
                }

                text.contains("Download File", ignoreCase = true) -> {
                    safeCallback.invoke(
                        newExtractorLink(
                            "$referer",
                            "$referer $labelExtras",
                            link,
                        ) { this.quality = serverQuality }
                    )
                }

                // BuzzServer - bloggingvector.shop (URL-based detection)
                link.contains("bloggingvector", ignoreCase = true) ||
                        text.contains("BuzzServer", ignoreCase = true) -> {
                    try {
                        val buzzResp = app.get("$link/download", referer = link, allowRedirects = false)
                        val dlinkHeader = buzzResp.headers["hx-redirect"]
                        val dlink = dlinkHeader ?: ""
                        if (dlink.isNotBlank()) {
                            safeCallback.invoke(
                                newExtractorLink(
                                    "$referer [BuzzServer]",
                                    "$referer [BuzzServer] $labelExtras",
                                    dlink,
                                ) { this.quality = serverQuality }
                            )
                        } else {
                            // Try direct link if no redirect
                            safeCallback.invoke(
                                newExtractorLink(
                                    "$referer [BuzzServer]",
                                    "$referer [BuzzServer] $labelExtras",
                                    link,
                                ) { this.quality = serverQuality }
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "BuzzServer failed: ${e.message}")
                    }
                }

                // PixelDrain - URL-based detection (pixeldrain.dev, hubcdn.fans)
                link.contains("pixeldrain", ignoreCase = true) ||
                        link.contains("hubcdn.fans", ignoreCase = true) ||
                        text.contains("pixeldra", ignoreCase = true) ||
                        text.contains("pixel", ignoreCase = true) -> {
                    // Handle different pixeldrain URL formats
                    val finalURL = when {
                        link.contains("pixeldrain.dev/u/") || link.contains("pixeldrain.com/u/") -> {
                            // Format: pixeldrain.dev/u/ID -> pixeldrain.dev/api/file/ID?download
                            val baseUrlLink = getBaseUrl(link)
                            "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"
                        }
                        link.contains("pixel.hubcdn.fans") || link.contains("hubcdn.fans") -> {
                            // hubcdn.fans links redirect to pixeldrain - follow redirect
                            try {
                                val redirectResp = app.get(link, allowRedirects = false)
                                val redirectLoc = redirectResp.headers["location"]
                                if (!redirectLoc.isNullOrBlank() && redirectLoc.contains("pixeldrain")) {
                                    val baseUrlLink = getBaseUrl(redirectLoc)
                                    "$baseUrlLink/api/file/${redirectLoc.substringAfterLast("/")}?download"
                                } else {
                                    link // Use original if no redirect
                                }
                            } catch (e: Exception) {
                                Log.w(tag, "hubcdn.fans redirect failed: ${e.message}")
                                link
                            }
                        }
                        link.contains("download", true) -> link
                        else -> {
                            val baseUrlLink = getBaseUrl(link)
                            "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"
                        }
                    }

                    safeCallback(
                        newExtractorLink(
                            "Pixeldrain",
                            "Pixeldrain $labelExtras",
                            finalURL
                        ) { this.quality = serverQuality }
                    )
                }

                text.contains("S3 Server", ignoreCase = true) -> {
                    safeCallback.invoke(
                        newExtractorLink(
                            "$referer S3 Server",
                            "$referer S3 Server $labelExtras",
                            link,
                        ) { this.quality = serverQuality }
                    )
                }

                text.contains("FSLv2", ignoreCase = true) -> {
                    safeCallback.invoke(
                        newExtractorLink(
                            "$referer FSLv2",
                            "$referer FSLv2 $labelExtras",
                            link,
                        ) { this.quality = serverQuality }
                    )
                }

                text.contains("Mega Server", ignoreCase = true) -> {
                    safeCallback.invoke(
                        newExtractorLink(
                            "$referer [Mega Server]",
                            "$referer [Mega Server] $labelExtras",
                            link,
                        ) { this.quality = serverQuality }
                    )
                }

                text.contains("10Gbps", ignoreCase = true) -> {
                    var currentLink = link
                    var redirectUrl: String?
                    var redirectCount = 0
                    val maxRedirects = 3

                    while (redirectCount < maxRedirects) {
                        val response = app.get(currentLink, allowRedirects = false)
                        redirectUrl = response.headers["location"]

                        if (redirectUrl == null) {
                            Log.e(tag, "10Gbps: No redirect")
                            return@amap
                        }

                        if ("link=" in redirectUrl) {
                            val finalLink = redirectUrl.substringAfter("link=")
                            safeCallback.invoke(
                                newExtractorLink(
                                    "10Gbps [Download]",
                                    "10Gbps [Download] $labelExtras",
                                    finalLink
                                ) { this.quality = serverQuality }
                            )
                            return@amap
                        }

                        currentLink = redirectUrl
                        redirectCount++
                    }

                    Log.e(tag, "10Gbps: Redirect limit reached ($maxRedirects)")
                    return@amap
                }

                else -> {
                    // Handle unknown server types - direct link callback
                    // FILTER: Only accept if it's a download link, not streaming
                    Log.d(tag, "Unknown server type, checking link: $text -> $link")
                    if (link.isNotBlank() && link.startsWith("http")) {
                        if (isDirectDownloadUrl(link)) {
                            safeCallback.invoke(
                                newExtractorLink(
                                    "$referer [Direct]",
                                    "$referer [Direct] $labelExtras",
                                    link,
                                ) { this.quality = serverQuality }
                            )
                        } else {
                            Log.d(tag, "SKIPPED unknown link (not direct download): $link")
                        }
                    }
                }
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        val searchStr = str ?: ""
        return Regex("(\\d{3,4})[pP]").find(searchStr)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.P2160.value
    }

    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (_: Exception) {
            ""
        }
    }

    fun cleanTitle(title: String): String {
        val parts = title.split(".", "-", "_")

        val qualityTags = listOf(
            "WEBRip", "WEB-DL", "WEB", "BluRay", "HDRip", "DVDRip", "HDTV",
            "CAM", "TS", "R5", "DVDScr", "BRRip", "BDRip", "DVD", "PDTV",
            "HD"
        )

        val audioTags = listOf(
            "AAC", "AC3", "DTS", "MP3", "FLAC", "DD5", "EAC3", "Atmos"
        )

        val subTags = listOf(
            "ESub", "ESubs", "Subs", "MultiSub", "NoSub", "EnglishSub", "HindiSub"
        )

        val codecTags = listOf(
            "x264", "x265", "H264", "HEVC", "AVC"
        )

        val startIndex = parts.indexOfFirst { part ->
            qualityTags.any { tag -> part.contains(tag, ignoreCase = true) }
        }

        val endIndex = parts.indexOfLast { part ->
            subTags.any { tag -> part.contains(tag, ignoreCase = true) } ||
                    audioTags.any { tag -> part.contains(tag, ignoreCase = true) } ||
                    codecTags.any { tag -> part.contains(tag, ignoreCase = true) }
        }

        return if (startIndex != -1 && endIndex != -1 && endIndex >= startIndex) {
            parts.subList(startIndex, endIndex + 1).joinToString(".")
        } else if (startIndex != -1) {
            parts.subList(startIndex, parts.size).joinToString(".")
        } else {
            parts.takeLast(3).joinToString(".")
        }
    }
}

/**
 * HDStream4u Extractor - BLOCKED (Streaming Only, High Buffering)
 * 
 * ═══════════════════════════════════════════════════════════════════════════
 * REASON FOR BLOCKING:
 * - hdstream4u.com only provides M3U8 streaming URLs
 * - Requires reCAPTCHA bypass which is unreliable
 * - High buffering issues on streaming playback
 * - No direct download links available without CAPTCHA
 * 
 * SOLUTION: Use hubcloud/hubdrive/hblinks extractors instead which provide
 * direct download links from CDN servers (FSL, Pixeldrain, etc.)
 * ═══════════════════════════════════════════════════════════════════════════
 */
class HDStream4u : ExtractorApi() {
    override val name = "HDStream4u"
    override val mainUrl = "https://hdstream4u.com"
    override val requiresReferer = true

    companion object {
        private val FILE_CODE_REGEX = Regex("""/file/([a-zA-Z0-9]+)""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HDStream4u"
        
        // ═══════════════════════════════════════════════════════════════════
        // BLOCKED: hdstream4u.com is streaming-only with high buffering
        // Skip this extractor to prioritize download links from other sources
        // ═══════════════════════════════════════════════════════════════════
        Log.d(tag, "SKIPPED: hdstream4u.com blocked (streaming-only, use hubcloud/hblinks instead)")
        Log.d(tag, "URL was: $url")
        
        // Do not process - return immediately
        // This forces the app to use other extractors that provide download links
        return
    }
}

/**
 * Hubstream Extractor - BLOCKED (Streaming Only, High Buffering)
 * 
 * ═══════════════════════════════════════════════════════════════════════════
 * REASON FOR BLOCKING:
 * - hubstream.art ONLY provides M3U8 streaming URLs
 * - High buffering issues due to HLS streaming nature
 * - No direct download links available
 * - AES-encrypted API responses are unreliable
 * 
 * SOLUTION: Use hubcloud/hubdrive/hblinks extractors instead which provide
 * direct download links from CDN servers (FSL, Pixeldrain, etc.)
 * ═══════════════════════════════════════════════════════════════════════════
 */
class Hubstream : ExtractorApi() {
    override val name = "Hubstream"
    override val mainUrl = "https://hubstream.art"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "Hubstream"
        
        // ═══════════════════════════════════════════════════════════════════
        // BLOCKED: hubstream.art is streaming-only with high buffering
        // Skip this extractor to prioritize download links from other sources
        // ═══════════════════════════════════════════════════════════════════
        Log.d(tag, "SKIPPED: hubstream.art blocked (streaming-only, use hubcloud/hblinks instead)")
        Log.d(tag, "URL was: $url")
        
        // Do not process - return immediately
        // This forces the app to use other extractors that provide download links
        return
    }
}

/**
 * HUBCDN Extractor - hubcdn.fans instant downloads + gadgetsweb.xyz redirect
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
        Log.d(tag, "Processing URL: $url")

        try {
            when {
                // gadgetsweb.xyz/?id=BASE64 - Redirect mediator
                url.contains("gadgetsweb.xyz") && url.contains("?id=") -> {
                    Log.d(tag, "Gadgetsweb mediator detected")
                    
                    var hblinksUrl: String? = null
                    
                    // Method 1: Try to decode base64 id parameter to find hblinks URL
                    val encodedId = url.substringAfter("?id=").substringBefore("&")
                    try {
                        val decoded = base64Decode(encodedId)
                        Log.d(tag, "Decoded id: $decoded")
                        val hblinkMatch = Regex("""https?://(?:hblinks|4khdhub)\.[a-z]+/archives/\d+""")
                            .find(decoded)
                        hblinksUrl = hblinkMatch?.value
                    } catch (e: Exception) {
                        Log.w(tag, "Base64 decode failed: ${e.message}")
                    }
                    
                    // Method 2: Load page and find hblinks URL
                    if (hblinksUrl == null) {
                        try {
                            val doc = app.get(url, timeout = 30).document
                            
                            // Look for hblinks in scripts
                            doc.select("script").forEach { script ->
                                val scriptText = script.data()
                                val match = Regex("""https?://(?:hblinks|4khdhub)\.[a-z]+/archives/\d+""")
                                    .find(scriptText)
                                if (match != null) {
                                    hblinksUrl = match.value
                                }
                            }
                            
                            // Look for verify_btn link
                            if (hblinksUrl == null) {
                                val verifyBtn = doc.selectFirst("a#verify_btn, a.get-link, a[href*=hblinks], a[href*=4khdhub]")
                                hblinksUrl = verifyBtn?.attr("href")
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Page load failed: ${e.message}")
                        }
                    }
                    
                    // Method 3: Try homelander page
                    if (hblinksUrl == null) {
                        try {
                            val homelanderUrl = "https://gadgetsweb.xyz/homelander/"
                            val homelanderDoc = app.get(homelanderUrl, timeout = 30).document
                            val verifyBtn = homelanderDoc.selectFirst("a#verify_btn")
                            hblinksUrl = verifyBtn?.attr("href")
                        } catch (e: Exception) {
                            Log.e(tag, "Homelander failed: ${e.message}")
                        }
                    }
                    
                    if (!hblinksUrl.isNullOrBlank()) {
                        Log.d(tag, "Found hblinks URL: $hblinksUrl")
                        Hblinks().getUrl(hblinksUrl, referer, subtitleCallback, callback)
                    } else {
                        Log.e(tag, "Could not extract hblinks URL from gadgetsweb")
                    }
                }
                
                // Format: hubcdn.fans/file/XXX (Instant download)
                url.contains("hubcdn.fans/file/") -> {
                    Log.d(tag, "hubcdn.fans instant download")
                    val doc = app.get(url, timeout = 30).document
                    
                    // Try to find reurl variable
                    val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()
                    val encodedUrl = Regex("""reurl\s*=\s*"([^"]+)"""")
                        .find(scriptText ?: "")
                        ?.groupValues?.get(1)
                        ?.substringAfter("?r=")
                    
                    val decodedUrl = encodedUrl?.let { base64Decode(it) }?.substringAfterLast("link=")
                    
                    if (decodedUrl != null) {
                        callback(
                            newExtractorLink(
                                "Instant DL",
                                "Instant DL [hubcdn.fans]",
                                decodedUrl,
                                INFER_TYPE,
                            ) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    } else {
                        // Direct link fallback
                        callback(
                            newExtractorLink(
                                "Instant DL",
                                "Instant DL [hubcdn.fans]",
                                url,
                                INFER_TYPE,
                            ) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
                
                // Legacy hubcdn format
                url.contains("hubcdn") -> {
                    val doc = app.get(url).document
                    val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()

                    val encodedUrl = Regex("""reurl\s*=\s*"([^"]+)"""")
                        .find(scriptText ?: "")
                        ?.groupValues?.get(1)
                        ?.substringAfter("?r=")

                    val decodedUrl = encodedUrl?.let { base64Decode(it) }?.substringAfterLast("link=")

                    if (decodedUrl != null) {
                        callback(
                            newExtractorLink(
                                this.name,
                                this.name,
                                decodedUrl,
                                INFER_TYPE,
                            ) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    } else {
                        // Try hubcloud fallback
                        val hubcloudLink = doc.select("a[href*=hubcloud]").attr("href")
                        if (hubcloudLink.isNotBlank()) {
                            HubCloud().getUrl(hubcloudLink, referer, subtitleCallback, callback)
                        }
                    }
                }
                
                else -> {
                    Log.w(tag, "Unknown URL format: $url")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing URL: ${e.message}")
        }
    }
}