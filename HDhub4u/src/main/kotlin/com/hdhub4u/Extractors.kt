package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import org.json.JSONObject
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

fun getBaseUrl(url: String): String {
    return URI(url).let {
        "${it.scheme}://${it.host}"
    }
}

/**
 * UrlManager - Dynamic URL fetching from urls.json API
 */
object UrlManager {
    private var cachedUrls: Map<String, String>? = null
    private var lastFetch: Long = 0
    private const val CACHE_DURATION = 30 * 60 * 1000L // 30 minutes
    private const val API_URL = "https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json"
    
    suspend fun getUrl(source: String, fallback: String): String {
        val now = System.currentTimeMillis()
        
        if (cachedUrls != null && now - lastFetch < CACHE_DURATION) {
            return cachedUrls?.get(source) ?: fallback
        }
        
        return try {
            val response = app.get(API_URL, timeout = 10)
            val json = JSONObject(response.text)
            val urls = mutableMapOf<String, String>()
            json.keys().forEach { key -> urls[key] = json.optString(key) }
            cachedUrls = urls
            lastFetch = now
            Log.d("UrlManager", "Fetched ${urls.size} URLs from API")
            urls[source] ?: fallback
        } catch (e: Exception) {
            Log.w("UrlManager", "Failed to fetch urls.json: ${e.message}")
            fallback
        }
    }
    
    fun clearCache() {
        cachedUrls = null
        lastFetch = 0
    }
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

// Parse file size string to MB
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

// Server speed priority
fun getServerPriority(serverName: String): Int {
    return when {
        serverName.contains("Instant", true) -> 100
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

// Adjust quality for sorting
fun getAdjustedQuality(quality: Int, sizeStr: String, serverName: String = ""): Int {
    var adjustedQuality = quality
    
    if (quality == 1080) {
        val sizeMB = parseSizeToMB(sizeStr)
        val sizeBonus = when {
            sizeMB <= 1000 -> 50
            sizeMB <= 1500 -> 40
            sizeMB <= 2000 -> 30
            sizeMB <= 3000 -> 20
            else -> 10
        }
        adjustedQuality += sizeBonus
    }
    
    adjustedQuality += getServerPriority(serverName)
    return adjustedQuality
}

// ==================== VidHidePro Extractor ====================
class HdStream4u : VidHidePro() {
    override var mainUrl = "https://hdstream4u.com"
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
            if(result.contains("var links")){
                result = result.substringAfter("var links")
            }
            result
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        } ?: return

        Regex(""":\s*"(.*?m3u8.*?)"""").findAll(script).forEach { m3u8Match ->
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

// ==================== VidStack Extractor ====================
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
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")
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
        } ?: throw Exception("Failed to decrypt with all IVs")

        val m3u8 = Regex(""""source":"(.*?)"""").find(decryptedText)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: ""
            
        val subtitlePattern = Regex(""""([^"]+)":\s*"([^"]+)"""")
        val subtitleSection = Regex(""""subtitle":\{(.*?)\}""").find(decryptedText)?.groupValues?.get(1)

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

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8.replace("https","http"),
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.headers = mapOf("referer" to url, "Origin" to url.substringAfterLast("/"))
                this.quality = Qualities.Unknown.value
            }
        )
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

// ==================== PixelDrain Extractor ====================
open class PixelDrain : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileId = url.substringAfterLast("/")
        val baseUrl = getBaseUrl(url)
        val downloadUrl = "$baseUrl/api/file/$fileId?download"
        
        callback.invoke(
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

class PixelDrainDev : PixelDrain() {
    override var mainUrl = "https://pixeldrain.dev"
}

// ==================== Hblinks Extractor ====================
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
        app.get(url).document.select("h3 a, h5 a, div.entry-content p a, a.btn").amap {
            val href = it.absUrl("href").ifBlank { it.attr("href") }
            if (href.isBlank() || href.startsWith("#")) return@amap
            
            Log.d("Hblinks", "Processing link: $href")
            
            when {
                "hubdrive" in href.lowercase() -> Hubdrive().getUrl(href, name, subtitleCallback, callback)
                "hubcloud" in href.lowercase() -> HubCloud().getUrl(href, name, subtitleCallback, callback)
                "hubcdn" in href.lowercase() -> HUBCDN().getUrl(href, name, subtitleCallback, callback)
                "hdstream4u" in href.lowercase() || "vidhide" in href.lowercase() -> {
                    HdStream4u().getUrl(href, name, subtitleCallback, callback)
                }
                "pixeldrain" in href.lowercase() -> {
                    PixelDrain().getUrl(href, name, subtitleCallback, callback)
                }
                href.startsWith("http") -> {
                    loadSourceNameExtractor(name, href, "", Qualities.Unknown.value, subtitleCallback, callback)
                }
            }
        }
    }
}

// ==================== Hubcdn Extractor ====================
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
                Log.e("Hubcdnn", "Encoded URL not found")
            }
        }
    }
}

