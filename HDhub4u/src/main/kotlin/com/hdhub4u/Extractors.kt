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

// Helper function to load source with extractor name
suspend fun loadSourceNameExtractor(
    name: String,
    url: String,
    referer: String,
    quality: Int,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
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

class Hubcdnn : ExtractorApi() {
    override val name = "Hubcdn"
    override val mainUrl = "https://hubcdn.*"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        app.get(url).documentLarge.toString().let {
            val encoded = Regex("r=([A-Za-z0-9+/=]+)").find(it)?.groups?.get(1)?.value
            if (!encoded.isNullOrEmpty()) {
                val m3u8 = base64Decode(encoded).substringAfterLast("link=")
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        url = m3u8,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                Log.e("Error", "Encoded URL not found")
            }


        }
    }
}

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

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HubCloud"
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


        document.select("div.card-body a.btn").amap { element ->
            val link = element.attr("href")
            val text = element.text()
            val serverQuality = getAdjustedQuality(baseQuality, size, text)
            Log.d("Phisher", "Link: $link, Text: $text")

            // URL-based server detection (since button text is often empty)
            when {
                // Instant DL - Fastest server, highest priority
                text.contains("Instant", ignoreCase = true) || text.contains("🚀", ignoreCase = true) -> {
                    callback.invoke(
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
                    callback.invoke(
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
                    callback.invoke(
                        newExtractorLink(
                            "$referer [FSLv2]",
                            "$referer [FSLv2] $labelExtras",
                            link,
                        ) { this.quality = serverQuality + 20 }
                    )
                }

                // Old text-based FSL Server detection (fallback)
                text.contains("FSL Server", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$referer [FSL Server]",
                            "$referer [FSL Server] $labelExtras",
                            link,
                        ) { this.quality = serverQuality }
                    )
                }

                text.contains("Download File", ignoreCase = true) -> {
                    callback.invoke(
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
                            callback.invoke(
                                newExtractorLink(
                                    "$referer [BuzzServer]",
                                    "$referer [BuzzServer] $labelExtras",
                                    dlink,
                                ) { this.quality = serverQuality }
                            )
                        } else {
                            // Try direct link if no redirect
                            callback.invoke(
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

                    callback(
                        newExtractorLink(
                            "Pixeldrain",
                            "Pixeldrain $labelExtras",
                            finalURL
                        ) { this.quality = serverQuality }
                    )
                }

                text.contains("S3 Server", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$referer S3 Server",
                            "$referer S3 Server $labelExtras",
                            link,
                        ) { this.quality = serverQuality }
                    )
                }

                text.contains("FSLv2", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$referer FSLv2",
                            "$referer FSLv2 $labelExtras",
                            link,
                        ) { this.quality = serverQuality }
                    )
                }

                text.contains("Mega Server", ignoreCase = true) -> {
                    callback.invoke(
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
                            callback.invoke(
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
                    Log.d(tag, "Unknown server type, using direct link: $text -> $link")
                    if (link.isNotBlank() && link.startsWith("http")) {
                        callback.invoke(
                            newExtractorLink(
                                "$referer [Direct]",
                                "$referer [Direct] $labelExtras",
                                link,
                            ) { this.quality = serverQuality }
                        )
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
 * HDStream4u Extractor - hdstream4u.com streaming player with download links
 * 
 * URL Format: hdstream4u.com/file/{file_code}
 * Download Chain:
 *   1. hdstream4u.com/file/{code} → Extract file code
 *   2. minochinos.com/download/{code} → Quality selection (HD: _n, Normal: _l)
 *   3. minochinos.com/download/{code}_{quality} → POST form with hash
 *   4. Final CDN URL: *.acek-cdn.com/vp/.../filename.mkv.mp4?...
 * 
 * Note: reCAPTCHA bypassed by sending form directly with proper parameters
 */
class HDStream4u : ExtractorApi() {
    override val name = "HDStream4u"
    override val mainUrl = "https://hdstream4u.com"
    override val requiresReferer = true

    companion object {
        // Regex patterns
        private val FILE_CODE_REGEX = Regex("""/file/([a-zA-Z0-9]+)""")
        private val DOWNLOAD_LINK_REGEX = Regex("""href=["']([^"']*acek-cdn\.com[^"']*)["']""", RegexOption.IGNORE_CASE)
        private val HASH_REGEX = Regex("""name=["']hash["']\s+value=["']([^"']+)["']""")
        private val QUALITY_REGEX = Regex("""(\d{3,4})x(\d{3,4})\s+([\d.]+\s*[GM]B)""", RegexOption.IGNORE_CASE)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HDStream4u"
        Log.d(tag, "Processing URL: $url")

        try {
            // Extract file code from URL
            val fileCode = FILE_CODE_REGEX.find(url)?.groupValues?.get(1)
            if (fileCode.isNullOrBlank()) {
                Log.e(tag, "Could not extract file code from: $url")
                return
            }
            Log.d(tag, "File code: $fileCode")

            // Determine download domain dynamically
            // hdstream4u.com uses minochinos.com for downloads (may change)
            val downloadBaseUrl = getDownloadBaseUrl(url, fileCode)
            if (downloadBaseUrl.isBlank()) {
                Log.e(tag, "Could not determine download URL")
                return
            }

            // Fetch quality selection page
            val downloadUrl = "$downloadBaseUrl/download/$fileCode"
            Log.d(tag, "Fetching quality page: $downloadUrl")
            
            val qualityDoc = app.get(downloadUrl, timeout = 30).document

            // Find quality links: /download/{code}_n (HD), /download/{code}_l (Normal)
            val qualityLinks = qualityDoc.select("a.btn[href*='/download/$fileCode']")
            
            if (qualityLinks.isEmpty()) {
                Log.w(tag, "No quality links found, trying direct download")
                processDownloadPage(downloadUrl, fileCode, "n", referer, callback)
                return
            }

            // Process each quality option
            qualityLinks.forEach { element ->
                val href = element.absUrl("href").ifBlank { element.attr("href") }
                val qualityText = element.selectFirst("small")?.text() ?: ""
                val buttonText = element.selectFirst("b")?.text() ?: ""
                
                // Extract quality mode (_n for HD, _l for Normal)
                val mode = when {
                    href.endsWith("_n") -> "n"
                    href.endsWith("_l") -> "l"
                    else -> "n"
                }

                // Parse resolution and size
                val qualityMatch = QUALITY_REGEX.find(qualityText)
                val width = qualityMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val height = qualityMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
                val size = qualityMatch?.groupValues?.get(3) ?: ""
                
                val quality = when {
                    height >= 1080 || width >= 1920 -> 1080
                    height >= 720 || width >= 1280 -> 720
                    height >= 480 || width >= 854 -> 480
                    else -> 0
                }

                Log.d(tag, "Quality option: $buttonText - ${width}x$height $size")

                // Process download page for this quality
                val fullDownloadUrl = if (href.startsWith("http")) href else "$downloadBaseUrl$href"
                processDownloadPage(fullDownloadUrl, fileCode, mode, referer, callback, quality, size)
            }

        } catch (e: Exception) {
            Log.e(tag, "Error processing HDStream4u: ${e.message}")
        }
    }

    private suspend fun getDownloadBaseUrl(url: String, fileCode: String): String {
        val tag = "HDStream4u"
        
        try {
            // Try to find download URL from the player page
            val doc = app.get(url, timeout = 30).document
            
            // Look for window.open with download URL in scripts
            doc.select("script").forEach { script ->
                val scriptData = script.data()
                
                // Pattern: window.open('https://minochinos.com/download/...')
                val downloadMatch = Regex("""window\.open\(['"]([^'"]+/download/[^'"]+)['"]""")
                    .find(scriptData)
                if (downloadMatch != null) {
                    val foundUrl = downloadMatch.groupValues[1]
                    return getBaseUrl(foundUrl)
                }
                
                // Alternative: look for download domain in packed JS
                val domainMatch = Regex("""https?://([a-zA-Z0-9.-]+)/download/""")
                    .find(scriptData)
                if (domainMatch != null) {
                    return "https://${domainMatch.groupValues[1]}"
                }
            }

            // Fallback: known download domains
            val knownDomains = listOf(
                "minochinos.com",
                "earnvids.com",
                "streamhg.com"
            )

            for (domain in knownDomains) {
                try {
                    val testUrl = "https://$domain/download/$fileCode"
                    val response = app.get(testUrl, timeout = 10, allowRedirects = false)
                    if (response.code == 200 || response.code == 302) {
                        return "https://$domain"
                    }
                } catch (e: Exception) {
                    continue
                }
            }

            // Ultimate fallback
            return "https://minochinos.com"

        } catch (e: Exception) {
            Log.e(tag, "Error finding download URL: ${e.message}")
            return "https://minochinos.com"
        }
    }

    private suspend fun processDownloadPage(
        url: String,
        fileCode: String,
        mode: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
        quality: Int = 0,
        size: String = ""
    ) {
        val tag = "HDStream4u"
        
        try {
            Log.d(tag, "Processing download page: $url")
            
            val doc = app.get(url, timeout = 30).document
            
            // Extract form parameters
            val form = doc.selectFirst("form[method=POST]")
            if (form != null) {
                val op = form.selectFirst("input[name=op]")?.attr("value") ?: "download_orig"
                val id = form.selectFirst("input[name=id]")?.attr("value") ?: fileCode
                val modeValue = form.selectFirst("input[name=mode]")?.attr("value") ?: mode
                val hash = form.selectFirst("input[name=hash]")?.attr("value") ?: ""
                
                if (hash.isNotBlank()) {
                    Log.d(tag, "Submitting download form with hash: ${hash.take(20)}...")
                    
                    // Submit form (bypass reCAPTCHA by direct POST)
                    val formData = mapOf(
                        "op" to op,
                        "id" to id,
                        "mode" to modeValue,
                        "hash" to hash,
                        "g-recaptcha-response" to "" // Empty token, may work without verification
                    )
                    
                    val resultDoc = app.post(
                        url,
                        data = formData,
                        timeout = 30
                    ).document
                    
                    // Look for direct download link
                    val downloadLink = resultDoc.selectFirst("a.btn[href*=acek-cdn], a[href*=acek-cdn], a.btn-gradient[href]")
                        ?.absUrl("href")
                    
                    if (!downloadLink.isNullOrBlank()) {
                        val qualityLabel = when {
                            mode == "n" -> "HD"
                            mode == "l" -> "SD"
                            quality >= 720 -> "HD"
                            else -> "SD"
                        }
                        
                        val sizeLabel = if (size.isNotBlank()) "[$size]" else ""
                        
                        callback(
                            newExtractorLink(
                                "HDStream4u [$qualityLabel]",
                                "HDStream4u [$qualityLabel] $sizeLabel",
                                downloadLink,
                            ) {
                                this.quality = if (quality > 0) quality else if (mode == "n") 720 else 480
                            }
                        )
                        Log.d(tag, "Found download link: $downloadLink")
                        return
                    }
                }
            }
            
            // Fallback: Look for any CDN links on the page
            val cdnLinks = doc.select("a[href*=acek-cdn], a[href*=cdn], a.btn-gradient")
            cdnLinks.forEach { link ->
                val href = link.absUrl("href")
                if (href.contains("acek-cdn") || href.contains("cdn")) {
                    callback(
                        newExtractorLink(
                            "HDStream4u",
                            "HDStream4u",
                            href,
                        ) {
                            this.quality = if (quality > 0) quality else Qualities.Unknown.value
                        }
                    )
                }
            }

        } catch (e: Exception) {
            Log.e(tag, "Error processing download page: ${e.message}")
        }
    }
}

/**
 * Hubstream Extractor - hubstream.art video streaming
 * 
 * URL Format: hubstream.art/#{video_id}
 * 
 * API Endpoint: hubstream.art/api/v1/player?t={encrypted_token}
 * Returns: Encrypted video data (AES-CBC with varying keys)
 * 
 * Stream URLs:
 *   - Primary: hubstream.art/hls/{token}/js/{id}/{folder}/tt/master.m3u8
 *   - CDN: *.zenitharchitecture.site/v4/js/{id}/cf-master.*.txt
 *   - Direct IP: {IP}/v4/{token}/{timestamp}/js/{id}/master.m3u8
 * 
 * Note: Only M3U8 streaming available - NO direct download links
 */
class Hubstream : ExtractorApi() {
    override val name = "Hubstream"
    override val mainUrl = "https://hubstream.art"
    override val requiresReferer = true

    companion object {
        // Regex patterns for Hubstream
        private val HASH_REGEX = Regex("""#([a-zA-Z0-9]+)""")
        private val M3U8_REGEX = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""", RegexOption.IGNORE_CASE)
        private val TXT_M3U8_REGEX = Regex("""(https?://[^"'\s]+master[^"'\s]*\.txt[^"'\s]*)""", RegexOption.IGNORE_CASE)
        
        // AES-128-CBC Decryption Key for Hubstream Download API
        private const val AES_KEY = "kiemtienmua911ca"  // 16 chars for AES-128
        
        /**
         * Convert hex string to byte array
         */
        private fun hexToBytes(hex: String): ByteArray {
            val cleanHex = hex.trim()
            return ByteArray(cleanHex.length / 2) { i ->
                cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
        
        /**
         * Decrypt AES-128-CBC encrypted hex string
         * Format: First 16 bytes = IV, remaining = ciphertext
         */
        private fun decryptAES(encryptedHex: String): String? {
            return try {
                val encryptedBytes = hexToBytes(encryptedHex)
                
                // First 16 bytes are the IV
                val iv = encryptedBytes.sliceArray(0 until 16)
                // Remaining bytes are the ciphertext
                val ciphertext = encryptedBytes.sliceArray(16 until encryptedBytes.size)
                
                val keySpec = SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES")
                val ivSpec = IvParameterSpec(iv)
                
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
                
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e("Hubstream", "AES decryption failed: ${e.message}")
                null
            }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "Hubstream"
        Log.d(tag, "Processing URL: $url")

        try {
            // Extract video ID from hash
            val videoId = HASH_REGEX.find(url)?.groupValues?.get(1)
            if (videoId.isNullOrBlank()) {
                Log.e(tag, "Could not extract video ID from: $url")
                return
            }
            Log.d(tag, "Video ID: $videoId")

            // PRIORITY 1: Try direct MP4 download API (best quality, fastest)
            val downloadSuccess = extractDownloadLink(videoId, url, subtitleCallback, callback)
            
            // PRIORITY 2: Fall back to M3U8 streaming if download failed
            if (!downloadSuccess) {
                Log.d(tag, "Download API failed, trying M3U8 streams")
                
                // Fetch the player page
                val doc = app.get(url, timeout = 30).document
                val pageHtml = doc.html()
                
                // Find .txt master files (used by hubstream CDN)
                val txtMatches = TXT_M3U8_REGEX.findAll(pageHtml)
                txtMatches.forEach { match ->
                    val m3u8Url = match.groupValues[1]
                        .replace("&amp;", "&")
                        .replace("\\/", "/")
                    
                    if (m3u8Url.contains("master") || m3u8Url.contains("cf-master")) {
                        callback(
                            newExtractorLink(
                                name,
                                "Hubstream [Stream]",
                                m3u8Url,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        Log.d(tag, "Found TXT M3U8: $m3u8Url")
                    }
                }

                // Find direct M3U8 URLs
                val m3u8Matches = M3U8_REGEX.findAll(pageHtml)
                m3u8Matches.forEach { match ->
                    val m3u8Url = match.groupValues[1]
                        .replace("&amp;", "&")
                        .replace("\\/", "/")
                    
                    if (m3u8Url.contains("master") && !m3u8Url.contains(".txt")) {
                        callback(
                            newExtractorLink(
                                name,
                                "Hubstream [HLS]",
                                m3u8Url,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        Log.d(tag, "Found M3U8: $m3u8Url")
                    }
                }

                // Method 2: Try to call the player API directly
                tryPlayerApi(videoId, url, callback)
            }

        } catch (e: Exception) {
            Log.e(tag, "Error processing Hubstream: ${e.message}")
        }
    }
    
    /**
     * Extract direct MP4 download link using Hubstream Download API
     * 
     * API: /api/v1/download?id={videoId}
     * Returns: Hex-encoded AES-128-CBC encrypted JSON
     * Key: kiemtienmua911ca (16 chars)
     * Format: First 16 bytes = IV, rest = ciphertext
     * Decrypted: {"mp4": "https://IP/token/timestamp/js/path/file.mp4/download?title=...", ...}
     */
    private suspend fun extractDownloadLink(
        videoId: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tag = "Hubstream"
        
        try {
            // Call the download API
            val downloadApiUrl = "$mainUrl/api/v1/download?id=$videoId"
            Log.d(tag, "Calling download API: $downloadApiUrl")
            
            val response = app.get(
                downloadApiUrl,
                referer = referer,
                timeout = 30
            ).text.trim()
            
            if (response.isBlank() || response.length < 32) {
                Log.w(tag, "Empty or invalid API response")
                return false
            }
            
            Log.d(tag, "API response length: ${response.length}")
            
            // Decrypt the response
            val decrypted = decryptAES(response)
            if (decrypted.isNullOrBlank()) {
                Log.w(tag, "Decryption returned null")
                return false
            }
            
            Log.d(tag, "Decrypted response: ${decrypted.take(200)}...")
            
            // Extract mp4 URL directly from decrypted data (may not be valid JSON)
            // Pattern: "mp4":"https://IP/token/timestamp/path/file.mp4/download?title=..."
            val mp4Url = Regex(""""mp4"\s*:\s*"([^"]+)""").find(decrypted)?.groupValues?.get(1)
                ?.replace("\\/", "/")
            
            if (!mp4Url.isNullOrBlank() && mp4Url.startsWith("http")) {
                Log.d(tag, "Found MP4 download URL: $mp4Url")
                
                // Extract title from URL if available
                val title = Regex("""title=([^&]+)""").find(mp4Url)?.groupValues?.get(1)
                    ?.replace(".mp4", "")
                    ?.replace("+", " ")
                    ?: "Hubstream Download"
                
                // Determine quality from filename
                val quality = when {
                    title.contains("2160p", true) || title.contains("4K", true) -> Qualities.P2160.value
                    title.contains("1080p", true) -> Qualities.P1080.value
                    title.contains("720p", true) -> Qualities.P720.value
                    title.contains("480p", true) -> Qualities.P480.value
                    else -> Qualities.P1080.value  // Default to 1080p for downloads
                }
                
                callback(
                    newExtractorLink(
                        name,
                        "Hubstream [Download]",
                        mp4Url,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = quality
                    }
                )
                
                // Also try to extract subtitles
                val subtitleMatch = Regex(""""subtitle"\s*:\s*\{([^}]+)\}""").find(decrypted)
                if (subtitleMatch != null) {
                    val subtitleJson = subtitleMatch.groupValues[1]
                    // Pattern: "en": "/path/to/en.vtt#en"
                    Regex(""""(\w+)"\s*:\s*"([^"]+)"""").findAll(subtitleJson).forEach { subMatch ->
                        val lang = subMatch.groupValues[1]
                        var subUrl = subMatch.groupValues[2].replace("\\/", "/")
                        
                        // Make URL absolute if needed
                        if (subUrl.startsWith("/")) {
                            subUrl = "$mainUrl$subUrl"
                        }
                        
                        // Remove hash fragment if present
                        subUrl = subUrl.substringBefore("#")
                        
                        subtitleCallback(
                            newSubtitleFile(lang.uppercase(), subUrl)
                        )
                        Log.d(tag, "Found subtitle: $lang -> $subUrl")
                    }
                }
                
                return true
            }
            
            Log.w(tag, "No valid MP4 URL in response")
            return false
            
        } catch (e: Exception) {
            Log.e(tag, "Download API failed: ${e.message}")
            return false
        }
    }

    private suspend fun tryPlayerApi(
        videoId: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "Hubstream"
        
        try {
            // Known CDN patterns for hubstream
            val cdnDomains = listOf(
                "zenitharchitecture.site",
                "hubstream.art"
            )
            
            for (domain in cdnDomains) {
                try {
                    // Pattern 1: Direct CDN txt file
                    val txtUrl = "https://s3ae.$domain/v4/js/$videoId/cf-master.txt"
                    val txtResponse = app.get(txtUrl, timeout = 10, allowRedirects = true)
                    
                    if (txtResponse.code == 200) {
                        callback(
                            newExtractorLink(
                                name,
                                "Hubstream CDN",
                                txtUrl,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = referer
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        Log.d(tag, "Found CDN stream: $txtUrl")
                        return
                    }
                } catch (e: Exception) {
                    continue
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "API method failed: ${e.message}")
        }
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