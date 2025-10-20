package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

/**
 * CherryExtractor - Movierulzhd की primary video hosting service
 * 
 * Host: cherry.upns.online
 * Technology: Vidstack Player v16.5.3
 * Features:
 *   - Dynamic video source loading
 *   - HLS (m3u8) and MP4 support
 *   - Obfuscated JavaScript
 *   - WebView-based extraction required
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
            Log.d("CherryExtractor", "Extracting from: $url")
            
            // Step 1: Use WebView to load page and trigger video initialization
            val response = app.get(
                url = url,
                referer = referer ?: mainUrl,
                interceptor = WebViewResolver(
                    Regex("""(https?://[^\s"']+\.(m3u8|mp4|mkv)[^\s"']*)""")
                )
            )
            
            val finalUrl = response.url
            Log.d("CherryExtractor", "Final URL: $finalUrl")
            
            // Step 2: Check if we got a direct video URL
            when {
                finalUrl.contains(".m3u8") -> {
                    Log.d("CherryExtractor", "Found HLS stream: $finalUrl")
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = finalUrl,
                        referer = url,
                        headers = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to url
                        )
                    ).forEach(callback)
                }
                finalUrl.contains(".mp4") || finalUrl.contains(".mkv") -> {
                    Log.d("CherryExtractor", "Found direct video: $finalUrl")
                    callback.invoke(
                        com.lagradost.cloudstream3.utils.newExtractorLink(
                            source = name,
                            name = name,
                            url = finalUrl
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                else -> {
                    // Step 3: Fallback - Parse HTML for video sources
                    val document = app.get(url, referer = referer ?: mainUrl).document
                    
                    // Look for video/source tags
                    document.select("video source, video").forEach { element ->
                        val src = element.attr("src")
                        if (src.isNotEmpty() && (src.contains(".m3u8") || src.contains(".mp4"))) {
                            Log.d("CherryExtractor", "Found video in HTML: $src")
                            
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
                        }
                    }
                    
                    // Look for URLs in JavaScript
                    document.select("script").forEach { script ->
                        val scriptData = script.data()
                        
                        // Pattern 1: Look for m3u8 URLs
                        val m3u8Regex = """(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""".toRegex()
                        m3u8Regex.findAll(scriptData).forEach { match ->
                            val m3u8Url = match.groupValues[1]
                                .replace("\\\\", "\\")  // Fix escaped backslashes
                                .replace("\\/", "/")      // Fix escaped forward slashes
                            
                            Log.d("CherryExtractor", "Found m3u8 in script: $m3u8Url")
                            
                            M3u8Helper.generateM3u8(
                                source = name,
                                streamUrl = m3u8Url,
                                referer = url,
                                headers = mapOf("Origin" to mainUrl)
                            ).forEach(callback)
                        }
                        
                        // Pattern 2: Look for mp4/mkv URLs
                        val videoRegex = """(https?://[^\s"'<>]+\.(mp4|mkv)[^\s"'<>]*)""".toRegex()
                        videoRegex.findAll(scriptData).forEach { match ->
                            val videoUrl = match.groupValues[1]
                                .replace("\\\\", "\\")  // Fix escaped backslashes
                                .replace("\\/", "/")      // Fix escaped forward slashes
                            
                            Log.d("CherryExtractor", "Found video in script: $videoUrl")
                            
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
                    
                    Log.d("CherryExtractor", "Video extraction completed")
                }
            }
        } catch (e: Exception) {
            Log.e("CherryExtractor", "Extraction error: ${e.message}")
        }
    }
}
