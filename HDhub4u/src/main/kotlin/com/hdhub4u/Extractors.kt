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
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Centralized Regex Patterns for all extractors
 * Professional approach: Compile once, reuse everywhere
 */
object ExtractorPatterns {
    // Domain patterns
    val HUBDRIVE = Regex("""(?i)hubdrive""")
    val HUBCLOUD = Regex("""(?i)(hubcloud|cloud\.php)""")
    val HUBCDN = Regex("""(?i)(hubcdn|gadgetsweb)""")
    val HDSTREAM4U = Regex("""(?i)(hdstream4u|vidhidepro)""")
    val HUBSTREAM = Regex("""(?i)hubstream""")
    val HBLINKS = Regex("""(?i)(hblinks|4khdhub)""")
    val PIXELDRAIN = Regex("""(?i)pixeldrain""")
    val VIDSTACK = Regex("""(?i)(vidstack|stream|video|player)""")
    
    // Server priority patterns
    val BUZZSERVER = Regex("""(?i)(buzzserver|buzz)""")
    val FSL = Regex("""(?i)fsl""")
    val GBPS_10 = Regex("""(?i)(10gbps|10gb)""")
    val MEGA = Regex("""(?i)mega""")
    val S3 = Regex("""(?i)s3""")
    
    // Quality patterns
    val QUALITY_PATTERN = Regex("""(?i)(\d{3,4})p""")
    val QUALITY_4K = Regex("""(?i)(4k|2160)""")
    val QUALITY_1080 = Regex("""(?i)1080""")
    val QUALITY_720 = Regex("""(?i)720""")
    val QUALITY_480 = Regex("""(?i)480""")
    
