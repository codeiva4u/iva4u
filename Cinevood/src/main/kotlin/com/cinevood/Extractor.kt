package com.cinevood

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI

// ==================== Utility Functions ====================

fun getBaseUrl(url: String): String {
    return URI(url).let {
        "${it.scheme}://${it.host}"
    }
}

suspend fun getLatestUrl(url: String, source: String): String {
    return try {
        val link = JSONObject(
            app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json").text
        ).optString(source)
        if (link.isNullOrEmpty()) {
            getBaseUrl(url)
        } else {
            link
        }
    } catch (e: Exception) {
        getBaseUrl(url)
    }
}

// ==================== OxxFile Extractor ====================
/**
 * OxxFile Extractor - oxxfile.info से HubCloud और Filepress लिंक निकालता है
 * 
 * यह Next.js based file-sharing site है जो download buttons में
 * HubCloud और Filepress links provide करती है
 */
class OxxFileExtractor : ExtractorApi() {
    override val name = "OxxFile"
    override val mainUrl = "https://oxxfile.info"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("OxxFile", "Extracting from: $url")
            
            val document = app.get(url, referer = referer).document
            val bodyHtml = document.body().html()
            
            // Method 1: Look for HubCloud links in HTML
            val hubcloudRegex = Regex("""(https?://[^\s"'<>]*hubcloud[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
            val hubcloudMatches = hubcloudRegex.findAll(bodyHtml)
            
            for (match in hubcloudMatches) {
                val hubcloudUrl = match.groupValues[1]
                Log.d("OxxFile", "Found HubCloud URL: $hubcloudUrl")
                HubCloudExtractor().getUrl(hubcloudUrl, url, subtitleCallback, callback)
            }
            
            // Method 2: Look for Filepress links
            val filepressRegex = Regex("""(https?://[^\s"'<>]*filepress[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
            val filepressMatches = filepressRegex.findAll(bodyHtml)
            
            for (match in filepressMatches) {
                val filepressUrl = match.groupValues[1]
                Log.d("OxxFile", "Found Filepress URL: $filepressUrl")
                FilepressExtractor().getUrl(filepressUrl, url, subtitleCallback, callback)
            }
            
            // Method 3: Look for download buttons with onclick or data-link attributes
            document.select("button[onclick], a[data-link], [data-url]").forEach { element ->
                val onclick = element.attr("onclick")
                val dataLink = element.attr("data-link").ifBlank { element.attr("data-url") }
                
                val linkUrl = when {
                    dataLink.isNotBlank() -> dataLink
                    onclick.contains("hubcloud", ignoreCase = true) -> {
                        Regex("""['"]([^'"]*hubcloud[^'"]*)['""]""").find(onclick)?.groupValues?.get(1)
                    }
                    onclick.contains("filepress", ignoreCase = true) -> {
                        Regex("""['"]([^'"]*filepress[^'"]*)['""]""").find(onclick)?.groupValues?.get(1)
                    }
                    else -> null
                }
                
                linkUrl?.let { extractedUrl ->
                    when {
                        extractedUrl.contains("hubcloud", ignoreCase = true) -> {
                            Log.d("OxxFile", "Found HubCloud from button: $extractedUrl")
                            HubCloudExtractor().getUrl(extractedUrl, url, subtitleCallback, callback)
                        }
                        extractedUrl.contains("filepress", ignoreCase = true) -> {
                            Log.d("OxxFile", "Found Filepress from button: $extractedUrl")
                            FilepressExtractor().getUrl(extractedUrl, url, subtitleCallback, callback)
                        }
                    }
                }
            }
            
            // Method 4: Check for API endpoints that might return links
            val apiRegex = Regex("""['"](/api/[^'"]+)['"]""")
            apiRegex.findAll(bodyHtml).forEach { match ->
                val apiPath = match.groupValues[1]
                try {
                    val apiUrl = getBaseUrl(url) + apiPath
                    val apiResponse = app.get(apiUrl, referer = url).text
                    
                    // Parse JSON response for links
                    if (apiResponse.startsWith("{") || apiResponse.startsWith("[")) {
                        val hubcloudApiMatch = hubcloudRegex.find(apiResponse)
                        hubcloudApiMatch?.let {
                            Log.d("OxxFile", "Found HubCloud from API: ${it.groupValues[1]}")
                            HubCloudExtractor().getUrl(it.groupValues[1], url, subtitleCallback, callback)
                        }
                        
                        val filepressApiMatch = filepressRegex.find(apiResponse)
                        filepressApiMatch?.let {
                            Log.d("OxxFile", "Found Filepress from API: ${it.groupValues[1]}")
                            FilepressExtractor().getUrl(it.groupValues[1], url, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    Log.d("OxxFile", "API call failed: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            Log.e("OxxFile", "Extraction error: ${e.message}")
            e.printStackTrace()
        }
    }
}

// ==================== HubCloud Extractor ====================
/**
 * HubCloud Extractor - HubCloud से actual video/download links निकालता है
 * 
 * यह HDhub4u plugin के HubCloud extractor पर based है
 * Multiple server options (FSL, BuzzServer, Pixeldrain, etc.) support करता है
 */
class HubCloudExtractor : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.day"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("HubCloud", "Extracting from: $url")
            
            // Validate URL
            val realUrl = url.takeIf {
                try { URI(it).toURL(); true } catch (e: Exception) { false }
            } ?: return
            
            // Dynamic URL management - fetch latest hubcloud URL
            val latestUrl = getLatestUrl(realUrl, "hubcloud")
            val baseUrl = getBaseUrl(realUrl)
            val newUrl = realUrl.replace(baseUrl, latestUrl)
            
            // Get the download page URL
            val href = try {
                if ("hubcloud.php" in newUrl) {
                    newUrl
                } else {
                    val rawHref = app.get(newUrl).document.select("#download, .download-btn, a[href*='hubcloud.php']").attr("href")
                    if (rawHref.startsWith("http", ignoreCase = true)) {
                        rawHref
                    } else if (rawHref.isNotBlank()) {
                        latestUrl.trimEnd('/') + "/" + rawHref.trimStart('/')
                    } else {
                        newUrl
                    }
                }
            } catch (e: Exception) {
                Log.e("HubCloud", "Failed to extract href: ${e.message}")
                newUrl
            }
            
            val document = app.get(href).document
            val size = document.selectFirst("i#size, .file-size")?.text().orEmpty()
            val header = document.selectFirst("div.card-header, .file-name, h1, h2")?.text().orEmpty()
            
            val headerDetails = cleanTitle(header)
            val labelExtras = buildString {
                if (headerDetails.isNotEmpty()) append("[$headerDetails]")
                if (size.isNotEmpty()) append("[$size]")
            }
            val quality = getIndexQuality(header)
            
            // Extract download buttons
            document.select("div.card-body h2 a.btn, .download-links a, a.btn[href]").forEach { element ->
                val link = element.attr("href")
                val text = element.text()
                
                Log.d("HubCloud", "Processing button: $text -> $link")
                
                when {
                    text.contains("FSL Server", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "Cinevood [FSL Server]",
                                "Cinevood [FSL Server] $labelExtras",
                                link,
                            ) { this.quality = quality }
                        )
                    }
                    
                    text.contains("Download File", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "Cinevood",
                                "Cinevood $labelExtras",
                                link,
                            ) { this.quality = quality }
                        )
                    }
                    
                    text.contains("BuzzServer", ignoreCase = true) -> {
                        try {
                            val buzzResp = app.get("$link/download", referer = link, allowRedirects = false)
                            val dlink = buzzResp.headers["hx-redirect"].orEmpty()
                            if (dlink.isNotBlank()) {
                                callback.invoke(
                                    newExtractorLink(
                                        "Cinevood [BuzzServer]",
                                        "Cinevood [BuzzServer] $labelExtras",
                                        dlink,
                                    ) { this.quality = quality }
                                )
                            }
                        } catch (e: Exception) {
                            Log.w("HubCloud", "BuzzServer extraction failed: ${e.message}")
                        }
                    }
                    
                    text.contains("pixeldra", ignoreCase = true) || text.contains("pixel", ignoreCase = true) -> {
                        val baseUrlLink = getBaseUrl(link)
                        val finalURL = if (link.contains("download", true)) link
                        else "$baseUrlLink/api/file/${link.substringAfterLast("/")}"
                        
                        callback.invoke(
                            newExtractorLink(
                                "Pixeldrain",
                                "Pixeldrain $labelExtras",
                                finalURL
                            ) { this.quality = quality }
                        )
                    }
                    
                    text.contains("S3 Server", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "Cinevood [S3 Server]",
                                "Cinevood [S3 Server] $labelExtras",
                                link,
                            ) { this.quality = quality }
                        )
                    }
                    
                    text.contains("10Gbps", ignoreCase = true) -> {
                        try {
                            var currentLink = link
                            var redirectCount = 0
                            val maxRedirects = 3
                            
                            while (redirectCount < maxRedirects) {
                                val response = app.get(currentLink, allowRedirects = false)
                                val redirectUrl = response.headers["location"] ?: break
                                
                                if ("link=" in redirectUrl) {
                                    val finalLink = redirectUrl.substringAfter("link=")
                                    callback.invoke(
                                        newExtractorLink(
                                            "10Gbps [Download]",
                                            "10Gbps [Download] $labelExtras",
                                            finalLink
                                        ) { this.quality = quality }
                                    )
                                    break
                                }
                                
                                currentLink = redirectUrl
                                redirectCount++
                            }
                        } catch (e: Exception) {
                            Log.e("HubCloud", "10Gbps extraction failed: ${e.message}")
                        }
                    }
                    
                    link.isNotBlank() && (link.endsWith(".mp4") || link.endsWith(".mkv") || link.contains(".m3u8")) -> {
                        val linkType = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        callback.invoke(
                            newExtractorLink(
                                "Cinevood [Direct]",
                                "Cinevood [Direct] $labelExtras",
                                link,
                                linkType
                            ) { this.quality = quality }
                        )
                    }
                }
            }
            
            // Fallback: Find any direct video links in page
            val videoRegex = Regex("""(https?://[^\s"'<>]+\.(mp4|mkv|m3u8)[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
            videoRegex.findAll(document.body().html()).forEach { match ->
                val videoUrl = match.groupValues[1]
                val linkType = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        "Cinevood [Auto]",
                        "Cinevood [Auto] $labelExtras",
                        videoUrl,
                        linkType
                    ) { this.quality = quality }
                )
            }
            
        } catch (e: Exception) {
            Log.e("HubCloud", "Extraction error: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
    
    private fun cleanTitle(title: String): String {
        val parts = title.split(".", "-", "_")
        val qualityTags = listOf(
            "WEBRip", "WEB-DL", "WEB", "BluRay", "HDRip", "DVDRip", "HDTV",
            "CAM", "TS", "R5", "DVDScr", "BRRip", "BDRip", "DVD", "PDTV", "HD"
        )
        
        val startIndex = parts.indexOfFirst { part ->
            qualityTags.any { tag -> part.contains(tag, ignoreCase = true) }
        }
        
        return if (startIndex != -1) {
            parts.subList(startIndex, minOf(startIndex + 4, parts.size)).joinToString(".")
        } else {
            parts.takeLast(3).joinToString(".")
        }
    }
}

// ==================== Filepress Extractor ====================
/**
 * Filepress Extractor - Filepress से video source निकालता है
 * 
 * M3U8 और direct video URLs दोनों support करता है
 */
class FilepressExtractor : ExtractorApi() {
    override val name = "Filepress"
    override val mainUrl = "https://filepress.store"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Filepress", "Extracting from: $url")
            
            val document = app.get(url, referer = referer).document
            
            // Method 1: Look for video source in HTML
            val videoSrc = document.select("source[src], video source").firstOrNull()?.attr("src")
            
            if (!videoSrc.isNullOrBlank()) {
                val linkType = if (videoSrc.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        videoSrc,
                        linkType
                    ) {
                        this.referer = referer ?: url
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }
            
            // Method 2: Look for video URL in scripts
            val bodyHtml = document.body().html()
            val videoRegex = Regex("""(https?://[^\s"'<>]+\.(mp4|mkv|m3u8)[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
            
            videoRegex.findAll(bodyHtml).forEach { match ->
                val videoUrl = match.groupValues[1]
                val linkType = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        videoUrl,
                        linkType
                    ) {
                        this.referer = referer ?: url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            
            // Method 3: Check for encoded URLs
            val encodedRegex = Regex("""['"](aHR0c[A-Za-z0-9+/=]+)['"]""")
            encodedRegex.findAll(bodyHtml).forEach { match ->
                try {
                    val decoded = base64Decode(match.groupValues[1])
                    if (decoded.startsWith("http") && (decoded.contains(".mp4") || decoded.contains(".m3u8"))) {
                        val linkType = if (decoded.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name [Decoded]",
                                decoded,
                                linkType
                            ) {
                                this.referer = referer ?: url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                } catch (e: Exception) {
                    Log.d("Filepress", "Base64 decode failed: ${e.message}")
                }
            }
            
            // Method 4: Look for download button/link
            document.select("a[href*='download'], a.download-btn, button[onclick*='download']").forEach { element ->
                val downloadUrl = element.attr("href").ifBlank {
                    Regex("""['"]([^'"]+)['"]""").find(element.attr("onclick"))?.groupValues?.get(1)
                }
                
                downloadUrl?.let { dUrl ->
                    if (dUrl.startsWith("http")) {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name [Download]",
                                dUrl,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.referer = referer ?: url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("Filepress", "Extraction error: ${e.message}")
            e.printStackTrace()
        }
    }
}
