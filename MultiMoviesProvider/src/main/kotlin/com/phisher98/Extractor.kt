package com.phisher98

import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// AES Helper for video decryption
object VideoAesHelper {
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

// RpmShare/UpnShare Extractor with AES decryption support
class RpmShareExtractor : ExtractorApi() {
    override val name = "RpmShare"
    override val mainUrl = "https://multimovies.rpmhub.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("RpmShare", "Starting extraction for URL: $url")
            val videoId = url.substringAfter("#").substringBefore("&")
            val baseUrl = getBaseUrl(url)
            
            // Method 1: Try AES decryption
            try {
                val apiUrl = "$baseUrl/api/v1/video?id=$videoId"
                val encoded = app.get(apiUrl, referer = url).text.trim()
                
                // Try decryption with common keys
                val keys = listOf("rpmshare12345678", "videoplayer1234")
                val ivs = listOf("1234567890abcdef", "abcdef1234567890")
                
                for (key in keys) {
                    for (iv in ivs) {
                        try {
                            val decrypted = VideoAesHelper.decryptAES(encoded, key, iv)
                            val m3u8Url = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(decrypted)?.groupValues?.get(1)
                            
                            if (!m3u8Url.isNullOrEmpty()) {
                                Log.d("RpmShare", "AES decryption successful")
                                callback.invoke(
                                    newExtractorLink(
                                        name,
                                        "$name [Decrypted]",
                                        m3u8Url,
                                        ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = url
                                        this.quality = Qualities.P1080.value
                                    }
                                )
                                return
                            }
                        } catch (ignored: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.e("RpmShare", "AES decryption failed: ${e.message}")
            }
            
            // Method 2: WebView fallback
            try {
                val response = app.get(
                    url,
                    referer = referer ?: mainUrl,
                    interceptor = WebViewResolver(
                        Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|\.m3u8)""")
                    )
                )
                
                if (response.url.contains("m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name [WebView]",
                            response.url,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e("RpmShare", "WebView extraction failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e("RpmShare", "Extraction error: ${e.message}")
        }
    }
    
    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            mainUrl
        }
    }
}

// SmoothPre/EarnVids Extractor
class SmoothPreExtractor : ExtractorApi() {
    override val name = "SmoothPre"
    override val mainUrl = "https://smoothpre.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("SmoothPre", "Starting extraction for URL: $url")
            val response = app.get(
                url,
                referer = referer ?: mainUrl,
                interceptor = WebViewResolver(
                    Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|\.m3u8)""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                Log.d("SmoothPre", "M3U8 extraction successful: ${response.url}")
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name [WebView]",
                        response.url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("SmoothPre", "Extraction error: ${e.message}")
        }
    }
}

// StreamP2P Extractor
class StreamP2PExtractor : ExtractorApi() {
    override val name = "StreamP2P"
    override val mainUrl = "https://multimovies.p2pplay.pro"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("StreamP2P", "Starting extraction for URL: $url")
            val response = app.get(
                url,
                referer = referer ?: mainUrl,
                interceptor = WebViewResolver(
                    Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|\.m3u8)""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                Log.d("StreamP2P", "M3U8 extraction successful: ${response.url}")
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name [WebView]",
                        response.url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("StreamP2P", "Extraction error: ${e.message}")
        }
    }
}

// TechInMind Extractor - handles stream.techinmind.space & ssn.techinmind.space
class TechInMindExtractor : ExtractorApi() {
    override val name = "TechInMind"
    override val mainUrl = "https://stream.techinmind.space"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("TechInMind", "Starting extraction for URL: $url")
            
            // Method 1: Try direct iframe extraction from page
            try {
                val doc = app.get(url, referer = referer).document
                val playerIframe = doc.select("iframe#player[src]").attr("src")
                
                if (playerIframe.isNotEmpty()) {
                    Log.d("TechInMind", "Found player iframe: $playerIframe")
                    
                    // Use WebView to extract m3u8 from iframe
                    val response = app.get(
                        playerIframe,
                        referer = url,
                        interceptor = WebViewResolver(
                            Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|\.m3u8)""")
                        )
                    )
                    
                    if (response.url.contains("m3u8")) {
                        Log.d("TechInMind", "M3U8 extraction successful: ${response.url}")
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name [WebView]",
                                response.url,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = playerIframe
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e("TechInMind", "Iframe extraction failed: ${e.message}")
            }
            
            // Method 2: Fallback - direct WebView on main URL
            try {
                val response = app.get(
                    url,
                    referer = referer ?: mainUrl,
                    interceptor = WebViewResolver(
                        Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|\.m3u8)""")
                    )
                )
                
                if (response.url.contains("m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name [Direct]",
                            response.url,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e("TechInMind", "Direct extraction failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e("TechInMind", "Extraction error: ${e.message}")
        }
    }
}

// Gofile Extractor
class GofileExtractor : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Gofile", "Processing Gofile link: $url")
            // Gofile extraction logic
            val response = app.get(url, referer = referer).document
            val downloadLinks = response.select("a[href*='gofile.io/d/']").map { it.attr("href") }
            
            downloadLinks.forEach { link ->
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name [Download]",
                        link,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("Gofile", "Extraction error: ${e.message}")
        }
    }
}

// FilePress Extractor
class FilePressExtractor : ExtractorApi() {
    override val name = "FilePress"
    override val mainUrl = "https://filepress.cloud"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("FilePress", "Processing FilePress link: $url")
            val response = app.get(url, referer = referer).document
            val downloadLink = response.select("a.btn-download, a[href*='/download/'], button[data-url]").attr("href")
            
            if (downloadLink.isNotEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name [Download]",
                        downloadLink,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("FilePress", "Extraction error: ${e.message}")
        }
    }
}

// VidHide Extractor
class VidHideExtractor : ExtractorApi() {
    override val name = "VidHide"
    override val mainUrl = "https://vidhide.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("VidHide", "Starting extraction for URL: $url")
            val response = app.get(
                url,
                referer = referer ?: mainUrl,
                interceptor = WebViewResolver(
                    Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|\.m3u8)""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                Log.d("VidHide", "M3U8 extraction successful: ${response.url}")
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name [WebView]",
                        response.url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("VidHide", "Extraction error: ${e.message}")
        }
    }
}

// StreamWish Extractor (StreamHG is actually StreamWish)
class StreamWishExtractor : ExtractorApi() {
    override val name = "StreamWish"
    override val mainUrl = "https://streamwish.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("StreamWish", "Starting extraction for URL: $url")
            val baseUrl = getBaseUrl(url)
            
            // Method 1: Try WebView extraction
            try {
                val response = app.get(
                    url,
                    referer = referer ?: mainUrl,
                    interceptor = WebViewResolver(
                        Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|\.m3u8)""")
                    )
                )
                
                if (response.url.contains("m3u8")) {
                    Log.d("StreamWish", "M3U8 extraction successful: ${response.url}")
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name [WebView]",
                            response.url,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return
                }
            } catch (e: Exception) {
                Log.e("StreamWish", "WebView extraction failed: ${e.message}")
            }
            
            // Method 2: Try API extraction
            try {
                val videoId = url.substringAfter("/e/").substringBefore("?")
                if (videoId.isNotEmpty()) {
                    val apiUrl = "$baseUrl/api/source/$videoId"
                    val apiResponse = app.get(apiUrl, referer = url).text
                    
                    // Try parsing as JSON
                    try {
                        val jsonData = JsonParser.parseString(apiResponse).asJsonObject
                        val source = jsonData.get("file")?.asString
                        if (!source.isNullOrEmpty() && source.contains("m3u8")) {
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    "$name [API]",
                                    source,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.referer = url
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            return
                        }
                    } catch (ignored: Exception) {}
                }
            } catch (e: Exception) {
                Log.e("StreamWish", "API extraction failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e("StreamWish", "Extraction error: ${e.message}")
        }
    }
    
    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            mainUrl
        }
    }
}
