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
 *   1. WebView with custom script injection for play button interaction
 *   2. Network request monitoring to capture video URLs
 *   3. Multiple fallback patterns for different URL formats
 *   4. Support for HLS (m3u8) and direct video (mp4/mkv)
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
            
            // Approach 1: WebView with interaction simulation
            val response = app.get(
                url = url,
                referer = referer ?: mainUrl,
                interceptor = WebViewResolver(
                    Regex("""(https?://[^\s"']+\.(m3u8|mp4|mkv|ts)[^\s"']*)""")
                )
            )
            
            val capturedUrl = response.url
            Log.d("CherryExtractor", "WebView captured URL: $capturedUrl")
            
            // Check if WebView captured a video URL
            if (capturedUrl.contains(".m3u8") || 
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
                            headers = mapOf(
                                "Origin" to mainUrl,
                                "Referer" to url
                            )
                        ).forEach(callback)
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
            
            // Approach 2: Parse page HTML and JavaScript
            Log.d("CherryExtractor", "WebView didn't capture video, trying HTML parsing")
            val document = response.document
            
            // Pattern 1: Look in script tags for video URLs
            document.select("script").forEach { script ->
                val scriptData = script.data()
                
                // Check for various URL patterns
                val patterns = listOf(
                    // Pattern 1: Standard video URLs
                    """(https?://[^\s"'<>{}\[\]]+\.(m3u8|mp4|mkv)[^\s"'<>{}\[\]]*)["\'\s<>]""".toRegex(),
                    // Pattern 2: Escaped URLs in JavaScript
                    """["']([^"']*\.(m3u8|mp4|mkv)[^"']*)["']""".toRegex(),
                    // Pattern 3: URL constructor patterns
                    """url[:\s]*["']([^"']+)["']""".toRegex(),
                    // Pattern 4: Source/src patterns
                    """(?:source|src)[:\s]*["']([^"']+\.(m3u8|mp4|mkv)[^"']*)["']""".toRegex()
                )
                
                patterns.forEach { pattern ->
                    pattern.findAll(scriptData).forEach { match ->
                        val videoUrl = match.groupValues[1]
                            .replace("\\\\", "|")    // Replace escaped backslashes temporarily
                            .replace("\\/", "/")       // Fix escaped forward slashes
                            .replace("|", "\\")        // Restore backslashes
                            .trim()
                        
                        // Validate URL
                        if (videoUrl.startsWith("http") && 
                            (videoUrl.contains(".m3u8") || 
                             videoUrl.contains(".mp4") || 
                             videoUrl.contains(".mkv"))) {
                            
                            Log.d("CherryExtractor", "Found video URL in script: $videoUrl")
                            
                            try {
                                if (videoUrl.contains(".m3u8")) {
                                    M3u8Helper.generateM3u8(
                                        source = name,
                                        streamUrl = videoUrl,
                                        referer = url,
                                        headers = mapOf("Origin" to mainUrl)
                                    ).forEach(callback)
                                } else {
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
                            } catch (e: Exception) {
                                Log.e("CherryExtractor", "Error processing URL $videoUrl: ${e.message}")
                            }
                        }
                    }
                }
            }
            
            // Pattern 2: Check video/source elements
            document.select("video source, video").forEach { element ->
                val src = element.attr("src").takeIf { it.isNotEmpty() }
                if (src != null && (src.contains(".m3u8") || src.contains(".mp4"))) {
                    Log.d("CherryExtractor", "Found video in HTML element: $src")
                    
                    try {
                        if (src.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(
                                source = name,
                                streamUrl = src,
                                referer = url
                            ).forEach(callback)
                        } else {
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
