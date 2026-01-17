package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

// Helper function to load source with extractor name
suspend fun loadSourceNameExtractor(
    name: String,
    url: String,
    referer: String,
    quality: Int,
    callback: (ExtractorLink) -> Unit
) {
    try {
        callback.invoke(
            newExtractorLink(
                name,
                name,
                url = url,
            ) {
                this.referer = referer
                this.quality = quality
            }
        )
    } catch (e: Exception) {
        Log.e("loadSourceNameExtractor", "Error: ${e.message}")
    }
}

// Cached URLs to avoid fetching urls.json on every call
private var cachedUrlsJson: org.json.JSONObject? = null

suspend fun getLatestUrl(url: String, source: String): String {
    // Use cached JSON if available (fetch only once per session)
    if (cachedUrlsJson == null) {
        try {
            // 10 second timeout - no fallback, only urls.json
            cachedUrlsJson = org.json.JSONObject(
                app.get(
                    "https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json",
                    timeout = 10
                ).text
            )
        } catch (e: Exception) {
            Log.e("getLatestUrl", "Failed to fetch urls.json: ${e.message}")
            throw e  // No fallback - urls.json is required
        }
    }
    
    val link = cachedUrlsJson?.optString(source)
    if (link.isNullOrEmpty()) {
        throw IllegalStateException("Source '$source' not found in urls.json")
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
        app.get(url).documentLarge.select("h3 a,h5 a,div.entry-content p a").map {
            val lower = it.absUrl("href").ifBlank { it.attr("href") }
            val href = lower.lowercase()
            when {
                "hubdrive" in lower -> Hubdrive().getUrl(href, name, subtitleCallback, callback)
                "hubcloud" in lower -> HubCloud().getUrl(href, name, subtitleCallback, callback)
                "hubcdn" in lower -> HUBCDN().getUrl(href, name, subtitleCallback, callback)
                else -> loadSourceNameExtractor(name, href, "", Qualities.Unknown.value, callback)
            }
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
        // Use CloudflareKiller interceptor to bypass Cloudflare 403 (15s timeout for speed)
        val doc = app.get(url, timeout = 15, interceptor = cfKiller).documentLarge
        
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
        val realUrl = url.takeIf {
            try { URI(it).toURL(); true } catch (e: Exception) { Log.e(tag, "Invalid URL: ${e.message}"); false }
        } ?: return
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
                    // Use CloudflareKiller with 15s timeout for speed
                    val driveDoc = app.get(urlToUse, interceptor = cfKiller, timeout = 15).document
                    
                    // Primary selectors based on Brave Browser inspection:
                    // Button class: "btn btn-primary h6 p-2" links to gamerxyt.com/hubcloud.php
                    val generateBtn = driveDoc.selectFirst("a.btn.btn-primary.h6")?.attr("href")
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
                    val rawHref = app.get(urlToUse, interceptor = cfKiller, timeout = 15).document.select("#download").attr("href")
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

        // Use CloudflareKiller for gamerxyt.com final download page (15s timeout)
        val document = app.get(href, interceptor = cfKiller, timeout = 15).document
        val size = document.selectFirst("i#size")?.text().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()

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

                // FSL Server - fsl.gigabytes.icu
                link.contains("fsl.gigabytes", ignoreCase = true) || 
                (link.contains("gigabytes.icu", ignoreCase = true) && !link.contains("gdboka")) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$referer [FSL Server]",
                            "$referer [FSL Server] $labelExtras",
                            link,
                        ) { this.quality = serverQuality + 15 }
                    )
                }

                // FSLv2 - r2.dev or gdboka.buzz
                link.contains("r2.dev", ignoreCase = true) || 
                link.contains("gdboka.buzz", ignoreCase = true) -> {
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
                        val dlink = buzzResp.headers["hx-redirect"].orEmpty()
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
        return Regex("(\\d{3,4})[pP]").find(str.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
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
        Log.d(tag, "Processing URL: $url")
        
        // Skip hubcdn.fans/file/ URLs - they are ad redirect pages
        if (url.contains("hubcdn.fans/file/", ignoreCase = true)) {
            Log.d(tag, "Skipping hubcdn.fans/file/ URL (ad page): $url")
            return
        }
        
        try {
            // For gadgetsweb.xyz with encrypted id parameter
            // Flow: gadgetsweb.xyz -> decrypt -> hblinks.dad -> hubcloud/hubdrive
            if (url.contains("gadgetsweb.xyz", ignoreCase = true) && url.contains("id=", ignoreCase = true)) {
                Log.d(tag, "Handling gadgetsweb.xyz - attempting to decrypt")
                
                // Fetch the initial redirect page
                val response = app.get(url, timeout = 20)
                val html = response.text
                
                // Method 1: Extract encrypted data from JavaScript s() function call
                // Pattern: s('o','BASE64_DATA',180*1000)
                val encryptedDataRegex = Regex("""s\s*\(\s*['"]o['"]\s*,\s*['"]([A-Za-z0-9+/=]+)['"]\s*,""")
                val encryptedMatch = encryptedDataRegex.find(html)
                
                if (encryptedMatch != null) {
                    val encryptedData = encryptedMatch.groupValues[1]
                    Log.d(tag, "Found encrypted data: ${encryptedData.take(50)}...")
                    
                    try {
                        // Gadgetsweb uses: Base64 -> ROT13-like -> Base64 -> hblinks URL
                        // First decode
                        val firstDecode = base64Decode(encryptedData)
                        Log.d(tag, "First decode: ${firstDecode.take(50)}...")
                        
                        // Apply ROT13 variant (shift by 13)
                        val rot13Decoded = firstDecode.map { char ->
                            when {
                                char in 'a'..'z' -> ((char - 'a' + 13) % 26 + 'a'.code).toChar()
                                char in 'A'..'Z' -> ((char - 'A' + 13) % 26 + 'A'.code).toChar()
                                else -> char
                            }
                        }.joinToString("")
                        
                        // Try second base64 decode
                        val secondDecode = try { base64Decode(rot13Decoded) } catch (e: Exception) { rot13Decoded }
                        Log.d(tag, "Second decode: ${secondDecode.take(100)}...")
                        
                        // Extract hblinks URL from decoded data
                        val hblinksRegex = Regex("""https?://hblinks[^"'\s<>]+/archives/\d+""", RegexOption.IGNORE_CASE)
                        val hblinksMatch = hblinksRegex.find(secondDecode)
                        
                        if (hblinksMatch != null) {
                            val hblinksUrl = hblinksMatch.value
                            Log.d(tag, "Decrypted hblinks URL: $hblinksUrl")
                            Hblinks().getUrl(hblinksUrl, referer, subtitleCallback, callback)
                            return
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Decryption failed: ${e.message}")
                    }
                }
                
                // Method 2: Fetch /homelander/ page and check for links
                try {
                    val homelanderDoc = app.get("https://gadgetsweb.xyz/homelander/", timeout = 15).document
                    val hblinksLink = homelanderDoc.select("a[href*=hblinks]").firstOrNull()?.attr("href")
                    if (!hblinksLink.isNullOrBlank()) {
                        Log.d(tag, "Found hblinks on homelander page: $hblinksLink")
                        Hblinks().getUrl(hblinksLink, referer, subtitleCallback, callback)
                        return
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Homelander page fetch failed: ${e.message}")
                }
                
                // Method 3: Original DOM parsing fallback
                val doc = response.document
                val hblinksLink = doc.select("a[href*=hblinks]").firstOrNull()?.attr("href")
                    ?: doc.select("a#verify_btn[href*=hblinks]").firstOrNull()?.attr("href")
                    
                if (!hblinksLink.isNullOrBlank() && hblinksLink.contains("hblinks", true)) {
                    Log.d(tag, "Found hblinks link: $hblinksLink")
                    Hblinks().getUrl(hblinksLink, referer, subtitleCallback, callback)
                    return
                }
                
                // Method 4: Check for direct hubcloud link
                val hubcloudLink = doc.select("a[href*=hubcloud]").firstOrNull()?.attr("href")
                    ?: doc.select("a[href*=drive]").firstOrNull()?.attr("href")
                
                if (!hubcloudLink.isNullOrBlank() && hubcloudLink.contains("hubcloud", true)) {
                    Log.d(tag, "Found hubcloud link: $hubcloudLink")
                    HubCloud().getUrl(hubcloudLink, referer, subtitleCallback, callback)
                    return
                }
                
                Log.w(tag, "No hblinks/hubcloud link found on gadgetsweb page")
                return
            }
        } catch (e: Exception) {
            Log.e(tag, "Error handling gadgetsweb: ${e.message}")
        }
        
        val doc = app.get(url).documentLarge
        val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()

        val encodedUrl = Regex("reurl\\s*=\\s*\"([^\"]+)\"")
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
                )
                {
                    this.quality=Qualities.Unknown.value
                }
            )
        } else {
            // Fallback: Try to find hubcloud link on page for /file/ URLs
            Log.d("HUBCDN", "var reurl not found, trying fallback for /file/ URL")
            try {
                // Try to find any redirect link or hubcloud link
                val fallbackDoc = app.get(url).document
                val hubcloudLink = fallbackDoc.select("a[href*=hubcloud]").attr("href")
                    .ifBlank {
                        fallbackDoc.select("a.btn[href*=drive]").attr("href")
                    }
                
                if (hubcloudLink.isNotBlank() && hubcloudLink.contains("hubcloud", true)) {
                    Log.d("HUBCDN", "Found hubcloud link: $hubcloudLink")
                    HubCloud().getUrl(hubcloudLink, referer, subtitleCallback, callback)
                } else {
                    Log.e("HUBCDN", "No fallback link found for: $url")
                }
            } catch (e: Exception) {
                Log.e("HUBCDN", "Fallback failed: ${e.message}")
            }
        }
    }
}

