package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Dynamic Domain Configuration
 * Fetches domains from urls.json API
 */
object DomainConfig {
    private const val TAG = "DomainConfig"
    private const val URLS_JSON = "https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json"
    
    // Cached domains
    private var domains: Map<String, String> = emptyMap()
    private var fetched = false
    
    // Fallback domains
    private val fallbackDomains = mapOf(
        "hdhub4u" to "https://new2.hdhub4u.fo",
        "hubdrive" to "https://hubdrive.space",
        "hubcloud" to "https://hubcloud.foo",
        "hubstream" to "https://hubstream.art",
        "hdstream4u" to "https://hdstream4u.com",
        "hblinks" to "https://hblinks.dad",
        "hubstreamdad" to "https://4khdhub.dad"
    )
    
    /**
     * Fetch domains from urls.json (call once at startup)
     */
    suspend fun fetchDomains() {
        if (fetched) return
        fetched = true
        
        try {
            val response = app.get(URLS_JSON, timeout = 10)
            val json = JSONObject(response.text)
            val map = mutableMapOf<String, String>()
            
            json.keys().forEach { key ->
                map[key] = json.getString(key)
            }
            
            domains = map
            Log.d(TAG, "Fetched ${domains.size} domains")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch domains: ${e.message}")
            domains = fallbackDomains
        }
    }
    
    fun get(key: String): String {
        return domains[key] ?: fallbackDomains[key] ?: ""
    }
    
