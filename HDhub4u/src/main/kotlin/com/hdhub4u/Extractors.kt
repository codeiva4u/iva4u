package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

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

class HdStream4u : VidHidePro() {
    override var mainUrl = "https://hdstream4u.*"
}

class Hubstream : VidStack() {
    override var mainUrl = "https://hubstream.*"
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
                else -> loadSourceNameExtractor(name, href, "", Qualities.Unknown.value,subtitleCallback, callback)
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
                    // Use CloudflareKiller to bypass protection
                    val driveDoc = app.get(urlToUse, interceptor = cfKiller, timeout = 10).document
                    
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
        // Skip /file/ URLs - they are ad redirect pages with no video content
        if ("/file/" in url) {
            Log.d("HUBCDN", "Skipping /file/ URL (ad page): $url")
            return
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