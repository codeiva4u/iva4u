package com.phisher98

import android.util.Base64
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
    return URI(url).let { "${it.scheme}://${it.host}" }
}

fun getQualityFromName(name: String): Int {
    val upper = name.uppercase()
    return when {
        upper.contains("1080P") -> Qualities.P1080.value
        upper.contains("720P") -> Qualities.P720.value
        upper.contains("480P") -> Qualities.P480.value
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
        upper.contains("480P") -> 5
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

// ====== Techinmind Helper (SVid Page API) ======

/**
 * Calls the ssn.techinmind.space embedhelper API to get all server links
 * Returns list of pairs (serverUrl, sourceKey)
 */
suspend fun fetchSvidServerLinks(
    fileslug: String,
    ssnBaseUrl: String = "https://ssn.techinmind.space"
): List<Pair<String, String>> {
    val serverLinks = mutableListOf<Pair<String, String>>()
    try {
        Log.d("TechinmindHelper", "Fetching server links for fileslug: $fileslug")

        // Call the embedhelper API (reverse-engineered from piliyerxnew.js)
        val apiUrl = "$ssnBaseUrl/embedhelper.php"
        val response = app.post(
            apiUrl,
            data = mapOf(
                "sid" to fileslug,
                "UserFavSite" to "",
                "currentDomain" to "[]"
            ),
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Referer" to "$ssnBaseUrl/svid/",
                "Origin" to ssnBaseUrl
            )
        )

        val json = JSONObject(response.text)

        // DDN direct download link
        val ddnUrl = "https://ddn.iqsmartgames.com/file/$fileslug"
        serverLinks.add(Pair(ddnUrl, "ddn"))

        // Parse mresult (Base64 encoded JSON with server-specific file IDs)
        val mresultB64 = json.optString("mresult", "")
        val siteUrlsObj = json.optJSONObject("siteUrls")

        if (mresultB64.isNotEmpty() && siteUrlsObj != null) {
            try {
                val mresultJson = JSONObject(
                    String(Base64.decode(mresultB64, Base64.DEFAULT))
                )

                val keys = mresultJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val fileCode = mresultJson.optString(key, "")
                    val siteUrl = siteUrlsObj.optString(key, "")

                    if (fileCode.isNotEmpty() && siteUrl.isNotEmpty()) {
                        val fullUrl = "$siteUrl$fileCode"
                        serverLinks.add(Pair(fullUrl, key))
                        Log.d("TechinmindHelper", "Server [$key]: $fullUrl")
                    }
                }
            } catch (e: Exception) {
                Log.e("TechinmindHelper", "Error parsing mresult: ${e.message}")
            }
        }

        Log.d("TechinmindHelper", "Total server links found: ${serverLinks.size}")
    } catch (e: Exception) {
        Log.e("TechinmindHelper", "API error: ${e.message}")
        // Fallback: at least return DDN link
        val ddnUrl = "https://ddn.iqsmartgames.com/file/$fileslug"
        serverLinks.add(Pair(ddnUrl, "ddn"))
    }
    return serverLinks
}

// ====== Extractor Classes ======

