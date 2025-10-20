package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

/**
 * CherryExtractor - Working implementation for cherry.upns.online
 * Extracts video URLs from Vidstack player
 */
open class CherryExtractor : ExtractorApi() {
    override val name = "Cherry"
    override val mainUrl = "https://cherry.upns.online"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("CherryExtractor", "=== START EXTRACTION ===")
        Log.d("CherryExtractor", "URL: $url")
        Log.d("CherryExtractor", "Referer: $referer")
        
        try {
            // Fetch the page
            val response = app.get(
                url = url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to (referer ?: mainUrl)
                )
            )
            
            val html = response.text
            Log.d("CherryExtractor", "Page loaded, size: ${html.length} bytes")
            
            var foundCount = 0
            
            // Method 1: Extract m3u8 URLs
            val m3u8Pattern = """(https?://[^\s"'<>\)\(\[\]{}]+\.m3u8[^\s"'<>\)\(\[\]{}]*)""".toRegex()
            m3u8Pattern.findAll(html).forEach { match ->
                val m3u8Url = match.groupValues[1].trim()
                if (m3u8Url.isNotEmpty()) {
                    Log.d("CherryExtractor", "Found M3U8: $m3u8Url")
                    try {
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = m3u8Url,
                            referer = url
                        ).forEach(callback)
                        foundCount++
                    } catch (e: Exception) {
                        Log.e("CherryExtractor", "M3U8 error: ${e.message}")
                    }
                }
            }
            
            // Method 2: Extract MP4 URLs
            val mp4Pattern = """(https?://[^\s"'<>\)\(\[\]{}]+\.mp4[^\s"'<>\)\(\[\]{}]*)""".toRegex()
            mp4Pattern.findAll(html).forEach { match ->
                val mp4Url = match.groupValues[1].trim()
                if (mp4Url.isNotEmpty()) {
                    Log.d("CherryExtractor", "Found MP4: $mp4Url")
                    try {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name MP4",
                                url = mp4Url
                            ) {
                                this.referer = url
                            }
                        )
                        foundCount++
                    } catch (e: Exception) {
                        Log.e("CherryExtractor", "MP4 error: ${e.message}")
                    }
                }
            }
            
            // Method 3: Check for any video file extensions
            if (foundCount == 0) {
                Log.d("CherryExtractor", "No direct URLs found, checking for any video patterns...")
                val anyVideoPattern = """(https?://[^\s"'<>]+\.(m3u8|mp4|mkv|ts|mpd)[^\s"'<>]*)""".toRegex()
                anyVideoPattern.findAll(html).forEach { match ->
                    val videoUrl = match.groupValues[1].replace("\\/", "/").trim()
                    if (videoUrl.isNotEmpty()) {
                        Log.d("CherryExtractor", "Found video URL: $videoUrl")
                        try {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = name,
                                    url = videoUrl
                                ) {
                                    this.referer = url
                                }
                            )
                            foundCount++
                        } catch (e: Exception) {
                            Log.e("CherryExtractor", "Video URL error: ${e.message}")
                        }
                    }
                }
            }
            
            Log.d("CherryExtractor", "=== EXTRACTION COMPLETE ===")
            Log.d("CherryExtractor", "Total links found: $foundCount")
            
        } catch (e: Exception) {
            Log.e("CherryExtractor", "=== EXTRACTION FAILED ===")
            Log.e("CherryExtractor", "Error: ${e.message}")
            e.printStackTrace()
        }
    }
}