    // M3U8 extraction pattern
    val M3U8_PATTERN = Regex(""":\s*"(.*?m3u8.*?)"""")
    
    // Encoded URL pattern
    val ENCODED_URL_PATTERN = Regex("""r=([A-Za-z0-9+/=]+)""")
    val REURL_PATTERN = Regex("""reurl\s*=\s*"([^"]+)"""")
    
    // Video source pattern
    val VIDEO_SOURCE = Regex(""""source":"(.*?)"""")
    val SUBTITLE_SECTION = Regex(""""subtitle":\{(.*?)\}""")
    val SUBTITLE_PATTERN = Regex(""""([^"]+)":\s*"([^"]+)"""")
    
    // Valid download link pattern
    val VALID_LINK = Regex("""(?i)https?://[^\s]*?(hubdrive|gadgetsweb|hdstream4u|hubstream|hubcloud|hubcdn|hblinks|4khdhub)[^\s]*""")
    
    // Redirect pattern
    val REDIRECT_ID = Regex("""\?id=""")
    
    /**
     * Match URL to extractor type
     */
    fun matchExtractor(url: String): ExtractorType {
        return when {
            HUBDRIVE.containsMatchIn(url) -> ExtractorType.HUBDRIVE
            HUBCLOUD.containsMatchIn(url) -> ExtractorType.HUBCLOUD
            HUBCDN.containsMatchIn(url) -> ExtractorType.HUBCDN
            HDSTREAM4U.containsMatchIn(url) -> ExtractorType.HDSTREAM4U
            HUBSTREAM.containsMatchIn(url) -> ExtractorType.HUBSTREAM
            HBLINKS.containsMatchIn(url) -> ExtractorType.HBLINKS
            PIXELDRAIN.containsMatchIn(url) -> ExtractorType.PIXELDRAIN
            VIDSTACK.containsMatchIn(url) -> ExtractorType.VIDSTACK
            else -> ExtractorType.HUBCLOUD // Default fallback
        }
    }
    
    /**
     * Get server priority based on URL/text
     */
    fun getServerPriority(text: String): Int = when {
        HDSTREAM4U.containsMatchIn(text) -> 100
        HUBSTREAM.containsMatchIn(text) -> 95
        PIXELDRAIN.containsMatchIn(text) -> 90
        BUZZSERVER.containsMatchIn(text) -> 85
        FSL.containsMatchIn(text) -> 80
        GBPS_10.containsMatchIn(text) -> 75
        HUBCLOUD.containsMatchIn(text) -> 70
        HUBDRIVE.containsMatchIn(text) -> 65
        HUBCDN.containsMatchIn(text) -> 55
        MEGA.containsMatchIn(text) -> 50
        S3.containsMatchIn(text) -> 45
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
     * Check if URL is a valid download link
     */
    fun isValidDownloadLink(url: String): Boolean = VALID_LINK.containsMatchIn(url)
    
    /**
     * Check if URL has redirect ID
     */
    fun hasRedirectId(url: String): Boolean = REDIRECT_ID.containsMatchIn(url)
}

enum class ExtractorType {
    HUBDRIVE, HUBCLOUD, HUBCDN, HDSTREAM4U, HUBSTREAM, HBLINKS, PIXELDRAIN, VIDSTACK
}

// ============== EXTRACTORS ==============

class HdStream4u : VidHidePro() {
    override var mainUrl = "https://hdstream4u.*"
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

        val m3u8 = ExtractorPatterns.VIDEO_SOURCE.find(decryptedText)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: ""
            
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
                this.headers = mapOf("referer" to url, "Origin" to url.substringAfterLast("/"))
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            android.util.Log.e("Vidstack", "getBaseUrl fallback: ${e.message}")
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
            val href = it.absUrl("href").ifBlank { it.attr("href") }
            when (ExtractorPatterns.matchExtractor(href)) {
                ExtractorType.HUBDRIVE -> Hubdrive().getUrl(href, name, subtitleCallback, callback)
                ExtractorType.HUBCLOUD -> HubCloud().getUrl(href, name, subtitleCallback, callback)
                ExtractorType.HUBCDN -> HUBCDN().getUrl(href, name, subtitleCallback, callback)
                else -> loadSourceNameExtractor(name, href, "", Qualities.Unknown.value, subtitleCallback, callback)
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
            val encoded = ExtractorPatterns.ENCODED_URL_PATTERN.find(it)?.groups?.get(1)?.value
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

class PixelDrainDev : PixelDrain() {
    override var mainUrl = "https://pixeldrain.dev"
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
        val href = app.get(url, timeout = 5000L).documentLarge
            .select(".btn.btn-primary.btn-user.btn-success1.m-1").attr("href")
        
        when (ExtractorPatterns.matchExtractor(href)) {
            ExtractorType.HUBCLOUD -> HubCloud().getUrl(href, "HubDrive", subtitleCallback, callback)
            else -> loadSourceNameExtractor("HubDrive", href, "", Qualities.Unknown.value, subtitleCallback, callback)
        }
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
        val ref = referer.orEmpty()

        val uri = runCatching { URI(url) }.getOrElse {
            Log.e(tag, "Invalid URL: ${it.message}")
            return
        }

        val realUrl = uri.toString()
        val baseUrl = "${uri.scheme}://${uri.host}"

        val href = runCatching {
            if ("hubcloud.php" in realUrl) {
                realUrl
            } else {
                val raw = app.get(realUrl).document
                    .selectFirst("#download")
                    ?.attr("href")
                    .orEmpty()

                if (raw.startsWith("http", true)) raw
                else baseUrl.trimEnd('/') + "/" + raw.trimStart('/')
            }
        }.getOrElse {
            Log.e(tag, "Failed to extract href: ${it.message}")
            ""
        }

        if (href.isBlank()) return

        val document = app.get(href).document
        val size = document.selectFirst("i#size")?.text().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()

        val headerDetails = cleanTitle(header)
        val quality = getIndexQuality(header)

        val labelExtras = buildString {
            if (headerDetails.isNotEmpty()) append("[$headerDetails]")
            if (size.isNotEmpty()) append("[$size]")
        }

        document.select("a.btn").forEach { element ->
            val link = element.attr("href")
            val text = element.ownText()
            val label = text.lowercase()
            Log.d("Phisher", label)
            
            when {
                "fsl server" in label -> {
                    callback(
                        newExtractorLink(
                            "$ref [FSL Server]",
                            "$ref [FSL Server] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "download file" in label -> {
                    callback(
                        newExtractorLink(
                            ref,
                            "$ref $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "buzzserver" in label -> {
                    val resp = app.get("$link/download", referer = link, allowRedirects = false)
                    val dlink = resp.headers["hx-redirect"]
                        ?: resp.headers["HX-Redirect"].orEmpty()

                    if (dlink.isNotBlank()) {
                        callback(
                            newExtractorLink(
                                "$ref [BuzzServer]",
                                "$ref [BuzzServer] $labelExtras",
                                dlink
                            ) { this.quality = quality }
                        )
                    } else {
                        Log.w(tag, "BuzzServer: No redirect")
                    }
                }

                "pixeldra" in label || "pixelserver" in label || "pixel server" in label || "pixeldrain" in label -> {
                    val base = getBaseUrl(link)
                    val finalUrl =
                        if ("download" in link) link
                        else "$base/api/file/${link.substringAfterLast("/")}?download"

                    callback(
                        newExtractorLink(
                            "$ref Pixeldrain",
                            "$ref Pixeldrain $labelExtras",
                            finalUrl
                        ) { this.quality = quality }
                    )
                }

                "s3 server" in label -> {
                    callback(
                        newExtractorLink(
                            "$ref [S3 Server]",
                            "$ref [S3 Server] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "fslv2" in label -> {
                    callback(
                        newExtractorLink(
                            "$ref [FSLv2]",
                            "$ref [FSLv2] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "mega server" in label -> {
                    callback(
                        newExtractorLink(
                            "$ref [Mega Server]",
                            "$ref [Mega Server] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "10gbps" in label -> {
                    var current = link
                    repeat(3) {
                        val resp = app.get(current, allowRedirects = false)
                        val loc = resp.headers["location"] ?: return@repeat

                        if ("link=" in loc) {
                            callback(
                                newExtractorLink(
                                    "$ref 10Gbps [Download]",
                                    "$ref 10Gbps [Download] $labelExtras",
                                    loc.substringAfter("link=")
                                ) { this.quality = quality }
                            )
                        }
                        current = loc
                    }
                    Log.e(tag, "10Gbps: Redirect limit reached")
                }

                else -> {
                    loadSourceNameExtractor(ref, link, "", quality, subtitleCallback, callback)
                }
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("""(\d{3,4})[pP]""")
            .find(str.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.P2160.value
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault("")
    }

    private fun cleanTitle(title: String): String {
        val parts = title.split(".", "-", "_")

        val qualityTags = listOf(
            "WEBRip", "WEB-DL", "WEB", "BluRay", "HDRip", "DVDRip", "HDTV",
            "CAM", "TS", "R5", "DVDScr", "BRRip", "BDRip", "DVD", "PDTV", "HD"
        )

        val audioTags = listOf("AAC", "AC3", "DTS", "MP3", "FLAC", "DD5", "EAC3", "Atmos")
        val subTags = listOf("ESub", "ESubs", "Subs", "MultiSub", "NoSub", "EnglishSub", "HindiSub")
        val codecTags = listOf("x264", "x265", "H264", "HEVC", "AVC")

        val startIndex = parts.indexOfFirst { part ->
            qualityTags.any { part.contains(it, true) }
        }

        val endIndex = parts.indexOfLast { part ->
            subTags.any { part.contains(it, true) } ||
                    audioTags.any { part.contains(it, true) } ||
                    codecTags.any { part.contains(it, true) }
        }

        return when {
            startIndex != -1 && endIndex != -1 && endIndex >= startIndex ->
                parts.subList(startIndex, endIndex + 1).joinToString(".")
            startIndex != -1 ->
                parts.subList(startIndex, parts.size).joinToString(".")
            else ->
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
        val doc = app.get(url).documentLarge
        val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()

        val encodedUrl = ExtractorPatterns.REURL_PATTERN
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
        ExtractorType.HDSTREAM4U -> HdStream4u().getUrl(url, referer, subtitleCallback, callback)
        ExtractorType.HUBSTREAM -> Hubstream().getUrl(url, referer, subtitleCallback, callback)
        ExtractorType.HBLINKS -> Hblinks().getUrl(url, referer, subtitleCallback, callback)
        ExtractorType.PIXELDRAIN -> PixelDrainDev().getUrl(url, referer, subtitleCallback, callback)
        ExtractorType.VIDSTACK -> VidStack().getUrl(url, referer, subtitleCallback, callback)
    }
}

// Backward compatibility function
fun getServerPriority(text: String): Int = ExtractorPatterns.getServerPriority(text)
