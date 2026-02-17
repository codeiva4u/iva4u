package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ============================================================================
// Utility Functions
// ============================================================================

private fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (_: Exception) { url }
}

// ============================================================================
// VidStack AES-CBC Decryptor
// Used by RpmHub, UnsBio, P2pPlay to decrypt /api/v1/video responses
// ============================================================================
object VidStackDecryptor {
    private const val AES_KEY = "kiemtienmua911ca"
    private const val AES_IV = "1234567890oiuytr"

    /**
     * Decrypts a hex-encoded AES-128-CBC encrypted string.
     * @param hexData The hex-encoded ciphertext from the API response
     * @return The decrypted plaintext (JSON string)
     */
    fun decrypt(hexData: String): String {
        val encBytes = hexData.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return String(cipher.doFinal(encBytes), Charsets.UTF_8)
    }

    /**
     * Extracts the video hash from a VidStack URL (the fragment after #).
     * e.g., "https://multimovies.rpmhub.site/#as8d3s" → "as8d3s"
     */
    fun extractHash(url: String): String? {
        // Try fragment first: https://domain/#hash
        val fragment = URI(url).fragment?.trimStart('#')?.trim()
        if (!fragment.isNullOrBlank()) return fragment.split("&").first()

        // Fallback: try path-based hash e.g., /e/hash or /v/hash
        val pathMatch = Regex("/[ev]/([a-zA-Z0-9]+)").find(url)
        if (pathMatch != null) return pathMatch.groupValues[1]

        return null
    }

    /**
     * Fetches and decrypts the M3U8 source URL from a VidStack player page.
     * @param baseUrl The base URL of the VidStack domain (e.g., "https://multimovies.rpmhub.site")
     * @param hash The video hash/ID
     * @return The M3U8 source URL, or null if extraction fails
     */
    suspend fun getM3U8Url(baseUrl: String, hash: String): String? {
        try {
            val apiUrl = "$baseUrl/api/v1/video?id=$hash"
            Log.d("VidStackDecryptor", "Fetching API: $apiUrl")

            val response = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to "$baseUrl/"
                )
            ).text.trim()

            if (response.isBlank() || response.startsWith("{")) {
                Log.e("VidStackDecryptor", "Invalid API response (not hex data)")
                return null
            }

            val decrypted = decrypt(response)
            Log.d("VidStackDecryptor", "Decrypted: ${decrypted.take(200)}")

            val json = JSONObject(decrypted)
            return json.optString("source")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e("VidStackDecryptor", "Decryption failed: ${e.message}")
            return null
        }
    }
}

// ============================================================================
// 1) Techinmind — Main orchestrator extractor
//    Parses the embed page for FinalID/idType/myKey,
//    calls mymovieapi, fetches svid page, delegates server links.
// ============================================================================
class Techinmind : ExtractorApi() {
    override val name = "Techinmind"
    override val mainUrl = "https://stream.techinmind.space"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Techinmind", "Starting extraction for: $url")