    fun getHost(key: String): String {
        return try {
            URI(get(key)).host ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}

/**
 * Centralized Regex Patterns for all extractors
 * Professional approach: Compile once, reuse everywhere
 */
object ExtractorPatterns {
    // Domain patterns - match multiple TLDs
    val HUBDRIVE = Regex("""(?i)hubdrive\.[a-z]+""")
    val HUBCLOUD = Regex("""(?i)(hubcloud\.[a-z]+|gamerxyt\.com|cloud\.php)""")
    val HUBCDN = Regex("""(?i)hubcdn\.[a-z]+""")
    val GADGETSWEB = Regex("""(?i)gadgetsweb\.[a-z]+""")
    val HDSTREAM4U = Regex("""(?i)(hdstream4u\.[a-z]+|vidhidepro\.[a-z]+)""")
    val HUBSTREAM = Regex("""(?i)hubstream\.[a-z]+""")
    val HBLINKS = Regex("""(?i)(hblinks\.[a-z]+|4khdhub\.[a-z]+)""")
    val PIXELDRAIN = Regex("""(?i)pixeldrain\.[a-z]+""")
    val VIDSTACK = Regex("""(?i)vidstack\.[a-z]+""")
    
    // Server button patterns (for HubCloud page)
    val FSL_PATTERN = Regex("""(?i)fsl\s*(server)?""")
    val FSLV2_PATTERN = Regex("""(?i)fslv2""")
    val GBPS_PATTERN = Regex("""(?i)(10\s*gbps|10gb)""")
    val PIXEL_PATTERN = Regex("""(?i)(pixel|pixelserver|pixeldrain)""")
    val BUZZ_PATTERN = Regex("""(?i)(buzz|buzzserver)""")
    val MEGA_PATTERN = Regex("""(?i)mega\s*server""")
    val S3_PATTERN = Regex("""(?i)s3\s*server""")
    val DOWNLOAD_PATTERN = Regex("""(?i)download""")
    
    // Quality patterns
    val QUALITY_PATTERN = Regex("""(?i)(\d{3,4})p""")
    val QUALITY_4K = Regex("""(?i)(4k|2160)""")
    val QUALITY_1080 = Regex("""(?i)1080""")
    val QUALITY_720 = Regex("""(?i)720""")
    val QUALITY_480 = Regex("""(?i)480""")
    
    // Codec patterns for smart selection
    val X264_PATTERN = Regex("""(?i)x264""")
    val X265_PATTERN = Regex("""(?i)(x265|hevc)""")
    
    // Size pattern
    val SIZE_PATTERN = Regex("""(?i)(\d+(?:\.\d+)?)\s*(GB|MB)""")
    
    // M3U8 extraction pattern
    val M3U8_PATTERN = Regex(""":\s*"(.*?m3u8.*?)"""")
    
    // Encoded URL patterns
    val ENCODED_URL_PATTERN = Regex("""r=([A-Za-z0-9+/=]+)""")
    val REURL_PATTERN = Regex("""reurl\s*=\s*"([^"]+)"""")
    
    // Video source pattern (for VidStack/Hubstream)
    val VIDEO_SOURCE = Regex(""""source":"(.*?)"""")
    val SUBTITLE_SECTION = Regex(""""subtitle":\{(.*?)\}""")
    val SUBTITLE_PATTERN = Regex(""""([^"]+)":\s*"([^"]+)"""")
    
    // Valid download link pattern
    val VALID_LINK = Regex("""(?i)https?://[^\s]*?(hubdrive|gadgetsweb|hdstream4u|hubstream|hubcloud|hubcdn|hblinks|4khdhub|gamerxyt)[^\s]*""")
    
    // Redirect pattern
    val REDIRECT_ID = Regex("""\?id=""")
    
    /**
     * Match URL to extractor type
     */
    fun matchExtractor(url: String): ExtractorType = when {
        HUBDRIVE.containsMatchIn(url) -> ExtractorType.HUBDRIVE
        GADGETSWEB.containsMatchIn(url) -> ExtractorType.GADGETSWEB
        HUBCLOUD.containsMatchIn(url) -> ExtractorType.HUBCLOUD
        HUBCDN.containsMatchIn(url) -> ExtractorType.HUBCDN
        HDSTREAM4U.containsMatchIn(url) -> ExtractorType.HDSTREAM4U
        HUBSTREAM.containsMatchIn(url) -> ExtractorType.HUBSTREAM
        HBLINKS.containsMatchIn(url) -> ExtractorType.HBLINKS
        PIXELDRAIN.containsMatchIn(url) -> ExtractorType.PIXELDRAIN
        VIDSTACK.containsMatchIn(url) -> ExtractorType.VIDSTACK
        else -> ExtractorType.HUBCLOUD
    }
    
    /**
     * Get server priority for sorting
     * Higher = Better quality/faster
     */
    fun getServerPriority(text: String): Int = when {
        // Streaming servers (highest priority)
        HDSTREAM4U.containsMatchIn(text) -> 100
        HUBSTREAM.containsMatchIn(text) -> 95
        
        // Fast download servers
        FSLV2_PATTERN.containsMatchIn(text) -> 90
        FSL_PATTERN.containsMatchIn(text) && !FSLV2_PATTERN.containsMatchIn(text) -> 85
        GBPS_PATTERN.containsMatchIn(text) -> 80
        PIXEL_PATTERN.containsMatchIn(text) -> 75
        BUZZ_PATTERN.containsMatchIn(text) -> 70
        
        // Other servers
        HUBCLOUD.containsMatchIn(text) -> 60
        HUBDRIVE.containsMatchIn(text) -> 55
        HUBCDN.containsMatchIn(text) -> 50
        MEGA_PATTERN.containsMatchIn(text) -> 45
        S3_PATTERN.containsMatchIn(text) -> 40
        else -> 30
    }
    
    /**
     * Extract quality from text
     */
    fun extractQuality(text: String): Int {
        QUALITY_PATTERN.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return when {
            QUALITY_4K.containsMatchIn(text) -> 2160
            QUALITY_1080.containsMatchIn(text) -> 1080
            QUALITY_720.containsMatchIn(text) -> 720
            QUALITY_480.containsMatchIn(text) -> 480
            else -> 0
        }
    }
    
    /**
     * Extract file size in MB
     */
    fun extractSize(text: String): Double {
        val match = SIZE_PATTERN.find(text) ?: return 0.0
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0.0
        val unit = match.groupValues[2].uppercase()
        return if (unit == "GB") value * 1024 else value
    }
    
    /**
     * Check if codec is x264 (preferred)
     */
    fun isX264(text: String): Boolean = X264_PATTERN.containsMatchIn(text)
    
    /**
     * Check if URL is a valid download link
     */
    fun isValidDownloadLink(url: String): Boolean = VALID_LINK.containsMatchIn(url)
    
    /**
     * Check if URL has redirect ID
     */
    fun hasRedirectId(url: String): Boolean = REDIRECT_ID.containsMatchIn(url)
}

enum class ExtractorType {
    HUBDRIVE, HUBCLOUD, HUBCDN, GADGETSWEB, HDSTREAM4U, HUBSTREAM, HBLINKS, PIXELDRAIN, VIDSTACK
}

// ============== EXTRACTORS ==============

class HdStream4u : VidHidePro() {
    override var mainUrl = "https://hdstream4u.com"
    
    init {
        runBlocking { DomainConfig.fetchDomains() }
        val domain = DomainConfig.get("hdstream4u")
        if (domain.isNotBlank()) mainUrl = domain
    }
}

open class VidHidePro : ExtractorApi() {
    override val name = "VidHidePro"
    override val mainUrl = "https://vidhidepro.com"
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
            if (result.contains("var links")) {
                result = result.substringAfter("var links")
            }
            result
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        } ?: return

        ExtractorPatterns.M3U8_PATTERN.findAll(script).forEach { m3u8Match ->
            generateM3u8(
                name,
                fixUrl(m3u8Match.groupValues[1]),
                referer = "$mainUrl/",
                headers = headers
            ).forEach(callback)
        }
    }

    private fun getEmbedUrl(url: String): String = when {
        url.contains("/d/") -> url.replace("/d/", "/v/")
        url.contains("/download/") -> url.replace("/download/", "/v/")
        url.contains("/file/") -> url.replace("/file/", "/v/")
        else -> url.replace("/f/", "/v/")
    }
}

open class VidStack : ExtractorApi() {
    override var name = "Vidstack"
    override var mainUrl = "https://vidstack.io"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to USER_AGENT)
        val hash = url.substringAfterLast("#").substringAfter("/")
        val baseurl = getBaseUrl(url)

        val encoded = app.get("$baseurl/api/v1/video?id=$hash", headers = headers).text.trim()

        val key = "kiemtienmua911ca"
        val ivList = listOf("1234567890oiuytr", "0123456789abcdef")

        val decryptedText = ivList.firstNotNullOfOrNull { iv ->
            try {
                AesHelper.decryptAES(encoded, key, iv)
            } catch (_: Exception) {
                null
            }
        } ?: return

        val m3u8 = ExtractorPatterns.VIDEO_SOURCE.find(decryptedText)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: return
            
        val subtitleSection = ExtractorPatterns.SUBTITLE_SECTION.find(decryptedText)?.groupValues?.get(1)

        subtitleSection?.let { section ->
            ExtractorPatterns.SUBTITLE_PATTERN.findAll(section).forEach { match ->
                val lang = match.groupValues[1]
                val rawPath = match.groupValues[2].split("#")[0]
                if (rawPath.isNotEmpty()) {
                    val path = rawPath.replace("\\/", "/")
                    val subUrl = "$mainUrl$path"
                    subtitleCallback(newSubtitleFile(lang, fixUrl(subUrl)))
                }
            }
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8.replace("https", "http"),
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.headers = mapOf("referer" to url, "Origin" to baseurl)
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            mainUrl
        }
    }
}

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

class Hubstream : VidStack() {
    override var mainUrl = "https://hubstream.art"
    
    init {
        runBlocking { DomainConfig.fetchDomains() }
        val domain = DomainConfig.get("hubstream")
        if (domain.isNotBlank()) mainUrl = domain
    }
}

class Hubstreamdad : Hblinks() {
    override var mainUrl = "https://4khdhub.dad"
    
    init {
        runBlocking { DomainConfig.fetchDomains() }
        val domain = DomainConfig.get("hubstreamdad")
        if (domain.isNotBlank()) mainUrl = domain
    }
}

open class Hblinks : ExtractorApi() {
    override val name = "Hblinks"
    override val mainUrl = "https://hblinks.dad"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        app.get(url).documentLarge.select("h3 a, h5 a, div.entry-content p a").forEach {
            val href = it.absUrl("href").ifBlank { it.attr("href") }
            if (href.isNotBlank()) {
                when (ExtractorPatterns.matchExtractor(href)) {
                    ExtractorType.HUBDRIVE -> Hubdrive().getUrl(href, name, subtitleCallback, callback)
                    ExtractorType.HUBCLOUD -> HubCloud().getUrl(href, name, subtitleCallback, callback)
                    ExtractorType.HUBCDN -> HUBCDN().getUrl(href, name, subtitleCallback, callback)
                    else -> loadSourceNameExtractor(name, href, "", Qualities.Unknown.value, subtitleCallback, callback)
                }
            }
        }
    }
}

class Hubcdnn : ExtractorApi() {
    override val name = "Hubcdn"
    override val mainUrl = "https://hubcdn.fans"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url).documentLarge.toString()
        val encoded = ExtractorPatterns.ENCODED_URL_PATTERN.find(html)?.groups?.get(1)?.value
        
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
        }
    }
}

