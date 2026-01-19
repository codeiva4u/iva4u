package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
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

open class VidHidePro : ExtractorApi() {
    override val name = "VidHidePro"
    override val mainUrl = "https://vidhidepro.*"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
        )

        val response = app.get(getEmbedUrl(url), referer = referer)
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            var result = getAndUnpack(response.text)
            if(result.contains("var links")){
                result = result.substringAfter("var links")
            }
            result
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        } ?: return

        // m3u8 urls could be prefixed by 'file:', 'hls2:' or 'hls4:', so we just match ':'
        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { m3u8Match ->
            generateM3u8(
                name,
                fixUrl(m3u8Match.groupValues[1]),
                referer = "$mainUrl/",
                headers = headers
            ).forEach(callback)
        }
    }

    private fun getEmbedUrl(url: String): String {
        return when {
            url.contains("/d/") -> url.replace("/d/", "/v/")
            url.contains("/download/") -> url.replace("/download/", "/v/")
            url.contains("/file/") -> url.replace("/file/", "/v/")
            else -> url.replace("/f/", "/v/")
        }
    }
}

// HdStream4u extends VidHidePro - same player type
class HdStream4u : VidHidePro() {
    override val name = "HdStream4u"
    override val mainUrl = "https://hdstream4u.com"
}

// Working VidStack implementation with AES decryption
open class VidStackExtractor : ExtractorApi() {
    override var name = "Vidstack"
    override var mainUrl = "https://vidstack.io"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")
        val hash = url.substringAfterLast("#").substringAfter("/")
        val baseurl = getVidstackBaseUrl(url)

        Log.d("VidStack", "Processing: $url, hash: $hash, baseurl: $baseurl")

        val encoded = app.get("$baseurl/api/v1/video?id=$hash", headers = headers).text.trim()

        val key = "kiemtienmua911ca"
        val ivList = listOf("1234567890oiuytr", "0123456789abcdef")

        val decryptedText = ivList.firstNotNullOfOrNull { iv ->
            try {
                AesHelper.decryptAES(encoded, key, iv)
            } catch (_: Exception) {
                null
            }
        } ?: throw Exception("Failed to decrypt with all IVs")

        Log.d("VidStack", "Decrypted: ${decryptedText.take(200)}")

        val m3u8 = Regex("\"source\":\"(.*?)\"").find(decryptedText)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: ""
        
        // Extract subtitles
        val subtitlePattern = Regex("\"([^\"]+)\":\\s*\"([^\"]+)\"")
        val subtitleSection = Regex("\"subtitle\":\\{(.*?)\\}").find(decryptedText)?.groupValues?.get(1)

        subtitleSection?.let { section ->
            subtitlePattern.findAll(section).forEach { match ->
                val lang = match.groupValues[1]
                val rawPath = match.groupValues[2].split("#")[0]
                if (rawPath.isNotEmpty()) {
                    val path = rawPath.replace("\\/", "/")
                    val subUrl = "$mainUrl$path"
                    subtitleCallback(newSubtitleFile(lang, fixUrl(subUrl)))
                }
            }
        }

        if (m3u8.isNotBlank()) {
            Log.d("VidStack", "Found M3U8: $m3u8")
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8.replace("https", "http"),
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.headers = mapOf("referer" to url, "Origin" to url.substringAfterLast("/"))
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    private fun getVidstackBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            Log.e("Vidstack", "getBaseUrl fallback: ${e.message}")
            mainUrl
        }
    }
}

// AES Helper for decryption
object AesHelper {
    private const val TRANSFORMATION = "AES/CBC/PKCS5PADDING"

    fun decryptAES(inputHex: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray(Charsets.UTF_8))

        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        val decryptedBytes = cipher.doFinal(inputHex.hexToByteArray())
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have an even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

// Hubstream uses VidStack API
class Hubstream : VidStackExtractor() {
    override var mainUrl = "https://hubstream.*"
    override var name = "Hubstream"

}

open class Hblinks : ExtractorApi() {
    override val name = "Hblinks"
    override val mainUrl = "https://hblinks.*"
    override val requiresReferer = true
    