            // Resolve latest domain dynamically
            val latestBase = MultiMoviesProvider.getLatestUrl("techinmind") ?: mainUrl
            val embedUrl = url.replace(getBaseUrl(url), latestBase)

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to (referer ?: "https://multimovies.sarl/")
            )

            // Step 1: Fetch embed page and extract JS variables
            val embedHtml = app.get(embedUrl, headers = headers).text
            val finalId = Regex("""let\s+FinalID\s*=\s*["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1)
            val idType = Regex("""let\s+idType\s*=\s*["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1)
            val myKey = Regex("""let\s+myKey\s*=\s*["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1)

            if (finalId.isNullOrBlank() || idType.isNullOrBlank() || myKey.isNullOrBlank()) {
                Log.e("Techinmind", "Could not extract JS variables from embed page")
                // Fallback: try to extract direct quality links from embed HTML
                extractDirectLinksFromEmbed(embedHtml, latestBase, headers,
                    subtitleCallback, callback)
                return
            }

            Log.d("Techinmind", "FinalID=$finalId, idType=$idType, myKey=$myKey")

            // Step 2: Call mymovieapi to get quality items
            val apiUrl = "$latestBase/mymovieapi?$idType=$finalId&key=$myKey"
            Log.d("Techinmind", "API URL: $apiUrl")

            val apiResponse = try {
                app.get(apiUrl, headers = headers).text
            } catch (e: Exception) {
                Log.e("Techinmind", "API call failed: ${e.message}")
                null
            }

            if (apiResponse.isNullOrBlank()) {
                Log.e("Techinmind", "Empty API response")
                extractDirectLinksFromEmbed(embedHtml, latestBase, headers,
                    subtitleCallback, callback)
                return
            }

            val apiJson = try { JSONObject(apiResponse) } catch (_: Exception) { null }
            if (apiJson == null || !apiJson.optBoolean("success", false)) {
                Log.e("Techinmind", "API response not successful")
                extractDirectLinksFromEmbed(embedHtml, latestBase, headers,
                    subtitleCallback, callback)
                return
            }

            val dataArray = apiJson.optJSONArray("data")
            if (dataArray == null || dataArray.length() == 0) {
                Log.e("Techinmind", "No quality items in API response")
                extractDirectLinksFromEmbed(embedHtml, latestBase, headers,
                    subtitleCallback, callback)
                return
            }

            Log.d("Techinmind", "Found ${dataArray.length()} quality items")

            // Step 3: For each quality item, fetch the svid page and extract server links
            val ssnBase = latestBase.replace("stream.", "ssn.")

            for (i in 0 until dataArray.length()) {
                val item = dataArray.optJSONObject(i) ?: continue
                val fileslug = item.optString("fileslug", "")
                val filename = item.optString("filename", "")
                val fsize = item.optString("fsize", "")

                if (fileslug.isBlank()) continue

                Log.d("Techinmind", "Processing: $filename ($fsize) — slug: $fileslug")

                try {
                    // Fetch svid page
                    val evidUrl = "$ssnBase/evid/$fileslug"
                    val svidResponse = app.get(evidUrl, headers = headers, allowRedirects = true)
                    val svidHtml = svidResponse.text

                    // Extract quality from filename
                    val quality = when {
                        filename.contains("2160", ignoreCase = true) || filename.contains("4K", ignoreCase = true) -> Qualities.P2160.value
                        filename.contains("1080", ignoreCase = true) -> Qualities.P1080.value
                        filename.contains("720", ignoreCase = true) -> Qualities.P720.value
                        filename.contains("480", ignoreCase = true) -> Qualities.P480.value
                        filename.contains("360", ignoreCase = true) -> Qualities.P360.value
                        else -> Qualities.P1080.value
                    }

                    // Extract download link (ddn.iqsmartgames.com/file/...)
                    val downloadRegex = Regex("""href=["'](https?://[^"']*iqsmartgames\.com/file/[^"']+)["']""")
                    val downloadMatch = downloadRegex.find(svidHtml)
                    if (downloadMatch != null) {
                        val downloadUrl = downloadMatch.groupValues[1]
                        Log.d("Techinmind", "Found download link: $downloadUrl")
                        try {
                            // Follow redirect to get final direct download URL
                            val dlResponse = app.get(downloadUrl, headers = headers, allowRedirects = false)
                            val finalUrl = dlResponse.headers["location"] ?: downloadUrl
                            val directUrl = if (finalUrl.startsWith("http")) finalUrl else downloadUrl

                            callback.invoke(
                                newExtractorLink(
                                    "MultiMovies",
                                    "MultiMovies [$fsize]",
                                    directUrl,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = ssnBase
                                    this.quality = quality
                                }
                            )
                        } catch (e: Exception) {
                            Log.e("Techinmind", "Download follow failed: ${e.message}")
                            callback.invoke(
                                newExtractorLink(
                                    "MultiMovies",
                                    "MultiMovies [$fsize]",
                                    downloadUrl,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = ssnBase
                                    this.quality = quality
                                }
                            )
                        }
                    }

                    // Extract server links from data-link attributes
                    val serverRegex = Regex("""data-link=["'](https?://[^"']+)["']""")
                    val serverMatches = serverRegex.findAll(svidHtml)

                    for (match in serverMatches) {
                        val serverUrl = match.groupValues[1]
                        Log.d("Techinmind", "Found server link: $serverUrl")
                        try {
                            loadExtractor(serverUrl, "$ssnBase/", subtitleCallback, callback)
                        } catch (e: Exception) {
                            Log.e("Techinmind", "Extractor failed for $serverUrl: ${e.message}")
                        }
                    }

                } catch (e: Exception) {
                    Log.e("Techinmind", "Failed to process slug $fileslug: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e("Techinmind", "Overall extraction failed: ${e.message}")
        }
    }

    /**
     * Fallback: extract direct links from the embed page HTML
     * (quality links from #quality-links div, data-link attributes)
     */
    private suspend fun extractDirectLinksFromEmbed(
        embedHtml: String,
        baseUrl: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val ssnBase = baseUrl.replace("stream.", "ssn.")

            // Extract links from data-link attributes in quality-links div
            val linkRegex = Regex("""data-link=["'](https?://[^"']+)["']""")
            val linkMatches = linkRegex.findAll(embedHtml)

            for (match in linkMatches) {
                val link = match.groupValues[1]
                if (link.contains("techinmind") && link.contains("/evid/")) {
                    // This is an evid link — follow it to get server links
                    try {
                        val svidResponse = app.get(link, headers = headers, allowRedirects = true)
                        val svidHtml = svidResponse.text
                        val serverRegex = Regex("""data-link=["'](https?://[^"']+)["']""")
                        for (serverMatch in serverRegex.findAll(svidHtml)) {
                            val serverUrl = serverMatch.groupValues[1]
                            loadExtractor(serverUrl, "$ssnBase/", subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        Log.e("Techinmind", "Fallback evid failed: ${e.message}")
                    }
                } else {
                    loadExtractor(link, "$ssnBase/", subtitleCallback, callback)
                }
            }

        } catch (e: Exception) {
            Log.e("Techinmind", "Fallback extraction failed: ${e.message}")
        }
    }
}

// ============================================================================
// 2) Multimoviesshg — StreamHG / JWPlayer HLS extractor
//    Extracts M3U8 HLS streams from JWPlayer setup on multimoviesshg.com
// ============================================================================
class Multimoviesshg : ExtractorApi() {
    override val name = "Multimoviesshg"
    override val mainUrl = "https://multimoviesshg.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Multimoviesshg", "Starting extraction for: $url")

            // Resolve latest domain
            val latestBase = MultiMoviesProvider.getLatestUrl("multimoviesshg") ?: mainUrl
            val embedUrl = url.replace(getBaseUrl(url), latestBase)

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to (referer ?: "https://ssn.techinmind.space/"),
                "Accept" to "*/*"
            )

            val response = app.get(embedUrl, headers = headers)
            val html = response.text

            // Method 1: Extract M3U8 from JWPlayer setup — file: "..." pattern
            val m3u8Patterns = listOf(
                Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex(""""?file"?\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
                Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
            )

            for (pattern in m3u8Patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val m3u8Url = match.groupValues[1].replace("\\", "")
                    if (m3u8Url.isNotBlank()) {
                        Log.d("Multimoviesshg", "Found M3U8 via pattern: $m3u8Url")
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name HLS",
                                m3u8Url,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = embedUrl
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return
                    }
                }
            }

            // Method 2: Try unpacking obfuscated JavaScript (eval/p/a/c/k/e/d)
            val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d\)""")
            if (packedRegex.containsMatchIn(html)) {
                Log.d("Multimoviesshg", "Found packed JS, trying to unpack...")
                try {
                    val unpacked = JsUnpacker(html).unpack()
                    if (!unpacked.isNullOrBlank()) {
                        for (pattern in m3u8Patterns) {
                            val match = pattern.find(unpacked)
                            if (match != null) {
                                val m3u8Url = match.groupValues[1].replace("\\", "")
                                if (m3u8Url.isNotBlank()) {
                                    Log.d("Multimoviesshg", "Found M3U8 in unpacked JS: $m3u8Url")
                                    callback.invoke(
                                        newExtractorLink(
                                            name,
                                            "$name HLS",
                                            m3u8Url,
                                            ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = embedUrl
                                            this.quality = Qualities.P1080.value
                                        }
                                    )
                                    return
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Multimoviesshg", "JS unpack failed: ${e.message}")
                }
            }

            // Method 3: WebView extraction as last resort
            try {
                Log.d("Multimoviesshg", "Trying WebView extraction...")
                val webViewResponse = app.get(
                    embedUrl,
                    referer = referer ?: mainUrl,
                    interceptor = WebViewResolver(
                        Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|\.m3u8)""")
                    )
                )
                if (webViewResponse.url.contains("m3u8")) {
                    Log.d("Multimoviesshg", "WebView M3U8: ${webViewResponse.url}")
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name HLS",
                            webViewResponse.url,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = embedUrl
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return
                }
            } catch (e: Exception) {
                Log.e("Multimoviesshg", "WebView extraction failed: ${e.message}")
            }

            Log.e("Multimoviesshg", "All extraction methods failed for $url")
        } catch (e: Exception) {
            Log.e("Multimoviesshg", "Overall extraction failed: ${e.message}")
        }
    }
}

// ============================================================================
// 3) VidStack — Base extractor for all VidStack player domains
//    Uses /api/v1/video?id={hash} → hex-encoded AES-128-CBC → JSON with M3U8 URL
//    Subclassed by RpmHub, UnsBio, P2pPlay for domain matching
// ============================================================================
open class VidStack : ExtractorApi() {
    override val name = "VidStack"
    override val mainUrl = "https://multimovies.rpmhub.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Starting extraction for: $url")

            val baseUrl = getBaseUrl(url)
            val hash = VidStackDecryptor.extractHash(url)

            if (hash.isNullOrBlank()) {
                Log.e(name, "Could not extract video hash from URL: $url")
                return
            }

            Log.d(name, "Video hash: $hash")

            // Call API and decrypt to get full JSON
            val apiUrl = "$baseUrl/api/v1/video?id=$hash"
            val response = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to "$baseUrl/"
                )
            ).text.trim()

            if (response.isBlank() || response.startsWith("{")) {
                Log.e(name, "Invalid API response (not hex data)")
                fallbackWebView(url, referer, baseUrl, callback)
                return
            }

            val decrypted = try {
                VidStackDecryptor.decrypt(response)
            } catch (e: Exception) {
                Log.e(name, "Decryption failed: ${e.message}")
                fallbackWebView(url, referer, baseUrl, callback)
                return
            }

            Log.d(name, "Decrypted: ${decrypted.take(200)}")
            val json = JSONObject(decrypted)

            // Extract M3U8 source
            val m3u8Url = json.optString("source").takeIf { it.isNotBlank() }
            if (m3u8Url != null) {
                Log.d(name, "Found M3U8: $m3u8Url")
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name HLS",
                        m3u8Url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = baseUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                Log.e(name, "No source in decrypted JSON")
                fallbackWebView(url, referer, baseUrl, callback)
            }

            // Extract subtitles if available
            val subtitleObj = json.optJSONObject("subtitle")
            if (subtitleObj != null) {
                val keys = subtitleObj.keys()
                while (keys.hasNext()) {
                    val lang = keys.next()
                    val subUrl = subtitleObj.optString(lang)
                    if (!subUrl.isNullOrBlank()) {
                        val fullSubUrl = if (subUrl.startsWith("http")) subUrl
                            else "$baseUrl${subUrl.substringBefore("#")}"
                        subtitleCallback.invoke(
                            SubtitleFile(lang, fullSubUrl)
                        )
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(name, "Overall extraction failed: ${e.message}")
        }
    }

    private suspend fun fallbackWebView(
        url: String, referer: String?, baseUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Trying WebView fallback...")
            val webViewResponse = app.get(
                url,
                referer = referer ?: mainUrl,
                interceptor = WebViewResolver(Regex("""(\.m3u8)"""))
            )
            if (webViewResponse.url.contains("m3u8")) {
                callback.invoke(
                    newExtractorLink(name, "$name HLS", webViewResponse.url, ExtractorLinkType.M3U8) {
                        this.referer = baseUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "WebView fallback failed: ${e.message}")
        }
    }
}

// Domain-specific subclasses for Cloudstream's loadExtractor matching
class RpmHub : VidStack() {
    override val name = "RpmHub"
    override val mainUrl = "https://multimovies.rpmhub.site"
}

class UnsBio : VidStack() {
    override val name = "UnsBio"
    override val mainUrl = "https://server1.uns.bio"
}

class P2pPlay : VidStack() {
    override val name = "P2pPlay"
    override val mainUrl = "https://multimovies.p2pplay.pro"
}

// ============================================================================
// 6) SmoothPre — Generic smooth streaming fallback extractor
// ============================================================================
class SmoothPre : ExtractorApi() {
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
            Log.d("SmoothPre", "Starting extraction for: $url")

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to (referer ?: "https://multimovies.sarl/")
            )

            val response = app.get(url, headers = headers, allowRedirects = true)
            val html = response.text

            // Extract M3U8/MP4/video links
            val streamPatterns = listOf(
                Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["'](https?://[^"']+)["']"""),
                Regex("""source\s*[:=]\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)"""),
                Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""")
            )

            for (pattern in streamPatterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val streamUrl = match.groupValues[1].replace("\\", "")
                    if (streamUrl.isNotBlank()) {
                        val linkType = if (streamUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        Log.d("SmoothPre", "Found stream: $streamUrl")
                        callback.invoke(
                            newExtractorLink(name, name, streamUrl, linkType) {
                                this.referer = url
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return
                    }
                }
            }

            // Try packed JS
            if (html.contains("eval(function(p,a,c,k,e,d)")) {
                try {
                    val unpacked = JsUnpacker(html).unpack()
                    if (!unpacked.isNullOrBlank()) {
                        for (pattern in streamPatterns) {
                            val match = pattern.find(unpacked)
                            if (match != null) {
                                val streamUrl = match.groupValues[1].replace("\\", "")
                                if (streamUrl.isNotBlank()) {
                                    val linkType = if (streamUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    callback.invoke(
                                        newExtractorLink(name, name, streamUrl, linkType) {
                                            this.referer = url
                                            this.quality = Qualities.P1080.value
                                        }
                                    )
                                    return
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SmoothPre", "Unpack failed: ${e.message}")
                }
            }

            Log.e("SmoothPre", "All extraction methods failed for $url")
        } catch (e: Exception) {
            Log.e("SmoothPre", "Overall extraction failed: ${e.message}")
        }
    }
}