class PixelDrainDev : PixelDrain() {
    override var mainUrl = "https://pixeldrain.dev"
}

/**
 * GadgetsWeb Extractor
 * Handles URLs like: https://gadgetsweb.xyz/?id=base64encodeddata
 * Decodes the base64 ID and routes to appropriate extractor
 */
class GadgetsWeb : ExtractorApi() {
    override val name = "GadgetsWeb"
    override val mainUrl = "https://gadgetsweb.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // URL format: gadgetsweb.xyz/?id=BASE64DATA
            val id = url.substringAfter("?id=").substringBefore("&")
            if (id.isBlank()) {
                Log.e("GadgetsWeb", "No ID found in URL: $url")
                return
            }
            
            // Decode base64 ID
            val decoded = try {
                base64Decode(id)
            } catch (e: Exception) {
                Log.e("GadgetsWeb", "Base64 decode failed: ${e.message}")
                return
            }
            
            Log.d("GadgetsWeb", "Decoded URL: $decoded")
            
            // Decoded URL might be direct link or path
            // Format can be: /path/to/file or full URL
            val finalUrl = when {
                decoded.startsWith("http") -> decoded
                decoded.startsWith("/") -> "https://hubcloud.foo$decoded"
                else -> decoded
            }
            
            // Route to appropriate extractor based on decoded URL
            when (ExtractorPatterns.matchExtractor(finalUrl)) {
                ExtractorType.HUBDRIVE -> Hubdrive().getUrl(finalUrl, name, subtitleCallback, callback)
                ExtractorType.HUBCLOUD -> HubCloud().getUrl(finalUrl, name, subtitleCallback, callback)
                ExtractorType.HUBCDN -> HUBCDN().getUrl(finalUrl, name, subtitleCallback, callback)
                ExtractorType.HDSTREAM4U -> HdStream4u().getUrl(finalUrl, name, subtitleCallback, callback)
                ExtractorType.HUBSTREAM -> Hubstream().getUrl(finalUrl, name, subtitleCallback, callback)
                ExtractorType.HBLINKS -> Hblinks().getUrl(finalUrl, name, subtitleCallback, callback)
                else -> {
                    // Direct callback if it looks like a stream URL
                    if (finalUrl.contains(".m3u8") || finalUrl.contains(".mp4")) {
                        callback(
                            newExtractorLink(
                                name,
                                name,
                                finalUrl,
                                INFER_TYPE
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    } else {
                        // Try HubCloud as default
                        HubCloud().getUrl(finalUrl, name, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GadgetsWeb", "Error processing URL: ${e.message}")
        }
    }
}


class Hubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.space"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val href = app.get(url, timeout = 5000L).documentLarge
                .select(".btn.btn-primary.btn-user.btn-success1.m-1").attr("href")
            
            if (href.isNotBlank()) {
                when (ExtractorPatterns.matchExtractor(href)) {
                    ExtractorType.HUBCLOUD -> HubCloud().getUrl(href, "HubDrive", subtitleCallback, callback)
                    else -> loadSourceNameExtractor("HubDrive", href, "", Qualities.Unknown.value, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e("Hubdrive", "Error: ${e.message}")
        }
    }
}

class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.foo"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HubCloud"
        val ref = referer.orEmpty()

        val uri = runCatching { URI(url) }.getOrElse {
            Log.e(tag, "Invalid URL: ${it.message}")
            return
        }

        val realUrl = uri.toString()
        val baseUrl = "${uri.scheme}://${uri.host}"

        // Step 1: Get download page URL
        val href = runCatching {
            if ("hubcloud.php" in realUrl || "gamerxyt.com" in realUrl) {
                realUrl
            } else {
                val doc = app.get(realUrl).document
                val raw = doc.selectFirst("#download")?.attr("href").orEmpty()
                if (raw.startsWith("http", true)) raw
                else baseUrl.trimEnd('/') + "/" + raw.trimStart('/')
            }
        }.getOrElse {
            Log.e(tag, "Failed to extract href: ${it.message}")
            ""
        }

        if (href.isBlank()) return

        // Step 2: Parse download page
        val document = app.get(href).document
        val size = document.selectFirst("i#size")?.text().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()

        val quality = ExtractorPatterns.extractQuality(header)
        val labelExtras = buildString {
            if (header.isNotEmpty()) append("[${cleanTitle(header)}]")
            if (size.isNotEmpty()) append("[$size]")
        }

        // Step 3: Extract all server buttons using regex patterns
        document.select("a.btn").forEach { element ->
            val link = element.attr("href")
            val label = element.ownText().lowercase()
            
            if (link.isBlank() || link.contains("logout")) return@forEach
            
            Log.d(tag, "Found button: $label -> $link")
            
            when {
                // FSLv2 Server (check first)
                ExtractorPatterns.FSLV2_PATTERN.containsMatchIn(label) -> {
                    callback(newExtractorLink(
                        "$ref [FSLv2]",
                        "$ref [FSLv2] $labelExtras",
                        link
                    ) { this.quality = quality })
                }
                
                // FSL Server
                ExtractorPatterns.FSL_PATTERN.containsMatchIn(label) -> {
                    callback(newExtractorLink(
                        "$ref [FSL Server]",
                        "$ref [FSL Server] $labelExtras",
                        link
                    ) { this.quality = quality })
                }
                
                // 10Gbps Server
                ExtractorPatterns.GBPS_PATTERN.containsMatchIn(label) -> {
                    try {
                        var current = link
                        repeat(3) {
                            val resp = app.get(current, allowRedirects = false)
                            val loc = resp.headers["location"] ?: return@repeat
                            if ("link=" in loc) {
                                callback(newExtractorLink(
                                    "$ref [10Gbps]",
                                    "$ref [10Gbps] $labelExtras",
                                    loc.substringAfter("link=")
                                ) { this.quality = quality })
                                return@repeat
                            }
                            current = loc
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "10Gbps error: ${e.message}")
                    }
                }
                
                // Pixeldrain Server
                ExtractorPatterns.PIXEL_PATTERN.containsMatchIn(label) -> {
                    val finalUrl = if ("download" in link || "api/file" in link) link
                    else "${getBaseUrl(link)}/api/file/${link.substringAfterLast("/")}?download"
                    
                    callback(newExtractorLink(
                        "$ref [Pixeldrain]",
                        "$ref [Pixeldrain] $labelExtras",
                        finalUrl
                    ) { this.quality = quality })
                }
                
                // Buzz Server
                ExtractorPatterns.BUZZ_PATTERN.containsMatchIn(label) -> {
                    try {
                        val resp = app.get("$link/download", referer = link, allowRedirects = false)
                        val dlink = resp.headers["hx-redirect"] ?: resp.headers["HX-Redirect"]
                        if (!dlink.isNullOrBlank()) {
                            callback(newExtractorLink(
                                "$ref [BuzzServer]",
                                "$ref [BuzzServer] $labelExtras",
                                dlink
                            ) { this.quality = quality })
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "BuzzServer error: ${e.message}")
                    }
                }
                
                // Mega Server
                ExtractorPatterns.MEGA_PATTERN.containsMatchIn(label) -> {
                    callback(newExtractorLink(
                        "$ref [Mega Server]",
                        "$ref [Mega Server] $labelExtras",
                        link
                    ) { this.quality = quality })
                }
                
                // S3 Server
                ExtractorPatterns.S3_PATTERN.containsMatchIn(label) -> {
                    callback(newExtractorLink(
                        "$ref [S3 Server]",
                        "$ref [S3 Server] $labelExtras",
                        link
                    ) { this.quality = quality })
                }
                
                // Generic download button
                ExtractorPatterns.DOWNLOAD_PATTERN.containsMatchIn(label) -> {
                    callback(newExtractorLink(
                        "$ref [Download]",
                        "$ref [Download] $labelExtras",
                        link
                    ) { this.quality = quality })
                }
            }
        }
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault("")
    }

    private fun cleanTitle(title: String): String {
        val parts = title.split(".", "-", "_")
        val qualityTags = listOf("WEBRip", "WEB-DL", "WEB", "BluRay", "HDRip", "HDTV", "CAM", "HD")
        val startIndex = parts.indexOfFirst { part -> qualityTags.any { part.contains(it, true) } }
        return if (startIndex != -1) parts.drop(startIndex).take(3).joinToString(".") 
               else parts.takeLast(3).joinToString(".")
    }
}

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
        val doc = app.get(url).documentLarge
        val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()

        val encodedUrl = ExtractorPatterns.REURL_PATTERN
            .find(scriptText ?: "")
            ?.groupValues?.get(1)
            ?.substringAfter("?r=")

        val decodedUrl = encodedUrl?.let { base64Decode(it) }?.substringAfterLast("link=")

        if (!decodedUrl.isNullOrBlank()) {
            callback(newExtractorLink(
                this.name,
                this.name,
                decodedUrl,
                INFER_TYPE
            ) { this.quality = Qualities.Unknown.value })
        }
    }
}