/**
 * DDN (ddn.iqsmartgames.com) - Direct download via redirect chain
 * Flow: /file/{slug} → 302 → /files/{token} → POST form → direct download
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

            // Step 1: Follow redirect /file/{slug} → /files/{token}
            val response = app.get(url, allowRedirects = true)
            val finalUrl = response.url
            val doc = response.document

            Log.d(name, "Redirected to: $finalUrl")

            // Step 2: Try to find direct download link from forms
            // The page has a form that POSTs to get the download
            val forms = doc.select("form")
            for (form in forms) {
                val action = form.attr("action")
                if (action.contains("/cldst") || action.contains("/stream/")) {
                    // Try POST to cldst endpoint for CloudStream
                    try {
                        val slug = url.substringAfterLast("/")
                        val cldstResponse = app.post(
                            "$mainUrl/cldst",
                            data = mapOf("slug" to slug),
                            referer = finalUrl,
                            allowRedirects = false
                        )
                        val dlUrl = cldstResponse.headers["Location"]
                        if (!dlUrl.isNullOrBlank() && dlUrl.startsWith("http")) {
                            callback.invoke(
                                newExtractorLink(
                                    name, "$name Direct Download", dlUrl,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            return
                        }
                    } catch (e: Exception) {
                        Log.d(name, "cldst attempt: ${e.message}")
                    }
                }
            }

            // Step 3: Try POST to the files page itself
            try {
                val postResponse = app.post(
                    finalUrl,
                    referer = finalUrl,
                    allowRedirects = false
                )
                val dlLocation = postResponse.headers["Location"]
                if (!dlLocation.isNullOrBlank() && dlLocation.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink(
                            name, "$name Download", dlLocation,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }
            } catch (e: Exception) {
                Log.d(name, "POST attempt: ${e.message}")
            }

            // Step 4: Fallback - use the final redirect URL directly
            callback.invoke(
                newExtractorLink(
                    name, name, finalUrl,
                    ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            Log.e(name, "Extraction failed: ${e.message}")
        }
    }
}

/**
 * Multimoviesshg (multimoviesshg.com / StreamHG)
 * Flow: /e/{id} → convert to /f/{id} → parse quality download links
 * Download links: /f/{id}_h (1080p), /f/{id}_n (720p), /f/{id}_l (480p)
 */
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
            Log.d(name, "Starting extraction: $url")

            // Convert /e/{id} to /f/{id} (download page)
            val fileId = Regex("""/[ef]/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
                ?: url.substringAfterLast("/")
            val downloadPageUrl = "$mainUrl/f/$fileId"

            Log.d(name, "Download page: $downloadPageUrl")
            val doc = app.get(downloadPageUrl, referer = referer ?: mainUrl).document

            // Parse download quality options
            val downloadItems = doc.select("a.downloadv-item")

            if (downloadItems.isEmpty()) {
                Log.d(name, "No download items found, trying direct URL")
                callback.invoke(
                    newExtractorLink(name, name, downloadPageUrl, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            for (item in downloadItems) {
                val href = item.attr("href")
                if (href.isBlank()) continue

                val qualityLabel = item.selectFirst("h5")?.text()?.trim() ?: ""
                val sizeInfo = item.selectFirst("small")?.text()?.trim() ?: ""

                val quality = when {
                    qualityLabel.contains("Full HD", true) || sizeInfo.contains("1920x1080") ->
                        Qualities.P1080.value
                    qualityLabel.contains("HD", true) || sizeInfo.contains("1280x720") ->
                        Qualities.P720.value
                    qualityLabel.contains("Normal", true) || sizeInfo.contains("852x480") ->
                        Qualities.P480.value
                    else -> Qualities.Unknown.value
                }

                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                val displayName = "$name ${qualityLabel.ifBlank { "" }} ${sizeInfo.ifBlank { "" }}".trim()

                callback.invoke(
                    newExtractorLink(name, displayName, fullUrl, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = quality
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
 * URL pattern: https://server1.uns.bio/#{hash}
 * dl=1 parameter triggers download mode
 */
class UnsBio : ExtractorApi() {
    override val name = "UnsBio"
    override val mainUrl = "https://server1.uns.bio"
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

            // Try download mode with dl=1
            val downloadUrl = "$mainUrl/#$hash&dl=1"
            val baseUrl = getBaseUrl(url)

            // Fetch the page to find API endpoint or direct link
            val pageResponse = app.get(
                "$baseUrl/dl/$hash",
                referer = referer ?: url,
                allowRedirects = true
            )

            // Check for redirect to actual file
            val finalUrl = pageResponse.url
            if (finalUrl.contains(".mp4") || finalUrl.contains(".mkv") || finalUrl.contains(".avi")) {
                callback.invoke(
                    newExtractorLink(name, "$name Download", finalUrl, ExtractorLinkType.VIDEO) {
                        this.referer = baseUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            // Parse page for download link
            val doc = pageResponse.document
            val dlLink = doc.selectFirst("a[href*=download], a.download-btn, a[download], a.btn-download")
                ?.attr("href")

            if (!dlLink.isNullOrBlank()) {
                val fullDlUrl = if (dlLink.startsWith("http")) dlLink else "$baseUrl$dlLink"
                callback.invoke(
                    newExtractorLink(name, "$name Download", fullDlUrl, ExtractorLinkType.VIDEO) {
                        this.referer = baseUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            // Try API endpoint pattern
            try {
                val apiResponse = app.post(
                    "$baseUrl/api/file/$hash",
                    data = mapOf("hash" to hash, "dl" to "1"),
                    referer = url
                )
                val json = JSONObject(apiResponse.text)
                val fileUrl = json.optString("url", "")
                    .ifBlank { json.optString("file", "") }
                    .ifBlank { json.optString("source", "") }
                    .ifBlank { json.optString("download", "") }

                if (fileUrl.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(name, "$name Download", fileUrl, ExtractorLinkType.VIDEO) {
                            this.referer = baseUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }
            } catch (e: Exception) {
                Log.d(name, "API attempt: ${e.message}")
            }

            // Fallback: use download URL directly
            callback.invoke(
                newExtractorLink(name, name, downloadUrl, ExtractorLinkType.VIDEO) {
                    this.referer = baseUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            Log.e(name, "Extraction failed: ${e.message}")
        }
    }
}

/**
 * RpmHub (multimovies.rpmhub.site / RpmShare)
 * URL pattern: https://multimovies.rpmhub.site/#{hash}
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

            // Try fetching page with the hash as path
            try {
                val response = app.get(
                    "$baseUrl/$hash",
                    referer = referer ?: url,
                    allowRedirects = true
                )
                val doc = response.document

                // Look for video source or download link
                val videoSrc = doc.selectFirst("video source")?.attr("src")
                    ?: doc.selectFirst("source[src]")?.attr("src")

                if (!videoSrc.isNullOrBlank()) {
                    val fullUrl = if (videoSrc.startsWith("http")) videoSrc else "$baseUrl$videoSrc"
                    callback.invoke(
                        newExtractorLink(name, name, fullUrl, ExtractorLinkType.VIDEO) {
                            this.referer = baseUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }

                // Try to find download link in page
                val dlLink = doc.selectFirst("a[href*=download], a.download-btn, a[download]")
                    ?.attr("href")
                if (!dlLink.isNullOrBlank()) {
                    val fullUrl = if (dlLink.startsWith("http")) dlLink else "$baseUrl$dlLink"
                    callback.invoke(
                        newExtractorLink(name, "$name Download", fullUrl, ExtractorLinkType.VIDEO) {
                            this.referer = baseUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }
            } catch (e: Exception) {
                Log.d(name, "Page fetch: ${e.message}")
            }

            // Try API call
            try {
                val apiResponse = app.post(
                    "$baseUrl/api/source/$hash",
                    data = mapOf("hash" to hash),
                    referer = url
                )
                val json = JSONObject(apiResponse.text)
                val fileUrl = json.optString("url", "")
                    .ifBlank { json.optString("file", "") }
                    .ifBlank { json.optString("source", "") }

                if (fileUrl.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(name, name, fileUrl, ExtractorLinkType.VIDEO) {
                            this.referer = baseUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }
            } catch (e: Exception) {
                Log.d(name, "API attempt: ${e.message}")
            }

            // Fallback
            callback.invoke(
                newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                    this.referer = baseUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            Log.e(name, "Extraction failed: ${e.message}")
        }
    }
}

/**
 * P2pPlay (multimovies.p2pplay.pro / StreamP2p)
 * URL pattern: https://multimovies.p2pplay.pro/#{hash}
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

            // Try direct page fetch
            try {
                val response = app.get(
                    "$baseUrl/$hash",
                    referer = referer ?: url,
                    allowRedirects = true
                )
                val doc = response.document

                val videoSrc = doc.selectFirst("video source")?.attr("src")
                    ?: doc.selectFirst("source[src]")?.attr("src")

                if (!videoSrc.isNullOrBlank()) {
                    val fullUrl = if (videoSrc.startsWith("http")) videoSrc else "$baseUrl$videoSrc"
                    callback.invoke(
                        newExtractorLink(name, name, fullUrl, ExtractorLinkType.VIDEO) {
                            this.referer = baseUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }

                val dlLink = doc.selectFirst("a[href*=download], a.download-btn, a[download]")
                    ?.attr("href")
                if (!dlLink.isNullOrBlank()) {
                    val fullUrl = if (dlLink.startsWith("http")) dlLink else "$baseUrl$dlLink"
                    callback.invoke(
                        newExtractorLink(name, "$name Download", fullUrl, ExtractorLinkType.VIDEO) {
                            this.referer = baseUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }
            } catch (e: Exception) {
                Log.d(name, "Page fetch: ${e.message}")
            }

            // Try API
            try {
                val apiResponse = app.post(
                    "$baseUrl/api/source/$hash",
                    data = mapOf("hash" to hash),
                    referer = url
                )
                val json = JSONObject(apiResponse.text)
                val fileUrl = json.optString("url", "")
                    .ifBlank { json.optString("file", "") }

                if (fileUrl.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(name, name, fileUrl, ExtractorLinkType.VIDEO) {
                            this.referer = baseUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }
            } catch (e: Exception) {
                Log.d(name, "API attempt: ${e.message}")
            }

            // Fallback
            callback.invoke(
                newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                    this.referer = baseUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            Log.e(name, "Extraction failed: ${e.message}")
        }
    }
}

/**
 * SmoothPre (smoothpre.com / EarnVids)
 * URL pattern: https://smoothpre.com/v/{id}
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

            // Look for video source
            val videoSrc = doc.selectFirst("video source")?.attr("src")
                ?: doc.selectFirst("source[src]")?.attr("src")

            if (!videoSrc.isNullOrBlank()) {
                val fullUrl = if (videoSrc.startsWith("http")) videoSrc else "$mainUrl$videoSrc"
                callback.invoke(
                    newExtractorLink(name, name, fullUrl, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            // Check for packed/obfuscated JS with file URLs
            val pageHtml = doc.html()
            val fileRegex = Regex("""(https?://[^\s"'<>]+\.(mp4|mkv|avi|m4v)[^\s"'<>]*)""")
            val fileMatch = fileRegex.find(pageHtml)
            if (fileMatch != null) {
                callback.invoke(
                    newExtractorLink(name, name, fileMatch.groupValues[1], ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            // Try download link
            val dlLink = doc.selectFirst("a[href*=download], a.download-btn, a[download]")
                ?.attr("href")
            if (!dlLink.isNullOrBlank()) {
                val fullUrl = if (dlLink.startsWith("http")) dlLink else "$mainUrl$dlLink"
                callback.invoke(
                    newExtractorLink(name, "$name Download", fullUrl, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            // Fallback
            callback.invoke(
                newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            Log.e(name, "Extraction failed: ${e.message}")
        }
    }
}