// Hubstream.art Video Player Extractor
class Hubstream : ExtractorApi() {
    override val name = "Hubstream"
    override val mainUrl = "https://hubstream.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "Hubstream"
        Log.d(tag, "Processing URL: $url")
        
        // Extract video ID from URL hash (e.g., hubstream.art/#d8kdio -> d8kdio)
        val videoIdRegex = Regex("""#([a-zA-Z0-9]+)""")
        val videoId = videoIdRegex.find(url)?.groupValues?.get(1)
        
        if (videoId.isNullOrEmpty()) {
            Log.e(tag, "No video ID found in URL: $url")
            return
        }
        
        Log.d(tag, "Found video ID: $videoId")
        
        try {
            // Method 0: Try direct hubstream.art HLS endpoint (discovered via deep scraping)
            // Pattern: https://hubstream.art/hls/{token}/us/{videoId}/{suffix}/master.m3u8
            // The token is dynamic, but we can try to fetch the page and extract it
            val hubstreamPageUrl = "https://hubstream.art/#$videoId"
            Log.d(tag, "Fetching hubstream page for HLS URL: $hubstreamPageUrl")
            
            try {
                val pageResponse = app.get(hubstreamPageUrl, timeout = 15)
                val pageHtml = pageResponse.text
                
                // Look for direct HLS URL in page/script
                val directHlsPatterns = listOf(
                    Regex("""(https?://hubstream\.art/hls/[^"'\s<>]+master\.m3u8[^"'\s<>]*)"""),
                    Regex("""(https?://[^"'\s<>]+hubstream[^"'\s<>]+/hls/[^"'\s<>]+\.m3u8)"""),
                    Regex(""""src"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""""),
                    Regex("""source\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""")
                )
                
                for (pattern in directHlsPatterns) {
                    val match = pattern.find(pageHtml)
                    if (match != null) {
                        val streamUrl = match.groupValues[1]
                        Log.d(tag, "Found direct HLS URL: $streamUrl")
                        
                        callback.invoke(
                            newExtractorLink(
                                "Hubstream",
                                "Hubstream [HLS Direct]",
                                streamUrl,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://hubstream.art/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return
                    }
                }
                
                // Try to extract video config from JavaScript
                val configPatterns = listOf(
                    Regex("""video_id\s*[=:]\s*['"]([^'"]+)['"]"""),
                    Regex("""file\s*[=:]\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                    Regex("""hls\s*[=:]\s*['"]([^'"]+)['"]""")
                )
                
                for (pattern in configPatterns) {
                    val match = pattern.find(pageHtml)
                    if (match != null) {
                        val value = match.groupValues[1]
                        if (value.contains("m3u8") || value.contains("http")) {
                            var streamUrl = value
                            if (streamUrl.startsWith("/")) {
                                streamUrl = "https://hubstream.art$streamUrl"
                            }
                            Log.d(tag, "Found config URL: $streamUrl")
                            
                            callback.invoke(
                                newExtractorLink(
                                    "Hubstream",
                                    "Hubstream [Config]",
                                    streamUrl,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "https://hubstream.art/"
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(tag, "Direct HLS extraction failed: ${e.message}")
            }
            
            // Method 1: Try pureluxurystudios.online direct URL (fallback)
            // Pattern: https://srcf.pureluxurystudios.online/v4/us/{VIDEO_ID}/cf-master.{TIMESTAMP}.txt
            
            val pureServers = listOf("srcf", "sil5", "src2", "sil2")
            val currentTimestamp = System.currentTimeMillis() / 1000
            
            for (server in pureServers) {
                try {
                    val pureStreamUrl = "https://$server.pureluxurystudios.online/v4/us/$videoId/cf-master.$currentTimestamp.txt"
                    Log.d(tag, "Trying pureluxurystudios: $pureStreamUrl")
                    
                    // Check if URL is accessible with HEAD request
                    val testResponse = app.head(pureStreamUrl, timeout = 5)
                    if (testResponse.code == 200) {
                        Log.d(tag, "Found working pureluxurystudios URL: $pureStreamUrl")
                        
                        callback.invoke(
                            newExtractorLink(
                                "Hubstream",
                                "Hubstream [HLS]",
                                pureStreamUrl,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://hubstream.art/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return
                    }
                } catch (e: Exception) {
                    Log.d(tag, "Server $server not available: ${e.message}")
                }
            }
            
            // Method 2: Fetch hubstream page and look for stream URLs in HTML/JS
            val hubstreamDomain = try {
                getLatestUrl(url, "hubstream")
            } catch (e: Exception) {
                "https://hubstream.art"
            }
            Log.d(tag, "Using hubstream domain: $hubstreamDomain")
        
            // Construct URL with latest domain
            val requestUrl = "$hubstreamDomain/#$videoId"
            
            // Fetch the page to extract video source
            val doc = app.get(requestUrl, timeout = 15).document
            val pageHtml = doc.html()
            
            // Pattern 1: hubstream.art/hls/.../master.m3u8 format (primary HLS stream)
            val hlsRegex = Regex("""(https?://[^"'\s]+/hls/[^"'\s]+master\.m3u8[^"'\s]*)""")
            val hlsMatch = hlsRegex.find(pageHtml)
            
            // Pattern 2: pureluxurystudios.online/v4/.../cf-master.xxx.txt format
            val pureStreamRegex = Regex("""(https?://[^"'\s]*pureluxurystudios[^"'\s]+/cf-master[^"'\s]+\.txt)""")
            val pureMatch = pureStreamRegex.find(pageHtml)
            
            // Pattern 3: v4/us/{videoId}/cf-master format
            val streamRegex = Regex("""(https?://[^"'\s]+/v4/[^"'\s]+/$videoId/[^"'\s]+\.txt)""")
            val streamMatch = streamRegex.find(pageHtml)
            
            // Pattern 4: Generic src attribute
            val altStreamRegex = Regex("""src['":\s]+['"](https?://[^"'\s]+(?:m3u8|txt|mp4)[^"'\s]*)['"]?""", RegexOption.IGNORE_CASE)
            val altMatch = altStreamRegex.find(pageHtml)
            
            // Pattern 5: Extract from source element
            val sourceRegex = Regex("""<source[^>]+src=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            val sourceMatch = sourceRegex.find(pageHtml)
            
            val streamUrl = hlsMatch?.groupValues?.get(1)
                ?: pureMatch?.groupValues?.get(1)
                ?: streamMatch?.groupValues?.get(1)
                ?: altMatch?.groupValues?.get(1)
                ?: sourceMatch?.groupValues?.get(1)
            
            if (!streamUrl.isNullOrEmpty()) {
                Log.d(tag, "Found stream URL: $streamUrl")
                
                // Determine if it's m3u8 or direct
                val linkType = when {
                    streamUrl.contains("m3u8", true) || streamUrl.contains(".txt", true) -> ExtractorLinkType.M3U8
                    streamUrl.contains("mp4", true) || streamUrl.contains("mkv", true) -> ExtractorLinkType.VIDEO
                    else -> INFER_TYPE
                }
                
                // Extract quality from title if available
                val title = doc.title()
                val qualityRegex = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
                val quality = qualityRegex.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
                
                callback.invoke(
                    newExtractorLink(
                        "Hubstream [Stream]",
                        "Hubstream [Stream] [$title]",
                        streamUrl,
                        linkType
                    ) {
                        this.referer = mainUrl
                        this.quality = quality
                    }
                )
            }
            
            // Also provide download link if available
            val downloadUrl = "$mainUrl/#$videoId&dl=1"
            Log.d(tag, "Download link: $downloadUrl")
            
        } catch (e: Exception) {
            Log.e(tag, "Error extracting hubstream: ${e.message}")
        }
    }
}

// HDStream4u.com Video Streaming Extractor (JWPlayer based)
// hdstream4u.com/file/xxx embeds packed JS directly on page with stream URLs
// Decoded packed JS format: var links = {hls2: "https://...m3u8", hls3: "https://...txt", hls4: "/stream/...m3u8"}
class HDStream4u : ExtractorApi() {
    override val name = "HDStream4u"
    override val mainUrl = "https://hdstream4u.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HDStream4u"
        Log.d(tag, "Processing URL: $url")
        
        try {
            // Extract file ID from URL (e.g., /file/5cma7x2t4a88)
            val fileIdRegex = Regex("""/file/([a-zA-Z0-9]+)""")
            val fileId = fileIdRegex.find(url)?.groupValues?.get(1)
            
            if (fileId.isNullOrEmpty()) {
                Log.e(tag, "Could not extract file ID from URL: $url")
                return
            }
            
            Log.d(tag, "Found file ID: $fileId")
            
            // Fetch hdstream4u.com page directly (packed JS is embedded on this page)
            Log.d(tag, "Fetching hdstream4u page: $url")
            
            val pageResponse = app.get(url, referer = referer ?: mainUrl, timeout = 20)
            val embedHtml = pageResponse.text
            
            Log.d(tag, "Page content length: ${embedHtml.length}")
            
            // ============================================================
            // Method 1: Extract URLs directly from unpacked/decoded content
            // Look for var links = {"hls2":"...", "hls3":"...", "hls4":"..."}
            // ============================================================
            
            // Pattern 1: Full URL with domain (hls2/hls3 format)
            val fullUrlPatterns = listOf(
                Regex(""""hls[23]"\s*:\s*"(https?://[^"]+\.(m3u8|txt)[^"]*)""""),
                Regex("""hls[23]\s*:\s*"(https?://[^"]+\.(m3u8|txt)[^"]*)""""),
                Regex(""""hls[23]"\s*:\s*\\?"(https?://[^"\\]+\.(m3u8|txt)[^"\\]*)"""),
                Regex("""(https?://[^"'\s<>]+\.acek-cdn\.com/[^"'\s<>]+master\.m3u8[^"'\s<>]*)"""),
                Regex("""(https?://[^"'\s<>]+willowgrove[^"'\s<>]+master\.txt[^"'\s<>]*)"""),
                Regex("""(https?://[^"'\s<>]+creativebranding[^"'\s<>]+master\.txt[^"'\s<>]*)""")
            )
            
            for (pattern in fullUrlPatterns) {
                val match = pattern.find(embedHtml)
                if (match != null) {
                    val streamUrl = match.groupValues[1]
                    Log.d(tag, "Found full stream URL: $streamUrl")
                    createExtractorLink(streamUrl, callback)
                    return
                }
            }
            
            // Pattern 2: Relative URL (/stream/...) for hls4
            val relativePatterns = listOf(
                Regex(""""hls4"\s*:\s*"(/stream/[^"]+master\.m3u8[^"]*)""""),
                Regex("""hls4\s*:\s*"(/stream/[^"]+)""""),
                Regex(""""hls4"\s*:\s*\\?"(/stream/[^"\\]+)""")
            )
            
            for (pattern in relativePatterns) {
                val match = pattern.find(embedHtml)
                if (match != null) {
                    val streamPath = match.groupValues[1]
                    val streamUrl = "https://minochinos.com$streamPath"
                    Log.d(tag, "Found relative stream URL, converted to: $streamUrl")
                    createExtractorLink(streamUrl, callback)
                    return
                }
            }
            
            // ============================================================
            // Method 2: Try to extract from JWPlayer source directly
            // ============================================================
            val jwSourcePatterns = listOf(
                Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*[^,]+\s*,\s*type\s*:\s*["']hls["']"""),
                Regex("""file\s*:\s*links\.(hls\d)"""),
                Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""")
            )
            
            for (pattern in jwSourcePatterns) {
                val match = pattern.find(embedHtml)
                if (match != null && match.groupValues.size > 1) {
                    val url = match.groupValues[1]
                    if (url.startsWith("http")) {
                        Log.d(tag, "Found JW source URL: $url")
                        createExtractorLink(url, callback)
                        return
                    }
                }
            }
            
            // ============================================================
            // Method 3: Use known CDN patterns with file ID
            // ============================================================
            Log.d(tag, "Trying CDN pattern construction with fileId: $fileId")
            
            // Try to construct stream URL using known patterns (this is a heuristic)
            val streamUrlFromConstruction = "https://minochinos.com/stream/$fileId/master.m3u8"
            
            // Verify if URL works by checking headers
            try {
                val testResponse = app.head(streamUrlFromConstruction, timeout = 5)
                if (testResponse.code == 200) {
                    Log.d(tag, "Constructed stream URL works: $streamUrlFromConstruction")
                    createExtractorLink(streamUrlFromConstruction, callback)
                    return
                }
            } catch (e: Exception) {
                Log.d(tag, "Constructed URL failed: ${e.message}")
            }
            
            Log.w(tag, "No stream URL found for $url")
            
        } catch (e: Exception) {
            Log.e(tag, "Error extracting hdstream4u: ${e.message}")
        }
    }
    
    private suspend fun createExtractorLink(streamUrl: String, callback: (ExtractorLink) -> Unit) {
        // Determine quality from URL or default to 1080
        val qualityRegex = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
        val quality = qualityRegex.find(streamUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1080
        
        // Determine link type
        val linkType = when {
            streamUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            streamUrl.contains(".txt", true) -> ExtractorLinkType.M3U8 // HLS with txt extension
            else -> INFER_TYPE
        }
        
        callback.invoke(
            newExtractorLink(
                name,
                "$name [Stream]",
                streamUrl,
                linkType
            ) {
                this.referer = "https://minochinos.com/"
                this.quality = quality
            }
        )
    }
}