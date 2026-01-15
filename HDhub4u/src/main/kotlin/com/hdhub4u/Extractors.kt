package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidStack
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

suspend fun getLatestUrl(url: String, source: String): String {
    val link = org.json.JSONObject(
        app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json").text
    ).optString(source)
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

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val href=app.get(url, timeout = 2000).documentLarge.select(".btn.btn-primary.btn-user.btn-success1.m-1").attr("href")
        if (href.contains("hubcloud",ignoreCase = true)) HubCloud().getUrl(href,"HubDrive",subtitleCallback,callback)
        Log.d("Phisher", "No local extractor found for:")
    }
}

class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.*"
    override val requiresReferer = false

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
        Log.d("Phisher",url)

        // Dynamic URL management - fetch latest hubcloud URL
        val latestUrl = getLatestUrl(realUrl, "hubcloud")
        val baseUrl = getBaseUrl(realUrl)
        val newUrl = realUrl.replace(baseUrl, latestUrl)

        val href = try {
            when {
                "hubcloud.php" in newUrl || "gamerxyt.com" in newUrl -> newUrl
                "/drive/" in newUrl -> {
                    // hubcloud.fyi/drive/ URLs have "Generate Direct Download Link" button
                    val driveDoc = app.get(newUrl).document
                    val downloadBtn = driveDoc.selectFirst("div.card-body h2 a.btn[href*=hubcloud.php]")?.attr("href")
                        ?: driveDoc.selectFirst("a.btn[href*=gamerxyt]")?.attr("href")
                        ?: driveDoc.selectFirst("#download")?.attr("href")
                    downloadBtn?.takeIf { it.startsWith("http") } ?: ""
                }
                else -> {
                    val rawHref = app.get(newUrl).document.select("#download").attr("href")
                    if (rawHref.startsWith("http", ignoreCase = true)) rawHref
                    else latestUrl.trimEnd('/') + "/" + rawHref.trimStart('/')
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

        val document = app.get(href).document
        val size = document.selectFirst("i#size")?.text().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()

        val headerDetails = cleanTitle(header)

        val labelExtras = buildString {
            if (headerDetails.isNotEmpty()) append("[$headerDetails]")
            if (size.isNotEmpty()) append("[$size]")
        }
        val baseQuality = getIndexQuality(header)

        document.select("div.card-body h2 a.btn").amap { element ->
            val link = element.attr("href")
            val text = element.text()
            val serverQuality = getAdjustedQuality(baseQuality, size, text)
            Log.d("Phisher",headerDetails)

            Log.d("Phisher",text)


            when {
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

                text.contains("BuzzServer", ignoreCase = true) -> {
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
                        Log.w(tag, "BuzzServer: No redirect")
                    }
                }

                text.contains("pixeldra", ignoreCase = true) || text.contains("pixel", ignoreCase = true) -> {
                    val baseUrlLink = getBaseUrl(link)
                    val finalURL = if (link.contains("download", true)) link
                    else "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"

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
                    Log.d("Phisher", "No local extractor found for:")
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