// ==================== Hubdrive Extractor ====================
class Hubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.space"
    override val requiresReferer = false

    private val cfKiller by lazy { CloudflareKiller() }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, timeout = 15, interceptor = cfKiller).document
            
            // Try multiple selectors
            var href = doc.select(".btn.btn-primary.btn-user.btn-success1.m-1").attr("href")
            
            if (href.isBlank()) {
                href = doc.selectFirst("a.btn[href*=hubcloud]")?.attr("href") ?: ""
            }
            if (href.isBlank()) {
                href = doc.selectFirst("a[href*=hubcloud]")?.attr("href") ?: ""
            }
            if (href.isBlank()) {
                // Try to find any download button
                href = doc.select("a.btn").firstOrNull { 
                    it.attr("href").contains("hubcloud", true) || 
                    it.attr("href").contains("drive", true)
                }?.attr("href") ?: ""
            }
            
            Log.d("Hubdrive", "Found href: $href")
            
            if (href.contains("hubcloud", ignoreCase = true)) {
                HubCloud().getUrl(href, referer ?: "HubDrive", subtitleCallback, callback)
            } else if (href.isNotBlank() && href.startsWith("http")) {
                loadExtractor(href, referer ?: "HubDrive", subtitleCallback, callback)
            } else {
                Log.d("Hubdrive", "No valid link found for: $url")
            }
        } catch (e: Exception) {
            Log.e("Hubdrive", "Error: ${e.message}")
        }
    }
}

