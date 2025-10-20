package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import okhttp3.FormBody
import org.json.JSONObject
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// AES Helper for Cherry decryption
object CherryAesHelper {
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

class CherryExtractor : ExtractorApi() {
    override val name = "Cherry"
    override val mainUrl = "https://cherry.upns.online"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Cherry", "Starting extraction for URL: $url")
            
            // Extract video ID from URL fragment
            val videoId = url.substringAfter("#").substringBefore("&")
            if (videoId.isEmpty() || videoId == url) {
                Log.e("Cherry", "Invalid video ID from URL")
                return
            }
            
            Log.d("Cherry", "Extracted video ID: $videoId")
            val baseUrl = getBaseUrl(url)
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0",
                "Referer" to url,
                "Accept" to "*/*",
                "Origin" to baseUrl
            )
            
            // Method 1: Try AES decryption (like VidStack)
            try {
                Log.d("Cherry", "Method 1: Trying AES decryption...")
                val apiUrl = "$baseUrl/api/v1/video?id=$videoId"
                Log.d("Cherry", "API URL: $apiUrl")
                val encoded = app.get(apiUrl, headers = headers).text.trim()
                Log.d("Cherry", "Encoded response length: ${encoded.length}")
                
                // Try multiple key/IV combinations
                val keys = listOf("kiemtienmua911ca", "cherry123456789")
                val ivs = listOf("1234567890oiuytr", "0123456789abcdef", "cherryiv12345678")
                
                for (key in keys) {
                    for (iv in ivs) {
                        try {
                            val decrypted = CherryAesHelper.decryptAES(encoded, key, iv)
                            val m3u8Regex = Regex(""""source":"(.*?)"""").find(decrypted)
                            val m3u8 = m3u8Regex?.groupValues?.get(1)?.replace("\\/", "/")
                            
                            if (!m3u8.isNullOrBlank() && (m3u8.contains("m3u8") || m3u8.contains("http"))) {
                                Log.d("Cherry", "AES decryption successful: $m3u8")
                                callback.invoke(
                                    ExtractorLink(
                                        source = name,
                                        name = "$name [Decrypted]",
                                        url = m3u8,
                                        referer = url,
                                        quality = Qualities.P1080.value,
                                        type = ExtractorLinkType.M3U8
                                    )
                                )
                                return
                            }
                        } catch (e: Exception) {
                            // Try next key/IV combination
                            continue
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Cherry", "AES decryption failed: ${e.message}")
            }
            
            // Method 2: Try WebView extraction (like VidHide)
            try {
                Log.d("Cherry", "Method 2: Trying WebView extraction...")
                val response = app.get(
                    url,
                    referer = referer ?: mainUrl,
                    interceptor = WebViewResolver(
                        Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|\.m3u8)""")
                    )
                )
                
                if (response.url.contains("m3u8")) {
                    Log.d("Cherry", "WebView extraction successful: ${response.url}")
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name [WebView]",
                            url = response.url,
                            referer = url,
                            quality = Qualities.P1080.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    return
                }
                Log.d("Cherry", "WebView did not capture M3U8 URL")
            } catch (e: Exception) {
                Log.e("Cherry", "WebView extraction failed: ${e.message}")
            }
            
            // Method 3: Try iframe HTML and JavaScript parsing
            try {
                Log.d("Cherry", "Method 3: Trying iframe HTML/JS extraction...")
                val iframeHtml = app.get(url, headers = headers).text
                val iframeDoc = app.get(url, headers = headers).document
                
                // Try extracting from video/source tags
                val videoSrc = iframeDoc.select("source[src*=m3u8]").attr("src").ifBlank {
                    iframeDoc.select("video source").firstOrNull()?.attr("src")
                }
                
                if (!videoSrc.isNullOrBlank() && videoSrc.contains("m3u8")) {
                    Log.d("Cherry", "iframe HTML extraction successful: $videoSrc")
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name [Iframe]",
                            url = videoSrc,
                            referer = url,
                            quality = Qualities.P720.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    return
                }
                
                // Try extracting from JavaScript
                val m3u8Regex = Regex("""(https?://[^"'\\s]+\.m3u8[^"'\\s]*)""")
                val jsMatch = m3u8Regex.find(iframeHtml)
                if (jsMatch != null) {
                    val m3u8Url = jsMatch.groupValues[1]
                    Log.d("Cherry", "JavaScript extraction successful: $m3u8Url")
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name [JS]",
                            url = m3u8Url,
                            referer = url,
                            quality = Qualities.P720.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    return
                }
                
                // Try unpacking obfuscated JavaScript (eval/p/a/c/k/e/d)
                val packedRegex = Regex("""eval\\(function\\(p,a,c,k,e,d\\)""")
                if (packedRegex.containsMatchIn(iframeHtml)) {
                    Log.d("Cherry", "Found packed JavaScript, unpacking...")
                    try {
                        val unpacked = JsUnpacker(iframeHtml).unpack()
                        val unpackedMatch = m3u8Regex.find(unpacked ?: "")
                        if (unpackedMatch != null) {
                            val m3u8Url = unpackedMatch.groupValues[1]
                            Log.d("Cherry", "Unpacked JS extraction successful: $m3u8Url")
                            callback.invoke(
                                ExtractorLink(
                                    source = name,
                                    name = "$name [Unpacked]",
                                    url = m3u8Url,
                                    referer = url,
                                    quality = Qualities.P720.value,
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                            return
                        }
                    } catch (e: Exception) {
                        Log.e("Cherry", "JS unpacking failed: ${e.message}")
                    }
                }
                
                Log.d("Cherry", "No M3U8 found in iframe HTML or JS")
            } catch (e: Exception) {
                Log.e("Cherry", "Iframe/JS parsing failed: ${e.message}")
            }
            
            // If all else fails, log error
            Log.e("Cherry", "Unable to extract playable M3U8 from Cherry - all 3 methods failed")
            
        } catch (e: Exception) {
            Log.e("Cherry", "All extraction methods failed: ${e.message}")
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
