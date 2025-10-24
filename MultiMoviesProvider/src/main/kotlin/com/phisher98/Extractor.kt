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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
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

// StreamHG Extractor
class StreamHGExtractor : ExtractorApi() {
    override val name = "StreamHG"
    override val mainUrl = "https://multimoviesshg.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("StreamHG", "Starting extraction for URL: $url")
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
                    Log.d("StreamHG", "M3U8 extraction successful: ${response.url}")
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
                Log.e("StreamHG", "WebView extraction failed: ${e.message}")
            }
            
            // Method 2: Try API with potential base64 or JSON parsing
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
                    } catch (e: Exception) {
                        // Try base64 decode
                        try {
                            val decoded = base64Decode(apiResponse)
                            if (decoded.contains("m3u8")) {
                                val m3u8Url = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(decoded)?.groupValues?.get(1)
                                if (!m3u8Url.isNullOrEmpty()) {
                                    callback.invoke(
                                        newExtractorLink(
                                            name,
                                            "$name [Base64]",
                                            m3u8Url,
                                            ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = url
                                            this.quality = Qualities.P1080.value
                                        }
                                    )
                                    return
                                }
                            }
                        } catch (ignored: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.e("StreamHG", "API extraction failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e("StreamHG", "Extraction error: ${e.message}")
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

// RpmShare Extractor with AES decryption support
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

// UpnShare Extractor
class UpnShareExtractor : ExtractorApi() {
    override val name = "UpnShare"
    override val mainUrl = "https://server1.uns.bio"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("UpnShare", "Starting extraction for URL: $url")
            val response = app.get(
                url,
                referer = referer ?: mainUrl,
                interceptor = WebViewResolver(
                    Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|\.m3u8)""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                Log.d("UpnShare", "M3U8 extraction successful: ${response.url}")
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
            Log.e("UpnShare", "Extraction error: ${e.message}")
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

// GTXGamer Download Extractor  
class GTXGamerExtractor : ExtractorApi() {
    override val name = "GTXGamer"
    override val mainUrl = "https://ddn.gtxgamer.site"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // GTXGamer is a download link provider, use built-in CloudStream extractors
        loadExtractor(url, referer ?: mainUrl, subtitleCallback, callback)
    }
}