    // Pattern-based detection (domain-agnostic)
    private val cloudPatterns = listOf("hubdrive", "hubcloud", "hubcdn", "drive", "cloud", "cdn")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Follow redirects first
        val finalUrl = try {
            app.get(url, allowRedirects = true, timeout = 30).url
        } catch (e: Exception) { url }
        
        app.get(finalUrl).documentLarge.select("h3 a,h5 a,div.entry-content p a").map {
            val href = it.absUrl("href").ifBlank { it.attr("href") }
            val hrefLower = href.lowercase()
            
            // Pattern-based extractor selection
            when {
                cloudPatterns.any { p -> "hubdrive" in hrefLower && p == "hubdrive" } -> 
                    Hubdrive().getUrl(href, name, subtitleCallback, callback)
                cloudPatterns.any { p -> "hubcloud" in hrefLower && p == "hubcloud" } -> 
                    HubCloud().getUrl(href, name, subtitleCallback, callback)
                cloudPatterns.any { p -> "hubcdn" in hrefLower && p == "hubcdn" } -> 
                    HUBCDN().getUrl(href, name, subtitleCallback, callback)
                cloudPatterns.any { p -> ("drive" in hrefLower || "cloud" in hrefLower) && !hrefLower.contains("hubdrive") } -> 
                    HubCloud().getUrl(href, name, subtitleCallback, callback)
                else -> loadSourceNameExtractor(name, href, "", Qualities.Unknown.value, subtitleCallback, callback)
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
    
    // Pattern-based link detection (domain-agnostic)
    private val cloudPatterns = listOf("hubcloud", "cloud", "drive", "download")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "Hubdrive"
        
        // Follow redirects automatically
        val finalUrl = try {
            app.get(url, allowRedirects = true, interceptor = cfKiller, timeout = 30).url
        } catch (e: Exception) {
            url
        }
        Log.d(tag, "Final URL: $finalUrl")
        
        val doc = app.get(finalUrl, timeout = 30000, interceptor = cfKiller).documentLarge
        
        // Pattern-based selector - find any button with cloud/drive patterns
        var href = doc.select("a.btn, a[href*=cloud], a[href*=drive]").firstOrNull { btn ->
            val h = btn.attr("href")
            cloudPatterns.any { h.contains(it, true) } && h.startsWith("http")
        }?.attr("href") ?: ""
        
        // Fallback: original selectors
        if (href.isBlank()) {
            href = doc.select(".btn.btn-primary.btn-user.btn-success1.m-1").attr("href")
        }
        
        Log.d(tag, "Found href: $href")
        
        if (href.isNotBlank() && href.startsWith("http")) {
            // Follow redirect on extracted link
            val redirectedHref = try {
                app.get(href, allowRedirects = true, timeout = 30).url
            } catch (e: Exception) { href }
            
            HubCloud().getUrl(redirectedHref, name, subtitleCallback, callback)
        } else {
            Log.d(tag, "No cloud link found for: $url")
        }
    }
}

class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.*"
    override val requiresReferer = false

    // Cloudflare bypass
    private val cfKiller by lazy { CloudflareKiller() }
    
    // Domain-agnostic patterns for download page detection
    private val downloadPagePatterns = listOf(
        "hubcloud.php",
        "/download",
        "php?",
        "drive.php"
    )
    
    // CDN patterns for direct download links
    private val cdnPatterns = listOf(
        "fsl", "pixel", "r2.dev", "gigabytes", "buzz", 
        "cdn", "download", "stream", "file"
    )

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

        // Follow redirects automatically to get final URL
        val finalUrl = try {
            val response = app.get(realUrl, allowRedirects = true, interceptor = cfKiller, timeout = 30)
            response.url  // Get the final redirected URL
        } catch (e: Exception) {
            Log.w(tag, "Redirect follow failed: ${e.message}")
            realUrl
        }
        Log.d(tag, "Final URL after redirects: $finalUrl")

