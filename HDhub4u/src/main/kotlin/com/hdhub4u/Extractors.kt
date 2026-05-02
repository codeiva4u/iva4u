package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import org.json.JSONObject
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

val VIDEO_HEADERS = mapOf(
    "User-Agent" to "VLC/3.6.0 LibVLC/3.0.18 (Android)",
    "Accept" to "*/*",
    "Accept-Encoding" to "identity",
    "Connection" to "keep-alive",
    "Range" to "bytes=0-",
    "Icy-MetaData" to "1"
)

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
    if (cachedUrlsJson == null) {
        try {
            cachedUrlsJson = JSONObject(
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
    var score = when (quality) {
        1080 -> 1000
        2160 -> 900
        720 -> 700
        480 -> 500
        else -> 300
    }

    if (codec.contains("hevc", true) || codec.contains("x265", true) || codec.contains("h265", true) || codec.contains("h.265", true)) {
        score += 150
    } else if (codec.contains("x264", true) || codec.contains("h264", true) || codec.contains("h.264", true)) {
        score += 100
    }

    if (quality == 1080) {
        val sizeMB = parseSizeToMB(sizeStr)
        score += when {
            sizeMB <= 600 -> 90
            sizeMB <= 900 -> 75
            sizeMB <= 1200 -> 60
            sizeMB <= 1600 -> 45
            sizeMB <= 2000 -> 30
            sizeMB <= 2500 -> 15
            else -> 0
        }
    }

    score += getServerPriority(serverName)
    return score
}

/**
 * Check if URL should be blocked (streaming/non-download).
 * Blocks: hdstream4u, hubstream.art, HLS streams, Telegram, VPN pages, etc.
 */
fun shouldBlockUrl(url: String): Boolean {
    val blockList = listOf(
        ".m3u8", "/hls/", "hubstream", "hdstream",
        "hdstream4u", "t.me/", "tinyurl.com",
        "google.com/search", "one.one.one.one",
        "/tg/go"
    )
    return blockList.any { url.contains(it, ignoreCase = true) }
}

// ═══════════════════════════════════════════════════════════════════════════════════
// EXTRACTOR CLASSES
// ═══════════════════════════════════════════════════════════════════════════════════

/**
 * Hblinks Extractor - Download Aggregator Page
 *
 * Flow: hblinks.dad/archives/XXX → hubcdn.fans/file/XXX, hubcloud.foo/drive/XXX, gofile.io/d/XXX
 * Note: "hblinks" key is NOT in urls.json, so we use getBaseUrl() for hblinks.dad
 *       and "hubstreamdad" key for 4khdhub.* domains
 */
open class Hblinks : ExtractorApi() {
    override val name = "Hblinks"
    override val mainUrl = "https://(?:hblinks|4khdhub).*"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "Hblinks"
        Log.d(tag, "Processing: $url")

        // hblinks.dad → use as-is (not in urls.json)
        // 4khdhub.* → use hubstreamdad key from urls.json
        val latestUrl = if (url.contains("4khdhub", true)) {
            getLatestUrl(url, "hubstreamdad")
        } else {
            getBaseUrl(url)
        }
        val baseUrl = getBaseUrl(url)
        val newUrl = url.replace(baseUrl, latestUrl)

        try {
            val doc = app.get(newUrl).document
            val links = doc.select("a[href*='hubcloud'], a[href*='hubcdn'], a[href*='hubdrive'], a[href*='pixeldrain'], a[href*='gofile.io']")

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
                        href.contains("hubcdn", true) ->
                            HUBCDN().getUrl(href, name, subtitleCallback, callback)
                        href.contains("pixeldrain", true) || href.contains("gofile.io", true) ->
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


    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "Hubdrive"
        Log.d(tag, "Processing: $url")

        val latestUrl = getLatestUrl(url, "hubdrive")
        val baseUrl = getBaseUrl(url)
        val newUrl = url.replace(baseUrl, latestUrl)

        try {
            val doc = app.get(newUrl).documentLarge

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
 * HubCloud Extractor - Main Download Server
 *
 * NEW Flow (2026):
 * Step 1: hubcloud.foo/drive/XXX → find a#download with ?token= URL
 * Step 2: hubcloud.foo/drive/XXX?token=TOKEN → actual download buttons:
 *         - FSLv2 (fsl.gigabytes.icu / cdn.fsl-buckets.life)
 *         - FSL (hub.diskcdn.buzz)
 *         - PixelServer (pixeldrain.dev)
 *         - 10Gbps (pixel.hubcdn.fans)
 */
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

        if (shouldBlockUrl(url)) {
            Log.d(tag, "BLOCKED streaming URL: $url")
            return
        }

        val isValidUrl = try {
            URI(url).toURL()
            true
        } catch (_: Exception) { false }
        if (!isValidUrl) return

        val latestUrl = getLatestUrl(url, "hubcloud")
        val currentBaseUrl = getBaseUrl(url)
        val newUrl = url.replace(currentBaseUrl, latestUrl)

        Log.d(tag, "Processing: $newUrl")

        try {
            // ═══════════════════════════════════════════════════════════
            // Step 1: Get drive page and find token URL
            // ═══════════════════════════════════════════════════════════
            val driveDoc = app.get(newUrl).document
            val driveHtml = driveDoc.html()

            // Extract file info
            val header = driveDoc.selectFirst("div.card-header")?.text() ?: ""
            val size = driveDoc.selectFirst("i#size")?.text() ?: ""

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

            var tokenUrl = ""

            // If URL already has ?token=, use directly
            if (newUrl.contains("?token=")) {
                tokenUrl = newUrl
            }

            // Find a#download link with token
            if (tokenUrl.isBlank()) {
                val downloadHref = driveDoc.selectFirst("a#download")?.attr("href") ?: ""
                if (downloadHref.isNotBlank() && downloadHref.contains("token=")) {
                    tokenUrl = if (downloadHref.startsWith("http")) downloadHref
                    else latestUrl.trimEnd('/') + "/" + downloadHref.trimStart('/')
                }
            }

            // Extract from JS: var url = '/drive/XXX?token=TOKEN'
            if (tokenUrl.isBlank()) {
                val jsPattern = Regex("""var\s+url\s*=\s*['"]([^'"]*\?token=[^'"]+)['"]""")
                val jsMatch = jsPattern.find(driveHtml)
                if (jsMatch != null) {
                    val jsUrl = jsMatch.groupValues[1]
                    tokenUrl = if (jsUrl.startsWith("http")) jsUrl
                    else latestUrl.trimEnd('/') + "/" + jsUrl.trimStart('/')
                }
            }

            // Handle search-recover.php redirect
            if (tokenUrl.isBlank() && newUrl.contains("search-recover.php", true)) {
                try {
                    val qMatch = Regex("""Q_INITIAL\s*=\s*"([^"]+)"""").find(driveHtml)
                    val qInitial = qMatch?.groupValues?.get(1) ?: ""
                    val fromAc = newUrl.substringAfter("from_ac=").substringBefore("&")

                    if (qInitial.isNotEmpty() && !qInitial.contains("<html") && fromAc.isNotEmpty()) {
                        val apiLink = "$latestUrl/drive/search-recover.php?api=search&q=${java.net.URLEncoder.encode(qInitial, "UTF-8")}&from_ac=$fromAc"
                        val jsonResponse = app.get(apiLink, headers = mapOf("Accept" to "application/json")).text
                        val hits = JSONObject(jsonResponse).optJSONArray("hits")
                        if (hits != null && hits.length() > 0) {
                            val firstUrl = hits.optJSONObject(0)?.optString("url")
                            if (!firstUrl.isNullOrEmpty()) {
                                HubCloud().getUrl(firstUrl, referer, subtitleCallback, callback)
                                return
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "search-recover error: ${e.message}")
                }
            }

            if (tokenUrl.isBlank()) {
                Log.w(tag, "No token URL found for: $newUrl")
                return
            }

            Log.d(tag, "Token URL: $tokenUrl")

            // ═══════════════════════════════════════════════════════════
            // Step 2: Fetch token page and extract download buttons
            // ═══════════════════════════════════════════════════════════
            val document = app.get(tokenUrl).document

            // Re-extract file info if not found earlier
            val finalHeader = header.ifEmpty { document.selectFirst("div.card-header")?.text() ?: "" }
            val finalSize = size.ifEmpty { document.selectFirst("i#size")?.text() ?: "" }
            val finalLabel = labelExtras.ifEmpty {
                buildString {
                    if (finalHeader.isNotEmpty()) append("[${cleanTitle(finalHeader)}]")
                    if (finalSize.isNotEmpty()) append("[$finalSize]")
                }
            }

            // Process download buttons
            document.select("div.card-body a, a.btn").amap { element ->
                val link = element.attr("href")
                val text = element.text()

                if (link.isBlank() || !link.startsWith("http")) return@amap
                if (shouldBlockUrl(link)) return@amap

                // Skip non-download buttons
                val skipTexts = listOf("Telegram", "IDM", "IDA", "VPN", "Tutorial", "Copy", "Login", "Create", "How", "Report")
                if (skipTexts.any { text.contains(it, true) }) return@amap

                // Skip ZipDisk server
                if (text.contains("ZipDisk", true) || link.contains("zipdisk", true) ||
                    link.endsWith(".zip", true) || link.contains("cloudserver", true)) {
                    Log.d(tag, "SKIPPED ZipDisk: $link")
                    return@amap
                }

                val score = calculateQualityScore(quality, finalSize, text, codec)

                try {
                    when {
                        // FSLv2 Server
                        text.contains("FSLv2", true) || link.contains("fsl-buckets", true) ||
                                link.contains("fsl.gigabytes", true) -> {
                            Log.d(tag, "FSLv2: $link")
                            callback(newExtractorLink(
                                "$name [FSLv2]",
                                "$name [FSLv2] $finalLabel",
                                link
                            ) {
                                this.quality = score + 20
                                this.headers = VIDEO_HEADERS
                            })
                        }

                        // FSL Server (hub.diskcdn.buzz) - check before generic "FSL"
                        link.contains("diskcdn.buzz", true) ||
                                (text.contains("FSL", true) && !text.contains("FSLv2", true)) -> {
                            Log.d(tag, "FSL: $link")
                            callback(newExtractorLink(
                                "$name [FSL]",
                                "$name [FSL] $finalLabel",
                                link
                            ) {
                                this.quality = score + 15
                                this.headers = VIDEO_HEADERS
                            })
                        }

                        // 10Gbps Server (pixel.hubcdn.fans/?id=)
                        text.contains("10Gbps", true) ||
                                (link.contains("pixel.hubcdn", true) && link.contains("?id=")) -> {
                            Log.d(tag, "10Gbps: $link")
                            try {
                                val dlink = app.get(link, allowRedirects = false).headers["location"] ?: ""
                                val finalUrl = if (dlink.contains("link=")) dlink.substringAfter("link=") else dlink
                                callback(newExtractorLink(
                                    "10Gbps", "10Gbps $finalLabel", finalUrl
                                ) {
                                    this.quality = score + 10
                                    this.headers = VIDEO_HEADERS
                                })
                            } catch (e: Exception) {
                                Log.e(tag, "10Gbps redirect error: ${e.message}")
                            }
                        }

                        // PixelDrain (pixeldrain.dev/u/XXX)
                        link.contains("pixeldrain", true) -> {
                            Log.d(tag, "PixelDrain: $link")
                            val finalURL = if (link.contains("/u/")) {
                                "${getBaseUrl(link)}/api/file/${link.substringAfterLast("/")}?download"
                            } else link
                            callback(newExtractorLink(
                                "Pixeldrain", "Pixeldrain $finalLabel", finalURL
                            ) {
                                this.quality = score
                                this.headers = VIDEO_HEADERS
                            })
                        }

                        // Generic Download button (catch new server types)
                        text.contains("Download", true) && !link.contains("google.com", true) -> {
                            Log.d(tag, "Download: $link")
                            callback(newExtractorLink(
                                "$name", "$name $finalLabel", link
                            ) {
                                this.quality = score
                                this.headers = VIDEO_HEADERS
                            })
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error processing: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }

    private fun cleanTitle(title: String): String {
        val parts = title.split(".", "-", "_")
        val qualityTags = listOf("WEBRip", "WEB-DL", "WEB", "BluRay", "HDRip", "DVDRip", "HDTV", "HD")

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
 * HUBCDN Extractor - Instant Download & Legacy hubcdn
 *
 * NEW Flow (2026):
 * hubcdn.fans/file/XXX → redirects to hubcdn.fans/dl/?link=FINAL_URL
 * Parse final URL from redirect or page "Download Here" link
 */
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

        val latestUrl = getLatestUrl(url, "hubcdn")
        val baseUrl = getBaseUrl(url)
        val newUrl = url.replace(baseUrl, latestUrl)

        Log.d(tag, "Processing: $newUrl")

        try {
            when {
                // hubcdn.fans/file/XXX - Instant download (primary flow)
                newUrl.contains("/file/") -> {
                    Log.d(tag, "Instant download: $newUrl")

                    // Follow redirects - final URL will be hubcdn.fans/dl/?link=FINAL_URL
                    val response = app.get(newUrl, allowRedirects = true)
                    val finalPageUrl = response.url
                    val doc = response.document

                    var downloadUrl: String? = null

                    // Method 1: Extract from redirect URL parameter (?link=)
                    if (finalPageUrl.contains("link=")) {
                        downloadUrl = java.net.URLDecoder.decode(
                            finalPageUrl.substringAfter("link="), "UTF-8"
                        )
                        Log.d(tag, "Got URL from redirect param: $downloadUrl")
                    }

                    // Method 2: Find "Download Here" link on the dl page
                    if (downloadUrl.isNullOrBlank()) {
                        val dlLink = doc.selectFirst("a[href]:contains(Download Here)")?.attr("href")
                            ?: doc.selectFirst("a.btn[href^=http]")?.attr("href")
                        if (!dlLink.isNullOrBlank() && !dlLink.contains("hubcdn.fans")) {
                            downloadUrl = dlLink
                            Log.d(tag, "Got URL from Download Here link: $downloadUrl")
                        }
                    }

                    // Method 3: Legacy - try reurl JS variable
                    if (downloadUrl.isNullOrBlank()) {
                        val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()
                        val encodedUrl = Regex("""reurl\s*=\s*"([^"]+)"""")
                            .find(scriptText ?: "")?.groupValues?.get(1)?.substringAfter("?r=")
                        if (encodedUrl != null) {
                            try {
                                downloadUrl = com.lagradost.cloudstream3.base64Decode(encodedUrl)
                                    .substringAfterLast("link=")
                                Log.d(tag, "Got URL from reurl JS: $downloadUrl")
                            } catch (_: Exception) {}
                        }
                    }

                    if (!downloadUrl.isNullOrBlank() && downloadUrl.startsWith("http")) {
                        callback(newExtractorLink(
                            "Instant DL",
                            "Instant DL [HUBCDN]",
                            downloadUrl,
                            INFER_TYPE
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = VIDEO_HEADERS
                        })
                    } else {
                        Log.w(tag, "Failed to extract download URL from: $newUrl")
                    }
                }

                // hubcdn with hubcloud link fallback
                newUrl.contains("hubcdn") -> {
                    val doc = app.get(newUrl).document

                    // Try reurl JS variable first
                    val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()
                    val encodedUrl = Regex("""reurl\s*=\s*"([^"]+)"""")
                        .find(scriptText ?: "")?.groupValues?.get(1)?.substringAfter("?r=")

                    var decodedUrl: String? = null
                    if (encodedUrl != null) {
                        try {
                            decodedUrl = com.lagradost.cloudstream3.base64Decode(encodedUrl)
                                .substringAfterLast("link=")
                        } catch (_: Exception) {}
                    }

                    if (!decodedUrl.isNullOrBlank()) {
                        callback(newExtractorLink(
                            this.name,
                            this.name,
                            decodedUrl,
                            INFER_TYPE
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = VIDEO_HEADERS
                        })
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
