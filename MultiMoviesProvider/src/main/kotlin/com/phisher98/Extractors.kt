package com.phisher98

import com.lagradost.cloudstream3.base64Decode
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

        val ddnUrl = "https://ddn.iqsmartgames.com/file/$fileslug"
        serverLinks.add(Pair(ddnUrl, "ddn"))

        val mresultB64 = json.optString("mresult", "")
        val siteUrlsObj = json.optJSONObject("siteUrls")

        if (mresultB64.isNotEmpty() && siteUrlsObj != null) {
            try {
                val mresultJson = JSONObject(
                    base64Decode(mresultB64)
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
        val ddnUrl = "https://ddn.iqsmartgames.com/file/$fileslug"
        serverLinks.add(Pair(ddnUrl, "ddn"))
    }
    return serverLinks
}

// ====== Extractor Classes ======

/**
 * DDN (ddn.iqsmartgames.com) - Direct download via redirect chain
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

            val response = app.get(url, allowRedirects = true)
            val finalUrl = response.url
            val doc = response.document

            Log.d(name, "Redirected to: $finalUrl")

            val forms = doc.select("form")
            for (form in forms) {
                val action = form.attr("action")
                if (action.contains("/cldst") || action.contains("/stream/")) {
                    try {
                        val slug = url.substringAfterLast("/")
                        val cldstResponse = app.post(
                            "$mainUrl/cldst",
                            data = mapOf("slug" to slug),
                            referer = finalUrl,
                            allowRedirects = false
                        )
                        val dlUrl = cldstResponse.headers["Location"]
                        if (!dlUrl.isNullOrBlank() && dlUrl.startsWith("http") && !isStreamingUrl(dlUrl)) {
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

            try {
                val postResponse = app.post(
                    finalUrl,
                    referer = finalUrl,
                    allowRedirects = false
                )
                val dlLocation = postResponse.headers["Location"]
                if (!dlLocation.isNullOrBlank() && dlLocation.startsWith("http") && !isStreamingUrl(dlLocation)) {
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

            if (!isStreamingUrl(finalUrl)) {
                callback.invoke(
                    newExtractorLink(
                        name, name, finalUrl,
                        ExtractorLinkType.VIDEO
                    ) {
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
 * Download page: /f/{id} - Shows quality options
 * Each quality: /f/{id}_h (1080p), /f/{id}_n (720p), /f/{id}_l (480p)
 * Contains form with hash that needs reCAPTCHA to get final download link
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

            val cleanUrl = url.substringBefore("?") 
            val fileIdPattern = Regex("""/f/([a-zA-Z0-9]+)""")
            val match = fileIdPattern.find(cleanUrl)
            
            if (match == null) {
                Log.e(name, "Could not extract file ID from URL: $url")
                callback.invoke(
                    newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            val fileId = match.groupValues[1]
            val qualityMode = cleanUrl.substringAfterLast("_", "")

            val downloadPageUrl = if (qualityMode.isNotEmpty() && listOf("h", "n", "l").contains(qualityMode)) {
                "$mainUrl/f/${fileId}_$qualityMode"
            } else {
                "$mainUrl/f/$fileId"
            }

            Log.d(name, "Download page: $downloadPageUrl")
            val doc = app.get(downloadPageUrl, referer = referer ?: mainUrl).document

            val downloadItems = doc.select("a.downloadv-item")

            if (downloadItems.isEmpty()) {
                val form = doc.selectFirst("form#F1")
                if (form != null) {
                    val op = form.selectFirst("input[name=op]")?.attr("value") ?: "download_orig"
                    val id = form.selectFirst("input[name=id]")?.attr("value") ?: fileId
                    val mode = form.selectFirst("input[name=mode]")?.attr("value") ?: qualityMode.ifEmpty { "h" }
                    val hash = form.selectFirst("input[name=hash]")?.attr("value") ?: ""
                    val recaptchaPub = form.selectFirst("input[name=recaptcha3_pub]")?.attr("value") ?: ""

                    val filename = doc.selectFirst("b")?.text()?.trim() 
                        ?: doc.selectFirst("h3")?.text()?.trim()
                        ?: "Video"

                    val sizeText = doc.selectFirst("small")?.text()?.trim() ?: ""

                    val quality = when {
                        mode == "h" || sizeText.contains("1080") || sizeText.contains("1920") -> Qualities.P1080.value
                        mode == "n" || sizeText.contains("720") || sizeText.contains("1280") -> Qualities.P720.value
                        mode == "l" || sizeText.contains("480") || sizeText.contains("852") -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }

                    val formActionUrl = "$downloadPageUrl?op=$op&id=$id&mode=$mode&hash=$hash"

                    Log.d(name, "Form URL: $formActionUrl")

                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name $filename ${sizeText.ifBlank { "" }}".trim(),
                            formActionUrl,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = quality
                        }
                    )
                    return
                }

                Log.d(name, "No download items or form found")
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
            callback.invoke(
                newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

/**
 * UnsBio (server1.uns.bio / UpnShare)
 * URL pattern: https://server1.uns.bio/#{hash} or #{hash}&dl=1
 * Hash-based file access with download mode
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
                Log.e(name, "No hash found in URL: $url")
                callback.invoke(
                    newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            val baseUrl = getBaseUrl(url)
            val isDownloadMode = url.contains("dl=1")

            val pageUrl = if (isDownloadMode) {
                "$mainUrl/#$hash&dl=1"
            } else {
                "$mainUrl/#$hash"
            }

            try {
                val response = app.get(pageUrl, referer = referer ?: mainUrl, allowRedirects = true)
                val finalUrl = response.url
                val doc = response.document
                val html = doc.html()

                val title = doc.selectFirst("h1")?.text()?.trim() ?: "Video"

                val buttonLink = doc.selectFirst("button.downloader-button")?.parent()?.selectFirst("a[href]")?.attr("href")

                val scriptRegex = Regex("""target1_urls\s*:\s*\[([^\]]+)\]""")
                val scriptMatch = scriptRegex.find(html)
                
                if (scriptMatch != null) {
                    val urlsContent = scriptMatch.groupValues[1]
                    val urlRegex = Regex("""['"](https?://[^'"]+)['"]""")
                    val urlMatches = urlRegex.findAll(urlsContent)
                    
                    for (urlMatch in urlMatches) {
                        val videoUrl = urlMatch.groupValues[1]
                        if (!isStreamingUrl(videoUrl)) {
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    "$name $title",
                                    videoUrl,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = baseUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }
                }

                if (buttonLink != null && buttonLink.startsWith("http")) {
                    if (!isStreamingUrl(buttonLink)) {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name Watch Online $title",
                                buttonLink,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.referer = baseUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }

                try {
                    val apiResponse = app.get(
                        "$mainUrl/d/$hash",
                        referer = pageUrl,
                        allowRedirects = true
                    )
                    val apiDoc = apiResponse.document
                    val apiHtml = apiDoc.html()

                    val apiScriptMatch = scriptRegex.find(apiHtml)
                    if (apiScriptMatch != null) {
                        val urlsContent = apiScriptMatch.groupValues[1]
                        val urlRegex = Regex("""['"](https?://[^'"]+)['"]""")
                        val urlMatches = urlRegex.findAll(urlsContent)
                        
                        for (urlMatch in urlMatches) {
                            val videoUrl = urlMatch.groupValues[1]
                            if (!isStreamingUrl(videoUrl)) {
                                callback.invoke(
                                    newExtractorLink(
                                        name,
                                        "$name API $title",
                                        videoUrl,
                                        ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = baseUrl
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(name, "API /d/ attempt: ${e.message}")
                }

                if (!isStreamingUrl(finalUrl)) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name $title",
                            pageUrl,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = baseUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }

            } catch (e: Exception) {
                Log.e(name, "Page fetch error: ${e.message}")
                callback.invoke(
                    newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Extraction failed: ${e.message}")
            callback.invoke(
                newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

/**
 * RpmHub (multimovies.rpmhub.site / RpmShare)
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

            try {
                val response = app.get(
                    "$baseUrl/$hash",
                    referer = referer ?: url,
                    allowRedirects = true
                )
                val doc = response.document

                val videoSrc = doc.selectFirst("video source")?.attr("src")
                    ?: doc.selectFirst("source[src]")?.attr("src")

                if (!videoSrc.isNullOrBlank() && !isStreamingUrl(videoSrc)) {
                    val fullUrl = if (videoSrc.startsWith("http")) videoSrc else "$baseUrl$videoSrc"
                    callback.invoke(
                        newExtractorLink(name, name, fullUrl, ExtractorLinkType.VIDEO) {
                            this.referer = baseUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }

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
            } catch (e: Exception) {
                Log.d(name, "Page fetch: ${e.message}")
            }

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

                if (fileUrl.isNotBlank() && !isStreamingUrl(fileUrl)) {
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

            try {
                val response = app.get(
                    "$baseUrl/$hash",
                    referer = referer ?: url,
                    allowRedirects = true
                )
                val doc = response.document

                val videoSrc = doc.selectFirst("video source")?.attr("src")
                    ?: doc.selectFirst("source[src]")?.attr("src")

                if (!videoSrc.isNullOrBlank() && !isStreamingUrl(videoSrc)) {
                    val fullUrl = if (videoSrc.startsWith("http")) videoSrc else "$baseUrl$videoSrc"
                    callback.invoke(
                        newExtractorLink(name, name, fullUrl, ExtractorLinkType.VIDEO) {
                            this.referer = baseUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }

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
            } catch (e: Exception) {
                Log.d(name, "Page fetch: ${e.message}")
            }

            try {
                val apiResponse = app.post(
                    "$baseUrl/api/source/$hash",
                    data = mapOf("hash" to hash),
                    referer = url
                )
                val json = JSONObject(apiResponse.text)
                val fileUrl = json.optString("url", "")
                    .ifBlank { json.optString("file", "") }

                if (fileUrl.isNotBlank() && !isStreamingUrl(fileUrl)) {
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
 * Handles evid and svid pages to extract server download links
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

            val slug = Regex("""/(evid|svid)/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(2)
                ?: url.substringAfterLast("/")

            val pageUrl = if (url.contains("/evid/")) {
                "$mainUrl/evid/$slug"
            } else if (url.contains("/svid/")) {
                "$mainUrl/svid/$slug"
            } else {
                url
            }

            Log.d(name, "Fetching page: $pageUrl")
            val doc = app.get(pageUrl, referer = referer ?: mainUrl).document

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
                                this.referer = pageUrl
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
                        this.referer = pageUrl
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
