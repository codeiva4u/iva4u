package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import org.json.JSONObject
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

// ═══════════════════════════════════════════════════════════════════════════════════
// UTILITY FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════════════

fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (_: Exception) { "" }
}

// Cached URLs for session-level caching (fetch once, use throughout session)
private var cachedUrlsJson: JSONObject? = null

suspend fun getLatestUrl(url: String, source: String): String {
    // Use cached JSON if available (fetch only once per session)
    if (cachedUrlsJson == null) {
        try {
            cachedUrlsJson = JSONObject(
                app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json").text
            )
            Log.d("DomainResolver", "✅ Successfully fetched domains from GitHub")
        } catch (e: Exception) {
            Log.e("DomainResolver", "❌ Failed to fetch domain from GitHub: ${e.message}, using fallback")
            return getBaseUrl(url)
        }
    }
    
    val link = cachedUrlsJson?.optString(source)
    if (link.isNullOrEmpty()) {
        Log.d("DomainResolver", "⚠️ No domain found for source: $source, using base URL")
        return getBaseUrl(url)
    }
    Log.d("DomainResolver", "✅ Using domain for $source: $link")
    return link
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
    serverName.contains("10Gbps", true) -> 88
    serverName.contains("FSLv2", true) -> 85
    serverName.contains("FSL", true) -> 80
    serverName.contains("Download", true) -> 70
    serverName.contains("Pixel", true) -> 60
    else -> 50
}

fun calculateQualityScore(quality: Int, sizeStr: String, serverName: String, codec: String = ""): Int {
    // Base score by resolution
    var score = when (quality) {
        1080 -> 1000
        2160 -> 900  // 4K lower priority (larger files)
        720 -> 700
        480 -> 500
        else -> 300
    }
    
    // PRIORITY 1: X265/HEVC gets highest codec bonus (smaller files, better compression)
    if (codec.contains("hevc", true) || codec.contains("x265", true) || codec.contains("h265", true) || codec.contains("h.265", true)) {
        score += 150  // HEVC highest priority
    } 
    // PRIORITY 2: X264 gets medium codec bonus (better compatibility)
    else if (codec.contains("x264", true) || codec.contains("h264", true) || codec.contains("h.264", true)) {
        score += 100
    }
    
    // PRIORITY 3: Size bonus (smaller = higher priority)
    if (quality == 1080) {
        val sizeMB = parseSizeToMB(sizeStr)
        score += when {
            sizeMB <= 600 -> 90   // Ultra HEVC compressed
            sizeMB <= 900 -> 75   // Highly compressed
            sizeMB <= 1200 -> 60
            sizeMB <= 1600 -> 45
            sizeMB <= 2000 -> 30
            sizeMB <= 2500 -> 15
            else -> 0
        }
    }
    
    // PRIORITY 4: Server speed bonus
    score += getServerPriority(serverName)
    
    return score
}

// Check if URL is a direct download (not streaming)
fun isDirectDownloadUrl(url: String): Boolean {
    val downloadIndicators = listOf(
        "gamerxyt.com", "pixeldrain", "r2.dev", "gdboka.buzz", 
        "fukggl.buzz", "carnewz.site", "fsl.gigabytes", "fsl-lover.buzz",
        "gigabytes.icu", "acek-cdn.com",
        "/download", ".mkv", ".mp4", ".avi"
    )
    return downloadIndicators.any { url.contains(it, ignoreCase = true) }
}

// Check if URL should be blocked (streaming only)
fun shouldBlockUrl(url: String): Boolean {
    return url.contains(".m3u8", true) || url.contains("/hls/", true)
}

/**
 * ROT13 decode function for gadgetsweb.xyz URL obfuscation
 * Used in the decode chain: base64 -> base64 -> rot13 -> base64
 */
fun rot13(input: String): String {
    return input.map { char ->
        when (char) {
            in 'a'..'z' -> ((char - 'a' + 13) % 26 + 'a'.code).toChar()
            in 'A'..'Z' -> ((char - 'A' + 13) % 26 + 'A'.code).toChar()
            else -> char
        }
    }.joinToString("")
}