/**
 * Universal extractor loader - uses centralized ExtractorPatterns
 */
suspend fun loadSourceNameExtractor(
    sourceName: String,
    url: String,
    referer: String,
    quality: Int,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    when (ExtractorPatterns.matchExtractor(url)) {
        ExtractorType.HUBDRIVE -> Hubdrive().getUrl(url, referer, subtitleCallback, callback)
        ExtractorType.HUBCLOUD -> HubCloud().getUrl(url, referer, subtitleCallback, callback)
        ExtractorType.HUBCDN -> HUBCDN().getUrl(url, referer, subtitleCallback, callback)
        ExtractorType.GADGETSWEB -> GadgetsWeb().getUrl(url, referer, subtitleCallback, callback)
        ExtractorType.HDSTREAM4U -> HdStream4u().getUrl(url, referer, subtitleCallback, callback)
        ExtractorType.HUBSTREAM -> Hubstream().getUrl(url, referer, subtitleCallback, callback)
        ExtractorType.HBLINKS -> Hblinks().getUrl(url, referer, subtitleCallback, callback)
        ExtractorType.PIXELDRAIN -> PixelDrainDev().getUrl(url, referer, subtitleCallback, callback)
        ExtractorType.VIDSTACK -> VidStack().getUrl(url, referer, subtitleCallback, callback)
    }
}


// Backward compatibility
fun getServerPriority(text: String): Int = ExtractorPatterns.getServerPriority(text)
