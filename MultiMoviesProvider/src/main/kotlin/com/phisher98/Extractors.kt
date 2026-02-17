package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI

// ====== Helper Functions ======

fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (e: Exception) {
        ""
    }
}

fun getQualityFromName(name: String): Int {
    val upper = name.uppercase()
    return when {
        upper.contains("1080P") || upper.contains("FULL HD") -> Qualities.P1080.value
        upper.contains("720P") || upper.contains("HD QUALITY") -> Qualities.P720.value
        upper.contains("480P") || upper.contains("NORMAL") -> Qualities.P480.value
        upper.contains("360P") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}

/**
 * Quality priority: lower number = higher priority
 * 1: X264 1080p, 2: X264 720p, 3: X265/HEVC 1080p
 * 4: X265/HEVC 720p, 5: 480p, 6: others
 */
fun getQualityPriority(name: String): Int {
    val upper = name.uppercase()
    val isHevc = upper.contains("X265") || upper.contains("HEVC")
    return when {
        upper.contains("1080P") && !isHevc -> 1
        upper.contains("720P") && !isHevc -> 2
        upper.contains("1080P") && isHevc -> 3
        upper.contains("720P") && isHevc -> 4
        upper.contains("480P") || upper.contains("NORMAL") -> 5
        else -> 6
    }
}

/**
 * Parse file size from text like "3.3 GB" or "839.4 MB"
 * Returns size in MB for comparison
 */
fun parseFileSizeMB(sizeText: String): Double {
    val regex = Regex("""([\d.]+)\s*(GB|MB|KB)""", RegexOption.IGNORE_CASE)
    val match = regex.find(sizeText) ?: return Double.MAX_VALUE
    val value = match.groupValues[1].toDoubleOrNull() ?: return Double.MAX_VALUE
    return when (match.groupValues[2].uppercase()) {
        "GB" -> value * 1024
        "MB" -> value
        "KB" -> value / 1024
        else -> Double.MAX_VALUE
    }
}

/**
 * Check if URL is a streaming URL (should be blocked for download-only mode)
 */
fun isStreamingUrl(url: String): Boolean {
    return url.contains(".m3u8", ignoreCase = true) ||
           url.contains("/hls/", ignoreCase = true) ||
           url.contains(".mpd", ignoreCase = true)
}

// ====== SVID Page Parser ======

/**
 * Follows evid redirect to SVID page and parses server links from HTML.
 *
 * Verified SVID page structure (from deep scraping):
 * - DDN download link: a.dlvideoLinks[href]
 *   e.g. https://ddn.iqsmartgames.com/file/rzujlij
 * - Server items: li.server-item[data-link][data-source-key]
 *   e.g. data-source-key="smwh" -> multimoviesshg.com/e/{id}
 *        data-source-key="rpmshre" -> multimovies.rpmhub.site/#{hash}
 *        data-source-key="upnshr" -> server1.uns.bio/#{hash}
 *        data-source-key="strmp2" -> multimovies.p2pplay.pro/#{hash}
 * - Hidden input: #gdmrfid contains fileslug
 *
 * @param fileslug The file slug from the API (e.g. "rzujlij")
 * @param baseEvidUrl The base URL for evid pages (e.g. "https://ssn.techinmind.space/evid/")
 * @return List of pairs (serverUrl, sourceKey)
 */
suspend fun fetchSvidServerLinks(
    fileslug: String,
    baseEvidUrl: String = "https://ssn.techinmind.space/evid/"
): List<Pair<String, String>> {
    val serverLinks = mutableListOf<Pair<String, String>>()
    try {
        Log.d("SvidParser", "Fetching SVID page for fileslug: $fileslug")

        // Construct evid URL — follows redirect to SVID page
        // evid URL: https://ssn.techinmind.space/evid/rzujlij
        // Redirects to: https://ssn.techinmind.space/svid/{encoded}
        val evidUrl = if (fileslug.startsWith("http")) fileslug else "$baseEvidUrl$fileslug"
        val response = app.get(evidUrl, allowRedirects = true)
        val doc = response.document
        val svidUrl = response.url
        Log.d("SvidParser", "SVID page URL: $svidUrl")

        // Extract DDN download link (highest priority — direct download)
        val ddnLink = doc.selectFirst("a.dlvideoLinks")?.attr("href")?.trim()
        if (!ddnLink.isNullOrBlank()) {
            serverLinks.add(Pair(ddnLink, "ddn"))
            Log.d("SvidParser", "DDN download link: $ddnLink")
        }

        // Extract all server links from li.server-item elements
        val serverItems = doc.select("li.server-item[data-link]")
        for (item in serverItems) {
            val serverUrl = item.attr("data-link").trim()
            val sourceKey = item.attr("data-source-key").trim()
            if (serverUrl.isNotBlank()) {
                serverLinks.add(Pair(serverUrl, sourceKey))
                Log.d("SvidParser", "Server [$sourceKey]: $serverUrl")
            }
        }

        // Fallback: if no links found at all, construct DDN URL from fileslug
        if (serverLinks.isEmpty()) {
            val gdmrfid = doc.selectFirst("input#gdmrfid")?.attr("value")?.trim()
            val slug = gdmrfid ?: fileslug
            val ddnUrl = "https://ddn.iqsmartgames.com/file/$slug"
            serverLinks.add(Pair(ddnUrl, "ddn"))
            Log.d("SvidParser", "Fallback DDN URL: $ddnUrl")
        }

        Log.d("SvidParser", "Total server links found: ${serverLinks.size}")
    } catch (e: Exception) {
        Log.e("SvidParser", "SVID page error: ${e.message}")
        // Fallback to direct DDN URL
        val ddnUrl = "https://ddn.iqsmartgames.com/file/$fileslug"
        serverLinks.add(Pair(ddnUrl, "ddn"))
    }
    return serverLinks
}

// ====== Extractor Classes ======

/**
 * DDN (ddn.iqsmartgames.com) — Direct download via Cloudflare Workers
 *
 * Verified flow (from deep scraping):
 * 1. GET /file/{slug} → 302 redirect → /files/{encoded}
 * 2. Final page contains JS variable: var fileurl = "https://...workers.dev/?id=...&name=filename.mkv"
 * 3. The fileurl is a direct Cloudflare Workers download URL — no streaming, instant play
 *
 * Fallback chain:
 * - POST /cldst form with hidden fields (slug, source_url, original_name, stored_name, size, uidd)
 * - POST to the final page itself
 * - Use the redirected URL as-is
 */
class DDNIqsmartgames : ExtractorApi() {
    override val name = "DDNIqsmartgames"
    override val mainUrl = "https://ddn.iqsmartgames.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Starting extraction: $url")

            // Follow redirect: /file/{slug} → /files/{encoded}
            val response = app.get(url, allowRedirects = true)
            val finalUrl = response.url
            val html = response.text

            Log.d(name, "Redirected to: $finalUrl")

            // Primary: Extract fileurl JS variable — direct Cloudflare Workers download URL
            // Pattern verified: var fileurl = "https://icy-feather-221c.jakcminasi.workers.dev/?id=...&name=filename.mkv"
            val fileurlMatch = Regex("""(?:var|let|const)\s+fileurl\s*=\s*["']([^"']+)["']""")
                .find(html)

            if (fileurlMatch != null) {
                // Unescape JSON-encoded slashes (\/ → /) from the JS variable value
                val directUrl = fileurlMatch.groupValues[1].replace("\\/", "/")
                Log.d(name, "Found direct download URL via fileurl")

                if (!isStreamingUrl(directUrl) && directUrl.startsWith("http")) {
                    // Extract filename from URL &name= parameter for display
                    val fileName = try {
                        Regex("""[&?]name=([^&]+)""").find(directUrl)?.groupValues?.get(1)
                            ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
                    } catch (_: Exception) { "" }

                    val quality = getQualityFromName(fileName)

                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name ${fileName.ifBlank { "Direct" }}".trim(),
                            directUrl,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = finalUrl
                            this.quality = quality
                        }
                    )
                    return
                }
            }

            // Fallback 1: POST /cldst form with hidden fields
            val doc = response.document
            val cldstForm = doc.selectFirst("form[action*=cldst]")
            if (cldstForm != null) {
                val formData = mutableMapOf<String, String>()
                cldstForm.select("input[type=hidden]").forEach { input ->
                    val inputName = input.attr("name")
                    val inputValue = input.attr("value")
                    if (inputName.isNotBlank()) {
                        formData[inputName] = inputValue
                    }
                }

                if (formData.isNotEmpty()) {
                    val actionUrl = cldstForm.attr("action").let { action ->
                        if (action.startsWith("http")) action
                        else "$mainUrl${if (action.startsWith("/")) "" else "/"}$action"
                    }

                    try {
                        val cldstResponse = app.post(
                            actionUrl,
                            data = formData,
                            referer = finalUrl,
                            allowRedirects = false
                        )
                        val location = cldstResponse.headers["Location"]
                        if (!location.isNullOrBlank() && location.startsWith("http") && !isStreamingUrl(location)) {
                            callback.invoke(
                                newExtractorLink(name, "$name Download", location, ExtractorLinkType.VIDEO) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            return
                        }
                    } catch (e: Exception) {
                        Log.d(name, "cldst POST failed: ${e.message}")
                    }
                }
            }

            // Fallback 2: POST to the page itself
            try {
                val postResponse = app.post(
                    finalUrl,
                    referer = finalUrl,
                    allowRedirects = false
                )
                val dlLocation = postResponse.headers["Location"]
                if (!dlLocation.isNullOrBlank() && dlLocation.startsWith("http") && !isStreamingUrl(dlLocation)) {
                    callback.invoke(
                        newExtractorLink(name, "$name Download", dlLocation, ExtractorLinkType.VIDEO) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }
            } catch (e: Exception) {
                Log.d(name, "POST fallback: ${e.message}")
            }

            // Final fallback: use the redirected URL itself
            if (!isStreamingUrl(finalUrl) && finalUrl != url) {
                callback.invoke(
                    newExtractorLink(name, name, finalUrl, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Extraction failed: ${e.message}")
        }
    }
}

/**
 * Multimoviesshg (multimoviesshg.com / StreamHG)
 *
 * URL from SVID page: /e/{id} (embed URL)
 * StreamHG typically has: /e/{id} (embed), /d/{id} (download), /f/{id} (file info)
 *
 * Strategy (in order):
 * 1. Try /d/{id} download page — look for direct download link or form
 * 2. Try /f/{id} file info page — look for quality options (downloadv-item links)
 * 3. Parse /e/{id} embed page — extract video source URLs or JS variables
 */
class Multimoviesshg : ExtractorApi() {
    override val name = "Multimoviesshg"
    override val mainUrl = MultiMoviesProvider.getMultimoviesshgUrl()
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Starting extraction: $url")

            // Extract file ID from URL (/e/, /f/, or /d/ patterns)
            val fileId = Regex("""/[efd]/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)

            if (fileId == null) {
                Log.e(name, "Could not extract file ID from URL: $url")
                return
            }

            Log.d(name, "File ID: $fileId")

            // Strategy 1: Try download page /d/{id}
            try {
                val dlPageUrl = "$mainUrl/d/$fileId"
                val dlResponse = app.get(dlPageUrl, referer = referer ?: mainUrl, allowRedirects = true)
                val dlDoc = dlResponse.document

                // Look for direct download link
                val dlLink = dlDoc.selectFirst("a#download-btn, a.download-btn, a[href*='/d/'], a[download]")
                    ?.attr("href")
                if (!dlLink.isNullOrBlank() && !isStreamingUrl(dlLink)) {
                    val fullUrl = if (dlLink.startsWith("http")) dlLink else "$mainUrl$dlLink"
                    callback.invoke(
                        newExtractorLink(name, "$name Download", fullUrl, ExtractorLinkType.VIDEO) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }

                // Look for form-based download (StreamHG F1 form)
                val form = dlDoc.selectFirst("form#F1, form[name=F1]")
                if (form != null) {
                    val formData = mutableMapOf<String, String>()
                    form.select("input[type=hidden], input[name]").forEach { input ->
                        val inputName = input.attr("name")
                        val inputValue = input.attr("value")
                        if (inputName.isNotBlank()) {
                            formData[inputName] = inputValue
                        }
                    }

                    if (formData.isNotEmpty()) {
                        val formResponse = app.post(
                            dlPageUrl,
                            data = formData,
                            referer = dlPageUrl,
                            allowRedirects = true
                        )
                        val formDoc = formResponse.document

                        // After form submit, look for direct link
                        val directLink = formDoc.selectFirst("a[href*='://'], a.download-btn")?.attr("href")
                        if (!directLink.isNullOrBlank() && !isStreamingUrl(directLink)) {
                            val fullUrl = if (directLink.startsWith("http")) directLink else "$mainUrl$directLink"
                            callback.invoke(
                                newExtractorLink(name, "$name Direct", fullUrl, ExtractorLinkType.VIDEO) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(name, "Download page attempt: ${e.message}")
            }

            // Strategy 2: Try file info page /f/{id}
            try {
                val fPageUrl = "$mainUrl/f/$fileId"
                val fResponse = app.get(fPageUrl, referer = referer ?: mainUrl)
                val fDoc = fResponse.document

                // Look for quality options (downloadv-item links with h5 + small)
                val downloadItems = fDoc.select("a.downloadv-item")
                if (downloadItems.isNotEmpty()) {
                    for (item in downloadItems) {
                        val href = item.attr("href").trim()
                        if (href.isBlank()) continue

                        val qualityLabel = item.selectFirst("h5")?.text()?.trim() ?: ""
                        val sizeInfo = item.selectFirst("small")?.text()?.trim() ?: ""
                        val quality = getQualityFromName("$qualityLabel $sizeInfo")
                        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                        val displayName = "$name ${qualityLabel.ifBlank { "Video" }} ${sizeInfo.ifBlank { "" }}".trim()

                        callback.invoke(
                            newExtractorLink(name, displayName, fullUrl, ExtractorLinkType.VIDEO) {
                                this.referer = mainUrl
                                this.quality = quality
                            }
                        )
                    }
                    return
                }
            } catch (e: Exception) {
                Log.d(name, "File page attempt: ${e.message}")
            }

            // Strategy 3: Parse embed page /e/{id} for video source
            try {
                val embedUrl = "$mainUrl/e/$fileId"
                val embedResponse = app.get(embedUrl, referer = referer ?: mainUrl)
                val embedHtml = embedResponse.text

                // Look for direct video file URLs in page source
                val videoUrlRegex = Regex("""(https?://[^\s"'<>]+\.(mp4|mkv|avi|m4v)[^\s"'<>]*)""")
                val videoMatch = videoUrlRegex.find(embedHtml)
                if (videoMatch != null && !isStreamingUrl(videoMatch.groupValues[1])) {
                    callback.invoke(
                        newExtractorLink(name, name, videoMatch.groupValues[1], ExtractorLinkType.VIDEO) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }

                // Look for source/file in JS variables
                val jsVarRegex = Regex("""(?:sources|file|src)\s*[:=]\s*["']([^"']+)["']""")
                val jsMatch = jsVarRegex.find(embedHtml)
                if (jsMatch != null) {
                    val videoUrl = jsMatch.groupValues[1]
                    if (videoUrl.startsWith("http") && !isStreamingUrl(videoUrl)) {
                        callback.invoke(
                            newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                                this.referer = embedUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return
                    }
                }
            } catch (e: Exception) {
                Log.d(name, "Embed page attempt: ${e.message}")
            }

            // Final fallback
            if (!isStreamingUrl(url)) {
                callback.invoke(
                    newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Extraction failed: ${e.message}")
        }
    }
}

/**
 * UnsBio (server1.uns.bio / UpnShare)
 *
 * URL from SVID page: https://server1.uns.bio/#{hash}
 * Hash-based SPA — the fragment is processed client-side by JavaScript.
 *
 * Strategy:
 * 1. GET the base URL to retrieve the page JS
 * 2. Try common API patterns: /api/file/{hash}, /d/{hash}, /api/source/{hash}
 * 3. Look for JS-based patterns (target1_urls, eval/packed JS)
 * 4. Fallback to passthrough URL
 */
class UnsBio : ExtractorApi() {
    override val name = "UnsBio"
    override val mainUrl = MultiMoviesProvider.getUnsBioUrl()
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Starting extraction: $url")

            val hash = url.substringAfter("#").substringBefore("&")
            if (hash.isBlank() || hash == url) {
                Log.e(name, "No hash found in URL: $url")
                return
            }

            val baseUrl = getBaseUrl(url)
            Log.d(name, "Hash: $hash, Base: $baseUrl")

            // Strategy 1: Load the base page and look for API patterns in JS
            try {
                val response = app.get(baseUrl, referer = referer ?: url, allowRedirects = true)
                val html = response.text

                // Look for API endpoint patterns in the page JS
                val apiPatterns = listOf(
                    Regex("""['"]([^'"]*api[^'"]*)\s*\+\s*(?:hash|id|slug)""", RegexOption.IGNORE_CASE),
                    Regex("""fetch\s*\(\s*['"]([^'"]+)['"]"""),
                    Regex("""axios\s*\.\s*(?:get|post)\s*\(\s*['"]([^'"]+)['"]""")
                )

                for (pattern in apiPatterns) {
                    val apiMatch = pattern.find(html)
                    if (apiMatch != null) {
                        val apiEndpoint = apiMatch.groupValues[1]
                        val apiUrl = if (apiEndpoint.startsWith("http")) {
                            "$apiEndpoint$hash"
                        } else {
                            "$baseUrl${if (apiEndpoint.startsWith("/")) "" else "/"}$apiEndpoint$hash"
                        }
                        Log.d(name, "Found API pattern: $apiUrl")

                        try {
                            val apiResponse = app.get(apiUrl, referer = url)
                            val json = JSONObject(apiResponse.text)
                            val fileUrl = json.optString("url", "")
                                .ifBlank { json.optString("file", "") }
                                .ifBlank { json.optString("source", "") }
                                .ifBlank { json.optString("direct", "") }

                            if (fileUrl.isNotBlank() && !isStreamingUrl(fileUrl)) {
                                callback.invoke(
                                    newExtractorLink(name, name, fileUrl, ExtractorLinkType.VIDEO) {
                                        this.referer = baseUrl
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                return
                            }
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.d(name, "Base page fetch: ${e.message}")
            }

            // Strategy 2: Try common API endpoints with the hash
            val commonEndpoints = listOf(
                "$baseUrl/api/file/$hash",
                "$baseUrl/api/source/$hash",
                "$baseUrl/d/$hash",
                "$baseUrl/dl/$hash"
            )

            for (endpoint in commonEndpoints) {
                try {
                    val apiResponse = app.get(endpoint, referer = url, allowRedirects = true)
                    val responseText = apiResponse.text.trim()

                    // Try JSON response
                    if (responseText.startsWith("{")) {
                        val json = JSONObject(responseText)
                        val fileUrl = json.optString("url", "")
                            .ifBlank { json.optString("file", "") }
                            .ifBlank { json.optString("source", "") }

                        if (fileUrl.isNotBlank() && !isStreamingUrl(fileUrl)) {
                            callback.invoke(
                                newExtractorLink(name, name, fileUrl, ExtractorLinkType.VIDEO) {
                                    this.referer = baseUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            return
                        }
                    }

                    // Try HTML response — look for download links
                    val apiDoc = apiResponse.document
                    val dlLink = apiDoc.selectFirst("a[href*=download], a.download-btn, a[download]")?.attr("href")
                    if (!dlLink.isNullOrBlank() && !isStreamingUrl(dlLink)) {
                        val fullUrl = if (dlLink.startsWith("http")) dlLink else "$baseUrl$dlLink"
                        callback.invoke(
                            newExtractorLink(name, "$name Download", fullUrl, ExtractorLinkType.VIDEO) {
                                this.referer = baseUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return
                    }
                } catch (_: Exception) {}
            }

            // Fallback: passthrough URL
            if (!isStreamingUrl(url)) {
                callback.invoke(
                    newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                        this.referer = baseUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Extraction failed: ${e.message}")
        }
    }
}

/**
 * RpmHub (multimovies.rpmhub.site / RpmShare)
 *
 * URL from SVID page: https://multimovies.rpmhub.site/#{hash}
 * Same hash-based SPA pattern as UnsBio.
 */
class RpmHub : ExtractorApi() {
    override val name = "RpmHub"
    override val mainUrl = "https://multimovies.rpmhub.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Starting extraction: $url")

            val hash = url.substringAfter("#").substringBefore("&")
            if (hash.isBlank() || hash == url) {
                Log.e(name, "No hash found in URL")
                return
            }

            val baseUrl = getBaseUrl(url)

            // Try common API endpoints
            val endpoints = listOf(
                "$baseUrl/api/file/$hash",
                "$baseUrl/api/source/$hash",
                "$baseUrl/d/$hash",
                "$baseUrl/dl/$hash"
            )

            for (endpoint in endpoints) {
                try {
                    val response = app.get(endpoint, referer = url, allowRedirects = true)
                    val responseText = response.text.trim()

                    if (responseText.startsWith("{")) {
                        val json = JSONObject(responseText)
                        val fileUrl = json.optString("url", "")
                            .ifBlank { json.optString("file", "") }
                            .ifBlank { json.optString("source", "") }

                        if (fileUrl.isNotBlank() && !isStreamingUrl(fileUrl)) {
                            callback.invoke(
                                newExtractorLink(name, name, fileUrl, ExtractorLinkType.VIDEO) {
                                    this.referer = baseUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            return
                        }
                    }

                    val doc = response.document
                    val dlLink = doc.selectFirst("a[href*=download], a.download-btn, a[download]")?.attr("href")
                    if (!dlLink.isNullOrBlank() && !isStreamingUrl(dlLink)) {
                        val fullUrl = if (dlLink.startsWith("http")) dlLink else "$baseUrl$dlLink"
                        callback.invoke(
                            newExtractorLink(name, "$name Download", fullUrl, ExtractorLinkType.VIDEO) {
                                this.referer = baseUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return
                    }
                } catch (_: Exception) {}
            }

            // Fallback: passthrough URL
            if (!isStreamingUrl(url)) {
                callback.invoke(
                    newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                        this.referer = baseUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Extraction failed: ${e.message}")
        }
    }
}

/**
 * P2pPlay (multimovies.p2pplay.pro / StreamP2p)
 *
 * URL from SVID page: https://multimovies.p2pplay.pro/#{hash}
 * Same hash-based SPA pattern as UnsBio and RpmHub.
 */
class P2pPlay : ExtractorApi() {
    override val name = "P2pPlay"
    override val mainUrl = "https://multimovies.p2pplay.pro"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Starting extraction: $url")

            val hash = url.substringAfter("#").substringBefore("&")
            if (hash.isBlank() || hash == url) {
                Log.e(name, "No hash found in URL")
                return
            }

            val baseUrl = getBaseUrl(url)

            // Try common API endpoints
            val endpoints = listOf(
                "$baseUrl/api/file/$hash",
                "$baseUrl/api/source/$hash",
                "$baseUrl/d/$hash",
                "$baseUrl/dl/$hash"
            )

            for (endpoint in endpoints) {
                try {
                    val response = app.get(endpoint, referer = url, allowRedirects = true)
                    val responseText = response.text.trim()

                    if (responseText.startsWith("{")) {
                        val json = JSONObject(responseText)
                        val fileUrl = json.optString("url", "")
                            .ifBlank { json.optString("file", "") }
                            .ifBlank { json.optString("source", "") }

                        if (fileUrl.isNotBlank() && !isStreamingUrl(fileUrl)) {
                            callback.invoke(
                                newExtractorLink(name, name, fileUrl, ExtractorLinkType.VIDEO) {
                                    this.referer = baseUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            return
                        }
                    }

                    val doc = response.document
                    val dlLink = doc.selectFirst("a[href*=download], a.download-btn, a[download]")?.attr("href")
                    if (!dlLink.isNullOrBlank() && !isStreamingUrl(dlLink)) {
                        val fullUrl = if (dlLink.startsWith("http")) dlLink else "$baseUrl$dlLink"
                        callback.invoke(
                            newExtractorLink(name, "$name Download", fullUrl, ExtractorLinkType.VIDEO) {
                                this.referer = baseUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return
                    }
                } catch (_: Exception) {}
            }

            // Fallback: passthrough URL
            if (!isStreamingUrl(url)) {
                callback.invoke(
                    newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                        this.referer = baseUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Extraction failed: ${e.message}")
        }
    }
}

/**
 * SmoothPre (smoothpre.com / EarnVids)
 * Generic extractor for SmoothPre-hosted files.
 */
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
            Log.d(name, "Starting extraction: $url")

            val doc = app.get(url, referer = referer ?: mainUrl).document

            val videoSrc = doc.selectFirst("video source")?.attr("src")
                ?: doc.selectFirst("source[src]")?.attr("src")

            if (!videoSrc.isNullOrBlank() && !isStreamingUrl(videoSrc)) {
                val fullUrl = if (videoSrc.startsWith("http")) videoSrc else "$mainUrl$videoSrc"
                callback.invoke(
                    newExtractorLink(name, name, fullUrl, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            val pageHtml = doc.html()
            val fileRegex = Regex("""(https?://[^\s"'<>]+\.(mp4|mkv|avi|m4v)[^\s"'<>]*)""")
            val fileMatch = fileRegex.find(pageHtml)
            if (fileMatch != null && !isStreamingUrl(fileMatch.groupValues[1])) {
                callback.invoke(
                    newExtractorLink(name, name, fileMatch.groupValues[1], ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            val dlLink = doc.selectFirst("a[href*=download], a.download-btn, a[download]")?.attr("href")
            if (!dlLink.isNullOrBlank() && !isStreamingUrl(dlLink)) {
                val fullUrl = if (dlLink.startsWith("http")) dlLink else "$mainUrl$dlLink"
                callback.invoke(
                    newExtractorLink(name, "$name Download", fullUrl, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            if (!isStreamingUrl(url)) {
                callback.invoke(
                    newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Extraction failed: ${e.message}")
        }
    }
}

/**
 * Techinmind (ssn.techinmind.space / stream.techinmind.space)
 *
 * Handles evid and svid pages directly when loaded as extractor URLs.
 * Parses server links from SVID page HTML (same logic as fetchSvidServerLinks).
 *
 * Verified SVID page structure:
 * - Server items: li.server-item[data-link][data-source-key]
 * - DDN download link: a.dlvideoLinks
 */
class Techinmind : ExtractorApi() {
    override val name = "Techinmind"
    override val mainUrl = "https://ssn.techinmind.space"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Starting extraction: $url")

            // Follow redirect if evid URL
            Log.d(name, "Fetching page: $url")
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val doc = response.document
            val finalUrl = response.url

            // Extract data-link attributes from server items
            val serverItems = doc.select("li.server-item[data-link]")

            if (serverItems.isNotEmpty()) {
                for (item in serverItems) {
                    val serverUrl = item.attr("data-link").trim()
                    val sourceKey = item.attr("data-source-key")
                    val serverName = item.selectFirst(".server-name")?.text()?.trim() ?: sourceKey

                    if (serverUrl.isNotBlank() && !isStreamingUrl(serverUrl)) {
                        Log.d(name, "Found server: $serverName -> $serverUrl")

                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name $serverName",
                                serverUrl,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.referer = finalUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
                return
            }

            // Fallback: try to find direct download link
            val downloadLink = doc.selectFirst("a.dlvideoLinks[href], a[href*='/file/']")?.attr("href")
            if (!downloadLink.isNullOrBlank() && !isStreamingUrl(downloadLink)) {
                callback.invoke(
                    newExtractorLink(name, "$name Download", downloadLink, ExtractorLinkType.VIDEO) {
                        this.referer = finalUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            if (!isStreamingUrl(url)) {
                callback.invoke(
                    newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Extraction failed: ${e.message}")
        }
    }
}