// ==================== HubCloud Extractor ====================
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
        val ref = referer.orEmpty()

        val uri = runCatching { URI(url) }.getOrElse {
            Log.e(tag, "Invalid URL: ${it.message}")
            return
        }

        val realUrl = uri.toString()
        val baseUrl = "${uri.scheme}://${uri.host}"

        val href = runCatching {
            when {
                "hubcloud.php" in realUrl || "gamerxyt.com" in realUrl -> realUrl
                "/drive/" in realUrl -> {
                    val driveDoc = app.get(realUrl, interceptor = cfKiller, timeout = 15).document
                    
                    val generateBtn = driveDoc.selectFirst("a.btn.btn-primary.h6")?.attr("href")
                        ?: driveDoc.selectFirst("a.btn[href*=gamerxyt.com/hubcloud.php]")?.attr("href")
                        ?: driveDoc.selectFirst("a.btn[href*=hubcloud.php]")?.attr("href")
                        ?: driveDoc.selectFirst("a#download")?.attr("href")
                        ?: driveDoc.selectFirst("a.btn-primary")?.attr("href")
                    
                    Log.d(tag, "Drive page button: $generateBtn")
                    
                    if (!generateBtn.isNullOrBlank() && generateBtn.startsWith("http")) {
                        generateBtn
                    } else {
                        val allBtns = driveDoc.select("a.btn")
                        allBtns.firstOrNull { 
                            it.attr("href").contains("gamerxyt", true) || 
                            it.attr("href").contains("hubcloud.php", true)
                        }?.attr("href") ?: ""
                    }
                }
                else -> {
                    val raw = app.get(realUrl, interceptor = cfKiller, timeout = 15).document
                        .selectFirst("#download")?.attr("href").orEmpty()
                    if (raw.startsWith("http", true)) raw
                    else baseUrl.trimEnd('/') + "/" + raw.trimStart('/')
                }
            }
        }.getOrElse {
            Log.e(tag, "Failed to extract href: ${it.message}")
            ""
        }

        if (href.isBlank()) {
            Log.w(tag, "No valid href for: $url")
            return
        }

        Log.d(tag, "Fetching download page: $href")

        val document = app.get(href, interceptor = cfKiller, timeout = 15).document
        val size = document.selectFirst("i#size")?.text().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()

        val headerDetails = cleanTitle(header)
        val quality = getIndexQuality(header)

        val labelExtras = buildString {
            if (headerDetails.isNotEmpty()) append("[$headerDetails]")
            if (size.isNotEmpty()) append("[$size]")
        }

        document.select("a.btn, div.card-body a.btn").amap { element ->
            val link = element.attr("href")
            val text = element.ownText().ifBlank { element.text() }
            val label = text.lowercase()
            
            if (link.isBlank() || !link.startsWith("http")) return@amap
            
            Log.d(tag, "Processing: $label -> $link")
            
            when {
                "fsl server" in label || "fsl.gigabytes" in link.lowercase() -> {
                    callback(
                        newExtractorLink("$ref [FSL Server]", "$ref [FSL Server] $labelExtras", link)
                        { this.quality = quality }
                    )
                }

                "download file" in label -> {
                    callback(
                        newExtractorLink(ref, "$ref $labelExtras", link)
                        { this.quality = quality }
                    )
                }

                "buzzserver" in label || "bloggingvector" in link.lowercase() -> {
                    try {
                        val resp = app.get("$link/download", referer = link, allowRedirects = false)
                        val dlink = resp.headers["hx-redirect"] ?: resp.headers["HX-Redirect"].orEmpty()

                        if (dlink.isNotBlank()) {
                            callback(
                                newExtractorLink("$ref [BuzzServer]", "$ref [BuzzServer] $labelExtras", dlink)
                                { this.quality = quality }
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "BuzzServer failed: ${e.message}")
                    }
                }

                "pixeldra" in label || "pixelserver" in label || "pixel" in label || "pixeldrain" in link.lowercase() -> {
                    val base = getBaseUrl(link)
                    val finalUrl = if ("download" in link || "/api/" in link) link
                        else "$base/api/file/${link.substringAfterLast("/")}?download"

                    callback(
                        newExtractorLink("$ref Pixeldrain", "$ref Pixeldrain $labelExtras", finalUrl)
                        { this.quality = quality }
                    )
                }

                "s3 server" in label -> {
                    callback(
                        newExtractorLink("$ref [S3 Server]", "$ref [S3 Server] $labelExtras", link)
                        { this.quality = quality }
                    )
                }

                "fslv2" in label || "r2.dev" in link.lowercase() || "gdboka" in link.lowercase() -> {
                    callback(
                        newExtractorLink("$ref [FSLv2]", "$ref [FSLv2] $labelExtras", link)
                        { this.quality = quality }
                    )
                }

                "mega server" in label -> {
                    callback(
                        newExtractorLink("$ref [Mega Server]", "$ref [Mega Server] $labelExtras", link)
                        { this.quality = quality }
                    )
                }

                "10gbps" in label -> {
                    var current = link
                    repeat(3) {
                        val resp = app.get(current, allowRedirects = false)
                        val loc = resp.headers["location"] ?: return@repeat

                        if ("link=" in loc) {
                            callback(
                                newExtractorLink("$ref 10Gbps", "$ref 10Gbps $labelExtras", loc.substringAfter("link="))
                                { this.quality = quality }
                            )
                            return@repeat
                        }
                        current = loc
                    }
                }

                "instant" in label -> {
                    callback(
                        newExtractorLink("$ref [Instant DL]", "$ref [Instant DL] $labelExtras", link)
                        { this.quality = quality + 50 }
                    )
                }

                else -> {
                    // Try loadExtractor for unknown links
                    if (link.isNotBlank()) {
                        loadExtractor(link, ref, subtitleCallback, callback)
                    }
                }
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]")
            .find(str.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun cleanTitle(title: String): String {
        val parts = title.split(".", "-", "_")
        val qualityTags = listOf("WEBRip", "WEB-DL", "WEB", "BluRay", "HDRip", "DVDRip", "HDTV", "CAM", "TS", "HD")
        val audioTags = listOf("AAC", "AC3", "DTS", "MP3", "FLAC", "DD5", "EAC3", "Atmos")
        val subTags = listOf("ESub", "ESubs", "Subs", "MultiSub")
        val codecTags = listOf("x264", "x265", "H264", "HEVC", "AVC")

        val startIndex = parts.indexOfFirst { part -> qualityTags.any { part.contains(it, true) } }
        val endIndex = parts.indexOfLast { part ->
            subTags.any { part.contains(it, true) } ||
            audioTags.any { part.contains(it, true) } ||
            codecTags.any { part.contains(it, true) }
        }

        return when {
            startIndex != -1 && endIndex != -1 && endIndex >= startIndex ->
                parts.subList(startIndex, endIndex + 1).joinToString(".")
            startIndex != -1 -> parts.subList(startIndex, parts.size).joinToString(".")
            else -> parts.takeLast(3).joinToString(".")
        }
    }
}

// ==================== HUBCDN Extractor ====================
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
        // Skip /file/ URLs - they are ad redirect pages
        if ("/file/" in url) {
            Log.d("HUBCDN", "Skipping /file/ URL: $url")
            return
        }
        
        val doc = app.get(url, timeout = 15).document
        val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()

        val encodedUrl = Regex("""reurl\s*=\s*"([^"]+)"""")
            .find(scriptText ?: "")?.groupValues?.get(1)?.substringAfter("?r=")

        val decodedUrl = encodedUrl?.let { base64Decode(it) }?.substringAfterLast("link=")

        if (decodedUrl != null) {
            callback(
                newExtractorLink(this.name, this.name, decodedUrl, INFER_TYPE)
                { this.quality = Qualities.Unknown.value }
            )
        } else {
            Log.d("HUBCDN", "var reurl not found, trying fallback")
            try {
                val hubcloudLink = doc.select("a[href*=hubcloud]").attr("href")
                    .ifBlank { doc.select("a.btn[href*=drive]").attr("href") }
                
                if (hubcloudLink.isNotBlank() && hubcloudLink.contains("hubcloud", true)) {
                    HubCloud().getUrl(hubcloudLink, referer, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e("HUBCDN", "Fallback failed: ${e.message}")
            }
        }
    }
}