package com.multimovies

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder

// ═══════════════════════════════════════════════════════════════════════════════
// HELPER FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════════

fun getIndexQuality(str: String?): Int {
    return Regex("""(\d{3,4})[pP]""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

fun getBaseUrl(url: String): String {
    return URI(url).let {
        "${it.scheme}://${it.host}"
    }
}

fun parseSizeToMB(sizeStr: String): Double {
    val cleanSize = sizeStr.replace("[", "").replace("]", "").trim()
    val regex = Regex("""([\d.]+)\s*(GB|MB|gb|mb)""", RegexOption.IGNORE_CASE)
    val match = regex.find(cleanSize) ?: return Double.MAX_VALUE
    val value = match.groupValues[1].toDoubleOrNull() ?: return Double.MAX_VALUE
    val unit = match.groupValues[2].uppercase()
    return when (unit) {
        "GB" -> value * 1024
        "MB" -> value
        else -> Double.MAX_VALUE
    }
}

/**
 * Quality Priority System for Cloudstream
 * Priority Order: X264 1080p > X264 720p > HEVC 1080p > HEVC 720p > Others
 * Within same quality: Smaller file size preferred
 * Within same size: Faster server preferred
 */
fun getAdjustedQuality(quality: Int, sizeStr: String, serverName: String = "", fileName: String = ""): Int {
    val text = (fileName + sizeStr + serverName).lowercase()
    
    // Detect codec: X264 vs HEVC
    val isHEVC = text.contains("hevc") || text.contains("x265") || text.contains("h265") || text.contains("h.265")
    val isX264 = text.contains("x264") || text.contains("h264") || text.contains("h.264")
    
    // Priority 1: Codec + Quality Group (10,000 points per group)
    val codecQualityScore = when {
        isX264 && quality >= 1080 -> 30000  // Group 1: X264 1080p (30000-30999)
        isX264 && quality >= 720  -> 20000  // Group 2: X264 720p (20000-20999)
        isHEVC && quality >= 1080 -> 10000  // Group 3: HEVC 1080p (10000-10999)
        isHEVC && quality >= 720  -> 9000   // Group 4: HEVC 720p
        quality >= 1080 -> 8000             // Unknown 1080p
        quality >= 720  -> 7000             // Unknown 720p
        quality >= 480  -> 6000             // 480p
        else -> 5000                        // Unknown
    }
    
    // Priority 2: File Size (max 300 points within group - smaller is better)
    val sizeMB = parseSizeToMB(sizeStr)
    val sizeScore = when {
        sizeMB <= 300  -> 260
        sizeMB <= 400  -> 250
        sizeMB <= 500  -> 240
        sizeMB <= 600  -> 230
        sizeMB <= 700  -> 220
        sizeMB <= 800  -> 210
        sizeMB <= 900  -> 200
        sizeMB <= 1000 -> 190
        sizeMB <= 1200 -> 170
        sizeMB <= 1500 -> 140
        sizeMB <= 2000 -> 100
        sizeMB <= 2500 -> 60
        sizeMB <= 3000 -> 20
        else -> 0
    }
    
    // Priority 3: Server Speed (max 100 points)
    val serverScore = getServerPriority(serverName)
    
    return codecQualityScore + sizeScore + serverScore
}

fun getServerPriority(serverName: String): Int {
    return when {
        serverName.contains("Instant", true) -> 100
        serverName.contains("Direct", true) -> 95
        serverName.contains("premilkyway", true) -> 95
        serverName.contains("FSL", true) -> 80
        serverName.contains("10Gbps", true) -> 85
        serverName.contains("Download File", true) -> 70
        else -> 50
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MULTIMOVIESSHG EXTRACTOR - For Movies (multimoviesshg.com)
// Uses WebViewResolver to bypass reCAPTCHA and extract direct MP4 links
// ═══════════════════════════════════════════════════════════════════════════════
open class Multimoviesshg : ExtractorApi() {
    override val name: String = "Multimoviesshg"
    override val mainUrl: String = "https://multimoviesshg.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Multimoviesshg", "Processing URL: $url")
            
            // Extract video ID from embed URL
            // Format: https://multimoviesshg.com/e/{videoId} or /f/{videoId}
            val videoId = extractVideoId(url)
            
            if (videoId.isEmpty()) {
                Log.e("Multimoviesshg", "Failed to extract video ID from URL: $url")
                return
            }
            
            Log.d("Multimoviesshg", "Extracted video ID: $videoId")
            
            // Navigate to download page: /f/{id}
            val downloadPageUrl = "$mainUrl/f/$videoId"
            Log.d("Multimoviesshg", "Fetching download page: $downloadPageUrl")
            
            val downloadDoc = app.get(downloadPageUrl, referer = referer).document
            
            // Method 1: Extract quality links and process them
            val qualityLinks = downloadDoc.select("a.downloadv-item[href]")
            
            if (qualityLinks.isNotEmpty()) {
                Log.d("Multimoviesshg", "Found ${qualityLinks.size} quality options")
                processQualityLinks(qualityLinks, downloadPageUrl, referer, callback)
            } else {
                // Method 2: Try direct extraction from current page
                Log.w("Multimoviesshg", "No quality links found, trying direct extraction")
                tryDirectExtraction(downloadDoc, videoId, referer, callback)
            }
            
        } catch (e: Exception) {
            Log.e("Multimoviesshg", "Error in getUrl: ${e.message}")
        }
    }
    
    private suspend fun processQualityLinks(
        qualityLinks: org.jsoup.select.Elements,
        downloadPageUrl: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        for (qualityLink in qualityLinks) {
            try {
                val qualityHref = qualityLink.attr("href")
                val qualityText = qualityLink.text()
                
                if (qualityHref.isEmpty()) continue
                
                // Extract quality info from text (e.g., "Full HD quality 1920x1080 3.3 GB")
                val qualityInfo = extractQualityInfo(qualityText)
                
                Log.d("Multimoviesshg", "Processing quality: ${qualityInfo.qualityName} - ${qualityInfo.size}")
                
                // Get full URL for quality page
                val fullQualityUrl = if (qualityHref.startsWith("http")) qualityHref 
                                     else "$mainUrl$qualityHref"
                
                // METHOD: Use WebViewResolver to bypass reCAPTCHA and get final download link
                try {
                    Log.d("Multimoviesshg", "Using WebViewResolver for: $fullQualityUrl")
                    
                    val response = app.get(
                        fullQualityUrl,
                        referer = downloadPageUrl,
                        interceptor = WebViewResolver(
                            // Capture final download URLs (premilkyway.com or similar CDNs)
                            Regex("""(https?://[^"'\s]+\.(?:mp4|mkv)[^"'\s]*)""")
                        )
                    )
                    
                    // Check if WebView captured the final URL
                    if (response.url.contains(".mp4") || response.url.contains(".mkv")) {
                        Log.d("Multimoviesshg", "WebView captured download URL: ${response.url}")
                        
                        val adjustedQuality = getAdjustedQuality(
                            qualityInfo.resolution,
                            qualityInfo.size,
                            "Multimoviesshg",
                            qualityInfo.qualityName
                        )
                        
                        callback.invoke(
                            ExtractorLink(
                                name,
                                "$name - ${qualityInfo.qualityName} [${qualityInfo.size}]",
                                response.url,
                                mainUrl,
                                adjustedQuality,
                                isM3u8 = false
                            )
                        )
                        
                        Log.d("Multimoviesshg", "Added WebView link: ${qualityInfo.qualityName}")
                        continue
                    }
                    
                    // Fallback: Parse the response HTML for download links
                    val doc = response.document
                    val directLink = doc.selectFirst("a.btn.btn-gr.submit-btn[href], a.submit-btn[href], .down-wrap a[href*='.mp4'], a[href*='premilkyway']")
                        ?.attr("href")
                    
                    if (!directLink.isNullOrEmpty() && directLink.startsWith("http")) {
                        Log.d("Multimoviesshg", "Found direct link in HTML: $directLink")
                        
                        val adjustedQuality = getAdjustedQuality(
                            qualityInfo.resolution,
                            qualityInfo.size,
                            "Multimoviesshg",
                            qualityInfo.qualityName
                        )
                        
                        callback.invoke(
                            ExtractorLink(
                                name,
                                "$name - ${qualityInfo.qualityName} [${qualityInfo.size}]",
                                directLink,
                                mainUrl,
                                adjustedQuality,
                                isM3u8 = false
                            )
                        )
                        
                        Log.d("Multimoviesshg", "Added HTML link: ${qualityInfo.qualityName}")
                    }
                    
                } catch (e: Exception) {
                    Log.e("Multimoviesshg", "WebViewResolver error for ${qualityInfo.qualityName}: ${e.message}")
                }
                
            } catch (e: Exception) {
                Log.e("Multimoviesshg", "Error processing quality link: ${e.message}")
            }
        }
    }
    
    private fun extractVideoId(url: String): String {
        // Handle various URL formats:
        // /e/{id}, /f/{id}, /f/{id}_h, /f/{id}_n, /f/{id}_l
        val patterns = listOf(
            Regex("""/e/([a-zA-Z0-9]+)"""),
            Regex("""/f/([a-zA-Z0-9]+)(?:_[hnl])?"""),
            Regex("""multimoviesshg\.com/(?:e|f)/([a-zA-Z0-9]+)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return ""
    }
    
    private data class QualityInfo(
        val qualityName: String,
        val resolution: Int,
        val size: String
    )
    
    private fun extractQualityInfo(text: String): QualityInfo {
        // Parse text like "Full HD quality 1920x1080 3.3 GB"
        val resolutionMatch = Regex("""(\d{3,4})x(\d{3,4})""").find(text)
        val sizeMatch = Regex("""([\d.]+\s*(?:GB|MB))""", RegexOption.IGNORE_CASE).find(text)
        
        val resolution = resolutionMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val size = sizeMatch?.value ?: ""
        
        val qualityName = when {
            resolution >= 1080 -> "1080p"
            resolution >= 720 -> "720p"
            resolution >= 480 -> "480p"
            text.contains("Full HD", true) -> "1080p"
            text.contains("HD", true) -> "720p"
            else -> "Unknown"
        }
        
        return QualityInfo(qualityName, resolution, size)
    }
    
    private suspend fun tryDirectExtraction(
        doc: Document,
        videoId: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        // Try to find direct download link in the current page
        val directLink = doc.selectFirst("a[href*='.mp4'], a[href*='download'], a.submit-btn[href]")
            ?.attr("href")
        
        if (!directLink.isNullOrEmpty() && directLink.startsWith("http")) {
            callback.invoke(
                ExtractorLink(
                    name,
                    name,
                    directLink,
                    mainUrl,
                    Qualities.Unknown.value,
                    isM3u8 = false
                )
            )
            Log.d("Multimoviesshg", "Added direct link from fallback extraction")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SERVER1UNSBIO EXTRACTOR - For TV Shows (server1.uns.bio)
// Uses WebViewResolver to capture JavaScript-generated download links
// ═══════════════════════════════════════════════════════════════════════════════
open class Server1UnsBio : ExtractorApi() {
    override val name: String = "Server1UnsBio"
    override val mainUrl: String = "https://server1.uns.bio"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Server1UnsBio", "Processing URL: $url")
            
            // Extract hash from URL
            // Format: https://server1.uns.bio/#kndabz or with &dl=1
            val hash = extractHash(url)
            
            if (hash.isEmpty()) {
                Log.e("Server1UnsBio", "Failed to extract hash from URL: $url")
                return
            }
            
            Log.d("Server1UnsBio", "Extracted hash: $hash")
            
            // Construct the download URL with dl=1 parameter
            val downloadUrl = "$mainUrl/#$hash&dl=1"
            
            // METHOD 1: Use WebViewResolver to capture dynamically generated download link
            try {
                Log.d("Server1UnsBio", "Using WebViewResolver for: $downloadUrl")
                
                val response = app.get(
                    downloadUrl,
                    referer = referer,
                    interceptor = WebViewResolver(
                        // Capture download URLs (IP-based servers or domain-based CDNs)
                        Regex("""(https?://[^"'\s]+/download[^"'\s]*|https?://[\d.]+/[^"'\s]+\.mp4[^"'\s]*)""")
                    )
                )
                
                // Check if WebView captured the final download URL
                if (response.url.contains("/download") || response.url.contains(".mp4")) {
                    Log.d("Server1UnsBio", "WebView captured download URL: ${response.url}")
                    
                    // Decode URL if needed
                    val finalUrl = try {
                        URLDecoder.decode(response.url, "UTF-8")
                    } catch (e: Exception) {
                        response.url
                    }
                    
                    // Extract title from URL parameter or use hash
                    val title = extractTitleFromUrl(finalUrl) ?: "Episode $hash"
                    val quality = getIndexQuality(title)
                    val adjustedQuality = getAdjustedQuality(quality, "", "Server1UnsBio", title)
                    
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "$name - $title",
                            finalUrl,
                            mainUrl,
                            adjustedQuality,
                            isM3u8 = false
                        )
                    )
                    
                    Log.d("Server1UnsBio", "Added WebView link: $finalUrl")
                    return
                }
                
            } catch (e: Exception) {
                Log.e("Server1UnsBio", "WebViewResolver error: ${e.message}")
            }
            
            // METHOD 2: Parse HTML for download link
            try {
                val doc = app.get(downloadUrl, referer = referer).document
                
                val title = doc.selectFirst("title")?.text() ?: ""
                val h1Title = doc.selectFirst("h1")?.text() ?: title
                
                Log.d("Server1UnsBio", "Video title: $h1Title")
                
                val downloadLink = extractDownloadLink(doc)
                
                if (downloadLink != null) {
                    val quality = getIndexQuality(h1Title)
                    val adjustedQuality = getAdjustedQuality(quality, "", "Server1UnsBio", h1Title)
                    
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "$name - $h1Title",
                            downloadLink,
                            mainUrl,
                            adjustedQuality,
                            isM3u8 = false
                        )
                    )
                    
                    Log.d("Server1UnsBio", "Added HTML download link: $downloadLink")
                }
                
            } catch (e: Exception) {
                Log.e("Server1UnsBio", "HTML parsing error: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e("Server1UnsBio", "Error in getUrl: ${e.message}")
        }
    }
    
    private fun extractHash(url: String): String {
        // Handle various formats:
        // /#hash, /#hash&dl=1, #hash
        val patterns = listOf(
            Regex("""#([a-zA-Z0-9]+)(?:&|$)"""),
            Regex("""uns\.bio/#([a-zA-Z0-9]+)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return ""
    }
    
    private fun extractDownloadLink(doc: Document): String? {
        // Try various selectors to find the download link
        val selectors = listOf(
            "a.downloader-button[href*='download'][download]",
            "a[href*='/download?title=']",
            "a[href*='.mp4/download']",
            "a.downloader-button[href*='.mp4']",
            "a[href*='://'][href*='.mp4']"
        )
        
        for (selector in selectors) {
            val link = doc.selectFirst(selector)?.attr("href")
            if (!link.isNullOrEmpty() && link.startsWith("http")) {
                return link
            }
        }
        
        // Try to find in script tags
        val scripts = doc.select("script").map { it.html() }
        for (script in scripts) {
            val linkMatch = Regex("""href['":\s]+["']?(https?://[^"'\s>]+\.mp4[^"'\s>]*)""").find(script)
            if (linkMatch != null) {
                return linkMatch.groupValues[1]
            }
        }
        
        return null
    }
    
    private fun extractTitleFromUrl(url: String): String? {
        val match = Regex("""title=([^&]+)""").find(url)
        return match?.groupValues?.get(1)?.let { 
            URLDecoder.decode(it, "UTF-8")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// TECHINMINDSPACE EXTRACTOR - For Intermediate Player (ssn.techinmind.space)
// Routes to appropriate downstream extractor
// ═══════════════════════════════════════════════════════════════════════════════
open class TechInMindSpace : ExtractorApi() {
    override val name: String = "TechInMindSpace"
    override val mainUrl: String = "https://ssn.techinmind.space"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("TechInMindSpace", "Processing URL: $url")
            
            val doc = app.get(url, referer = referer, allowRedirects = true).document
            
            // Find the video iframe
            val iframeSrc = doc.selectFirst("iframe#vidFrame, iframe#player, iframe[src*='multimoviesshg'], iframe[src*='uns.bio']")
                ?.attr("src") ?: ""
            
            if (iframeSrc.isEmpty()) {
                Log.w("TechInMindSpace", "No iframe found in page")
                return
            }
            
            Log.d("TechInMindSpace", "Found iframe: $iframeSrc")
            
            // Route to appropriate extractor based on iframe URL
            when {
                iframeSrc.contains("multimoviesshg.com", ignoreCase = true) -> {
                    Log.d("TechInMindSpace", "Routing to Multimoviesshg")
                    Multimoviesshg().getUrl(iframeSrc, url, subtitleCallback, callback)
                }
                iframeSrc.contains("uns.bio", ignoreCase = true) -> {
                    Log.d("TechInMindSpace", "Routing to Server1UnsBio")
                    Server1UnsBio().getUrl(iframeSrc, url, subtitleCallback, callback)
                }
                else -> {
                    Log.w("TechInMindSpace", "Unknown iframe source: $iframeSrc")
                }
            }
            
        } catch (e: Exception) {
            Log.e("TechInMindSpace", "Error in getUrl: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// STREAMTECHINMIND EXTRACTOR - For Main Embed (stream.techinmind.space)
// Routes to appropriate downstream extractor
// ═══════════════════════════════════════════════════════════════════════════════
open class StreamTechInMind : ExtractorApi() {
    override val name: String = "StreamTechInMind"
    override val mainUrl: String = "https://stream.techinmind.space"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("StreamTechInMind", "Processing URL: $url")
            
            val doc = app.get(url, referer = referer, allowRedirects = true).document
            
            // Find all iframes - typically there's a nested iframe structure
            val iframes = doc.select("iframe[src]")
            
            Log.d("StreamTechInMind", "Found ${iframes.size} iframes")
            
            for (iframe in iframes) {
                val iframeSrc = iframe.attr("src")
                
                if (iframeSrc.isEmpty() || iframeSrc == "about:blank") continue
                
                Log.d("StreamTechInMind", "Processing iframe: $iframeSrc")
                
                when {
                    iframeSrc.contains("ssn.techinmind.space", ignoreCase = true) -> {
                        Log.d("StreamTechInMind", "Routing to TechInMindSpace")
                        TechInMindSpace().getUrl(iframeSrc, url, subtitleCallback, callback)
                    }
                    iframeSrc.contains("multimoviesshg.com", ignoreCase = true) -> {
                        Log.d("StreamTechInMind", "Routing to Multimoviesshg")
                        Multimoviesshg().getUrl(iframeSrc, url, subtitleCallback, callback)
                    }
                    iframeSrc.contains("uns.bio", ignoreCase = true) -> {
                        Log.d("StreamTechInMind", "Routing to Server1UnsBio")
                        Server1UnsBio().getUrl(iframeSrc, url, subtitleCallback, callback)
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("StreamTechInMind", "Error in getUrl: ${e.message}")
        }
    }
}
