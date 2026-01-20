package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ==================== CONSTANTS ====================

val VIDEO_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Accept" to "*/*",
    "Accept-Encoding" to "identity",
    "Connection" to "keep-alive",
    "Range" to "bytes=0-"
)

// ==================== UTILITY FUNCTIONS ====================

fun getBaseUrl(url: String): String {
    return URI(url).let { "${it.scheme}://${it.host}" }
}

fun getIndexQuality(str: String?): Int {
    return Regex("""(\d{3,4})[pP]""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

// ROT13 decoder
fun String.rot13(): String {
    return this.map { char ->
        when (char) {
            in 'A'..'Z' -> ((char - 'A' + 13) % 26 + 'A'.code).toChar()
            in 'a'..'z' -> ((char - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> char
        }
    }.joinToString("")
}

// GadgetsWeb URL decoder: Base64 -> ROT13 -> Base64
fun decodeGadgetsWebUrl(encodedId: String): String? {
    return try {
        val firstDecode = base64Decode(encodedId)
        val rot13Applied = firstDecode.rot13()
        val finalUrl = base64Decode(rot13Applied)
        if (finalUrl.startsWith("http")) finalUrl else null
    } catch (_: Exception) { null }
}

// AES-CBC decryption for HubStream
fun decryptAES(encryptedHex: String, key: String = "kiemtienmua911ca", iv: String = "1234567890oiuytr"): String? {
    return try {
        val encryptedBytes = encryptedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.e("AES", "Decryption failed: ${e.message}")
        null
    }
}

// Cached URLs JSON for dynamic domain resolution
private var cachedUrlsJson: JSONObject? = null

suspend fun getLatestUrl(url: String, source: String): String {
    if (cachedUrlsJson == null) {
        try {
            cachedUrlsJson = JSONObject(
                app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json").text
            )
        } catch (_: Exception) {
            return getBaseUrl(url)
        }
    }
    val link = cachedUrlsJson?.optString(source)
    return if (link.isNullOrEmpty()) getBaseUrl(url) else link
}

// ==================== HUBDRIVE EXTRACTOR ====================

class HubDrive : ExtractorApi() {
    override val name = "HubDrive"
    override val mainUrl = "https://hubdrive.space"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val latestUrl = getLatestUrl(url, "hubdrive")
            val baseUrl = getBaseUrl(url)
            val newUrl = url.replace(baseUrl, latestUrl)
            
            val doc = app.get(newUrl, allowRedirects = true).document
            
            // Find HubCloud button and extract link
            doc.select("a.btn").amap { button ->
                val href = button.attr("href")
                val text = button.text()
                
                if (href.isNotBlank() && (
                    text.contains("HubCloud", ignoreCase = true) ||
                    href.contains("hubcloud", ignoreCase = true))) {
                    // Route to HubCloud extractor
                    HubCloud().getUrl(href, newUrl, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e("HubDrive", "Error: ${e.message}")
        }
    }
}

// ==================== HUBCLOUD EXTRACTOR ====================

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.foo"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val latestUrl = getLatestUrl(url, "hubcloud")
            val baseUrl = getBaseUrl(url)
            val newUrl = url.replace(baseUrl, latestUrl)
            
            val doc = app.get(newUrl, allowRedirects = true).document
            
            // Check if this is a /drive/ page or /video/ page
            var link = if (newUrl.contains("drive")) {
                // Extract link from script or button
                val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
                Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) 
                    ?: doc.selectFirst("h2 a.btn")?.attr("href") ?: ""
            } else {
                doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
            }

            if (link.isBlank()) return
            if (!link.startsWith("https://")) {
                link = latestUrl + link
            }

            // Follow redirect to gamerxyt.com or direct download page
            val finalDoc = app.get(link, allowRedirects = true).document
            val header = finalDoc.select("div.card-header").text()
            val size = finalDoc.select("i#size").text()
            val baseQuality = getIndexQuality(header)

            // Extract all download server buttons
            finalDoc.select("h2 a.btn, a.btn-success, a.btn-danger, a.btn-lg").amap { btn ->
                val btnLink = btn.attr("href")
                val text = btn.text()

                if (btnLink.isBlank() || btnLink.contains("logout") || btnLink.contains("javascript") || !btnLink.startsWith("http")) return@amap
                
                Log.d("HubCloud", "Found button: $text -> $btnLink")

                when {
                    // FSL Servers - direct links (most reliable)
                    text.contains("FSL", ignoreCase = true) || btnLink.contains("gigabytes.icu") || btnLink.contains("polgen.buzz") -> {
                        val serverName = when {
                            text.contains("FSLv2") || btnLink.contains("gigabytes.icu") -> "[FSLv2 Server]"
                            else -> "[FSL Server]"
                        }
                        callback.invoke(
                            newExtractorLink(
                                "$name$serverName",
                                "$name$serverName $header[$size]",
                                btnLink,
                            ) {
                                this.quality = baseQuality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }

                    // 10Gbps Server - follow redirect
                    text.contains("10Gbps", ignoreCase = true) || btnLink.contains("hubcdn.fans") -> {
                        try {
                            val redirectUrl = app.get(btnLink, allowRedirects = false).headers["location"]
                            val finalLink = redirectUrl?.substringAfter("link=") ?: btnLink
                            if (finalLink.isNotBlank() && finalLink.startsWith("http")) {
                                callback.invoke(
                                    newExtractorLink(
                                        "$name[10Gbps]",
                                        "$name[10Gbps] $header[$size]",
                                        finalLink,
                                    ) {
                                        this.quality = baseQuality
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                            }
                        } catch (_: Exception) {}
                    }

                    // Pixeldrain
                    btnLink.contains("pixeldra") -> {
                        val pixelBaseUrl = getBaseUrl(btnLink)
                        val finalURL = if (btnLink.contains("download", true)) btnLink
                        else "$pixelBaseUrl/api/file/${btnLink.substringAfterLast("/")}?download"

                        callback.invoke(
                            newExtractorLink(
                                "Pixeldrain",
                                "Pixeldrain $header[$size]",
                                finalURL,
                            ) {
                                this.quality = baseQuality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }

                    // BuzzServer
                    text.contains("Buzz", ignoreCase = true) -> {
                        try {
                            val dlink = app.get("$btnLink/download", referer = btnLink, allowRedirects = false)
                                .headers["hx-redirect"] ?: ""
                            if (dlink.isNotEmpty()) {
                                callback.invoke(
                                    newExtractorLink(
                                        "$name[BuzzServer]",
                                        "$name[BuzzServer] $header[$size]",
                                        getBaseUrl(btnLink) + dlink,
                                    ) {
                                        this.quality = baseQuality
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                            }
                        } catch (_: Exception) {}
                    }

                    // Download File button
                    text.contains("Download File", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name $header[$size]",
                                btnLink,
                            ) {
                                this.quality = baseQuality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }

                    // Direct video file links
                    btnLink.contains(".mkv") || btnLink.contains(".mp4") -> {
                        if (!btnLink.contains(".zip")) {
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    "$name $header[$size]",
                                    btnLink,
                                ) {
                                    this.quality = baseQuality
                                    this.headers = VIDEO_HEADERS
                                }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HubCloud", "Error: ${e.message}")
        }
    }
}

// ==================== HDSTREAM4U EXTRACTOR ====================

class HdStream4u : ExtractorApi() {
    override val name = "HdStream4u"
    override val mainUrl = "https://hdstream4u.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val latestUrl = getLatestUrl(url, "hdstream4u")
            val baseUrl = getBaseUrl(url)
            val newUrl = url.replace(baseUrl, latestUrl)
            
            val response = app.get(newUrl, allowRedirects = true)
            val html = response.text
            
            // Extract m3u8 from JWPlayer setup
            val m3u8Patterns = listOf(
                Regex(""""?file"?\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
                Regex(""""?source"?\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
                Regex("""sources:\s*\[\s*\{\s*file:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex(""""?sources"?\s*:\s*\[\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
            )
            
            for (pattern in m3u8Patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val m3u8Url = match.groupValues[1]
                    if (m3u8Url.isNotBlank()) {
                        // Extract title from page
                        val title = Regex("""<title>([^<]+)</title>""").find(html)?.groupValues?.get(1)
                            ?.substringBefore(" - ")?.trim() ?: "HdStream4u"
                        
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name - $title",
                                m3u8Url,
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.headers = VIDEO_HEADERS + mapOf("Referer" to newUrl)
                            }
                        )
                        return
                    }
                }
            }
            
            // Fallback: try to extract from script tags
            val doc = response.document
            doc.select("script").forEach { script ->
                val scriptContent = script.data()
                if (scriptContent.contains("m3u8") || scriptContent.contains("urlset")) {
                    val m3u8Match = Regex("""(https?://[^"'\s]+\.(?:m3u8|txt)[^"'\s]*)""").find(scriptContent)
                    if (m3u8Match != null) {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                name,
                                m3u8Match.groupValues[1],
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.headers = VIDEO_HEADERS + mapOf("Referer" to newUrl)
                            }
                        )
                        return@forEach
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HdStream4u", "Error: ${e.message}")
        }
    }
}

// ==================== HUBSTREAM EXTRACTOR ====================

class HubStream : ExtractorApi() {
    override val name = "HubStream"
    override val mainUrl = "https://hubstream.art"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val latestUrl = getLatestUrl(url, "hubstream")
            val baseUrl = getBaseUrl(url)
            val newUrl = url.replace(baseUrl, latestUrl)
            
            // Extract video ID from hash
            val videoId = newUrl.substringAfter("#").takeIf { it.isNotBlank() } ?: return
            
            // Call API to get encrypted video config
            val apiUrl = "$latestUrl/api/v1/video?id=$videoId"
            val apiResponse = app.get(apiUrl, referer = newUrl).text
            
            // Decrypt the AES encrypted response
            val decrypted = decryptAES(apiResponse)
            if (decrypted == null) {
                Log.e("HubStream", "Decryption failed for $videoId")
                return
            }
            
            // Parse JSON and extract m3u8 source
            val json = JSONObject(decrypted)
            val source = json.optString("source")
            val title = json.optString("title", "HubStream")
            
            if (source.isNotBlank() && source.contains("m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name - $title",
                        source,
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = VIDEO_HEADERS + mapOf("Referer" to latestUrl)
                    }
                )
            }
            
            // Also check for Cloudflare backup source
            val cfSource = json.optString("cf")
            if (cfSource.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        "$name[CF]",
                        "$name[CF] - $title",
                        cfSource,
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = VIDEO_HEADERS + mapOf("Referer" to latestUrl)
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("HubStream", "Error: ${e.message}")
        }
    }
}