        val href = try {
            when {
                // Pattern-based detection: if URL contains download page pattern, use directly
                downloadPagePatterns.any { finalUrl.contains(it, true) } -> finalUrl
                
                "/drive/" in finalUrl -> {
                    // Drive page - find download button link (domain-agnostic)
                    val driveDoc = app.get(finalUrl, interceptor = cfKiller, timeout = 30).document
                    
                    // Look for download buttons by pattern, not specific domain
                    val downloadBtn = driveDoc.select("a.btn, a[href*=php], a#download, a.btn-primary").firstOrNull { btn ->
                        val href = btn.attr("href")
                        downloadPagePatterns.any { href.contains(it, true) } || 
                        href.startsWith("http")
                    }?.attr("href")
                    
                    Log.d(tag, "Drive page button found: $downloadBtn")
                    
                    if (!downloadBtn.isNullOrBlank() && downloadBtn.startsWith("http")) {
                        // Follow redirect on button link too
                        try {
                            val btnResponse = app.get(downloadBtn, allowRedirects = true, interceptor = cfKiller, timeout = 30)
                            btnResponse.url
                        } catch (e: Exception) {
                            downloadBtn
                        }
                    } else {
                        // Fallback: find any CDN link
                        val cdnLink = driveDoc.select("a.btn, a[href^=http]").firstOrNull { btn ->
                            val h = btn.attr("href")
                            cdnPatterns.any { h.contains(it, true) }
                        }?.attr("href")
                        cdnLink ?: ""
                    }
                }
                else -> {
                    val doc = app.get(finalUrl, interceptor = cfKiller, timeout = 30).document
                    val rawHref = doc.selectFirst("#download, a.btn-primary, a.download-btn, a[href*=download]")?.attr("href") ?: ""
                    if (rawHref.startsWith("http", ignoreCase = true)) {
                        // Follow redirect
                        try {
                            app.get(rawHref, allowRedirects = true, timeout = 30).url
                        } catch (e: Exception) { rawHref }
                    } else if (rawHref.isNotBlank()) {
                        getBaseUrl(finalUrl).trimEnd('/') + "/" + rawHref.trimStart('/')
                    } else ""
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

                // FSL/FSLv2 patterns moved to generic CDN block at the end
                // This keeps specific text-based detection as fallback

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

                // BuzzServer - pattern-based (any buzz-related domain)
                link.contains("buzz", ignoreCase = true) || 
                text.contains("BuzzServer", ignoreCase = true) -> {
                    try {
                        // Follow redirect automatically
                        val buzzResp = app.get("$link/download", referer = link, allowRedirects = true, timeout = 30)
                        val finalLink = buzzResp.url.ifBlank { link }
                        callback.invoke(
                            newExtractorLink(
                                "$referer [BuzzServer]",
                                "$referer [BuzzServer] $labelExtras",
                                finalLink,
                            ) { this.quality = serverQuality }
                        )
                    } catch (e: Exception) {
                        // Fallback to direct link
                        callback.invoke(
                            newExtractorLink(
                                "$referer [BuzzServer]",
                                "$referer [BuzzServer] $labelExtras",
                                link,
                            ) { this.quality = serverQuality }
                        )
                    }
                }

                // PixelDrain - pattern-based detection (any pixel/drain URL)
                link.contains("pixel", ignoreCase = true) || 
                link.contains("drain", ignoreCase = true) ||
                text.contains("pixel", ignoreCase = true) -> {
                    // Follow redirects to get final URL
                    val finalURL = try {
                        val redirectResp = app.get(link, allowRedirects = true, timeout = 30)
                        val finalRedirectUrl = redirectResp.url
                        
                        // Convert /u/ID to /api/file/ID?download if pixeldrain
                        if (finalRedirectUrl.contains("/u/") && finalRedirectUrl.contains("pixel", true)) {
                            val baseUrlLink = getBaseUrl(finalRedirectUrl)
                            "$baseUrlLink/api/file/${finalRedirectUrl.substringAfterLast("/")}?download"
                        } else if (finalRedirectUrl.contains("download", true)) {
                            finalRedirectUrl
                        } else {
                            val baseUrlLink = getBaseUrl(finalRedirectUrl)
                            "$baseUrlLink/api/file/${finalRedirectUrl.substringAfterLast("/")}?download"
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "Pixel redirect failed: ${e.message}")
                        link
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
                    // Follow redirects automatically
                    try {
                        val response = app.get(link, allowRedirects = true, timeout = 30)
                        val finalLink = if (response.url.contains("link=")) {
                            response.url.substringAfter("link=")
                        } else {
                            response.url
                        }
                        callback.invoke(
                            newExtractorLink(
                                "10Gbps [Download]",
                                "10Gbps [Download] $labelExtras",
                                finalLink
                            ) { this.quality = serverQuality }
                        )
                    } catch (e: Exception) {
                        Log.e(tag, "10Gbps failed: ${e.message}")
                    }
                }

                // CDN patterns - generic pattern matching for any CDN/download server
                link.contains("fsl", ignoreCase = true) ||
                link.contains("cdn", ignoreCase = true) ||
                link.contains("r2.dev", ignoreCase = true) ||
                link.contains("gigabytes", ignoreCase = true) -> {
                    // Determine server type from URL pattern
                    val serverType = when {
                        link.contains("fsl", true) && !link.contains("v2", true) -> "FSL Server"
                        link.contains("v2", true) || link.contains("r2.dev", true) -> "FSLv2"
                        else -> "CDN"
                    }
                    val bonus = if (serverType == "FSLv2") 20 else 15
                    
                    callback.invoke(
                        newExtractorLink(
                            "$referer [$serverType]",
                            "$referer [$serverType] $labelExtras",
                            link,
                        ) { this.quality = serverQuality + bonus }
                    )
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
    
    // Pattern-based cloud detection (domain-agnostic)
    private val cloudPatterns = listOf("hubcloud", "cloud", "drive", "cdn", "download")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HUBCDN"
        
        // Follow redirects first
        val finalUrl = try {
            app.get(url, allowRedirects = true, timeout = 30).url
        } catch (e: Exception) { url }
        
        // hdstream4u.com /file/ URLs - redirect to HdStream4u extractor
        if ("/file/" in finalUrl && finalUrl.contains("hdstream4u", ignoreCase = true)) {
            Log.d(tag, "Redirecting to HdStream4u: $finalUrl")
            HdStream4u().getUrl(finalUrl, referer, subtitleCallback, callback)
            return
        }

        val doc = app.get(finalUrl).documentLarge
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
            // Fallback: Pattern-based cloud link detection
            Log.d(tag, "var reurl not found, trying pattern-based fallback")
            try {
                val fallbackDoc = app.get(finalUrl, allowRedirects = true).document
                
                // Find any cloud/drive link using patterns
                val cloudLink = fallbackDoc.select("a[href^=http]").firstOrNull { link ->
                    val href = link.attr("href")
                    cloudPatterns.any { href.contains(it, true) }
                }?.attr("href")

                if (!cloudLink.isNullOrBlank()) {
                    // Follow redirect on found link
                    val redirectedLink = try {
                        app.get(cloudLink, allowRedirects = true, timeout = 30).url
                    } catch (e: Exception) { cloudLink }
                    
                    Log.d(tag, "Found cloud link: $redirectedLink")
                    HubCloud().getUrl(redirectedLink, referer, subtitleCallback, callback)
                } else {
                    Log.e(tag, "No cloud link found for: $finalUrl")
                }
            } catch (e: Exception) {
                Log.e(tag, "Fallback failed: ${e.message}")
            }
        }
    }
}

// PixelDrain extractor for pixeldrain.dev links
class PixelDrainDev : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Convert /u/ID to /api/file/ID?download
        val fileId = url.substringAfterLast("/")
        val baseUrl = getBaseUrl(url)
        val downloadUrl = "$baseUrl/api/file/$fileId?download"
        
        callback(
            newExtractorLink(
                this.name,
                this.name,
                downloadUrl,
                INFER_TYPE
            ) {
                this.quality = Qualities.Unknown.value
            }
        )
    }
}