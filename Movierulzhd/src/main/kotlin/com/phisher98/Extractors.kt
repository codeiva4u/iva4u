package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

/**
 * CherryExtractor - Movierulzhd की primary video hosting service
 * 
 * Host: cherry.upns.online
 * Technology: Vidstack Player v16.5.3
 * 
 * Implementation Strategy:
 *   1. Enhanced WebView with better video URL detection
 *   2. Network request monitoring to capture video URLs
 *   3. Support for HLS (m3u8), DASH (mpd), and direct video (mp4/mkv)
 *   4. Ad layer bypass with proper user-agent and headers
 *   5. Multiple regex patterns for robust URL extraction
 */
open class CherryExtractor : ExtractorApi() {
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
            Log.d("CherryExtractor", "Starting extraction from: $url")
            
            // Enhanced headers for better compatibility
            val customHeaders = mapOf(
                "Accept" to "*/*",
                "Origin" to mainUrl,
                "Referer" to (referer ?: mainUrl),
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            
            // Approach 1: Enhanced WebView with multiple video format detection
            val response = app.get(
                url = url,
                referer = referer ?: mainUrl,
                headers = customHeaders,
                interceptor = WebViewResolver(
                    // Enhanced regex for better video URL detection
                    Regex("""(https?://[^\s"'<>{}\[\]]+\.(m3u8|mp4|mkv|ts|mpd)[^\s"'<>{}\[\]]*)""")
                )
            )
            
            val capturedUrl = response.url
            Log.d("CherryExtractor", "WebView captured URL: $capturedUrl")
            
            // Check if WebView captured a video URL
            if (capturedUrl.contains(".m3u8") || 
                capturedUrl.contains(".mpd") ||
                capturedUrl.contains(".mp4") || 
                capturedUrl.contains(".mkv") ||
                capturedUrl.contains(".ts")) {
                
                Log.d("CherryExtractor", "Direct video URL captured: $capturedUrl")
                
                when {
                    capturedUrl.contains(".m3u8") -> {
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = capturedUrl,
                            referer = url,
                            headers = customHeaders
                        ).forEach(callback)
                    }
                    capturedUrl.contains(".mpd") -> {
                        // DASH stream support
                        callback.invoke(
                            com.lagradost.cloudstream3.utils.newExtractorLink(
                                source = name,
                                name = "$name DASH",
                                url = capturedUrl
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                    else -> {
                        callback.invoke(
                            com.lagradost.cloudstream3.utils.newExtractorLink(
                                source = name,
                                name = name,
                                url = capturedUrl
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
                return
            }
            
            // Approach 2: Parse page HTML and JavaScript with enhanced patterns
            Log.d("CherryExtractor", "WebView didn't capture video, trying HTML parsing")
            val document = response.document
            
            // Pattern 1: Look in script tags for video URLs with enhanced patterns
            document.select("script").forEach { script ->
                val scriptData = script.data()
                
                // Skip ad-related scripts
                if (scriptData.contains("girlieturtle") || scriptData.contains("managerX")) {
                    return@forEach
                }
                
                // Enhanced URL patterns for better detection
                val patterns = listOf(
                    // Pattern 1: Standard video URLs (m3u8, mp4, mkv, mpd, ts)
                    """(https?://[^\s"'<>{}\[\]]+\.(m3u8|mp4|mkv|mpd|ts)[^\s"'<>{}\[\]]*)["\'\s<>]""".toRegex(),
                    // Pattern 2: Escaped URLs in JavaScript
                    """["']([^"']*\.(m3u8|mp4|mkv|mpd|ts)[^"']*)["']""".toRegex(),
                    // Pattern 3: URL constructor patterns
                    """url[:\s]*["']([^"']+)["']""".toRegex(),
                    // Pattern 4: Source/src patterns
                    """(?:source|src)[:\s]*["']([^"']+\.(m3u8|mp4|mkv|mpd|ts)[^"']*)["']""".toRegex(),
                    // Pattern 5: Vidstack player specific
                    """(?:streamUrl|videoUrl|file)[:\s]*["']([^"']+)["']""".toRegex(),
                    // Pattern 6: HLS/DASH manifest patterns
                    """(?:manifest|playlist)[:\s]*["']([^"']+\.(m3u8|mpd)[^"']*)["']""".toRegex()
                )
                
                patterns.forEach { pattern ->
                    pattern.findAll(scriptData).forEach { match ->
                        val videoUrl = match.groupValues[1]
                            .replace("\\\\", "|")    // Replace escaped backslashes temporarily
                            .replace("\\/", "/")       // Fix escaped forward slashes
                            .replace("|", "\\")        // Restore backslashes
                            .trim()
                        
                        // Validate URL - enhanced to support more formats
                        if (videoUrl.startsWith("http") && 
                            (videoUrl.contains(".m3u8") || 
                             videoUrl.contains(".mpd") ||
                             videoUrl.contains(".mp4") || 
                             videoUrl.contains(".mkv") ||
                             videoUrl.contains(".ts"))) {
                            
                            Log.d("CherryExtractor", "Found video URL in script: $videoUrl")
                            
                            try {
                                when {
                                    videoUrl.contains(".m3u8") -> {
                                        M3u8Helper.generateM3u8(
                                            source = name,
                                            streamUrl = videoUrl,
                                            referer = url,
                                            headers = customHeaders
                                        ).forEach(callback)
                                    }
                                    videoUrl.contains(".mpd") -> {
                                        // DASH stream
                                        callback.invoke(
                                            com.lagradost.cloudstream3.utils.newExtractorLink(
                                                source = name,
                                                name = "$name DASH",
                                                url = videoUrl
                                            ) {
                                                this.referer = url
                                                this.quality = Qualities.Unknown.value
                                            }
                                        )
                                    }
                                    else -> {
                                        // Direct video (mp4, mkv, ts)
                                        callback.invoke(
                                            com.lagradost.cloudstream3.utils.newExtractorLink(
                                                source = name,
                                                name = name,
                                                url = videoUrl
                                            ) {
                                                this.referer = url
                                                this.quality = Qualities.Unknown.value
                                            }
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("CherryExtractor", "Error processing URL $videoUrl: ${e.message}")
                            }
                        }
                    }
                }
            }
            
            // Pattern 2: Check video/source elements with enhanced format support
            document.select("video source, video").forEach { element ->
                val src = element.attr("src").takeIf { it.isNotEmpty() }
                if (src != null && (src.contains(".m3u8") || src.contains(".mpd") || src.contains(".mp4"))) {
                    Log.d("CherryExtractor", "Found video in HTML element: $src")
                    
                    try {
                        when {
                            src.contains(".m3u8") -> {
                                M3u8Helper.generateM3u8(
                                    source = name,
                                    streamUrl = src,
                                    referer = url,
                                    headers = customHeaders
                                ).forEach(callback)
                            }
                            src.contains(".mpd") -> {
                                callback.invoke(
                                    com.lagradost.cloudstream3.utils.newExtractorLink(
                                        source = name,
                                        name = "$name DASH",
                                        url = src
                                    ) {
                                        this.referer = url
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                            else -> {
                                callback.invoke(
                                    com.lagradost.cloudstream3.utils.newExtractorLink(
                                        source = name,
                                        name = name,
                                        url = src
                                    ) {
                                        this.referer = url
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CherryExtractor", "Error processing element src: ${e.message}")
                    }
                }
            }
            
            Log.d("CherryExtractor", "Extraction completed")
            
        } catch (e: Exception) {
            Log.e("CherryExtractor", "Fatal extraction error: ${e.message}")
        }
    }
}