/**
 * Decode gadgetsweb localStorage data to extract final hblinks URL
 * Decode chain: base64 -> base64 -> rot13 -> base64 -> JSON parse -> base64 decode 'o' field
 */
fun decodeGadgetswebData(encodedData: String): String? {
    return try {
        // Decode chain: base64 -> base64 -> rot13 -> base64
        val step1 = base64Decode(encodedData)  // First base64 decode
        val step2 = base64Decode(step1)         // Second base64 decode
        val step3 = rot13(step2)                // ROT13 decode
        val step4 = base64Decode(step3)         // Third base64 decode
        
        // Parse JSON: {"w":10,"l":"...","o":"BASE64_URL"}
        val oFieldPattern = Regex(""""o"\s*:\s*"([^"]+)"""")
        val oMatch = oFieldPattern.find(step4)
        
        if (oMatch != null) {
            val encodedUrl = oMatch.groupValues[1]
            base64Decode(encodedUrl)  // Final URL
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

suspend fun isValidVideoUrl(url: String): Boolean {
    return try {
        val response = com.lagradost.cloudstream3.app.head(url)
        val contentType = response.headers["content-type"]?.lowercase() ?: ""
        val contentDisposition = response.headers["content-disposition"]?.lowercase() ?: ""
        
        // Check for invalid content types
        val isZip = contentType.contains("zip") || 
                    contentType.contains("octet-stream") && url.contains(".zip") ||
                    contentDisposition.contains(".zip")
        val isHtml = contentType.contains("text/html")
        val isError = response.code >= 400
        
        // Valid if video/* or application/octet-stream (for mkv/mp4)
        val isVideo = contentType.contains("video/") || 
                      (contentType.contains("octet-stream") && !isZip)
        
        if (isZip) Log.d("URLValidator", "BLOCKED ZIP: $url")
        if (isHtml) Log.d("URLValidator", "BLOCKED HTML response: $url")
        if (isError) Log.d("URLValidator", "BLOCKED HTTP error ${response.code}: $url")
        
        !isZip && !isHtml && !isError && (isVideo || contentType.isEmpty())
    } catch (e: Exception) {
        Log.d("URLValidator", "Validation failed (allowing): ${e.message}")
        true  // Allow if validation fails (don't block on timeout)
    }
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
            // Fetch latest hblinks domain from GitHub JSON
            val latestUrl = getLatestUrl(url, "hblinks")
            val baseUrl = getBaseUrl(url)
            val newUrl = url.replace(baseUrl, latestUrl)
            
            Log.d(tag, "Using domain: $latestUrl")
            val doc = app.get(newUrl).document
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
/**
 * PixelDrain Dev - pixeldrain.dev direct downloads
 */
class PixelDrainDev : PixelDrain() {
    override var mainUrl = "https://pixeldrain.*"
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
            // Fetch latest hubdrive domain from GitHub JSON
            val latestUrl = getLatestUrl(url, "hubdrive")
            val baseUrl = getBaseUrl(url)
            val newUrl = url.replace(baseUrl, latestUrl)
            
            Log.d(tag, "Using domain: $latestUrl")
            val doc = app.get(newUrl, interceptor = cfKiller).documentLarge
            
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
        
        // Fetch latest hubcloud domain from GitHub JSON
        val latestUrl = getLatestUrl(url, "hubcloud")
        val baseUrl = getBaseUrl(url)
        val newUrl = url.replace(baseUrl, latestUrl)
        
        Log.d(tag, "Using domain: $latestUrl")

        // Get hubcloud.php page URL
        val phpUrl = try {
            when {
                "hubcloud.php" in newUrl || "gamerxyt.com" in newUrl -> newUrl
                "/drive/" in newUrl -> {
                    val driveDoc = app.get(newUrl, interceptor = cfKiller).document
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
                    val rawHref = app.get(newUrl, interceptor = cfKiller)
                        .document.select("#download").attr("href")
                    if (rawHref.startsWith("http")) rawHref
                    else getBaseUrl(newUrl).trimEnd('/') + "/" + rawHref.trimStart('/')
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
        val document = app.get(phpUrl, interceptor = cfKiller).document
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
            
            // Skip ZipDisk server (downloads ZIP files instead of video)
            if (text.contains("ZipDisk", true) || link.contains("zipdisk", true) || 
                link.endsWith(".zip", true) || link.contains(".zip?", true) ||
                link.contains("cloudserver", true)) {
                Log.d(tag, "SKIPPED ZipDisk (ZIP not video): $link")
                return@amap
            }
            
            // Skip BuzzServer / Telegram fake links (bloggingvector.shop)
            if (link.contains("bloggingvector", true) || text.contains("Telegram", true)) {
                Log.d(tag, "SKIPPED BuzzServer/Telegram: $link")
                return@amap
            }

            val score = calculateQualityScore(quality, size, text, codec)
            
            try {
                when {
                    // Instant DL - Fastest
                    text.contains("Instant", true) || text.contains("🚀") -> {
                        // Validate before adding
                        if (isValidVideoUrl(link)) {
                            callback(newExtractorLink(
                                "$referer [Instant DL]",
                                "$referer [Instant DL] $labelExtras",
                                link
                            ) { this.quality = score + 50 })
                        }
                    }

                    // FSL Server
                    link.contains("fsl.gigabytes", true) || 
                    link.contains("fsl-lover.buzz", true) ||
                    (link.contains("gigabytes.icu", true) && !link.contains("gdboka")) -> {
                        if (isValidVideoUrl(link)) {
                            callback(newExtractorLink(
                                "$referer [FSL]",
                                "$referer [FSL] $labelExtras",
                                link
                            ) { this.quality = score + 15 })
                        }
                    }

                    // FSLv2 - R2/CDN
                    link.contains("r2.dev", true) ||
                    link.contains("gdboka.buzz", true) ||
                    link.contains("fukggl.buzz", true) ||
                    link.contains("carnewz.site", true) -> {
                        if (isValidVideoUrl(link)) {
                            callback(newExtractorLink(
                                "$referer [FSLv2]",
                                "$referer [FSLv2] $labelExtras",
                                link
                            ) { this.quality = score + 20 })
                        }
                    }

                    // Download File button
                    text.contains("Download", true) -> {
                        if (isValidVideoUrl(link)) {
                            callback(newExtractorLink(
                                "$referer",
                                "$referer $labelExtras",
                                link
                            ) { this.quality = score })
                        }
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
                        // PixelDrain URLs are usually reliable, skip validation
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
                                val finalLink = redirectUrl.substringAfter("link=")
                                if (isValidVideoUrl(finalLink)) {
                                    callback(newExtractorLink(
                                        "10Gbps",
                                        "10Gbps $labelExtras",
                                        finalLink
                                    ) { this.quality = score })
                                }
                                return@amap
                            }
                            currentLink = redirectUrl
                        }
                    }

                    // Other direct download links
                    link.startsWith("http") && isDirectDownloadUrl(link) -> {
                        if (isValidVideoUrl(link)) {
                            callback(newExtractorLink(
                                "$referer [Direct]",
                                "$referer [Direct] $labelExtras",
                                link
                            ) { this.quality = score })
                        }
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

class HUBCDN : ExtractorApi() {
    override val name = "HUBCDN"
    override val mainUrl = "https://hubcdn.*"
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
            // Fetch latest hubcdn domain from GitHub JSON
            val latestHubcdnUrl = getLatestUrl(url, "hubcdn")
            val baseUrl = getBaseUrl(url)
            val newUrl = url.replace(baseUrl, latestHubcdnUrl)
            
            Log.d(tag, "Using hubcdn domain: $latestHubcdnUrl")
            
            when {
                // gadgetsweb.xyz/?id=ENCRYPTED - NEW v2.0 approach
                url.contains("gadgetsweb.xyz") && url.contains("?id=") -> {
                    Log.d(tag, "Gadgetsweb v2.0 - extracting encoded data from response")
                    
                    var hblinksUrl: String? = null
                    
                    // Step 1: Fetch WITHOUT following redirects to get the JS with encoded data
                    val response = app.get(url, allowRedirects = false)
                    val html = response.text
                    
                    Log.d(tag, "Response status: ${response.code}, length: ${html.length}")
                    
                    // Step 2: Extract encoded data from s('o','ENCODED_DATA',...)
                    // Pattern: s('o','Y214WE0xWjNZbXRh....',180*1000);
                    val sPattern = Regex("""s\s*\(\s*['"]o['"]\s*,\s*['"]([A-Za-z0-9+/=]+)['"]""")
                    val sMatch = sPattern.find(html)
                    
                    if (sMatch != null) {
                        val encodedData = sMatch.groupValues[1]
                        Log.d(tag, "✓ Found encoded data (${encodedData.length} chars)")
                        
                        // Step 3: Decode using the chain: base64 → base64 → rot13 → base64 → JSON → base64
                        hblinksUrl = decodeGadgetswebData(encodedData)
                        
                        if (hblinksUrl != null) {
                            Log.d(tag, "✓ Decoded hblinks URL: $hblinksUrl")
                        } else {
                            Log.w(tag, "✗ Failed to decode data")
                        }
                    } else {
                        Log.d(tag, "s() function not found, trying legacy redirect method")
                        
                        // Fallback: Follow redirect and try legacy extraction
                        val redirectResponse = app.get(url, allowRedirects = true)
                        val finalUrl = redirectResponse.url
                        val doc = redirectResponse.document
                        val fullHtml = doc.html()
                        
                        Log.d(tag, "Redirected to: $finalUrl")
                        
                        // Try regex patterns in HTML
                        val hblinksPattern = Regex("""https?://(?:hblinks|4khdhub)\.[a-z]+/archives/\d+""")
                        
                        doc.select("script").forEach { script ->
                            val scriptData = script.data()
                            val match = hblinksPattern.find(scriptData)
                            if (match != null && hblinksUrl == null) {
                                hblinksUrl = match.value
                                Log.d(tag, "✓ Found hblinks in script: $hblinksUrl")
                            }
                        }
                        
                        if (hblinksUrl == null) {
                            val match = hblinksPattern.find(fullHtml)
                            if (match != null) {
                                hblinksUrl = match.value
                                Log.d(tag, "✓ Found hblinks in HTML: $hblinksUrl")
                            }
                        }
                    }
                    
                    // Step 4: Process hblinks URL
                    if (!hblinksUrl.isNullOrBlank()) {
                        Log.d(tag, "→ Processing hblinks: $hblinksUrl")
                        Hblinks().getUrl(hblinksUrl, referer, subtitleCallback, callback)
                    } else {
                        Log.w(tag, "✗ Failed to extract hblinks URL from gadgetsweb")
                    }
                }
                
                // gadgetsweb.xyz/homelander/ or similar mediator pages (direct access)
                url.contains("gadgetsweb.xyz") && !url.contains("?id=") -> {
                    Log.d(tag, "Gadgetsweb mediator page (direct access)")
                    val doc = app.get(url).document
                    val html = doc.html()
                    
                    val hblinksPattern = Regex("""https?://(?:hblinks|4khdhub)\.[a-z]+/archives/\d+""")
                    val match = hblinksPattern.find(html)
                    
                    if (match != null) {
                        val hblinksUrl = match.value
                        Log.d(tag, "✓ Found hblinks: $hblinksUrl")
                        Hblinks().getUrl(hblinksUrl, referer, subtitleCallback, callback)
                    } else {
                        Log.w(tag, "✗ No hblinks URL found in mediator page")
                    }
                }
                
                // hubcdn.fans/file/XXX - Instant download
                url.contains("hubcdn.fans/file/") -> {
                    Log.d(tag, "Instant download")
                    val doc = app.get(url).document
                    
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


