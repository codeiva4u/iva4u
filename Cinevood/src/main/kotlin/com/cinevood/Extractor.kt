package com.cinevood

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

// Utility function to get base URL
fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (_: Exception) {
        ""
    }
}

// Hubcloud Extractor - Updated with gamerxyt.com support
class Hubcloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.foo"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("Hubcloud", "Starting extraction for: $url")
        
        // Validate URL
        val realUrl = url.takeIf {
            try { java.net.URI(it).toURL(); true } catch (_: Exception) { false }
        } ?: return

        try {
            // Step 1: Get the hubcloud.php URL from drive page
            val hubcloudPhpUrl = if (realUrl.contains("hubcloud.php")) {
                realUrl
            } else if (realUrl.contains("gamerxyt.com")) {
                realUrl
            } else {
                // Get download button href from drive page
                val driveDoc = app.get(realUrl).document
                val downloadHref = driveDoc.selectFirst("#download")?.attr("href")
                    ?: driveDoc.selectFirst("a[href*=hubcloud.php]")?.attr("href")
                    ?: driveDoc.selectFirst("a[href*=gamerxyt.com]")?.attr("href")
                
                if (downloadHref.isNullOrBlank()) {
                    Log.e("Hubcloud", "No download button found")
                    return
                }
                
                Log.d("Hubcloud", "Found download href: $downloadHref")
                downloadHref
            }
            
            // Step 2: Parse the hubcloud.php/gamerxyt page for download links
            Log.d("Hubcloud", "Fetching PHP page: $hubcloudPhpUrl")
            val phpDoc = app.get(hubcloudPhpUrl).document
            
            // Extract file info
            val header = phpDoc.selectFirst("div.card-header, h2.title, .file-name")?.text().orEmpty()
            val size = phpDoc.selectFirst("i#size, .file-size, span:contains(Size)")?.text().orEmpty()
            val quality = getIndexQuality(header)
            val labelExtras = if (size.isNotEmpty()) "[$size]" else ""
            
            Log.d("Hubcloud", "File: $header, Size: $size")
            
            // Extract all download links from buttons
            val downloadButtons = phpDoc.select("a.btn, a[href*=cdn.fsl], a[href*=fsl.firecdn], a[href*=pixel], a[href*=pixeldrain], a[href*=cloudserver], a[href*=hubcdn]")
            
            Log.d("Hubcloud", "Found ${downloadButtons.size} download buttons")
            
            downloadButtons.forEach { element ->
                val link = element.attr("href")
                val text = element.text().trim()
                
                if (link.isBlank() || link.startsWith("#") || link.contains("google.com/search")) {
                    return@forEach
                }
                
                Log.d("Hubcloud", "Processing button: $text -> $link")
                
                when {
                    // FSLv2 Server
                    text.contains("FSLv2", ignoreCase = true) || link.contains("fsl-buckets", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "$name [FSLv2]",
                                "$name [FSLv2] $labelExtras",
                                link,
                            ) {
                                this.referer = hubcloudPhpUrl
                                this.quality = quality
                            }
                        )
                    }
                    
                    // FSL Server
                    text.contains("FSL Server", ignoreCase = true) || link.contains("fsl.firecdn", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "$name [FSL]",
                                "$name [FSL] $labelExtras",
                                link,
                            ) {
                                this.referer = hubcloudPhpUrl
                                this.quality = quality
                            }
                        )
                    }
                    
                    // 10Gbps Server - needs redirect following
                    text.contains("10Gbps", ignoreCase = true) || link.contains("pixel.hubcdn", ignoreCase = true) -> {
                        try {
                            val finalLink = followRedirectsForLink(link)
                            if (finalLink.isNotBlank()) {
                                callback.invoke(
                                    newExtractorLink(
                                        "$name [10Gbps]",
                                        "$name [10Gbps] $labelExtras",
                                        finalLink,
                                    ) {
                                        this.referer = hubcloudPhpUrl
                                        this.quality = quality
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("Hubcloud", "10Gbps redirect error: ${e.message}")
                        }
                    }
                    
                    // Pixeldrain
                    text.contains("Pixel", ignoreCase = true) || link.contains("pixeldrain", ignoreCase = true) -> {
                        val baseUrlLink = getBaseUrl(link)
                        val finalURL = if (link.contains("download", true)) {
                            link
                        } else {
                            "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"
                        }
                        callback.invoke(
                            newExtractorLink(
                                "Pixeldrain",
                                "Pixeldrain $labelExtras",
                                finalURL,
                            ) {
                                this.referer = hubcloudPhpUrl
                                this.quality = quality
                            }
                        )
                    }
                    
                    // ZipDisk Server
                    text.contains("ZipDisk", ignoreCase = true) || link.contains("cloudserver", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "$name [ZipDisk]",
                                "$name [ZipDisk] $labelExtras",
                                link,
                            ) {
                                this.referer = hubcloudPhpUrl
                                this.quality = quality
                            }
                        )
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("Hubcloud", "Extraction error: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private suspend fun followRedirectsForLink(link: String): String {
        var currentLink = link
        var redirectCount = 0
        val maxRedirects = 5
        
        while (redirectCount < maxRedirects) {
            val response = app.get(currentLink, allowRedirects = false)
            val redirectUrl = response.headers["location"]
            
            if (redirectUrl == null) {
                return currentLink
            }
            
            // Check if final link is in the redirect URL
            if ("link=" in redirectUrl) {
                return redirectUrl.substringAfter("link=")
            }
            
            currentLink = if (redirectUrl.startsWith("http")) {
                redirectUrl
            } else {
                getBaseUrl(currentLink) + redirectUrl
            }
            redirectCount++
        }
        
        return currentLink
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value
    }
}


// Filepress Extractor - WebView-based with network monitoring for React SPA
class Filepress : ExtractorApi() {
    override val name = "Filepress"
    override val mainUrl = "https://filepress.cloud"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("Filepress", "Starting extraction for: $url")
        
        try {
            val baseUrl = getBaseUrl(url)
            
            // Step 1: Get file ID from URL
            val fileId = when {
                url.contains("/file/") -> url.substringAfter("/file/").substringBefore("?").substringBefore("/")
                url.contains("/video/") -> url.substringAfter("/video/").substringBefore("?").substringBefore("/")
                else -> url.substringAfterLast("/").substringBefore("?")
            }
            
            if (fileId.isBlank()) {
                Log.e("Filepress", "Could not extract file ID")
                return
            }
            
            Log.d("Filepress", "File ID: $fileId")
            
            // Step 2: Navigate to video page (this has the stream buttons)
            val videoUrl = "$baseUrl/video/$fileId"
            Log.d("Filepress", "Using video page: $videoUrl")
            
            // Method 1: Try to extract stream URLs from page source first (faster)
            try {
                val videoDoc = app.get(videoUrl).document
                val pageHtml = videoDoc.html()
                val scripts = videoDoc.select("script").map { it.html() }
                
                // Look for embedded stream URLs in JavaScript/JSON
                val allScriptContent = scripts.joinToString("\n")
                
                // Pattern 1: Direct StreamWish/DoodStream URLs in JSON or variables
                val streamUrlPatterns = listOf(
                    // StreamWish variants
                    Regex("""["']?(https?://(?:[^"'\s]*\.)?(?:streamwish|swhoi|wishembed|awish|streamwish\.to|streamwish\.com|embedwish)[^"'\s]*/(?:e|d|v)/[a-zA-Z0-9]+[^"'\s]*)["']?"""),
                    // DoodStream variants  
                    Regex("""["']?(https?://(?:[^"'\s]*\.)?(?:dood|doodstream|ds2play|doods|dood\.to|dood\.watch|dood\.pm)[^"'\s]*/(?:e|d)/[a-zA-Z0-9]+[^"'\s]*)["']?"""),
                )
                
                for (pattern in streamUrlPatterns) {
                    val matches = pattern.findAll(allScriptContent + pageHtml)
                    for (match in matches) {
                        val streamUrl = match.groupValues[1].trim('"', '\'', ',', ' ')
                        if (streamUrl.isNotBlank() && streamUrl.startsWith("http")) {
                            Log.d("Filepress", "Found stream URL in scripts: $streamUrl")
                            when {
                                streamUrl.contains("streamwish", ignoreCase = true) ||
                                streamUrl.contains("swhoi", ignoreCase = true) ||
                                streamUrl.contains("wishembed", ignoreCase = true) -> {
                                    loadExtractor(streamUrl, videoUrl, subtitleCallback, callback)
                                }
                                streamUrl.contains("dood", ignoreCase = true) -> {
                                    loadExtractor(streamUrl, videoUrl, subtitleCallback, callback)
                                }
                            }
                        }
                    }
                }
                
                // Check for iframes
                videoDoc.select("iframe[src]").forEach { iframe ->
                    val iframeSrc = iframe.attr("src").let { src ->
                        when {
                            src.startsWith("//") -> "https:$src"
                            src.startsWith("/") -> "$baseUrl$src"
                            else -> src
                        }
                    }
                    
                    if (iframeSrc.isNotBlank() && 
                        !iframeSrc.contains("google") && 
                        !iframeSrc.contains("facebook") &&
                        (iframeSrc.contains("streamwish", ignoreCase = true) || 
                         iframeSrc.contains("dood", ignoreCase = true) ||
                         iframeSrc.contains("swhoi", ignoreCase = true))) {
                        Log.d("Filepress", "Found stream iframe: $iframeSrc")
                        loadExtractor(iframeSrc, videoUrl, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                Log.e("Filepress", "Script extraction failed: ${e.message}")
            }
            
            // Method 2: Fallback - Try /file/ page for direct links
            try {
                val filePageUrl = "$baseUrl/file/$fileId"
                val fileDoc = app.get(filePageUrl).document
                
                // Sometimes file page has direct download links or watch links
                fileDoc.select("a[href*=streamwish], a[href*=dood], a[href*=swhoi]").forEach { link ->
                    val href = link.attr("href")
                    if (href.isNotBlank()) {
                        Log.d("Filepress", "Found link on file page: $href")
                        loadExtractor(href, filePageUrl, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                Log.d("Filepress", "File page check failed: ${e.message}")
            }
            
            // Method 3: Check API endpoints (some React apps expose these)
            try {
                // Try common API patterns
                val apiPatterns = listOf(
                    "$baseUrl/api/file/$fileId",
                    "$baseUrl/api/video/$fileId",
                    "$baseUrl/api/stream/$fileId"
                )
                
                for (apiUrl in apiPatterns) {
                    try {
                        val apiResponse = app.get(apiUrl).text
                        // Look for stream URLs in JSON response
                        val streamUrlPatterns = listOf(
                            Regex("""["']?(?:url|link|stream|video)["']?\s*:\s*["'](https?://[^"']+)["']"""),
                            Regex("""(https?://(?:[^"'\s]*\.)?(?:streamwish|swhoi|dood)[^"'\s]*/(?:e|d)/[a-zA-Z0-9]+)""")
                        )
                        
                        for (pattern in streamUrlPatterns) {
                            val matches = pattern.findAll(apiResponse)
                            for (match in matches) {
                                val streamUrl = match.groupValues[1]
                                if (streamUrl.isNotBlank()) {
                                    Log.d("Filepress", "Found stream URL in API: $streamUrl")
                                    loadExtractor(streamUrl, videoUrl, subtitleCallback, callback)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Skip failed API calls
                    }
                }
            } catch (e: Exception) {
                Log.d("Filepress", "API check failed: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e("Filepress", "Extraction error: ${e.message}")
            e.printStackTrace()
        }
    }
}
